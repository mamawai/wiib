package com.mawai.wiibquant.agent.trader;

import com.mawai.wiibcommon.entity.AiTrader;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BYOK 建模：构造期不许抛（纯本地构造，不发网络请求）。 */
class TraderModelFactoryTest {

    private final ApiKeyCrypto crypto = new ApiKeyCrypto(Base64.getEncoder().encodeToString(new byte[32]));

    private TraderModelFactory factory() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ObservationRegistry> op = mock(ObjectProvider.class);
        when(op.getIfUnique(any())).thenReturn(ObservationRegistry.NOOP);
        return new TraderModelFactory(crypto, mock(ToolCallingManager.class), op);
    }

    private AiTrader trader(String protocol) {
        AiTrader t = new AiTrader();
        t.setId(1L);
        t.setApiProtocol(protocol);
        t.setBaseUrl("https://api.deepseek.com");
        t.setModel("deepseek-chat");
        t.setApiKeyEnc(crypto.encrypt("sk-fake-key"));
        return t;
    }

    /**
     * openai 协议建模：OpenAiChatModel.builder() 只给 sync client 时会拿 options 自建 async client，
     * 而 options 里没有 key → SDK 抛 "At least one credential source must be specified"。
     */
    @Test
    void openAiProtocolModelBuildsWithoutCredentialError() {
        ChatModel model = factory().modelFor(trader("openai"));

        assertThat(model).isNotNull();
    }

    @Test
    void responsesProtocolModelBuilds() {
        assertThat(factory().modelFor(trader("responses"))).isNotNull();
    }
}

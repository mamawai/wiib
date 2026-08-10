package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.entity.UserLlmConfig;
import com.mawai.wiibquant.agent.trader.ApiKeyCrypto;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BYOK 建模：构造期不发网络请求；同配置必须复用，改配置必须换新的 */
class ChatModelFactoryTest {

    /** spy 而不是裸实例：建一次模解一次密，用调用次数数出"到底真建了几次"，见 同配置命中缓存 */
    private final ApiKeyCrypto crypto =
            spy(new ApiKeyCrypto(Base64.getEncoder().encodeToString(new byte[32])));

    /**
     * 密文只加密一次全程复用。ApiKeyCrypto 是 AES-GCM 随机 IV，每次现加密的话两份配置的指纹
     * 本来就不同——下面"改配置拿到新实例""指纹覆盖全部要素"会退化成恒真：
     * 把 getModel()/getLightModel() 从 fingerprint() 里整个删掉它们照样绿。
     */
    private final String encOnce = crypto.encrypt("sk-test");

    private ChatModelFactory factory() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ObservationRegistry> op = mock(ObjectProvider.class);
        when(op.getIfUnique(any())).thenReturn(ObservationRegistry.NOOP);
        return new ChatModelFactory(crypto, mock(ToolCallingManager.class), op);
    }

    private UserLlmConfig config(String model, String lightModel) {
        UserLlmConfig c = new UserLlmConfig();
        c.setUserId(1L);
        c.setApiProtocol("openai");
        c.setBaseUrl("https://api.example.com");
        c.setModel(model);
        c.setLightModel(lightModel);
        c.setApiKeyEnc(encOnce);
        return c;
    }

    /** 同一份配置反复取必须命中缓存：既要是同一对实例，也不许背地里重建一遍再丢掉 */
    @Test
    void 同配置命中缓存() {
        ChatModelFactory f = factory();
        UserLlmConfig c = config("gpt-5", "gpt-5-mini");

        ChatModelFactory.Models first = f.modelsFor(c);

        assertThat(f.modelsFor(c).deep()).isSameAs(first.deep());
        assertThat(f.modelsFor(c).light()).isSameAs(first.light());
        // 光断实例相同抓不住漏建：把"先查缓存"那步删掉，后两次照样重新建一遍，
        // 再被 putIfAbsent 换回旧实例——断言全绿，而每轮对话都在白建 SDK 客户端。
        // 建一次模解一次密，用解密次数数真实建了几次（实测过：只留 isSameAs 那条变异是绿的）
        verify(crypto, times(1)).decrypt(anyString());
    }

    /** 改了模型名必须拿到新实例——指纹变了旧的就不该再用 */
    @Test
    void 改配置后拿到新实例() {
        ChatModelFactory f = factory();

        ChatModelFactory.Models first = f.modelsFor(config("gpt-5", "gpt-5-mini"));
        ChatModelFactory.Models second = f.modelsFor(config("gpt-5.1", "gpt-5-mini"));

        assertThat(second.deep()).isNotSameAs(first.deep());
    }

    /** 轻模型不填时直接复用深模型实例，不该白建第二个 */
    @Test
    void 轻模型不填时复用主模型实例() {
        ChatModelFactory.Models models = factory().modelsFor(config("gpt-5", null));

        assertThat(models.light()).isSameAs(models.deep());
    }

    /**
     * 指纹漏掉任何一个建模要素，都会出现"改了配置还用旧模型"。
     * 第一条正向断言（同输入同输出）不能省——没有它，"密文只算一次"这个前提本身没被验证，
     * 后面两条不等断言可能只是因为密文每次都不一样才成立。
     */
    @Test
    void 指纹覆盖全部建模要素() {
        String base = ChatModelFactory.fingerprint(config("gpt-5", "gpt-5-mini"));

        assertThat(ChatModelFactory.fingerprint(config("gpt-5", "gpt-5-mini"))).isEqualTo(base);
        assertThat(ChatModelFactory.fingerprint(config("gpt-5.1", "gpt-5-mini"))).isNotEqualTo(base);
        assertThat(ChatModelFactory.fingerprint(config("gpt-5", "other"))).isNotEqualTo(base);
    }

    /** 分隔符不能省：没有它 ("ab","c") 和 ("a","bc") 拼出同一个串，两份配置共用一个 ChatModel */
    @Test
    void 相邻字段拼接不会串味() {
        UserLlmConfig a = config("ab", "c");
        UserLlmConfig b = config("a", "bc");

        assertThat(ChatModelFactory.fingerprint(a)).isNotEqualTo(ChatModelFactory.fingerprint(b));
    }

    /**
     * 上限真的会淘汰。不是在测 JDK：putIfAbsent 走的是 HashMap.putVal，
     * 它触不触发 removeEldestEntry 得实证——不触发的话这个上限就是死的、缓存无界增长。
     */
    @Test
    void 超过上限后最久未用的被淘汰() {
        ChatModelFactory f = factory();
        UserLlmConfig oldest = config("m-0", null);
        ChatModelFactory.Models first = f.modelsFor(oldest);

        for (int i = 1; i <= ChatModelFactory.MAX_ENTRIES; i++) {
            f.modelsFor(config("m-" + i, null));
        }

        assertThat(f.modelsFor(oldest).deep()).isNotSameAs(first.deep());
    }
}

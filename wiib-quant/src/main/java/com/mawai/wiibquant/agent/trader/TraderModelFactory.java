package com.mawai.wiibquant.agent.trader;

import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibquant.agent.llm.ResponsesChatModel;
import com.openai.client.OpenAIClient;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BYOK 模型工厂：按 trader 构建/缓存 ChatModel。
 * 建模路径与 {@link com.mawai.wiibquant.agent.config.AiAgentRuntimeManager} 完全同款
 * （openai→OpenAiChatModel / responses→ResponsesChatModel），区别只是配置来源换成 ai_trader 行。
 * 缓存按配置指纹失效：改了 baseUrl/model/key/protocol 下次唤醒自动重建，无需显式刷新。
 */
@Slf4j
@Component
public class TraderModelFactory {

    private record CacheEntry(String fingerprint, ChatModel model) {
    }

    private final ApiKeyCrypto apiKeyCrypto;
    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    public TraderModelFactory(ApiKeyCrypto apiKeyCrypto,
                              ToolCallingManager toolCallingManager,
                              ObjectProvider<ObservationRegistry> observationRegistry) {
        this.apiKeyCrypto = apiKeyCrypto;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);
    }

    public ChatModel modelFor(AiTrader trader) {
        String fp = fingerprint(trader);
        CacheEntry entry = cache.compute(trader.getId(), (id, cached) ->
                cached != null && cached.fingerprint().equals(fp)
                        ? cached
                        : new CacheEntry(fp, build(trader)));
        return entry.model();
    }

    public void evict(long traderId) {
        cache.remove(traderId);
    }

    /** 连通性测试：发一条最小请求。成功返回 null，失败返回给用户看的错误摘要。 */
    public String testConnection(AiTrader trader) {
        try {
            build(trader).call(new Prompt(new UserMessage("ping")));
            return null;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // 上游错误可能带请求细节，截断防刷屏；key 本身不会出现在异常信息里
            return msg.length() > 300 ? msg.substring(0, 300) : msg;
        }
    }

    private ChatModel build(AiTrader trader) {
        String apiKey = apiKeyCrypto.decrypt(trader.getApiKeyEnc());
        if (AiProtocols.isResponses(trader.getApiProtocol())) {
            return new ResponsesChatModel(apiKey, trader.getBaseUrl(), trader.getModel(),
                    null, null, toolCallingManager);
        }
        // timeout 非空是硬约束（SDK 是 Kotlin，null 运行时 NPE）；重试超时口径与平台模型一致
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                trader.getBaseUrl(), apiKey, null, null, null, null,
                false, false, trader.getModel(), ResponsesChatModel.CALL_TIMEOUT, 3, null, null,
                observationRegistry, null, List.of());
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .options(OpenAiChatOptions.builder().model(trader.getModel()).build())
                .observationRegistry(observationRegistry)
                .build();
    }

    /** 指纹只含建模四件套；key 用密文参与（明文不留存内存字段） */
    private static String fingerprint(AiTrader t) {
        return Objects.hash(t.getApiProtocol(), t.getBaseUrl(), t.getModel(), t.getApiKeyEnc()) + "";
    }
}

package com.mawai.wiibquant.agent.llm;

import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibquant.agent.trader.ApiKeyCrypto;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.models.models.Model;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BYOK 建模的唯一实现：一条 {@link UserLlmEndpoint} → 一个 ChatModel（openai→OpenAiChatModel / responses→ResponsesChatModel）。
 * 对话（ChatModelFactory）与交易员（TraderModelFactory）两个工厂只管各自的缓存策略，建模都走这里——
 * 以前两边各抄一份是因为配置形状不同（ai_trader 行 vs user_llm_config 行），现在同一张端点表，没理由再养两份。
 * <p>
 * 与平台轨（{@link com.mawai.wiibquant.agent.runtime.AiAgentRuntimeManager}）的建模路径同款，区别只是 key 来源。
 */
@Component
public class ByokModelBuilder {

    private final ApiKeyCrypto apiKeyCrypto;
    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;

    public ByokModelBuilder(ApiKeyCrypto apiKeyCrypto,
                            ToolCallingManager toolCallingManager,
                            ObjectProvider<ObservationRegistry> observationRegistry) {
        this.apiKeyCrypto = apiKeyCrypto;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);
    }

    /** 建模。纯本地构造不发网络请求；key 现解现用，明文不留存字段 */
    public ChatModel build(UserLlmEndpoint e) {
        String apiKey = apiKeyCrypto.decrypt(e.getApiKeyEnc());
        String effort = e.getReasoningEffort() == null || e.getReasoningEffort().isBlank() ? null : e.getReasoningEffort();
        if (AiProtocols.isResponses(e.getApiProtocol())) {
            return new ResponsesChatModel(apiKey, e.getBaseUrl(), e.getModel(), null, effort, toolCallingManager);
        }
        // timeout 非空是硬约束（SDK 是 Kotlin，null 运行时 NPE）
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                OpenAiBaseUrl.forSdk(e.getBaseUrl()), apiKey, null, null, null, null,
                false, false, e.getModel(), ResponsesChatModel.CALL_TIMEOUT, 3, null, null,
                observationRegistry, null, List.of());
        // async 也必须显式给：builder 见 openAiClientAsync 为空就拿 options 自建，而 options 里没 key，
        // SDK 当场抛 "At least one credential source must be specified"（哪怕根本不走流式）
        OpenAIClientAsync clientAsync = OpenAiSetup.setupAsyncClient(
                OpenAiBaseUrl.forSdk(e.getBaseUrl()), apiKey, null, null, null, null,
                false, false, e.getModel(), ResponsesChatModel.CALL_TIMEOUT, 3, null, null,
                observationRegistry, null, List.of());
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(e.getModel());
        if (effort != null) {
            options.reasoningEffort(effort);
        }
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(clientAsync)
                .options(options.build())
                .observationRegistry(observationRegistry)
                .build();
    }

    /** 拉取端点可用模型清单。两协议的 /models 都是 OpenAI 风格，同一条路；失败原样抛给调用方 */
    public List<String> listModels(String baseUrl, String apiKeyEnc) {
        String apiKey = apiKeyCrypto.decrypt(apiKeyEnc);
        // model 参数只在 Azure/GitHub 分支参与 URL 计算，探针还没选模型，占位即可
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                OpenAiBaseUrl.forSdk(baseUrl), apiKey, null, null, null, null,
                false, false, "list-models", ResponsesChatModel.CALL_TIMEOUT, 3, null, null,
                observationRegistry, null, List.of());
        return client.models().list().data().stream().map(Model::id).sorted().toList();
    }
}

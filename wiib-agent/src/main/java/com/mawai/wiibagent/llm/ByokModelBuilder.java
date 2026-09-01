package com.mawai.wiibagent.llm;

import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibagent.trader.ApiKeyCrypto;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.models.models.Model;
import io.micrometer.observation.ObservationRegistry;
import okhttp3.Response;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * BYOK 建模的唯一实现：一条 {@link UserLlmEndpoint} → 一个 ChatModel（openai→OpenAiChatModel / responses→ResponsesChatModel）。
 * 对话（ChatModelFactory）与交易员（TraderModelFactory）两个工厂只管各自的缓存策略，建模都走这里。
 * <p>
 * 与平台轨（{@link com.mawai.wiibagent.runtime.AiAgentRuntimeManager}）的建模路径同款，区别只是 key 来源。
 */
@Component
public class ByokModelBuilder {

    /**
     * 落点主机必须还是用户填的那个：baseUrl 的地址校验只在它上面做过，跨主机跳走的落点没校验。
     * 只比主机不比端口——http→https 的 301 会把端口从 80 带到 443，比端口就把这种正常重定向误杀了。
     * Spring AI 只暴露 application 级 interceptor（在 OkHttp 重定向处理之上，看不到中间跳），
     * 所以判据取最终响应落在哪台主机。
     */
    private static final List<OpenAiHttpClientBuilderCustomizer> REJECT_CROSS_HOST_REDIRECT = List.of(
            builder -> builder.interceptor(chain -> {
                Response response = chain.proceed(chain.request());
                String asked = chain.request().url().host();
                String landed = response.request().url().host();
                if (!asked.equalsIgnoreCase(landed)) {
                    response.close();
                    throw new IOException("上游把请求重定向到了 " + landed + "，已拒绝");
                }
                return response;
            }));

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
            // webSearch 只是端点能力声明：真发不发 web_search 还要看调用方授权（summarizer 独有），见 ResponsesChatModel
            return new ResponsesChatModel(apiKey, e.getBaseUrl(), e.getModel(), null, effort, toolCallingManager,
                    Boolean.TRUE.equals(e.getWebSearch()));
        }
        // timeout 非空是硬约束（SDK 是 Kotlin，null 运行时 NPE）
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                OpenAiBaseUrl.forSdk(e.getBaseUrl()), apiKey, null, null, null, null,
                false, false, e.getModel(), ResponsesChatModel.CALL_TIMEOUT, 3, null, null,
                observationRegistry, null, REJECT_CROSS_HOST_REDIRECT);
        // async 也必须显式给：builder 见 openAiClientAsync 为空就拿 options 自建，而 options 里没 key，
        // SDK 当场抛 "At least one credential source must be specified"（哪怕根本不走流式）
        OpenAIClientAsync clientAsync = OpenAiSetup.setupAsyncClient(
                OpenAiBaseUrl.forSdk(e.getBaseUrl()), apiKey, null, null, null, null,
                false, false, e.getModel(), ResponsesChatModel.CALL_TIMEOUT, 3, null, null,
                observationRegistry, null, REJECT_CROSS_HOST_REDIRECT);
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
                observationRegistry, null, REJECT_CROSS_HOST_REDIRECT);
        return client.models().list().data().stream().map(Model::id).sorted().toList();
    }
}

package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.UserLlmConfig;
import com.mawai.wiibquant.agent.llm.ResponsesChatModel;
import com.mawai.wiibquant.agent.trader.ApiKeyCrypto;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话轨的 BYOK 模型工厂：配置 → 深/浅两个 ChatModel。建模路径与
 * {@link com.mawai.wiibquant.agent.trader.TraderModelFactory} 同款，区别只是配置来自哪张表。
 * <p>
 * 缓存按<b>配置指纹</b>而不是按 userId：用同一个中转 + 同一模型的用户共享同一对实例
 *（实际部署里这是多数情况），用户改配置指纹就变、自然拿到新实例，不需要任何显式 evict。
 * <p>
 * <b>已知行为</b>：api_key_enc 是 AES-GCM 随机 IV，同一把 key 每次加密出的密文都不同，
 * 所以用户点保存但一个字没改，指纹照样变、模型会白重建一次。旧实例被 LRU 淘汰，没有实际损失。
 */
@Slf4j
@Component
public class ChatModelFactory {

    /** 缓存上限：32 份不同配置同时在用远超实际规模，够用又不会无界增长 */
    static final int MAX_ENTRIES = 32;

    public record Models(ChatModel deep, ChatModel light) {
    }

    private final ApiKeyCrypto apiKeyCrypto;
    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;

    /**
     * LRU（accessOrder=true + removeEldest）。必须包 synchronizedMap：accessOrder 下连 get
     * 都会改链表。锁只保护单次 get/putIfAbsent，<b>建模不在锁里做</b>，见 {@link #modelsFor}。
     */
    private final Map<String, Models> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Models> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    public ChatModelFactory(ApiKeyCrypto apiKeyCrypto,
                            ToolCallingManager toolCallingManager,
                            ObjectProvider<ObservationRegistry> observationRegistry) {
        this.apiKeyCrypto = apiKeyCrypto;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);
    }

    /**
     * 先查后建，<b>不用 computeIfAbsent</b>：它会在整个 mapping 函数执行期间攥着互斥锁，
     * 而这里的 mapping 是装配 SDK 客户端和连接池。这条路后面还要放到请求线程上，
     * 写成 computeIfAbsent 的话任何一个用户首次建模期间，别人的对话请求全堵在这把锁上。
     */
    public Models modelsFor(UserLlmConfig config) {
        String fp = fingerprint(config);
        Models hit = cache.get(fp);
        if (hit != null) {
            return hit;
        }
        Models built = build(config);      // 锁外建模，慢也只慢自己
        // 并发下可能有人先放好了，用先到的那份：模型无状态，多建一份只是一次 GC
        Models prev = cache.putIfAbsent(fp, built);
        return prev != null ? prev : built;
    }

    /**
     * 指纹含全部建模要素；key 用密文参与，明文不留存内存字段。
     * <p>
     * <b>用 SHA-256 而不是 {@code Objects.hash}</b>：这是跨用户的缓存主键，32 位 int 撞一次
     * 就是 B 的请求拿到 A 的 ChatModel——<b>烧的是 A 的 key</b>。概率低（生日界约 7.7 万份配置
     * 撞到 50%），但代价是"用别人的 key"，换 SHA-256 零成本。
     *（{@code TraderModelFactory} 那个 {@code Objects.hash} 语义完全不同：它是同一个 traderId
     * 槽位内的"变了没有"校验位，撞了只影响该不该重建自己那一个模型。）
     * <p>
     * 分隔符不能省：没有它 {@code ("ab","c")} 和 {@code ("a","bc")} 拼出同一个串。
     */
    public static String fingerprint(UserLlmConfig c) {
        String raw = String.join(" ",
                String.valueOf(c.getApiProtocol()), String.valueOf(c.getBaseUrl()),
                String.valueOf(c.getModel()), String.valueOf(c.getLightModel()),
                String.valueOf(c.getApiKeyEnc()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 没有 SHA-256", e);   // 不可能发生
        }
    }

    private Models build(UserLlmConfig config) {
        String apiKey = apiKeyCrypto.decrypt(config.getApiKeyEnc());
        ChatModel deep = buildOne(config, apiKey, config.getModel());
        // 轻模型不填就复用深模型这个实例本身（不是照参数再建一个）：省一份客户端和连接池
        ChatModel light = config.getLightModel() == null || config.getLightModel().isBlank()
                ? deep
                : buildOne(config, apiKey, config.getLightModel());
        log.info("[ChatModel] 建模完成 model={} light={}", config.getModel(), config.getLightModel());
        return new Models(deep, light);
    }

    private ChatModel buildOne(UserLlmConfig config, String apiKey, String modelName) {
        if (AiProtocols.isResponses(config.getApiProtocol())) {
            return new ResponsesChatModel(apiKey, config.getBaseUrl(), modelName,
                    null, null, toolCallingManager);
        }
        // timeout 非空是硬约束（SDK 是 Kotlin，null 运行时 NPE）
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                config.getBaseUrl(), apiKey, null, null, null, null,
                false, false, modelName, ResponsesChatModel.CALL_TIMEOUT, 3, null, null,
                observationRegistry, null, List.of());
        // async 也必须显式给：builder 见 openAiClientAsync 为空就拿 options 自建，而 options 里没 key，
        // SDK 当场抛 "At least one credential source must be specified"
        OpenAIClientAsync clientAsync = OpenAiSetup.setupAsyncClient(
                config.getBaseUrl(), apiKey, null, null, null, null,
                false, false, modelName, ResponsesChatModel.CALL_TIMEOUT, 3, null, null,
                observationRegistry, null, List.of());
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(clientAsync)
                .options(OpenAiChatOptions.builder().model(modelName).build())
                .observationRegistry(observationRegistry)
                .build();
    }
}

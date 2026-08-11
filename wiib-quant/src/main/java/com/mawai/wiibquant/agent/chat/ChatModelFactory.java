package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.UserLlmConfig;
import com.mawai.wiibquant.agent.llm.OpenAiBaseUrl;
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
 * 缓存键是<b>配置指纹</b>而不是 userId，实际效果是<b>一个用户一份</b>：指纹把 api_key_enc 算了进去，
 * 而它是 AES-GCM 随机 IV 的密文，两个用户的密文不可能相同——所以这里不存在跨用户共享实例。
 * 用指纹的好处在另一头：用户改了配置指纹就变、自然拿到新实例，不需要任何显式 evict。
 * <p>
 * <b>什么时候会白重建一次</b>：用户重新输入 key（哪怕就是同一把），密文随机 IV 变了指纹就变。
 * 只改模型名之类、key 输入框留空的话密文沿用旧的（见 {@code UserLlmConfigService.toRow}），
 * 不会重建。旧实例被 LRU 淘汰，没有实际损失。
 */
@Slf4j
@Component
public class ChatModelFactory {

    /**
     * 缓存上限。既然一个用户一份（见类头），这个数就是<b>能同时缓存几个活跃用户</b>——
     * 超了就 LRU 抖动，被淘汰的人下次发消息要重建一整套模型（建对象和 HTTP client，不打网络）。
     * <p>
     * 对话已对全体用户开放，32 是拍的数。注意 {@link ChatConcurrencyGate} 卡的是
     * <b>同时在跑的轮数</b>（默认全局 10），卡不住这里——一轮跑完缓存还占着。
     * 所以要调，按<b>一段时间内轮流来聊的人数</b>调，不是按并发数，更不是按注册用户数。
     */
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
     * 而且必须挑一个<b>字段值里不可能出现</b>的字符——model/lightModel 是用户在前端自由输入的，
     * 用空格的话 {@code ("gpt-5 x","y")} 和 {@code ("gpt-5","x y")} 照样撞。取 NUL：
     * Postgres 的 text 存不下这个字节，所以它绝不会出现在任何一个从库里读出来的字段值里。
     */
    public static String fingerprint(UserLlmConfig c) {
        String raw = String.join("\0",
                String.valueOf(c.getApiProtocol()), String.valueOf(c.getBaseUrl()),
                String.valueOf(c.getModel()), String.valueOf(c.getLightModel()),
                String.valueOf(c.getReasoningEffort()), String.valueOf(c.getApiKeyEnc()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 没有 SHA-256", e);   // 不可能发生
        }
    }

    private Models build(UserLlmConfig config) {
        String apiKey = apiKeyCrypto.decrypt(config.getApiKeyEnc());
        // 思考档位只给深模型：轻模型跑 router/专家/历史压缩这些简单活，高档纯烧钱烧延迟。
        // 用户不填轻模型时 light 就是 deep 这个实例，档位自然全局生效——正是单模型用户想要的
        ChatModel deep = buildOne(config, apiKey, config.getModel(), config.getReasoningEffort());
        // 轻模型不填就复用深模型这个实例本身（不是照参数再建一个）：省一份客户端和连接池
        ChatModel light = config.getLightModel() == null || config.getLightModel().isBlank()
                ? deep
                : buildOne(config, apiKey, config.getLightModel(), null);
        log.info("[ChatModel] 建模完成 model={} light={} effort={}",
                config.getModel(), config.getLightModel(), config.getReasoningEffort());
        return new Models(deep, light);
    }

    /** @param reasoningEffort 可空=不传，走模型默认。模型认不认这个参数查不到，所以是用户自己选的值 */
    private ChatModel buildOne(UserLlmConfig config, String apiKey, String modelName,
                               String reasoningEffort) {
        if (AiProtocols.isResponses(config.getApiProtocol())) {
            return new ResponsesChatModel(apiKey, config.getBaseUrl(), modelName,
                    null, reasoningEffort, toolCallingManager);
        }
        // timeout 非空是硬约束（SDK 是 Kotlin，null 运行时 NPE）
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                OpenAiBaseUrl.forSdk(config.getBaseUrl()), apiKey, null, null, null, null,
                false, false, modelName, ResponsesChatModel.CALL_TIMEOUT, 3, null, null,
                observationRegistry, null, List.of());
        // async 也必须显式给：builder 见 openAiClientAsync 为空就拿 options 自建，而 options 里没 key，
        // SDK 当场抛 "At least one credential source must be specified"
        OpenAIClientAsync clientAsync = OpenAiSetup.setupAsyncClient(
                OpenAiBaseUrl.forSdk(config.getBaseUrl()), apiKey, null, null, null, null,
                false, false, modelName, ResponsesChatModel.CALL_TIMEOUT, 3, null, null,
                observationRegistry, null, List.of());
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(modelName);
        if (reasoningEffort != null) {
            options.reasoningEffort(reasoningEffort);
        }
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(clientAsync)
                .options(options.build())
                .observationRegistry(observationRegistry)
                .build();
    }
}

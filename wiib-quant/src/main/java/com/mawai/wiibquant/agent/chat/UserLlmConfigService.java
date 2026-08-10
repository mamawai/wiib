package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.UserLlmConfig;
import com.mawai.wiibquant.agent.trader.ApiKeyCrypto;
import com.mawai.wiibquant.agent.trader.BaseUrlGuard;
import com.mawai.wiibquant.agent.trader.TraderModelFactory;
import com.mawai.wiibquant.mapper.UserLlmConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 用户 BYOK 配置读写。与 trader 侧的 BYOK 是<b>两份独立配置</b>：
 * trader 一天自动跑几十上百轮（要便宜稳），对话是按需的深度研判（要强模型），
 * 成本模型不同，绑一起会逼用户在两个诉求里二选一。
 * <p>
 * 错误约定与 TraderService 一致：返回 String 错误消息，成功返回 null。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserLlmConfigService {

    public record SaveReq(String apiProtocol, String baseUrl, String model,
                          String lightModel, String reasoningEffort, String apiKey) {
    }

    /**
     * 思考档位的合法值，与 {@code ai_runtime_config.reasoning_effort} 同一套。
     * <p>
     * 这里必须挡一道：档位是原样塞进上游请求体的，OpenAI 官方对不认识的值直接 400，
     * 而那时用户看到的是一次失败的对话，不是一句"档位填错了"。本地校验零成本。
     */
    private static final Set<String> REASONING_EFFORTS = Set.of("none", "low", "medium", "high");

    public record ListModelsResult(String error, List<String> models) {
    }

    private final UserLlmConfigMapper mapper;
    private final ApiKeyCrypto apiKeyCrypto;
    private final BaseUrlGuard baseUrlGuard;
    /** 只剩 listModels 在用：拉清单只打 /models，与建模无关，两协议同一条路 */
    private final TraderModelFactory modelFactory;
    private final ChatModelFactory chatModelFactory;

    /** 无配置返回 null——"没配"不是错误，是准入层要引导用户去处理的状态。 */
    public UserLlmConfig get(long userId) {
        return mapper.selectById(userId);
    }

    /**
     * 保存（新建或覆盖）。apiKey 传空=沿用已存的 key。
     * <p>
     * <b>只做本地校验，不发上游请求</b>：连通性探测是独立的按钮（{@link #testConnection}）。
     * 塞进这里的代价是端点抖一下用户就改不了模型名、每保存一次都要等一整个 LLM round-trip
     * 并烧一次 token，而 trader 侧的 TraderService.save 也不这么做。
     * 配置能存下但建不出模型的情况，准入期有 LLM_CONFIG_INVALID 兜着。
     */
    public String save(long userId, SaveReq req) {
        UserLlmConfig existing = get(userId);
        boolean keyChanged = req.apiKey() != null && !req.apiKey().isBlank();
        String err = validate(req, existing == null || keyChanged);
        if (err != null) {
            return err;
        }
        UserLlmConfig row = toRow(userId, req, existing, keyChanged);
        if (existing == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        log.info("[LlmConfig] 保存 userId={} model={} light={}", userId, row.getModel(), row.getLightModel());
        return null;
    }

    /**
     * 连通性探测（前端"测试连通性"按钮）。成功返 null，失败返上游原因。
     * <p>
     * <b>走的就是生产建模那条路</b>（{@link ChatModelFactory#modelsFor}），不是另搭一个形似的探针——
     * 测什么就得是接下来真跑什么。之前借道 {@code TraderModelFactory} 的探针是"测 A 跑 B"：
     * 那边按 {@code ai_trader} 的形状建模，没有思考档位这一项，于是档位填错要等到第一轮真对话才炸。
     * <p>
     * 顺带把缓存预热了：这次的指纹和用户接下来聊天用的是同一个，建的模不白建。
     * 探测失败也不清缓存——上游拒绝说明 key/模型名不对，不代表建出来的对象坏了。
     */
    public String testConnection(long userId, SaveReq req) {
        UserLlmConfig existing = get(userId);
        boolean keyChanged = req.apiKey() != null && !req.apiKey().isBlank();
        String err = validate(req, existing == null || keyChanged);
        if (err != null) {
            return err;
        }
        try {
            // 探深模型：它才是烧钱和吃档位的那个，浅模型探通了不代表它能用
            chatModelFactory.modelsFor(toRow(userId, req, existing, keyChanged))
                    .deep().call(new Prompt(new UserMessage("ping")));
            return null;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // 上游错误可能带整个请求细节，截断防刷屏；key 本身不会出现在异常信息里
            return "模型连通性测试失败：" + (msg.length() > 300 ? msg.substring(0, 300) : msg);
        }
    }

    /** SaveReq → 行对象。save 与 testConnection 必须用同一份组装，否则"测通了但存进去的不是它" */
    private UserLlmConfig toRow(long userId, SaveReq req, UserLlmConfig existing, boolean keyChanged) {
        UserLlmConfig row = new UserLlmConfig();
        row.setUserId(userId);
        row.setApiProtocol(normalizeProtocol(req.apiProtocol()));
        row.setBaseUrl(stripTrailingSlash(req.baseUrl().trim()));
        row.setModel(req.model().trim());
        row.setLightModel(blankToNull(req.lightModel()));
        row.setReasoningEffort(normalizeEffort(req.reasoningEffort()));
        row.setApiKeyEnc(keyChanged ? apiKeyCrypto.encrypt(req.apiKey().trim()) : existing.getApiKeyEnc());
        return row;
    }

    /** apiKey 传空=用已存的 key，与保存语义一致。 */
    public ListModelsResult listModels(long userId, SaveReq req) {
        String ssrf = baseUrlGuard.check(req.baseUrl() == null ? "" : req.baseUrl());
        if (ssrf != null) {
            return new ListModelsResult(ssrf, List.of());
        }
        UserLlmConfig existing = get(userId);
        boolean keyChanged = req.apiKey() != null && !req.apiKey().isBlank();
        if (!keyChanged && existing == null) {
            return new ListModelsResult("apiKey不能为空", List.of());
        }
        // 不设 model：拉清单只打 /models，TraderModelFactory.listModels 压根不读这个字段
        //（它建 client 时自己写死了占位），这里凭空编个模型名只会误导读日志的人
        UserLlmConfig probe = new UserLlmConfig();
        probe.setApiProtocol(req.apiProtocol());
        probe.setBaseUrl(stripTrailingSlash(req.baseUrl().trim()));
        probe.setApiKeyEnc(keyChanged ? apiKeyCrypto.encrypt(req.apiKey().trim()) : existing.getApiKeyEnc());
        try {
            return new ListModelsResult(null, modelFactory.listModels(asProbe(probe)));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new ListModelsResult(msg.length() > 300 ? msg.substring(0, 300) : msg, List.of());
        }
    }

    /** 明文永远不出服务端，回显只给尾 4 位够用户认出是哪把 key。 */
    public String keyTail(UserLlmConfig c) {
        try {
            String plain = apiKeyCrypto.decrypt(c.getApiKeyEnc());
            return plain.length() > 4 ? plain.substring(plain.length() - 4) : "****";
        } catch (Exception e) {
            return "????";
        }
    }

    /**
     * 把 UserLlmConfig 适配成 TraderModelFactory 认的 AiTrader 形状，<b>只为拉模型清单</b>。
     * 拉清单只打 /models，不建模、也就与协议分叉和思考档位都无关，借那边的实现没有代价；
     * 连通性探测则不能这么借，它必须走自己那条真建模的路（见 {@link #testConnection}）。
     */
    private static AiTrader asProbe(UserLlmConfig c) {
        AiTrader t = new AiTrader();
        // modelFor 按 id 缓存，探针不能用真实 id 污染缓存；这条路只调 listModels（不走缓存），
        // 哨兵值是防将来误用
        t.setId(-1L);
        t.setApiProtocol(c.getApiProtocol());
        t.setBaseUrl(c.getBaseUrl());
        t.setModel(c.getModel());
        t.setApiKeyEnc(c.getApiKeyEnc());
        return t;
    }

    private String validate(SaveReq req, boolean requireKey) {
        if (req.baseUrl() == null || req.baseUrl().isBlank()) {
            return "baseUrl不能为空";
        }
        String ssrf = baseUrlGuard.check(req.baseUrl());
        if (ssrf != null) {
            return ssrf;
        }
        if (req.model() == null || req.model().isBlank()) {
            return "model不能为空";
        }
        // 不学 trader 侧的"空→默认 openai"：那条兜底是给存量行留的，新表没这包袱；
        // 前端是 select，传空说明请求本身不对，响亮拒绝比默默兜底好查
        if (req.apiProtocol() == null || req.apiProtocol().isBlank()) {
            return "apiProtocol不能为空";
        }
        if (!AiProtocols.isValid(normalizeProtocol(req.apiProtocol()))) {
            return "协议仅支持 openai / responses";
        }
        String effort = normalizeEffort(req.reasoningEffort());
        if (effort != null && !REASONING_EFFORTS.contains(effort)) {
            return "思考档位仅支持 none / low / medium / high，留空=不传";
        }
        if (requireKey && (req.apiKey() == null || req.apiKey().isBlank())) {
            return "apiKey不能为空";
        }
        return null;
    }

    /**
     * 下游 {@link AiProtocols#isResponses} 是 equalsIgnoreCase 且不 trim，
     * "responses " 这种脏值存进去会被当成 openai——用户选了 responses 却发 /chat/completions，
     * 错误要到上游才冒出来。所以校验和落库都用抹平后的值，保证"验的就是存的"。
     */
    private static String normalizeProtocol(String protocol) {
        return protocol == null ? null : protocol.trim().toLowerCase();
    }

    /** 同 normalizeProtocol 的道理：档位是原样进请求体的，脏值等到上游才报错就太晚了。留空一律 null=不传 */
    private static String normalizeEffort(String effort) {
        String trimmed = blankToNull(effort);
        return trimmed == null ? null : trimmed.toLowerCase();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String stripTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}

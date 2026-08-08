package com.mawai.wiibquant.agent.trader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderRequest;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import com.mawai.wiibquant.mapper.AiTraderRequestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * trader 生命周期：创建（连通性校验→key加密→开sim子账户注资）→ 启停 → 重置开新局。
 * 每用户 1 个；每局一个独立 sim 子账户（账号名 ai_trader_{userId}_r{round}），历史局留档。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraderService {

    public static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000");
    /** 唤醒档位四档（1d 已下线：一天一醒的观赏性与反馈密度都撑不起一个档位） */
    private static final Set<String> INTERVALS = Set.of("5m", "15m", "1h", "4h");

    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final TraderModelFactory modelFactory;
    private final ApiKeyCrypto apiKeyCrypto;
    private final SimTradeClient simTradeClient;
    private final BinanceProperties binanceProperties;
    private final BaseUrlGuard baseUrlGuard;
    private final TraderPlanStore planStore;
    private final AiTraderRequestMapper requestMapper;

    public record UpsertReq(String name, String symbols, String intervalCode, String customPrompt,
                            String apiProtocol, String baseUrl, String model, String apiKey,
                            Boolean useDefaultPrompt,
                            Integer leverageMin, Integer leverageMax,
                            BigDecimal marginPctMin, BigDecimal marginPctMax,
                            Boolean allowMultiPosition, Boolean allowHedge,
                            Boolean allowSelfAdd, Boolean allowSelfReduce,
                            Boolean alertEnabled, BigDecimal alertThresholdMult) {
    }

    public record ListModelsReq(String apiProtocol, String baseUrl, String apiKey) {
    }

    public record ListModelsResult(String error, List<String> models) {
    }

    /** 拉取端点可用模型清单：key 传空=用已存 key（与改配置语义一致）。 */
    public ListModelsResult listModels(long userId, ListModelsReq req) {
        if (req.baseUrl() == null || req.baseUrl().isBlank()) {
            return new ListModelsResult("baseUrl不能为空", null);
        }
        String ssrf = baseUrlGuard.check(req.baseUrl());
        if (ssrf != null) {
            return new ListModelsResult(ssrf, null);
        }
        String protocol = req.apiProtocol() == null || req.apiProtocol().isBlank()
                ? AiProtocols.OPENAI : req.apiProtocol().trim().toLowerCase();
        if (!AiProtocols.isValid(protocol)) {
            return new ListModelsResult("协议仅支持 openai / responses", null);
        }
        String keyEnc;
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            keyEnc = apiKeyCrypto.encrypt(req.apiKey().trim());
        } else {
            AiTrader t = mine(userId);
            if (t == null) {
                return new ListModelsResult("尚未创建 Trader，请先填写 apiKey", null);
            }
            keyEnc = t.getApiKeyEnc();
        }
        AiTrader probe = new AiTrader();
        probe.setApiProtocol(protocol);
        probe.setBaseUrl(stripTrailingSlash(req.baseUrl().trim()));
        probe.setApiKeyEnc(keyEnc);
        try {
            return new ListModelsResult(null, modelFactory.listModels(probe).stream().sorted().toList());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new ListModelsResult(msg.length() > 300 ? msg.substring(0, 300) : msg, null);
        }
    }

    public AiTrader mine(long userId) {
        return traderMapper.selectOne(new LambdaQueryWrapper<AiTrader>().eq(AiTrader::getUserId, userId));
    }

    public AiTrader byId(long id) {
        return traderMapper.selectById(id);
    }

    public List<AiTrader> all() {
        return traderMapper.selectList(new LambdaQueryWrapper<AiTrader>().orderByAsc(AiTrader::getId));
    }

    /** 创建：校验→连通性测试→加密→开子账户→PAUSED 入库。返回错误信息或 null。 */
    public String create(long userId, UpsertReq req) {
        if (mine(userId) != null) {
            return "每个用户只能创建一个 AI Trader";
        }
        String err = validate(req, true);
        if (err != null) {
            return err;
        }
        AiTrader t = new AiTrader();
        t.setUserId(userId);
        applyConfig(t, req);
        t.setApiKeyEnc(apiKeyCrypto.encrypt(req.apiKey().trim()));
        String connErr = modelFactory.testConnection(t);
        if (connErr != null) {
            return "模型连通性测试失败：" + connErr;
        }
        t.setStatus(AiTrader.STATUS_PAUSED);
        t.setRoundNo(1);
        t.setConsecutiveFailures(0);
        t.setSimUserId(simTradeClient.ensureAccount(accountName(userId, 1), INITIAL_BALANCE));
        traderMapper.insert(t);
        log.info("[Trader] 创建 traderId={} userId={} model={}", t.getId(), userId, t.getModel());
        return null;
    }

    /** 改配置：apiKey 传空=不换 key；模型三件套或 key 变更时重测连通并逐出模型缓存。 */
    public String updateConfig(long userId, UpsertReq req) {
        AiTrader t = mine(userId);
        if (t == null) {
            return "尚未创建 AI Trader";
        }
        boolean keyChanged = req.apiKey() != null && !req.apiKey().isBlank();
        String err = validate(req, keyChanged);
        if (err != null) {
            return err;
        }
        AiTrader probe = new AiTrader();
        applyConfig(probe, req);
        probe.setApiKeyEnc(keyChanged ? apiKeyCrypto.encrypt(req.apiKey().trim()) : t.getApiKeyEnc());
        boolean modelChanged = keyChanged
                || !probe.getBaseUrl().equals(t.getBaseUrl())
                || !probe.getModel().equals(t.getModel())
                || !probe.getApiProtocol().equals(t.getApiProtocol());
        if (modelChanged) {
            String connErr = modelFactory.testConnection(probe);
            if (connErr != null) {
                return "模型连通性测试失败：" + connErr;
            }
        }
        // 列级更新只写配置字段：整行 updateById 会把唤醒回路并发写的 status/连败计数盖回快照旧值
        // （连通性测试要出网数秒，窗口不小）——与 runner 侧"状态回写列级更新"是同一条铁律的两半
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, t.getId())
                .set(AiTrader::getName, probe.getName())
                .set(AiTrader::getSymbols, probe.getSymbols())
                .set(AiTrader::getIntervalCode, probe.getIntervalCode())
                .set(AiTrader::getCustomPrompt, probe.getCustomPrompt())
                .set(AiTrader::getUseDefaultPrompt, probe.getUseDefaultPrompt())
                .set(AiTrader::getApiProtocol, probe.getApiProtocol())
                .set(AiTrader::getBaseUrl, probe.getBaseUrl())
                .set(AiTrader::getModel, probe.getModel())
                .set(AiTrader::getApiKeyEnc, probe.getApiKeyEnc())
                .set(AiTrader::getLeverageMin, probe.getLeverageMin())
                .set(AiTrader::getLeverageMax, probe.getLeverageMax())
                .set(AiTrader::getMarginPctMin, probe.getMarginPctMin())
                .set(AiTrader::getMarginPctMax, probe.getMarginPctMax())
                .set(AiTrader::getAllowMultiPosition, probe.getAllowMultiPosition())
                .set(AiTrader::getAllowHedge, probe.getAllowHedge())
                .set(AiTrader::getAllowSelfAdd, probe.getAllowSelfAdd())
                .set(AiTrader::getAllowSelfReduce, probe.getAllowSelfReduce())
                .set(AiTrader::getAlertEnabled, probe.getAlertEnabled())
                .set(AiTrader::getAlertThresholdMult, probe.getAlertThresholdMult())
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        if (modelChanged) {
            modelFactory.evict(t.getId());
        }
        return null;
    }

    public String start(long userId) {
        AiTrader t = mine(userId);
        if (t == null) {
            return "尚未创建 AI Trader";
        }
        if (AiTrader.STATUS_LIQUIDATED.equals(t.getStatus())) {
            return "本局已爆仓终局，请先重置开新一局";
        }
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, t.getId())
                .set(AiTrader::getStatus, AiTrader.STATUS_RUNNING)
                .set(AiTrader::getPausedReason, null)
                .set(AiTrader::getConsecutiveFailures, 0)
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        return null;
    }

    public String pause(long userId) {
        AiTrader t = mine(userId);
        if (t == null) {
            return "尚未创建 AI Trader";
        }
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, t.getId())
                .set(AiTrader::getStatus, AiTrader.STATUS_PAUSED)
                .set(AiTrader::getPausedReason, "手动暂停")
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        return null;
    }

    /** 重置开新局：round+1、新 sim 子账户注资、PAUSED 待手动启动；旧账户与决策历史留档。 */
    public String reset(long userId) {
        AiTrader t = mine(userId);
        if (t == null) {
            return "尚未创建 AI Trader";
        }
        int newRound = t.getRoundNo() + 1;
        Long simUserId = simTradeClient.ensureAccount(accountName(userId, newRound), INITIAL_BALANCE);
        // 本局存活计划随重置归档（不删）：论点/失效条件/修订史是公开凭证，也是learning agent的复盘原料
        planStore.archiveRound(t.getId(), t.getRoundNo(), System.currentTimeMillis());
        // 未处理的请求随本局一并作废：换了新账户，那个 positionId 早已不存在，留着也永远处理不掉
        requestMapper.update(null, new LambdaUpdateWrapper<AiTraderRequest>()
                .eq(AiTraderRequest::getTraderId, t.getId())
                .eq(AiTraderRequest::getStatus, AiTraderRequest.STATUS_PENDING)
                .set(AiTraderRequest::getStatus, AiTraderRequest.STATUS_REJECTED)
                .set(AiTraderRequest::getExecutedResult, "重置开新局，请求作废")
                .set(AiTraderRequest::getDecidedAt, LocalDateTime.now()));
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, t.getId())
                .set(AiTrader::getRoundNo, newRound)
                .set(AiTrader::getSimUserId, simUserId)
                .set(AiTrader::getStatus, AiTrader.STATUS_PAUSED)
                .set(AiTrader::getPausedReason, null)
                .set(AiTrader::getConsecutiveFailures, 0)
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        log.info("[Trader] 重置开新局 traderId={} round={}", t.getId(), newRound);
        return null;
    }

    /** 该 trader 最新权益（最近一条带 equity 的决策行；开局无决策时=初始资金）。 */
    public BigDecimal latestEquity(AiTrader t) {
        AiTraderDecision d = decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .isNotNull(AiTraderDecision::getEquity)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
        return d != null ? d.getEquity() : INITIAL_BALANCE;
    }

    /**
     * 决策时间线。必须按局过滤：局与局之间是两个互不相干的 sim 子账户（各自注资 10000），
     * 混排会出现"曲线上没有的决策"，权益数字也在两条基线之间跳。round 传空=当前局。
     */
    public List<AiTraderDecision> decisions(long traderId, int limit, Long before, Integer round) {
        AiTrader t = traderMapper.selectById(traderId);
        if (t == null) {
            return List.of();
        }
        LambdaQueryWrapper<AiTraderDecision> q = new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, traderId)
                .eq(AiTraderDecision::getRoundNo, round != null ? round : t.getRoundNo())
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT " + Math.clamp(limit, 1, 100));
        if (before != null) {
            q.lt(AiTraderDecision::getWakeTime, before);
        }
        return decisionMapper.selectList(q);
    }

    /** 当前局的持仓交易计划（竞技场详情随持仓一并展示）。 */
    public List<AiTraderPlan> plans(AiTrader t) {
        return planStore.list(t.getId(), t.getRoundNo());
    }

    /** 净值曲线：(wakeTime, equity) 升序；round 缺省=当前局。 */
    public List<AiTraderDecision> equityCurve(AiTrader t, Integer round) {
        return decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getWakeTime, AiTraderDecision::getEquity)
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, round != null ? round : t.getRoundNo())
                .isNotNull(AiTraderDecision::getEquity)
                .orderByAsc(AiTraderDecision::getWakeTime));
    }

    /** mine 页回显的 key 尾 4 位。 */
    public String keyTail(AiTrader t) {
        try {
            String plain = apiKeyCrypto.decrypt(t.getApiKeyEnc());
            return plain.length() > 4 ? plain.substring(plain.length() - 4) : "****";
        } catch (Exception e) {
            return "????";
        }
    }

    private String validate(UpsertReq req, boolean requireKey) {
        if (req.name() == null || req.name().isBlank() || req.name().length() > 32) {
            return "名字必填且不超过32字符";
        }
        if (req.intervalCode() == null || !INTERVALS.contains(req.intervalCode())) {
            return "K线级别仅支持 5m/15m/1h/4h";
        }
        String spec = validateSpec(req);
        if (spec != null) {
            return spec;
        }
        List<String> whitelist = binanceProperties.getSymbols();
        Set<String> symbols = parseSymbols(req.symbols());
        if (symbols.isEmpty()) {
            return "至少选择一个交易币种";
        }
        if (whitelist == null || !whitelist.containsAll(symbols)) {
            return "币种超出可交易范围: " + whitelist;
        }
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
        String protocol = req.apiProtocol() == null || req.apiProtocol().isBlank()
                ? AiProtocols.OPENAI : req.apiProtocol();
        if (!AiProtocols.isValid(protocol)) {
            return "协议仅支持 openai / responses";
        }
        if (requireKey && (req.apiKey() == null || req.apiKey().isBlank())) {
            return "apiKey不能为空";
        }
        if (req.customPrompt() != null && req.customPrompt().length() > 4000) {
            return "自定义提示词不超过4000字符";
        }
        // 退出平台模板后自定义就是唯一指令来源，空着=模型裸奔
        if (Boolean.FALSE.equals(req.useDefaultPrompt())
                && (req.customPrompt() == null || req.customPrompt().isBlank())) {
            return "已取消平台系统提示词，自定义提示词不能为空";
        }
        return null;
    }

    /**
     * 仓位规格校验：区间本身要成立，边界不能离谱。
     * 杠杆上界只卡到 125——实际可用还受 sim 按名义价值分档限制，超档由 sim 拒并把原因回传给模型，
     * 这里不重复实现一套分档表（quant 进程读不到 sim 的 bracket registry）。
     */
    private static String validateSpec(UpsertReq req) {
        int lmin = req.leverageMin() == null ? TraderRiskConfig.DEF_LEV_MIN : req.leverageMin();
        int lmax = req.leverageMax() == null ? TraderRiskConfig.DEF_LEV_MAX : req.leverageMax();
        if (lmin < 1 || lmax > TraderRiskConfig.LEVERAGE_HARD_MAX) {
            return "杠杆区间须在 1~" + TraderRiskConfig.LEVERAGE_HARD_MAX + " 倍之内";
        }
        if (lmin > lmax) {
            return "杠杆区间下界不能大于上界";
        }
        BigDecimal mmin = req.marginPctMin() == null ? TraderRiskConfig.DEF_MARGIN_MIN : req.marginPctMin();
        BigDecimal mmax = req.marginPctMax() == null ? TraderRiskConfig.DEF_MARGIN_MAX : req.marginPctMax();
        if (mmin.compareTo(TraderRiskConfig.MARGIN_PCT_HARD_MIN) < 0
                || mmax.compareTo(TraderRiskConfig.MARGIN_PCT_HARD_MAX) > 0) {
            return "单笔保证金占比须在 0.1~100% 之内";
        }
        if (mmin.compareTo(mmax) > 0) {
            return "保证金占比下界不能大于上界";
        }
        // 双开天然要占两个仓位，单仓模式下勾它是自相矛盾的配置，直接拦在入口
        if (Boolean.FALSE.equals(req.allowMultiPosition()) && Boolean.TRUE.equals(req.allowHedge())) {
            return "只允许一个仓位时无法开启多空双开（双开本身需要两个仓位）";
        }
        // 警报阈值只能调高：系数<1 等于把每币基准（平台下限）调低
        if (req.alertThresholdMult() != null && req.alertThresholdMult().compareTo(BigDecimal.ONE) < 0) {
            return "警报灵敏度系数不能低于 1.0（阈值只能调高不能调低）";
        }
        return null;
    }

    private static void applyConfig(AiTrader t, UpsertReq req) {
        t.setName(req.name().trim());
        t.setSymbols(String.join(",", parseSymbols(req.symbols())));
        t.setIntervalCode(req.intervalCode());
        t.setCustomPrompt(req.customPrompt());
        t.setApiProtocol(req.apiProtocol() == null || req.apiProtocol().isBlank()
                ? AiProtocols.OPENAI : req.apiProtocol().trim().toLowerCase());
        t.setBaseUrl(stripTrailingSlash(req.baseUrl().trim()));
        t.setModel(req.model().trim());
        t.setUseDefaultPrompt(req.useDefaultPrompt() == null || req.useDefaultPrompt());
        t.setLeverageMin(req.leverageMin() == null ? TraderRiskConfig.DEF_LEV_MIN : req.leverageMin());
        t.setLeverageMax(req.leverageMax() == null ? TraderRiskConfig.DEF_LEV_MAX : req.leverageMax());
        t.setMarginPctMin(req.marginPctMin() == null ? TraderRiskConfig.DEF_MARGIN_MIN : req.marginPctMin());
        t.setMarginPctMax(req.marginPctMax() == null ? TraderRiskConfig.DEF_MARGIN_MAX : req.marginPctMax());
        boolean multi = !Boolean.FALSE.equals(req.allowMultiPosition());
        t.setAllowMultiPosition(multi);
        // 单仓模式下双开无从谈起，落库直接归位 false，免得开关状态自相矛盾
        t.setAllowHedge(multi && Boolean.TRUE.equals(req.allowHedge()));
        t.setAllowSelfAdd(!Boolean.FALSE.equals(req.allowSelfAdd()));
        t.setAllowSelfReduce(Boolean.TRUE.equals(req.allowSelfReduce()));
        t.setAlertEnabled(!Boolean.FALSE.equals(req.alertEnabled()));
        t.setAlertThresholdMult(req.alertThresholdMult() == null ? BigDecimal.ONE : req.alertThresholdMult());
    }

    private static String stripTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static Set<String> parseSymbols(String symbols) {
        if (symbols == null || symbols.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(Arrays.stream(symbols.split(","))
                .map(s -> s.trim().toUpperCase()).filter(s -> !s.isEmpty()).toList());
    }

    private static String accountName(long userId, int round) {
        return "ai_trader_" + userId + "_r" + round;
    }
}

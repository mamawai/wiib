package com.mawai.wiibquant.agent.trader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
import com.mawai.wiibquant.mapper.AiTraderMapper;
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
    private static final Set<String> INTERVALS = Set.of("15m", "1h", "4h", "1d");

    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final TraderModelFactory modelFactory;
    private final ApiKeyCrypto apiKeyCrypto;
    private final SimTradeClient simTradeClient;
    private final BinanceProperties binanceProperties;

    public record UpsertReq(String name, String symbols, String intervalCode, String customPrompt,
                            String apiProtocol, String baseUrl, String model, String apiKey) {
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
        applyConfig(t, req);
        t.setApiKeyEnc(probe.getApiKeyEnc());
        t.setUpdatedAt(LocalDateTime.now());
        traderMapper.updateById(t);
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

    public List<AiTraderDecision> decisions(long traderId, int limit, Long before) {
        LambdaQueryWrapper<AiTraderDecision> q = new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, traderId)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT " + Math.clamp(limit, 1, 100));
        if (before != null) {
            q.lt(AiTraderDecision::getWakeTime, before);
        }
        return decisionMapper.selectList(q);
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
            return "K线级别仅支持 15m/1h/4h/1d";
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
        return null;
    }

    private static void applyConfig(AiTrader t, UpsertReq req) {
        t.setName(req.name().trim());
        t.setSymbols(String.join(",", parseSymbols(req.symbols())));
        t.setIntervalCode(req.intervalCode());
        t.setCustomPrompt(req.customPrompt());
        t.setApiProtocol(req.apiProtocol() == null || req.apiProtocol().isBlank()
                ? AiProtocols.OPENAI : req.apiProtocol().trim().toLowerCase());
        String baseUrl = req.baseUrl().trim();
        t.setBaseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        t.setModel(req.model().trim());
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

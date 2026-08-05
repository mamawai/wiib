package com.mawai.wiibquant.agent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibquant.agent.llm.ModelCallLimiter;
import com.mawai.wiibquant.agent.llm.ResilientChatService;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
import com.mawai.wiibquant.agent.toolkit.IndicatorToolkit;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 唤醒回路核心：一次唤醒 = 一个无状态 ReactAgent 会话（BYOK 模型 + 数据工具 + 绑定子账户的交易工具），
 * 决策全文 + 动作轨迹 + 权益落 ai_trader_decision。
 * 失败语义：连续 5 次失败自动 PAUSED；权益跌破初始 1% 判 LIQUIDATED 终局。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderWakeupRunner {

    static final int WAKE_TIMEOUT_SECONDS = 120;
    /** 单次唤醒模型调用上限（ReAct 迭代保险丝，挡住无限工具循环烧用户的钱） */
    static final int MAX_MODEL_CALLS = 8;
    static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** 爆仓判定线：权益 < 初始资金 10000 的 1% */
    static final BigDecimal LIQUIDATION_FLOOR = new BigDecimal("100");
    private static final int RECENT_DECISIONS = 5;

    private final TraderModelFactory modelFactory;
    private final TraderPromptAssembler promptAssembler;
    private final SimTradeClient simTradeClient;
    private final BinanceRestClient binanceRestClient;
    private final IndicatorToolkit indicatorToolkit;
    private final MarketToolkit marketToolkit;
    private final NewsToolkit newsToolkit;
    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final StateSerializer<MessagesState<Message>> stateSerializer;

    public void wake(AiTrader trader, long boundaryTime) {
        long start = System.currentTimeMillis();
        AiTraderDecision decision = baseDecision(trader, boundaryTime);
        try {
            List<FuturesPositionDTO> positions = simTradeClient.getAllPositions(trader.getSimUserId());
            BigDecimal equity = computeEquity(trader.getSimUserId(), positions);
            decision.setEquity(equity);

            if (equity.compareTo(LIQUIDATION_FLOOR) < 0) {
                markLiquidated(trader, decision);
                return;
            }

            String reasoning = runAgentSession(trader, boundaryTime, positions, equity, decision);
            decision.setStatus(AiTraderDecision.STATUS_OK);
            decision.setReasoning(reasoning);
            decision.setLatencyMs((int) (System.currentTimeMillis() - start));
            decisionMapper.insert(decision);
            clearFailures(trader);
        } catch (Exception e) {
            String msg = e instanceof TimeoutException ? "唤醒超时(" + WAKE_TIMEOUT_SECONDS + "s)"
                    : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[Trader] 唤醒失败 traderId={} boundary={} msg={}", trader.getId(), boundaryTime, msg);
            decision.setStatus(AiTraderDecision.STATUS_ERROR);
            decision.setError(msg.length() > 500 ? msg.substring(0, 500) : msg);
            decision.setLatencyMs((int) (System.currentTimeMillis() - start));
            decisionMapper.insert(decision);
            recordFailure(trader, msg);
        }
    }

    /** 上一唤醒未完被跳过：留痕，竞技场时间线可见调度诚实。 */
    public void recordSkipped(AiTrader trader, long boundaryTime) {
        AiTraderDecision d = baseDecision(trader, boundaryTime);
        d.setStatus(AiTraderDecision.STATUS_SKIPPED);
        d.setError("上一轮唤醒尚未结束，本轮跳过");
        decisionMapper.insert(d);
    }

    /** ReactAgent 会话：返回模型最终文本；动作轨迹随 decision 一并写入。 */
    private String runAgentSession(AiTrader trader, long boundaryTime,
                                   List<FuturesPositionDTO> positions, BigDecimal equity,
                                   AiTraderDecision decision) throws Exception {
        ChatModel model = modelFactory.modelFor(trader);
        Set<String> whitelist = Arrays.stream(trader.getSymbols().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        TradeTools tradeTools = new TradeTools(simTradeClient, trader.getSimUserId(), whitelist, equity,
                sym -> JSON.parseObject(binanceRestClient.getPremiumIndex(sym)).getBigDecimal("markPrice"));

        List<AiTraderDecision> recent = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, trader.getId())
                .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                .lt(AiTraderDecision::getWakeTime, boundaryTime)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT " + RECENT_DECISIONS));
        String prompt = promptAssembler.assemble(trader, accountStateJson(equity, positions), recent);

        CompiledGraph<MessagesState<Message>> graph = ReactAgent.<MessagesState<Message>>builder()
                .chatModel(model)
                .stateSerializer(stateSerializer)
                .defaultSystem(prompt)
                .toolsFromObject(tradeTools)
                .toolsFromObject(indicatorToolkit)
                .toolsFromObject(marketToolkit)
                .toolsFromObject(newsToolkit)
                // 首轮强制调工具：不看数据不许决策；弱模型不支持 tool_choice 会以 ERROR 落库并最终自动暂停
                .addExecuteToolsHook(new ModelCallLimiter(MAX_MODEL_CALLS))
                .build(ResilientChatService.builder().model(model).forceFirstToolChoice("required").asFactory())
                .compile();

        String instruction = "新一根 " + trader.getIntervalCode() + " K线已收盘（边界时刻 " + boundaryTime
                + "）。请按纪律流程分析并给出本轮决策。";
        RunnableConfig config = RunnableConfig.builder()
                .threadId("trader-" + trader.getId() + "-" + boundaryTime).build();

        // 虚拟线程 + FutureTask 承载超时；超时后本轮作废（已发出的订单不回滚——sim 是事实源）
        FutureTask<String> task = new FutureTask<>(() -> graph
                .invoke(Map.of("messages", List.of(new UserMessage(instruction))), config)
                .flatMap(MessagesState::lastMessage)
                .map(Message::getText)
                .orElse(""));
        Thread.startVirtualThread(task);
        String reasoning;
        try {
            reasoning = task.get(WAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            task.cancel(true);
            // 无论成败，动作轨迹都要留：超时/异常时已执行的开平仓是真实发生的
            decision.setActionsJson(JSON.toJSONString(tradeTools.actions()));
            decision.setToolCalls(tradeTools.actions().size());
        }
        return reasoning;
    }

    /** 权益 = 可用余额 + 冻结 + Σ(仓位保证金 + 未实现盈亏)。 */
    private BigDecimal computeEquity(Long simUserId, List<FuturesPositionDTO> positions) {
        Map<String, Object> balance = simTradeClient.getBalanceDetail(simUserId);
        BigDecimal equity = new BigDecimal(String.valueOf(balance.get("balance")))
                .add(new BigDecimal(String.valueOf(balance.getOrDefault("frozenBalance", "0"))));
        for (FuturesPositionDTO p : positions) {
            if (p.getMargin() != null) {
                equity = equity.add(p.getMargin());
            }
            if (p.getUnrealizedPnl() != null) {
                equity = equity.add(p.getUnrealizedPnl());
            }
        }
        return equity.setScale(8, RoundingMode.HALF_UP);
    }

    private static String accountStateJson(BigDecimal equity, List<FuturesPositionDTO> positions) {
        JSONObject out = new JSONObject();
        out.put("equity", equity);
        JSONArray ps = new JSONArray();
        for (FuturesPositionDTO p : positions) {
            ps.add(new JSONObject()
                    .fluentPut("positionId", p.getId())
                    .fluentPut("symbol", p.getSymbol())
                    .fluentPut("side", p.getSide())
                    .fluentPut("quantity", p.getQuantity())
                    .fluentPut("entryPrice", p.getEntryPrice())
                    .fluentPut("unrealizedPnl", p.getUnrealizedPnl()));
        }
        out.put("positions", ps);
        return out.toJSONString();
    }

    private static AiTraderDecision baseDecision(AiTrader trader, long boundaryTime) {
        AiTraderDecision d = new AiTraderDecision();
        d.setTraderId(trader.getId());
        d.setRoundNo(trader.getRoundNo());
        d.setWakeTime(boundaryTime);
        d.setIntervalCode(trader.getIntervalCode());
        d.setToolCalls(0);
        return d;
    }

    // 状态回写一律列级更新：runner 手里的 trader 是调度时刻的快照，整行 updateById 会把
    // 用户并发修改的配置（提示词/模型等）覆盖回旧值

    private void markLiquidated(AiTrader trader, AiTraderDecision decision) {
        decision.setStatus(AiTraderDecision.STATUS_OK);
        decision.setReasoning("账户权益已低于爆仓终局线，本局结束。可在配置页重置开新一局。");
        decisionMapper.insert(decision);
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, trader.getId())
                .set(AiTrader::getStatus, AiTrader.STATUS_LIQUIDATED)
                .set(AiTrader::getPausedReason, "爆仓终局：权益低于初始资金1%")
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        log.info("[Trader] 爆仓终局 traderId={} round={}", trader.getId(), trader.getRoundNo());
    }

    private void recordFailure(AiTrader trader, String lastError) {
        int failures = (trader.getConsecutiveFailures() == null ? 0 : trader.getConsecutiveFailures()) + 1;
        LambdaUpdateWrapper<AiTrader> update = new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, trader.getId())
                .set(AiTrader::getConsecutiveFailures, failures)
                .set(AiTrader::getUpdatedAt, LocalDateTime.now());
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            update.set(AiTrader::getStatus, AiTrader.STATUS_PAUSED)
                    .set(AiTrader::getPausedReason, "连续" + failures + "次唤醒失败: "
                            + (lastError.length() > 150 ? lastError.substring(0, 150) : lastError));
            log.warn("[Trader] 连败自动暂停 traderId={} failures={}", trader.getId(), failures);
        }
        traderMapper.update(null, update);
    }

    private void clearFailures(AiTrader trader) {
        if (trader.getConsecutiveFailures() != null && trader.getConsecutiveFailures() > 0) {
            traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                    .eq(AiTrader::getId, trader.getId())
                    .set(AiTrader::getConsecutiveFailures, 0)
                    .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        }
    }
}

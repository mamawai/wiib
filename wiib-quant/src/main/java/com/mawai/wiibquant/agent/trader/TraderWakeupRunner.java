package com.mawai.wiibquant.agent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderRequest;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibquant.agent.llm.ModelCallLimiter;
import com.mawai.wiibquant.agent.llm.ResilientChatService;
import com.mawai.wiibquant.agent.llm.ToolCallTraceHook;
import com.mawai.wiibquant.agent.llm.UsageTrackingChatModel;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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

    /** 距下一边界不足此秒数=触发过晚（事件迟到），放弃本轮不算失败 */
    static final int MIN_WAKE_SECONDS = 30;
    /** 单轮预算上限：再慢的端点也不许无限吃时长 */
    static final int MAX_WAKE_SECONDS = 600;
    /** 截止安全余量：唤醒决不占用下一根K线 */
    private static final long DEADLINE_SAFETY_MS = 5_000;
    /** 单次唤醒模型调用上限（ReAct 迭代保险丝，挡住无限工具循环烧用户的钱） */
    static final int MAX_MODEL_CALLS = 8;
    static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** 爆仓判定线：权益 < 初始资金 10000 的 1% */
    static final BigDecimal LIQUIDATION_FLOOR = new BigDecimal("100");
    private static final int RECENT_DECISIONS = 5;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final TraderModelFactory modelFactory;
    private final TraderPromptAssembler promptAssembler;
    private final SimTradeClient simTradeClient;
    private final BinanceRestClient binanceRestClient;
    private final IndicatorToolkit indicatorToolkit;
    private final MarketToolkit marketToolkit;
    private final NewsToolkit newsToolkit;
    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final TraderPlanStore planStore;
    private final TraderRequestService requestService;
    private final StateSerializer<MessagesState<Message>> stateSerializer;

    /** 墙钟注入点：预算计算要可测（测试里把"现在"钉在边界附近） */
    java.util.function.LongSupplier nowMs = System::currentTimeMillis;

    /** 唤醒预算(秒)：截止 = 下一边界前 5s——唤醒决不占用下一根K线；上限 600s。 */
    static long wakeBudgetSeconds(long boundary, long intervalMs, long now) {
        long deadline = boundary + intervalMs - DEADLINE_SAFETY_MS;
        return Math.min((deadline - now) / 1000, MAX_WAKE_SECONDS);
    }

    public void wake(AiTrader trader, long boundaryTime) {
        long start = System.currentTimeMillis();
        long intervalMs = TraderScheduler.INTERVAL_MS.getOrDefault(trader.getIntervalCode(), 300_000L);
        long budgetSeconds = wakeBudgetSeconds(boundaryTime, intervalMs, nowMs.getAsLong());
        AiTraderDecision decision = baseDecision(trader, boundaryTime);
        if (budgetSeconds < MIN_WAKE_SECONDS) {
            // 事件迟到太多：与其用残余时间仓促决策，不如放弃等下一根新鲜K线（不算失败不计连败）
            decision.setStatus(AiTraderDecision.STATUS_SKIPPED);
            decision.setError("触发过晚（K线信号迟到），距下一边界不足" + MIN_WAKE_SECONDS + "s，放弃本轮");
            decisionMapper.insert(decision);
            log.warn("[Trader] 触发过晚放弃 traderId={} boundary={} budget={}s", trader.getId(), boundaryTime, budgetSeconds);
            return;
        }
        try {
            List<FuturesPositionDTO> positions = simTradeClient.getAllPositions(trader.getSimUserId());
            BigDecimal equity = computeEquity(trader.getSimUserId(), positions);
            decision.setEquity(equity);

            if (equity.compareTo(LIQUIDATION_FLOOR) < 0) {
                markLiquidated(trader, decision);
                return;
            }

            String reasoning = runAgentSession(trader, boundaryTime, budgetSeconds, positions, equity, decision);
            decision.setStatus(AiTraderDecision.STATUS_OK);
            decision.setReasoning(reasoning);
            // 动作都落地了再记权益：本轮开平仓立刻体现在净值曲线，否则要等下一根K线才现形
            decision.setEquity(computeEquity(trader.getSimUserId(),
                    simTradeClient.getAllPositions(trader.getSimUserId())));
            decision.setLatencyMs((int) (System.currentTimeMillis() - start));
            decisionMapper.insert(decision);
            clearFailures(trader);
        } catch (Exception e) {
            String msg = e instanceof TimeoutException ? "唤醒超时(" + budgetSeconds + "s)"
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
    private String runAgentSession(AiTrader trader, long boundaryTime, long budgetSeconds,
                                   List<FuturesPositionDTO> positions, BigDecimal equity,
                                   AiTraderDecision decision) throws Exception {
        // 包一层用量统计：ReAct 一轮要调模型很多次，包在最外层才收得全。
        // 工厂里的实例是跨唤醒缓存的，装饰器必须每轮新建，否则用量会跨轮累加
        UsageTrackingChatModel model = new UsageTrackingChatModel(modelFactory.modelFor(trader));
        Set<String> whitelist = Arrays.stream(trader.getSymbols().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        TraderRiskConfig risk = TraderRiskConfig.of(trader);
        TradeTools tradeTools = new TradeTools(simTradeClient, trader.getSimUserId(), whitelist, equity,
                sym -> JSON.parseObject(binanceRestClient.getPremiumIndex(sym)).getBigDecimal("markPrice"),
                planStore, requestService,
                new TradeTools.WakeCtx(trader.getId(), trader.getRoundNo(), boundaryTime, risk));

        List<AiTraderDecision> recent = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, trader.getId())
                .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                .lt(AiTraderDecision::getWakeTime, boundaryTime)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT " + RECENT_DECISIONS));

        // 计划懒清理 + 加载：仓位/挂单还活着的计划保留，已了结（止损/止盈/平仓/撤单）的删；存活计划随持仓回注
        Set<String> liveKeys = new HashSet<>();
        positions.forEach(p -> liveKeys.add(TraderPlanStore.key(p.getSymbol(), p.getSide())));
        for (FuturesOrderResponse o : simTradeClient.getPendingOrders(trader.getSimUserId(), null)) {
            if (o.getOrderSide() != null && o.getOrderSide().startsWith("OPEN_")) {
                liveKeys.add(TraderPlanStore.key(o.getSymbol(), o.getOrderSide().substring("OPEN_".length())));
            }
        }
        List<AiTraderPlan> plans = planStore.cleanupStale(trader.getId(), trader.getRoundNo(), liveKeys);

        String prompt = promptAssembler.assemble(trader,
                accountStateJson(equity, positions, plans, boundaryTime,
                        requestService.pendingOf(trader.getId(), trader.getRoundNo())), recent);

        // 全量工具轨迹（含数据工具）：收集器在本方法手里，超时 cancel 也保得住已发生的记录
        ToolCallTraceHook trace = new ToolCallTraceHook();
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
                .addExecuteToolsHook(trace)
                .build(ResilientChatService.builder().model(model).forceFirstToolChoice("required").asFactory())
                .compile();

        String instruction = "新一根 " + trader.getIntervalCode() + " K线已收盘（边界时刻 " + boundaryTime
                + "）。请按纪律流程分析并给出本轮决策。HOLD 也是完整决策——写明你在等的触发条件。";
        RunnableConfig config = RunnableConfig.builder()
                .threadId("trader-" + trader.getId() + "-" + boundaryTime).build();

        record SessionOutcome(String reasoning, int modelCalls) {
        }
        // 虚拟线程 + FutureTask 承载超时；超时后本轮作废（已发出的订单不回滚——sim 是事实源）
        FutureTask<SessionOutcome> task = new FutureTask<>(() -> {
            MessagesState<Message> state = graph
                    .invoke(Map.of("messages", List.of(new UserMessage(instruction))), config)
                    .orElseThrow(() -> new IllegalStateException("图执行无返回状态"));
            return new SessionOutcome(
                    state.lastMessage().map(Message::getText).orElse(""),
                    state.<Number>value(ModelCallLimiter.CALL_COUNT_KEY).map(Number::intValue).orElse(0));
        });
        Thread.startVirtualThread(task);
        SessionOutcome outcome;
        try {
            outcome = task.get(budgetSeconds, TimeUnit.SECONDS);
        } finally {
            task.cancel(true);
            // 无论成败，动作轨迹都要留：超时/异常时已执行的开平仓是真实发生的
            List<JSONObject> actions = mergeActions(trace.calls(), tradeTools.actions());
            decision.setActionsJson(JSON.toJSONString(actions));
            decision.setToolCalls(actions.size());
            // 用量同理落在 finally：超时作废的那一轮，token 也是真烧掉了，不能不记
            UsageTrackingChatModel.UsageSnapshot usage = model.snapshot();
            decision.setModelCalls(usage.modelCalls());
            decision.setPromptTokens(usage.promptTokens());
            decision.setCompletionTokens(usage.completionTokens());
            decision.setTotalTokens(usage.totalTokens());
        }
        if (outcome.modelCalls() >= MAX_MODEL_CALLS) {
            // 保险丝收束不算失败（已有动作真实生效），但必须留痕——否则时间线上像正常决策
            decision.setError("达单轮模型调用上限(" + MAX_MODEL_CALLS + ")，提前收束");
        }
        return outcome.reasoning();
    }

    /**
     * 合并轨迹：顺序骨架来自图上 hook 的全量记录（含数据工具）；交易工具用 TradeTools 的
     * 富记录（结果/拒因）按序替换轻量占位。极端中断时 hook 记录缺失，富记录兜底补尾。
     */
    private static List<JSONObject> mergeActions(List<JSONObject> traced, List<JSONObject> tradeActions) {
        Deque<JSONObject> rich = new ArrayDeque<>(tradeActions);
        List<JSONObject> merged = new ArrayList<>();
        for (JSONObject t : traced) {
            if (TradeTools.RECORDED_TOOLS.contains(t.getString("tool")) && !rich.isEmpty()) {
                merged.add(rich.poll());
            } else {
                merged.add(t);
            }
        }
        merged.addAll(rich);
        return merged;
    }

    /**
     * 权益 = 可用余额 + 冻结 + Σ仓位价值。口径对齐 sim AssetValuationService：
     * 全仓仓位只计浮盈亏——占用制下保证金从没离开余额钱包，再加 margin 就是同一笔钱计两遍
     * （曾造成竞技场亏损却显示 +1281 的虚高）；逐仓才是划扣制，margin 住在仓位里要加回。
     */
    private BigDecimal computeEquity(Long simUserId, List<FuturesPositionDTO> positions) {
        Map<String, Object> balance = simTradeClient.getBalanceDetail(simUserId);
        BigDecimal equity = new BigDecimal(String.valueOf(balance.get("balance")))
                .add(new BigDecimal(String.valueOf(balance.getOrDefault("frozenBalance", "0"))));
        for (FuturesPositionDTO p : positions) {
            if (!FuturesPosition.CROSS.equals(p.getMarginMode()) && p.getMargin() != null) {
                equity = equity.add(p.getMargin());
            }
            if (p.getUnrealizedPnl() != null) {
                equity = equity.add(p.getUnrealizedPnl());
            }
        }
        return equity.setScale(8, RoundingMode.HALF_UP);
    }

    /** 持仓携带交易计划与当前止损止盈回注：让模型一眼看到"浮亏离止损还远/计划没被证伪"，掐灭恐慌平仓。 */
    private static String accountStateJson(BigDecimal equity, List<FuturesPositionDTO> positions,
                                           List<AiTraderPlan> plans, long boundaryTime,
                                           List<AiTraderRequest> pendingRequests) {
        Map<String, AiTraderPlan> planByKey = new HashMap<>();
        plans.forEach(p -> planByKey.put(TraderPlanStore.key(p.getSymbol(), p.getSide()), p));
        JSONObject out = new JSONObject();
        out.put("equity", equity);
        JSONArray ps = new JSONArray();
        for (FuturesPositionDTO p : positions) {
            JSONObject row = new JSONObject()
                    .fluentPut("positionId", p.getId())
                    .fluentPut("symbol", p.getSymbol())
                    .fluentPut("side", p.getSide())
                    .fluentPut("quantity", p.getQuantity())
                    .fluentPut("entryPrice", p.getEntryPrice())
                    .fluentPut("unrealizedPnl", p.getUnrealizedPnl());
            if (p.getStopLosses() != null && !p.getStopLosses().isEmpty()) {
                row.put("currentStopLoss", p.getStopLosses().stream().map(FuturesStopLoss::getPrice).toList());
            }
            if (p.getTakeProfits() != null && !p.getTakeProfits().isEmpty()) {
                row.put("currentTakeProfit", p.getTakeProfits().stream().map(FuturesTakeProfit::getPrice).toList());
            }
            AiTraderPlan plan = planByKey.get(TraderPlanStore.key(p.getSymbol(), p.getSide()));
            if (plan != null) {
                JSONObject planJson = new JSONObject()
                        .fluentPut("playType", plan.getPlayType())
                        .fluentPut("signalsUsed", plan.getSignalsUsed())
                        .fluentPut("invalidationCondition", plan.getInvalidationCondition())
                        .fluentPut("entryPrice", plan.getEntryPrice())
                        .fluentPut("originalStop", plan.getStopLossPrice())
                        .fluentPut("target", plan.getTakeProfitPrice())
                        .fluentPut("openedAt", TIME_FMT.format(Instant.ofEpochMilli(plan.getOpenedWakeTime())))
                        .fluentPut("heldFor", humanizeHeld(boundaryTime - plan.getOpenedWakeTime()));
                // 修订历史也回注：无记忆的模型必须看到"上轮为什么动了止损/目标"
                if (plan.getRevisionsJson() != null && !plan.getRevisionsJson().isBlank()) {
                    planJson.put("revisions", JSON.parse(plan.getRevisionsJson()));
                }
                row.put("plan", planJson);
            }
            ps.add(row);
        }
        out.put("positions", ps);
        // 待确认请求必须回注：不然模型看仓位没动，下一轮还会提同一个请求，卡片越堆越多
        if (pendingRequests != null && !pendingRequests.isEmpty()) {
            JSONArray rs = new JSONArray();
            for (AiTraderRequest r : pendingRequests) {
                rs.add(new JSONObject()
                        .fluentPut("type", r.getType())
                        .fluentPut("symbol", r.getSymbol())
                        .fluentPut("side", r.getSide())
                        .fluentPut("positionId", r.getPositionId())
                        .fluentPut("quantity", r.getQuantity())
                        .fluentPut("requestPrice", r.getRequestPrice())
                        .fluentPut("askedAt", TIME_FMT.format(Instant.ofEpochMilli(r.getWakeTime())))
                        .fluentPut("reason", r.getReason()));
            }
            out.put("pendingRequests", rs);
            out.put("pendingRequestsNote", "以上请求已提交给主人、尚未处理，不要重复提交");
        }
        return out.toJSONString();
    }

    private static String humanizeHeld(long ms) {
        long min = Math.max(0, ms / 60_000);
        if (min < 120) {
            return min + "分钟";
        }
        long hours = min / 60;
        return hours < 48 ? hours + "小时" : (hours / 24) + "天";
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

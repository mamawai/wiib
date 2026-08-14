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
import com.mawai.wiibquant.agent.llm.MessagesSchema;
import com.mawai.wiibquant.agent.llm.ModelCallLimiter;
import com.mawai.wiibquant.agent.llm.ResilientChatService;
import com.mawai.wiibquant.agent.llm.ToolCallTraceHook;
import com.mawai.wiibquant.agent.llm.UsageTrackingChatModel;
import com.mawai.wiibquant.external.sim.SimTradeClient;
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
import org.springframework.ai.chat.messages.AssistantMessage;
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
        wake(trader, boundaryTime, AiTraderDecision.KIND_TRADE);
    }

    /** 手动唤醒（对话轨 wake_trader，已过 HITL）：回路与例行完全相同，只是决策行标 MANUAL——时间线要看得出扳机在人手里。 */
    public void wakeManual(AiTrader trader, long boundaryTime) {
        wake(trader, boundaryTime, AiTraderDecision.KIND_MANUAL);
    }

    private void wake(AiTrader trader, long boundaryTime, String kind) {
        long intervalMs = TraderScheduler.INTERVAL_MS.getOrDefault(trader.getIntervalCode(), 300_000L);
        long budgetSeconds = wakeBudgetSeconds(boundaryTime, intervalMs, nowMs.getAsLong());
        AiTraderDecision decision = baseDecision(trader, boundaryTime);
        decision.setKind(kind);
        if (budgetSeconds < MIN_WAKE_SECONDS) {
            // 事件迟到太多：与其用残余时间仓促决策，不如放弃等下一根新鲜K线（不算失败不计连败）
            decision.setStatus(AiTraderDecision.STATUS_SKIPPED);
            decision.setError("触发过晚（K线信号迟到），距下一边界不足" + MIN_WAKE_SECONDS + "s，放弃本轮");
            decisionMapper.insert(decision);
            log.warn("[Trader] 触发过晚放弃 traderId={} boundary={} budget={}s", trader.getId(), boundaryTime, budgetSeconds);
            return;
        }
        doWake(trader, boundaryTime, budgetSeconds, decision, null);
    }

    /**
     * 波动哨兵警报唤醒：kind=ALERT、wake_time=触发时刻（非K线边界）；
     * 预算截止仍是下一例行边界−5s——警报绝不占用下一根K线。
     */
    public void wakeAlert(AiTrader trader, AlertTrigger trigger) {
        long intervalMs = TraderScheduler.INTERVAL_MS.getOrDefault(trader.getIntervalCode(), 3_600_000L);
        long now = nowMs.getAsLong();
        long boundary = now - Math.floorMod(now, intervalMs);
        long budgetSeconds = wakeBudgetSeconds(boundary, intervalMs, now);
        if (budgetSeconds < MIN_WAKE_SECONDS) {
            // 调度器已预检，这里兜底：例行将至警报静默放弃，不写决策行（SKIPPED 只属于例行调度）
            log.info("[Trader] 例行将至警报放弃 traderId={} {}", trader.getId(), trigger.symbol());
            return;
        }
        AiTraderDecision decision = baseDecision(trader, trigger.triggeredAt());
        decision.setKind(AiTraderDecision.KIND_ALERT);
        doWake(trader, trigger.triggeredAt(), budgetSeconds, decision, trigger);
    }

    private void doWake(AiTrader trader, long boundaryTime, long budgetSeconds,
                        AiTraderDecision decision, AlertTrigger trigger) {
        long start = System.currentTimeMillis();
        try {
            List<FuturesPositionDTO> positions = simTradeClient.getAllPositions(trader.getSimUserId());
            BigDecimal equity = computeEquity(trader.getSimUserId(), positions);
            decision.setEquity(equity);

            if (equity.compareTo(LIQUIDATION_FLOOR) < 0) {
                markLiquidated(trader, decision);
                return;
            }

            String reasoning = runAgentSession(trader, boundaryTime, budgetSeconds, positions, equity, decision, trigger);
            decision.setStatus(AiTraderDecision.STATUS_OK);
            decision.setReasoning(reasoning);
            // 动作都落地了再记权益：本轮开平仓立刻体现在净值曲线，否则要等下一根K线才现形。
            // 单独 try：会话成功 = 这轮就是成功，刷新只是锦上添花。sim 抖一下若翻进外层 catch，
            // 决策全文会被丢掉、整轮判 ERROR 还计连败——连 5 次自动 PAUSED，可每轮其实都下过单了
            try {
                decision.setEquity(computeEquity(trader.getSimUserId(),
                        simTradeClient.getAllPositions(trader.getSimUserId())));
            } catch (Exception e) {
                log.warn("[Trader] 动作后权益刷新失败，沿用唤醒前快照 traderId={} equity={} msg={}",
                        trader.getId(), equity, e.getMessage());
            }
            decision.setLatencyMs((int) (System.currentTimeMillis() - start));
            decisionMapper.insert(decision);
            clearFailures(trader);
        } catch (Exception e) {
            String msg = e instanceof TimeoutException ? "唤醒超时(" + budgetSeconds + "s)"
                    : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[Trader] 唤醒失败 traderId={} boundary={} msg={}", trader.getId(), boundaryTime, msg);
            // 决策行已经落库了（异常出在 insert 之后的收尾，如 clearFailures/markLiquidated 的列级更新）：
            // 这一轮本身是成功的，不该改写成 ERROR 更不该计连败；而且 MP 自增主键 insert 后已把 id
            // 回填进这个对象，同一个对象再 insert 必撞主键，异常会直接逃出唤醒回路——调度器的
            // 虚拟线程只 catch InterruptedException，兜不住。只留日志。
            if (decision.getId() != null) {
                return;
            }
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

    /** ReactAgent 会话：返回模型最终文本；动作轨迹随 decision 一并写入。trigger 非空=警报唤醒（只换开场白）。 */
    private String runAgentSession(AiTrader trader, long boundaryTime, long budgetSeconds,
                                   List<FuturesPositionDTO> positions, BigDecimal equity,
                                   AiTraderDecision decision, AlertTrigger trigger) throws Exception {
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

        // 回注窗口只认交易决策行（白名单：例行/警报/手动）——REVIEW/LEARN 的产出已经走
        // memory/learning_notes 注入，再进最近决策就是重复占字数；ALERT/MANUAL 是真实交易
        // 决策必须保留——警报轮可能刚动过仓位，开场白的"上次唤醒"也取自本列表第一条
        List<AiTraderDecision> recent = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, trader.getId())
                .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                .in(AiTraderDecision::getKind, AiTraderDecision.KIND_TRADE,
                        AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL)
                .lt(AiTraderDecision::getWakeTime, boundaryTime)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT " + RECENT_DECISIONS));

        // 计划懒清理 + 加载：仓位/挂单还活着的计划保留，已了结（止损/止盈/平仓/撤单）的归档；存活计划随持仓/挂单回注
        List<FuturesOrderResponse> pendingOrders = simTradeClient.getPendingOrders(trader.getSimUserId(), null);
        Set<String> liveKeys = new HashSet<>();
        positions.forEach(p -> liveKeys.add(TraderPlanStore.key(p.getSymbol(), p.getSide())));
        for (FuturesOrderResponse o : pendingOrders) {
            if (o.getOrderSide() != null && o.getOrderSide().startsWith("OPEN_")) {
                liveKeys.add(TraderPlanStore.key(o.getSymbol(), o.getOrderSide().substring("OPEN_".length())));
            }
        }
        List<AiTraderPlan> plans = planStore.cleanupStale(trader.getId(), trader.getRoundNo(), liveKeys, boundaryTime);

        List<AiTraderRequest> decided = requestService.decidedUnnotified(trader.getId(), trader.getRoundNo());
        String prompt = promptAssembler.assemble(trader,
                accountStateJson(equity, positions, pendingOrders, plans, boundaryTime,
                        requestService.pendingOf(trader.getId(), trader.getRoundNo()), decided), recent);
        // 结果说一次就够：注入本轮后置已通知，防同一条回执每轮反复出现
        requestService.markNotified(decided);

        // 全量工具轨迹（含数据工具）：收集器在本方法手里，超时 cancel 也保得住已发生的记录
        ToolCallTraceHook trace = new ToolCallTraceHook();
        CompiledGraph<MessagesState<Message>> graph = ReactAgent.<MessagesState<Message>>builder()
                .chatModel(model)
                .stateSerializer(stateSerializer)
                .schema(MessagesSchema.SCHEMA)
                .defaultSystem(prompt)
                .toolsFromObject(tradeTools)
                .toolsFromObject(indicatorToolkit)
                .toolsFromObject(marketToolkit)
                .toolsFromObject(newsToolkit)
                .addExecuteToolsHook(new ModelCallLimiter(MAX_MODEL_CALLS))
                .addExecuteToolsHook(trace)
                // 首轮强制调工具：不看数据不许决策；弱模型不支持 tool_choice 会以 ERROR 落库并最终自动暂停
                .build(ResilientChatService.builder().model(model).forceFirstToolChoice("required").asFactory())
                .compile();

        String instruction = trigger != null
                ? alertInstruction(trader, trigger, recent)
                : routineInstruction(trader, boundaryTime, marketSnapshot(whitelist));
        RunnableConfig config = RunnableConfig.builder()
                .threadId("trader-" + trader.getId() + "-" + boundaryTime).build();

        record SessionOutcome(String reasoning, int modelCalls) {
        }
        // 虚拟线程 + FutureTask 承载超时；超时后本轮作废（已发出的订单不回滚——sim 是事实源）
        FutureTask<SessionOutcome> task = new FutureTask<>(() -> {
            MessagesState<Message> state = graph
                    .invoke(Map.of("messages", List.of(new UserMessage(instruction))), config)
                    .orElseThrow(() -> new IllegalStateException("图执行无返回状态"));
            return new SessionOutcome(finalReasoning(state.messages()),
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
     * 决策正文：往前找最近一条有正文的助手消息，而不是死盯最后一条。
     * 保险丝在工具边收束时，末尾是纯 tool_call 的助手消息 + 未执行占位回执，正文都是空的——
     * 死盯最后一条就会写出 status=OK 却一个字没有的决策行，时间线上与正常决策无从区分。
     */
    private static String finalReasoning(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage assistant
                    && assistant.getText() != null && !assistant.getText().isBlank()) {
                return assistant.getText();
            }
        }
        return "本轮模型全程只在调用工具，没有产出决策正文。";
    }

    /** 例行唤醒开场白：单问题框架 + 行情快照锚定价格水平。 */
    private String routineInstruction(AiTrader trader, long boundaryTime, String snapshot) {
        return "新一根 " + trader.getIntervalCode() + " K线已收盘（"
                + TIME_FMT.format(Instant.ofEpochMilli(boundaryTime)) + "）。"
                + (snapshot.isEmpty() ? "" : "\n行情快照（细节自己用工具查证）：\n" + snapshot)
                + "本轮只需回答一个问题：这根K线收盘后，你的计划需要改变吗？"
                + "先检验上一轮【本轮结论】里的等待条件与各持仓的失效条件，再考虑新机会；"
                + "最后按纪律用【本轮结论】固定格式收尾。";
    }

    /**
     * 警报唤醒开场白：事实全代码注入（振幅/方向/上次唤醒时间/距例行还有多久），
     * 反锚定是灵魂——被波动惊醒正是恐慌平仓的高发场景，必须明说"未收盘不作数、
     * 止损在岗、不因被叫醒而必须动作"。
     */
    private String alertInstruction(AiTrader trader, AlertTrigger trig, List<AiTraderDecision> recent) {
        long intervalMs = TraderScheduler.INTERVAL_MS.getOrDefault(trader.getIntervalCode(), 3_600_000L);
        long toNextMin = Math.max(1, (intervalMs - Math.floorMod(trig.triggeredAt(), intervalMs)) / 60_000);
        String lastWake = recent.isEmpty() ? "本局还没有过唤醒"
                : "在 " + TIME_FMT.format(Instant.ofEpochMilli(recent.get(0).getWakeTime()))
                        + "（约 " + Math.max(1, (trig.triggeredAt() - recent.get(0).getWakeTime()) / 60_000) + " 分钟前）";
        return "⚠️ 行情波动警报（非例行唤醒）：" + trig.symbol() + " 5分钟内波动 "
                + trig.amplitudePct().stripTrailingZeros().toPlainString() + "%（方向：" + trig.direction()
                + "，现价 " + trig.price().stripTrailingZeros().toPlainString() + "）。\n"
                + "你上次唤醒" + lastWake + "，距下一次例行唤醒还有约 " + toNextMin + " 分钟。\n"
                + "注意：当前 " + trader.getIntervalCode() + " K线尚未收盘——你的收盘制失效条件此刻不作数，"
                + "求证请用已收盘的 5m/15m K线。你的止损单仍在自动保护你。\n"
                + "本次只需回答一个问题：这次波动是否动摇了你的持仓计划？计划未被动摇 → HOLD 并说明理由；"
                + "不因为被叫醒而必须动作。最后仍用【本轮结论】固定格式收尾。";
    }

    /**
     * 开场行情快照：各币标记价+资金费率，一行一个——把价格水平先钉进模型的世界，
     * 省下"查户口"的工具轮次；细节与多周期确认仍由模型自己用工具求证。
     * 单币快照拉取失败就跳过，不挡唤醒。
     */
    private String marketSnapshot(Set<String> whitelist) {
        StringBuilder snap = new StringBuilder();
        for (String sym : whitelist.stream().sorted().toList()) {
            try {
                JSONObject p = JSON.parseObject(binanceRestClient.getPremiumIndex(sym));
                snap.append("- ").append(sym).append(" 标记价 ")
                        .append(p.getBigDecimal("markPrice").stripTrailingZeros().toPlainString())
                        .append("，资金费率 ").append(p.getString("lastFundingRate")).append('\n');
            } catch (Exception e) {
                log.debug("[Trader] 行情快照拉取失败 {} msg={}", sym, e.getMessage());
            }
        }
        return snap.toString();
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

    /**
     * 账户状态一次给足（持仓+计划+挂单+待办与结果）：模型不必再花工具预算查户口，
     * 预算留给行情求证。持仓携带交易计划与当前止损止盈——让模型一眼看到
     * "浮亏离止损还远/计划没被证伪"，掐灭恐慌平仓。
     */
    private static String accountStateJson(BigDecimal equity, List<FuturesPositionDTO> positions,
                                           List<FuturesOrderResponse> pendingOrders,
                                           List<AiTraderPlan> plans, long boundaryTime,
                                           List<AiTraderRequest> pendingRequests,
                                           List<AiTraderRequest> decidedRequests) {
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
        // 挂单同样给足：开仓挂单占坑且带着计划（成交后计划全文随持仓回注，这里给轻量版）
        if (pendingOrders != null && !pendingOrders.isEmpty()) {
            JSONArray po = new JSONArray();
            for (FuturesOrderResponse o : pendingOrders) {
                JSONObject row = new JSONObject()
                        .fluentPut("orderId", o.getOrderId())
                        .fluentPut("symbol", o.getSymbol())
                        .fluentPut("orderSide", o.getOrderSide())
                        .fluentPut("quantity", o.getQuantity())
                        .fluentPut("limitPrice", o.getLimitPrice())
                        .fluentPut("leverage", o.getLeverage());
                if (o.getOrderSide() != null && o.getOrderSide().startsWith("OPEN_")) {
                    AiTraderPlan plan = planByKey.get(TraderPlanStore.key(o.getSymbol(),
                            o.getOrderSide().substring("OPEN_".length())));
                    if (plan != null) {
                        row.put("plan", new JSONObject()
                                .fluentPut("playType", plan.getPlayType())
                                .fluentPut("invalidationCondition", plan.getInvalidationCondition()));
                    }
                }
                po.add(row);
            }
            out.put("pendingOrders", po);
        }
        // 已处理请求的结果回注一次：模型提的请求什么下场必须告诉它，不然它只能从仓位变化倒猜
        if (decidedRequests != null && !decidedRequests.isEmpty()) {
            JSONArray rr = new JSONArray();
            for (AiTraderRequest r : decidedRequests) {
                rr.add(new JSONObject()
                        .fluentPut("type", r.getType())
                        .fluentPut("symbol", r.getSymbol())
                        .fluentPut("quantity", r.getQuantity())
                        .fluentPut("decision", AiTraderRequest.STATUS_APPROVED.equals(r.getStatus())
                                ? "主人已同意" : "主人已拒绝")
                        .fluentPut("result", r.getExecutedResult()));
            }
            out.put("requestResults", rr);
            out.put("requestResultsNote", "你此前请求的处理结果，只通知这一次；仓位变化已反映在 positions 里");
        }
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
        d.setKind(AiTraderDecision.KIND_TRADE);
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

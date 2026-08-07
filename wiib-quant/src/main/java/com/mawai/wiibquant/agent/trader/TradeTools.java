package com.mawai.wiibquant.agent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderRequest;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 交易工具（非 Spring bean）：每次唤醒 new 一个，绑定该 trader 的 sim 子账户与白名单。
 * 所有调用（含被 TradeGuard 拒绝的）都记入 actions 列表——决策日志的动作轨迹，
 * 拒绝原因原样返回给模型，模型可自行修正重试。
 */
@Slf4j
public class TradeTools {

    /** 唤醒上下文：计划落库与风险护栏所需的 trader 侧信息 */
    public record WakeCtx(long traderId, int roundNo, long boundaryTime, TraderRiskConfig risk) {
    }

    /** 本类自带富记录（结果/拒因）的工具名——轨迹合并时用富记录替换 hook 的轻量占位 */
    public static final Set<String> RECORDED_TOOLS = Set.of(
            "get_account", "open_position", "close_position", "set_stop_loss",
            "set_take_profit", "cancel_order", "write_plan");

    private final SimTradeClient simTradeClient;
    private final long simUserId;
    private final Set<String> symbolWhitelist;
    private final BigDecimal equity;
    /** 现价查询（symbol → mark price）；由唤醒回路注入，通常取最近K线收盘价 */
    private final Function<String, BigDecimal> markPrice;
    private final TraderPlanStore planStore;
    /** 自主加/减仓关掉时，工具调用转成待确认请求走这里 */
    private final TraderRequestService requestService;
    private final WakeCtx ctx;
    /** 本次唤醒的动作轨迹，唤醒回路收走序列化进 ai_trader_decision.actions_json */
    private final List<JSONObject> actions = new ArrayList<>();

    public TradeTools(SimTradeClient simTradeClient, long simUserId, Set<String> symbolWhitelist,
                      BigDecimal equity, Function<String, BigDecimal> markPrice,
                      TraderPlanStore planStore, TraderRequestService requestService, WakeCtx ctx) {
        this.simTradeClient = simTradeClient;
        this.simUserId = simUserId;
        this.symbolWhitelist = symbolWhitelist;
        this.equity = equity;
        this.markPrice = markPrice;
        this.planStore = planStore;
        this.requestService = requestService;
        this.ctx = ctx;
    }

    public List<JSONObject> actions() {
        return actions;
    }

    @Tool(name = "get_account", description = """
            Get your full account state: available balance, open positions (with id, side, quantity,
            entry price, leverage, unrealized PnL, liquidation price, current stop-loss/take-profit)
            and pending limit orders. ALWAYS check this before trading decisions.""")
    public String getAccount() {
        try {
            JSONObject out = new JSONObject();
            out.put("balance", simTradeClient.getBalance(simUserId));
            List<FuturesPositionDTO> positions = simTradeClient.getAllPositions(simUserId);
            JSONArray ps = new JSONArray();
            for (FuturesPositionDTO p : positions) {
                JSONObject row = new JSONObject();
                row.put("positionId", p.getId());
                row.put("symbol", p.getSymbol());
                row.put("side", p.getSide());
                row.put("quantity", p.getQuantity());
                row.put("entryPrice", p.getEntryPrice());
                row.put("leverage", p.getLeverage());
                row.put("margin", p.getMargin());
                row.put("unrealizedPnl", p.getUnrealizedPnl());
                row.put("liquidationPrice", p.getLiquidationPrice());
                row.put("stopLosses", p.getStopLosses());
                row.put("takeProfits", p.getTakeProfits());
                ps.add(row);
            }
            out.put("positions", ps);
            List<FuturesOrderResponse> pending = simTradeClient.getPendingOrders(simUserId, null);
            out.put("pendingOrders", JSON.toJSON(pending));
            return ok("get_account", null, out.toJSONString());
        } catch (Exception e) {
            return fail("get_account", null, e);
        }
    }

    @Tool(name = "open_position", description = """
            Open a futures position on your sim account. Hard rules (violations are rejected with a
            reason you can fix): leverage 1-20, margin (=quantity*price/leverage) at most 50% of equity,
            per-trade risk (=|entry-stopLoss|*quantity) at most your configured percent of equity,
            LIMIT price within 5% of mark, stopLossPrice REQUIRED and on the correct side.
            playType is your thesis label: BREAKOUT/PULLBACK/REVERSAL/TREND_FOLLOW/RANGE/NEWS/FUNDING/OTHER.
            signalsUsed: one sentence citing the concrete data fields your thesis rests on.
            invalidationCondition: the market condition that would prove your thesis wrong (NOT a PnL
            number) — it becomes part of your position's plan and is your only ground for manual exit.""")
    public String openPosition(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                               @ToolParam(description = "LONG or SHORT") String side,
                               @ToolParam(description = "MARKET or LIMIT") String orderType,
                               @ToolParam(description = "Position size in coins, e.g. 0.01") double quantity,
                               @ToolParam(description = "Leverage 1-20") int leverage,
                               @ToolParam(description = "Limit price; required for LIMIT, ignored for MARKET", required = false) Double limitPrice,
                               @ToolParam(description = "Stop-loss price, REQUIRED") double stopLossPrice,
                               @ToolParam(description = "Take-profit price, optional", required = false) Double takeProfitPrice,
                               @ToolParam(description = "Thesis label: BREAKOUT/PULLBACK/REVERSAL/TREND_FOLLOW/RANGE/NEWS/FUNDING/OTHER") String playType,
                               @ToolParam(description = "One sentence citing concrete data behind this trade") String signalsUsed,
                               @ToolParam(description = "Market condition that proves this thesis wrong, e.g. '1h close back below 64200 box top'") String invalidationCondition) {
        TradeGuard.OpenReq req = new TradeGuard.OpenReq(symbol, side, orderType,
                BigDecimal.valueOf(quantity), leverage,
                limitPrice == null ? null : BigDecimal.valueOf(limitPrice),
                BigDecimal.valueOf(stopLossPrice),
                takeProfitPrice == null ? null : BigDecimal.valueOf(takeProfitPrice),
                playType, signalsUsed, invalidationCondition);
        JSONObject argSummary = openArgs(req);
        BigDecimal mark;
        try {
            mark = markPrice.apply(symbol);
        } catch (Exception e) {
            return fail("open_position", argSummary, e);
        }
        Account acct = account();
        String reject = TradeGuard.validateOpen(req, equity, mark, symbolWhitelist, ctx.risk(), acct.snaps());
        if (reject != null) {
            // action() 内部已入轨迹列表，不许再包一层 add——否则拒绝动作双计
            return rejected("open_position", argSummary, reject);
        }
        // 加仓需主人确认：转请求即返回，本轮唤醒继续跑，不在这里等
        FuturesPositionDTO sameSide = acct.sameSide(req.symbol(), req.side());
        if (sameSide != null && !ctx.risk().allowSelfAdd()) {
            AiTraderRequest ask = new AiTraderRequest();
            ask.setTraderId(ctx.traderId());
            ask.setRoundNo(ctx.roundNo());
            ask.setType(AiTraderRequest.TYPE_ADD);
            ask.setSymbol(req.symbol());
            ask.setSide(req.side());
            ask.setPositionId(sameSide.getId());
            ask.setQuantity(req.quantity());
            ask.setLeverage(req.leverage());
            ask.setRequestPrice(mark);
            ask.setReason(req.signalsUsed());
            ask.setWakeTime(ctx.boundaryTime());
            return ok("open_position", argSummary, requestService.submit(ask));
        }
        try {
            FuturesOpenRequest openReq = new FuturesOpenRequest();
            openReq.setSymbol(req.symbol());
            openReq.setSide(req.side());
            // 全仓显式声明：权益口径（全仓保证金不重复计）依赖这个事实，不许靠 sim 远端默认值
            openReq.setMarginMode(FuturesPosition.CROSS);
            openReq.setOrderType(req.orderType());
            openReq.setQuantity(req.quantity());
            openReq.setLeverage(req.leverage());
            openReq.setLimitPrice("LIMIT".equals(req.orderType()) ? req.limitPrice() : null);
            openReq.setMemo("ai_trader:" + req.playType());
            FuturesOpenRequest.StopLoss sl = new FuturesOpenRequest.StopLoss();
            sl.setPrice(req.stopLossPrice());
            sl.setQuantity(req.quantity());
            openReq.setStopLosses(List.of(sl));
            if (req.takeProfitPrice() != null) {
                FuturesOpenRequest.TakeProfit tp = new FuturesOpenRequest.TakeProfit();
                tp.setPrice(req.takeProfitPrice());
                tp.setQuantity(req.quantity());
                openReq.setTakeProfits(List.of(tp));
            }
            FuturesOrderResponse resp = simTradeClient.openPosition(simUserId, openReq);
            persistPlan(req, mark);
            return ok("open_position", argSummary, JSON.toJSONString(resp));
        } catch (Exception e) {
            return fail("open_position", argSummary, e);
        }
    }

    /** 成交/挂单即落计划（下轮唤醒回注）；写失败只记日志不回错——交易已真实发生，回错误会诱导模型重复开仓。 */
    private void persistPlan(TradeGuard.OpenReq req, BigDecimal mark) {
        try {
            AiTraderPlan plan = new AiTraderPlan();
            plan.setTraderId(ctx.traderId());
            plan.setRoundNo(ctx.roundNo());
            plan.setSymbol(req.symbol());
            plan.setSide(req.side());
            plan.setPlayType(req.playType());
            plan.setSignalsUsed(req.signalsUsed());
            plan.setInvalidationCondition(req.invalidationCondition());
            plan.setEntryPrice("LIMIT".equals(req.orderType()) ? req.limitPrice() : mark);
            plan.setStopLossPrice(req.stopLossPrice());
            plan.setTakeProfitPrice(req.takeProfitPrice());
            plan.setOpenedWakeTime(ctx.boundaryTime());
            planStore.upsert(plan);
        } catch (Exception e) {
            log.warn("[TradeTools] 计划落库失败 traderId={} {} msg={}", ctx.traderId(), req.symbol(), e.getMessage());
        }
    }

    @Tool(name = "close_position", description = """
            Close (part of) an open position by positionId (get it from get_account), at market price.
            quantity: coins to close; pass the full position quantity to close it entirely.
            reason: one sentence on why you are closing now.""")
    public String closePosition(@ToolParam(description = "Position id from get_account") long positionId,
                                @ToolParam(description = "Quantity in coins to close") double quantity,
                                @ToolParam(description = "One sentence: why close now") String reason) {
        JSONObject args = new JSONObject()
                .fluentPut("positionId", positionId)
                .fluentPut("quantity", quantity)
                .fluentPut("reason", reason);
        try {
            // 减仓需主人确认：转请求即返回。止损止盈单不走这条路，仍自动执行，风险有保护
            if (!ctx.risk().allowSelfReduce()) {
                FuturesPositionDTO pos = findPosition(positionId);
                if (pos == null) {
                    return rejected("close_position", args, "仓位不存在，先 get_account 看当前持仓");
                }
                AiTraderRequest ask = new AiTraderRequest();
                ask.setTraderId(ctx.traderId());
                ask.setRoundNo(ctx.roundNo());
                ask.setType(AiTraderRequest.TYPE_REDUCE);
                ask.setSymbol(pos.getSymbol());
                ask.setSide(pos.getSide());
                ask.setPositionId(positionId);
                ask.setQuantity(BigDecimal.valueOf(quantity));
                ask.setRequestPrice(markPrice.apply(pos.getSymbol()));
                ask.setReason(reason);
                ask.setWakeTime(ctx.boundaryTime());
                return ok("close_position", args, requestService.submit(ask));
            }
            FuturesCloseRequest req = new FuturesCloseRequest();
            req.setPositionId(positionId);
            req.setQuantity(BigDecimal.valueOf(quantity));
            req.setOrderType("MARKET");
            FuturesOrderResponse resp = simTradeClient.closePosition(simUserId, req);
            return ok("close_position", args, JSON.toJSONString(resp));
        } catch (Exception e) {
            return fail("close_position", args, e);
        }
    }

    @Tool(name = "set_stop_loss", description = """
            Replace the stop-loss of an open position (positionId from get_account). TIGHTEN ONLY:
            LONG stops may only move UP, SHORT stops only DOWN (relative to the current stop) —
            widening a stop means your thesis is shaken; check your invalidation condition instead.
            reason is REQUIRED and becomes part of the position's public plan revision history.""")
    public String setStopLoss(@ToolParam(description = "Position id from get_account") long positionId,
                              @ToolParam(description = "New stop-loss price") double stopLossPrice,
                              @ToolParam(description = "Quantity in coins covered by the stop") double quantity,
                              @ToolParam(description = "Why you move the stop now, e.g. 'price +2R, lock breakeven'") String reason) {
        JSONObject args = new JSONObject()
                .fluentPut("positionId", positionId)
                .fluentPut("stopLossPrice", stopLossPrice)
                .fluentPut("quantity", quantity)
                .fluentPut("reason", reason);
        try {
            FuturesPositionDTO pos = findPosition(positionId);
            if (pos == null) {
                return rejected("set_stop_loss", args, "positionId不存在，请先 get_account 查当前持仓");
            }
            if (reason == null || reason.isBlank()) {
                return rejected("set_stop_loss", args, "必须给reason：说明为什么现在移动止损（会进公开修订历史）");
            }
            // 止损只许收紧：放宽止损=放大风险=移动球门柱；想给仓位更多空间说明论点已动摇，该查失效条件而不是松止损
            boolean isLong = "LONG".equals(pos.getSide());
            BigDecimal newStop = BigDecimal.valueOf(stopLossPrice);
            BigDecimal loosest = extremePrice(
                    pos.getStopLosses() == null ? List.of()
                            : pos.getStopLosses().stream().map(FuturesStopLoss::getPrice).toList(), isLong);
            if (loosest != null && (isLong ? newStop.compareTo(loosest) < 0 : newStop.compareTo(loosest) > 0)) {
                return rejected("set_stop_loss", args, "止损只许收紧（多单上移/空单下移，当前止损"
                        + loosest.stripTrailingZeros().toPlainString()
                        + "）——想给仓位更多空间说明论点已动摇，去检查失效条件");
            }
            FuturesStopLossRequest req = new FuturesStopLossRequest();
            req.setPositionId(positionId);
            FuturesStopLossRequest.StopLossItem item = new FuturesStopLossRequest.StopLossItem();
            item.setPrice(newStop);
            item.setQuantity(BigDecimal.valueOf(quantity));
            req.setStopLosses(List.of(item));
            simTradeClient.setStopLoss(simUserId, req);
            revisePlan(pos, "移动止损",
                    (loosest == null ? "无" : loosest.stripTrailingZeros().toPlainString())
                            + "→" + newStop.stripTrailingZeros().toPlainString(), reason);
            return ok("set_stop_loss", args, "{\"ok\":true}");
        } catch (Exception e) {
            return fail("set_stop_loss", args, e);
        }
    }

    @Tool(name = "set_take_profit", description = """
            Replace the take-profit of an open position (positionId from get_account). AWAY ONLY:
            LONG targets may only move UP, SHORT targets only DOWN — lowering a LONG target toward
            price would be a disguised panic exit; to leave early, cite your invalidation condition
            and use close_position instead. reason is REQUIRED (public plan revision history).""")
    public String setTakeProfit(@ToolParam(description = "Position id from get_account") long positionId,
                                @ToolParam(description = "New take-profit price") double takeProfitPrice,
                                @ToolParam(description = "Quantity in coins covered by the target") double quantity,
                                @ToolParam(description = "Why you move the target now, e.g. 'trend accelerating, extend to next resistance'") String reason) {
        JSONObject args = new JSONObject()
                .fluentPut("positionId", positionId)
                .fluentPut("takeProfitPrice", takeProfitPrice)
                .fluentPut("quantity", quantity)
                .fluentPut("reason", reason);
        try {
            FuturesPositionDTO pos = findPosition(positionId);
            if (pos == null) {
                return rejected("set_take_profit", args, "positionId不存在，请先 get_account 查当前持仓");
            }
            if (reason == null || reason.isBlank()) {
                return rejected("set_take_profit", args, "必须给reason：说明为什么现在移动目标位（会进公开修订历史）");
            }
            // 止盈只许远离入场：把目标降到现价上方一点点秒触发＝"止盈带走"马甲下的恐慌平仓
            boolean isLong = "LONG".equals(pos.getSide());
            BigDecimal newTarget = BigDecimal.valueOf(takeProfitPrice);
            BigDecimal farthest = extremePrice(
                    pos.getTakeProfits() == null ? List.of()
                            : pos.getTakeProfits().stream().map(FuturesTakeProfit::getPrice).toList(), isLong);
            if (farthest != null && (isLong ? newTarget.compareTo(farthest) < 0 : newTarget.compareTo(farthest) > 0)) {
                return rejected("set_take_profit", args, "止盈只许向远离入场的方向移动（多单上移/空单下移，当前目标"
                        + farthest.stripTrailingZeros().toPlainString()
                        + "）——想提前离场请检查失效条件并用 close_position 说明理由");
            }
            FuturesTakeProfitRequest req = new FuturesTakeProfitRequest();
            req.setPositionId(positionId);
            FuturesTakeProfitRequest.TakeProfitItem item = new FuturesTakeProfitRequest.TakeProfitItem();
            item.setPrice(newTarget);
            item.setQuantity(BigDecimal.valueOf(quantity));
            req.setTakeProfits(List.of(item));
            simTradeClient.setTakeProfit(simUserId, req);
            revisePlan(pos, "移动止盈",
                    (farthest == null ? "无" : farthest.stripTrailingZeros().toPlainString())
                            + "→" + newTarget.stripTrailingZeros().toPlainString(), reason);
            return ok("set_take_profit", args, "{\"ok\":true}");
        } catch (Exception e) {
            return fail("set_take_profit", args, e);
        }
    }

    @Tool(name = "write_plan", description = """
            Backfill a trading plan for an open position that has NO plan record (positionId from
            get_account). Rejected if the position already has a plan — plans are immutable; the only
            legal ways to change a thesis are adding to the position or closing and reopening.""")
    public String writePlan(@ToolParam(description = "Position id from get_account") long positionId,
                            @ToolParam(description = "Thesis label: BREAKOUT/PULLBACK/REVERSAL/TREND_FOLLOW/RANGE/NEWS/FUNDING/OTHER") String playType,
                            @ToolParam(description = "One sentence citing concrete data behind holding this position") String signalsUsed,
                            @ToolParam(description = "Market condition that proves this thesis wrong (NOT a PnL number)") String invalidationCondition,
                            @ToolParam(description = "Target price, optional", required = false) Double targetPrice) {
        JSONObject args = new JSONObject()
                .fluentPut("positionId", positionId)
                .fluentPut("playType", playType)
                .fluentPut("signalsUsed", signalsUsed)
                .fluentPut("invalidationCondition", invalidationCondition)
                .fluentPut("targetPrice", targetPrice);
        try {
            FuturesPositionDTO pos = findPosition(positionId);
            if (pos == null) {
                return rejected("write_plan", args, "positionId不存在，请先 get_account 查当前持仓");
            }
            if (planStore.find(ctx.traderId(), ctx.roundNo(), pos.getSymbol(), pos.getSide()) != null) {
                return rejected("write_plan", args,
                        "该持仓已有计划，计划不可改写——加仓覆盖或平仓重开才是改论点的合法途径");
            }
            if (playType == null || !TradeGuard.PLAY_TYPES.contains(playType)) {
                return rejected("write_plan", args, "playType必须是: " + TradeGuard.PLAY_TYPES);
            }
            if (invalidationCondition == null || invalidationCondition.isBlank()) {
                return rejected("write_plan", args,
                        "必须给invalidationCondition失效条件：一句话说明什么市场状况会证明这个论点错了（市场条件，不是盈亏数字）");
            }
            boolean isLong = "LONG".equals(pos.getSide());
            AiTraderPlan plan = new AiTraderPlan();
            plan.setTraderId(ctx.traderId());
            plan.setRoundNo(ctx.roundNo());
            plan.setSymbol(pos.getSymbol());
            plan.setSide(pos.getSide());
            plan.setPlayType(playType);
            plan.setSignalsUsed(signalsUsed);
            plan.setInvalidationCondition(invalidationCondition);
            plan.setEntryPrice(pos.getEntryPrice());
            plan.setStopLossPrice(extremePrice(pos.getStopLosses() == null ? List.of()
                    : pos.getStopLosses().stream().map(FuturesStopLoss::getPrice).toList(), isLong));
            plan.setTakeProfitPrice(targetPrice == null ? null : BigDecimal.valueOf(targetPrice));
            // 持有时长按仓位真实开仓时间算，不是补立时刻——补立不能"清零仓龄"
            plan.setOpenedWakeTime(pos.getCreatedAt() != null
                    ? pos.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    : ctx.boundaryTime());
            TraderPlanStore.appendRevision(plan, ctx.boundaryTime(), "补立",
                    "为无计划持仓补立计划", signalsUsed);
            planStore.upsert(plan);
            return ok("write_plan", args, "{\"ok\":true}");
        } catch (Exception e) {
            return fail("write_plan", args, e);
        }
    }

    /**
     * 账户当前占位快照：已成交持仓 + 未成交挂单。
     * 每次开仓都现查——一轮里模型可能连开几笔，用唤醒开头的快照会算漏。
     * 挂单必须计入：只数持仓的话，先挂三个不同币的限价单就绕过了单仓限制。
     */
    private record Account(List<FuturesPositionDTO> positions, List<FuturesOrderResponse> pending) {
        List<TradeGuard.PosSnap> snaps() {
            List<TradeGuard.PosSnap> out = new ArrayList<>();
            positions.forEach(p ->
                    out.add(new TradeGuard.PosSnap(p.getSymbol(), p.getSide(), p.getLeverage(), true)));
            for (FuturesOrderResponse o : pending) {
                // orderSide 形如 OPEN_LONG / CLOSE_LONG：只有开仓挂单才占坑，平仓挂单是在减仓
                String s = o.getOrderSide();
                if (s == null || !s.startsWith("OPEN_")) {
                    continue;
                }
                out.add(new TradeGuard.PosSnap(o.getSymbol(), s.substring("OPEN_".length()), o.getLeverage(), false));
            }
            return out;
        }

        /** 同币同向的已成交仓位＝本次开仓是加仓（sim 会并入这一仓） */
        FuturesPositionDTO sameSide(String symbol, String side) {
            return positions.stream()
                    .filter(p -> p.getSymbol().equals(symbol) && p.getSide().equals(side))
                    .findFirst().orElse(null);
        }
    }

    private Account account() {
        return new Account(simTradeClient.getAllPositions(simUserId),
                simTradeClient.getPendingOrders(simUserId, null));
    }

    /** 按 positionId 从 sim 现查持仓（工具间不共享缓存——sim 是唯一事实源）。 */
    private FuturesPositionDTO findPosition(long positionId) {
        return simTradeClient.getAllPositions(simUserId).stream()
                .filter(p -> p.getId() != null && p.getId() == positionId)
                .findFirst().orElse(null);
    }

    /** 多单取最高价/空单取最低价（多档止损止盈的"最松/最远"那一档，作为收紧/远离判定基准）。 */
    private static BigDecimal extremePrice(List<BigDecimal> prices, boolean isLong) {
        return prices.stream().filter(java.util.Objects::nonNull)
                .reduce((a, b) -> isLong ? a.max(b) : a.min(b)).orElse(null);
    }

    /** 有计划就留修订；没有计划（旧仓）不强求——write_plan 是它的补救路径。 */
    private void revisePlan(FuturesPositionDTO pos, String type, String change, String reason) {
        try {
            AiTraderPlan plan = planStore.find(ctx.traderId(), ctx.roundNo(), pos.getSymbol(), pos.getSide());
            if (plan != null) {
                planStore.revise(plan, ctx.boundaryTime(), type, change, reason);
            }
        } catch (Exception e) {
            log.warn("[TradeTools] 修订落库失败 traderId={} {} msg={}", ctx.traderId(), pos.getSymbol(), e.getMessage());
        }
    }

    /** 护栏拒绝：与 open_position 的 REJECTED 同一语义，进动作轨迹，模型可修正重试。 */
    private String rejected(String tool, JSONObject args, String reason) {
        action(tool, args).fluentPut("rejected", reason);
        return "REJECTED: " + reason;
    }

    @Tool(name = "cancel_order", description = "Cancel a pending limit order by orderId (from get_account pendingOrders).")
    public String cancelOrder(@ToolParam(description = "Order id from get_account pendingOrders") long orderId) {
        JSONObject args = new JSONObject().fluentPut("orderId", orderId);
        try {
            FuturesOrderResponse resp = simTradeClient.cancelOrder(simUserId, orderId);
            return ok("cancel_order", args, JSON.toJSONString(resp));
        } catch (Exception e) {
            return fail("cancel_order", args, e);
        }
    }

    private static JSONObject openArgs(TradeGuard.OpenReq req) {
        return new JSONObject()
                .fluentPut("symbol", req.symbol())
                .fluentPut("side", req.side())
                .fluentPut("orderType", req.orderType())
                .fluentPut("quantity", req.quantity())
                .fluentPut("leverage", req.leverage())
                .fluentPut("limitPrice", req.limitPrice())
                .fluentPut("stopLossPrice", req.stopLossPrice())
                .fluentPut("takeProfitPrice", req.takeProfitPrice())
                .fluentPut("playType", req.playType())
                .fluentPut("signalsUsed", req.signalsUsed())
                .fluentPut("invalidationCondition", req.invalidationCondition());
    }

    private JSONObject action(String tool, JSONObject args) {
        JSONObject a = new JSONObject().fluentPut("tool", tool);
        if (args != null) {
            a.put("args", args);
        }
        actions.add(a);
        return a;
    }

    /** 成功：结果同时写进动作轨迹（摘要）与工具返回值（全文）。 */
    private String ok(String tool, JSONObject args, Object payload) {
        String s = payload instanceof String str ? str : JSON.toJSONString(payload);
        // 轨迹里只存摘要，防止 get_account 大 JSON 把决策行撑爆
        action(tool, args).fluentPut("status", "ok")
                .fluentPut("result", s.length() > 400 ? s.substring(0, 400) + "…" : JSON.parse(s));
        return s;
    }

    private String fail(String tool, JSONObject args, Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        action(tool, args).fluentPut("status", "error").fluentPut("error", msg);
        log.warn("[TradeTools] {} 失败 simUserId={} msg={}", tool, simUserId, msg);
        return "ERROR: " + msg;
    }
}

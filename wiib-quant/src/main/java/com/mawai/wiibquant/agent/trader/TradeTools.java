package com.mawai.wiibquant.agent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
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

    private final SimTradeClient simTradeClient;
    private final long simUserId;
    private final Set<String> symbolWhitelist;
    private final BigDecimal equity;
    /** 现价查询（symbol → mark price）；由唤醒回路注入，通常取最近K线收盘价 */
    private final Function<String, BigDecimal> markPrice;
    /** 本次唤醒的动作轨迹，唤醒回路收走序列化进 ai_trader_decision.actions_json */
    private final List<JSONObject> actions = new ArrayList<>();

    public TradeTools(SimTradeClient simTradeClient, long simUserId, Set<String> symbolWhitelist,
                      BigDecimal equity, Function<String, BigDecimal> markPrice) {
        this.simTradeClient = simTradeClient;
        this.simUserId = simUserId;
        this.symbolWhitelist = symbolWhitelist;
        this.equity = equity;
        this.markPrice = markPrice;
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
            LIMIT price within 5% of mark, stopLossPrice REQUIRED and on the correct side.
            playType is your thesis label: BREAKOUT/PULLBACK/REVERSAL/TREND_FOLLOW/RANGE/NEWS/FUNDING/OTHER.
            signalsUsed: one sentence citing the concrete data fields your thesis rests on.""")
    public String openPosition(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                               @ToolParam(description = "LONG or SHORT") String side,
                               @ToolParam(description = "MARKET or LIMIT") String orderType,
                               @ToolParam(description = "Position size in coins, e.g. 0.01") double quantity,
                               @ToolParam(description = "Leverage 1-20") int leverage,
                               @ToolParam(description = "Limit price; required for LIMIT, ignored for MARKET", required = false) Double limitPrice,
                               @ToolParam(description = "Stop-loss price, REQUIRED") double stopLossPrice,
                               @ToolParam(description = "Take-profit price, optional", required = false) Double takeProfitPrice,
                               @ToolParam(description = "Thesis label: BREAKOUT/PULLBACK/REVERSAL/TREND_FOLLOW/RANGE/NEWS/FUNDING/OTHER") String playType,
                               @ToolParam(description = "One sentence citing concrete data behind this trade") String signalsUsed) {
        TradeGuard.OpenReq req = new TradeGuard.OpenReq(symbol, side, orderType,
                BigDecimal.valueOf(quantity), leverage,
                limitPrice == null ? null : BigDecimal.valueOf(limitPrice),
                BigDecimal.valueOf(stopLossPrice),
                takeProfitPrice == null ? null : BigDecimal.valueOf(takeProfitPrice),
                playType, signalsUsed);
        JSONObject argSummary = openArgs(req);
        BigDecimal mark;
        try {
            mark = markPrice.apply(symbol);
        } catch (Exception e) {
            return fail("open_position", argSummary, e);
        }
        String reject = TradeGuard.validateOpen(req, equity, mark, symbolWhitelist);
        if (reject != null) {
            actions.add(action("open_position", argSummary).fluentPut("rejected", reject));
            return "REJECTED: " + reject;
        }
        try {
            FuturesOpenRequest openReq = new FuturesOpenRequest();
            openReq.setSymbol(req.symbol());
            openReq.setSide(req.side());
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
            return ok("open_position", argSummary, JSON.toJSONString(resp));
        } catch (Exception e) {
            return fail("open_position", argSummary, e);
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
            Replace the stop-loss orders of an open position (positionId from get_account).
            Use to trail your stop after price moves in your favor.""")
    public String setStopLoss(@ToolParam(description = "Position id from get_account") long positionId,
                              @ToolParam(description = "New stop-loss price") double stopLossPrice,
                              @ToolParam(description = "Quantity in coins covered by the stop") double quantity) {
        JSONObject args = new JSONObject()
                .fluentPut("positionId", positionId)
                .fluentPut("stopLossPrice", stopLossPrice)
                .fluentPut("quantity", quantity);
        try {
            FuturesStopLossRequest req = new FuturesStopLossRequest();
            req.setPositionId(positionId);
            FuturesStopLossRequest.StopLossItem item = new FuturesStopLossRequest.StopLossItem();
            item.setPrice(BigDecimal.valueOf(stopLossPrice));
            item.setQuantity(BigDecimal.valueOf(quantity));
            req.setStopLosses(List.of(item));
            simTradeClient.setStopLoss(simUserId, req);
            return ok("set_stop_loss", args, "{\"ok\":true}");
        } catch (Exception e) {
            return fail("set_stop_loss", args, e);
        }
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
                .fluentPut("signalsUsed", req.signalsUsed());
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

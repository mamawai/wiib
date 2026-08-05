package com.mawai.wiibquant.agent.trader;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * 开仓硬校验：AI 会幻觉出荒谬参数，入口一票否决。拒绝原因用中文原样返回给模型——
 * 模型看得懂就能自行修正重试。虚拟盘不需要更重的防御，仅此十条。
 */
public final class TradeGuard {

    /** 论点标签枚举：写入时结构化，未来挖掘"哪些打法反复赚钱"直接 SQL 分桶 */
    public static final Set<String> PLAY_TYPES = Set.of(
            "BREAKOUT", "PULLBACK", "REVERSAL", "TREND_FOLLOW", "RANGE", "NEWS", "FUNDING", "OTHER");

    public static final int MAX_LEVERAGE = 20;
    /** 单笔保证金占权益上限 */
    public static final BigDecimal MAX_MARGIN_PCT = new BigDecimal("0.5");
    /** 限价偏离现价上限 */
    public static final BigDecimal MAX_LIMIT_DEVIATION = new BigDecimal("0.05");

    /** 开仓请求（工具参数的结构化镜像）；with* 便于测试构造变体。 */
    public record OpenReq(String symbol, String side, String orderType, BigDecimal quantity, Integer leverage,
                          BigDecimal limitPrice, BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                          String playType, String signalsUsed) {
        public OpenReq withSymbol(String v) { return new OpenReq(v, side, orderType, quantity, leverage, limitPrice, stopLossPrice, takeProfitPrice, playType, signalsUsed); }
        public OpenReq withSide(String v) { return new OpenReq(symbol, v, orderType, quantity, leverage, limitPrice, stopLossPrice, takeProfitPrice, playType, signalsUsed); }
        public OpenReq withOrderType(String v) { return new OpenReq(symbol, side, v, quantity, leverage, limitPrice, stopLossPrice, takeProfitPrice, playType, signalsUsed); }
        public OpenReq withQuantity(BigDecimal v) { return new OpenReq(symbol, side, orderType, v, leverage, limitPrice, stopLossPrice, takeProfitPrice, playType, signalsUsed); }
        public OpenReq withLeverage(Integer v) { return new OpenReq(symbol, side, orderType, quantity, v, limitPrice, stopLossPrice, takeProfitPrice, playType, signalsUsed); }
        public OpenReq withLimitPrice(BigDecimal v) { return new OpenReq(symbol, side, orderType, quantity, leverage, v, stopLossPrice, takeProfitPrice, playType, signalsUsed); }
        public OpenReq withStopLossPrice(BigDecimal v) { return new OpenReq(symbol, side, orderType, quantity, leverage, limitPrice, v, takeProfitPrice, playType, signalsUsed); }
        public OpenReq withTakeProfitPrice(BigDecimal v) { return new OpenReq(symbol, side, orderType, quantity, leverage, limitPrice, stopLossPrice, v, playType, signalsUsed); }
        public OpenReq withPlayType(String v) { return new OpenReq(symbol, side, orderType, quantity, leverage, limitPrice, stopLossPrice, takeProfitPrice, v, signalsUsed); }
    }

    private TradeGuard() {
    }

    /** 返回 null=放行；否则给模型看的中文拒绝原因。 */
    public static String validateOpen(OpenReq req, BigDecimal equity, BigDecimal markPrice, Set<String> symbolWhitelist) {
        if (req.symbol() == null || !symbolWhitelist.contains(req.symbol())) {
            return "symbol不在白名单内，可交易: " + symbolWhitelist;
        }
        boolean isLong = "LONG".equals(req.side());
        if (!isLong && !"SHORT".equals(req.side())) {
            return "side必须是LONG或SHORT";
        }
        boolean isLimit = "LIMIT".equals(req.orderType());
        if (!isLimit && !"MARKET".equals(req.orderType())) {
            return "orderType必须是MARKET或LIMIT";
        }
        if (req.quantity() == null || req.quantity().signum() <= 0) {
            return "数量必须为正数";
        }
        if (req.leverage() == null || req.leverage() < 1 || req.leverage() > MAX_LEVERAGE) {
            return "杠杆必须在1~" + MAX_LEVERAGE + "倍之间";
        }
        if (req.playType() == null || !PLAY_TYPES.contains(req.playType())) {
            return "playType必须是: " + PLAY_TYPES;
        }
        if (isLimit && req.limitPrice() == null) {
            return "限价单必须给limitPrice";
        }
        if (isLimit) {
            BigDecimal deviation = req.limitPrice().subtract(markPrice).abs()
                    .divide(markPrice, 8, RoundingMode.HALF_UP);
            if (deviation.compareTo(MAX_LIMIT_DEVIATION) > 0) {
                return "限价偏离现价" + deviation.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP)
                        + "%，超过5%上限（现价" + markPrice.stripTrailingZeros().toPlainString() + "）";
            }
        }
        // 入场参考价：限价单按限价、市价单按现价
        BigDecimal entryRef = isLimit ? req.limitPrice() : markPrice;
        BigDecimal margin = req.quantity().multiply(entryRef)
                .divide(BigDecimal.valueOf(req.leverage()), 8, RoundingMode.HALF_UP);
        BigDecimal maxMargin = equity.multiply(MAX_MARGIN_PCT);
        if (margin.compareTo(maxMargin) > 0) {
            return "保证金" + margin.setScale(0, RoundingMode.HALF_UP) + "超过权益50%上限"
                    + maxMargin.setScale(0, RoundingMode.HALF_UP) + "，请减小数量或降杠杆";
        }
        if (req.stopLossPrice() == null) {
            return "必须设置止损价（每笔交易先想好在哪认错）";
        }
        if (isLong ? req.stopLossPrice().compareTo(entryRef) >= 0
                : req.stopLossPrice().compareTo(entryRef) <= 0) {
            return "止损价方向错误：" + (isLong ? "LONG止损须低于入场价" : "SHORT止损须高于入场价")
                    + "（入场参考" + entryRef.stripTrailingZeros().toPlainString() + "）";
        }
        if (req.takeProfitPrice() != null
                && (isLong ? req.takeProfitPrice().compareTo(entryRef) <= 0
                : req.takeProfitPrice().compareTo(entryRef) >= 0)) {
            return "止盈价方向错误：" + (isLong ? "LONG止盈须高于入场价" : "SHORT止盈须低于入场价");
        }
        return null;
    }
}

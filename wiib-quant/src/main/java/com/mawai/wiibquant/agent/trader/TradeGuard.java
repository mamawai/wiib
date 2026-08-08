package com.mawai.wiibquant.agent.trader;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * 开仓硬校验：AI 会幻觉出荒谬参数，入口一票否决。拒绝原因用中文原样返回给模型——
 * 模型看得懂就能自行修正重试。
 * 杠杆/保证金/仓位数/双开来自主人的 {@link TraderRiskConfig}，越界一律拒不截断：
 * 这是模拟盘，仓位规格是主人说了算的硬参数，模型无权评价也无权自行缩小。
 */
public final class TradeGuard {

    /** 论点标签枚举：写入时结构化，未来挖掘"哪些打法反复赚钱"直接 SQL 分桶 */
    public static final Set<String> PLAY_TYPES = Set.of(
            "BREAKOUT", "PULLBACK", "REVERSAL", "TREND_FOLLOW", "RANGE", "NEWS", "FUNDING", "OTHER");

    /** 限价偏离现价上限：防模型幻觉出离谱价格，与用户配置无关，不开放 */
    public static final BigDecimal MAX_LIMIT_DEVIATION = new BigDecimal("0.05");

    /**
     * 账户占位快照：已成交持仓 + 未成交挂单统一成这个形状。
     * 挂单必须一起计数——只看持仓的话，模型挂三个不同币的限价单，成交后就绕过了单仓限制。
     *
     * @param filled true=已成交持仓，false=未成交挂单。这个区分不能省：
     *               sim 只与已成交持仓并仓，把挂单也当成"已有同向仓"会让加仓判定为真、
     *               从而跳过保证金区间校验——先挂一个不会成交的限价单，之后开仓就不受约束了
     */
    public record PosSnap(String symbol, String side, Integer leverage, boolean filled) {
    }

    /** 开仓请求（工具参数的结构化镜像）；with* 是测试构造变体用的，只留真被用到的那几个。 */
    public record OpenReq(String symbol, String side, String orderType, BigDecimal quantity, Integer leverage,
                          BigDecimal limitPrice, BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                          String playType, String signalsUsed, String invalidationCondition) {
        public OpenReq withOrderType(String v) { return new OpenReq(symbol, side, v, quantity, leverage, limitPrice, stopLossPrice, takeProfitPrice, playType, signalsUsed, invalidationCondition); }
        public OpenReq withQuantity(BigDecimal v) { return new OpenReq(symbol, side, orderType, v, leverage, limitPrice, stopLossPrice, takeProfitPrice, playType, signalsUsed, invalidationCondition); }
        public OpenReq withLeverage(Integer v) { return new OpenReq(symbol, side, orderType, quantity, v, limitPrice, stopLossPrice, takeProfitPrice, playType, signalsUsed, invalidationCondition); }
        public OpenReq withLimitPrice(BigDecimal v) { return new OpenReq(symbol, side, orderType, quantity, leverage, v, stopLossPrice, takeProfitPrice, playType, signalsUsed, invalidationCondition); }
        public OpenReq withStopLossPrice(BigDecimal v) { return new OpenReq(symbol, side, orderType, quantity, leverage, limitPrice, v, takeProfitPrice, playType, signalsUsed, invalidationCondition); }
        public OpenReq withInvalidationCondition(String v) { return new OpenReq(symbol, side, orderType, quantity, leverage, limitPrice, stopLossPrice, takeProfitPrice, playType, signalsUsed, v); }
    }

    private TradeGuard() {
    }

    /**
     * 多单取最高价/空单取最低价。作判定基准时含义随场景不同：对止损是"最紧那一档"、对止盈是
     * "最远那一档"——sim 整组替换语义下按最保守档做基线，替换后才不会比原来任何一档更松/更近。
     * 批准加仓给新增部分补挂保护单时也照抄这一档（本版 sl/tp 都是全仓单，正常只有一档）。
     */
    public static BigDecimal extremePrice(List<BigDecimal> prices, boolean isLong) {
        return prices.stream().filter(java.util.Objects::nonNull)
                .reduce((a, b) -> isLong ? a.max(b) : a.min(b)).orElse(null);
    }

    /**
     * 返回 null=放行；否则给模型看的中文拒绝原因。
     *
     * @param cfg      主人设的仓位规格（区间是允许集合，越界拒不截断）
     * @param existing 当前账户占位：已成交持仓 + 未成交挂单
     */
    public static String validateOpen(OpenReq req, BigDecimal equity, BigDecimal markPrice,
                                      Set<String> symbolWhitelist, TraderRiskConfig cfg,
                                      List<PosSnap> existing) {
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
        if (req.leverage() == null || req.leverage() < cfg.leverageMin() || req.leverage() > cfg.leverageMax()) {
            return "杠杆必须在" + cfg.leverageMin() + "~" + cfg.leverageMax() + "倍之间（主人设定，不可协商），你给了"
                    + req.leverage();
        }
        if (req.playType() == null || !PLAY_TYPES.contains(req.playType())) {
            return "playType必须是: " + PLAY_TYPES;
        }
        if (req.invalidationCondition() == null || req.invalidationCondition().isBlank()) {
            return "必须给invalidationCondition失效条件：一句话说明什么市场状况会证明这个论点错了（市场条件，不是盈亏数字）";
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
        List<PosSnap> snaps = existing == null ? List.of() : existing;
        // 只有已成交持仓才算加仓：sim 不会与挂单并仓，把挂单算进来等于送出一条绕过保证金区间的路
        boolean isAddOn = snaps.stream().filter(PosSnap::filled)
                .anyMatch(p -> p.symbol().equals(req.symbol()) && p.side().equals(req.side()));

        // 币种级杠杆一致性：sim 侧 validateSymbolConsistency 会拒不一致的加仓，护栏提前拦并说清楚，
        // 免得模型对着 sim 的错误码猜半天
        Integer symLev = snaps.stream()
                .filter(p -> p.symbol().equals(req.symbol()))
                .map(PosSnap::leverage).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        if (symLev != null && !symLev.equals(req.leverage())) {
            return req.symbol() + "已有" + symLev + "倍仓位/挂单，同币杠杆必须一致（交易所规则），本次也得用" + symLev + "倍";
        }
        // 单仓模式：加仓不占新坑（sim 同向自动并仓），其余一律拒
        if (!cfg.allowMultiPosition() && !isAddOn && !snaps.isEmpty()) {
            return "主人设定只允许同时持有一个仓位，当前已占用：" + describe(snaps)
                    + "。要换标的先平掉现有仓位";
        }
        // 双开：同币反向占的是另一个坑，只可能在多仓位模式下发生
        if (!cfg.allowHedge() && snaps.stream()
                .anyMatch(p -> p.symbol().equals(req.symbol()) && !p.side().equals(req.side()))) {
            return req.symbol() + "已有反向仓位，主人未开启多空双开——同一个币不能同时做多做空";
        }

        // 入场参考价：限价单按限价、市价单按现价
        BigDecimal entryRef = isLimit ? req.limitPrice() : markPrice;
        // 保证金区间只管开新仓：加仓多大由模型自己斟酌（主人的原话）
        if (!isAddOn) {
            BigDecimal margin = req.quantity().multiply(entryRef)
                    .divide(BigDecimal.valueOf(req.leverage()), 8, RoundingMode.HALF_UP);
            BigDecimal pct = margin.multiply(new BigDecimal("100"))
                    .divide(equity, 4, RoundingMode.HALF_UP);
            if (pct.compareTo(cfg.marginPctMin()) < 0 || pct.compareTo(cfg.marginPctMax()) > 0) {
                // 占比原样展示不四舍五入：9.995% 若显示成"10.00%超出10~15%"是自相矛盾，模型会懵
                return "开仓保证金" + margin.setScale(0, RoundingMode.HALF_UP) + "占权益"
                        + pct.stripTrailingZeros().toPlainString() + "%，超出主人设定的"
                        + cfg.marginPctMin().stripTrailingZeros().toPlainString() + "~"
                        + cfg.marginPctMax().stripTrailingZeros().toPlainString() + "%区间。"
                        + req.leverage() + "倍杠杆下数量应在 "
                        + qtyFor(cfg.marginPctMin(), equity, req.leverage(), entryRef, RoundingMode.CEILING) + " ~ "
                        + qtyFor(cfg.marginPctMax(), equity, req.leverage(), entryRef, RoundingMode.FLOOR) + " 之间";
            }
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

    /**
     * 拒因里把该配多少数量直接算给模型，省一轮试错。
     * 下界向上取整、上界向下取整：模型会照抄提示数字重试（一晚 8 次贴边拒绝的实测教训——
     * HALF_UP 算出的下界被模型截位后又低于下界，陷入拒绝循环）。
     */
    private static String qtyFor(BigDecimal marginPct, BigDecimal equity, int leverage,
                                 BigDecimal entryRef, RoundingMode mode) {
        return equity.multiply(marginPct).divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(leverage))
                .divide(entryRef, 6, mode)
                .stripTrailingZeros().toPlainString();
    }

    private static String describe(List<PosSnap> snaps) {
        return snaps.stream().map(p -> p.symbol() + " " + p.side())
                .collect(java.util.stream.Collectors.joining("、"));
    }
}

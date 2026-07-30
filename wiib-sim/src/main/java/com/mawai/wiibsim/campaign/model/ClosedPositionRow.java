package com.mawai.wiibsim.campaign.model;

import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 活动期内平掉的一个合约仓位，已按订单侧聚合出真实口径。
 * <p>
 * 【为什么不用仓位表的 closed_pnl / margin】那两列对部分平仓过的仓位是残值
 * （见 FuturesPositionMapper:136-138 的注释），按残值算 ROI 可以被
 * "亏着分批平掉、留一小段等反弹"刷出 300%+。这里的两个字段来自订单表聚合，
 * 与用户在「仓位历史」页看到的 ROI 同一口径。
 */
@Data
public class ClosedPositionRow {

    private Long userId;

    private Long positionId;

    private String symbol;

    /** 累计投入保证金 = 所有非 CLOSE_* 订单的 margin_amount 之和 */
    private BigDecimal investedMargin;

    /** 已实现净盈亏 = SUM(realized_pnl - commission) - funding_fee_total */
    private BigDecimal netPnl;

    /** 平仓时刻，「前 N 笔」的排序依据 */
    private LocalDateTime closedAt;

    /** ISOLATED / CROSS */
    private String marginMode;

    /** ROI = netPnl / investedMargin；investedMargin 为 0 时返回 null（该仓位不参与任何仓位任务） */
    public BigDecimal roi() {
        if (investedMargin == null || investedMargin.signum() <= 0) return null;
        return netPnl.divide(investedMargin, 6, RoundingMode.HALF_UP);
    }
}

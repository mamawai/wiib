package com.mawai.wiibcommon.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 持仓变化事件
 * 触发场景：买入、卖出、冻结、解冻
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PositionChangeEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long userId;

    /** 股票ID */
    private Long stockId;

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 可用数量 */
    private Integer quantity;

    /** 冻结数量 */
    private Integer frozenQuantity;

    /** 持仓成本 */
    private BigDecimal avgCost;

    /** 当前价格 */
    private BigDecimal currentPrice;

    /** 市值 */
    private BigDecimal marketValue;

    /** 盈亏 */
    private BigDecimal profit;

    /** 盈亏率 */
    private BigDecimal profitPct;

    /** 变化类型：BUY、SELL、FREEZE、UNFREEZE */
    private String changeType;

    /** 变化数量（正数增加，负数减少） */
    private Integer changeQuantity;

    /** 事件时间戳 */
    private Long timestamp;

    public PositionChangeEvent(Long userId, Long stockId, String stockCode, String stockName,
                               Integer quantity, Integer frozenQuantity, BigDecimal avgCost,
                               BigDecimal currentPrice, String changeType, Integer changeQuantity) {
        this.userId = userId;
        this.stockId = stockId;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.quantity = quantity;
        this.frozenQuantity = frozenQuantity;
        this.avgCost = avgCost;
        this.currentPrice = currentPrice;
        this.changeType = changeType;
        this.changeQuantity = changeQuantity;
        this.timestamp = System.currentTimeMillis();

        // 计算市值和盈亏
        if (currentPrice != null && quantity != null) {
            this.marketValue = currentPrice.multiply(BigDecimal.valueOf(quantity));
            if (avgCost != null && avgCost.compareTo(BigDecimal.ZERO) > 0) {
                this.profit = currentPrice.subtract(avgCost).multiply(BigDecimal.valueOf(quantity));
                this.profitPct = profit.divide(avgCost.multiply(BigDecimal.valueOf(quantity)), 4, BigDecimal.ROUND_HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
        }
    }
}

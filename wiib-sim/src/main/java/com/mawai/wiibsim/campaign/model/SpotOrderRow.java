package com.mawai.wiibsim.campaign.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一笔现货成交（加密现货与 bStock 同走 crypto_order，天然都在内）。
 * <p>
 * 现货任务要的是"已实现收益率摸到过的最高台阶"（只进不退），期末聚合值会漏掉
 * 中途冲高后回落的那段，所以查的是逐笔流水、Java 侧按时间重放（TradeScorer.countSpotUnits）。
 */
@Data
public class SpotOrderRow {

    private Long userId;

    private String symbol;

    /** BUY / SELL */
    private String orderSide;

    /** 成交额（不含手续费） */
    private BigDecimal filledAmount;

    /** 手续费，SQL 侧已 COALESCE 成 0 */
    private BigDecimal commission;

    /** 成交时刻（updated_at，限价单以成交那一刻为准） */
    private LocalDateTime filledAt;
}

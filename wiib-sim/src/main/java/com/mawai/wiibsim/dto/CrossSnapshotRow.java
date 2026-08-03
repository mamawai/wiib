package com.mawai.wiibsim.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 全仓账户快照单行（"user" LEFT JOIN 全仓持仓 + 挂单占用标量子查询，见
 * FuturesPositionMapper#selectCrossSnapshot）。positionId 为 null = 有账号无全仓持仓的空行。
 * <p>
 * 不带 stop_losses/take_profits：注解查询走自动映射、挂不上 JSONB typeHandler，
 * 且快照的所有消费方只读 价格/数量/保证金 维度（强平判定、预估强平价、额度计算），
 * 要 SL/TP 的场景走 positionMapper 常规查询。
 */
@Data
public class CrossSnapshotRow {

    private BigDecimal balance;
    private BigDecimal pendingReserved;

    private Long positionId;
    private String symbol;
    private String side;
    private Integer leverage;
    private BigDecimal quantity;
    private BigDecimal entryPrice;
    private BigDecimal margin;
    private BigDecimal fundingFeeTotal;
}

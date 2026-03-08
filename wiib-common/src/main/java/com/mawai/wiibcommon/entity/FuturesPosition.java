package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("futures_position")
public class FuturesPosition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String symbol; // 交易对 如BTCUSDT

    private String side; // LONG做多 SHORT做空

    private Integer leverage; // 杠杆倍数

    private BigDecimal quantity; // 持仓数量(币)

    private BigDecimal entryPrice; // 开仓均价

    private BigDecimal margin; // 保证金

    private BigDecimal fundingFeeTotal; // 累计资金费

    private BigDecimal stopLossPercent; // 止损百分比(保留保证金%) null=未设置

    private BigDecimal stopLossPrice; // 止损价(后端计算) null=未设置

    private String status; // OPEN持仓 CLOSED已平仓 LIQUIDATED已强平

    private BigDecimal closedPrice; // 平仓价

    private BigDecimal closedPnl; // 已实现盈亏

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("quant_horizon_forecast")
public class QuantHorizonForecast {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cycleId;

    private String horizon;

    private String direction;

    private BigDecimal confidence;

    private BigDecimal weightedScore;

    private BigDecimal disagreement;

    private BigDecimal entryLow;

    private BigDecimal entryHigh;

    private BigDecimal invalidationPrice;

    private BigDecimal tp1;

    private BigDecimal tp2;

    private Integer maxLeverage;

    private BigDecimal maxPositionPct;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("quant_signal_decision")
public class QuantSignalDecision {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cycleId;

    private String horizon;

    private String direction;

    private BigDecimal confidence;

    private Integer maxLeverage;

    private BigDecimal maxPositionPct;

    private String riskStatus;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

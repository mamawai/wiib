package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quant_forecast_cycle")
public class QuantForecastCycle {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cycleId;

    private String symbol;

    private LocalDateTime forecastTime;

    private String overallDecision;

    private String riskStatus;

    private String snapshotJson;

    private String reportJson;

    private String debateJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

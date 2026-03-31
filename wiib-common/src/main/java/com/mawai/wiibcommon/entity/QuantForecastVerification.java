package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("quant_forecast_verification")
public class QuantForecastVerification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cycleId;

    private String symbol;

    private String horizon;

    private String predictedDirection;

    private BigDecimal predictedConfidence;

    private BigDecimal actualPriceAtForecast;

    private BigDecimal actualPriceAfter;

    private Integer actualChangeBps;

    private Boolean predictionCorrect;

    private LocalDateTime verifiedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("prediction_round")
public class PredictionRound {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long windowStart;

    private BigDecimal startPrice;

    private BigDecimal endPrice;

    /** UP/DOWN/DRAW */
    private String outcome;

    /** OPEN/LOCKED/SETTLED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

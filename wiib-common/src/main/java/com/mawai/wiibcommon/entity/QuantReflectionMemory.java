package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("quant_reflection_memory")
public class QuantReflectionMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String symbol;

    private String cycleId;

    private String regime;

    private String overallDecision;

    private String predictedDirection;

    private Integer actualPriceChangeBps;

    private Boolean predictionCorrect;

    private String reflectionText;

    private String lessonTags;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

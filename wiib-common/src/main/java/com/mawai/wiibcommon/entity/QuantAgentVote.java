package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("quant_agent_vote")
public class QuantAgentVote {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cycleId;

    private String agent;

    private String horizon;

    private String direction;

    private BigDecimal score;

    private BigDecimal confidence;

    private Integer expectedMoveBps;

    private Integer volatilityBps;

    private String reasonCodes;

    private String riskFlags;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

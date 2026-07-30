package com.mawai.wiibsim.campaign.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("campaign")
public class Campaign {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SETTLING = "SETTLING";
    public static final String STATUS_DONE = "DONE";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 活动码，out_trade_no 的组成部分，发放开始后不可变更 */
    private String code;

    private String name;

    /** 活动开始时刻（含） */
    private LocalDateTime startAt;

    /** 活动结束时刻（不含） */
    private LocalDateTime endAt;

    /** 奖池 LDC 总额 */
    private BigDecimal prizePool;

    /** RUNNING / SETTLING / DONE */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

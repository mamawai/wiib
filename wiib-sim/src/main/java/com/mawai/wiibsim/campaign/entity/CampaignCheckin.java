package com.mawai.wiibsim.campaign.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("campaign_checkin")
public class CampaignCheckin {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long campaignId;

    private Long userId;

    private LocalDate checkinDate;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

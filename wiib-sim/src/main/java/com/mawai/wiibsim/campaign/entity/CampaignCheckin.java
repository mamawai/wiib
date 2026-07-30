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

    /**
     * 服务器本地日（容器 TZ Asia/Singapore），与 CampaignVote.voteDate 的 UTC 口径不同，别混用。
     * 连续签到天数就是数这一列的连续段，跨时区取日会在日界线上差一天。
     */
    private LocalDate checkinDate;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

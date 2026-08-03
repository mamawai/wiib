package com.mawai.wiibsim.campaign.model;

import lombok.Data;

/** campaign_carryover 的一行：某用户某积分项在历次重置前累计的达成次数 */
@Data
public class CarryoverRow {

    private Long userId;

    /** 积分项 code（TradeScorer.CODE_* 或 BUCKET_*） */
    private String code;

    private Integer cnt;
}

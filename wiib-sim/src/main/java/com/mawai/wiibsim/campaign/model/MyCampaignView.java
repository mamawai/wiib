package com.mawai.wiibsim.campaign.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 活动页一次要的全部数据。
 *
 * @param eligibleTotal 有资格参与分配的人的总分，也就是分配公式的分母
 * @param estimatedLdc  预估到手，随参与人数变动，以结算为准
 * @param rank          我在榜上的名次，从 1 起；<b>0 = 还没上榜</b>（一分没有的人不进榜单）
 */
public record MyCampaignView(
        Long campaignId,
        String campaignName,
        String startAt,
        String endAt,
        BigDecimal prizePool,
        CampaignScore me,
        BigDecimal eligibleTotal,
        BigDecimal estimatedLdc,
        int rank,
        int participants,
        boolean checkedToday,
        List<VoteBoard> voteBoard
) {
}

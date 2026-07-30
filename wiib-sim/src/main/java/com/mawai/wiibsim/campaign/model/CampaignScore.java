package com.mawai.wiibsim.campaign.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一个人的完整活动积分。
 *
 * @param claimable  能否领 LDC。参与名单已按"纯数字 linux_do_id"筛过（见
 *                   {@link com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper#listEligibleUsers()}），
 *                   故<b>榜上的行在现行规则下恒为 true</b>；保留只作分配侧的防御闸，前端不必据此分支。
 *                   例外只有一处：{@code CampaignScoreService.myView} 给未上榜用户造的兜底行，
 *                   那一行的 claimable 与 username 都是占位而非事实（详见该方法内注释）
 * @param penalty    强平扣分，负数
 * @param finalScore max(0, trade + daily + vote + penalty)。个人总分下限为 0，
 *                   强平扣分不会导致负数，也就不会影响别人的分配比例
 */
public record CampaignScore(
        Long userId,
        String username,
        boolean claimable,
        int tradeScore,
        int dailyScore,
        BigDecimal voteScore,
        int penalty,
        BigDecimal finalScore,
        List<ScoreItem> items
) {
}

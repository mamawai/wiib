package com.mawai.wiibsim.campaign.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 结算的依据：一份<b>刚算出来</b>的榜，和从它筛出来的分配权重，绑成一个对象。
 * <p>
 * 绑在一起由 {@code CampaignScoreService.settlementBasis()} 一次产出，
 * 让"榜吃了缓存"和"榜与权重取自两次计算"这两种静默错法在类型上不可表达。
 *
 * @param board   全站积分表，按最终分降序。落 campaign_reward 的明细字段从这里取
 * @param weights 参与分配的权重（能领取且分数为正的人），{@code ScoreRules.largestRemainder} 的入参
 */
public record SettlementBasis(List<CampaignScore> board, Map<Long, BigDecimal> weights) {
}

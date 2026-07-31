package com.mawai.wiibsim.campaign.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 结算的依据：一份<b>刚算出来</b>的榜，和从它筛出来的分配权重，绑成一个对象。
 * <p>
 * 【为什么要绑在一起】结算需要这两样东西，分开取就有两种错法，而且都是静默的：
 * <ul>
 *   <li>榜取成了 {@code scoreBoard()}（60 秒缓存）—— 缓存可能早于最后一批投票分落库，
 *       按它分池子就是按永久少算的权重把 LDC 发出去，CAS 之后纠不回来。</li>
 *   <li>榜和权重取自两次不同的计算 —— 两份数据之间隔着一次全站扫表，中间落库的分
 *       会让"权重里有他、榜里的分不是这个数"，写进 campaign_reward 的明细与实发金额对不上账。</li>
 * </ul>
 * 由 {@code CampaignScoreService.settlementBasis()} 一次产出，调用方拿不到拆开的机会。
 *
 * @param board   全站积分表，按最终分降序。落 campaign_reward 的明细字段从这里取
 * @param weights 参与分配的权重（能领取且分数为正的人），{@code ScoreRules.largestRemainder} 的入参
 */
public record SettlementBasis(List<CampaignScore> board, Map<Long, BigDecimal> weights) {
}

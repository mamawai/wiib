package com.mawai.wiibsim.campaign.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 一天的投票分配结果。
 *
 * @param awarded   userId → 当日实得分（已封顶）
 * @param carryOver 没发出去的部分，留到次日奖池。awarded 之和 + carryOver 恒等于当日可分池
 */
public record VoteTally(Map<Long, BigDecimal> awarded, BigDecimal carryOver) {
}

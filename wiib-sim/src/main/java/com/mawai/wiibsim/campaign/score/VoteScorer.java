package com.mawai.wiibsim.campaign.score;

import com.mawai.wiibsim.campaign.model.VoteTally;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** 投票奖池的均分 / 封顶 / 顺延。无依赖纯函数 */
public final class VoteScorer {

    private VoteScorer() {
    }

    /**
     * 按正确票数均分当日奖池，单人封顶，没发出去的顺延。
     * 每票价值 = 池 ÷ 当日总正确票数（两标的共享一个池，冷门猜对分多）；
     * 向下取整到 4 位，不变量是"发放之和 + 顺延 = 池子"。
     *
     * @param correctCountByUser userId → 当日猜对的票数（1 或 2；没猜对的不要传进来）
     * @param pool               当日可分池 = 100 + 累计顺延
     */
    public static VoteTally allocate(Map<Long, Integer> correctCountByUser, BigDecimal pool) {
        int totalCorrect = correctCountByUser.values().stream().mapToInt(Integer::intValue).sum();
        if (totalCorrect <= 0) return new VoteTally(Map.of(), pool);

        BigDecimal perVote = pool.divide(BigDecimal.valueOf(totalCorrect), 4, RoundingMode.DOWN);

        Map<Long, BigDecimal> awarded = new LinkedHashMap<>();
        BigDecimal spent = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> e : correctCountByUser.entrySet()) {
            BigDecimal raw = perVote.multiply(BigDecimal.valueOf(e.getValue()));
            BigDecimal capped = raw.min(ScoreRules.VOTE_DAILY_CAP).setScale(2, RoundingMode.DOWN);
            awarded.put(e.getKey(), capped);
            spent = spent.add(capped);
        }
        return new VoteTally(awarded, pool.subtract(spent));
    }
}

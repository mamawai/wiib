package com.mawai.wiibsim.campaign.score;

import com.mawai.wiibsim.campaign.model.VoteTally;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 投票奖池分配。钉住"每票价值 = 池 ÷ 当日总正确票数"（共享池反向赔率）
 * 与单人封顶（防活动初期人少时早期玩家拿超额分）。
 */
class VoteScorerTest {

    private static final BigDecimal POOL = new BigDecimal("100");

    @Test
    void 没人猜对时整池顺延() {
        VoteTally t = VoteScorer.allocate(Map.of(), POOL);

        assertThat(t.awarded()).isEmpty();
        assertThat(t.carryOver()).isEqualByComparingTo("100");
    }

    /** 20 票均分 100 恰好每票 5 分，不触碰封顶，池子发光 */
    @Test
    void 二十票均分刚好发完() {
        Map<Long, Integer> correct = new LinkedHashMap<>();
        for (long i = 1; i <= 20; i++) correct.put(i, 1);

        VoteTally t = VoteScorer.allocate(correct, POOL);

        assertThat(t.awarded().values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("5"));
        assertThat(t.carryOver()).isEqualByComparingTo("0");
    }

    /** 只有一个人猜对：每票值 100，但封顶 6，剩下 94 顺延 */
    @Test
    void 单人猜对被封顶剩余顺延() {
        VoteTally t = VoteScorer.allocate(Map.of(7L, 1), POOL);

        assertThat(t.awarded()).containsExactly(Map.entry(7L, new BigDecimal("6.00")));
        assertThat(t.carryOver()).isEqualByComparingTo("94.00");
    }

    /** 两个都猜对拿双份：每票 5 分 → 10 分，但仍被单日 6 分封顶 */
    @Test
    void 两标的都猜对拿双份但仍受封顶() {
        Map<Long, Integer> correct = new LinkedHashMap<>();
        correct.put(1L, 2);
        for (long i = 2; i <= 19; i++) correct.put(i, 1);   // 总正确票数 20，每票 5

        VoteTally t = VoteScorer.allocate(correct, POOL);

        assertThat(t.awarded().get(1L)).isEqualByComparingTo("6.00");   // 10 被砍到 6
        assertThat(t.awarded().get(2L)).isEqualByComparingTo("5.00");
    }

    /** 双份不封顶的情形：每票 2 分 → 4 分照发 */
    @Test
    void 双份未超封顶时照发() {
        Map<Long, Integer> correct = new LinkedHashMap<>();
        correct.put(1L, 2);
        for (long i = 2; i <= 49; i++) correct.put(i, 1);   // 总 50 票，每票 2

        assertThat(VoteScorer.allocate(correct, POOL).awarded().get(1L)).isEqualByComparingTo("4.00");
    }

    /** 顺延进来的池子照常参与均分 */
    @Test
    void 顺延池参与次日均分() {
        VoteTally t = VoteScorer.allocate(Map.of(1L, 1, 2L, 1), new BigDecimal("194"));

        // 每票 97，双双封顶 6，剩 182 继续顺延
        assertThat(t.awarded().get(1L)).isEqualByComparingTo("6.00");
        assertThat(t.carryOver()).isEqualByComparingTo("182.00");
    }

    /** 发出去的 + 顺延的必须恰好等于池子，一分不能凭空多也不能凭空少 */
    @Test
    void 发放与顺延之和恒等于池子() {
        Map<Long, Integer> correct = new LinkedHashMap<>();
        for (long i = 1; i <= 7; i++) correct.put(i, i % 2 == 0 ? 2 : 1);

        VoteTally t = VoteScorer.allocate(correct, new BigDecimal("137.50"));

        BigDecimal sum = t.awarded().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum.add(t.carryOver())).isEqualByComparingTo("137.50");
    }

    @Test
    void 池子为零时不发也不欠() {
        VoteTally t = VoteScorer.allocate(Map.of(1L, 1), BigDecimal.ZERO);

        assertThat(t.awarded().get(1L)).isEqualByComparingTo("0.00");
        assertThat(t.carryOver()).isEqualByComparingTo("0");
    }
}

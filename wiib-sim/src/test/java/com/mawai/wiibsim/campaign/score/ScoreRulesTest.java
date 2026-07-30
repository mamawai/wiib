package com.mawai.wiibsim.campaign.score;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计分规则纯函数。
 * <p>
 * 【为什么单独测】阶梯与分配是整套积分里唯一"错了全员都错"的部分：SQL 查漏一笔只影响一个人，
 * 阶梯边界差一位、余额法多发一分，是所有人的账都对不上。而它们又恰好完全无依赖，
 * 测起来只花几十行，没有不测的理由。
 */
class ScoreRulesTest {

    // ---- 阶梯边界：每一档都测"最后一个高分位"和"第一个降档位" ----

    @Test
    void roi50档前五笔各五分之后各一分() {
        assertThat(List.of(1, 2, 3, 4, 5).stream().map(ScoreRules::roi50Tier).toList())
                .containsExactly(5, 5, 5, 5, 5);
        assertThat(ScoreRules.roi50Tier(6)).isEqualTo(1);
        assertThat(ScoreRules.roi50Tier(100)).isEqualTo(1);
    }

    @Test
    void roi100档首笔十五第二三笔各五之后各一() {
        assertThat(ScoreRules.roi100Tier(1)).isEqualTo(15);
        assertThat(ScoreRules.roi100Tier(2)).isEqualTo(5);
        assertThat(ScoreRules.roi100Tier(3)).isEqualTo(5);
        assertThat(ScoreRules.roi100Tier(4)).isEqualTo(1);
    }

    /** 封神是一次性的：第二笔起 0 分，不是 1 分 */
    @Test
    void 封神只认第一笔() {
        assertThat(ScoreRules.godlyTier(1)).isEqualTo(20);
        assertThat(ScoreRules.godlyTier(2)).isZero();
        assertThat(ScoreRules.godlyTier(9)).isZero();
    }

    @Test
    void 现货前三个标的各五分之后各一分() {
        assertThat(ScoreRules.spotTier(3)).isEqualTo(5);
        assertThat(ScoreRules.spotTier(4)).isEqualTo(1);
    }

    // ---- 连续签到 ----

    /** 累进：连满 14 天拿满 5+15+40，不是只拿 40 */
    @Test
    void 连续签到奖励累进且按档累加() {
        assertThat(ScoreRules.streakBonus(2)).isZero();
        assertThat(ScoreRules.streakBonus(3)).isEqualTo(5);
        assertThat(ScoreRules.streakBonus(6)).isEqualTo(5);
        assertThat(ScoreRules.streakBonus(7)).isEqualTo(20);
        assertThat(ScoreRules.streakBonus(13)).isEqualTo(20);
        assertThat(ScoreRules.streakBonus(14)).isEqualTo(60);
        assertThat(ScoreRules.streakBonus(30)).isEqualTo(60);
    }

    @Test
    void 最长连续段取最长的那一段() {
        assertThat(ScoreRules.longestStreak(Set.of())).isZero();
        assertThat(ScoreRules.longestStreak(Set.of(LocalDate.of(2026, 8, 3)))).isEqualTo(1);
        // 3号-5号(3天) 断7号 8号-11号(4天) → 4
        assertThat(ScoreRules.longestStreak(List.of(
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 9),
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11)))).isEqualTo(4);
    }

    /**
     * 断签重计不是断签清零：两段各 3 天只能拿一次 +5，不能拿 +10。
     * 否则"签3天歇1天"比连续签划算，把时间这个唯一压缩不了的资源变成了可绕过的。
     */
    @Test
    void 两个三天段只按最长段发一次奖() {
        int bonus = ScoreRules.streakBonus(ScoreRules.longestStreak(List.of(
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 9))));
        assertThat(bonus).isEqualTo(5);
    }

    @Test
    void 乱序与重复日期不影响最长连续段() {
        assertThat(ScoreRules.longestStreak(List.of(
                LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 3),
                LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 4)))).isEqualTo(3);
    }

    // ---- 最大余额法 ----

    /** 核心不变量：不管权重多难整除，发出去的必须恰好等于奖池，一分不多一分不少 */
    @Test
    void 分配总额恰好等于奖池() {
        Map<Long, BigDecimal> weights = new LinkedHashMap<>();
        weights.put(1L, new BigDecimal("37"));
        weights.put(2L, new BigDecimal("21"));
        weights.put(3L, new BigDecimal("13"));
        weights.put(4L, new BigDecimal("7"));
        weights.put(5L, new BigDecimal("1"));

        Map<Long, BigDecimal> got = ScoreRules.largestRemainder(new BigDecimal("500"), weights);

        assertThat(got.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("500.00");
        assertThat(got.values()).allSatisfy(v -> assertThat(v.scale()).isEqualTo(2));
    }

    /** 三个人平分 100：33.33 除不尽，余 1 分必须补给某一个，总额仍是 100.00 */
    @Test
    void 除不尽时余数补足不留零头() {
        Map<Long, BigDecimal> weights = new LinkedHashMap<>();
        weights.put(1L, BigDecimal.ONE);
        weights.put(2L, BigDecimal.ONE);
        weights.put(3L, BigDecimal.ONE);

        Map<Long, BigDecimal> got = ScoreRules.largestRemainder(new BigDecimal("100"), weights);

        assertThat(got.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100.00");
        assertThat(got.values()).containsExactlyInAnyOrder(
                new BigDecimal("33.34"), new BigDecimal("33.33"), new BigDecimal("33.33"));
    }

    /** 结果必须可重算：同样的输入跑两次必须一模一样，否则争议时无法复现 */
    @Test
    void 同输入两次分配结果完全一致() {
        Map<Long, BigDecimal> weights = new LinkedHashMap<>();
        for (long i = 1; i <= 40; i++) weights.put(i, BigDecimal.valueOf(i % 7 + 1));

        assertThat(ScoreRules.largestRemainder(new BigDecimal("500"), weights))
                .isEqualTo(ScoreRules.largestRemainder(new BigDecimal("500"), weights));
    }

    @Test
    void 无人有分时不分配() {
        assertThat(ScoreRules.largestRemainder(new BigDecimal("500"), Map.of())).isEmpty();
        assertThat(ScoreRules.largestRemainder(new BigDecimal("500"), Map.of(1L, BigDecimal.ZERO))).isEmpty();
    }

    /** 独一人拿全部奖池 */
    @Test
    void 只有一人时拿满奖池() {
        assertThat(ScoreRules.largestRemainder(new BigDecimal("500"), Map.of(9L, new BigDecimal("3"))))
                .containsExactly(Map.entry(9L, new BigDecimal("500.00")));
    }
}

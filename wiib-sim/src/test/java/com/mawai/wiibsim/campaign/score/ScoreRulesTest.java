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
 * 计分规则纯函数。阶梯与分配是"错了全员都错"的部分，且完全无依赖，单独钉。
 */
class ScoreRulesTest {

    // ---- ROI 占位制：每仓只占一档，高档满了往下顺延 ----

    /** 首个 100% 只占 100 档；后来的 100% 依次落 60 档、40 档，最后进无限溢出 */
    @Test
    void 占位制高档满了往下顺延() {
        // 1 笔 ≥100%：只占 100 档，60/40 档一个名额不占
        assertThat(ScoreRules.roiLadder(1, 1, 1, 1))
                .isEqualTo(new ScoreRules.RoiLadder(1, 0, 0, 0, 0));
        // 5 笔 ≥100%：1 进 100 档、3 进 60 档、1 进 40 档
        assertThat(ScoreRules.roiLadder(5, 5, 5, 5))
                .isEqualTo(new ScoreRules.RoiLadder(1, 3, 1, 0, 0));
        // 10 笔 ≥100%：40 档也满，最后 1 笔进无限溢出
        assertThat(ScoreRules.roiLadder(10, 10, 10, 10))
                .isEqualTo(new ScoreRules.RoiLadder(1, 3, 5, 1, 0));
    }

    /** 各区间各归各档：3 笔 [20,40) 进 20 档，7 笔 [40,60) 里 5 笔占 40 档、2 笔溢出 */
    @Test
    void 占位制各区间落对应档() {
        assertThat(ScoreRules.roiLadder(10, 7, 0, 0))
                .isEqualTo(new ScoreRules.RoiLadder(0, 0, 5, 2, 3));
    }

    /** 20~40% 区间的仓不与 ≥40% 抢名额，也吃不到无限溢出：30 笔全记 band20，计分时只认 20 笔 */
    @Test
    void 低区间仓位只进自己那档() {
        assertThat(ScoreRules.roiLadder(30, 0, 0, 0))
                .isEqualTo(new ScoreRules.RoiLadder(0, 0, 0, 0, 30));
    }

    /** 混合场景：2 笔 100% + 2 笔 [60,100) + 4 笔 [40,60) + 1 笔 [20,40) */
    @Test
    void 占位制混合区间分派() {
        // c20=9, c40=8, c60=4, c100=2 → 100 档 1；60 池 1+2=3 全进；40 池 0+4=4 全进；band20=1
        assertThat(ScoreRules.roiLadder(9, 8, 4, 2))
                .isEqualTo(new ScoreRules.RoiLadder(1, 3, 4, 0, 1));
    }

    /** 封神是一次性的：第二笔起 0 分，不是 1 分 */
    @Test
    void 封神只认第一笔() {
        assertThat(ScoreRules.godlyTier(1)).isEqualTo(25);
        assertThat(ScoreRules.godlyTier(2)).isZero();
        assertThat(ScoreRules.godlyTier(9)).isZero();
    }

    /** 现货单位阶梯：前 3 个各 5，第 4 起各 1、限 20 次 —— 第 24 个单位起 0 分 */
    @Test
    void 现货前三个单位高分之后各一分限二十次() {
        assertThat(ScoreRules.spotTier(3)).isEqualTo(5);
        assertThat(ScoreRules.spotTier(4)).isEqualTo(1);
        assertThat(ScoreRules.spotTier(23)).isEqualTo(1);
        assertThat(ScoreRules.spotTier(24)).isZero();
    }

    /** 预测封顶不是降到 1 分而是 0 分：第 11 次起一分没有 */
    @Test
    void 预测前三次各三第四到十次各一之后零分() {
        assertThat(ScoreRules.predictionTier(3)).isEqualTo(3);
        assertThat(ScoreRules.predictionTier(4)).isEqualTo(1);
        assertThat(ScoreRules.predictionTier(10)).isEqualTo(1);
        assertThat(ScoreRules.predictionTier(11)).isZero();
        assertThat(ScoreRules.predictionTier(100)).isZero();
    }

    // ---- 连续签到 ----

    /** 累进：连满 14 天拿满 3+5+10，不是只拿 10 */
    @Test
    void 连续签到奖励累进且按档累加() {
        assertThat(ScoreRules.streakBonus(2)).isZero();
        assertThat(ScoreRules.streakBonus(3)).isEqualTo(3);
        assertThat(ScoreRules.streakBonus(6)).isEqualTo(3);
        assertThat(ScoreRules.streakBonus(7)).isEqualTo(8);
        assertThat(ScoreRules.streakBonus(13)).isEqualTo(8);
        assertThat(ScoreRules.streakBonus(14)).isEqualTo(18);
        assertThat(ScoreRules.streakBonus(30)).isEqualTo(18);
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
     * 断签重计不是断签清零：两段各 3 天只能拿一次 +3，不能拿 +6。
     * 否则"签3天歇1天"比连续签划算，把时间这个唯一压缩不了的资源变成了可绕过的。
     */
    @Test
    void 两个三天段只按最长段发一次奖() {
        int bonus = ScoreRules.streakBonus(ScoreRules.longestStreak(List.of(
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 9))));
        assertThat(bonus).isEqualTo(3);
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

    /**
     * 三个人平分 100：33.33 除不尽，余 1 分必须补给某一个，总额仍是 100.00。
     * <p>
     * 多出的 0.01 归 userId 最小的：喂入顺序刻意排成 3,1,2，删掉 tie-break 这条当场红。
     * 用 containsExactly 不用 InAnyOrder——分配结果必须可复现，不能随喂入顺序漂。
     */
    @Test
    void 除不尽时余数补给userId最小的那个() {
        Map<Long, BigDecimal> weights = new LinkedHashMap<>();
        weights.put(3L, BigDecimal.ONE);
        weights.put(1L, BigDecimal.ONE);
        weights.put(2L, BigDecimal.ONE);

        Map<Long, BigDecimal> got = ScoreRules.largestRemainder(new BigDecimal("100"), weights);

        assertThat(got.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("100.00");
        assertThat(got).containsExactly(
                Map.entry(1L, new BigDecimal("33.34")),
                Map.entry(2L, new BigDecimal("33.33")),
                Map.entry(3L, new BigDecimal("33.33")));
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

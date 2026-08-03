package com.mawai.wiibsim.campaign.score;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * 活动计分的全部阈值、阶梯与分配算法。无依赖纯函数，改规则只改这一个文件。
 * <p>
 * 【阶梯为什么必要】ROI≥100% 那档在 100x 杠杆下价格动 1% 就达标，不递减会成为主刷分渠道；
 * ROI≥50% 同理。达标制的意义在于赚 5% 和赚 500% 拿一样的分，梭哈换不来超额收益。
 */
public final class ScoreRules {

    private ScoreRules() {
    }

    // ==================== 阈值 ====================

    /** 仓位任务的保证金门槛，判的是累计投入（订单侧 invested_margin），不是仓位表的残值 margin */
    public static final BigDecimal MIN_MARGIN = new BigDecimal("500");
    public static final BigDecimal ROI_25 = new BigDecimal("0.25");
    public static final BigDecimal ROI_50 = new BigDecimal("0.50");
    public static final BigDecimal ROI_100 = new BigDecimal("1.00");
    public static final BigDecimal ROI_300 = new BigDecimal("3.00");

    /**
     * 单仓位净盈亏任务的门槛（净利润与净亏损共用，比的是绝对值超过 1000）。
     * 刻意<b>不设保证金门槛</b>：这对任务是冲着"多空双开刷收益率"来的 ——
     * 双开的两腿盈亏近似对称，赚的那腿 +1、亏的那腿 −2，双开越多越亏。
     */
    public static final BigDecimal PNL_MIN = new BigDecimal("1000");

    /** 现货：活动期内该标的累计买入门槛 */
    public static final BigDecimal SPOT_MIN_BUY = new BigDecimal("1000");
    /** 现货：该标的整体收益率门槛（全历史净现金流口径） */
    public static final BigDecimal SPOT_MIN_RETURN = new BigDecimal("0.10");

    /** 预测市场：单次额度门槛（结算时刻的 cost） */
    public static final BigDecimal PREDICTION_MIN_COST = new BigDecimal("100");

    // ==================== 分值 ====================

    public static final int TRIPLE_MARKET = 15;
    public static final int STOP_LOSS_HERO = 3;
    /** 单仓位净亏损 > 1000：每仓 −2，不限次数 */
    public static final int PNL_LOSS = -2;
    /** 触发强平：逐仓强平（按被强平的仓位数）与全仓爆仓（按事件数）统一每次 −5 */
    public static final int PENALTY_LIQ_TRIGGER = -5;
    /**
     * 付费重置：活动期每自然周首次重置免费，之后每次扣 30（破产自动恢复同样计入次数）。
     * 前身是"全仓爆仓 −30"——爆仓的钱已经亏没了、大亏另有 PNL_LOSS 兜着，双重处罚撤销；
     * 这 30 分挪来看住"重置洗盘"这个真正的口子。
     */
    public static final int RESET_EXTRA = -30;
    public static final int CHECKIN_DAILY = 1;
    public static final int FIRST_COMMENT = 1;

    /** 投票每日总池 */
    public static final BigDecimal VOTE_DAILY_POOL = new BigDecimal("100");
    /**
     * 投票单人单日封顶。均分制下参与人数越少单人拿得越多，
     * 不封顶的话活动初期人没聚起来时早期玩家会拿到远超设计预期的分数。
     */
    public static final BigDecimal VOTE_DAILY_CAP = new BigDecimal("6");

    // ==================== 阶梯（n 从 1 起） ====================

    /** ROI≥25%：每笔 1 分，10 笔封顶 */
    public static int roi25Tier(int n) {
        return n <= 10 ? 1 : 0;
    }

    /** ROI≥50%：前 5 笔各 5 分，之后各 1 分 */
    public static int roi50Tier(int n) {
        return n <= 5 ? 5 : 1;
    }

    /** ROI≥100%：首笔 15，第 2-3 笔各 5，之后各 1 */
    public static int roi100Tier(int n) {
        if (n == 1) return 15;
        return n <= 3 ? 5 : 1;
    }

    /** 单笔封神 ROI≥300%：一次性，第 2 笔起 0 分（不是降到 1 分） */
    public static int godlyTier(int n) {
        return n == 1 ? 20 : 0;
    }

    /** 现货达标标的：前 3 个各 5 分，之后各 1 分 */
    public static int spotTier(int n) {
        return n <= 3 ? 5 : 1;
    }

    /**
     * 预测市场中奖：前 3 次各 5 分，第 4-10 次各 1 分，之后 0 分。
     * 每次中奖分值只与"第几次"有关，所以只要中奖次数就能算总分，SQL 侧 COUNT 即可。
     */
    public static int predictionTier(int n) {
        if (n <= 3) return 5;
        return n <= 10 ? 1 : 0;
    }

    /** 单仓位净利润 > 1000：每仓 1 分，25 仓封顶（防大本金无限刷） */
    public static int pnlProfitTier(int n) {
        return n <= 25 ? 1 : 0;
    }

    /**
     * 阶梯前 n 项之和。所有阶梯的分值都只与"第几次"有关，于是任何一档的总分
     * 都能从<b>次数</b>直接算出 —— 重置遗留（campaign_carryover 只存次数）靠的就是这一点：
     * 合并次数后从头累加，阶梯跨重置接着数，高分档与一次性档都刷不出第二份。
     */
    public static int tierSum(IntUnaryOperator tier, int n) {
        int sum = 0;
        for (int i = 1; i <= n; i++) sum += tier.applyAsInt(i);
        return sum;
    }

    // ==================== 连续签到 ====================

    /**
     * 连续签到奖励，累进：连满 14 天 = 5+15+40 = 60。
     * <p>
     * 参数是<b>最长连续段</b>而非总签到天数，且各档只发一次 —— 若按段累加，
     * "签3天歇1天"能拿两个 +5，比老老实实连签 6 天(+5)还多，时间这个压缩不了的资源就被绕过了。
     */
    public static int streakBonus(int longestStreak) {
        int bonus = 0;
        if (longestStreak >= 3) bonus += 5;
        if (longestStreak >= 7) bonus += 15;
        if (longestStreak >= 14) bonus += 40;
        return bonus;
    }

    /** 一组签到日里的最长连续天数；去重后排序再扫，调用方不必保证顺序 */
    public static int longestStreak(Collection<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) return 0;
        List<LocalDate> sorted = dates.stream().distinct().sorted().toList();
        int best = 1;
        int cur = 1;
        for (int i = 1; i < sorted.size(); i++) {
            cur = sorted.get(i - 1).plusDays(1).equals(sorted.get(i)) ? cur + 1 : 1;
            best = Math.max(best, cur);
        }
        return best;
    }

    // ==================== 最大余额法 ====================

    /**
     * 按权重把奖池精确分到 0.01，发出去的总额恰好等于奖池。
     * <p>
     * 【为什么全程按"分"的整数算】先各自算出小数金额再四舍五入，误差会累加：
     * 100 人每人差半分就是 5 毛钱发不出去或超发。这里先向下取整到分，
     * 剩下的零头按被截掉的小数从大到小每人补 0.01，直到凑满 —— 总额是构造出来的，不是算出来的。
     * <p>
     * 【同余额时按 userId 升序】保证同输入必得同输出。分配结果要能事后复现，
     * 否则出争议时没法自证"当初就是这么分的"。
     * <p>
     * 权重为 0 或负的条目由调用方剔除；本方法只做算术，不判谁该不该参与分配。
     */
    public static Map<Long, BigDecimal> largestRemainder(BigDecimal pool, Map<Long, BigDecimal> weights) {
        Map<Long, BigDecimal> positive = new LinkedHashMap<>();
        weights.forEach((k, v) -> {
            if (v != null && v.signum() > 0) positive.put(k, v);
        });
        if (positive.isEmpty()) return Map.of();

        BigDecimal total = positive.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        long poolCents = pool.movePointRight(2).setScale(0, RoundingMode.DOWN).longValueExact();

        record Share(Long userId, long cents, BigDecimal remainder) {
        }

        List<Share> shares = new ArrayList<>(positive.size());
        long allocated = 0;
        for (Map.Entry<Long, BigDecimal> e : positive.entrySet()) {
            BigDecimal exact = BigDecimal.valueOf(poolCents)
                    .multiply(e.getValue())
                    .divide(total, 10, RoundingMode.HALF_UP);
            long floor = exact.setScale(0, RoundingMode.DOWN).longValueExact();
            shares.add(new Share(e.getKey(), floor, exact.subtract(BigDecimal.valueOf(floor))));
            allocated += floor;
        }

        shares.sort(Comparator.comparing(Share::remainder).reversed().thenComparing(Share::userId));

        long rest = poolCents - allocated;
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        for (int i = 0; i < shares.size(); i++) {
            long cents = shares.get(i).cents() + (i < rest ? 1 : 0);
            result.put(shares.get(i).userId(), BigDecimal.valueOf(cents, 2));
        }
        return result;
    }
}

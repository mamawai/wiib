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
 * 【ROI 阶梯是占位制】每仓只占"还有名额的最高档"，高档满了往下顺延（见 {@link #roiLadder}）。
 * 各档独立判的话，100x 杠杆下价格动 1% 的一笔仓能同时吃满全部档位；
 * 占位制下它只占走一个名额，高分得靠多笔不同的仓去凑，梭哈换不来超额收益。
 */
public final class ScoreRules {

    private ScoreRules() {
    }

    // ==================== 阈值 ====================

    /** 仓位任务的保证金门槛，判的是累计投入（订单侧 invested_margin），不是仓位表的残值 margin */
    public static final BigDecimal MIN_MARGIN = new BigDecimal("500");
    public static final BigDecimal ROI_20 = new BigDecimal("0.20");
    public static final BigDecimal ROI_40 = new BigDecimal("0.40");
    /** 只给三市通吃的桶用：桶的门槛独立于占位制阶梯，保持 50% */
    public static final BigDecimal ROI_50 = new BigDecimal("0.50");
    public static final BigDecimal ROI_60 = new BigDecimal("0.60");
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
    /** 现货：达标单位的台阶宽度 —— 标的已实现收益率每摸到一个 10% 的整数倍记一个单位 */
    public static final BigDecimal SPOT_UNIT_STEP = new BigDecimal("0.10");

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

    // ==================== ROI 占位制阶梯 ====================

    /** 各档名额。20~40% 档满了就是 0 分；≥40% 配额外的溢出每笔 1 分、无上限（全场唯一无限项） */
    public static final int LADDER_100_SLOTS = 1;
    public static final int LADDER_60_SLOTS = 3;
    public static final int LADDER_40_SLOTS = 5;
    public static final int LADDER_20_SLOTS = 20;

    /** 各档单笔分值（≥40% 溢出与 20~40% 档都是每笔 1 分，不设常量） */
    public static final int LADDER_100_POINTS = 15;
    public static final int LADDER_60_POINTS = 5;
    public static final int LADDER_40_POINTS = 3;

    /**
     * 占位结果：n100/n60/n40 是各档实际占掉的名额数，extra40 是 ≥40% 配额外的溢出笔数（每笔 1 分），
     * band20 是 20%~40% 区间的达标笔数 —— 不封顶（展示要报真实笔数），计分时超出名额的部分为 0。
     */
    public record RoiLadder(int n100, int n60, int n40, int extra40, int band20) {
    }

    /**
     * ROI 占位制：每仓只占"还有名额的最高档"，高档满了往下顺延；≥40% 的仓在配额外每笔 +1 无限。
     * <p>
     * 入参是四个阈值的<b>累计</b>达标笔数（≥20% / ≥40% / ≥60% / ≥100%，一笔 100% 的仓四个都 +1）。
     * 资格是嵌套的（≥100% 必然 ≥60%/≥40%），"按平仓时间贪心占最高档"的结果因此只由笔数决定、
     * 与顺序无关 —— 占位制照样是纯次数函数，重置遗留合并次数后从头重算即可，这是它能落地的关键。
     * <p>
     * 20%~40% 区间的仓只进自己那档（{@link #LADDER_20_SLOTS} 笔封顶），
     * 不与 ≥40% 的仓抢名额，也吃不到无限 +1。
     */
    public static RoiLadder roiLadder(int c20, int c40, int c60, int c100) {
        int n100 = Math.min(c100, LADDER_100_SLOTS);

        // 100 档占不上的 ≥100% 仓落进 60 档的池子，60 档占不上的再落 40 档，依次类推
        int pool60 = (c100 - n100) + (c60 - c100);
        int n60 = Math.min(pool60, LADDER_60_SLOTS);

        int pool40 = (pool60 - n60) + (c40 - c60);
        int n40 = Math.min(pool40, LADDER_40_SLOTS);

        return new RoiLadder(n100, n60, n40, pool40 - n40, c20 - c40);
    }

    // ==================== 阶梯（n 从 1 起） ====================

    /** 单笔封神 ROI≥300%：一次性 25 分，第 2 笔起 0 分（不是降到 1 分）。独立于占位制，不占名额 */
    public static int godlyTier(int n) {
        return n == 1 ? 25 : 0;
    }

    /** 现货达标单位（已实现收益率每摸到一个 10% 台阶记一个）：前 3 个各 5 分，之后各 1 分、限 20 次 */
    public static int spotTier(int n) {
        if (n <= 3) return 5;
        return n <= 23 ? 1 : 0;
    }

    /**
     * 预测市场中奖：前 3 次各 3 分，第 4-10 次各 1 分，之后 0 分。
     * 每次中奖分值只与"第几次"有关，所以只要中奖次数就能算总分，SQL 侧 COUNT 即可。
     */
    public static int predictionTier(int n) {
        if (n <= 3) return 3;
        return n <= 10 ? 1 : 0;
    }

    /** 单仓位净利润 > 1000：每仓 1 分，20 仓封顶（防大本金无限刷） */
    public static int pnlProfitTier(int n) {
        return n <= 20 ? 1 : 0;
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
     * 连续签到奖励，累进：连满 14 天 = 3+5+10 = 18。
     * <p>
     * 参数是<b>最长连续段</b>而非总签到天数，且各档只发一次 —— 若按段累加，
     * "签3天歇1天"能拿两个 +3，比老老实实连签 6 天(+3)还多，时间这个压缩不了的资源就被绕过了。
     */
    public static int streakBonus(int longestStreak) {
        int bonus = 0;
        if (longestStreak >= 3) bonus += 3;
        if (longestStreak >= 7) bonus += 5;
        if (longestStreak >= 14) bonus += 10;
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

package com.mawai.wiibsim.campaign.service;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.campaign.model.CampaignScore;
import com.mawai.wiibsim.campaign.model.EligibleUserRow;
import com.mawai.wiibsim.campaign.model.MyCampaignView;
import com.mawai.wiibsim.campaign.model.ScoreItem;
import com.mawai.wiibsim.campaign.score.TradeScorer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把交易分、日常分、投票分并成一张全站积分表，「我的积分」「排行」「预估 LDC」全从这一份结果里取。
 * <p>
 * 【为什么单独一个类而不是塞进 CampaignService】{@link CampaignService} 是写路径的闸
 * （requireRunning），签到与投票两个 Service 都注入它；本类反过来要用那两个 Service 的汇总结果。
 * 塞在一起就是 CampaignService ⇄ 签到/投票 的构造器循环依赖。拆开之后依赖是单向的
 * （本类 → 那三个），CampaignService 保持只认 CampaignMapper 的样子，各司其职。
 * <p>
 * 【全站一次算完，不按人查】~100 人的量级，全站扫一次 + 缓存 60 秒，比每个人各查一遍便宜；
 * 更要紧的是三个视图同源，不会出现"我的积分 37、榜上写 36"这种对不上账的场面。
 */
@Service
@RequiredArgsConstructor
public class CampaignScoreService {

    /** 投票分在明细里的 code，与 {@link TradeScorer}/{@link CampaignCheckinService} 那些同级 */
    public static final String CODE_VOTE = "VOTE";

    private static final String BOARD_KEY = "campaign:board:";
    private static final Duration BOARD_TTL = Duration.ofSeconds(60);

    private final CampaignService campaignService;
    private final CampaignStatsMapper statsMapper;
    private final TradeScorer tradeScorer;
    private final CampaignCheckinService checkinService;
    private final CampaignVoteService voteService;
    private final CacheService cacheService;

    /**
     * 全站积分表，按最终分降序。缓存 60 秒。
     * <p>
     * 【为什么不落进度表】~100 人的量级不值得维护一张会写坏、会与真实数据漂移的进度表。
     * 唯一真相永远是业务表，每次现扫现算，缓存只挡住 60 秒内的重复请求。
     * <p>
     * 【读路径用 current() 不用 requireRunning()】活动开始前要能展示"还有几天开赛"、
     * 结束后要能展示最终榜单与领取入口；判了窗口这两段时间前端就只剩报错。
     */
    public List<CampaignScore> scoreBoard() {
        Campaign c = campaignService.current();
        if (c == null) return List.of();

        String key = BOARD_KEY + c.getId();
        String cached = cacheService.get(key);
        if (cached != null) {
            return JSON.parseArray(cached, CampaignScore.class);
        }

        List<CampaignScore> board = computeBoard(c);
        cacheService.set(key, JSON.toJSONString(board), BOARD_TTL);
        return board;
    }

    /**
     * 现扫现算一份积分表。
     * <p>
     * 【名单只认 listEligibleUsers】三份分数图里都可能出现名单外的 userId ——
     * 尤其投票那份（{@code sumScoreByUser} 不筛 result，只投过票还没结算的人也会出一行、total=0），
     * 所以循环必须以名单为轴、用分数图去取值，反过来遍历分数图就会把机器人和邀请码用户放进榜里。
     * <p>
     * 【活动进行中的分是下限，不是终值】预测市场那条 SQL 按 created_at 卡窗口但要求 status='WON'，
     * 而 WON 是结算时才写的 —— 临近结束下的注要等活动结束之后才结算得出。同理最后一天的投票分
     * 也要隔天 00:05 才发。所以活动期间显示的分只会比最终分少，不会多。这是接受的，别当 bug 去"修"。
     */
    private List<CampaignScore> computeBoard(Campaign c) {
        Map<Long, List<ScoreItem>> trade = tradeScorer.scoreAll(c.getStartAt(), c.getEndAt());
        Map<Long, List<ScoreItem>> daily = checkinService.scoreAll(c);
        Map<Long, BigDecimal> vote = voteService.voteScoreByUser(c.getId());

        List<CampaignScore> board = new ArrayList<>();
        for (EligibleUserRow u : statsMapper.listEligibleUsers()) {
            List<ScoreItem> tradeItems = trade.getOrDefault(u.getUserId(), List.of());
            List<ScoreItem> dailyItems = daily.getOrDefault(u.getUserId(), List.of());
            BigDecimal voteScore = vote.getOrDefault(u.getUserId(), BigDecimal.ZERO);

            // 罚分单独拎出来：正分与扣分分开存，出争议时能直接回答"为什么是这个分"
            int tradeScore = sumPositive(tradeItems);
            int penalty = sumNegative(tradeItems);
            int dailyScore = sumPositive(dailyItems);

            BigDecimal raw = BigDecimal.valueOf(tradeScore + dailyScore + penalty).add(voteScore);
            BigDecimal finalScore = raw.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

            List<ScoreItem> items = new ArrayList<>(tradeItems);
            items.addAll(dailyItems);
            if (voteScore.signum() > 0) {
                items.add(new ScoreItem(CODE_VOTE, "每日多空投票", 0, voteScore));
            }

            // 一分没有的人不上榜：榜单里挂一串 0 分只是噪音。
            // 判 items 是为了留住"扣到 0 分"的人 —— 他有明细，得让他看见自己被扣在哪
            if (finalScore.signum() == 0 && items.isEmpty()) continue;

            board.add(new CampaignScore(u.getUserId(), u.getUsername(), u.claimable(),
                    tradeScore, dailyScore, voteScore, penalty, finalScore, items));
        }

        board.sort(Comparator.comparing(CampaignScore::finalScore).reversed()
                .thenComparing(CampaignScore::userId));
        return board;
    }

    private static int sumPositive(List<ScoreItem> items) {
        return items.stream().map(ScoreItem::score)
                .filter(s -> s.signum() > 0).mapToInt(BigDecimal::intValue).sum();
    }

    private static int sumNegative(List<ScoreItem> items) {
        return items.stream().map(ScoreItem::score)
                .filter(s -> s.signum() < 0).mapToInt(BigDecimal::intValue).sum();
    }

    /**
     * 参与 LDC 分配的权重：能领取且分数为正的人。分母只算这些人。
     * <p>
     * 用 LinkedHashMap 保住榜单顺序 —— 结算侧要按名次逐个发放，顺序稳定才能对着日志核账。
     */
    public Map<Long, BigDecimal> eligibleWeights(List<CampaignScore> board) {
        Map<Long, BigDecimal> weights = new LinkedHashMap<>();
        for (CampaignScore s : board) {
            if (s.claimable() && s.finalScore().signum() > 0) weights.put(s.userId(), s.finalScore());
        }
        return weights;
    }

    /**
     * 活动页一次要的全部数据。没有活动返回 null（前端据此隐藏活动入口）。
     * <p>
     * 【没上榜的人给全零兜底而不是抛】活动刚开、一分没挣的人打开页面是最常见的情形，
     * 这时 rank = 0 表示"还没上榜"，预估 0。
     * <p>
     * 【checkedToday 的已知口径缺口】{@link CampaignCheckinService#checkedToday} 只按
     * campaignId + 今天查行，不筛活动时间窗，而计分那侧（scoreAll / myDates）是筛的。
     * 于是运营挪过 start_at/end_at 之后，会出现"页面显示已签到、但那天一分不算"的短暂不一致。
     * 这里<b>照用不改</b>：一来影响只是个对勾，分数面板本身出自 scoreAll 仍然是对的；
     * 二来真要补，该补在 checkedToday 里（把 Campaign 传进去复用同一个 inWindow），
     * 而不是在调用侧再抄一份日界判断 —— 那个 [startAt, endAt) 跨 DATE 与 TIMESTAMP 的比法
     * 抄第三份必然漂移，比这个对勾贵得多。
     */
    public MyCampaignView myView(Long userId) {
        Campaign c = campaignService.current();
        if (c == null) return null;

        List<CampaignScore> board = scoreBoard();
        CampaignScore me = board.stream().filter(s -> s.userId().equals(userId)).findFirst()
                .orElse(new CampaignScore(userId, null, false, 0, 0, BigDecimal.ZERO, 0,
                        BigDecimal.ZERO, List.of()));

        Map<Long, BigDecimal> weights = eligibleWeights(board);
        BigDecimal total = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal estimated = (total.signum() > 0 && weights.containsKey(userId))
                ? c.getPrizePool().multiply(me.finalScore()).divide(total, 2, RoundingMode.DOWN)
                : BigDecimal.ZERO;

        int rank = 0;
        for (int i = 0; i < board.size(); i++) {
            if (board.get(i).userId().equals(userId)) { rank = i + 1; break; }
        }

        return new MyCampaignView(c.getId(), c.getName(),
                c.getStartAt().toString(), c.getEndAt().toString(), c.getPrizePool(),
                me, total, estimated, rank, board.size(),
                checkinService.checkedToday(c.getId(), userId),
                voteService.board(userId));
    }
}

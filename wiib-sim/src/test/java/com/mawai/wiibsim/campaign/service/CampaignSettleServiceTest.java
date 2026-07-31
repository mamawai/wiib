package com.mawai.wiibsim.campaign.service;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignReward;
import com.mawai.wiibsim.campaign.mapper.CampaignMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignRewardMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignVoteMapper;
import com.mawai.wiibsim.campaign.model.CampaignScore;
import com.mawai.wiibsim.campaign.model.EligibleUserRow;
import com.mawai.wiibsim.campaign.model.ScoreItem;
import com.mawai.wiibsim.campaign.score.TradeScorer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结算：奖池怎么分、谁落行、什么情况下必须拒绝结算。不起 Spring、不连库、不连 Redis。
 * <p>
 * 【为什么 CampaignScoreService 用真的、只 mock 它底下那几个】本任务最贵的一条不变量是
 * <b>"结算读的是现算的榜，不是 60 秒缓存的那份"</b>。mock 掉 CampaignScoreService 就把这条一起
 * mock 掉了：无论结算调 scoreBoard() 还是 freshBoard()，桩都给同一个答案，用例照绿而线上少发钱。
 * 用真的之后，缓存里那份<b>故意做旧</b>的榜就成了照妖镜（见 {@code 结算不吃六十秒缓存}）。
 * 同理 CampaignService 也用真的，只 mock 它底下的 CampaignMapper。
 * <p>
 * 【样本是照着"天真实现必须露馅"挑的】权重 1000 / 1000 / 1000 / 0.01，奖池 500：
 * 精确份额是 16666.61 分，天真实现（各自 HALF_UP 到分）三个人各拿 166.67，
 * 加起来 <b>500.01</b> —— 超发一分。最大余额法给 166.67 / 166.67 / 166.66，恰好 500.00。
 * 所以本类那条总额断言是真有牙的，不是"怎么算都对"的同义反复
 * （Task 2 评审提过：权重挑得不好的话，天真法也恰好凑得出奖池，那条用例就成了摆设）。
 * <p>
 * 【同余额时那多出的 2 分归谁：钉了结果，但钉不住原因】三个人的余数逐位相同，本类断言那 2 分
 * 落在 ACE 与 MID（id 1、2）、LOW（id 5）少一分 —— 这是真金白银的语义，值得钉。
 * 但<b>删掉 {@code largestRemainder} 里的 .thenComparing(Share::userId) 本类照绿</b>（实测过）：
 * List.sort 是稳定排序，而喂进去的顺序本就是榜序 1,2,5，两条路答案一样。
 * 也就是说这条断言证的是"当前实现给出这个结果"，不是"tie-break 那句在承重"。
 * 后者是 {@code ScoreRulesTest.除不尽时余数补给userId最小的那个} 的活（它把喂入顺序刻意打乱，
 * 删掉 tie-break 就红），别在这儿假装覆盖了。
 * <p>
 * 【TINY 那 0.00 是承重的】最大余额法在权重悬殊时会给末位分到 0.00，落一行就是给用户一个
 * 点了必然失败的领取按钮。本类钉住它不落行，且不落行不影响总额对账。
 */
class CampaignSettleServiceTest {

    private static final long CAMPAIGN_ID = 7L;
    private static final String CODE = "FIVEDIM_202608";
    private static final String BOARD_KEY = "campaign:board:7";
    private static final BigDecimal POOL = new BigDecimal("500");

    private static final long ACE = 1L;    // 权重 1000.00 → 166.67（余数并列，靠 userId 小拿到那多的 1 分）
    private static final long MID = 2L;    // 权重 1000.00 → 166.67（同上）
    private static final long LOW = 5L;    // 权重 1000.00 → 166.66（userId 最大，那 1 分轮不到他）
    private static final long TINY = 3L;   // 权重 0.01    → 0.00，不落行
    private static final long ZERO = 4L;   // 罚到 0 分：上榜但不参与分配，不落行
    private static final long GHOST = 900L; // 有投票分但不在参与名单里，连榜都上不了

    private CampaignMapper campaignMapper;
    private CampaignRewardMapper rewardMapper;
    private CampaignVoteMapper voteMapper;
    private CampaignStatsMapper statsMapper;
    private TradeScorer tradeScorer;
    private CampaignCheckinService checkinService;
    private CampaignVoteService voteService;
    private CacheService cacheService;
    private CampaignSettleService service;

    /** 活动已经结束（20 天前开始、1 天前收摊）—— 结算的正常前提 */
    private LocalDateTime startAt;
    private LocalDateTime endAt;

    @BeforeEach
    void setUp() {
        campaignMapper = mock(CampaignMapper.class);
        rewardMapper = mock(CampaignRewardMapper.class);
        voteMapper = mock(CampaignVoteMapper.class);
        statsMapper = mock(CampaignStatsMapper.class);
        tradeScorer = mock(TradeScorer.class);
        checkinService = mock(CampaignCheckinService.class);
        voteService = mock(CampaignVoteService.class);
        cacheService = mock(CacheService.class);

        startAt = LocalDateTime.now().minusDays(20);
        endAt = LocalDateTime.now().minusDays(1);

        CampaignService campaignService = new CampaignService(campaignMapper);
        CampaignScoreService scoreService = new CampaignScoreService(campaignService, statsMapper,
                tradeScorer, checkinService, voteService, cacheService);
        service = new CampaignSettleService(campaignMapper, rewardMapper, voteMapper,
                campaignService, scoreService);

        activeCampaign(Campaign.STATUS_RUNNING);
        when(rewardMapper.countByCampaign(CAMPAIGN_ID)).thenReturn(0);
        when(voteMapper.listUnsettledDates(CAMPAIGN_ID)).thenReturn(List.of());
        stubScores();
    }

    // ==================== 分配 ====================

    /**
     * ★ 本任务的验收线：发出去的总额恰好等于奖池，一分不差 ★
     * <p>
     * 对不上就是 largestRemainder 的调用出了问题（比如喂进去的不是 eligibleWeights、
     * 或者奖池取错了列），一分都不许放过 —— 这是别人的钱。
     */
    @Test
    void 落表金额之和恰好等于奖池() {
        service.settle();

        BigDecimal sum = insertedRewards().stream()
                .map(CampaignReward::getLdcAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sum).as("发出去的总额必须与奖池逐分相等").isEqualByComparingTo(POOL);
    }

    /**
     * 每个人分到多少、"分到 0.00 的人不落行"、以及同余额时那多出的分归谁。
     * <p>
     * 三个人权重完全一样，除不尽多出 2 分 —— 给了 ACE 与 MID，LOW 少一分（归属的可测性见类注释）。
     * 天真的逐人四舍五入会给三个人各 166.67（合计 500.01，超发一分），这条当场红。
     */
    @Test
    void 按权重分配且分到零的人不落行() {
        assertThat(service.settle()).as("TINY 分到 0.00 不落行，只剩三个人").isEqualTo(3);

        assertThat(insertedRewards())
                .extracting(CampaignReward::getUserId, CampaignReward::getLdcAmount)
                .containsExactly(
                        tuple(ACE, new BigDecimal("166.67")),
                        tuple(MID, new BigDecimal("166.67")),
                        tuple(LOW, new BigDecimal("166.66")));
    }

    /**
     * ★★ 结算必须现算榜，不许吃 60 秒缓存 ★★
     * <p>
     * 【败法】投票结算 00:05:00 写完最后一天的分，结算 00:05:30 命中一份 00:04:50 算的榜
     * —— 那时最后一批分还不存在。按它分池子就是按永久少算的权重把钱发出去，
     * 而发放是 CAS 幂等的，发完纠不回来；更糟的是这事完全不响，陈旧的榜内部是自洽的。
     * <p>
     * 【用例怎么造】缓存里放一份<b>还没有 LOW</b> 的旧榜（模拟"他最后一天的分刚落库、缓存里还没有"）。
     * 吃缓存的话 ACE 与 MID 各拿 250.00 而 LOW 一分没有、连行都没有；现算才是 166.67/166.67/166.66。
     * 外加一条 never().get()：现算这条路根本不该去碰那个键。
     */
    @Test
    void 结算不吃六十秒缓存而是现算一份榜() {
        when(cacheService.get(BOARD_KEY)).thenReturn(JSON.toJSONString(List.of(
                new CampaignScore(ACE, "ace", true, 1000, 0, BigDecimal.ZERO, 0,
                        new BigDecimal("1000.00"), List.of()),
                new CampaignScore(MID, "mid", true, 1000, 0, BigDecimal.ZERO, 0,
                        new BigDecimal("1000.00"), List.of()))));

        service.settle();

        assertThat(insertedRewards())
                .as("吃了旧缓存的话是 ACE/MID 各 250.00，而 LOW 连行都没有")
                .extracting(CampaignReward::getUserId, CampaignReward::getLdcAmount)
                .containsExactly(
                        tuple(ACE, new BigDecimal("166.67")),
                        tuple(MID, new BigDecimal("166.67")),
                        tuple(LOW, new BigDecimal("166.66")));
        verify(cacheService, never()).get(anyString());
    }

    /**
     * ★ 先算榜、再把活动翻成 SETTLING ★
     * <p>
     * selectActive() 现在 {@code status IN ('RUNNING','SETTLING')}，所以顺序反了也还查得到活动。
     * 但那条 WHERE 是别人改得动的，而"翻了状态就再也算不出榜、于是谁也拿不到钱"这个失败完全无声。
     * 这条把顺序钉死，别人收窄那条 SQL 时会先在这里红。
     */
    @Test
    void 先算榜再翻活动状态() {
        service.settle();

        InOrder order = inOrder(statsMapper, campaignMapper);
        order.verify(statsMapper).listEligibleUsers();
        order.verify(campaignMapper).updateById(any(Campaign.class));

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Campaign.STATUS_SETTLING);
    }

    /** 单号是 WIIB_{活动码}_{userId}，人工补发要能照着重算出同一个串 */
    @Test
    void 单号是活动码加用户号() {
        service.settle();

        assertThat(insertedRewards())
                .extracting(CampaignReward::getOutTradeNo)
                .containsExactly("WIIB_FIVEDIM_202608_1", "WIIB_FIVEDIM_202608_2",
                        "WIIB_FIVEDIM_202608_5");
        assertThat(CampaignSettleService.outTradeNo(CODE, ACE)).isEqualTo("WIIB_FIVEDIM_202608_1");
    }

    /**
     * 落表的明细分与榜上那份逐字段一致，且身份留空、状态是 PENDING。
     * <p>
     * 【为什么 linux_do_id 落空串】真正的收款人身份要等领取时二次 OAuth 拿最新值回填
     * （库里那份 username 可能是过期的旧名）；列是 NOT NULL，所以是空串不是 null。
     */
    @Test
    void 落表的明细分与状态() {
        service.settle();

        CampaignReward ace = insertedRewards().getFirst();
        assertThat(ace)
                .extracting(CampaignReward::getCampaignId, CampaignReward::getUserId,
                        CampaignReward::getUsername, CampaignReward::getLinuxDoId,
                        CampaignReward::getTradeScore, CampaignReward::getDailyScore,
                        CampaignReward::getPenalty, CampaignReward::getStatus)
                .containsExactly(CAMPAIGN_ID, ACE, "ace", "", 1000, 0, 0, CampaignReward.PENDING);
        assertThat(ace.getVoteScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ace.getFinalScore()).isEqualByComparingTo(new BigDecimal("1000"));

        // MID 那 1.00 投票分：三个人权重靠它才相等，丢了他就掉一档
        CampaignReward mid = insertedRewards().get(1);
        assertThat(mid.getDailyScore()).isEqualTo(999);
        assertThat(mid.getVoteScore()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(mid.getFinalScore()).isEqualByComparingTo(new BigDecimal("1000"));
    }

    /**
     * 分不到钱的两类人都不落行：0 分的（ZERO，被罚分兜到 0，人在榜上）
     * 与不在参与名单里的（GHOST，只在投票分那张图里出现）。
     * <p>
     * GHOST 这一类是承重的：{@code sumScoreByUser} 不筛 result，只投过票还没结算的人也会出一行。
     * 拿那张图当名单，机器人和邀请码用户就进了发钱名单 —— 而他们物理上收不到 LDC，
     * 那部分奖池会永远卡在账上谁也拿不走。
     */
    @Test
    void 零分与名单外的人都不落行() {
        service.settle();

        assertThat(insertedRewards())
                .extracting(CampaignReward::getUserId)
                .containsExactly(ACE, MID, LOW)
                .doesNotContain(ZERO, GHOST, TINY);
    }

    // ==================== 幂等 ====================

    /** 已经结算过就原样返回条数：一行不重插、状态不重翻、榜也不必再算一遍 */
    @Test
    void 已结算过直接返回条数不重复生成() {
        when(rewardMapper.countByCampaign(CAMPAIGN_ID)).thenReturn(42);

        assertThat(service.settle()).isEqualTo(42);

        verify(rewardMapper, never()).insert(any(CampaignReward.class));
        verify(campaignMapper, never()).updateById(any(Campaign.class));
        verify(statsMapper, never()).listEligibleUsers();
    }

    // ==================== 拒绝结算的几种情形 ====================

    /**
     * ★★ 投票没结完就拒绝结算 ★★
     * <p>
     * 【为什么这条最容易踩】未结算的票 score 是 NULL，榜单按 0 计。所以"票没结完"不报错、不少人，
     * 只让那几个人的 vote_score 悄悄少一截，然后按少算的权重把钱发出去 —— 一次性、不可逆、无声。
     * <p>
     * 【为什么活动刚结束往往就是没结完的】settleDay 只结已经过完的 UTC 日；TZ=+8 时活动在
     * UTC 末日 16:00 收摊，那天还得再等 8 小时（或下一次 00:05 回扫）才结得上。
     * <p>
     * 报错里必须带上具体是哪几天，运营才分得清"再等一晚"还是"某天一直取不到日线得去查"。
     */
    @Test
    void 投票没结完拒绝结算并报出是哪几天() {
        LocalDate lastDay = LocalDate.of(2026, 8, 16);
        when(voteMapper.listUnsettledDates(CAMPAIGN_ID)).thenReturn(List.of(lastDay));

        assertThatThrownBy(() -> service.settle())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("还有投票没结算")
                .hasMessageContaining("2026-08-16");

        assertNothingWritten();
    }

    /**
     * 活动还没结束就拒绝结算 —— 结算把当下的分定格成钱发出去，一次性不可逆。
     * 活动进行到一半点下去就是拿半场的分把整个奖池分完，后半程再怎么打也兑不成 LDC。
     * <p>
     * 真要提前结，改 end_at 即可（campaign 表本就设计成可运行时改），这里不锁死路。
     */
    @Test
    void 活动没结束拒绝结算() {
        endAt = LocalDateTime.now().plusDays(3);
        activeCampaign(Campaign.STATUS_RUNNING);

        assertThatThrownBy(() -> service.settle())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("活动还没结束");

        assertNothingWritten();
    }

    /** 没有 active 活动（都 DONE 了）就没什么可结的 */
    @Test
    void 没有活动时拒绝结算() {
        when(campaignMapper.selectActive()).thenReturn(null);

        assertThatThrownBy(() -> service.settle())
                .isInstanceOf(BizException.class)
                .hasMessage("没有可结算的活动");

        assertNothingWritten();
    }

    /**
     * 一个够格分钱的人都没有时拒绝结算，而不是翻成 SETTLING 之后一行不落。
     * <p>
     * 这条正是 selectActive() 那条链路出岔子的样子：freshBoard() 返回空 → eligibleWeights 空 map
     * → 谁也拿不到钱。让它响出来，别悄悄把活动推进结算态。
     */
    @Test
    void 没人够格分钱时拒绝结算() {
        when(statsMapper.listEligibleUsers()).thenReturn(List.of(user(ZERO, "zero")));

        assertThatThrownBy(() -> service.settle())
                .isInstanceOf(BizException.class)
                .hasMessage("没有符合领取条件的用户，无法结算");

        assertNothingWritten();
    }

    // ==================== 手搓行 ====================

    /**
     * 权重设计见类注释：ACE / MID / LOW 各 1000.00，TINY 0.01，ZERO 0（罚到 0）。
     * GHOST 只出现在投票分图里，用来钉"名单以 listEligibleUsers 为轴"。
     * <p>
     * MID 的 1000.00 是 999 签到分 + 1.00 投票分凑的，好让"投票分真进了权重"这条也被带上
     * —— 三个人权重一样，MID 的那 1.00 要是丢了，他就掉到 166.50 一档，本类立刻红。
     * <p>
     * 名单顺序刻意打乱：排序真在干活的话，落表顺序必须是 1,2,5 而不是这里的顺序。
     */
    private void stubScores() {
        when(statsMapper.listEligibleUsers()).thenReturn(List.of(
                user(TINY, "tiny"), user(ZERO, "zero"), user(LOW, "low"),
                user(ACE, "ace"), user(MID, "mid")));

        when(tradeScorer.scoreAll(startAt, endAt)).thenReturn(Map.of(
                ACE, List.of(ScoreItem.of("ROI50", "单仓位 ROI ≥ 50%", 3, 1000)),
                LOW, List.of(ScoreItem.of("ROI50", "单仓位 ROI ≥ 50%", 3, 1000)),
                ZERO, List.of(ScoreItem.of("LIQ_CROSS", "全仓爆仓", 1, -30))));

        when(checkinService.scoreAll(any(Campaign.class))).thenReturn(Map.of(
                MID, List.of(ScoreItem.of("CHECKIN", "每日签到", 14, 999))));

        when(voteService.voteScoreByUser(CAMPAIGN_ID)).thenReturn(Map.of(
                MID, new BigDecimal("1.00"),
                TINY, new BigDecimal("0.01"),
                GHOST, new BigDecimal("50.00")));
    }

    private void activeCampaign(String status) {
        Campaign c = new Campaign();
        c.setId(CAMPAIGN_ID);
        c.setCode(CODE);
        c.setStartAt(startAt);
        c.setEndAt(endAt);
        c.setPrizePool(POOL);
        c.setStatus(status);
        when(campaignMapper.selectActive()).thenReturn(c);
    }

    private static EligibleUserRow user(long id, String name) {
        EligibleUserRow row = new EligibleUserRow();
        row.setUserId(id);
        row.setUsername(name);
        row.setLinuxDoId(String.valueOf(200000 + id));
        return row;
    }

    /** 按插入顺序拿到落表的每一行 */
    private List<CampaignReward> insertedRewards() {
        ArgumentCaptor<CampaignReward> captor = ArgumentCaptor.forClass(CampaignReward.class);
        verify(rewardMapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }

    /** 拒绝结算的用例统一收口：一行不插、状态不翻 */
    private void assertNothingWritten() {
        verify(rewardMapper, never()).insert(any(CampaignReward.class));
        verify(campaignMapper, never()).updateById(any(Campaign.class));
    }
}

package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignVote;
import com.mawai.wiibsim.campaign.mapper.CampaignMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignVoteMapper;
import com.mawai.wiibsim.campaign.model.VoteBoard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多空投票的下票与看板。不起 Spring、不连库。
 * <p>
 * 【为什么 CampaignService 用真的、只 mock 它底下的 CampaignMapper】同
 * {@link CampaignCheckinServiceTest}：投票的时间窗闸门就在
 * {@link CampaignService#requireRunning()} 里，mock 掉它等于把被测的闸一起 mock 掉。
 * 顺带这也把"时钟"变得可控——不动系统时间，改喂进去那场活动的窗口就行。
 * <p>
 * 【这里能测什么、不能测什么】"多空二选一"这条铁律真正的执行者是数据库的 uk_campaign_vote
 * 唯一索引，mock 的 mapper 证不了它 —— 那条由
 * {@link com.mawai.wiibsim.campaign.CampaignVoteRealRunTest} 在真库上钉。
 * 本类管 Java 这一侧：闸门放不放行、插的行对不对（尤其投票日是不是 UTC 日）、
 * DuplicateKeyException 有没有被翻成人话、看板怎么把两条 SQL 的结果拼起来。
 */
class CampaignVoteServiceTest {

    private static final long CAMPAIGN_ID = 7L;
    private static final long ME = 1L;
    private static final long OTHER = 2L;

    private static final String BTC = CampaignVote.SYMBOL_BTC;
    private static final String GOLD = CampaignVote.SYMBOL_GOLD;

    private CampaignVoteMapper voteMapper;
    private CampaignMapper campaignMapper;
    private CampaignVoteService service;

    @BeforeEach
    void setUp() {
        voteMapper = mock(CampaignVoteMapper.class);
        campaignMapper = mock(CampaignMapper.class);
        // 行情客户端只在结算路径上用，下票/看板一次都不该碰它 —— 给个 mock 占位即可，
        // 结算那半边由 CampaignVoteSettleTest 单独钉
        service = new CampaignVoteService(voteMapper, new CampaignService(campaignMapper),
                mock(BinanceRestClient.class));

        // 默认给一场"此刻正开着"的活动，个别用例再按需换窗口
        running(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(13));
    }

    // ==================== 下票 ====================

    /**
     * 插的行必须是"本场活动 + 我 + <b>UTC</b> 今天 + 标的 + 方向"。
     * <p>
     * 【voteDate 为什么必须是 UTC 日】结算按 Binance 的 1d K 线走，那条线就是 UTC 日切；
     * 投票日跟着服务器本地日（Asia/Singapore）的话，UTC 16:00 之后投的票会被记成"明天"，
     * 永远对不上当日收盘。
     * <p>
     * 【这条用例的射程】断言比的是测试自己独立算出来的 UTC 日期，不是回头调
     * {@code utcToday()}（那样是同义反复）。但本地日与 UTC 日只在 UTC 16:00-24:00
     * 这一段才不同（+8 区），所以实现要是写成 {@code LocalDate.now()}，
     * 也只有那一段时间跑才咬得住 —— 要全天候咬死得给 service 注入 Clock，
     * 为一个静态工具方法加构造参数不划算。口径本身由类注释和 DDL 注释两处钉着。
     */
    @Test
    void 投票插入本场活动我今天UTC日的记录() {
        service.vote(ME, BTC, CampaignVote.UP);

        ArgumentCaptor<CampaignVote> captor = ArgumentCaptor.forClass(CampaignVote.class);
        verify(voteMapper).insert(captor.capture());
        assertThat(captor.getValue())
                .extracting(CampaignVote::getCampaignId,
                        CampaignVote::getUserId,
                        CampaignVote::getVoteDate,
                        CampaignVote::getSymbol,
                        CampaignVote::getDirection)
                .containsExactly(CAMPAIGN_ID, ME,
                        LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC),
                        BTC, CampaignVote.UP);
    }

    /** 黄金也能投，且方向 DOWN 原样落库 —— 免得实现里把标的或方向写死成 BTC/UP */
    @Test
    void 黄金看跌也能投且原样落库() {
        service.vote(ME, GOLD, CampaignVote.DOWN);

        ArgumentCaptor<CampaignVote> captor = ArgumentCaptor.forClass(CampaignVote.class);
        verify(voteMapper).insert(captor.capture());
        assertThat(captor.getValue())
                .extracting(CampaignVote::getSymbol, CampaignVote::getDirection)
                .containsExactly(GOLD, CampaignVote.DOWN);
    }

    /** 不在 SYMBOLS 里的标的：直接拒，且一行都不许插（否则脏数据结算时无价可比） */
    @Test
    void 不支持的标的被拒且一行都不插() {
        assertThatThrownBy(() -> service.vote(ME, "ETHUSDT", CampaignVote.UP))
                .isInstanceOf(BizException.class)
                .hasMessage("不支持的投票标的");

        verify(voteMapper, never()).insert(any(CampaignVote.class));
    }

    /** 方向只认 UP / DOWN 两个字面量，大小写变体、空值一律拒 */
    @Test
    void 非法方向被拒且一行都不插() {
        assertThatThrownBy(() -> service.vote(ME, BTC, "up"))
                .isInstanceOf(BizException.class)
                .hasMessage("方向只能是 UP 或 DOWN");
        assertThatThrownBy(() -> service.vote(ME, BTC, null))
                .isInstanceOf(BizException.class)
                .hasMessage("方向只能是 UP 或 DOWN");

        verify(voteMapper, never()).insert(any(CampaignVote.class));
    }

    /**
     * 同一标的投第二次：唯一索引顶回来的 DuplicateKeyException 翻成人话，
     * 且提示里带的是展示名（"BTC" / "黄金"）不是 symbol，用户不认识 XAUUSDT。
     * <p>
     * 这里若退回"先查后插"或捕获后重试，双击就能把多空各投一票、白拿一份投票分。
     */
    @Test
    void 重复投同一标的报多空二选一且不再插第二次() {
        when(voteMapper.insert(any(CampaignVote.class)))
                .thenThrow(new DuplicateKeyException("uk_campaign_vote"));

        assertThatThrownBy(() -> service.vote(ME, GOLD, CampaignVote.DOWN))
                .isInstanceOf(BizException.class)
                .hasMessage("今天已经投过 黄金 了，多空二选一");

        verify(voteMapper, times(1)).insert(any(CampaignVote.class));
    }

    // ==================== 时间窗闸门 ====================

    /*
     * 窗口的完整语义（半开区间、两端不留宽限期）由 CampaignCheckinServiceTest 对着同一个
     * CampaignService 钉过了，这里只钉"投票走的是 requireRunning 而不是 current"——
     * 走错了的话，种子活动排期未到时就能提前投票，而结算按 [start,end) 筛，那些票发不出分。
     */

    /** 活动尚未开始（种子活动此刻的真实状态）：拦下，且一行都不插 */
    @Test
    void 活动开始前投票被拦下且一行都不插() {
        running(LocalDateTime.now().plusHours(1), LocalDateTime.now().plusDays(14));

        assertThatThrownBy(() -> service.vote(ME, BTC, CampaignVote.UP))
                .isInstanceOf(BizException.class)
                .hasMessage("活动尚未开始");

        verify(voteMapper, never()).insert(any(CampaignVote.class));
    }

    /** 压根没有 RUNNING 活动时也是一行都不插 */
    @Test
    void 没有进行中的活动时投票直接被拦下() {
        when(campaignMapper.selectActive()).thenReturn(null);

        assertThatThrownBy(() -> service.vote(ME, BTC, CampaignVote.UP))
                .isInstanceOf(BizException.class)
                .hasMessage("活动未开始或已结束");

        verify(voteMapper, never()).insert(any(CampaignVote.class));
    }

    // ==================== 看板 ====================

    /**
     * 看板按 SYMBOLS 的顺序一标的一条（顺序即前端卡片顺序），票数各取各标的的行，
     * myDirection 只在我投过的标的上有值。
     * <p>
     * 【样本怎么设计的】BTC 与 GOLD 的票数刻意全不相同（3/1 与 2/5），
     * 实现里把 symbol 传串了、或把两个标的的结果混在一起，数就对不上。
     * 我只投了 BTC 的 UP：GOLD 那条的 myDirection 必须是 null 而不是 ""、也不是跟着 BTC 走。
     */
    @Test
    void 看板每标的一条并带上我的票() {
        LocalDate today = CampaignVoteService.utcToday();
        when(voteMapper.listMine(CAMPAIGN_ID, ME, today))
                .thenReturn(List.of(voteRow(BTC, CampaignVote.UP)));
        when(voteMapper.countByDirection(CAMPAIGN_ID, today, BTC))
                .thenReturn(List.of(countRow(CampaignVote.UP, 3), countRow(CampaignVote.DOWN, 1)));
        when(voteMapper.countByDirection(CAMPAIGN_ID, today, GOLD))
                .thenReturn(List.of(countRow(CampaignVote.UP, 2), countRow(CampaignVote.DOWN, 5)));

        assertThat(service.board(ME))
                .extracting(VoteBoard::symbol, VoteBoard::label,
                        VoteBoard::upCount, VoteBoard::downCount, VoteBoard::myDirection)
                .containsExactly(
                        tuple(BTC, "BTC", 3L, 1L, CampaignVote.UP),
                        tuple(GOLD, "黄金", 2L, 5L, null));
    }

    /**
     * 某方向一票没有时 GROUP BY 压根不出那一行 —— 得报 0，不能是 null 也不能炸。
     * 一天开始时两个标的都是这个状态，是最常见的一屏。
     */
    @Test
    void 一票没有时票数是零而不是空() {
        LocalDate today = CampaignVoteService.utcToday();
        // BTC 只有 UP 那一行，GOLD 一行都没有
        when(voteMapper.countByDirection(CAMPAIGN_ID, today, BTC))
                .thenReturn(List.of(countRow(CampaignVote.UP, 4)));
        when(voteMapper.countByDirection(CAMPAIGN_ID, today, GOLD)).thenReturn(List.of());

        assertThat(service.board(ME))
                .extracting(VoteBoard::symbol, VoteBoard::upCount, VoteBoard::downCount)
                .containsExactly(
                        tuple(BTC, 4L, 0L),
                        tuple(GOLD, 0L, 0L));
    }

    /** listMine 认人：别人的票只进总票数，不进我的 myDirection */
    @Test
    void 别人的票不进我的myDirection() {
        LocalDate today = CampaignVoteService.utcToday();
        when(voteMapper.listMine(CAMPAIGN_ID, OTHER, today))
                .thenReturn(List.of(voteRow(BTC, CampaignVote.DOWN)));
        when(voteMapper.countByDirection(CAMPAIGN_ID, today, BTC))
                .thenReturn(List.of(countRow(CampaignVote.DOWN, 1)));

        assertThat(service.board(ME))
                .as("我一票没投，listMine 该返回空（默认 stub），myDirection 全是 null")
                .extracting(VoteBoard::myDirection)
                .containsOnlyNulls();
        assertThat(service.board(OTHER))
                .extracting(VoteBoard::myDirection)
                .containsExactly(CampaignVote.DOWN, null);
    }

    /**
     * 没有活动时返回空 list（不是 null，也不是抛异常）—— 前端据此隐藏整块投票区。
     * 一条 SQL 都不该发：没有 campaign_id 可用，发出去也只能是错的。
     */
    @Test
    void 没有活动时看板返回空列表() {
        when(campaignMapper.selectActive()).thenReturn(null);

        assertThat(service.board(ME)).isEmpty();
        verify(voteMapper, never()).listMine(any(), any(), any());
        verify(voteMapper, never()).countByDirection(any(), any(), any());
    }

    /**
     * 看板不判时间窗：活动还没开赛也要能看（前端得展示"两边 0 票"那一屏）。
     * 实现里若把 current() 写成 requireRunning()，开赛前整个活动页就只剩报错。
     */
    @Test
    void 活动开始前看板照常返回不报错() {
        running(LocalDateTime.now().plusHours(1), LocalDateTime.now().plusDays(14));

        assertThat(service.board(ME))
                .extracting(VoteBoard::symbol)
                .containsExactly(BTC, GOLD);
    }

    // ==================== 手搓行 ====================

    /** 喂给真 CampaignService 的那场 RUNNING 活动，窗口由用例指定 */
    private void running(LocalDateTime startAt, LocalDateTime endAt) {
        Campaign c = new Campaign();
        c.setId(CAMPAIGN_ID);
        c.setStartAt(startAt);
        c.setEndAt(endAt);
        c.setStatus(Campaign.STATUS_RUNNING);
        when(campaignMapper.selectActive()).thenReturn(c);
    }

    private static CampaignVote voteRow(String symbol, String direction) {
        CampaignVote v = new CampaignVote();
        v.setCampaignId(CAMPAIGN_ID);
        v.setSymbol(symbol);
        v.setDirection(direction);
        return v;
    }

    /**
     * 仿 countByDirection 的一行。key 用真实的列名小写（PG 对未加引号的标识符一律折成小写，
     * MyBatis 映射到 Map 时直接拿列标签当 key，不套驼峰规则），
     * cnt 用 Long 模仿 COUNT(*) 的 bigint —— 实现里那个 (Number) 强转就是为它准备的。
     */
    private static Map<String, Object> countRow(String direction, long cnt) {
        return Map.of("direction", direction, "cnt", cnt);
    }
}

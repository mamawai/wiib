package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignCheckin;
import com.mawai.wiibsim.campaign.mapper.CampaignCheckinMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.campaign.model.ScoreItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 签到写入与日常积分组装。不起 Spring、不连库。
 * <p>
 * 【为什么 CampaignService 用真的、只 mock 它底下的 CampaignMapper】签到的时间窗闸门就在
 * {@link CampaignService#requireRunning()} 里，mock 掉它等于把被测的那道闸一起 mock 掉，
 * "活动没开始也能签"这种 bug 会照绿。它只依赖一个 mapper，真造一个的成本约等于零。
 * 顺带这也把"时钟"变得可控：不动系统时间，改喂进去那场活动的窗口就行。
 * <p>
 * 【这里能测什么、不能测什么】"一天只能签一次"这条铁律真正的执行者是数据库的
 * uk_campaign_checkin 唯一索引，mock 的 mapper 证不了它 —— 那条由
 * {@link com.mawai.wiibsim.campaign.CampaignCheckinRealRunTest} 在真库上钉。
 * 本类管的是 Java 这一侧：闸门放不放行、插的行对不对、DuplicateKeyException 有没有被翻成人话、
 * 以及积分怎么从签到日算出来。
 * <p>
 * 【连续段的取值全在边界上】longestStreak 本身由 ScoreRulesTest 管，本类只钉
 * "scoreAll 传进去的是最长连续段而不是总天数" —— 这两个数在"连签不断"的样本里恰好相等，
 * 所以样本一律造成断段的，否则用例是假绿。
 */
class CampaignCheckinServiceTest {

    private static final long CAMPAIGN_ID = 7L;

    private static final long ME = 1L;      // 签到主角
    private static final long OTHER = 2L;   // 陪跑：证明按用户切分组、不串号
    private static final long TALKER = 3L;  // 只评论没签到

    private CampaignCheckinMapper checkinMapper;
    private CampaignStatsMapper statsMapper;
    private CampaignMapper campaignMapper;
    private CampaignCheckinService service;

    @BeforeEach
    void setUp() {
        checkinMapper = mock(CampaignCheckinMapper.class);
        statsMapper = mock(CampaignStatsMapper.class);
        campaignMapper = mock(CampaignMapper.class);
        service = new CampaignCheckinService(checkinMapper, statsMapper, new CampaignService(campaignMapper));

        // 默认给一场"此刻正开着"的活动，个别用例再按需换窗口
        running(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(13));
        when(statsMapper.listCommenters(any(), any())).thenReturn(List.of());
    }

    // ==================== 时间窗闸门 ====================

    /*
     * 【时钟这件事说清楚】requireRunning 比的是真实的 LocalDateTime.now()，用例只能挪活动窗口。
     * 所以"开始前 / 结束后 / 窗口内"这三条是完全确定的（差着小时量级），
     * 而"恰好等于 startAt / endAt 那一纳秒"在真实时钟下测不出来 —— 真要测得给
     * CampaignService 注入 Clock，为这点收益给 Task 1 的类加个构造参数不划算。
     * 下面两条边界用例把窗口边界压到"此刻"，能咬住的是"两端都不许有宽限期"
     * （比如误把 endAt 当天整天都算上）；半开区间的日界口径则由 scoreAll 那几条日期用例
     * 精确钉死 —— 那边不碰时钟，窗口和签到日全是写死的字面量。
     */

    /** 活动开始前：闸门必须挡住，且一行都不许插 */
    @Test
    void 活动开始前签到被拦下且一行都不插() {
        running(LocalDateTime.now().plusHours(1), LocalDateTime.now().plusDays(14));

        assertThatThrownBy(() -> service.checkin(ME))
                .isInstanceOf(BizException.class)
                .hasMessage("活动尚未开始");

        verify(checkinMapper, never()).insert(any(CampaignCheckin.class));
    }

    /** 活动结束后：同上，且报的是"已结束"而不是笼统一句 —— 用户看一眼就知道不用再点了 */
    @Test
    void 活动结束后签到被拦下且一行都不插() {
        running(LocalDateTime.now().minusDays(14), LocalDateTime.now().minusHours(1));

        assertThatThrownBy(() -> service.checkin(ME))
                .isInstanceOf(BizException.class)
                .hasMessage("活动已结束");

        verify(checkinMapper, never()).insert(any(CampaignCheckin.class));
    }

    /** 下界含 startAt：活动"此刻"开赛，这一刻就该能签，不用等下一个 tick */
    @Test
    void 开始那一刻就算在窗口内() {
        running(LocalDateTime.now(), LocalDateTime.now().plusDays(14));
        stubRows(row(ME, LocalDate.now()));

        assertThat(service.checkin(ME)).isEqualTo(1);
        verify(checkinMapper).insert(any(CampaignCheckin.class));
    }

    /** 上界不含 endAt：活动"此刻"收摊，这一刻起就签不了了，没有宽限期 */
    @Test
    void 结束那一刻起就签不了了() {
        running(LocalDateTime.now().minusDays(14), LocalDateTime.now());

        assertThatThrownBy(() -> service.checkin(ME))
                .isInstanceOf(BizException.class)
                .hasMessage("活动已结束");

        verify(checkinMapper, never()).insert(any(CampaignCheckin.class));
    }

    /** 压根没有 RUNNING 活动时也是一行都不插 */
    @Test
    void 没有进行中的活动时签到直接被拦下() {
        when(campaignMapper.selectActive()).thenReturn(null);

        assertThatThrownBy(() -> service.checkin(ME))
                .isInstanceOf(BizException.class)
                .hasMessage("活动未开始或已结束");

        verify(checkinMapper, never()).insert(any(CampaignCheckin.class));
    }

    // ==================== 签到写入 ====================

    /**
     * 插的行必须是"本场活动 + 我 + 今天（服务器本地日）"。
     * <p>
     * checkin_date 拿 LocalDate.now() 不是 UTC 日：连续天数数的就是这一列，
     * 换成 UTC 会让 Asia/Singapore 的 00:00-08:00 记成昨天，用户点了签到却看到连续断了。
     */
    @Test
    void 签到插入本场活动我今天的记录() {
        LocalDate today = LocalDate.now();
        stubRows(row(ME, today));

        service.checkin(ME);

        ArgumentCaptor<CampaignCheckin> captor = ArgumentCaptor.forClass(CampaignCheckin.class);
        verify(checkinMapper).insert(captor.capture());
        assertThat(captor.getValue())
                .extracting(CampaignCheckin::getCampaignId,
                        CampaignCheckin::getUserId,
                        CampaignCheckin::getCheckinDate)
                .containsExactly(CAMPAIGN_ID, ME, today);
    }

    /**
     * 返回值是"签到后"的最长连续段，前端拿它显示"已连续 X 天"。
     * <p>
     * listByCampaign 的 stub 就是插入后的库状态（mock 的 insert 不会真改数据）。
     * OTHER 那两天刻意卡在我的两天前后：myDates 若漏了按 userId 过滤，
     * 四天连起来会算出 4，而正确答案是我自己的 2。
     */
    @Test
    void 签到后返回我自己的最长连续天数() {
        LocalDate today = LocalDate.now();
        stubRows(row(OTHER, today.minusDays(2)),
                row(ME, today.minusDays(1)),
                row(ME, today),
                row(OTHER, today.plusDays(1)));

        assertThat(service.checkin(ME)).isEqualTo(2);
    }

    /**
     * 签到后返回的连续天数与 scoreAll 计分用的是同一份筛选口径 —— 窗口外的老签到不算数。
     * <p>
     * 活动窗口从"前天"开始，而库里还留着"大前天"那行（运营改过 start_at 就会出现这种行）。
     * myDates 不筛的话用户会看到"已连续 4 天"，结算时按 3 天发分，当场对不上。
     */
    @Test
    void 签到返回的连续天数不含窗口外的老签到() {
        LocalDate today = LocalDate.now();
        running(today.minusDays(2).atStartOfDay(), today.plusDays(5).atStartOfDay());
        stubRows(row(ME, today.minusDays(3)),   // 窗口外，且紧贴着窗口内那三天
                row(ME, today.minusDays(2)),
                row(ME, today.minusDays(1)),
                row(ME, today));

        assertThat(service.checkin(ME))
                .as("窗口外那天要是被算进来就是 4")
                .isEqualTo(3);
    }

    /**
     * 重复签到：DuplicateKeyException 翻成人话，且<b>不重试插入</b>。
     * <p>
     * 唯一索引抛错就是"今天已经签了"的唯一判据；这里若退回"先查后插"或捕获后重试，
     * 双击就能插进两条、多白拿一分。
     */
    @Test
    void 重复签到报今天已经签到过了且不再插第二次() {
        when(checkinMapper.insert(any(CampaignCheckin.class)))
                .thenThrow(new DuplicateKeyException("uk_campaign_checkin"));

        assertThatThrownBy(() -> service.checkin(ME))
                .isInstanceOf(BizException.class)
                .hasMessage("今天已经签到过了");

        verify(checkinMapper, times(1)).insert(any(CampaignCheckin.class));
        // 插失败就该原地抛，不该再去算连续天数（那次查询的结果没人要，白花一次全表扫）
        verify(checkinMapper, never()).listByCampaign(anyLong());
    }

    /** checkedToday 问的是"今天"这一天，日期参数不能拿别的日子凑 */
    @Test
    void checkedToday按今天的本地日去查() {
        when(checkinMapper.countByDate(CAMPAIGN_ID, ME, LocalDate.now())).thenReturn(1);

        assertThat(service.checkedToday(CAMPAIGN_ID, ME)).isTrue();
        assertThat(service.checkedToday(CAMPAIGN_ID, OTHER)).isFalse();
    }

    // ==================== 日常积分 ====================

    /*
     * 下面这些用例把活动窗口写死成 2026-08-03 ~ 2026-08-17（种子活动的真实排期），
     * 签到日也全是字面量：scoreAll 不碰时钟，所以半开区间的日界能钉得死死的。
     */

    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 3, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 17, 0, 0);
    private static final LocalDate D1 = START.toLocalDate();   // 8-3，活动首日

    /**
     * 签到分 = 去重后的天数 × 1；连续奖励按最长段发，且只在够档时才产出这一条。
     * <p>
     * ME：3 连 + 断 + 3 连 = 6 天、最长段 3 → 签到 6 分 + 连续 5 分。
     * OTHER：2 天不成段 → 只有签到 2 分，<b>没有</b> STREAK 那条。
     */
    @Test
    void 签到与连续奖励按天数和最长段分别计分() {
        fixedWindow();
        stubRows(row(ME, D1), row(ME, D1.plusDays(1)), row(ME, D1.plusDays(2)),
                row(ME, D1.plusDays(4)), row(ME, D1.plusDays(5)), row(ME, D1.plusDays(6)),
                row(OTHER, D1), row(OTHER, D1.plusDays(3)));

        Map<Long, List<ScoreItem>> result = service.scoreAll(fixedCampaign());

        assertThat(result.get(ME))
                .extracting(ScoreItem::code, ScoreItem::count, ScoreItem::score)
                .containsExactly(
                        tuple("CHECKIN", 6, BigDecimal.valueOf(6)),
                        tuple("STREAK", 3, BigDecimal.valueOf(5)));
        assertThat(result.get(OTHER))
                .extracting(ScoreItem::code, ScoreItem::count, ScoreItem::score)
                .containsExactly(tuple("CHECKIN", 2, BigDecimal.valueOf(2)));
    }

    /**
     * ★ 断段只按最长的那一段发一次 ★ 3 连 + 断 + 3 连 = 5 分，不是两个 +5 = 10。
     * <p>
     * 若按"每一段各判一次"累加，"签3天歇1天"比老老实实连签划算，时间这个压不缩的资源就被绕过了
     * （ScoreRules.streakBonus 的注释说的就是这件事）。上面那条用例已顺带覆盖，
     * 这里单独钉一次是因为它是整个签到体系唯一能被刷的口子。
     */
    @Test
    void 断段的连续奖励只按最长段发一次不累加() {
        fixedWindow();
        stubRows(row(ME, D1), row(ME, D1.plusDays(1)), row(ME, D1.plusDays(2)),
                row(ME, D1.plusDays(4)), row(ME, D1.plusDays(5)), row(ME, D1.plusDays(6)));

        ScoreItem streak = item(service.scoreAll(fixedCampaign()).get(ME), "STREAK");

        assertThat(streak.score())
                .as("两段各 3 天各发一个 +5 就是 10，这条正是要挡住那种算法")
                .isEqualByComparingTo(BigDecimal.valueOf(5));
        assertThat(streak.count()).as("count 报的是最长段，不是总天数").isEqualTo(3);
    }

    /**
     * ★ 窗口外的签到行不计分，天数和连续段<b>同时</b>把它们排除 ★
     * <p>
     * 【为什么窗口外还会有行】campaign 表刻意可运行时改，运营挪一下 start_at/end_at，
     * 当初合法签下的行就落到窗口外了 —— 只在写入口卡窗口是堵不住的。
     * <p>
     * 【样本是怎么设计的】两个越界日（8-2 开赛前、8-17 结束那天）都紧贴着窗口内的日子，
     * 于是"只筛天数不筛连续段"的实现会当场露馅：
     * <ul>
     *   <li>ME 窗口内是 8-3、8-4 与 8-15、8-16 两段各 2 天 → 最长 2 → 不够 3 天档 →
     *       <b>根本不该有 STREAK 这条</b>；漏筛的话 8-2/8-3/8-4 和 8-15/8-16/8-17
     *       都成了 3 连，凭空多出一条 +5。</li>
     *   <li>OTHER 窗口内 8-3~8-7 共 5 天 → STREAK 的 count 是 5；漏筛的话 8-2 接上去变成 6。</li>
     * </ul>
     * 8-17 这一天的取舍就是半开区间的含义：活动结束在 8-17 00:00:00，那一整天已经在窗外。
     */
    @Test
    void 窗口外的签到不进天数也不进连续段() {
        fixedWindow();
        stubRows(
                // ME：越界两头夹，窗口内只剩两段各 2 天
                row(ME, D1.minusDays(1)),                            // 8-2，开赛前一天
                row(ME, D1), row(ME, D1.plusDays(1)),                // 8-3、8-4
                row(ME, D1.plusDays(12)), row(ME, D1.plusDays(13)),  // 8-15、8-16
                row(ME, D1.plusDays(14)),                            // 8-17，endAt 那一天
                // OTHER：开赛前一天 + 窗口内连 5 天
                row(OTHER, D1.minusDays(1)),
                row(OTHER, D1), row(OTHER, D1.plusDays(1)), row(OTHER, D1.plusDays(2)),
                row(OTHER, D1.plusDays(3)), row(OTHER, D1.plusDays(4)));

        Map<Long, List<ScoreItem>> result = service.scoreAll(fixedCampaign());

        assertThat(result.get(ME))
                .as("越界的 8-2 与 8-17 既不该进天数（应为 4），也不该把连续段撑到 3 天档")
                .extracting(ScoreItem::code, ScoreItem::count, ScoreItem::score)
                .containsExactly(tuple("CHECKIN", 4, BigDecimal.valueOf(4)));
        assertThat(result.get(OTHER))
                .as("STREAK 的 count 必须是窗口内的 5，不是把 8-2 接上去的 6")
                .extracting(ScoreItem::code, ScoreItem::count, ScoreItem::score)
                .containsExactly(
                        tuple("CHECKIN", 5, BigDecimal.valueOf(5)),
                        tuple("STREAK", 5, BigDecimal.valueOf(5)));
    }

    /** 全部签到都在窗口外的人直接不进结果，而不是留一条 0 分的空明细 */
    @Test
    void 签到全在窗口外的人不进结果() {
        fixedWindow();
        stubRows(row(ME, D1), row(OTHER, D1.minusDays(1)), row(OTHER, D1.plusDays(14)));

        assertThat(service.scoreAll(fixedCampaign())).containsOnlyKeys(ME);
    }

    /** 一天没签的人不出现在结果里（没评论的话） */
    @Test
    void 一天没签也没评论的人不进结果() {
        fixedWindow();
        stubRows(row(ME, D1));

        assertThat(service.scoreAll(fixedCampaign())).containsOnlyKeys(ME);
    }

    /**
     * 首次评论只判有没有：每个 commenter 一条 count 1 / 1 分，与他评了几条无关。
     * 窗口必须是活动的 [startAt, endAt)，拿错窗口会把活动前的老评论也算进来。
     */
    @Test
    void 首次评论每人一条一分且按活动窗口取人() {
        fixedWindow();
        stubRows(row(ME, D1));
        when(statsMapper.listCommenters(START, END)).thenReturn(List.of(ME));

        Map<Long, List<ScoreItem>> result = service.scoreAll(fixedCampaign());

        assertThat(result.get(ME))
                .extracting(ScoreItem::code, ScoreItem::count, ScoreItem::score)
                .containsExactly(
                        tuple("CHECKIN", 1, BigDecimal.valueOf(1)),
                        tuple("FIRST_COMMENT", 1, BigDecimal.valueOf(1)));
        verify(statsMapper).listCommenters(START, END);
    }

    /**
     * 只评论没签到的人也得进结果 —— computeIfAbsent 那条路。
     * 写成 result.get(userId).add(...) 的话这种人会 NPE，而"只逛社区不签到"是很常见的一类用户。
     */
    @Test
    void 只评论没签到的人也拿到首次评论分() {
        fixedWindow();
        stubRows(row(ME, D1));
        when(statsMapper.listCommenters(START, END)).thenReturn(List.of(TALKER));

        Map<Long, List<ScoreItem>> result = service.scoreAll(fixedCampaign());

        assertThat(result).containsOnlyKeys(ME, TALKER);
        assertThat(result.get(TALKER))
                .extracting(ScoreItem::code, ScoreItem::count, ScoreItem::score)
                .containsExactly(tuple("FIRST_COMMENT", 1, BigDecimal.valueOf(1)));
    }

    // ==================== 手搓行 ====================

    /** 喂给真 CampaignService 的那场 RUNNING 活动，窗口由用例指定 */
    private void running(LocalDateTime startAt, LocalDateTime endAt) {
        when(campaignMapper.selectActive()).thenReturn(campaign(startAt, endAt));
    }

    /** 积分用例的固定窗口：与种子活动同排期，日界全是写死的字面量 */
    private void fixedWindow() {
        running(START, END);
    }

    private static Campaign fixedCampaign() {
        return campaign(START, END);
    }

    private static Campaign campaign(LocalDateTime startAt, LocalDateTime endAt) {
        Campaign c = new Campaign();
        c.setId(CAMPAIGN_ID);
        c.setStartAt(startAt);
        c.setEndAt(endAt);
        c.setStatus(Campaign.STATUS_RUNNING);
        return c;
    }

    private void stubRows(CampaignCheckin... rows) {
        when(checkinMapper.listByCampaign(CAMPAIGN_ID)).thenReturn(List.of(rows));
    }

    private static CampaignCheckin row(long userId, LocalDate date) {
        CampaignCheckin r = new CampaignCheckin();
        r.setCampaignId(CAMPAIGN_ID);
        r.setUserId(userId);
        r.setCheckinDate(date);
        return r;
    }

    private static ScoreItem item(List<ScoreItem> items, String code) {
        return items.stream().filter(i -> i.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("清单里没有 " + code + " 这条：" + items));
    }
}

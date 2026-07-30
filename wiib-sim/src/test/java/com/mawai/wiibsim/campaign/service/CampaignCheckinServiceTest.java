package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignCheckin;
import com.mawai.wiibsim.campaign.mapper.CampaignCheckinMapper;
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
 * 签到写入与日常积分组装。不起 Spring、不连库，mapper 全 mock。
 * <p>
 * 【这里能测什么、不能测什么】"一天只能签一次"这条铁律真正的执行者是数据库的
 * uk_campaign_checkin 唯一索引，mock 的 mapper 证不了它 —— 那条由
 * {@link com.mawai.wiibsim.campaign.CampaignCheckinRealRunTest} 在真库上钉。
 * 本类管的是 Java 这一侧：插的行对不对、DuplicateKeyException 有没有被翻成人话、
 * 以及积分怎么从签到日算出来。
 * <p>
 * 【连续段的取值全在边界上】longestStreak 本身由 ScoreRulesTest 管，本类只钉
 * "scoreAll 传进去的是最长连续段而不是总天数" —— 这两个数在"连签不断"的样本里恰好相等，
 * 所以样本一律造成断段的，否则用例是假绿。
 */
class CampaignCheckinServiceTest {

    private static final long CAMPAIGN_ID = 7L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 3, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 17, 0, 0);

    private static final long ME = 1L;      // 签到主角
    private static final long OTHER = 2L;   // 陪跑：证明按用户切分组、不串号
    private static final long TALKER = 3L;  // 只评论没签到

    private CampaignCheckinMapper checkinMapper;
    private CampaignStatsMapper statsMapper;
    private CampaignService campaignService;
    private CampaignCheckinService service;

    @BeforeEach
    void setUp() {
        checkinMapper = mock(CampaignCheckinMapper.class);
        statsMapper = mock(CampaignStatsMapper.class);
        campaignService = mock(CampaignService.class);
        service = new CampaignCheckinService(checkinMapper, statsMapper, campaignService);

        when(campaignService.requireRunning()).thenReturn(campaign());
        when(statsMapper.listCommenters(any(), any())).thenReturn(List.of());
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
        when(checkinMapper.listByCampaign(CAMPAIGN_ID)).thenReturn(List.of(row(ME, today)));

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
        when(checkinMapper.listByCampaign(CAMPAIGN_ID)).thenReturn(List.of(
                row(OTHER, today.minusDays(2)),
                row(ME, today.minusDays(1)),
                row(ME, today),
                row(OTHER, today.plusDays(1))));

        assertThat(service.checkin(ME)).isEqualTo(2);
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

    /** 没有进行中的活动时连插都不该插 —— requireRunning 是所有写操作的第一道闸 */
    @Test
    void 活动没开时签到直接被拦下() {
        when(campaignService.requireRunning()).thenThrow(new BizException("活动未开始或已结束"));

        assertThatThrownBy(() -> service.checkin(ME))
                .isInstanceOf(BizException.class)
                .hasMessage("活动未开始或已结束");

        verify(checkinMapper, never()).insert(any(CampaignCheckin.class));
    }

    /** checkedToday 问的是"今天"这一天，日期参数不能拿别的日子凑 */
    @Test
    void checkedToday按今天的本地日去查() {
        when(checkinMapper.countByDate(CAMPAIGN_ID, ME, LocalDate.now())).thenReturn(1);

        assertThat(service.checkedToday(CAMPAIGN_ID, ME)).isTrue();
        assertThat(service.checkedToday(CAMPAIGN_ID, OTHER)).isFalse();
    }

    // ==================== 日常积分 ====================

    /**
     * 签到分 = 去重后的天数 × 1；连续奖励按最长段发，且只在够档时才产出这一条。
     * <p>
     * ME：3 连 + 断 + 3 连 = 6 天、最长段 3 → 签到 6 分 + 连续 5 分。
     * OTHER：2 天不成段 → 只有签到 2 分，<b>没有</b> STREAK 那条。
     */
    @Test
    void 签到与连续奖励按天数和最长段分别计分() {
        LocalDate d = START.toLocalDate();
        when(checkinMapper.listByCampaign(CAMPAIGN_ID)).thenReturn(List.of(
                row(ME, d), row(ME, d.plusDays(1)), row(ME, d.plusDays(2)),
                row(ME, d.plusDays(4)), row(ME, d.plusDays(5)), row(ME, d.plusDays(6)),
                row(OTHER, d), row(OTHER, d.plusDays(3))));

        Map<Long, List<ScoreItem>> result = service.scoreAll(campaign());

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
        LocalDate d = START.toLocalDate();
        when(checkinMapper.listByCampaign(CAMPAIGN_ID)).thenReturn(List.of(
                row(ME, d), row(ME, d.plusDays(1)), row(ME, d.plusDays(2)),
                row(ME, d.plusDays(4)), row(ME, d.plusDays(5)), row(ME, d.plusDays(6))));

        ScoreItem streak = item(service.scoreAll(campaign()).get(ME), "STREAK");

        assertThat(streak.score())
                .as("两段各 3 天各发一个 +5 就是 10，这条正是要挡住那种算法")
                .isEqualByComparingTo(BigDecimal.valueOf(5));
        assertThat(streak.count()).as("count 报的是最长段，不是总天数").isEqualTo(3);
    }

    /** 一天没签的人不出现在结果里（没评论的话） */
    @Test
    void 一天没签也没评论的人不进结果() {
        when(checkinMapper.listByCampaign(CAMPAIGN_ID)).thenReturn(List.of(row(ME, START.toLocalDate())));

        assertThat(service.scoreAll(campaign())).containsOnlyKeys(ME);
    }

    /**
     * 首次评论只判有没有：每个 commenter 一条 count 1 / 1 分，与他评了几条无关。
     * 窗口必须是活动的 [startAt, endAt)，拿错窗口会把活动前的老评论也算进来。
     */
    @Test
    void 首次评论每人一条一分且按活动窗口取人() {
        when(checkinMapper.listByCampaign(CAMPAIGN_ID)).thenReturn(List.of(row(ME, START.toLocalDate())));
        when(statsMapper.listCommenters(START, END)).thenReturn(List.of(ME));

        Map<Long, List<ScoreItem>> result = service.scoreAll(campaign());

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
        when(checkinMapper.listByCampaign(CAMPAIGN_ID)).thenReturn(List.of(row(ME, START.toLocalDate())));
        when(statsMapper.listCommenters(START, END)).thenReturn(List.of(TALKER));

        Map<Long, List<ScoreItem>> result = service.scoreAll(campaign());

        assertThat(result).containsOnlyKeys(ME, TALKER);
        assertThat(result.get(TALKER))
                .extracting(ScoreItem::code, ScoreItem::count, ScoreItem::score)
                .containsExactly(tuple("FIRST_COMMENT", 1, BigDecimal.valueOf(1)));
    }

    // ==================== 手搓行 ====================

    private static Campaign campaign() {
        Campaign c = new Campaign();
        c.setId(CAMPAIGN_ID);
        c.setStartAt(START);
        c.setEndAt(END);
        c.setStatus(Campaign.STATUS_RUNNING);
        return c;
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

package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignCheckin;
import com.mawai.wiibsim.campaign.mapper.CampaignCheckinMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.campaign.model.ScoreItem;
import com.mawai.wiibsim.campaign.score.ScoreRules;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 签到与日常积分。
 * <p>
 * 【签到日用服务器本地日】容器 TZ 统一 Asia/Singapore，与前端展示、与"今天"的直觉一致。
 * 投票那边才用 UTC 日（规则要求 UTC 0 点前投票，见 CampaignVoteService）。
 * <p>
 * 【计分前先按活动窗口筛一道】写入侧有 requireRunning 把关，但 campaign 表的时间是可以
 * 运行时改的：运营把 start_at/end_at 一挪，当初合法签下的行就落到新窗口外面去了。
 * 只卡入口的话那些行会一直算分，所以读出来算分时还得再筛一次。
 */
@Service
@RequiredArgsConstructor
public class CampaignCheckinService {

    public static final String CODE_CHECKIN = "CHECKIN";
    public static final String CODE_STREAK = "STREAK";
    public static final String CODE_FIRST_COMMENT = "FIRST_COMMENT";

    private final CampaignCheckinMapper checkinMapper;
    private final CampaignStatsMapper statsMapper;
    private final CampaignService campaignService;

    /**
     * 签到，返回签到后的最长连续天数（前端拿它显示"已连续 X 天"）。
     * <p>
     * 去重靠唯一索引不靠先查后插：先查后插在双击/重放下会插进两条。
     */
    public int checkin(Long userId) {
        Campaign c = campaignService.requireRunning();
        LocalDate today = LocalDate.now();

        CampaignCheckin row = new CampaignCheckin();
        row.setCampaignId(c.getId());
        row.setUserId(userId);
        row.setCheckinDate(today);
        try {
            checkinMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BizException("今天已经签到过了");
        }

        return ScoreRules.longestStreak(myDates(c, userId));
    }

    public boolean checkedToday(Long campaignId, Long userId) {
        return checkinMapper.countByDate(campaignId, userId, LocalDate.now()) > 0;
    }

    /**
     * 我在本场活动窗口内的全部签到日。
     * <p>
     * 【筛法必须和 scoreAll 一模一样】这个方法算出的连续天数是签到后立刻返回给用户看的
     * （"已连续 X 天"），scoreAll 算出的是真正发分的那个。两边口径差一天，
     * 用户看到的数就和最后拿到的分对不上。
     */
    public Set<LocalDate> myDates(Campaign c, Long userId) {
        return checkinMapper.listByCampaign(c.getId()).stream()
                .filter(r -> r.getUserId().equals(userId))
                .map(CampaignCheckin::getCheckinDate)
                .filter(d -> inWindow(d, c))
                .collect(Collectors.toSet());
    }

    /** 全站日常积分：签到 + 连续奖励 + 首次评论 */
    public Map<Long, List<ScoreItem>> scoreAll(Campaign c) {
        Map<Long, List<ScoreItem>> result = new HashMap<>();

        // 【窗口过滤放在分组之前，只此一处】天数和最长连续段必须看同一份日期集合：
        // 只筛天数不筛连续段的话，会出现"算了 4 天分却按 6 天连签发奖励"，比原来不筛还难查
        Map<Long, Set<LocalDate>> byUser = checkinMapper.listByCampaign(c.getId()).stream()
                .filter(r -> inWindow(r.getCheckinDate(), c))
                .collect(Collectors.groupingBy(CampaignCheckin::getUserId,
                        Collectors.mapping(CampaignCheckin::getCheckinDate, Collectors.toSet())));

        byUser.forEach((userId, dates) -> {
            List<ScoreItem> items = new ArrayList<>(2);
            items.add(ScoreItem.of(CODE_CHECKIN, "每日签到", dates.size(),
                    dates.size() * ScoreRules.CHECKIN_DAILY));
            int streak = ScoreRules.longestStreak(dates);
            int bonus = ScoreRules.streakBonus(streak);
            if (bonus > 0) {
                items.add(ScoreItem.of(CODE_STREAK, "连续签到 3 / 7 / 14 天", streak, bonus));
            }
            result.put(userId, items);
        });

        for (Long userId : statsMapper.listCommenters(c.getStartAt(), c.getEndAt())) {
            result.computeIfAbsent(userId, k -> new ArrayList<>())
                    .add(ScoreItem.of(CODE_FIRST_COMMENT, "首次评论", 1, ScoreRules.FIRST_COMMENT));
        }

        return result;
    }

    /**
     * 这一天算不算在活动窗口 [startAt, endAt) 内。
     * <p>
     * 【上界为什么拿 atStartOfDay 去比 endAt，而不是比 endAt.toLocalDate()】
     * 签到列是 DATE、活动边界是 TIMESTAMP，两者粒度不同，得挑一个能同时管住两种情形的比法：
     * 活动结束在 2026-08-17 00:00:00 时，8-17 这一整天都在窗外，那天的签到一分不该给；
     * 而结束在 8-17 12:00 时，8-17 上午签的那次是真在窗口里的。
     * {@code date.atStartOfDay() < endAt} 一个式子把两种都算对：前者 00:00 < 00:00 不成立，
     * 后者 00:00 < 12:00 成立。写成 {@code date < endAt.toLocalDate()} 会把第二种误杀。
     * <p>
     * 下界直接比日期即可：startAt 当天的签到只可能发生在 startAt 之后（入口有 requireRunning 把关）。
     */
    private static boolean inWindow(LocalDate date, Campaign c) {
        return !date.isBefore(c.getStartAt().toLocalDate())
                && date.atStartOfDay().isBefore(c.getEndAt());
    }
}

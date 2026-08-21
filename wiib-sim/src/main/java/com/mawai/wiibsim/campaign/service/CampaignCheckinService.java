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
 * 签到日用服务器本地日（投票那边才用 UTC 日，见 CampaignVoteService）。
 * 计分前按活动窗口再筛一道：campaign 的时间可运行时改，挪过窗口后旧行不该继续算分。
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
     * 筛法必须和 scoreAll 一模一样：这里算的连续天数给用户看，scoreAll 算的真发分，口径差一天就对不上。
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

        // 窗口过滤放在分组之前，只此一处：天数和最长连续段必须看同一份日期集合
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
     * 上界用 {@code date.atStartOfDay() < endAt}：签到列是 DATE、边界是 TIMESTAMP，
     * 这个比法在"整点结束"和"半天结束"两种情形都对；写成 {@code date < endAt.toLocalDate()} 会误杀后者。
     * 下界直接比日期即可（入口有 requireRunning 把关）。
     */
    private static boolean inWindow(LocalDate date, Campaign c) {
        return !date.isBefore(c.getStartAt().toLocalDate())
                && date.atStartOfDay().isBefore(c.getEndAt());
    }
}

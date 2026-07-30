package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignCheckin;
import com.mawai.wiibsim.campaign.mapper.CampaignCheckinMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.campaign.model.ScoreItem;
import com.mawai.wiibsim.campaign.score.ScoreRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
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

        return ScoreRules.longestStreak(myDates(c.getId(), userId));
    }

    public boolean checkedToday(Long campaignId, Long userId) {
        return checkinMapper.countByDate(campaignId, userId, LocalDate.now()) > 0;
    }

    /** 我在本场活动的全部签到日 */
    public Set<LocalDate> myDates(Long campaignId, Long userId) {
        return checkinMapper.listByCampaign(campaignId).stream()
                .filter(r -> r.getUserId().equals(userId))
                .map(CampaignCheckin::getCheckinDate)
                .collect(Collectors.toSet());
    }

    /** 全站日常积分：签到 + 连续奖励 + 首次评论 */
    public Map<Long, List<ScoreItem>> scoreAll(Campaign c) {
        Map<Long, List<ScoreItem>> result = new HashMap<>();

        Map<Long, Set<LocalDate>> byUser = checkinMapper.listByCampaign(c.getId()).stream()
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
}

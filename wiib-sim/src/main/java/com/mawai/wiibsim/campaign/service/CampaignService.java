package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.mapper.CampaignMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignService {

    private final CampaignMapper campaignMapper;

    /**
     * 当前活动（进行中或结算中）；没有则 null（前端据此隐藏活动入口）。
     * <p>
     * 【读路径故意不判时间窗】活动页在开始前要能展示"还有几天开赛"、结束后要能展示榜单与领取入口，
     * 判了窗口这两段时间前端就只剩一片空白。写操作那道闸在 {@link #requireRunning()}。
     * <p>
     * 【结算与领取只能用这个，不能用 requireRunning()】两者都发生在 endAt 之后，
     * 而 requireRunning() 在 endAt 之后必抛。它们自己按 status 判该不该放行。
     */
    public Campaign current() {
        return campaignMapper.selectActive();
    }

    /**
     * 取当前活动，不在窗口内就抛——所有需要活动上下文的写操作（签到/投票/领取）都先过这道。
     * <p>
     * 【时间窗必须在这儿判，不能只看 status】status 是运营手里的开关，start_at/end_at 才是真实排期，
     * 而 campaign 表刻意做成可运行时改的（见 sql/campaign.sql 抬头），两者对不上是常态——
     * 种子行提前置成 RUNNING 就是最典型的一种。只看 status 的话活动没开始就能签到、
     * 结束了还能接着签，而交易侧那套 SQL（CampaignStatsMapper）一律带 [start, end)，
     * 一边算一边不算，积分就是错的。
     * <p>
     * 【半开区间 [startAt, endAt)】endAt 那一刻已经不算，与交易侧 SQL 的
     * {@code >= start AND < end} 严格同口径。两边差一秒就会出现
     * "最后一秒的签到算分、最后一秒的成交不算"这种对不上账的场面。
     * <p>
     * 【两种失败分开报】"还没开始"和"已经结束"用户看一眼就知道该怎么办，混成一句话还得来问。
     * <p>
     * 【时间窗之外还要判 status】{@link #current()} 现在把 SETTLING 也算 active（结算后活动页
     * 还得显示榜单与领取入口）。正常排期下 SETTLING 必然在 endAt 之后、被时间窗挡掉，
     * 但 campaign 表是可运行时改的：运营把 end_at 往后挪一下，一场已经开始发钱的活动
     * 就会重新落回窗口内，签到和投票跟着复活 —— 而奖池早已按结算那一刻的分数分完，
     * 后补的分永远兑不成钱。这一句把"已经开始发钱就不再收新分"变成结构上成立的。
     */
    public Campaign requireRunning() {
        Campaign c = current();
        if (c == null) throw new BizException("活动未开始或已结束");

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(c.getStartAt())) throw new BizException("活动尚未开始");
        if (!now.isBefore(c.getEndAt())) throw new BizException("活动已结束");
        if (!Campaign.STATUS_RUNNING.equals(c.getStatus())) throw new BizException("活动已进入结算，不能再参与");
        return c;
    }
}

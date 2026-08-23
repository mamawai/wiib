package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
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
    /** 活动状态的拦阻文案跟界面语言 */
    private final MessageCatalog messages;

    /**
     * 当前活动（进行中或结算中）；没有则 null（前端据此隐藏活动入口）。
     * <p>
     * 读路径故意不判时间窗：开赛前后活动页都要能展示；写操作那道闸在 {@link #requireRunning()}。
     * 结算与领取发生在 endAt 之后，只能用这个，自己按 status 判放行。
     */
    public Campaign current() {
        return campaignMapper.selectActive();
    }

    /**
     * 取当前活动，不在窗口内就抛——所有需要活动上下文的写操作（签到/投票/领取）都先过这道。
     * <p>
     * 时间窗必须在这儿判，不能只看 status：status 是运营开关，start_at/end_at 才是真实排期，
     * 两者对不上是常态（种子行提前置 RUNNING）。
     * 半开区间 [startAt, endAt)，与交易侧 SQL 的 {@code >= start AND < end} 严格同口径。
     * 时间窗之外还要判 status 非 SETTLING：运营挪 end_at 会让已开始发钱的活动落回窗口内，
     * 这一句保证"已经开始发钱就不再收新分"。两种失败分开报，用户一眼知道该怎么办。
     */
    public Campaign requireRunning() {
        Campaign c = current();
        if (c == null) throw new BizException(messages.get("campaign.notRunning"));

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(c.getStartAt())) throw new BizException(messages.get("campaign.notStarted"));
        if (!now.isBefore(c.getEndAt())) throw new BizException(messages.get("campaign.ended"));
        if (!Campaign.STATUS_RUNNING.equals(c.getStatus())) throw new BizException(messages.get("campaign.settling"));
        return c;
    }
}

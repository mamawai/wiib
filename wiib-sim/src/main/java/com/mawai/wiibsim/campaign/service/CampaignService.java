package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.mapper.CampaignMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignService {

    private final CampaignMapper campaignMapper;

    /** 当前进行中的活动；没有则 null（前端据此隐藏活动入口） */
    public Campaign current() {
        return campaignMapper.selectRunning();
    }

    /** 取当前活动，没有就抛——所有需要活动上下文的写操作（签到/投票/领取）都先过这道 */
    public Campaign requireRunning() {
        Campaign c = current();
        if (c == null) throw new BizException("活动未开始或已结束");
        return c;
    }
}

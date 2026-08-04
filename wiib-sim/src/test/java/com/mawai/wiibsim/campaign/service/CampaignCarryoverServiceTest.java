package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.mapper.CampaignCarryoverMapper;
import com.mawai.wiibsim.campaign.score.TradeScorer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.Mockito.*;

/**
 * 重置前固化活动次数。三条规矩：
 * ① 没活动就什么都不写（活动结束后重置账户不该炸也不该写）；
 * ② 只写<b>本人</b>的次数 —— countAll 是全站的，捞错人等于把别人的积分送给重置者；
 * ③ 一个次数都没有的用户不写空行。
 */
class CampaignCarryoverServiceTest {

    private static final long CAMPAIGN_ID = 7L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 3, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 17, 0, 0);

    private CampaignService campaignService;
    private TradeScorer tradeScorer;
    private CampaignCarryoverMapper carryoverMapper;
    private CampaignCarryoverService service;

    @BeforeEach
    void setUp() {
        campaignService = mock(CampaignService.class);
        tradeScorer = mock(TradeScorer.class);
        carryoverMapper = mock(CampaignCarryoverMapper.class);
        service = new CampaignCarryoverService(campaignService, tradeScorer, carryoverMapper);
    }

    private static Campaign campaign() {
        Campaign c = new Campaign();
        c.setId(CAMPAIGN_ID);
        c.setStartAt(START);
        c.setEndAt(END);
        return c;
    }

    @Test
    void 无活动时空操作() {
        when(campaignService.current()).thenReturn(null);

        service.carryOver(1L);

        verifyNoInteractions(tradeScorer, carryoverMapper);
    }

    /** 全站 countAll 里混着别人的次数，只有 userId=1 的那份能落库 */
    @Test
    void 只固化本人的次数() {
        when(campaignService.current()).thenReturn(campaign());
        when(tradeScorer.countAll(START, END)).thenReturn(Map.of(
                1L, Map.of("ROI40", 2, "PNL_LOSS", 1),
                2L, Map.of("ROI40", 9)));

        service.carryOver(1L);

        verify(carryoverMapper).upsertAdd(CAMPAIGN_ID, 1L, "ROI40", 2);
        verify(carryoverMapper).upsertAdd(CAMPAIGN_ID, 1L, "PNL_LOSS", 1);
        verifyNoMoreInteractions(carryoverMapper);
    }

    @Test
    void 没有任何次数的用户不写行() {
        when(campaignService.current()).thenReturn(campaign());
        when(tradeScorer.countAll(START, END)).thenReturn(Map.of(
                2L, Map.of("ROI40", 9)));

        service.carryOver(1L);

        verifyNoInteractions(carryoverMapper);
    }

    // ==================== 付费重置 ====================

    /** 活动进行中（RUNNING 且在窗口内）付费重置记一次 RESET_EXTRA */
    @Test
    void 活动进行中付费重置记一次() {
        Campaign c = new Campaign();
        c.setId(CAMPAIGN_ID);
        c.setStatus(Campaign.STATUS_RUNNING);
        c.setStartAt(java.time.LocalDateTime.now().minusDays(1));
        c.setEndAt(java.time.LocalDateTime.now().plusDays(1));
        when(campaignService.current()).thenReturn(c);

        service.chargeExtraReset(1L);

        verify(carryoverMapper).upsertAdd(CAMPAIGN_ID, 1L, "RESET_EXTRA", 1);
    }

    /**
     * 无活动/已出窗口时是空操作：破产自动恢复在非活动期也会调进来，
     * 这里绝不能抛，也不能写行。
     */
    @Test
    void 非活动期付费重置空操作() {
        when(campaignService.current()).thenReturn(null);
        service.chargeExtraReset(1L);

        // 活动还在（SETTLING 阶段 current 也返回行）但窗口已过 → 同样不扣
        Campaign ended = new Campaign();
        ended.setId(CAMPAIGN_ID);
        ended.setStatus(Campaign.STATUS_RUNNING);
        ended.setStartAt(java.time.LocalDateTime.now().minusDays(20));
        ended.setEndAt(java.time.LocalDateTime.now().minusDays(1));
        when(campaignService.current()).thenReturn(ended);
        service.chargeExtraReset(1L);

        verifyNoInteractions(carryoverMapper);
    }
}

package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.mapper.CampaignCarryoverMapper;
import com.mawai.wiibsim.campaign.score.TradeScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 重置账户前把活动交易侧积分的达成次数固化下来（"重置前遗留积分"）。
 * <p>
 * 活动积分是从业务表现算的，重置一删仓位/订单/预测注单，分就归零。
 * 这里只存<b>次数</b>不存分数：阶梯分值只与"第几次"有关，算分时同 code 相加、
 * 从头累加即可跨重置续算（见 {@link TradeScorer} 类注释）。
 * <p>
 * 必须在删表的同一事务里调（唯一调用方是 AccountPurgeTx.purge 第一行），拆开会双算。
 * 签到/投票/评论/全仓爆仓不入快照：它们的表本就跨重置留存，入表反而双算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignCarryoverService {

    private final CampaignService campaignService;
    private final TradeScorer tradeScorer;
    private final CampaignCarryoverMapper carryoverMapper;

    /** 无活动（或已 DONE 退场）时是空操作，重置流程不感知活动存不存在 */
    public void carryOver(Long userId) {
        Campaign c = campaignService.current();
        if (c == null) return;

        Map<String, Integer> counts = tradeScorer.countAll(c.getStartAt(), c.getEndAt())
                .getOrDefault(userId, Map.of());
        if (counts.isEmpty()) return;

        counts.forEach((code, cnt) -> {
            if (cnt > 0) carryoverMapper.upsertAdd(c.getId(), userId, code, cnt);
        });
        log.info("[Campaign] 重置前固化活动次数 userId={} counts={}", userId, counts);
    }

    /** 活动进行中（RUNNING 且在时间窗内）才存在"付费重置"这回事；SETTLING/结束后按平时规则走 */
    public boolean campaignRunning() {
        Campaign c = campaignService.current();
        if (c == null || !Campaign.STATUS_RUNNING.equals(c.getStatus())) return false;
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(c.getStartAt()) && now.isBefore(c.getEndAt());
    }

    /**
     * 付费重置扣分：RESET_EXTRA 记一次，分值（每次 −30）在 {@link TradeScorer#toItems} 统一换算。
     * <p>
     * 两个调用方：手动重置（AccountPurgeTx，事前已判过 campaignRunning）与破产自动恢复
     * （BankruptcyServiceImpl.resetUser，不判就调）—— 所以这里自己再兜一道：
     * 无活动或不在窗口时空操作，绝不能因为活动结束了就把破产恢复搞炸。
     */
    public void chargeExtraReset(Long userId) {
        if (!campaignRunning()) return;
        Campaign c = campaignService.current();
        carryoverMapper.upsertAdd(c.getId(), userId, TradeScorer.CODE_RESET_EXTRA, 1);
        log.info("[Campaign] 付费重置扣分 userId={} （本周非首次重置，−30）", userId);
    }
}

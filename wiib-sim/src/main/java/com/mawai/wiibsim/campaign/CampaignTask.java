package com.mawai.wiibsim.campaign;

import com.mawai.wiibsim.campaign.service.CampaignVoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 活动定时任务。方法体一律甩进虚拟线程：调度器核心池只有 1（SchedulerConfig），
 * 在里面跑网络请求会把别的任务顶住。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignTask {

    private final CampaignVoteService voteService;

    /**
     * 每日 UTC 00:05 结算前一个 UTC 交易日的投票。
     * <p>
     * 【为什么留 5 分钟】Binance 的 1d K 线在 UTC 0 点整那一刻刚收，稍等一会儿再取更稳。
     * 【为什么带 zone=UTC】容器 TZ 是 Asia/Singapore，不写 zone 会变成新加坡时间 0 点跑，
     * 那时 UTC 还是前一天下午，日线根本没收。
     * 【漏跑不要紧】settleDay 幂等且拿的是不变的历史日线，隔天补跑结果一样。
     */
    @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
    public void settleVotes() {
        Thread.startVirtualThread(() -> {
            try {
                LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
                voteService.settleDay(yesterday);
            } catch (Exception e) {
                log.error("活动投票结算失败", e);
            }
        });
    }
}

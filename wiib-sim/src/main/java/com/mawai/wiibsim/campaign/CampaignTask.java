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

    /**
     * 每轮回扫的天数。回扫覆盖 [今天−SWEEP_DAYS, 今天−1]。
     * <p>
     * 14 天活动实际有 15 个投票日（UTC 日戳 vs 本地时间窗口，推导见 CampaignVoteService.poolOf），
     * 17 = 15 + 2 天余量，让活动结束后连着三晚任意一晚跑通都能一轮补齐全部日子。
     * 改活动天数这个数要跟着改。空查便宜：settleDay 没待结的票直接返回，连行情都不取。
     */
    private static final int SWEEP_DAYS = 17;

    private final CampaignVoteService voteService;

    /**
     * 每日 UTC 00:05 回扫最近 {@value #SWEEP_DAYS} 个已过完的 UTC 交易日，补上所有没结的票。
     * <p>
     * 05 分：等 UTC 0 点刚收的 1d K 线稳定。zone=UTC：容器 TZ 是 Asia/Singapore，不写会在本地 0 点跑。
     * 从最老一天往新扫：poolOf 按日期顺序结算才与设计一致，乱序会略超发。
     * 漏跑不要紧：settleDay 幂等（CAS）且日线不变，隔天补跑结果一样。
     */
    @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
    public void settleVotes() {
        Thread.startVirtualThread(() -> {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            for (int back = SWEEP_DAYS; back >= 1; back--) {
                LocalDate day = today.minusDays(back);
                try {
                    voteService.settleDay(day);
                } catch (Exception e) {
                    // 捕获放在单日而不是整个回扫外面：从老到新扫，某天一直炸的话
                    // 放外面会把它后面所有日子永远挡住，而那些日子本来是能结的
                    log.error("活动投票结算失败 {}", day, e);
                }
            }
        });
    }
}

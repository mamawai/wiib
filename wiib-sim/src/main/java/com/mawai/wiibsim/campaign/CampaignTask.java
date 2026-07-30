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
     * 每轮回扫的天数。
     * <p>
     * 【为什么是 15 不是 1】活动 14 天，但投票日盖的是 UTC 日戳、活动窗口按服务器本地时间开合，
     * 于是能投票的 UTC 日有 15 个（首日本地 0 点 = UTC 前一天 16 点，见 CampaignVoteService.poolOf）。
     * 从昨天往回扫满 15 天，正好覆盖整场 —— 哪怕整场的定时任务全没跑成，
     * 最后一晚跑通一次也能把所有日子补齐，不用人肉介入。
     * <p>
     * 【为什么这么扫不贵】settleDay 头一件事就是查该日有没有待结算的票，没有就直接返回，
     * 连行情都不取。正常那一晚是 14 次空查 + 1 次真结算。
     */
    private static final int SWEEP_DAYS = 15;

    private final CampaignVoteService voteService;

    /**
     * 每日 UTC 00:05 回扫最近 {@value #SWEEP_DAYS} 个已过完的 UTC 交易日，补上所有没结的票。
     * <p>
     * 【为什么留 5 分钟】Binance 的 1d K 线在 UTC 0 点整那一刻刚收，稍等一会儿再取更稳。
     * 【为什么带 zone=UTC】容器 TZ 是 Asia/Singapore，不写 zone 会变成新加坡时间 0 点跑，
     * 那时 UTC 还是前一天下午，日线根本没收。
     * 【为什么从最老的一天开始】poolOf 减的是"截至这天已发出的分"，按日期顺序结算时
     * 每天拿到的池才与设计一致；乱序虽然也不会让人发 0 分了，但总额会略微超发。
     * 从老到新扫，漏掉的那天下一轮自然排在后面几天前头，顺序自己就回来了。
     * 【漏跑不要紧】settleDay 幂等（CAS）且拿的是不变的历史日线，隔天补跑结果一样。
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

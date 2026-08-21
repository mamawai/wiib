package com.mawai.wiibsim.campaign;

import com.mawai.wiibsim.campaign.service.CampaignVoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 投票结算的定时回扫（回扫不只结昨天：漏跑的那天下一轮自动补上，空查近乎免费）。
 * 任务体在虚拟线程里跑，断言一律走 Mockito 的 {@code timeout(...)} 验证模式，不睡固定时长。
 */
class CampaignTaskTest {

    /**
     * 与 CampaignTask.SWEEP_DAYS 对齐：要盖住的投票日有 15 个
     * （票投的是明天，活动 14 天，种子活动是 08-03 ~ 08-17），
     * 而 17 是给"一轮补齐整场"留的余量 —— 08-18/19/20 三晚里哪晚跑通都够，不必卡死在 08-18 那一晚。
     */
    private static final int SWEEP_DAYS = 17;

    private CampaignVoteService voteService;
    private CampaignTask task;

    @BeforeEach
    void setUp() {
        voteService = mock(CampaignVoteService.class);
        task = new CampaignTask(voteService);
    }

    /**
     * 一次回扫覆盖昨天往回数的 {@value #SWEEP_DAYS} 个 UTC 日，<b>从最老的一天开始</b>
     * （poolOf 要按日期顺序结算），且绝不碰今天（半根还在长的日线）。
     */
    @Test
    void 每轮从最老的一天回扫满窗口且不碰今天() {
        task.settleVotes();

        ArgumentCaptor<LocalDate> days = ArgumentCaptor.forClass(LocalDate.class);
        verify(voteService, timeout(2_000).times(SWEEP_DAYS)).settleDay(days.capture());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> expected = new ArrayList<>(SWEEP_DAYS);
        for (int back = SWEEP_DAYS; back >= 1; back--) expected.add(today.minusDays(back));

        assertThat(days.getAllValues())
                .as("从 今天-" + SWEEP_DAYS + " 一路扫到昨天，一天不多一天不少，且严格由老到新")
                .containsExactlyElementsOf(expected);
        assertThat(days.getAllValues()).doesNotContain(today, today.plusDays(1));
    }

    /**
     * 中间某天一直炸（比如库里被手工塞了个 SYMBOLS 之外的 symbol，那处是有意留响的），
     * 不许把它<b>后面</b>那些本来结得了的日子一起拖死。
     * <p>
     * catch 必须在循环内：放在循环外的话坏日子每轮都在同一处中断，它后面的日子再也没机会结。
     */
    @Test
    void 中间某天出错不挡住后面的日子() {
        LocalDate bad = LocalDate.now(ZoneOffset.UTC).minusDays(10);
        doThrow(new RuntimeException("手工塞库塞出来的脏 symbol")).when(voteService).settleDay(bad);

        task.settleVotes();

        verify(voteService, timeout(2_000).times(SWEEP_DAYS)).settleDay(any());
    }

    /** 定时任务只负责派活，自己不碰活动上下文 —— 谁该结、结不结得动全在 settleDay 里判 */
    @Test
    void 任务本身不判活动状态() {
        task.settleVotes();

        verify(voteService, timeout(2_000).times(SWEEP_DAYS)).settleDay(any());
        verify(voteService, never()).voteScoreByUser(any());
        verify(voteService, never()).board(any());
    }
}

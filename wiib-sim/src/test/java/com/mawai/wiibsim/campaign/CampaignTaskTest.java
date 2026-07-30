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
 * 投票结算的定时回扫。
 * <p>
 * 【为什么回扫而不是只结昨天】只结昨天的话，00:05 那一次没跑成（宿主重启 / 发版窗口）
 * 那天就永远不会再被结，得人肉补。回扫是近乎免费的：settleDay 头一件事就是查该日
 * 有没有待结算的票，没有就直接返回，连行情都不取。
 * <p>
 * 【任务体在虚拟线程里跑】所以断言一律走 Mockito 的 {@code timeout(...)} 验证模式等它落地，
 * 不睡固定时长。
 */
class CampaignTaskTest {

    /** 与 CampaignTask.SWEEP_DAYS 对齐：活动 14 天，UTC 口径 15 个投票日 */
    private static final int SWEEP_DAYS = 15;

    private CampaignVoteService voteService;
    private CampaignTask task;

    @BeforeEach
    void setUp() {
        voteService = mock(CampaignVoteService.class);
        task = new CampaignTask(voteService);
    }

    /**
     * 一次回扫覆盖昨天往回数的 15 个 UTC 日，<b>从最老的一天开始</b>，且绝不碰今天。
     * <p>
     * 【顺序为什么要紧】poolOf 减的是"截至这天已发出的分"，按日期顺序结算时每天拿到的池
     * 才与设计一致；从老到新扫，前几天漏掉的那些自然排在后面几天前头，顺序自己就回来了。
     * <p>
     * 【为什么必须止步于昨天】今天那天还没过完，日线是半根还在长的蜡烛，票也没截止 ——
     * settleDay 自己也有一道闸，但任务这边压根就不该把今天递进去。
     */
    @Test
    void 每轮从最老的一天回扫十五天且不碰今天() {
        task.settleVotes();

        ArgumentCaptor<LocalDate> days = ArgumentCaptor.forClass(LocalDate.class);
        verify(voteService, timeout(2_000).times(SWEEP_DAYS)).settleDay(days.capture());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LocalDate> expected = new ArrayList<>(SWEEP_DAYS);
        for (int back = SWEEP_DAYS; back >= 1; back--) expected.add(today.minusDays(back));

        assertThat(days.getAllValues())
                .as("从 今天-15 一路扫到昨天，一天不多一天不少，且严格由老到新")
                .containsExactlyElementsOf(expected);
        assertThat(days.getAllValues()).doesNotContain(today, today.plusDays(1));
    }

    /**
     * 中间某天一直炸（比如库里被手工塞了个 SYMBOLS 之外的 symbol，那处是有意留响的），
     * 不许把它<b>后面</b>那些本来结得了的日子一起拖死。
     * <p>
     * 【为什么这条值得单写】回扫是从老到新的，catch 要是放在整个循环外面，
     * 那个坏日子每轮都排在最前头、每轮都在同一处中断 —— 它后面的日子就再也没机会结了。
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

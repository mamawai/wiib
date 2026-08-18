package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.agent.trader.TraderChatService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挂在 summarizer 上的三个动作工具。归属与业务规则归 {@code TraderChatServiceTest}，
 * 这里只管本层的一件事：userId 有没有烤死在叶子上。
 */
class TraderActionToolkitTest {

    private final TraderChatService service = mock(TraderChatService.class);
    private final TraderActionToolkit toolkit = new TraderActionToolkit(service, 42L);

    @Test
    void 动作都带着烤死的userId() {
        toolkit.wakeTrader();
        toolkit.leaveNoteToTrader("仓位轻点");

        verify(service).wake(42L);
        verify(service).leaveNote(42L, "仓位轻点");
    }

    /**
     * 复盘改异步后这层只做透传：service 当场返回准入结果，工具不再等、不再推进度。
     * 原来那两条进度断言随同步执行一起废掉了——没有干等窗口就没有进度可推。
     */
    @Test
    void 复盘结果原样透传() {
        when(service.reviewNow(42L)).thenReturn("{\"ok\":true}");

        assertThat(toolkit.reviewTraderNow()).contains("ok");

        verify(service).reviewNow(42L);
    }
}

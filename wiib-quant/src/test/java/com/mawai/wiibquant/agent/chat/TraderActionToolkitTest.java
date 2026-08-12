package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.agent.trader.TraderChatService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挂在 summarizer 上的三个动作工具。归属与业务规则归 {@code TraderChatServiceTest}，
 * 这里管两件本层的事：userId 烤死没有、复盘这种长工具推没推进度。
 */
class TraderActionToolkitTest {

    private static final String SESSION = "wb-1-abc";

    private final TraderChatService service = mock(TraderChatService.class);
    private final WorkbenchRunRegistry runRegistry = mock(WorkbenchRunRegistry.class);
    private final TraderActionToolkit toolkit = new TraderActionToolkit(service, runRegistry, 42L);

    @Test
    void 动作都带着烤死的userId() {
        toolkit.wakeTrader();
        toolkit.leaveNoteToTrader("仓位轻点");

        verify(service).wake(42L);
        verify(service).leaveNote(42L, "仓位轻点");
    }

    /**
     * 复盘同步跑最长 180s，期间 SSE 通道一个字节都没有。不推进度前端就是纯干等，
     * 用户只会以为卡死了。会话号从 {@link ToolRunContext} 取——工具方法体拿不到 RunnableConfig。
     */
    @Test
    void 复盘期间推进度() {
        when(service.reviewNow(42L)).thenReturn("{\"ok\":true}");
        ToolRunContext.set(SESSION);
        try {
            assertThat(toolkit.reviewTraderNow()).contains("ok");
        } finally {
            ToolRunContext.clear();
        }

        verify(runRegistry).publishProgress(SESSION, "正在复盘 trader 的近期交易（约需 1~3 分钟）");
        verify(runRegistry).publishProgress(SESSION, "复盘结束，正在生成回答");
    }

    /** 不在工具执行栈里（拿不到会话号）时进度静默跳过，不能因此把复盘本身搞挂 */
    @Test
    void 没有会话号时照常复盘不推进度() {
        when(service.reviewNow(42L)).thenReturn("{\"ok\":true}");

        assertThat(toolkit.reviewTraderNow()).contains("ok");

        verify(runRegistry, never()).publishProgress(anyString(), anyString());
    }
}

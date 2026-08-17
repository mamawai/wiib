package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.agent.llm.LlmEndpointService;
import com.mawai.wiibquant.agent.llm.SseChannel;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 断连之后这一轮怎么收场。
 * <p>
 * 语义是"<b>照跑、照攒、照落库，只是不再发帧</b>"：用户切页/刷新只断了 SSE，
 * 后台这轮还在烧他的 token，答案必须进历史——前端回来靠 /status + 历史回放补。
 * 把攒答案那行挪进 {@code if (!channel.isClosed())} 里，代码照跑什么都不报错，
 * 只是断连过的那一轮在历史里变成一条空回答，而且只有真用户切页才复现得出来。
 */
class ChatWorkbenchStreamTest {

    private static final String SESSION = "wb-1-stream";

    /** 记账用的 emitter：断连后 SseChannel 一帧都不该往这儿写 */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<String> raw = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            builder.build().forEach(d -> {
                if (d.getData() instanceof String s) raw.add(s);
            });
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void 断连后答案照样攒起来落进历史但不再发帧() {
        ChatHistoryService historyService = mock(ChatHistoryService.class);
        ChatTurnRunner turnRunner = mock(ChatTurnRunner.class);
        // runner 分两帧把答案交出来，controller 的 sink 得把它们攒全
        doAnswer((Answer<ChatTurnRunner.TurnResult>) inv -> {
            Consumer<String> sink = inv.getArgument(4);
            sink.accept("前半段");
            sink.accept("后半段");
            return ChatTurnRunner.TurnResult.COMPLETED;
        }).when(turnRunner).run(any(), anyLong(), any(), any(), any(), any(), any());
        ChatConcurrencyGate gate = new ChatConcurrencyGate(10);
        WorkbenchRunRegistry runRegistry = mock(WorkbenchRunRegistry.class);
        ChatYieldCoordinator coordinator =
                new ChatYieldCoordinator(gate, runRegistry, turnRunner, historyService);
        ChatWorkbenchController controller = new ChatWorkbenchController(mock(ChatAgentFactory.class),
                mock(LlmEndpointService.class), new ApprovalRegistry(),
                historyService, mock(ChatContextStore.class), turnRunner,
                runRegistry, gate, coordinator);

        RecordingEmitter emitter = new RecordingEmitter();
        SseChannel channel = new SseChannel(emitter);
        channel.markClosed();   // 用户切页：连接已经断了，这一轮才刚开始

        controller.run(channel, 1L, SESSION, "看看行情", null, coordinator.openTurn(1L));

        // 答案完整进历史——这是断连用户唯一还拿得到东西的途径
        verify(historyService).append(eq(SESSION), eq(1L), eq("assistant"), eq("前半段后半段"));
        // 但一帧都没往断掉的通道里写
        assertThat(emitter.raw).isEmpty();
    }
}

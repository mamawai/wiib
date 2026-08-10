package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.entity.UserLlmConfig;
import com.mawai.wiibcommon.exception.BizException;
import jakarta.servlet.http.HttpServletResponse;
import org.assertj.core.api.ThrowableAssert;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 门开了之后 {@code /chat} 对所有登录用户可用，入口靠三道准入把关：没配置 / 建不出图 / 没名额。
 * <p>
 * 三道都得在把 emitter 交给 MVC 之前拦住——交出去响应就成了 event-stream，错误只能推 error 事件，
 * 前端拿不到 code，也就没法把"去配置端点"和"稍后再试"区别对待。所以这里断的是抛出的业务错误码。
 * <p>
 * 错误码写死成 2201-2204 的字面量：它是前端契约，而且 Java 枚举<b>不校验 code 重复</b>，
 * 谁哪天顺手写成 1600 段（Crypto 占着）编译测试全过，只在运行时把"无法获取实时价格"
 * 弹成"去配置 LLM 端点"。
 */
class ChatWorkbenchAdmissionTest {

    private final ChatAgentFactory factory = mock(ChatAgentFactory.class);
    private final UserLlmConfigService llmConfigService = mock(UserLlmConfigService.class);

    private ChatWorkbenchController controller(ChatConcurrencyGate gate) {
        ChatMemoryService memory = mock(ChatMemoryService.class);
        when(memory.recall(anyLong())).thenReturn(""); // 空前缀：记忆拼接不是这里要验的
        return new ChatWorkbenchController(factory, llmConfigService, new ApprovalRegistry(), memory,
                mock(ChatHistoryService.class), mock(WorkbenchCheckpointStore.class),
                mock(WorkbenchRunRegistry.class), gate);
    }

    private static void chat(ChatWorkbenchController controller, long userId) {
        ChatWorkbenchController.WorkbenchChatRequest request =
                new ChatWorkbenchController.WorkbenchChatRequest();
        request.setMessage("深度研判 BTC");
        controller.chat(userId, request, mock(HttpServletResponse.class));
    }

    private static void assertRejectedWithCode(int code, ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", code);
    }

    @Test
    void 没配置端点时拒绝并给出配置缺失码() {
        when(llmConfigService.get(1L)).thenReturn(null);

        assertRejectedWithCode(2201, () -> chat(controller(new ChatConcurrencyGate(10)), 1L));
    }

    /** 配置能过保存校验但仍可能建不出模型（协议对不上等），这类错误必须在建流前暴露 */
    @Test
    void 建不出图时拒绝并给出配置无效码() {
        when(llmConfigService.get(1L)).thenReturn(new UserLlmConfig());
        when(factory.chatGraph(any())).thenThrow(new IllegalStateException("对话图构建失败"));

        assertRejectedWithCode(2202, () -> chat(controller(new ChatConcurrencyGate(10)), 1L));
    }

    /** 拒因要分得清：这条给的是"你已有一轮在跑"，不是下面那条"人满了" */
    @Test
    void 本人已有一轮在跑时拒绝() {
        when(llmConfigService.get(1L)).thenReturn(new UserLlmConfig());
        ChatConcurrencyGate gate = new ChatConcurrencyGate(10);
        gate.tryAcquire(1L); // 这个用户自己的上一轮还占着名额

        assertRejectedWithCode(2203, () -> chat(controller(gate), 1L));
    }

    @Test
    void 全局名额满时拒绝() {
        when(llmConfigService.get(1L)).thenReturn(new UserLlmConfig());
        ChatConcurrencyGate gate = new ChatConcurrencyGate(1);
        gate.tryAcquire(2L); // 唯一的名额被别人占着

        assertRejectedWithCode(2204, () -> chat(controller(gate), 1L));
    }

    /**
     * 名额漏了是全套设计里唯一不可恢复的失败模式：漏满就对所有人永久拒绝。
     * 这条钉的是那个唯一的泄漏窗口——名额已经拿到、任务却没提交出去。
     */
    @Test
    void 任务提交失败时当场还回名额() {
        when(llmConfigService.get(1L)).thenReturn(new UserLlmConfig());
        when(factory.chatGraph(any())).thenReturn(null); // 跑不到用它的那一步
        ChatConcurrencyGate gate = new ChatConcurrencyGate(1);
        ChatWorkbenchController controller = controller(gate);
        controller.streamExecutor.shutdown(); // 之后 submit 必被拒

        assertThatThrownBy(() -> chat(controller, 1L)).isInstanceOf(RejectedExecutionException.class);

        assertThat(gate.tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }

    /** 一轮正常跑完也要还，否则同一个人第二句话就再也发不出去了 */
    @Test
    void 一轮跑完把名额还回去() throws Exception {
        // 图要在 when(...) 之前建好：它自己也是个要打桩的 mock，塞进 thenReturn 的实参位置
        // 就成了"上一条 stubbing 还没收尾又开一条"，Mockito 直接 UnfinishedStubbingException
        CompiledGraph<MessagesState<Message>> graph = graphThatEmitsNothing();
        when(llmConfigService.get(1L)).thenReturn(new UserLlmConfig());
        when(factory.chatGraph(any())).thenReturn(graph);
        CountDownLatch released = new CountDownLatch(1);
        ChatConcurrencyGate gate = new ChatConcurrencyGate(1) {
            @Override
            public void release(long userId) {
                super.release(userId);
                released.countDown();
            }
        };

        chat(controller(gate), 1L);

        assertThat(released.await(30, TimeUnit.SECONDS)).as("名额被还回来").isTrue();
        assertThat(gate.tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }

    /** 一帧不吐就跑完的图：本条只关心 run() 收尾时名额有没有还，图里发生什么无所谓 */
    @SuppressWarnings("unchecked")
    private static CompiledGraph<MessagesState<Message>> graphThatEmitsNothing() {
        AsyncGenerator.Cancellable<NodeOutput<MessagesState<Message>>> nothing =
                new AsyncGenerator.Cancellable<NodeOutput<MessagesState<Message>>>() {
                    @Override
                    public AsyncGenerator.Data<NodeOutput<MessagesState<Message>>> next() {
                        return AsyncGenerator.Data.done();
                    }

                    @Override
                    public Executor executor() {
                        return Runnable::run;
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }

                    @Override
                    public boolean cancel(boolean mayInterruptIfRunning) {
                        return false;
                    }
                };
        CompiledGraph<MessagesState<Message>> graph = mock(CompiledGraph.class);
        when(graph.stream(any(Map.class), any(RunnableConfig.class))).thenReturn(nothing);
        return graph;
    }
}

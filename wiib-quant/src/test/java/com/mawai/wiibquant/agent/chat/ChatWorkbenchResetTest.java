package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.config.AiAgentRuntime;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibquant.agent.llm.ModelCallLimiter;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import jakarta.servlet.http.HttpServletResponse;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 每轮提问必须把"单轮作用域"的 state 键清零。它们存在 state 里，而父图带 checkpointSaver、
 * 续聊按同一 threadId 从上次 checkpoint 起算——不清就会一路累加到永远达上限。
 * <p>
 * 这里盯的是 {@link ModelCallLimiter#CALL_COUNT_KEY}。它累加的后果比另外两个键更隐蔽也更狠：
 * 保险丝挂在<b>工具边</b>上，到顶后是在 {@code action.apply} 之前短路，
 * 于是 summarizer 再也调不动 {@code run_deep_analysis}——而深研判 HITL 弹卡后前端补发的
 * "已确认，请继续"正是<b>新的一轮</b>，长会话里用户点了确认什么都不会发生，且没有任何日志说明原因。
 * <p>
 * <b>必须走 {@link ChatWorkbenchController} 的真入口</b>：这个重置是写在 controller 的输入 map 里的，
 * 测试自己拼一份同样的 map 只能证明"map 里有这个键就好使"，而 controller 里漏掉照样绿。
 */
class ChatWorkbenchResetTest {

    /** 生产口径的调用上限（application.yml 的 quant.workbench.run-model-call-limit） */
    private static final int PRODUCTION_LIMIT = 8;
    /** 阈值给足 = 这一跑不碰历史压缩，别让它掺进计数里 */
    private static final int NO_COMPRESSION = 999_999;

    /**
     * 连聊几轮。每轮 summarizer 走"调一次工具 + 再答一句" = 计数 +2，
     * 不清零的话第 4 轮末摸到上限 8、第 5 轮开头就短路。取 6 轮是为了跨过那道坎——
     * 计数那条断言在第 2 轮就会先炸（4≠2），但只有真跑到第 5、6 轮，
     * 下面"工具有没有被跳过"那条才验得到用户实际遭的罪。
     */
    private static final int TURNS = 6;

    /** 每轮 summarizer 的模型调用次数：第 1 次要工具、第 2 次给答案。工具真被执行才会有第 2 次 */
    private static final int DEEP_CALLS_PER_TURN = 2;

    private final AtomicInteger deepCallsThisTurn = new AtomicInteger();

    private static ChatResponse responseOf(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static AssistantMessage toolCall(String id, String name, String args) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args))).build();
    }

    /** 生产装配的对话图（真 {@link ChatAgentFactory#chatGraph()} + MemorySaver，续聊语义与线上一致） */
    private CompiledGraph<MessagesState<Message>> productionGraph(ApprovalRegistry approvalRegistry)
            throws Exception {
        ChatModel deep = mock(ChatModel.class);
        ChatModel light = mock(ChatModel.class);
        // ChatService 建请求时无条件读 getOptions() 挂工具，null 会 NPE
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(light.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AiAgentRuntimeManager runtimeManager = mock(AiAgentRuntimeManager.class);
        // 位序是 (behavior, quant, quantLight, chat)：深模型进 quant，浅模型进 quantLight
        when(runtimeManager.current()).thenReturn(new AiAgentRuntime(light, deep, light, deep));

        // router 恒答 FINISH：专家轮与本条无关，别让它掺进计数
        when(light.call(any(Prompt.class))).thenAnswer(inv ->
                responseOf(toolCall("r", "route", "{\"next\":[\"FINISH\"]}")));
        // 每轮：先要一次工具（工具真跑了才会有下面这次），再给答案。
        // 每条消息都带轮次编号是必须的，不是为了好看：MessagesState.SCHEMA 的 reducer 是
        // ReducerDisallowDuplicate，按 Objects.hash 去重——连着几轮回同一句话，第二轮那条会被
        // 静默丢掉，工具节点读到的最后一条就不是 AssistantMessage，当场 no AssistantMessage provided
        AtomicInteger seq = new AtomicInteger();
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> Flux.just(
                deepCallsThisTurn.incrementAndGet() == 1
                        ? toolCall("c" + seq.incrementAndGet(), "run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}")
                        : new AssistantMessage("这是第 " + seq.get() + " 轮的答案"))
                .map(ChatWorkbenchResetTest::responseOf));

        // 工厂内部自己 new 出真 toolkit：没授权时闸门直接短路回 PENDING_APPROVAL，一次深模型都不烧
        return new ChatAgentFactory(runtimeManager, mock(MarketToolkit.class), mock(NewsToolkit.class),
                mock(DeepAnalysisService.class), mock(WorkbenchRunRegistry.class),
                approvalRegistry, new MemorySaver(),
                new SpringAIJacksonStateSerializer<>(MessagesState::new),
                PRODUCTION_LIMIT, NO_COMPRESSION, 6, "X").chatGraph();
    }

    @Test
    void 同一会话连聊多轮时模型调用数每轮归零() throws Exception {
        ApprovalRegistry approvalRegistry = new ApprovalRegistry();
        CompiledGraph<MessagesState<Message>> graph = productionGraph(approvalRegistry);

        ChatAgentFactory factory = mock(ChatAgentFactory.class);
        when(factory.chatGraph()).thenReturn(graph);
        ChatMemoryService memory = mock(ChatMemoryService.class);
        when(memory.recall(anyLong())).thenReturn(""); // 空前缀：记忆拼接不是本条要验的
        WorkbenchRunRegistry runRegistry = mock(WorkbenchRunRegistry.class);
        // run() 是丢给虚拟线程跑的，靠 finally 里的 finish() 当完成信号
        CountDownLatch[] turnDone = new CountDownLatch[]{new CountDownLatch(1)};
        doAnswer(inv -> {
            turnDone[0].countDown();
            return null;
        }).when(runRegistry).finish(anyString());

        ChatWorkbenchController controller = new ChatWorkbenchController(factory, approvalRegistry,
                memory, mock(ChatHistoryService.class), mock(WorkbenchCheckpointStore.class), runRegistry);

        String sessionId = "wb-1-reset-probe";
        for (int turn = 1; turn <= TURNS; turn++) {
            deepCallsThisTurn.set(0);
            turnDone[0] = new CountDownLatch(1);
            ChatWorkbenchController.WorkbenchChatRequest request =
                    new ChatWorkbenchController.WorkbenchChatRequest();
            request.setSessionId(sessionId);
            request.setMessage("第 " + turn + " 轮：深度研判 BTC");

            controller.chat(1L, request, mock(HttpServletResponse.class));
            assertThat(turnDone[0].await(60, TimeUnit.SECONDS)).as("第 %d 轮跑完", turn).isTrue();

            // 计数是本轮自己的，不是从上一轮续上来的——不清零的话这里会是 2、4、6、8…
            assertThat(callCount(graph, sessionId)).as("第 %d 轮跑完后的 %s", turn, ModelCallLimiter.CALL_COUNT_KEY)
                    .isEqualTo(DEEP_CALLS_PER_TURN);
            // 第 2 次模型调用只有"工具真被执行了"才会发生：保险丝短路是掐在 action.apply 之前的
            assertThat(deepCallsThisTurn.get()).as("第 %d 轮 summarizer 的模型调用次数", turn)
                    .isEqualTo(DEEP_CALLS_PER_TURN);
        }
    }

    /** 从 checkpoint 里读本轮跑完的调用计数——续聊起算的就是这份 state */
    private static int callCount(CompiledGraph<MessagesState<Message>> graph, String sessionId) {
        return graph.lastStateOf(RunnableConfig.builder().threadId(sessionId).build())
                .orElseThrow()
                .state()
                .<Number>value(ModelCallLimiter.CALL_COUNT_KEY)
                .map(Number::intValue)
                .orElse(0);
    }
}

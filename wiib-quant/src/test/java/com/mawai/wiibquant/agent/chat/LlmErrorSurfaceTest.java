package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.agent.llm.SseChannel;
import com.mawai.wiibquant.agent.llm.ChatEndpoints;
import com.mawai.wiibquant.agent.llm.LlmEndpointService;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import com.mawai.wiibquant.agent.trader.TraderChatService;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上游异常的文案有三个出口，每一个都得先过 {@code LlmErrorMessages}——
 * 原文可能几百字符，还带着 key 和完整请求 URL：
 * <ol>
 *   <li>专家失败时推给用户的 progress 事件</li>
 *   <li>专家失败时拼成 AssistantMessage 喂回模型、随会话上下文落库的那条</li>
 *   <li>整轮失败时推给前端的 error 事件</li>
 * </ol>
 * 前两个在同一个 catch 里，是两行代码，漏改一行都不行。
 */
class LlmErrorSurfaceTest {

    /** 上游原样抛出来的东西：认得出是 401，同时带着一段绝不能外流的 key */
    private static final String RAW =
            "HTTP 401 Unauthorized: key sk-secret-abcdef123456 rejected by gw.example.com";

    private static ChatResponse responseOf(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    @Test
    void 专家失败时两个出口都不回显上游原文() {
        ChatModel deep = mock(ChatModel.class);
        ChatModel light = mock(ChatModel.class);
        // ChatService 建请求时无条件读 getOptions() 挂工具，null 会 NPE
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(light.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, light));

        // 浅模型同时服务路由和专家，只能靠系统提示词首句分辨是哪一种请求。
        // 路由每次都点名 market_agent：第二次会被去重转 FINISH，循环自然收口
        when(light.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            if (!prompt.getInstructions().getFirst().getText().contains("你是研判工作台的调度器")) {
                throw new RuntimeException(RAW);
            }
            return responseOf(AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("r", "function", "route",
                            "{\"next\":[\"market_agent\"]}"))).build());
        });
        when(deep.stream(any(Prompt.class)))
                .thenReturn(Flux.just(responseOf(new AssistantMessage("汇总一下"))));

        ChatEndpoints llmConfig = ChatTestEndpoints.eps(1L, "gpt-5");   // 叶子指纹含 userId（trader 工具按它认人）
        ChatAgentFactory.Leaves leaves = new ChatAgentFactory(chatModelFactory,
                mock(MarketToolkit.class), mock(NewsToolkit.class),
                mock(DeepAnalysisService.class), mock(TraderChatService.class),
                mock(WorkbenchRunRegistry.class),
                new ApprovalRegistry(),
                new SpringAIJacksonStateSerializer<>(MessagesState::new), 8, 999_999, 6, "X")
                .leavesFor(llmConfig);

        ChatContextStore contextStore = mock(ChatContextStore.class);
        List<ChatTurnRunner.ExpertProgress> progress = new CopyOnWriteArrayList<>();
        new ChatTurnRunner(contextStore, new ApprovalRegistry())
                .run(leaves, 1L, "wb-1-expert-fail", "看看行情", chunk -> { }, progress::add,
                        ChatTurnRunner.TurnYield.NONE);

        String pushedToUser = progress.stream()
                .filter(e -> ChatTurnRunner.ExpertProgress.ERROR.equals(e.phase()))
                .map(ChatTurnRunner.ExpertProgress::text)
                .findFirst().orElseThrow();
        // 喂回模型的那条会随会话上下文落库，从落库口截下来看
        ArgumentCaptor<List<Message>> saved = ArgumentCaptor.captor();
        verify(contextStore).save(eq("wb-1-expert-fail"), anyLong(), saved.capture());
        String fedBackToModel = saved.getValue().stream().map(Message::getText)
                .filter(text -> text != null && text.contains("【market_agent 本轮取数失败】"))
                .findFirst().orElseThrow();

        // 归类过了（认出是 401）+ 原文一个字都没漏出去
        assertThat(pushedToUser).contains("API key").doesNotContain("sk-secret-abcdef123456", "gw.example.com");
        assertThat(fedBackToModel).contains("API key").doesNotContain("sk-secret-abcdef123456", "gw.example.com");
    }

    /** 整轮跑挂了推给前端的那句话同理：直接回显 e.getMessage() 就是把上游原文送进用户浏览器 */
    @Test
    void 整轮失败时error事件不回显上游原文() {
        List<String> sent = new ArrayList<>();
        SseEmitter emitter = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) {
                builder.build().forEach(data -> {
                    if (data.getData() instanceof String text) sent.add(text);
                });
            }
        };
        ChatMemoryService memory = mock(ChatMemoryService.class);
        when(memory.recall(anyLong())).thenReturn("");
        ChatTurnRunner turnRunner = mock(ChatTurnRunner.class);
        doThrow(new RuntimeException(RAW)).when(turnRunner)
                .run(any(), anyLong(), any(), any(), any(), any(), any());
        ChatConcurrencyGate gate = new ChatConcurrencyGate(10);
        WorkbenchRunRegistry runRegistry = mock(WorkbenchRunRegistry.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        ChatYieldCoordinator coordinator =
                new ChatYieldCoordinator(gate, runRegistry, turnRunner, history, memory);
        ChatWorkbenchController controller = new ChatWorkbenchController(mock(ChatAgentFactory.class),
                mock(LlmEndpointService.class), new ApprovalRegistry(), memory,
                history, mock(ChatContextStore.class), turnRunner,
                runRegistry, gate, coordinator);

        controller.run(new SseChannel(emitter), 1L, "wb-1-boom", "看看行情", null,
                coordinator.openTurn(1L));

        String errorEvent = sent.stream().filter(text -> text.startsWith("{") && text.contains("message"))
                .reduce((first, second) -> second).orElseThrow();
        assertThat(errorEvent).contains("API key").doesNotContain("sk-secret-abcdef123456", "gw.example.com");
    }
}

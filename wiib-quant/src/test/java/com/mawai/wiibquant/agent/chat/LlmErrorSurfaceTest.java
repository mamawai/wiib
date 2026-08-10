package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.entity.UserLlmConfig;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上游异常的文案有三个出口，每一个都得先过 {@code LlmErrorMessages}——
 * 原文可能几百字符，还带着 key 和完整请求 URL：
 * <ol>
 *   <li>专家失败时推给用户的 progress 事件</li>
 *   <li>专家失败时拼成 AssistantMessage 喂回模型、随 checkpoint 落库的那条</li>
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

        // 浅模型同时服务 router 和专家，只能靠系统提示词首句分辨是哪一种请求。
        // router 每次都点名 market_agent：第二次会被 route 的去重转 FINISH，回环自然收口
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

        CompiledGraph<MessagesState<Message>> graph = new ChatAgentFactory(chatModelFactory,
                mock(MarketToolkit.class), mock(NewsToolkit.class),
                mock(DeepAnalysisService.class), mock(WorkbenchRunRegistry.class),
                new ApprovalRegistry(), new MemorySaver(),
                new SpringAIJacksonStateSerializer<>(MessagesState::new), 8, 999_999, 6, "X")
                .chatGraph(new UserLlmConfig());

        List<ChatAgentFactory.ExpertProgress> progress = new ArrayList<>();
        RunnableConfig config = RunnableConfig.builder().threadId("wb-1-expert-fail")
                .addMetadata(ChatAgentFactory.PROGRESS_SINK_KEY,
                        (Consumer<ChatAgentFactory.ExpertProgress>) progress::add)
                .build();

        MessagesState<Message> end = graph.invoke(Map.of(
                "messages", List.of(new UserMessage("看看行情")),
                ChatAgentFactory.DISPATCH_ROUND_KEY, 0,
                ChatAgentFactory.DISPATCHED_KEY, List.of()), config).orElseThrow();

        String pushedToUser = progress.stream()
                .filter(e -> ChatAgentFactory.ExpertProgress.ERROR.equals(e.phase()))
                .map(ChatAgentFactory.ExpertProgress::text)
                .findFirst().orElseThrow();
        String fedBackToModel = end.messages().stream().map(Message::getText)
                .filter(text -> text.contains("market_agent 暂时不可用"))
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
        ChatWorkbenchController controller = new ChatWorkbenchController(mock(ChatAgentFactory.class),
                mock(UserLlmConfigService.class), new ApprovalRegistry(), memory,
                mock(ChatHistoryService.class), mock(WorkbenchCheckpointStore.class),
                mock(WorkbenchRunRegistry.class), new ChatConcurrencyGate(10));
        @SuppressWarnings("unchecked")
        CompiledGraph<MessagesState<Message>> graph = mock(CompiledGraph.class);
        when(graph.stream(any(Map.class), any(RunnableConfig.class))).thenThrow(new RuntimeException(RAW));

        controller.run(new ChatWorkbenchController.SseChannel(emitter), 1L, "wb-1-boom", "看看行情", graph);

        String errorEvent = sent.stream().filter(text -> text.startsWith("{") && text.contains("message"))
                .reduce((first, second) -> second).orElseThrow();
        assertThat(errorEvent).contains("API key").doesNotContain("sk-secret-abcdef123456", "gw.example.com");
    }
}

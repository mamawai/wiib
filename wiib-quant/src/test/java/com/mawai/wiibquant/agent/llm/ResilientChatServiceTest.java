package com.mawai.wiibquant.agent.llm;

import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgentBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 韧性分层的契约：阻塞路径的重试归模型层（ResponsesChatModel 自带 / OpenAI SDK），
 * 本层不再来一轮——两层都重试会叠乘成 3×3=9 次，白白放大尾延迟。
 * 另钉两条 options 契约：首轮强制逐次落地、options 类型跟着模型走（openai 协议硬转 OpenAiChatOptions）。
 */
class ResilientChatServiceTest {

    private final ChatModel primary = mock(ChatModel.class);

    private ReactAgent.ChatService service() {
        ReactAgentBuilder<?, ?> agentBuilder = mock(ReactAgentBuilder.class);
        when(agentBuilder.tools()).thenReturn(List.of());
        when(agentBuilder.systemMessage()).thenReturn(Optional.of("你是助手"));
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        return ResilientChatService.builder()
                .model(primary)
                .asFactory().apply(agentBuilder);
    }

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static final List<Message> ASK = List.of(new UserMessage("BTC 现在怎么样"));

    /** 重试是模型层的事：本层只调一次，失败原样抛给上层归类（LlmErrorMessages） */
    @Test
    void 阻塞路径失败只调一次且原样抛出() {
        when(primary.call(any(Prompt.class))).thenThrow(new TransientAiException("502"));

        assertThatThrownBy(() -> service().execute(ASK))
                .isInstanceOf(TransientAiException.class);
        verify(primary, times(1)).call(any(Prompt.class));
    }

    private ReactAgentBuilder<?, ?> agentWithOneTool() {
        ReactAgentBuilder<?, ?> agentBuilder = mock(ReactAgentBuilder.class);
        when(agentBuilder.tools()).thenReturn(List.of(mock(ToolCallback.class)));
        when(agentBuilder.systemMessage()).thenReturn(Optional.of("你是助手"));
        return agentBuilder;
    }

    /** 主模型收到的 Prompt 的 options（逐次调用现算，chatOptions() 那份是不带强制的底稿） */
    private ChatOptions optionsSentTo(ChatModel model) {
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        return prompt.getValue().getOptions();
    }

    /**
     * 首轮强制是<b>逐次</b>落地的：只有"最后一条用户消息之后还没有工具回执"那一次调用带 required，
     * 拿到工具结果后必须放开否则 ReactAgent 收不了尾。responses 协议经 toolContext 捎信号。
     */
    @Test
    void 专家可要求首轮强制用工具_只在首轮() {
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(primary.call(any(Prompt.class))).thenReturn(responseOf("ok"));
        ReactAgent.ChatService service = ResilientChatService.builder()
                .model(primary).forceFirstToolChoice("required")
                .asFactory().apply(agentWithOneTool());

        service.execute(ASK);
        assertThat(ToolChoice.of(optionsSentTo(primary))).isEqualTo(ToolChoice.REQUIRED);

        // 同一 agent 第二次调用：本轮已有工具回执 → 放开
        org.mockito.Mockito.clearInvocations(primary);
        Message toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "market_snapshot", "{}")))
                .build();
        service.execute(List.of(ASK.getFirst(), new AssistantMessage(""), toolResponse));
        assertThat(ToolChoice.of(optionsSentTo(primary))).isEqualTo(ToolChoice.AUTO);
    }

    /**
     * options 的具体类型必须跟着模型走：Spring AI 2.0 的 OpenAiChatModel 把 prompt 的 options 硬转
     * OpenAiChatOptions（不再合并运行时 options），泛型 builder 造的会当场 ClassCastException。
     * 首轮强制在这条协议上落的是 toolChoice 字段，不是 toolContext 信号。
     */
    @Test
    void openai协议下options保持OpenAiChatOptions且强制落在toolChoice() {
        when(primary.getOptions()).thenReturn(OpenAiChatOptions.builder().model("deepseek-chat").build());
        when(primary.call(any(Prompt.class))).thenReturn(responseOf("ok"));
        ReactAgent.ChatService service = ResilientChatService.builder()
                .model(primary).forceFirstToolChoice("required")
                .asFactory().apply(agentWithOneTool());

        service.execute(ASK);

        ChatOptions sent = optionsSentTo(primary);
        assertThat(sent).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions openAi = (OpenAiChatOptions) sent;
        assertThat(openAi.getToolChoice()).isEqualTo("required");
        assertThat(openAi.getToolCallbacks()).hasSize(1);
        assertThat(openAi.getModel()).isEqualTo("deepseek-chat");   // 生成参数沿用模型自己的
    }

    @Test
    void 不要求时不塞信号() {
        ReactAgentBuilder<?, ?> agentBuilder = mock(ReactAgentBuilder.class);
        when(agentBuilder.tools()).thenReturn(List.of(mock(ToolCallback.class)));
        when(agentBuilder.systemMessage()).thenReturn(Optional.of("你是助手"));
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());

        ReactAgent.ChatService service = ResilientChatService.builder()
                .model(primary).asFactory().apply(agentBuilder);

        ToolCallingChatOptions options = (ToolCallingChatOptions) service.chatOptions().orElseThrow();
        // 不要求时压根不碰 toolContext，保持框架给的原样（这里就是 null）
        assertThat(options.getToolContext()).isNull();
    }

    /**
     * 服务端搜索许可（只有 chat 的 summarizer 开）：与首轮强制不同，它对本 agent 的<b>每次</b>调用
     * 都生效——联网补充不限于首轮。经 toolContext 捎带，ResponsesChatModel 建请求体时读回。
     */
    @Test
    void webSearch开关_许可落进chatOptions的toolContext() {
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());

        ReactAgent.ChatService service = ResilientChatService.builder()
                .model(primary).webSearch(true).asFactory().apply(agentWithOneTool());

        ToolCallingChatOptions options = (ToolCallingChatOptions) service.chatOptions().orElseThrow();
        assertThat(options.getToolContext().get(ResponsesChatModel.WEB_SEARCH_KEY)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void webSearch开关_无function工具的agent也捎得上() {
        // 没挂工具时 chatOptions 本是 null；搜索许可不许因此静默丢
        ReactAgentBuilder<?, ?> agentBuilder = mock(ReactAgentBuilder.class);
        when(agentBuilder.tools()).thenReturn(List.of());
        when(agentBuilder.systemMessage()).thenReturn(Optional.of("你是汇总者"));
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());

        ReactAgent.ChatService service = ResilientChatService.builder()
                .model(primary).webSearch(true).asFactory().apply(agentBuilder);

        ToolCallingChatOptions options = (ToolCallingChatOptions) service.chatOptions().orElseThrow();
        assertThat(options.getToolContext().get(ResponsesChatModel.WEB_SEARCH_KEY)).isEqualTo(Boolean.TRUE);
    }

    /**
     * SDK 重试盲区的唯一豁免：响应体读到一半被掐（OpenAIInvalidDataException，如 HTTP/2 stream reset）
     * SDK 的 maxRetries 不管这类失败——这里单次重试救整轮唤醒，不与 SDK 重试叠乘。
     */
    @Test
    void 响应读取中断单次重试() {
        when(primary.call(any(Prompt.class)))
                .thenThrow(new com.openai.errors.OpenAIInvalidDataException("Error reading response",
                        new java.io.IOException("stream was reset: CANCEL")))
                .thenReturn(responseOf("重试成功"));

        ChatResponse result = service().execute(ASK);

        verify(primary, times(2)).call(any(Prompt.class));
        assertThat(result.getResult().getOutput().getText()).isEqualTo("重试成功");
    }
}

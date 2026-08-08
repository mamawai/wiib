package com.mawai.wiibquant.agent.llm;

import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgentBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 韧性分层的契约：阻塞路径的重试归模型层（ResponsesChatModel 自带 / OpenAI SDK），
 * 本服务只负责兜底切换——两层都重试会叠乘成 3×3=9 次，白白放大尾延迟。
 */
class ResilientChatServiceTest {

    private final ChatModel primary = mock(ChatModel.class);
    private final ChatModel fallback = mock(ChatModel.class);

    private ReactAgent.ChatService service(ChatModel fallbackModel) {
        ReactAgentBuilder<?, ?> agentBuilder = mock(ReactAgentBuilder.class);
        when(agentBuilder.tools()).thenReturn(List.of());
        when(agentBuilder.systemMessage()).thenReturn(Optional.of("你是助手"));
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        return ResilientChatService.builder()
                .model(primary).fallbackModel(fallbackModel)
                .asFactory().apply(agentBuilder);
    }

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static final List<Message> ASK = List.of(new UserMessage("BTC 现在怎么样"));

    @Test
    void 阻塞路径只调一次主模型失败即切兜底() {
        when(primary.call(any(Prompt.class))).thenThrow(new TransientAiException("429 限流"));
        when(fallback.call(any(Prompt.class))).thenReturn(responseOf("兜底回答"));

        ChatResponse result = service(fallback).execute(ASK);

        // 重试是模型层的事，这里绝不能再来一轮
        verify(primary, times(1)).call(any(Prompt.class));
        assertThat(result.getResult().getOutput().getText()).isEqualTo("兜底回答");
    }

    @Test
    void 没有兜底模型时原样抛出() {
        when(primary.call(any(Prompt.class))).thenThrow(new TransientAiException("502"));

        assertThatThrownBy(() -> service(null).execute(ASK))
                .isInstanceOf(TransientAiException.class);
        verify(primary, times(1)).call(any(Prompt.class));
    }

    @Test
    void 专家可要求首轮强制用工具() {
        ReactAgentBuilder<?, ?> agentBuilder = mock(ReactAgentBuilder.class);
        when(agentBuilder.tools()).thenReturn(List.of(mock(ToolCallback.class)));
        when(agentBuilder.systemMessage()).thenReturn(Optional.of("你是助手"));
        when(primary.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());

        ReactAgent.ChatService service = ResilientChatService.builder()
                .model(primary).forceFirstToolChoice("required")
                .asFactory().apply(agentBuilder);

        ToolCallingChatOptions options = (ToolCallingChatOptions) service.chatOptions().orElseThrow();
        assertThat(options.getToolContext())
                .containsEntry(ResilientChatService.FORCE_FIRST_TOOL_CHOICE, "required");
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
     * SDK 重试盲区的唯一豁免：响应体读到一半被掐（OpenAIInvalidDataException，如 HTTP/2 stream reset）
     * SDK 的 maxRetries 不管这类失败——这里单次重试救整轮唤醒，不与 SDK 重试叠乘。
     */
    @Test
    void 响应读取中断单次重试() {
        when(primary.call(any(Prompt.class)))
                .thenThrow(new com.openai.errors.OpenAIInvalidDataException("Error reading response",
                        new java.io.IOException("stream was reset: CANCEL")))
                .thenReturn(responseOf("重试成功"));

        ChatResponse result = service(null).execute(ASK);

        verify(primary, times(2)).call(any(Prompt.class));
        assertThat(result.getResult().getOutput().getText()).isEqualTo("重试成功");
    }

    @Test
    void 主模型正常时不碰兜底() {
        when(primary.call(any(Prompt.class))).thenReturn(responseOf("主模型回答"));

        ChatResponse result = service(fallback).execute(ASK);

        assertThat(result.getResult().getOutput().getText()).isEqualTo("主模型回答");
        verify(fallback, never()).call(any(Prompt.class));
    }
}

package com.mawai.wiibquant.agent.llm;

import com.sun.net.httpserver.HttpServer;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgentBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 钉死与框架的契约边界（这些曾被别处单测 mock 掉，真跑才暴露）：
 * Spring AI 2.0 的契约方法是 getOptions()，覆写成旧名 getDefaultOptions() 会命中接口默认实现
 * 返回普通 ChatOptions → ResilientChatService 挂工具的 instanceof 恒假 → 专家请求 tools=[]。
 * <p>
 * 阻塞路径用例走真 HTTP + SSE（JDK HttpServer 假服务器，零新依赖）：
 * call() 与 stream() 共用 streamOnce 一条协议解析路，这些用例钉住的是各类 BYOK 渠道的
 * 事件形态兼容性——工具只在 output_item.done 发、无增量服务端只发 completed、半途断流等。
 */
class ResponsesChatModelTest {

    /** 假 SSE 服务器：每个请求都按 events 当前值回放事件后关流（关流即 SSE 正常终止信号） */
    private static HttpServer server;
    /** 各用例各自设定要回放的事件序列；handler 无状态，重试的每次请求拿到同样的流 */
    private static volatile String[] events = new String[0];

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                for (String event : events) {
                    // SSE 的 data 不允许裸换行，text block 里排版用的换行必须压平
                    String line = event.replaceAll("\\s*\\R\\s*", "");
                    os.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private ResponsesChatModel model() {
        return new ResponsesChatModel("key", "http://127.0.0.1:" + server.getAddress().getPort(),
                "grok-test", null, null, mock(ToolCallingManager.class));
    }

    @Test
    void getOptions必须给ToolCallingChatOptions() {
        // 真实实例、不 mock：ResilientChatService 靠 instanceof 这个类型决定挂不挂工具
        assertThat(model().getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        assertThat(model().getOptions().getModel()).isEqualTo("grok-test");
    }

    @Test
    void 与ResilientChatService组合时工具挂得上() {
        // 复刻 expertGraph 的装配路径：真实模型 + 工厂回调，工具必须进 chatOptions
        ReactAgentBuilder<?, ?> agentBuilder = mock(ReactAgentBuilder.class);
        when(agentBuilder.tools()).thenReturn(List.of(mock(ToolCallback.class)));
        when(agentBuilder.systemMessage()).thenReturn(Optional.of("你是专家"));

        ReactAgent.ChatService service = ResilientChatService.builder()
                .model(model()).forceFirstToolChoice("required")
                .asFactory().apply(agentBuilder);

        ToolCallingChatOptions options = (ToolCallingChatOptions) service.chatOptions().orElseThrow();
        assertThat(options.getToolCallbacks()).hasSize(1);
        // 强制信号是逐次调用时才捎的（ToolChoice.apply），底稿里没有；本模型读的就是这个键
        assertThat(ToolChoice.of(ToolChoice.apply(options, ToolChoice.REQUIRED))).isEqualTo("required");
        assertThat(ToolChoice.of(options)).isEqualTo(ToolChoice.AUTO);
    }

    // ========== 阻塞路径（call → streamOnce 帧合并） ==========

    @Test
    void 阻塞路径_正常SSE流_拼接增量并带usage收尾() {
        events = new String[]{
                """
                {"type":"response.output_text.delta","delta":"【本轮结论】"}""",
                """
                {"type":"response.output_text.delta","delta":"HOLD，等待突破确认。"}""",
                """
                {"type":"response.completed","response":{"id":"resp_1","status":"completed",
                 "model":"grok-test","output":[],
                 "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}"""
        };
        ChatResponse response = model().call(new Prompt("问题"));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("【本轮结论】HOLD，等待突破确认。");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
        assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(15);
    }

    @Test
    void 阻塞路径_工具帧_收齐并标TOOL_CALLS() {
        // 有的渠道 completed 里 output 不全，工具调用只能从 output_item.done 收——
        // 帧合并必须靠工具帧而不是 completed 的 output（cpa 对 grok 专门打过 output 补丁，上游真有此形态）
        events = new String[]{
                """
                {"type":"response.output_item.done","item":{"type":"function_call",
                 "call_id":"c1","name":"get_account","arguments":"{}"}}""",
                """
                {"type":"response.completed","response":{"id":"resp_2","status":"completed",
                 "model":"grok-test","output":[]}}"""
        };
        ChatResponse response = model().call(new Prompt("查账户"));

        List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.getFirst().name()).isEqualTo("get_account");
        assertThat(toolCalls.getFirst().id()).isEqualTo("c1");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
    }

    @Test
    void 阻塞路径_无增量服务端_completed里的工具调用也要收() {
        // 简易网关把非流式响应原样包成一个 completed 事件：工具调用只在 output 里，
        // 没有 output_item.done——兜底丢工具的话，"模型要调工具"会被当成"模型答了空话"收场
        events = new String[]{
                """
                {"type":"response.completed","response":{"id":"resp_5","status":"completed",
                 "model":"grok-test","output":[{"type":"function_call",
                     "call_id":"c9","name":"get_account","arguments":"{}"}]}}"""
        };
        ChatResponse response = model().call(new Prompt("查账户"));

        List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.getFirst().name()).isEqualTo("get_account");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
    }

    @Test
    void 阻塞路径_completed事件带incomplete状态_以payload为准判失败() {
        // 同一类简易网关的另一面：上游返回 status=incomplete 的 JSON 被不加判断地
        // 包成 completed 事件——事件类型说完成、payload 说截断时，信 payload
        events = new String[]{
                """
                {"type":"response.completed","response":{"id":"resp_6","status":"incomplete",
                 "model":"grok-test","incomplete_details":{"reason":"max_output_tokens"},
                 "output":[{"type":"message","content":[
                     {"type":"output_text","text":"【本轮结论】方向：做多 BTC，止损放在"}]}]}}"""
        };
        assertThatThrownBy(() -> model().call(new Prompt("问题")))
                .isInstanceOf(NonTransientAiException.class)
                .hasMessageContaining("max_output_tokens");
    }

    @Test
    void 阻塞路径_无增量服务端_从completed兜底全文() {
        // 不发 delta 的服务端：全文只在 completed 的 output 里，靠 toFrames 的兜底帧补出
        events = new String[]{
                """
                {"type":"response.completed","response":{"id":"resp_3","status":"completed",
                 "model":"grok-test","output":[{"type":"message","content":[
                     {"type":"output_text","text":"【本轮结论】HOLD，等待突破确认。"}]}]}}"""
        };
        assertThat(model().call(new Prompt("问题")).getResult().getOutput().getText())
                .isEqualTo("【本轮结论】HOLD，等待突破确认。");
    }

    /**
     * Responses 除了 failed 还有 incomplete（含 max_output_tokens 截断），它照常带着半截 output。
     * 阻塞路径当正常收尾发 STOP 的话，被截断的【本轮结论】会以 status=OK 落库，
     * 下一轮还被当"上一轮的承诺"回注给模型做检验基准。
     */
    @Test
    void 阻塞路径_incomplete事件_判截断失败() {
        events = new String[]{
                """
                {"type":"response.output_text.delta","delta":"【本轮结论】方向：做多 BTC，止损放在"}""",
                """
                {"type":"response.incomplete","response":{"status":"incomplete",
                 "incomplete_details":{"reason":"max_output_tokens"}}}"""
        };
        assertThatThrownBy(() -> model().call(new Prompt("问题")))
                .isInstanceOf(NonTransientAiException.class)
                .hasMessageContaining("max_output_tokens");
    }

    /**
     * 半途断流（cpa 侧对应 408 stream disconnected）：连接级偶发故障，换个连接大概率就好，
     * 必须判 Transient 进 callWithRetry 重试通道——判 NonTransient 会让 trader 整轮唤醒白跑。
     */
    @Test
    void 阻塞路径_断流无收尾_判瞬时可重试() {
        events = new String[]{
                """
                {"type":"response.output_text.delta","delta":"说到一半"}"""
                // 没有 completed，服务器随即关流
        };
        assertThatThrownBy(() -> model().call(new Prompt("问题")))
                .isInstanceOf(TransientAiException.class)
                .hasMessageContaining("断流");
    }
}

package com.mawai.wiibquant.agent.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgentBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 钉死与框架的契约边界（这些曾被别处单测 mock 掉，真跑才暴露）：
 * Spring AI 2.0 的契约方法是 getOptions()，覆写成旧名 getDefaultOptions() 会命中接口默认实现
 * 返回普通 ChatOptions → ResilientChatService 挂工具的 instanceof 恒假 → 专家请求 tools=[]。
 */
class ResponsesChatModelTest {

    private ResponsesChatModel model() {
        return new ResponsesChatModel("key", "http://localhost", "grok-test", null, null,
                mock(ToolCallingManager.class));
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
        assertThat(options.getToolContext())
                .containsEntry(ResilientChatService.FORCE_FIRST_TOOL_CHOICE, "required");
    }

    /**
     * Responses 除了 failed 还有 incomplete（含 max_output_tokens 截断），它照常带着半截 output。
     * 阻塞路径当正常收尾发 STOP 的话，被截断的【本轮结论】会以 status=OK 落库，
     * 下一轮还被当"上一轮的承诺"回注给模型做检验基准。流式路径（response.incomplete）早就当失败处理了。
     */
    @Test
    void 阻塞路径必须把incomplete当截断失败() {
        JSONObject truncated = JSON.parseObject("""
                {"id":"resp_1","status":"incomplete","model":"grok-test",
                 "incomplete_details":{"reason":"max_output_tokens"},
                 "output":[{"type":"message","content":[
                     {"type":"output_text","text":"【本轮结论】方向：做多 BTC，止损放在"}]}]}""");

        assertThatThrownBy(() -> model().parseResponse(truncated))
                .isInstanceOf(NonTransientAiException.class)
                .hasMessageContaining("max_output_tokens");
    }

    @Test
    void 阻塞路径正常收尾照常解析出正文() {
        JSONObject completed = JSON.parseObject("""
                {"id":"resp_2","status":"completed","model":"grok-test",
                 "output":[{"type":"message","content":[
                     {"type":"output_text","text":"【本轮结论】HOLD，等待突破确认。"}]}]}""");

        assertThat(model().parseResponse(completed).getResult().getOutput().getText())
                .isEqualTo("【本轮结论】HOLD，等待突破确认。");
    }

    @Test
    void 首轮判定只看最后一条用户消息之后() {
        Message user = new UserMessage("BTC 怎么样");
        Message assistant = new AssistantMessage("看涨");
        Message toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "market_snapshot", "{}")))
                .build();

        // 干净首轮：只有提问
        assertThat(ResponsesChatModel.isFirstTurn(List.of(user))).isTrue();
        // 本轮已拿过工具结果：不再是首轮（ReactAgent 循环收尾必须放开）
        assertThat(ResponsesChatModel.isFirstTurn(List.of(user, assistant, toolResponse))).isFalse();
        // 上一轮的 TRM 在新提问之前（summarizer 深研判留痕）：新一轮仍是首轮
        assertThat(ResponsesChatModel.isFirstTurn(List.of(toolResponse, assistant, user))).isTrue();
        // 新提问之后只有专家的普通回复（并行回环第二轮派发）：对没跑过的专家仍是首轮
        assertThat(ResponsesChatModel.isFirstTurn(List.of(user, assistant))).isTrue();
    }
}

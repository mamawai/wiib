package com.mawai.wiibquant.agent.llm;

import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.state.AppenderChannel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationSummarizerTest {

    private final ChatModel summaryModel = mock(ChatModel.class);

    private ConversationSummarizer summarizer(int thresholdTokens, int messagesToKeep) {
        return new ConversationSummarizer(summaryModel, thresholdTokens, messagesToKeep);
    }

    private void stubSummary(String text) {
        when(summaryModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    @SuppressWarnings("unchecked")
    private List<Message> compressedOf(Map<String, Object> update) {
        return ((AppenderChannel.ReplaceAllWith<Message>) update.get("messages")).newValues();
    }

    private static MessagesState<Message> stateOf(List<Message> messages) {
        return new MessagesState<>(Map.of("messages", messages));
    }

    // ===== token 估算：中英文分别校准 =====

    @Test
    void estimatesChineseAtRoughlyOneTokenPerChar() {
        // 20 个汉字 ≈ 20 token；框架的 charCount/4 只会算出 5
        int tokens = ConversationSummarizer.estimateTokens(
                List.of(new UserMessage("一二三四五六七八九十一二三四五六七八九十")));

        assertThat(tokens).isEqualTo(20);
    }

    @Test
    void estimatesEnglishAtRoughlyFourCharsPerToken() {
        int tokens = ConversationSummarizer.estimateTokens(new ArrayList<>(List.of(
                new UserMessage("abcdefgh")))); // 8 字符 → 2 token

        assertThat(tokens).isEqualTo(2);
    }

    // ===== 触发条件 =====

    @Test
    void skipsWhenUnderThreshold() {
        Map<String, Object> update = summarizer(10_000, 6)
                .applyBefore("agent", stateOf(List.of(new UserMessage("短对话"))), null).join();

        assertThat(update).isEmpty();
        verify(summaryModel, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void keepsFirstUserMessageAndRecentOnes() {
        stubSummary("摘要内容");
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("最初的诉求：帮我盯BTC"));
        for (int i = 0; i < 20; i++) {
            messages.add(new AssistantMessage("这是一段很长的助手回复用来撑高token计数" + i));
        }

        Map<String, Object> update = summarizer(50, 3).applyBefore("agent", stateOf(messages), null).join();
        List<Message> compressed = compressedOf(update);

        assertThat(compressed).hasSize(1 + 1 + 3); // 首条用户消息 + 摘要 + 最近3条
        assertThat(compressed.get(0)).isInstanceOf(UserMessage.class);
        assertThat(compressed.get(0).getText()).contains("最初的诉求");
        assertThat(compressed.get(1)).isInstanceOf(SystemMessage.class);
        assertThat(compressed.get(1).getText()).contains("摘要内容");
        assertThat(compressed.get(compressed.size() - 1).getText()).endsWith("19");
    }

    // ===== 核心正确性：切点不能拆散工具调用配对 =====

    @Test
    void cutoffNeverSeparatesToolCallFromItsResponse() {
        stubSummary("摘要");
        // 构造：理想切点(size-2)恰好落在 toolCall 与 toolResponse 之间，压缩器必须往前挪
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("查一下行情"));
        for (int i = 0; i < 8; i++) {
            messages.add(new AssistantMessage("填充消息拉高token" + i));
        }
        messages.add(AssistantMessage.builder().content("要调工具了")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "getSnapshot", "{}")))
                .build());
        messages.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call_1", "getSnapshot", "{\"price\":95000}"))).build());

        List<Message> compressed = compressedOf(
                summarizer(50, 2).applyBefore("agent", stateOf(messages), null).join());

        // 配对的两条要么都在保留区、要么都被压缩，不能只剩一半
        boolean hasCall = compressed.stream().anyMatch(m -> m instanceof AssistantMessage a
                && a.getToolCalls().stream().anyMatch(tc -> "call_1".equals(tc.id())));
        boolean hasResponse = compressed.stream().anyMatch(m -> m instanceof ToolResponseMessage t
                && t.getResponses().stream().anyMatch(r -> "call_1".equals(r.id())));
        assertThat(hasCall).isEqualTo(hasResponse);
    }

    // ===== 摘要输入必须含工具结果内容 =====

    /**
     * ToolResponseMessage.getText() 恒为空串（构造时传的就是 ""），真内容在 responseData()；
     * AssistantMessage 的 toolCalls().arguments() 同样不进文本。而阈值估算恰恰把这两样都算进去了——
     * 压缩是被工具结果的体积撑触发的，扔掉的却正是工具结果，行情数字和研判结论全没。
     */
    @Test
    void summaryInputCarriesToolResultPayload() {
        stubSummary("摘要");
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("BTC 现在怎么样"));
        messages.add(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "market_snapshot",
                        "{\"symbol\":\"BTCUSDT\"}"))).build());
        messages.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call_1", "market_snapshot",
                        "{\"markPrice\":95000,\"fundingRate\":0.0001}"))).build());
        for (int i = 0; i < 10; i++) {
            messages.add(new AssistantMessage("闲聊填充把体积撑过阈值" + i));
        }

        summarizer(50, 2).applyBefore("agent", stateOf(messages), null).join();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(summaryModel).call(prompt.capture());
        String input = prompt.getValue().getInstructions().getFirst().getText();
        assertThat(input).contains("95000").contains("market_snapshot").contains("BTCUSDT");
    }

    /** 超长工具回包要截断：摘要输入本身不能反被深研判回包撑爆 */
    @Test
    void oversizedToolPayloadIsTruncatedInSummaryInput() {
        String huge = "行情".repeat(5000);
        String text = ConversationSummarizer.textOf(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call_1", "deep_analysis", huge))).build());

        assertThat(text).contains("deep_analysis").contains("截断");
        assertThat(text.length()).isLessThan(huge.length() / 2);
    }

    // ===== 摘要不许套摘要 =====

    /**
     * 上次压缩产出的摘要落在 index 1，下次压缩必然把它再压一遍。除首条用户消息外没有原文锚点，
     * 3~4 次后早期事实基本消失且无法归因。已是摘要的那条要被认出来原样保留，只压新增的原文。
     */
    @Test
    void previousSummaryIsKeptVerbatimNotRecompressed() {
        stubSummary("本次新增要点");
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("最初的诉求：帮我盯BTC"));
        messages.add(new SystemMessage("## 早前对话摘要：\n用户在 92000 附近建了多单"));
        for (int i = 0; i < 20; i++) {
            messages.add(new AssistantMessage("新一轮对话内容填充把体积撑过阈值" + i));
        }

        List<Message> compressed = compressedOf(
                summarizer(50, 3).applyBefore("agent", stateOf(messages), null).join());

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(summaryModel).call(prompt.capture());
        // 老摘要不进这次的压缩输入——二次压缩正是早期事实消失的原因
        assertThat(prompt.getValue().getInstructions().getFirst().getText()).doesNotContain("92000");
        // 但它原样活在新的摘要消息里，与本次新增分段可辨
        assertThat(compressed).hasSize(1 + 1 + 3);
        assertThat(compressed.get(1)).isInstanceOf(SystemMessage.class);
        assertThat(compressed.get(1).getText()).contains("92000").contains("本次新增要点");
    }

    @Test
    void degradesToOriginalWhenSummaryModelFails() {
        when(summaryModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("模型挂了"));
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("诉求"));
        for (int i = 0; i < 20; i++) {
            messages.add(new AssistantMessage("很长很长的助手回复内容用来撑高计数" + i));
        }

        // 压缩失败只记日志，返回空更新=沿用原始对话，不该打断整轮对话
        assertThat(summarizer(50, 3).applyBefore("agent", stateOf(messages), null).join()).isEmpty();
    }
}

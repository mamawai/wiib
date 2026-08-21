package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibquant.agent.llm.LlmEndpointService;
import com.mawai.wiibquant.agent.llm.UsageTrackingChatModel;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重新生成最后一条回答：回退到"提问已在、回答未出"，再用原提问重跑。
 * <p>
 * 四件必须钉住的事：
 * <ol>
 *   <li><b>轮起始标记只是候选</b>——历史压缩会把首条用户消息原样放回队首，那条也带标记；
 *       不拿提问原文核对就会切错位置，把中间几轮连同摘要一起抹掉。</li>
 *   <li><b>旧答案要留到新答案确实落库之后才删</b>——重跑失败/空产出/被让位的路上，
 *       用户至少还留着原来那条，也还能再点一次。</li>
 *   <li><b>重新生成轮不许被新消息挤走</b>——旧答案的位置已经腾出来了，被挤掉就没处放新答案。</li>
 *   <li><b>拒绝时名额必须还回去</b>——不还的话这个用户此后永久占线，比不给重新生成严重得多。</li>
 * </ol>
 */
class ChatRegenerateTest {

    private static final String SESSION = "wb-1-regen";

    private static ChatHistoryService.ChatMessage msg(long id, String role, String content) {
        return new ChatHistoryService.ChatMessage(id, role, content, 1_700_000_000_000L, null);
    }

    /** 上下文里一条轮起始提问，形状与 run() 拼的 enriched 一致 */
    private static Message turnStart(String question) {
        return new UserMessage(ChatWorkbenchController.TURN_MARKER + "2026-08-18 14:32】\n"
                + ChatWorkbenchController.QUESTION_MARKER + question);
    }

    private record Harness(ChatWorkbenchController controller, ChatHistoryService history,
                           ChatContextStore contextStore, ChatTurnRunner turnRunner,
                           ChatConcurrencyGate gate, ChatYieldCoordinator coordinator) {
    }

    private static Harness harness(List<ChatHistoryService.ChatMessage> stored, List<Message> context) {
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(history.messages(SESSION)).thenReturn(stored);
        // 默认落库成功：删旧答案的前提就是这个返回值，桩成默认的 false 整条重生成都不会走到删
        when(history.append(any(), anyLong(), any(), any(), any())).thenReturn(true);
        ChatContextStore contextStore = mock(ChatContextStore.class);
        when(contextStore.load(SESSION)).thenReturn(context);

        ChatTurnRunner turnRunner = mock(ChatTurnRunner.class);
        doAnswer((Answer<ChatTurnRunner.TurnResult>) inv -> {
            Consumer<String> sink = inv.getArgument(4);
            sink.accept("新答案");
            return ChatTurnRunner.TurnResult.COMPLETED;
        }).when(turnRunner).run(any(), anyLong(), any(), any(), any(), any(), any());

        LlmEndpointService endpointService = mock(LlmEndpointService.class);
        when(endpointService.chatEndpoints(1L)).thenReturn(ChatTestEndpoints.eps(1L, "gpt-5"));

        // 叶子只需要账本是真的：turnRunner 被替身顶了，图用不上
        UsageTrackingChatModel model = new UsageTrackingChatModel(mock(ChatModel.class));
        ChatAgentFactory agentFactory = mock(ChatAgentFactory.class);
        when(agentFactory.leavesFor(any(), any()))
                .thenReturn(new ChatAgentFactory.Leaves("端点 · gpt-5", model, model, Map.of(), null, AgentLang.ZH));

        ChatConcurrencyGate gate = new ChatConcurrencyGate(10);
        WorkbenchRunRegistry runRegistry = mock(WorkbenchRunRegistry.class);
        ChatYieldCoordinator coordinator =
                new ChatYieldCoordinator(gate, runRegistry, turnRunner, history, ChatTestEndpoints.PROMPTS);
        ChatWorkbenchController controller = new ChatWorkbenchController(agentFactory, endpointService,
                new ApprovalRegistry(), history, contextStore, turnRunner, runRegistry, gate, coordinator, ChatTestEndpoints.PROMPTS, ChatTestEndpoints.zhLang());
        return new Harness(controller, history, contextStore, turnRunner, gate, coordinator);
    }

    private static void regenerate(Harness h) {
        regenerate(h, SESSION);
    }

    private static void regenerate(Harness h, String sessionId) {
        ChatWorkbenchController.RegenerateRequest r = new ChatWorkbenchController.RegenerateRequest();
        r.setSessionId(sessionId);
        h.controller().regenerate(1L, r, mock(HttpServletResponse.class));
    }

    private static void assertRejected(Harness h) {
        assertThatThrownBy(() -> regenerate(h))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.CHAT_REGENERATE_UNAVAILABLE.getCode());
        // 名额没还的话这个用户此后永久占线，比不给重新生成严重得多
        assertThat(h.gate().tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
        // 拒绝就什么都别动
        verify(h.contextStore(), never()).save(any(), anyLong(), any());
        verify(h.history(), never()).deleteMessage(anyLong());
    }

    @Test
    void 回退后用原提问重跑且不重复落提问行() {
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "assistant", "旧答案")),
                List.of(turnStart("BTC 怎么样"), new AssistantMessage("旧答案")));

        regenerate(h);

        // 上下文回删到那条提问为止（含它）：重跑时 run() 会自己把提问重新垫进去
        ArgumentCaptor<List<Message>> ctx = ArgumentCaptor.captor();
        verify(h.contextStore()).save(eq(SESSION), eq(1L), ctx.capture());
        assertThat(ctx.getValue()).isEmpty();

        // 重跑喂给 runner 的是库里那条原提问
        ArgumentCaptor<String> enriched = ArgumentCaptor.captor();
        verify(h.turnRunner(), timeout(5_000))
                .run(any(), anyLong(), eq(SESSION), enriched.capture(), any(), any(), any());
        assertThat(enriched.getValue()).endsWith(ChatWorkbenchController.QUESTION_MARKER + "BTC 怎么样");
        // 提问行已经在库里，再落一遍历史里就成了连问两遍
        verify(h.history(), never()).append(any(), anyLong(), eq("user"), any());
        // 新答案落库之后旧答案才被顶掉；提问行一直留着（前端气泡不闪、时间戳不变）
        verify(h.history(), timeout(5_000)).append(eq(SESSION), eq(1L), eq("assistant"), eq("新答案"), any());
        verify(h.history(), timeout(5_000)).deleteMessage(2L);
        verify(h.history(), never()).deleteMessage(1L);
    }

    /**
     * append 自己吞异常、只记日志（历史是增益不是主链），所以"落库了没有"必须看返回值。
     * 没落上还照删，这个提问下就一条答案都不剩了——末尾是 user 行，连重新生成都点不了。
     */
    @Test
    void 新答案没落库就绝不删旧答案() {
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "assistant", "旧答案")),
                List.of(turnStart("BTC 怎么样"), new AssistantMessage("旧答案")));
        when(h.history().append(any(), anyLong(), eq("assistant"), any(), any())).thenReturn(false);

        regenerate(h);

        verify(h.history(), timeout(5_000)).append(eq(SESSION), eq(1L), eq("assistant"), eq("新答案"), any());
        verify(h.history(), never()).deleteMessage(anyLong());
    }

    /**
     * 点了重新生成又马上点停止：一个字都没出，这时候顶掉旧答案等于拿一行"（未作答）"
     * 换掉用户原来那条好答案，而且找不回来。出了半截才算这一次重生成的产物，那才该顶替。
     */
    @Test
    void 重生成刚开跑就被中断_旧答案不许被顶掉() {
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "assistant", "旧答案")),
                List.of(turnStart("BTC 怎么样"), new AssistantMessage("旧答案")));
        doReturn(ChatTurnRunner.TurnResult.CANCELLED)
                .when(h.turnRunner()).run(any(), anyLong(), any(), any(), any(), any(), any());

        regenerate(h);

        verify(h.history(), timeout(5_000)).append(eq(SESSION), eq(1L), eq("assistant"), any(), any());
        verify(h.history(), never()).deleteMessage(anyLong());
    }

    @Test
    void 重跑失败时旧答案还留着() {
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "assistant", "旧答案")),
                List.of(turnStart("BTC 怎么样"), new AssistantMessage("旧答案")));
        doThrow(new RuntimeException("上游挂了"))
                .when(h.turnRunner()).run(any(), anyLong(), any(), any(), any(), any(), any());

        regenerate(h);

        // 这一轮不落 assistant 行；旧答案要是已经删了，用户点一次重新生成就把好答案弄丢了，还没法再点
        verify(h.history(), after(500).never()).deleteMessage(anyLong());
        verify(h.history(), never()).append(any(), anyLong(), eq("assistant"), any(), any());
    }

    @Test
    void 重新生成轮不许被新消息挤走() {
        Harness h = harness(List.of(), List.of());

        ChatYieldCoordinator.TurnHandle regen = h.coordinator().openTurn(1L, false);
        regen.enterExpertWait();
        // 它把旧答案的位置腾出来了，被挤掉的话新答案只能由补答轮追加到会话末尾，位置错还再也不能重新生成
        assertThat(h.coordinator().requestYield(1L)).isNull();

        ChatYieldCoordinator.TurnHandle normal = h.coordinator().openTurn(1L);
        normal.enterExpertWait();
        assertThat(h.coordinator().requestYield(1L)).isNotNull();
    }

    @Test
    void 上下文被压缩过时拒绝而不是切在压缩留下的首问上() {
        // 压缩产物的真实形状：首条用户消息被原样放回队首（它也带轮起始标记），中间是摘要。
        // 只认标记的话会命中 index 0，把中间好几轮连同摘要一起抹掉，而且悄无声息
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "assistant", "旧答案")),
                List.of(turnStart("很久以前的第一个问题"),
                        new SystemMessage("【历史摘要】前面聊过行情与新闻"),
                        new AssistantMessage("旧答案")));

        assertRejected(h);
    }

    @Test
    void 末尾是没答过的提问时拒绝() {
        // 上一轮让位不落 assistant 行，末尾就是一条没答案的提问。这里两轮问的是同一句，
        // 好让"提问原文核对"那一关也能过——否则 role 判据形同虚设也看不出来
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "assistant", "旧答案"), msg(3, "user", "BTC 怎么样")),
                List.of(turnStart("BTC 怎么样"), new AssistantMessage("旧答案"), turnStart("BTC 怎么样")));

        assertRejected(h);
    }

    @Test
    void 补答行不给重新生成() {
        // 补答行对应的提问不在会话末尾，中间夹着别的问答，回退会误伤那些轮次
        Harness h = harness(
                List.of(msg(1, "user", "BTC 怎么样"), msg(2, "user", "ETH 呢"), msg(3, "assistant", "ETH 的答案"),
                        msg(4, "assistant", ChatTestEndpoints.PROMPTS.get(AgentLang.ZH, "chat.deferred.prefix")
                                + "BTC 怎么样」】\n\n补上的答案")),
                List.of(turnStart("ETH 呢"), new AssistantMessage("ETH 的答案")));

        assertRejected(h);
    }

    @Test
    void 别人的会话号一律拒绝() {
        Harness h = harness(List.of(), List.of());

        assertThatThrownBy(() -> regenerate(h, "wb-2-someone-else"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.CHAT_REGENERATE_UNAVAILABLE.getCode());
        // 归属校验在拿名额之前，闸门根本没被碰过
        assertThat(h.gate().tryAcquire(1L)).isEqualTo(ChatConcurrencyGate.Acquire.OK);
    }
}

package com.mawai.wiibagent.llm;

import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.hook.EdgeHook;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 单次运行的模型调用保险丝：ReAct 是循环，模型可能陷进"查完行情又想查持仓、查完持仓又想查行情"
 * 的死转里一路烧 token。超过上限就直接结束本轮，把已有结果交出去。
 * 倒数第二次（最后一次能执行工具）的回执末尾贴一句预算已尽，让模型下一次调用直接收尾，
 * 而不是撞上限硬切、这轮连结论块都没有。
 * <p>
 * 挂在工具边（ExecuteToolsHook）而不是模型节点：只有边的返回值 {@link Command} 能决定路由，
 * 节点 hook 改不了"下一步走哪"。计数存 state；消费方（chat 叶子/trader 唤醒）都无 saver，
 * 每次 invoke/stream 从 schema 起算，"单次运行"这个作用域天然成立。
 */
@Slf4j
public class ModelCallLimiter implements EdgeHook.WrapCall<MessagesState<Message>> {

    /** ReAct 图里 END 的映射键（见 langgraph4j Agent：EdgeMappings.toEND("end")） */
    private static final String GOTO_END = "end";
    /** 最终 state 里可读到本轮模型调用数——调用方据此判断是否被保险丝提前收束 */
    public static final String CALL_COUNT_KEY = "model_call_count";

    private final int runLimit;
    /** 占位回执正文（llm.callLimit.notExecuted）：说清"没执行"，不是伪造的成功结果——
     * 模型和复盘都要看得懂。按语言在建图时由调用方取词表传入 */
    private final String notExecuted;
    /** 预算收尾提示（llm.callLimit.lastCall）：最后一次能执行工具时贴在回执末尾，下一次模型调用直接给最终答复 */
    private final String lastCall;

    public ModelCallLimiter(int runLimit, String notExecuted, String lastCall) {
        this.runLimit = runLimit;
        this.notExecuted = notExecuted;
        this.lastCall = lastCall;
    }

    @Override
    public CompletableFuture<Command> applyWrap(String nodeId, MessagesState<Message> state,
                                                 RunnableConfig config, AsyncCommandAction<MessagesState<Message>> action) {
        int calls = state.<Number>value(CALL_COUNT_KEY).map(Number::intValue).orElse(0) + 1;
        if (calls >= runLimit) {
            log.warn("[CallLimit] 模型调用达上限 {}，本轮提前结束", runLimit);
            Map<String, Object> update = new HashMap<>();
            update.put(CALL_COUNT_KEY, calls);
            // 跳 END 前必须给这批没跑的 tool_call 补上配对回执：本 hook 挂在工具边上，
            // 此刻最后一条正是带 toolCalls 的 AssistantMessage。只跳不补 = 留下永远等不到
            // tool_result 的孤儿——工作台会把这段残缺历史持久化，之后每次续聊重建成
            // function_call 却找不到 function_call_output，上游直接 400，会话只能删掉重开
            placeholderResponses(state).ifPresent(trm -> update.put("messages", List.of(trm)));
            return CompletableFuture.completedFuture(new Command(GOTO_END, update));
        }
        CompletableFuture<Command> executed = action.apply(state, config);
        if (calls == runLimit - 1) {
            // 这是最后一次能执行工具：回执末尾贴上提示，下一次模型调用直接收尾
            return executed.thenApply(command -> withLastCallNotice(command, calls));
        }
        return executed.thenApply(command -> command.withMergedUpdate(Map.of(CALL_COUNT_KEY, calls)));
    }

    /**
     * 提示贴在最后一条工具回执的末尾，不另起一条 user 消息：tool_result 后面紧跟 user 文本，
     * Anthropic/Gemini 协议不一定收。回执单条 / 列表两种形态都接
     */
    @SuppressWarnings("unchecked")
    private Command withLastCallNotice(Command command, int calls) {
        Map<String, Object> update = new HashMap<>(command.update());
        Object messages = update.get("messages");
        List<Message> list = messages instanceof List<?> l
                ? new ArrayList<>((List<Message>) l) : new ArrayList<>(List.of((Message) messages));
        int last = list.size() - 1;
        ToolResponseMessage trm = (ToolResponseMessage) list.get(last);
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(trm.getResponses());
        ToolResponseMessage.ToolResponse tail = responses.getLast();
        responses.set(responses.size() - 1, new ToolResponseMessage.ToolResponse(
                tail.id(), tail.name(), tail.responseData() + "\n\n" + lastCall));
        list.set(last, ToolResponseMessage.builder().responses(responses).build());
        update.put("messages", list);
        update.put(CALL_COUNT_KEY, calls);
        return new Command(command.gotoNode(), update);
    }

    /** 最后一条助手消息里待执行的 tool_call → 一条标记未执行的 ToolResponseMessage（没有就不补，空回执本身也是孤儿） */
    private Optional<ToolResponseMessage> placeholderResponses(MessagesState<Message> state) {
        return state.lastMessage()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(AssistantMessage::hasToolCalls)
                .map(assistant -> {
                    List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                    for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                        responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), notExecuted));
                    }
                    return ToolResponseMessage.builder().responses(responses).build();
                });
    }
}

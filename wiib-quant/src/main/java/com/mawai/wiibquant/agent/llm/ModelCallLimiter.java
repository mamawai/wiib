package com.mawai.wiibquant.agent.llm;

import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.hook.EdgeHook;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.ai.chat.messages.Message;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 单次运行的模型调用保险丝：ReAct 是循环，模型可能陷进"查完行情又想查持仓、查完持仓又想查行情"
 * 的死转里一路烧 token。超过上限就直接结束本轮，把已有结果交出去。
 * <p>
 * 挂在工具边（ExecuteToolsHook）而不是模型节点：只有边的返回值 {@link Command} 能决定路由，
 * 节点 hook 改不了"下一步走哪"。计数存 state，因此跨 checkpoint 恢复仍然连续。
 */
@Slf4j
public class ModelCallLimiter implements EdgeHook.WrapCall<MessagesState<Message>> {

    /** ReAct 图里 END 的映射键（见 langgraph4j Agent：EdgeMappings.toEND("end")） */
    private static final String GOTO_END = "end";
    /** 最终 state 里可读到本轮模型调用数——调用方据此判断是否被保险丝提前收束 */
    public static final String CALL_COUNT_KEY = "model_call_count";

    private final int runLimit;

    public ModelCallLimiter(int runLimit) {
        this.runLimit = runLimit;
    }

    @Override
    public CompletableFuture<Command> applyWrap(String nodeId, MessagesState<Message> state,
                                                 RunnableConfig config, AsyncCommandAction<MessagesState<Message>> action) {
        int calls = state.<Number>value(CALL_COUNT_KEY).map(Number::intValue).orElse(0) + 1;
        if (calls >= runLimit) {
            log.warn("[CallLimit] 模型调用达上限 {}，本轮提前结束", runLimit);
            return CompletableFuture.completedFuture(new Command(GOTO_END, Map.of(CALL_COUNT_KEY, calls)));
        }
        return action.apply(state, config).thenApply(command -> command.withMergedUpdate(Map.of(CALL_COUNT_KEY, calls)));
    }
}

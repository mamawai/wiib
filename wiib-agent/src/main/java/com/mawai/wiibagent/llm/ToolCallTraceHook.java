package com.mawai.wiibagent.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.hook.EdgeHook;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工具调用全量轨迹：挂在工具执行边上，模型每轮想调的工具（含 klines/indicators 等数据工具——
 * 它们是无状态共享 bean，自己没有记录点）都记下 名字+参数。每次运行 new 一个。
 * 收集器活在调用方手里而非图 state 里：超时 cancel 打断图执行时，已发生的记录仍保得住。
 */
public class ToolCallTraceHook implements EdgeHook.WrapCall<MessagesState<Message>> {

    /** 图执行线程写、调用方在 cancel 后读——COW 挡住这对竞争（写入≤模型调用上限次，成本可忽略） */
    private final List<JSONObject> calls = new CopyOnWriteArrayList<>();

    public List<JSONObject> calls() {
        return List.copyOf(calls);
    }

    @Override
    public CompletableFuture<Command> applyWrap(String nodeId, MessagesState<Message> state,
                                                RunnableConfig config, AsyncCommandAction<MessagesState<Message>> action) {
        state.lastMessage().ifPresent(msg -> {
            if (msg instanceof AssistantMessage am && am.hasToolCalls()) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    JSONObject row = new JSONObject().fluentPut("tool", tc.name());
                    try {
                        row.put("args", JSON.parseObject(tc.arguments()));
                    } catch (Exception ignore) {
                        // 参数不是合法 JSON 就只留工具名——轨迹的价值在"调了什么"，参数是锦上添花
                    }
                    calls.add(row);
                }
            }
        });
        return action.apply(state, config);
    }
}

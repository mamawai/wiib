package com.mawai.wiibagent.chat;

import org.springframework.ai.chat.model.ToolContext;

/**
 * 工具执行期的会话号传递：放在图 state 里走框架的 ToolContext。
 * langgraph4j 执行工具时把整个 state 作为 ToolContext 交给每个工具，工具方法声明一个 ToolContext 参数就读得到
 * （Spring AI 不把它放进 schema，模型看不见也填不了）。
 * <p>
 * 调用方在图输入里放 {@link #SESSION_KEY}（见 ChatTurnRunner.streamSummarizer），工具方法体用 {@link #sessionId} 读。
 * 不在图里跑（单测直接调方法）时没带这个键，读到 null。
 */
public final class ToolRunContext {

    /** 图 state / ToolContext 里的会话号键 */
    public static final String SESSION_KEY = "session_id";

    private ToolRunContext() {
    }

    /** 工具方法体内读当前会话号；没带就是 null */
    public static String sessionId(ToolContext context) {
        return (String) context.getContext().get(SESSION_KEY);
    }
}

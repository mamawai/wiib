package com.mawai.wiibquant.agent.chat;

/**
 * 工具执行期的会话号传递。
 * <p>
 * 为什么要 ThreadLocal 而不是参数或 ToolContext：框架的 {@code ChatService.execute}
 * 签名里没有 RunnableConfig，工具方法体拿不到 sessionId；而 ToolContext 是建图时
 * 算死的静态值（见 ResilientChatService），塞不进请求级数据。
 * <p>
 * 安全性依据：langgraph4j 的工具执行链全程同线程无线程池
 * （SpringAIToolService 里是普通 for 循环 + 同步 call + completedFuture），
 * 所以在 {@code action.apply(...)} 之前设、之后清是可靠的。
 * <b>前提是 hook 必须直接调 action.apply，不能先 thenCompose——那样就换线程了。</b>
 */
public final class ToolRunContext {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    private ToolRunContext() {
    }

    static void set(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    static void clear() {
        SESSION_ID.remove();
    }

    /** 工具方法体内读当前会话号；不在工具执行栈里时返回 null。 */
    public static String sessionId() {
        return SESSION_ID.get();
    }
}

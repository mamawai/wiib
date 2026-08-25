package com.mawai.wiibquant.agent.chat;

/**
 * 工具执行期的会话号传递。
 * 用 ThreadLocal，这么写为了工具方法体拿到请求级 sessionId
 * （ChatService.execute 签名没有 RunnableConfig，ToolContext 是建图时算死的静态值）。
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

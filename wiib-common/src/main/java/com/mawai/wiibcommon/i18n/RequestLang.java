package com.mawai.wiibcommon.i18n;

import com.mawai.wiibcommon.enums.AgentLang;

/**
 * 当次请求的<b>界面语言</b>，由 {@link RequestLangFilter} 从 {@code X-Lang} 头填进来。
 * <p>
 * <b>为什么不查 user.lang 列</b>：{@link AgentLang} 说得明白——前端 localStorage 是语言的唯一
 * 事实源，服务端那一列只决定 AI 产出。而报错里有一大半发生在<b>没登录</b>的时候（登录失败、
 * 邀请码无效、限流），那时候根本没有 userId 可查。头是唯一在所有场景下都在的来源。
 * <p>
 * <b>为什么用 ThreadLocal</b>：报错抛在 service 深处，异常一路冒到全局处理器才渲染成文案；
 * 沿途把语言当参数传一遍要动上百个方法签名，而它跟业务毫无关系。
 */
public final class RequestLang {

    private static final ThreadLocal<AgentLang> CURRENT = new ThreadLocal<>();

    private RequestLang() {
    }

    /** 没设过（非 HTTP 线程：定时任务、虚拟线程里的异步活）一律中文——回落中文即维持存量行为 */
    public static AgentLang current() {
        AgentLang lang = CURRENT.get();
        return lang == null ? AgentLang.ZH : lang;
    }

    /** 归 {@link RequestLangFilter} 用；单测里要造某门语言的现场也用它，用完记得 {@link #clear()} */
    public static void set(AgentLang lang) {
        CURRENT.set(lang);
    }

    public static void clear() {
        CURRENT.remove();
    }
}

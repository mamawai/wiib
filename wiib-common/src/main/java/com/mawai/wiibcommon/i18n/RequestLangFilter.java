package com.mawai.wiibcommon.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 把 {@code X-Lang} 头填进 {@link RequestLang}，供界面文案渲染。三模块共享（均 scan 到
 * {@code com.mawai.wiibcommon}）。
 * <p>
 * 排在最前：错误文案在异常链上任何一环都可能要用，包括别的过滤器（鉴权）抛出来的那些。
 * <p>
 * 头认不出或没带（第三方调用、健康检查）回落中文，见 {@link AgentLang#of}。
 * {@code finally} 清理是必须的——容器线程是复用的，不清就会把上一个请求的语言带给下一个人。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLangFilter extends OncePerRequestFilter {

    /** 与前端 api/index.ts 的请求头同名；值就是 AgentLang.code()（zh/en） */
    public static final String HEADER = "X-Lang";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RequestLang.set(AgentLang.of(request.getHeader(HEADER)));
        try {
            chain.doFilter(request, response);
        } finally {
            RequestLang.clear();
        }
    }
}

package com.mawai.wiibcommon.resolver;

import com.mawai.wiibcommon.annotation.CurrentUserId;

import cn.dev33.satoken.stp.StpUtil;
import org.jspecify.annotations.NonNull;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * @CurrentUserId 参数解析：从 Sa-Token 取当前登录用户 ID，按参数类型返回对应形态。
 * 未登录时 StpUtil 会抛 NotLoginException，交由全局处理器转 401；optional=true 的形参未登录给 null。
 */
public class CurrentUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  @NonNull NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        CurrentUserId ann = parameter.getParameterAnnotation(CurrentUserId.class);
        if (ann != null && ann.optional() && !StpUtil.isLogin()) {
            return null;
        }
        Class<?> type = parameter.getParameterType();
        if (type == String.class) {
            return StpUtil.getLoginIdAsString();
        }
        if (type == Integer.class || type == int.class) {
            return StpUtil.getLoginIdAsInt();
        }
        // 默认按 Long（Long/long）
        return StpUtil.getLoginIdAsLong();
    }
}

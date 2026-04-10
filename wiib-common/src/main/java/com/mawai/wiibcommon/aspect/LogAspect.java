package com.mawai.wiibcommon.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;

/**
 * 轻量日志切面
 */
@Slf4j
@Aspect
@Component
@Order(1)
public class LogAspect {

    @Around("execution(public * com.mawai..controller.*.*(..)) && !within(com.mawai..controller.MonitorController)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        long start = System.currentTimeMillis();

        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String url = "";
        String httpMethod = "";
        String ip = "";

        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            url = req.getRequestURL().toString();
            httpMethod = req.getMethod();
            ip = realIp(req);
        }

        String className = point.getSignature().getDeclaringTypeName();
        String methodName = point.getSignature().getName();

        Object result;
        try {
            result = point.proceed();
            long cost = System.currentTimeMillis() - start;

            log.info("""

                            ========== Request ==========
                            URL: {} {}
                            IP: {}
                            Method: {}.{}
                            Args: {}
                            Cost: {}ms
                            =============================""",
                    httpMethod, url, ip, className, methodName, Arrays.toString(point.getArgs()), cost);
            log.debug("Result: {}", result);

            return result;
        } catch (Throwable e) {
            long cost = System.currentTimeMillis() - start;

            log.error("""
                            
                            ========== Request Error ==========
                            URL: {} {}
                            IP: {}
                            Method: {}.{}
                            Args: {}
                            Error: {}
                            Cost: {}ms
                            ===================================""",
                    httpMethod, url, ip, className, methodName, Arrays.toString(point.getArgs()), e.getMessage(), cost);

            throw e;
        }
    }

    private static String realIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) return ip.split(",")[0].trim();
        ip = req.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty()) return ip;
        return req.getRemoteAddr();
    }
}

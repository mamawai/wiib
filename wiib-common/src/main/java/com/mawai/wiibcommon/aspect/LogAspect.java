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

    @Around("execution(public * com.mawai..controller.*.*(..))")
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
            ip = req.getRemoteAddr();
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
                            Result: {}
                            Cost: {}ms
                            =============================""",
                    httpMethod, url, ip, className, methodName, Arrays.toString(point.getArgs()), result, cost);

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
}

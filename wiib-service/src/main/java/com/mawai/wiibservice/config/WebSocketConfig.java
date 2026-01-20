package com.mawai.wiibservice.config;

import cn.dev33.satoken.stp.StpUtil;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket配置（STOMP协议）
 * 用于股票行情实时推送
 *
 * <p>技术特性：</p>
 * <ul>
 *   <li>使用虚拟线程处理心跳任务，支持高并发连接</li>
 *   <li>支持订阅机制（/topic/stock/{code}）</li>
 *   <li>支持 SockJS 降级方案</li>
 *   <li>10秒心跳检测，自动断开无响应连接</li>
 *   <li>连接数限制，防止资源耗尽</li>
 *   <li>可选的强制认证</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** 最大连接数 */
    @Value("${websocket.max-connections:10000}")
    private int maxConnections;

    /** 是否强制认证 */
    @Value("${websocket.require-auth:false}")
    private boolean requireAuth;

    /** 当前连接数 */
    private static final AtomicInteger connectionCount = new AtomicInteger(0);

    /** 维护session订阅关系 */
    private static final Map<String, String> sessionSubscriptions = new ConcurrentHashMap<>();

    /** 维护session最后活跃时间 */
    private static final Map<String, Long> sessionLastActive = new ConcurrentHashMap<>();

    /** 会话超时时间（毫秒） */
    private static final long SESSION_TIMEOUT_MS = 60000;

    /**
     * 虚拟线程任务调度器
     * 用于WebSocket心跳检测
     */
    @Bean("taskScheduler")
    public TaskScheduler taskScheduler() {
        // 虚拟线程工厂
        var virtualThreadFactory = Thread.ofVirtual()
                .name("ws-heartbeat-", 0)
                .factory();

        // 使用虚拟线程的调度器（poolSize=1，只需1个平台线程作为调度器）
        var virtualScheduler = Executors.newScheduledThreadPool(1, virtualThreadFactory);

        ConcurrentTaskScheduler scheduler = new ConcurrentTaskScheduler(virtualScheduler);

        // 注册shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("关闭WebSocket心跳调度器");
            virtualScheduler.shutdown();
            try {
                if (!virtualScheduler.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("心跳调度器未能在10秒内关闭，强制关闭");
                    virtualScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("等待心跳调度器关闭时被中断", e);
                virtualScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }, "ws-scheduler-shutdown"));

        return scheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 启用简单消息代理
        // /topic 用于广播消息（股票行情推送）
        // /queue 用于点对点消息（预留）
        config.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(taskScheduler())
                .setHeartbeatValue(new long[]{10000, 10000}); // 10秒心跳

        // 客户端发送消息的前缀（当前系统只推送不接收，预留）
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/quotes")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
                        // 连接数限制检查
                        if (connectionCount.get() >= maxConnections) {
                            log.warn("WebSocket连接数已达上限: {}", maxConnections);
                            return false;
                        }

                        // 从URL参数获取token
                        String token = null;
                        if (request instanceof ServletServerHttpRequest servletRequest) {
                            token = servletRequest.getServletRequest().getParameter("token");
                        }

                        // 强制认证检查
                        if (requireAuth) {
                            if (token == null || token.isEmpty()) {
                                log.warn("WebSocket连接被拒绝：缺少认证token");
                                return false;
                            }
                            try {
                                // 验证Sa-Token
                                Object loginId = StpUtil.getLoginIdByToken(token);
                                if (loginId == null) {
                                    log.warn("WebSocket连接被拒绝：无效的token");
                                    return false;
                                }
                                attributes.put("userId", loginId);
                            } catch (Exception e) {
                                log.warn("WebSocket连接被拒绝：token验证失败", e);
                                return false;
                            }
                        }

                        if (token != null && !token.isEmpty()) {
                            attributes.put("token", token);
                        }
                        return true;
                    }

                    @Override
                    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                               WebSocketHandler wsHandler, Exception exception) {
                        // 握手后处理
                    }
                })
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor != null && accessor.getCommand() != null) {
                    String sessionId = accessor.getSessionId();

                    switch (accessor.getCommand()) {
                        case CONNECT:
                            connectionCount.incrementAndGet();
                            sessionLastActive.put(sessionId, System.currentTimeMillis());
                            log.info("WebSocket连接建立: sessionId={}, 当前连接数={}", sessionId, connectionCount.get());

                            String token = (String) accessor.getSessionAttributes().get("token");
                            if (token != null) {
                                accessor.setUser(new Principal() {
                                    @Override
                                    public String getName() {
                                        return sessionId;
                                    }
                                });
                            }
                            break;

                        case SUBSCRIBE:
                            String destination = accessor.getDestination();
                            if (destination != null) {
                                sessionSubscriptions.put(sessionId, destination);
                                sessionLastActive.put(sessionId, System.currentTimeMillis());
                                log.info("订阅: sessionId={}, destination={}", sessionId, destination);
                            }
                            break;

                        case UNSUBSCRIBE:
                            sessionSubscriptions.remove(sessionId);
                            log.info("取消订阅: sessionId={}", sessionId);
                            break;

                        case DISCONNECT:
                            connectionCount.decrementAndGet();
                            sessionSubscriptions.remove(sessionId);
                            sessionLastActive.remove(sessionId);
                            log.info("WebSocket连接断开: sessionId={}, 当前连接数={}", sessionId, connectionCount.get());
                            break;

                        default:
                            sessionLastActive.put(sessionId, System.currentTimeMillis());
                            break;
                    }
                }

                return message;
            }
        });
    }

    /**
     * 定期清理超时的会话（每分钟执行）
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupStaleSessions() {
        long now = System.currentTimeMillis();
        int cleaned = 0;

        for (Map.Entry<String, Long> entry : sessionLastActive.entrySet()) {
            if (now - entry.getValue() > SESSION_TIMEOUT_MS) {
                String sessionId = entry.getKey();
                sessionSubscriptions.remove(sessionId);
                sessionLastActive.remove(sessionId);
                connectionCount.decrementAndGet();
                cleaned++;
            }
        }

        if (cleaned > 0) {
            log.info("清理超时会话: {} 个, 当前连接数: {}", cleaned, connectionCount.get());
        }
    }

    /** 获取session订阅关系 */
    public static Map<String, String> getSessionSubscriptions() {
        return sessionSubscriptions;
    }

    /** 获取当前连接数 */
    public static int getConnectionCount() {
        return connectionCount.get();
    }
}

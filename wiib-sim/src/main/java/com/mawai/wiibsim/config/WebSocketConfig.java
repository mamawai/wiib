package com.mawai.wiibsim.config;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.context.event.EventListener;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket配置（STOMP协议）
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${websocket.max-connections:10000}")
    private int maxConnections;

    @Value("${websocket.heartbeat-interval:15}")
    private int heartbeatInterval;

    private static final AtomicInteger connectionCount = new AtomicInteger(0);
    private static final Set<String> connectedSessions = ConcurrentHashMap.newKeySet();

    @Bean("wsHeartbeatTaskScheduler")
    public TaskScheduler wsHeartbeatTaskScheduler() {
        var virtualThreadFactory = Thread.ofVirtual().name("ws-heartbeat-", 0).factory();
        var virtualScheduler = Executors.newScheduledThreadPool(1, virtualThreadFactory);
        return new ConcurrentTaskScheduler(virtualScheduler);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        long heartbeatMs = heartbeatInterval * 1000L;
        config.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(wsHeartbeatTaskScheduler())
                .setHeartbeatValue(new long[]{heartbeatMs, heartbeatMs});
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/quotes")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
                                                   @NonNull ServerHttpResponse response,
                                                   @NonNull WebSocketHandler wsHandler,
                                                   @NonNull Map<String, Object> attributes) {
                        if (connectionCount.get() >= maxConnections) {
                            log.warn("WebSocket连接数已达上限: {}", maxConnections);
                            return false;
                        }
                        return true;
                    }

                    @Override
                    public void afterHandshake(@NonNull ServerHttpRequest request,
                                               @NonNull ServerHttpResponse response,
                                               @NonNull WebSocketHandler wsHandler,
                                               Exception exception) {
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
                            if (connectedSessions.add(sessionId)) {
                                connectionCount.incrementAndGet();
                            }
                            log.info("WebSocket连接: sessionId={}, 连接数={}", sessionId, connectionCount.get());
                            // 身份认在 CONNECT 帧的 token 头上（握手那层看不到它）。
                            // Principal 必须是 userId：convertAndSendToUser 靠它把点对点消息投到具体连接。
                            String token = accessor.getFirstNativeHeader("token");
                            if (token != null && !token.isEmpty()) {
                                try {
                                    Object loginId = StpUtil.getLoginIdByToken(token);
                                    if (loginId != null) {
                                        String principal = String.valueOf(loginId);
                                        accessor.setUser(() -> principal);
                                    }
                                } catch (Exception e) {
                                    // 无效/过期 token 本身不抛异常（换不出 loginId 而已），能到这儿的
                                    // 是 sa-token 背后的存储出了问题。静默吞掉的话线上表现是"所有人突然
                                    // 都变游客、通知全静默"，没有任何线索
                                    log.warn("WebSocket认身份失败 sessionId={} msg={}", sessionId, e.toString());
                                }
                            }
                            break;
                        case SUBSCRIBE:
                            log.info("订阅: sessionId={}, dest={}", sessionId, accessor.getDestination());
                            break;
                        case UNSUBSCRIBE:
                            log.info("取消订阅: sessionId={}", sessionId);
                            break;
                        case DISCONNECT:
                            log.info("WebSocket断开(STOMP): sessionId={}", sessionId);
                            break;
                        default:
                            break;
                    }
                }
                return message;
            }
        });
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        if (connectedSessions.remove(event.getSessionId())) {
            connectionCount.decrementAndGet();
        }
        log.info("WebSocket断开: sessionId={}, 连接数={}", event.getSessionId(), connectionCount.get());
    }
}

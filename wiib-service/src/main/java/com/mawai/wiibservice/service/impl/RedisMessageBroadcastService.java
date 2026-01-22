package com.mawai.wiibservice.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * Redis消息广播服务
 * 用于WebSocket集群消息同步
 *
 * <p>工作原理：</p>
 * <ol>
 *   <li>发送消息时，先发布到Redis频道</li>
 *   <li>所有实例订阅该频道，收到消息后推送给本地WebSocket连接</li>
 *   <li>实现集群环境下的消息广播</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisMessageBroadcastService implements MessageListener {

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisMessageListenerContainer redisMessageListenerContainer;

    /** Redis频道前缀 */
    private static final String CHANNEL_PREFIX = "ws:broadcast:";

    @PostConstruct
    public void init() {
        // 订阅股票行情广播频道
        ChannelTopic stockTopic = new ChannelTopic(CHANNEL_PREFIX + "stock");
        redisMessageListenerContainer.addMessageListener(this, stockTopic);
        log.info("已订阅Redis广播频道: {}", stockTopic.getTopic());
    }

    /**
     * 广播股票行情消息
     * 发布到Redis，所有实例都会收到并推送给本地WebSocket连接
     *
     * @param stockCode 股票代码
     * @param message   JSON消息内容
     */
    public void broadcastStockQuote(String stockCode, String message) {
        try {
            // 消息格式：stockCode|message
            String payload = stockCode + "|" + message;
            redisTemplate.convertAndSend(CHANNEL_PREFIX + "stock", payload);
            log.info("广播股票行情: {}", stockCode);
        } catch (Exception e) {
            log.error("广播股票行情失败: {}", stockCode, e);
        }
    }

    /**
     * 接收Redis消息并推送到本地WebSocket连接
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String payload = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            int separatorIndex = payload.indexOf('|');
            if (separatorIndex <= 0) {
                log.warn("无效的广播消息格式: {}", payload);
                return;
            }

            String stockCode = payload.substring(0, separatorIndex);
            String jsonMessage = payload.substring(separatorIndex + 1);

            // 推送到本地WebSocket连接
            messagingTemplate.convertAndSend("/topic/quote/" + stockCode, jsonMessage);
            log.info("本地推送股票行情: {}", stockCode);
        } catch (Exception e) {
            log.error("处理Redis广播消息失败", e);
        }
    }
}


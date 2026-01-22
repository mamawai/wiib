package com.mawai.wiibservice.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mawai.wiibcommon.event.AssetChangeEvent;
import com.mawai.wiibcommon.event.OrderStatusEvent;
import com.mawai.wiibcommon.event.PositionChangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;

/**
 * WebSocket事件监听器
 * 监听Redis Pub/Sub事件，推送到WebSocket客户端
 *
 * <p>Google级设计要点：</p>
 * <ul>
 *   <li>使用PatternTopic实现用户维度的动态订阅</li>
 *   <li>支持多实例部署，每个实例都会收到事件</li>
 *   <li>异步处理，不阻塞事件发布者</li>
 *   <li>错误隔离，单个事件失败不影响其他事件</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final RedisMessageListenerContainer redisMessageListenerContainer;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        // 订阅所有用户的资产变化事件
        redisMessageListenerContainer.addMessageListener(
                new AssetChangeMessageListener(),
                new PatternTopic("event:asset:change:*")
        );

        // 订阅所有用户的持仓变化事件
        redisMessageListenerContainer.addMessageListener(
                new PositionChangeMessageListener(),
                new PatternTopic("event:position:change:*")
        );

        // 订阅所有用户的订单状态事件
        redisMessageListenerContainer.addMessageListener(
                new OrderStatusMessageListener(),
                new PatternTopic("event:order:status:*")
        );

        log.info("WebSocket事件监听器初始化完成");
    }

    /**
     * 资产变化事件监听器
     */
    private class AssetChangeMessageListener implements MessageListener {
        @Override
        public void onMessage(Message message, byte[] pattern) {
            try {
                String json = new String(message.getBody());
                AssetChangeEvent event = objectMapper.readValue(json, AssetChangeEvent.class);

                // 推送到用户专属的WebSocket topic
                String destination = "/topic/user/" + event.getUserId() + "/asset";
                messagingTemplate.convertAndSend(destination, event);

                log.info("WebSocket推送资产变化: userId={}, totalAssets={}",
                        event.getUserId(), event.getTotalAssets());
            } catch (IOException e) {
                log.error("处理资产变化事件失败", e);
            } catch (Exception e) {
                log.error("推送WebSocket消息失败", e);
            }
        }
    }

    /**
     * 持仓变化事件监听器
     */
    private class PositionChangeMessageListener implements MessageListener {
        @Override
        public void onMessage(Message message, byte[] pattern) {
            try {
                String json = new String(message.getBody());
                PositionChangeEvent event = objectMapper.readValue(json, PositionChangeEvent.class);

                // 推送到用户专属的WebSocket topic
                String destination = "/topic/user/" + event.getUserId() + "/position";
                messagingTemplate.convertAndSend(destination, event);

                log.info("WebSocket推送持仓变化: userId={}, stockCode={}, quantity={}",
                        event.getUserId(), event.getStockCode(), event.getQuantity());
            } catch (IOException e) {
                log.error("处理持仓变化事件失败", e);
            } catch (Exception e) {
                log.error("推送WebSocket消息失败", e);
            }
        }
    }

    /**
     * 订单状态事件监听器
     */
    private class OrderStatusMessageListener implements MessageListener {
        @Override
        public void onMessage(Message message, byte[] pattern) {
            try {
                String json = new String(message.getBody());
                OrderStatusEvent event = objectMapper.readValue(json, OrderStatusEvent.class);

                // 推送到用户专属的WebSocket topic
                String destination = "/topic/user/" + event.getUserId() + "/order";
                messagingTemplate.convertAndSend(destination, event);

                log.info("WebSocket推送订单状态: userId={}, orderId={}, newStatus={}",
                        event.getUserId(), event.getOrderId(), event.getNewStatus());
            } catch (IOException e) {
                log.error("处理订单状态事件失败", e);
            } catch (Exception e) {
                log.error("推送WebSocket消息失败", e);
            }
        }
    }
}

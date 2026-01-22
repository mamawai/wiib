package com.mawai.wiibservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mawai.wiibcommon.event.AssetChangeEvent;
import com.mawai.wiibcommon.event.OrderStatusEvent;
import com.mawai.wiibcommon.event.PositionChangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 事件发布服务
 * 使用Redis Pub/Sub实现事件驱动架构
 *
 * <p>Google级设计要点：</p>
 * <ul>
 *   <li>解耦业务逻辑和通知逻辑</li>
 *   <li>支持多订阅者（WebSocket、日志、审计等）</li>
 *   <li>异步非阻塞，不影响主业务性能</li>
 *   <li>用户维度的Channel，精准推送</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** 用户资产变化事件频道 */
    private static final String ASSET_CHANGE_CHANNEL = "event:asset:change:";

    /** 持仓变化事件频道 */
    private static final String POSITION_CHANGE_CHANNEL = "event:position:change:";

    /** 订单状态变化事件频道 */
    private static final String ORDER_STATUS_CHANNEL = "event:order:status:";

    /**
     * 发布用户资产变化事件
     *
     * @param event 资产变化事件
     */
    public void publishAssetChange(AssetChangeEvent event) {
        if (event == null || event.getUserId() == null) {
            log.warn("无效的AssetChangeEvent");
            return;
        }

        try {
            String channel = ASSET_CHANGE_CHANNEL + event.getUserId();
            String message = objectMapper.writeValueAsString(event);
            stringRedisTemplate.convertAndSend(channel, message);

            log.info("发布资产变化事件: userId={}, reason={}, totalAssets={}",
                    event.getUserId(), event.getReason(), event.getTotalAssets());
        } catch (JsonProcessingException e) {
            log.error("序列化AssetChangeEvent失败", e);
        }
    }

    /**
     * 发布持仓变化事件
     *
     * @param event 持仓变化事件
     */
    public void publishPositionChange(PositionChangeEvent event) {
        if (event == null || event.getUserId() == null) {
            log.warn("无效的PositionChangeEvent");
            return;
        }

        try {
            String channel = POSITION_CHANGE_CHANNEL + event.getUserId();
            String message = objectMapper.writeValueAsString(event);
            stringRedisTemplate.convertAndSend(channel, message);

            log.info("发布持仓变化事件: userId={}, stockCode={}, changeType={}, quantity={}",
                    event.getUserId(), event.getStockCode(), event.getChangeType(), event.getQuantity());
        } catch (JsonProcessingException e) {
            log.error("序列化PositionChangeEvent失败", e);
        }
    }

    /**
     * 发布订单状态变化事件
     *
     * @param event 订单状态事件
     */
    public void publishOrderStatus(OrderStatusEvent event) {
        if (event == null || event.getUserId() == null) {
            log.warn("无效的OrderStatusEvent");
            return;
        }

        try {
            String channel = ORDER_STATUS_CHANNEL + event.getUserId();
            String message = objectMapper.writeValueAsString(event);
            stringRedisTemplate.convertAndSend(channel, message);

            log.info("发布订单状态事件: userId={}, orderId={}, oldStatus={}, newStatus={}",
                    event.getUserId(), event.getOrderId(), event.getOldStatus(), event.getNewStatus());
        } catch (JsonProcessingException e) {
            log.error("序列化OrderStatusEvent失败", e);
        }
    }

    /**
     * 批量发布资产和持仓变化事件
     * 用于订单成交等同时改变资产和持仓的场景
     */
    public void publishAssetAndPositionChange(AssetChangeEvent assetEvent, PositionChangeEvent positionEvent) {
        publishAssetChange(assetEvent);
        publishPositionChange(positionEvent);
    }
}

package com.mawai.wiibservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mawai.wiibcommon.dto.OrderResponse;
import com.mawai.wiibcommon.dto.OrderRequest;
import com.mawai.wiibcommon.entity.Order;

import java.time.LocalDate;
import java.util.List;

/**
 * 订单服务接口
 */
public interface OrderService extends IService<Order> {

    /**
     * 买入下单
     */
    OrderResponse buy(Long userId, OrderRequest request);

    /**
     * 卖出下单
     */
    OrderResponse sell(Long userId, OrderRequest request);

    /**
     * 取消订单（仅限价单且状态为PENDING）
     */
    OrderResponse cancel(Long userId, Long orderId);

    /**
     * 查询用户订单列表
     */
    List<OrderResponse> getUserOrders(Long userId, String status, int limit);

    /**
     * 触发限价单（定时任务调用）
     */
    void triggerLimitOrders();

    /**
     * 执行已触发的限价单（定时任务调用）
     */
    void executeTriggeredOrders();

    /**
     * 过期限价单处理（定时任务调用）
     */
    void expireLimitOrders();
}

package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 原子更新订单状态（CAS操作）
     * 只有当订单当前状态为expectedStatus时才会更新为newStatus
     *
     * @param orderId        订单ID
     * @param expectedStatus 期望的当前状态
     * @param newStatus      要更新的新状态
     * @return 影响行数（0表示状态已被其他操作改变）
     */
    @Update("UPDATE \"order\" SET status = #{newStatus}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = #{expectedStatus}")
    int casUpdateStatus(@Param("orderId") Long orderId,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus);

    /**
     * 原子更新限价单为已触发状态（CAS操作）
     * 同时记录触发价格和触发时间
     *
     * @param orderId      订单ID
     * @param triggerPrice 触发价格
     * @return 影响行数（0表示状态已被其他操作改变）
     */
    @Update("UPDATE \"order\" SET status = 'TRIGGERED', trigger_price = #{triggerPrice}, " +
            "triggered_at = NOW(), updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'PENDING'")
    int casUpdateToTriggered(@Param("orderId") Long orderId,
            @Param("triggerPrice") BigDecimal triggerPrice);

    /**
     * 原子更新限价单为已成交状态（CAS操作）
     * 同时更新成交价格、成交金额、手续费等信息
     *
     * @param orderId      订单ID
     * @param filledPrice  成交价格
     * @param filledAmount 成交金额
     * @param commission   手续费
     * @return 影响行数（0表示状态已被其他操作改变）
     */
    @Update("UPDATE \"order\" SET status = 'FILLED', filled_price = #{filledPrice}, " +
            "filled_amount = #{filledAmount}, commission = #{commission}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'TRIGGERED'")
    int casUpdateToFilled(@Param("orderId") Long orderId,
            @Param("filledPrice") BigDecimal filledPrice,
            @Param("filledAmount") BigDecimal filledAmount,
            @Param("commission") BigDecimal commission);
}

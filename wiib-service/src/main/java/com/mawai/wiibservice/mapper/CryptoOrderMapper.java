package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.CryptoOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface CryptoOrderMapper extends BaseMapper<CryptoOrder> {

    @Select("SELECT COALESCE(SUM(filled_amount - commission), 0) FROM crypto_order " +
            "WHERE user_id = #{userId} AND status = 'SETTLING'")
    BigDecimal sumSettlingAmount(@Param("userId") Long userId);

    @Select("SELECT user_id, COALESCE(SUM(filled_amount - commission), 0) AS amount " +
            "FROM crypto_order WHERE status = 'SETTLING' GROUP BY user_id")
    List<Map<String, Object>> sumAllSettlingAmounts();

    @Update("UPDATE crypto_order SET status = #{newStatus}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = #{expectedStatus}")
    int casUpdateStatus(@Param("orderId") Long orderId,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("newStatus") String newStatus);

    @Update("UPDATE crypto_order SET status = 'TRIGGERED', trigger_price = #{triggerPrice}, " +
            "triggered_at = NOW(), updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'PENDING'")
    int casUpdateToTriggered(@Param("orderId") Long orderId,
                             @Param("triggerPrice") BigDecimal triggerPrice);

    @Update("UPDATE crypto_order SET status = 'FILLED', filled_price = #{filledPrice}, " +
            "filled_amount = #{filledAmount}, commission = #{commission}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'TRIGGERED'")
    int casUpdateToFilled(@Param("orderId") Long orderId,
                          @Param("filledPrice") BigDecimal filledPrice,
                          @Param("filledAmount") BigDecimal filledAmount,
                          @Param("commission") BigDecimal commission);

    @Update("UPDATE crypto_order SET status = 'SETTLING', filled_price = #{filledPrice}, " +
            "filled_amount = #{filledAmount}, commission = #{commission}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'TRIGGERED'")
    int casUpdateToSettling(@Param("orderId") Long orderId,
                            @Param("filledPrice") BigDecimal filledPrice,
                            @Param("filledAmount") BigDecimal filledAmount,
                            @Param("commission") BigDecimal commission);

    @Update("UPDATE crypto_order SET status = 'CANCELLED', updated_at = NOW() " +
            "WHERE user_id = #{userId} AND status IN ('PENDING', 'TRIGGERED')")
    int cancelOpenOrdersByUserId(@Param("userId") Long userId);
}

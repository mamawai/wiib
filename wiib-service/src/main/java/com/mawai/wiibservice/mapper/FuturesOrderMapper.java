package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.FuturesOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface FuturesOrderMapper extends BaseMapper<FuturesOrder> {

    /** CAS状态转换 */
    @Update("UPDATE futures_order SET status = #{newStatus}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = #{oldStatus}")
    int casUpdateStatus(@Param("orderId") Long orderId,
                        @Param("oldStatus") String oldStatus,
                        @Param("newStatus") String newStatus);

    /** CAS更新为FILLED */
    @Update("UPDATE futures_order SET " +
            "status = 'FILLED', " +
            "filled_price = #{filledPrice}, " +
            "filled_amount = #{filledAmount}, " +
            "commission = #{commission}, " +
            "margin_amount = #{marginAmount}, " +
            "realized_pnl = #{realizedPnl}, " +
            "updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'TRIGGERED'")
    int casUpdateToFilled(@Param("orderId") Long orderId,
                          @Param("filledPrice") BigDecimal filledPrice,
                          @Param("filledAmount") BigDecimal filledAmount,
                          @Param("commission") BigDecimal commission,
                          @Param("marginAmount") BigDecimal marginAmount,
                          @Param("realizedPnl") BigDecimal realizedPnl);

    /** CAS标记为TRIGGERED */
    @Update("UPDATE futures_order SET status = 'TRIGGERED', filled_price = #{triggerPrice}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'PENDING'")
    int casUpdateToTriggered(@Param("orderId") Long orderId, @Param("triggerPrice") BigDecimal triggerPrice);

    /** 用户所有已平仓单的已实现盈亏（已扣手续费） */
    @Select("SELECT COALESCE(SUM(realized_pnl - COALESCE(commission, 0)), 0) FROM futures_order " +
            "WHERE user_id = #{userId} AND realized_pnl IS NOT NULL")
    BigDecimal sumRealizedPnl(@Param("userId") Long userId);
}

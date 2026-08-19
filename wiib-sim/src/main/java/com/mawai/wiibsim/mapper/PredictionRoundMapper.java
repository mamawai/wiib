package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.PredictionRound;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface PredictionRoundMapper extends BaseMapper<PredictionRound> {

    @Insert("INSERT INTO prediction_round (window_start, start_price, status, created_at, updated_at) " +
            "VALUES (#{windowStart}, #{startPrice}, 'OPEN', NOW(), NOW()) " +
            "ON CONFLICT (window_start) DO NOTHING")
    int insertIfAbsent(@Param("windowStart") long windowStart,
                       @Param("startPrice") BigDecimal startPrice);

    @Update("UPDATE prediction_round SET status = 'SETTLED', end_price = #{endPrice}, " +
            "outcome = #{outcome}, updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'LOCKED'")
    int casSettleRound(@Param("id") Long id,
                       @Param("endPrice") BigDecimal endPrice,
                       @Param("outcome") String outcome);

    @Update("UPDATE prediction_round SET start_price = #{startPrice}, updated_at = NOW() " +
            "WHERE window_start = #{windowStart} AND status = 'OPEN'")
    int updateStartPrice(@Param("windowStart") long windowStart,
                         @Param("startPrice") BigDecimal startPrice);

    @Update("UPDATE prediction_round SET status = 'LOCKED', updated_at = NOW() " +
            "WHERE window_start = #{windowStart} AND status = 'OPEN'")
    int casLockRound(@Param("windowStart") long windowStart);

    /** 锁定后补开盘价：updateStartPrice 只认 OPEN，够不着已锁定的行；只补空值不覆盖已有价 */
    @Update("UPDATE prediction_round SET start_price = #{startPrice}, updated_at = NOW() " +
            "WHERE id = #{id} AND start_price IS NULL")
    int fillStartPrice(@Param("id") Long id, @Param("startPrice") BigDecimal startPrice);

    /** 作废：取不到价，end_price 留空，注单退本金 */
    @Update("UPDATE prediction_round SET status = 'SETTLED', outcome = 'VOID', updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'LOCKED'")
    int casVoidRound(@Param("id") Long id);

    /** 补结算巡检的取数：窗口早该结束、却还没结算完的回合（OPEN=lock 事件没到，LOCKED=结算没成） */
    @Select("SELECT * FROM prediction_round WHERE status IN ('OPEN', 'LOCKED') AND window_start < #{before} " +
            "ORDER BY window_start")
    List<PredictionRound> selectUnsettledBefore(@Param("before") long before);
}

package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantForecastVerification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuantForecastVerificationMapper extends BaseMapper<QuantForecastVerification> {

    @Select("SELECT * FROM quant_forecast_verification WHERE symbol = #{symbol} ORDER BY verified_at DESC LIMIT #{limit}")
    List<QuantForecastVerification> selectRecent(@Param("symbol") String symbol, @Param("limit") int limit);

    @Select("SELECT COUNT(CASE WHEN prediction_correct THEN 1 END)::float / NULLIF(COUNT(*), 0) " +
            "FROM quant_forecast_verification " +
            "WHERE symbol = #{symbol} AND verified_at > NOW() - INTERVAL '1 hour' * #{hours}")
    Double selectAccuracyRate(@Param("symbol") String symbol, @Param("hours") int hours);

    @Select("SELECT COUNT(*) FROM quant_forecast_verification WHERE cycle_id = #{cycleId}")
    int countByCycleId(@Param("cycleId") String cycleId);
}

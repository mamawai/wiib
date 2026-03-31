package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuantSignalDecisionMapper extends BaseMapper<QuantSignalDecision> {

    @Select("SELECT * FROM quant_signal_decision WHERE cycle_id = (SELECT cycle_id FROM quant_forecast_cycle WHERE symbol = #{symbol} ORDER BY forecast_time DESC LIMIT 1)")
    List<QuantSignalDecision> selectLatestBySymbol(@Param("symbol") String symbol);
}

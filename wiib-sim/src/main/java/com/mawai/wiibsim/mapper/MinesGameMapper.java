package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.MinesGame;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface MinesGameMapper extends BaseMapper<MinesGame> {

    @Select("SELECT COALESCE(SUM(payout - bet_amount), 0) FROM mines_game WHERE user_id = #{userId} AND status IN ('CASHED_OUT', 'EXPLODED')")
    BigDecimal sumNetProfit(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM mines_game WHERE user_id = #{userId} AND status IN ('CASHED_OUT', 'EXPLODED')")
    int countFinishedGames(@Param("userId") Long userId);

    /** 进行中的那一局（同一用户至多一条，靠 bet 前置校验 + 用户锁保证） */
    @Select("SELECT * FROM mines_game WHERE user_id = #{userId} AND status = 'PLAYING' LIMIT 1")
    MinesGame selectPlaying(@Param("userId") Long userId);
}

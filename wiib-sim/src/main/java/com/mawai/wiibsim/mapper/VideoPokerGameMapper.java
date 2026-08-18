package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.VideoPokerGame;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface VideoPokerGameMapper extends BaseMapper<VideoPokerGame> {

    @Select("SELECT COALESCE(SUM(payout - bet_amount), 0) FROM video_poker_game WHERE user_id = #{userId} AND status = 'SETTLED'")
    BigDecimal sumNetProfit(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM video_poker_game WHERE user_id = #{userId} AND status = 'SETTLED'")
    int countSettledGames(@Param("userId") Long userId);

    /**
     * 发牌完等着 draw 的那一局（同一用户至多一条，靠 bet 前置校验 + 用户锁保证）。
     * deck 非空是硬条件：没牌堆就补不了牌，那种行接着打只会 NPE，当它不存在让用户能开新局。
     */
    @Select("SELECT * FROM video_poker_game WHERE user_id = #{userId} AND status = 'DEALING' " +
            "AND deck IS NOT NULL LIMIT 1")
    VideoPokerGame selectDealing(@Param("userId") Long userId);
}

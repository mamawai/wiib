package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("video_poker_game")
public class VideoPokerGame {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private BigDecimal betAmount;
    private String initialCards;
    /** 本局洗好的整副 52 张（逗号分隔）：前 5 张就是 initialCards，draw 从第 6 张起补牌 */
    private String deck;
    private String heldPositions;
    private String finalCards;
    private String handRank;
    private BigDecimal multiplier;
    private BigDecimal payout;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

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
    private String heldPositions;
    private String finalCards;
    private String handRank;
    private BigDecimal multiplier;
    private BigDecimal payout;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

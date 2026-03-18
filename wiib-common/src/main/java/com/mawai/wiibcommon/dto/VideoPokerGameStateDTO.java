package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class VideoPokerGameStateDTO {
    private Long gameId;
    private BigDecimal betAmount;
    private List<String> cards;
    private List<Integer> heldPositions;
    private String handRank;
    private BigDecimal multiplier;
    private BigDecimal payout;
    private String phase;
    private BigDecimal balance;
}

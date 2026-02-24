package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MinesGameStateDTO {
    private Long gameId;
    private BigDecimal betAmount;
    private List<Integer> revealed;
    private List<Integer> minePositions;
    private String result;
    private BigDecimal currentMultiplier;
    private BigDecimal nextMultiplier;
    private BigDecimal potentialPayout;
    private BigDecimal payout;
    private String phase;
    private BigDecimal balance;
}

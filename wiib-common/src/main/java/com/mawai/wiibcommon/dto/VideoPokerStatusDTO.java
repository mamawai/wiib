package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class VideoPokerStatusDTO {
    private BigDecimal balance;
    private VideoPokerGameStateDTO activeGame;
}

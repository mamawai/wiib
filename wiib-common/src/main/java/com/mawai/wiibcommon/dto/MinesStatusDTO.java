package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MinesStatusDTO {
    private BigDecimal balance;
    private MinesGameStateDTO activeGame;
}

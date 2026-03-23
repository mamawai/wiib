package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PredictionBetLiveResponse {

    private String username;
    private String avatar;
    private String side;
    private BigDecimal amount;
    private LocalDateTime createdAt;
}

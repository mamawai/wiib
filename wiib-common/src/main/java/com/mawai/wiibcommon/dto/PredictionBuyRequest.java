package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PredictionBuyRequest {

    /** UP/DOWN */
    private String side;

    private BigDecimal amount;
}

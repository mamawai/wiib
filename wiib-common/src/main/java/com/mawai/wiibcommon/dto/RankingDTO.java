package com.mawai.wiibcommon.dto;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class RankingDTO implements Serializable {
    private Integer rank;
    private Long userId;
    private String username;
    private String avatar;
    private BigDecimal totalAssets;
    private BigDecimal profitPct;
}

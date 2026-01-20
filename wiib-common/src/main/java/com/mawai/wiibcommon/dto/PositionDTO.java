package com.mawai.wiibcommon.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 持仓DTO
 */
@Data
public class PositionDTO {

    /** 持仓ID */
    private Long id;

    /** 股票ID */
    private Long stockId;

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 持仓数量 */
    private Integer quantity;

    /** 持仓成本 */
    private BigDecimal avgCost;

    /** 当前价 */
    private BigDecimal currentPrice;

    /** 市值 */
    private BigDecimal marketValue;

    /** 盈亏 */
    private BigDecimal profit;

    /** 盈亏率 */
    private BigDecimal profitPct;
}

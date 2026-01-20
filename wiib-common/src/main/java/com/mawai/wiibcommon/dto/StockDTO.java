package com.mawai.wiibcommon.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 股票DTO
 */
@Data
public class StockDTO {

    /** 股票ID */
    private Long id;

    /** 股票代码 */
    private String code;

    /** 股票名称 */
    private String name;

    /** 所属行业 */
    private String industry;

    /** 当前价 */
    private BigDecimal price;

    /** 开盘价 */
    private BigDecimal openPrice;

    /** 最高价 */
    private BigDecimal highPrice;

    /** 最低价 */
    private BigDecimal lowPrice;

    /** 昨收价 */
    private BigDecimal prevClose;

    /** 涨跌额 */
    private BigDecimal change;

    /** 涨跌幅 */
    private BigDecimal changePct;

    /** 成交量 */
    private Long volume;

    /** 成交额 */
    private BigDecimal turnover;

    /** 市值 */
    private BigDecimal marketCap;

    /** 市盈率 */
    private BigDecimal peRatio;

    /** 公司简介 */
    private String companyDesc;
}

package com.mawai.wiibcommon.dto;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "期权持仓（查询列表用）")
public class OptionPositionDTO {
    @Schema(description = "持仓ID")
    private Long positionId;

    @Schema(description = "期权合约ID")
    private Long contractId;

    @Schema(description = "标的股票ID")
    private Long stockId;

    @Schema(description = "标的股票代码（用于展示）")
    private String stockCode;

    @Schema(description = "标的股票名称（用于展示）")
    private String stockName;

    @Schema(description = "期权类型：CALL/PUT", example = "CALL")
    private String optionType;

    @Schema(description = "行权价")
    private BigDecimal strike;

    @Schema(description = "到期时间")
    private LocalDateTime expireAt;

    @Schema(description = "持仓数量")
    private Integer quantity;

    @Schema(description = "持仓成本（加权平均权利金）")
    private BigDecimal avgCost;

    @Schema(description = "当前权利金（用于盯市）")
    private BigDecimal currentPremium;

    @Schema(description = "持仓市值：currentPremium×quantity（可由其他字段推导）")
    private BigDecimal marketValue;

    @Schema(description = "浮动盈亏：marketValue - avgCost×quantity（可由其他字段推导）")
    private BigDecimal pnl;

    @Schema(description = "标的现价（S）")
    private BigDecimal spotPrice;
}

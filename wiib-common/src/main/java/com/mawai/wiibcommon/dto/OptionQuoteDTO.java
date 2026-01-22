package com.mawai.wiibcommon.dto;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "期权实时报价")
public class OptionQuoteDTO {
    @Schema(description = "期权合约ID")
    private Long contractId;

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

    @Schema(description = "权利金（期权价格，单位：每份；买卖金额通常=权利金×数量）")
    private BigDecimal premium;

    @Schema(description = "内在价值：CALL=max(S-K,0)；PUT=max(K-S,0)")
    private BigDecimal intrinsicValue;

    @Schema(description = "时间价值：premium - intrinsicValue（可由其他字段推导）")
    private BigDecimal timeValue;

    @Schema(description = "标的现价（S）")
    private BigDecimal spotPrice;

    @Schema(description = "年化波动率（如0.20表示20%）")
    private BigDecimal sigma;
}

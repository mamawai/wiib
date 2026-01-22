package com.mawai.wiibcommon.dto;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "期权订单（查询列表用）")
public class OptionOrderDTO {
    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "标的股票名称（用于展示）")
    private String stockName;

    @Schema(description = "期权类型：CALL/PUT", example = "CALL")
    private String optionType;

    @Schema(description = "订单方向：BTO(买入开仓)/STC(卖出平仓)", example = "BTO")
    private String orderSide;

    @Schema(description = "订单状态：PENDING/FILLED/CANCELLED/EXPIRED", example = "FILLED")
    private String status;

    @Schema(description = "行权价")
    private BigDecimal strike;

    @Schema(description = "数量")
    private Integer quantity;

    @Schema(description = "成交价（权利金）")
    private BigDecimal filledPrice;

    @Schema(description = "成交金额（通常=filledPrice×quantity；不含手续费，可由其他字段推导）")
    private BigDecimal filledAmount;

    @Schema(description = "手续费")
    private BigDecimal commission;

    @Schema(description = "合约到期时间")
    private LocalDateTime expireAt;
}

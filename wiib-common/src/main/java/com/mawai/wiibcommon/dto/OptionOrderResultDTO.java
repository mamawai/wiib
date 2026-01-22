package com.mawai.wiibcommon.dto;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Data
@Schema(description = "期权下单结果")
public class OptionOrderResultDTO {
    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "订单状态：PENDING/FILLED/CANCELLED/EXPIRED", example = "FILLED")
    private String status;

    @Schema(description = "成交价（权利金）")
    private BigDecimal filledPrice;

    @Schema(description = "成交金额（不含手续费）")
    private BigDecimal filledAmount;

    @Schema(description = "手续费")
    private BigDecimal commission;
}

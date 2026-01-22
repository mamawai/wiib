package com.mawai.wiibcommon.dto;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Schema(description = "期权下单请求（当前仅支持市价：买入开仓/卖出平仓）")
public class OptionOrderRequest {
    @Schema(description = "期权合约ID", example = "1")
    private Long contractId;

    @Schema(description = "数量（正整数）", example = "1")
    private Integer quantity;
}

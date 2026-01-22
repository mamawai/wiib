package com.mawai.wiibcommon.dto;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "期权链条目（单个合约的关键要素）")
public class OptionChainItemDTO {
    @Schema(description = "期权合约ID")
    private Long contractId;

    @Schema(description = "标的股票ID（同一链中通常相同，属于冗余展示字段）")
    private Long stockId;

    @Schema(description = "期权类型：CALL/PUT", example = "CALL")
    private String optionType;

    @Schema(description = "行权价")
    private BigDecimal strike;

    @Schema(description = "到期时间")
    private LocalDateTime expireAt;
}

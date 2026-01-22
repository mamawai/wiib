package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("option_settlement")
public class OptionSettlement {

    @TableId(type = IdType.AUTO)
    // 结算记录ID
    private Long id;

    // 用户ID
    private Long userId;

    // 期权合约ID
    private Long contractId;

    // 持仓ID
    private Long positionId;

    // 结算数量（份）
    private Integer quantity;

    // 行权价（K，可从合约推导但保留用于复盘）
    private BigDecimal strike;

    // 结算时标的价格（S）
    private BigDecimal settlementPrice;

    // 内在价值：CALL=max(S-K,0)；PUT=max(K-S,0)
    private BigDecimal intrinsicValue;

    // 结算金额：intrinsicValue×quantity（可推导但保留用于复盘）
    private BigDecimal settlementAmount;

    @TableField(fill = FieldFill.INSERT)
    // 结算时间
    private LocalDateTime settledAt;
}

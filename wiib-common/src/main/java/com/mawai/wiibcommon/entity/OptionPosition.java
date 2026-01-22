package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("option_position")
public class OptionPosition {

    @TableId(type = IdType.AUTO)
    // 期权持仓ID
    private Long id;

    // 用户ID
    private Long userId;

    // 期权合约ID
    private Long contractId;

    // 可用数量（份）
    private Integer quantity;

    // 冻结数量（预留/可选：挂单或风控冻结）
    private Integer frozenQuantity;

    /** 持仓成本（加权平均权利金） */
    private BigDecimal avgCost;

    @TableField(fill = FieldFill.INSERT)
    // 创建时间
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    // 更新时间
    private LocalDateTime updatedAt;

    public Integer getTotalQuantity() {
        int frozen = frozenQuantity != null ? frozenQuantity : 0;
        return quantity + frozen;
    }
}

package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 持仓实体
 * 使用数据库原子操作保证并发安全
 */
@Data
@TableName("position")
public class Position {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 股票ID */
    private Long stockId;

    /** 可用数量 */
    private Integer quantity;

    /** 冻结数量（限价卖单冻结的股票） */
    private Integer frozenQuantity;

    /** 持仓成本（加权平均） */
    private BigDecimal avgCost;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 获取总持仓（可用 + 冻结） */
    public Integer getTotalQuantity() {
        int frozen = frozenQuantity != null ? frozenQuantity : 0;
        return quantity + frozen;
    }
}

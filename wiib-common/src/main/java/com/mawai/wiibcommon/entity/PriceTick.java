package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 价格tick实体
 * 每10秒一个价格点，用于分时图
 */
@Data
@TableName("price_tick")
public class PriceTick {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 股票ID */
    private Long stockId;

    /** 价格 */
    private BigDecimal price;

    /** 成交量 */
    private Long volume;

    /** tick时间 */
    private LocalDateTime tickTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

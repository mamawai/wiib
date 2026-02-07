package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mawai.wiibcommon.handler.BigDecimalArrayTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "price_tick_daily", autoResultMap = true)
public class PriceTickDaily {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long stockId;

    private LocalDate tradeDate;

    @TableField(typeHandler = BigDecimalArrayTypeHandler.class)
    private List<BigDecimal> prices;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

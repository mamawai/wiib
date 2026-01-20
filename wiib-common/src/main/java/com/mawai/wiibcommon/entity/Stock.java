package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 股票实体（静态数据）
 * 实时数据（价格、涨跌、最高最低）从Redis获取
 */
@Data
@TableName("stock")
public class Stock {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 公司ID */
    private Long companyId;

    /** 股票代码 */
    private String code;

    /** 股票名称 */
    private String name;

    /** 昨收价 */
    private BigDecimal prevClose;

    /** 开盘价 */
    private BigDecimal open;

    /** 历史总成交量 */
    private Long volume;

    /** 历史总成交额 */
    private BigDecimal turnover;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

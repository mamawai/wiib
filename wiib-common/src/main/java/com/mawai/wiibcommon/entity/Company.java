package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 公司实体
 */
@Data
@TableName("company")
public class Company {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 公司名称 */
    private String name;

    /** 所属行业 */
    private String industry;

    /** 公司简介 */
    private String description;

    /** 市值 */
    private BigDecimal marketCap;

    /** 市盈率 */
    private BigDecimal peRatio;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}

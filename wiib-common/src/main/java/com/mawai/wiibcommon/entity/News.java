package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 新闻实体
 * AI生成的虚拟新闻，影响股价波动
 */
@Data
@TableName("news")
public class News {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 股票代码 */
    private String stockCode;

    /** 新闻标题 */
    private String title;

    /** 新闻内容 */
    private String content;

    /** 新闻类型 */
    private String newsType;

    /** 影响系数 */
    private BigDecimal impact;

    /** 发布时间 */
    private LocalDateTime publishTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

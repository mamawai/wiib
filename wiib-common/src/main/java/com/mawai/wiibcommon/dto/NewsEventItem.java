package com.mawai.wiibcommon.dto;

import lombok.Data;

/**
 * 打标快讯条目（K 线新闻图标数据源）：news_event 表的查询投影，MyBatis 按列别名注入。
 */
@Data
public class NewsEventItem {

    private Long id;
    private String title;
    /** 纯文本正文（入库时已脱 HTML） */
    private String content;
    /** 英文译文；null=没译成，前端回落中文 */
    private String titleEn;
    private String contentEn;
    /** 原始消息源链接 */
    private String url;
    /** 发稿时刻 epoch 毫秒，前端按 K 线周期桶定位图标 */
    private Long publishedAt;
    /** 逗号标签串，如 BTC,GOLD */
    private String tags;
}

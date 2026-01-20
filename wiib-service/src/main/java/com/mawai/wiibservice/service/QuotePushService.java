package com.mawai.wiibservice.service;

import java.time.LocalDate;

/**
 * 行情推送服务接口
 */
public interface QuotePushService {

    /**
     * 推送股票行情到指定主题
     * @param stockCode 股票代码
     * @param date 日期
     * @param tickIndex 行情索引
     */
    void pushQuote(String stockCode, LocalDate date, int tickIndex);

    /**
     * 推送所有股票行情
     * @param date 日期
     * @param tickIndex 行情索引
     */
    void pushAllQuotes(LocalDate date, int tickIndex);
}

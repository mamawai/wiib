package com.mawai.wiibservice.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 行情数据服务接口
 */
public interface MarketDataService {

    /**
     * 生成次日所有股票的行情数据
     * 写入数据库price_tick表 + Redis Hash
     */
    void generateNextDayMarketData(LocalDate targetDate);

    /**
     * 加载指定日期的行情数据到Redis
     * 从数据库读取，写入Redis Hash
     */
    void loadDayDataToRedis(LocalDate date);

    /**
     * 获取指定股票在指定时间的价格
     * @return {price, volume} 或 null
     */
    Map<String, Object> getTickByTime(Long stockId, LocalDate date, LocalTime time);

    /**
     * 获取指定日期的所有分时数据
     * @return 按时间排序的tick列表
     */
    List<Map<String, Object>> getDayTicks(Long stockId, LocalDate date);

    /**
     * 获取历史每日收盘价
     * @param days 天数
     * @return 每日收盘价列表
     */
    List<Map<String, Object>> getHistoryClose(Long stockId, int days);

    /**
     * 获取实时行情（用于WebSocket推送）
     * 包含：price, volume, time, open, high, low, prevClose
     * 同时更新当日最高最低价
     */
    Map<String, Object> getRealtimeQuote(Long stockId, LocalDate date, LocalTime time);
}

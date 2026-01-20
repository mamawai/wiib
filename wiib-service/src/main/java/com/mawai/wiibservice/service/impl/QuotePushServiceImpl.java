package com.mawai.wiibservice.service.impl;

import cn.hutool.json.JSONObject;
import com.mawai.wiibcommon.entity.Stock;
import com.mawai.wiibservice.service.MarketDataService;
import com.mawai.wiibservice.service.QuotePushService;
import com.mawai.wiibservice.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 行情推送服务实现
 * 从Redis读取实时数据，通过Redis广播推送到WebSocket
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotePushServiceImpl implements QuotePushService {

    private final MarketDataService marketDataService;
    private final StockService stockService;
    private final RedisMessageBroadcastService broadcastService;

    /**
     * 推送单个股票行情
     */
    @Override
    public void pushQuote(String stockCode, LocalDate date, int tickIndex) {
        try {
            Stock stock = stockService.findByCode(stockCode);
            if (stock == null) {
                return;
            }

            // 从Redis获取实时行情
            LocalTime now = LocalTime.now();
            LocalTime alignedTime = alignToTick(now);
            Map<String, Object> quote = marketDataService.getRealtimeQuote(stock.getId(), date, alignedTime);

            if (quote == null) {
                return;
            }

            // 构建推送消息
            JSONObject message = new JSONObject();
            message.set("code", stock.getCode());
            message.set("name", stock.getName());
            message.set("price", quote.get("price"));
            message.set("volume", quote.get("volume"));
            message.set("open", quote.get("open"));
            message.set("high", quote.get("high"));
            message.set("low", quote.get("low"));
            message.set("prevClose", quote.get("prevClose"));
            message.set("timestamp", System.currentTimeMillis());

            // 通过Redis广播
            broadcastService.broadcastStockQuote(stockCode, message.toString());

            log.debug("推送行情: {}", stockCode);
        } catch (Exception e) {
            log.error("推送行情失败: {}", stockCode, e);
        }
    }

    /**
     * 推送所有股票行情
     */
    @Override
    public void pushAllQuotes(LocalDate date, int tickIndex) {
        try {
            List<Stock> stocks = stockService.list();

            for (Stock stock : stocks) {
                pushQuote(stock.getCode(), date, tickIndex);
            }

            log.info("推送所有行情完成，共{}支股票", stocks.size());
        } catch (Exception e) {
            log.error("推送所有行情失败", e);
        }
    }

    /** 对齐到10秒整点 */
    private LocalTime alignToTick(LocalTime time) {
        int seconds = time.toSecondOfDay();
        int aligned = (seconds / 10) * 10;
        return LocalTime.ofSecondOfDay(aligned);
    }
}

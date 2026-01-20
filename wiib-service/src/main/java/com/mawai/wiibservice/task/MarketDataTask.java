package com.mawai.wiibservice.task;

import com.mawai.wiibservice.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataTask {

    private final MarketDataService marketDataService;

    @Scheduled(cron = "0 0 23 * * ?")
    public void generateNextDayData() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        log.info("定时任务：预生成明天行情 {}", tomorrow);
        marketDataService.generateNextDayMarketData(tomorrow);
    }

    @Scheduled(cron = "0 25 9 * * ?")
    public void loadTodayDataToRedis() {
        LocalDate today = LocalDate.now();
        log.info("定时任务：加载今日行情到Redis {}", today);
        marketDataService.loadDayDataToRedis(today);
    }
}

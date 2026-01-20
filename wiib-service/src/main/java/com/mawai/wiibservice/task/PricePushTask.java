package com.mawai.wiibservice.task;

import com.mawai.wiibservice.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PricePushTask {

    private final MarketDataService marketDataService;
    private final SimpMessagingTemplate messagingTemplate;

    @Scheduled(fixedRate = 10000)
    public void pushCurrentPrice() {
        LocalTime now = LocalTime.now();

        if (!isTradingTime(now)) {
            return;
        }

        LocalDate today = LocalDate.now();
        LocalTime alignedTime = alignToTick(now);

        List<Long> stockIds = getActiveStockIds();
        for (Long stockId : stockIds) {
            Map<String, Object> quote = marketDataService.getRealtimeQuote(stockId, today, alignedTime);
            if (quote != null) {
                messagingTemplate.convertAndSend("/topic/stock/" + stockId, quote);
            }
        }
    }

    private LocalTime alignToTick(LocalTime time) {
        int seconds = time.toSecondOfDay();
        int aligned = (seconds / 10) * 10;
        return LocalTime.ofSecondOfDay(aligned);
    }

    private boolean isTradingTime(LocalTime time) {
        return (time.isAfter(LocalTime.of(9, 30)) && time.isBefore(LocalTime.of(11, 30)))
            || (time.isAfter(LocalTime.of(13, 0)) && time.isBefore(LocalTime.of(15, 0)));
    }

    private List<Long> getActiveStockIds() {
        // TODO: 从缓存或数据库获取活跃股票ID列表
        return List.of(1L, 2L, 3L);
    }
}

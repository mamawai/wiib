package com.mawai.wiibservice.task;

import com.mawai.wiibservice.service.MarketDataService;
import com.mawai.wiibservice.service.OrderService;
import com.mawai.wiibservice.service.QuotePushService;
import com.mawai.wiibservice.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.ScheduledFuture;

/**
 * 定时任务
 * 1. 每日16:00生成次日行情
 * 2. 交易时段每10秒推送行情（上午9:30-11:30 + 下午13:00-15:00，共1440个点）
 * 3. 交易时段每10秒触发限价单检测
 * 4. 每分钟执行已触发的限价单
 * 5. 每日9:00处理过期限价单
 * 6. 每日0:05执行T+1资金结算
 *
 * <p>虚拟线程说明：</p>
 * <ul>
 *   <li>定时任务在虚拟线程中执行（由VirtualThreadConfig配置）</li>
 *   <li>行情推送使用虚拟线程异步推送（QuotePushService内部实现）</li>
 *   <li>数据库事务操作自动在平台线程中执行（Spring保证）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTasks {

    private final MarketDataService marketDataService;
    private final QuotePushService quotePushService;
    private final OrderService orderService;
    private final SettlementService settlementService;
    private final TaskScheduler taskScheduler;

    /** 每日数据点数：240分钟 × 6点/分钟 = 1440 */
    private static final int TOTAL_TICKS = 1440;

    // 当前推送的索引（0-1439）
    private int currentTickIndex = 0;

    // 是否在交易时段
    private boolean isTradingTime = false;

    // 动态定时任务的引用
    private ScheduledFuture<?> marketDataTask;

    /**
     * 每日16:00生成次日行情
     * 注意：此方法在虚拟线程中执行，但内部的数据库操作会自动切换到平台线程
     */
    @Scheduled(cron = "0 0 16 * * ?")
    public void generateNextDayMarketData() {
        LocalDate targetDate = LocalDate.now().plusDays(1);
        log.info("定时任务触发：生成次日行情 {}", targetDate);

        try {
            // 在虚拟线程中执行，数据库操作会自动切换到平台线程
            marketDataService.generateNextDayMarketData(targetDate);
            log.info("次日行情生成完成");
        } catch (Exception e) {
            log.error("生成次日行情失败", e);
        }
    }

    /**
     * 每日9:25重置索引并启动推送任务（仅周一到周五）
     */
    @Scheduled(cron = "0 25 9 * * MON-FRI")
    public void startMarketDataPush() {
        currentTickIndex = 0;
        isTradingTime = false;
        log.info("重置行情索引，准备开盘");

        // 如果任务已经在运行，先停止
        if (marketDataTask != null && !marketDataTask.isCancelled()) {
            marketDataTask.cancel(false);
            log.info("停止旧的推送任务");
        }

        // 启动新的推送任务，每10秒执行一次
        marketDataTask = taskScheduler.scheduleWithFixedDelay(
                this::pushMarketData,
                Duration.ofSeconds(10)
        );
        log.info("启动行情推送任务");
    }

    /**
     * 每日15:00停止推送任务（仅周一到周五）
     */
    @Scheduled(cron = "0 0 15 * * MON-FRI")
    public void stopMarketDataPush() {
        if (marketDataTask != null && !marketDataTask.isCancelled()) {
            marketDataTask.cancel(false);
            marketDataTask = null;
            isTradingTime = false;
            log.info("收盘，停止推送任务");
        }
    }

    /**
     * 推送行情数据（由动态任务调用）
     */
    private void pushMarketData() {
        LocalTime now = LocalTime.now();

        // 判断是否在交易时段（上午9:30-11:30 或 下午13:00-15:00）
        boolean inMorning = !now.isBefore(LocalTime.of(9, 30)) && !now.isAfter(LocalTime.of(11, 30));
        boolean inAfternoon = !now.isBefore(LocalTime.of(13, 0)) && !now.isAfter(LocalTime.of(15, 0));
        boolean inTradingTime = inMorning || inAfternoon;

        // 开盘时重置索引
        if (inTradingTime && !isTradingTime) {
            currentTickIndex = 0;
            isTradingTime = true;
            log.info("开盘，重置行情索引");
        }

        // 收盘时停止推送
        if (!inTradingTime && isTradingTime) {
            isTradingTime = false;
            log.info("收盘，停止推送");
            return;
        }

        // 非交易时段不推送
        if (!inTradingTime) {
            return;
        }

        // 索引超出范围，停止推送
        if (currentTickIndex >= TOTAL_TICKS) {
            log.warn("行情索引超出范围: {}", currentTickIndex);
            return;
        }

        try {
            LocalDate today = LocalDate.now();
            // 推送所有股票行情（内部使用虚拟线程并发推送）
            quotePushService.pushAllQuotes(today, currentTickIndex);
            // 触发限价单检测（标记触发状态）
            orderService.triggerLimitOrders();
            currentTickIndex++;
        } catch (Exception e) {
            log.error("推送行情失败", e);
        }
    }

    /**
     * 每分钟执行已触发的限价单
     * 批量处理TRIGGERED状态的订单
     */
    @Scheduled(cron = "0 * * * * ?")
    public void executeTriggeredLimitOrders() {
        try {
            orderService.executeTriggeredOrders();
        } catch (Exception e) {
            log.error("执行已触发订单失败", e);
        }
    }

    /**
     * 每日9:00处理过期限价单
     * 将超时的PENDING订单标记为EXPIRED，回退资金/股票
     */
    @Scheduled(cron = "0 0 9 * * ?")
    public void expireLimitOrders() {
        try {
            orderService.expireLimitOrders();
        } catch (Exception e) {
            log.error("处理过期订单失败", e);
        }
    }

    /**
     * 每日0:05执行T+1资金结算
     * 将昨日卖出的资金转入用户余额
     */
    @Scheduled(cron = "0 5 0 * * ?")
    public void processSettlements() {
        log.info("定时任务触发：T+1资金结算");
        try {
            settlementService.processSettlements();
            log.info("T+1资金结算完成");
        } catch (Exception e) {
            log.error("T+1资金结算失败", e);
        }
    }
}

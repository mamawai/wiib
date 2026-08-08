package com.mawai.wiibquant.agent.trader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibquant.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * trader 调度器：5m K线收盘事件当唯一时钟，对齐到各 trader 的 interval 边界触发唤醒。
 * 并发上限 10（信号量）+ 每 trader 互斥（上一唤醒未完 → 记 SKIPPED，要最新信号不排陈旧任务）；
 * 事件是 per-symbol 的，同一边界多币多次触发靠 firedBoundary 去重。
 * 不做兜底补漏：WS/事件断流丢的K线就丢了——陈旧信号唤醒没有意义（迟到事件由唤醒预算兜底：
 * 距下一边界不足 30s 直接放弃，见 TraderWakeupRunner）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderScheduler {

    static final int MAX_CONCURRENT_WAKEUPS = 10;
    /** interval → 毫秒；TraderWakeupRunner 计算唤醒预算共用同一份。1d 只留数学（learning 日线边界用），不再是唤醒档位 */
    static final Map<String, Long> INTERVAL_MS = Map.of(
            "5m", 300_000L, "15m", 900_000L, "1h", 3_600_000L, "4h", 14_400_000L, "1d", 86_400_000L);
    /** 例行唤醒只遍历四档（1d 档位已下线，存量 1d trader 自然停摆） */
    static final Set<String> WAKE_INTERVALS = Set.of("5m", "15m", "1h", "4h");

    private final AiTraderMapper traderMapper;
    private final TraderWakeupRunner runner;

    /** 警报冷静期：距该 trader 上一次任何唤醒（例行/警报）不足 5 分钟不再警报 */
    static final long ALERT_COOLDOWN_MS = 5 * 60_000L;

    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_WAKEUPS);
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** 每 trader 最近已触发的边界时刻：多 symbol 同刻收盘/事件与兜底双路都靠它去重 */
    private final Map<Long, Long> firedBoundary = new ConcurrentHashMap<>();
    /** 每 trader 最近一次唤醒起始时刻（例行+警报都记）：警报冷静期的基准；重启清零无所谓 */
    private final Map<Long, Long> lastWakeAt = new ConcurrentHashMap<>();

    /** 墙钟注入点：警报准入的冷静期/预算预检要可测 */
    java.util.function.LongSupplier nowMs = System::currentTimeMillis;

    /** 主触发：任意 watch 币的 5m 收盘都是一次时钟滴答。 */
    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (!"5m".equalsIgnoreCase(event.interval())) {
            return;
        }
        for (String ic : WAKE_INTERVALS) {
            long boundary = boundaryOf(event.closeTime(), ic);
            if (boundary > 0) {
                fireInterval(ic, boundary);
            }
        }
    }

    private void fireInterval(String intervalCode, long boundary) {
        List<AiTrader> traders = traderMapper.selectList(new LambdaQueryWrapper<AiTrader>()
                .eq(AiTrader::getStatus, AiTrader.STATUS_RUNNING)
                .eq(AiTrader::getIntervalCode, intervalCode));
        for (AiTrader trader : traders) {
            fireTrader(trader, boundary);
        }
    }

    private void fireTrader(AiTrader trader, long boundary) {
        // 原子抢占本边界：多 symbol 事件/兜底并发到达时只有一个赢家，输家静默返回（不是SKIPPED）
        boolean[] won = new boolean[1];
        firedBoundary.compute(trader.getId(), (id, prev) -> {
            if (prev == null || prev < boundary) {
                won[0] = true;
                return boundary;
            }
            return prev;
        });
        if (!won[0]) {
            return;
        }
        if (!inFlight.add(trader.getId())) {
            // 上一边界的唤醒还在跑：本边界作废并留痕，等下一根K线的新鲜信号
            runner.recordSkipped(trader, boundary);
            log.info("[TraderSched] 上轮未完跳过 traderId={} boundary={}", trader.getId(), boundary);
            return;
        }
        lastWakeAt.put(trader.getId(), nowMs.getAsLong());
        Thread.startVirtualThread(() -> {
            try {
                slots.acquire();
                try {
                    runner.wake(trader, boundary);
                } finally {
                    slots.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.remove(trader.getId());
            }
        });
    }

    /**
     * 波动哨兵警报唤醒准入（唤醒治理全在调度器）：冷静期→预算预检→互斥，全过才真唤醒。
     * 任一环节不过都静默放弃只留日志——警报是补充不是义务，SKIPPED 决策行只属于例行调度。
     */
    public void tryAlertWake(AiTrader trader, AlertTrigger trigger) {
        long now = nowMs.getAsLong();
        Long last = lastWakeAt.get(trader.getId());
        if (last != null && now - last < ALERT_COOLDOWN_MS) {
            return;
        }
        Long intervalMs = INTERVAL_MS.get(trader.getIntervalCode());
        if (intervalMs == null) {
            return;
        }
        // 例行唤醒将至就不抢戏：马上有新鲜K线信号，警报没有增量价值
        long boundary = now - Math.floorMod(now, intervalMs);
        if (TraderWakeupRunner.wakeBudgetSeconds(boundary, intervalMs, now) < TraderWakeupRunner.MIN_WAKE_SECONDS) {
            log.info("[TraderSched] 例行唤醒将至，警报放弃 traderId={} {}", trader.getId(), trigger.symbol());
            return;
        }
        if (!inFlight.add(trader.getId())) {
            return;
        }
        lastWakeAt.put(trader.getId(), now);
        log.info("[TraderSched] 波动警报唤醒 traderId={} {} 振幅{}% {}",
                trader.getId(), trigger.symbol(), trigger.amplitudePct(), trigger.direction());
        Thread.startVirtualThread(() -> {
            try {
                slots.acquire();
                try {
                    runner.wakeAlert(trader, trigger);
                } finally {
                    slots.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.remove(trader.getId());
            }
        });
    }

    /**
     * K线收盘时刻 → interval 边界：Binance closeTime 是 xx:x9:59.999，
     * (closeTime+1) 恰好整除 interval 毫秒数才是该 interval 的收盘边界；否则 -1。
     */
    static long boundaryOf(long closeTime, String intervalCode) {
        Long ms = INTERVAL_MS.get(intervalCode);
        if (ms == null) {
            return -1;
        }
        long instant = closeTime + 1;
        return instant % ms == 0 ? instant : -1;
    }
}

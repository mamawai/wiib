package com.mawai.wiibquant.agent.trader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibquant.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * trader 调度器：5m K线收盘事件当时钟，对齐到各 trader 的 interval 边界触发唤醒。
 * 并发上限 10（信号量）+ 每 trader 互斥（上一唤醒未完 → 记 SKIPPED，要最新信号不排陈旧任务）；
 * 事件是 per-symbol 的，同一边界多币多次触发靠 firedBoundary 去重；
 * 每分钟兜底扫描补 WS 断流漏掉的边界（只补 5 分钟内的新鲜边界，过期不补）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderScheduler {

    static final int MAX_CONCURRENT_WAKEUPS = 10;
    /** 兜底只补这么新的边界：太旧的信号唤醒无意义 */
    private static final long FALLBACK_FRESH_MS = 5 * 60_000L;
    private static final Map<String, Long> INTERVAL_MS = Map.of(
            "15m", 900_000L, "1h", 3_600_000L, "4h", 14_400_000L, "1d", 86_400_000L);

    private final AiTraderMapper traderMapper;
    private final TraderWakeupRunner runner;

    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_WAKEUPS);
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** 每 trader 最近已触发的边界时刻：多 symbol 同刻收盘/事件与兜底双路都靠它去重 */
    private final Map<Long, Long> firedBoundary = new ConcurrentHashMap<>();

    /** 主触发：任意 watch 币的 5m 收盘都是一次时钟滴答。 */
    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (!"5m".equalsIgnoreCase(event.interval())) {
            return;
        }
        for (String ic : INTERVAL_MS.keySet()) {
            long boundary = boundaryOf(event.closeTime(), ic);
            if (boundary > 0) {
                fireInterval(ic, boundary);
            }
        }
    }

    /** 兜底：WS 断流时按墙钟补最近错过的边界（新鲜期内才补）。 */
    @Scheduled(fixedDelay = 60_000)
    public void sweepMissedBoundaries() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : INTERVAL_MS.entrySet()) {
            long boundary = now / e.getValue() * e.getValue();
            if (now - boundary <= FALLBACK_FRESH_MS && boundary > 0) {
                fireInterval(e.getKey(), boundary);
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
        Long fired = firedBoundary.get(trader.getId());
        if (fired != null && fired >= boundary) {
            return; // 本边界已触发过（多 symbol 事件/兜底重复）
        }
        if (!inFlight.add(trader.getId())) {
            // 上一唤醒还在跑：本边界作废并留痕，等下一根K线的新鲜信号
            firedBoundary.put(trader.getId(), boundary);
            runner.recordSkipped(trader, boundary);
            log.info("[TraderSched] 上轮未完跳过 traderId={} boundary={}", trader.getId(), boundary);
            return;
        }
        firedBoundary.put(trader.getId(), boundary);
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

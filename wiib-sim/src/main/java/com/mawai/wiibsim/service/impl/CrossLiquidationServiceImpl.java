package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.TradeNotificationService;
import com.mawai.wiibsim.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static com.mawai.wiibsim.service.impl.FuturesHelper.calculatePnl;
import static com.mawai.wiibsim.service.impl.FuturesHelper.markPrice;
import static com.mawai.wiibsim.service.impl.FuturesHelper.removeFromLimitZSet;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrossLiquidationServiceImpl implements CrossLiquidationService {

    private final CrossMarginService crossMarginService;
    private final FuturesPositionMapper positionMapper;
    private final FuturesOrderMapper orderMapper;
    private final TradingConfig tradingConfig;
    private final CacheService cacheService;
    private final FuturesPositionIndexService positionIndexService;
    private final RedisLockUtil redisLockUtil;
    private final TradeNotificationService tradeNotificationService;

    /**
     * tick 去重表：userId → 上次巡检触发时刻。健康检查是账户级的（一次读全部持仓 symbol 的最新价），
     * 同一秒内多 symbol tick 对同一用户的重复触发纯属浪费。压测（2026-08）：合并原先靠"撞进用户锁
     * 持有窗口"的时序巧合，REST 降级的错开时序下巡检量翻倍——这里改成设计保证。
     * 900ms = 1s tick 周期留 100ms 到达抖动，保证每个整秒 tick 必放行一次。
     * 并发 get/put 竞态放行无害：用户级 Redis 锁是第二道闸。只挡 tick 路径，
     * sweepAll 兜底与爆仓链路直调 checkUser 不经过这里。
     */
    private static final long TICK_DEDUP_MS = 900;
    private final ConcurrentHashMap<Long, Long> lastTickCheck = new ConcurrentHashMap<>();

    @Override
    public void onPriceTick(String symbol) {
        long now = System.currentTimeMillis();
        for (String uid : crossMarginService.usersOnSymbol(symbol)) {
            long userId = Long.parseLong(uid);
            Long last = lastTickCheck.get(userId);
            if (last != null && now - last < TICK_DEDUP_MS) continue;
            lastTickCheck.put(userId, now);
            Thread.startVirtualThread(() -> checkUser(userId));
        }
    }

    @Override
    public void checkUser(Long userId) {
        // 用户级锁：同一账户的检查/爆仓串行
        String lockKey = "futures:cross:liq:" + userId;
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) return;
        try {
            var account = crossMarginService.snapshot(userId);
            if (account.positions().isEmpty()) {
                // 仓位已被别的路径清掉（如破产清算），顺手把索引残留擦干净
                crossMarginService.refreshUserIndex(userId);
                return;
            }
            if (!account.liquidatable()) return;
            SpringUtils.getAopProxy(this).liquidateAll(userId);
        } catch (Exception e) {
            log.error("全仓健康检查失败 userId={}", userId, e);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Override
    public void sweepAll() {
        for (String uid : crossMarginService.allCrossUsers()) {
            try {
                checkUser(Long.parseLong(uid));
            } catch (Exception e) {
                log.error("全仓兜底巡检失败 uid={}", uid, e);
            }
        }
    }

    /**
     * 全组爆：所有全仓仓位按 mark 价强平，盈亏净额一次结算进余额（允许为负）。
     * 结算后余额 &lt; 0 = 穿仓 → 立即破产（游戏钱包也保不住，这是用户要自己控制的风险点）。
     */
    @Transactional(rollbackFor = Exception.class)
    protected void liquidateAll(Long userId) {
        var positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getStatus, "OPEN")
                .eq(FuturesPosition::getMarginMode, FuturesPosition.CROSS));
        if (positions.isEmpty()) return;

        BigDecimal settle = BigDecimal.ZERO;
        int closed = 0;
        for (FuturesPosition pos : positions) {
            BigDecimal price = markPrice(cacheService, pos.getSymbol());
            BigDecimal pnl = calculatePnl(pos.getSide(), pos.getEntryPrice(), price, pos.getQuantity());
            BigDecimal closeValue = price.multiply(pos.getQuantity()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal commission = tradingConfig.calculateFuturesCommission(closeValue, true, true);

            // CAS抢平仓权：并发手动平仓赢了就跳过本仓，不重复结算
            if (positionMapper.casClosePosition(pos.getId(), "LIQUIDATED", price, pnl) == 0) continue;
            positionIndexService.unregisterAll(pos); // 全仓无LIQ索引，清的是SL/TP残留

            FuturesOrder order = new FuturesOrder();
            order.setUserId(userId);
            order.setPositionId(pos.getId());
            order.setSymbol(pos.getSymbol());
            order.setOrderSide("LONG".equals(pos.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT");
            order.setOrderType("MARKET");
            order.setMarginMode(FuturesPosition.CROSS);
            order.setQuantity(pos.getQuantity());
            order.setLeverage(pos.getLeverage());
            order.setFilledPrice(price);
            order.setFilledAmount(closeValue);
            order.setCommission(commission);
            order.setRealizedPnl(pnl);
            order.setStatus("LIQUIDATED");
            orderMapper.insert(order);

            settle = settle.add(pnl).subtract(commission);
            closed++;
        }
        if (closed == 0) return;

        int cancelled = cancelCrossOpenOrders(userId);

        log.warn("全仓爆仓 userId={} 平仓数={} 撤单数={} 结算={}", userId, closed, cancelled, settle);
        // 合并成一条：每仓一条会把信封刷满。closed 只统计 CAS 抢到的仓位，并发手动平仓的那些不算进来
        tradeNotificationService.crossLiquidation(userId, closed, settle);
        // 占用制下保证金没离开过余额，结算只记盈亏净额；全平后已无全仓仓位，扣穿由 settle 触发破产
        crossMarginService.settle(userId, settle);
    }

    /**
     * 爆仓后撤掉该用户残留的全仓开仓挂单。
     */
    private int cancelCrossOpenOrders(Long userId) {
        List<FuturesOrder> pendings = orderMapper.selectList(new LambdaQueryWrapper<FuturesOrder>()
                .eq(FuturesOrder::getUserId, userId)
                .eq(FuturesOrder::getMarginMode, FuturesPosition.CROSS)
                .in(FuturesOrder::getStatus, "PENDING", "TRIGGERED")
                .notLikeRight(FuturesOrder::getOrderSide, "CLOSE"));

        int cancelled = 0;
        for (FuturesOrder order : pendings) {
            // 逐单CAS不批量UPDATE：TRIGGERED单可能正被doProcessTriggeredOrder抢去转PROCESSING，抢输了就别动
            if (orderMapper.casUpdateStatus(order.getId(), order.getStatus(), "CANCELLED") == 0) continue;
            // TRIGGERED单在扫描时已被zRangeByScoreAndRemove摘走，这里ZREM返0无害；PENDING单靠这句摘干净
            removeFromLimitZSet(order, cacheService);
            cancelled++;
        }
        return cancelled;
    }
}

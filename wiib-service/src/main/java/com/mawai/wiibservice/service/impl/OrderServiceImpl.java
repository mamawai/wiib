package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.annotation.RateLimiter;
import com.mawai.wiibcommon.constant.RateLimiterType;
import com.mawai.wiibcommon.dto.OrderResponse;
import com.mawai.wiibcommon.dto.OrderRequest;
import com.mawai.wiibcommon.entity.Order;
import com.mawai.wiibcommon.entity.Settlement;
import com.mawai.wiibcommon.entity.Stock;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.enums.OrderSide;
import com.mawai.wiibcommon.enums.OrderStatus;
import com.mawai.wiibcommon.enums.OrderType;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibservice.config.TradingConfig;
import com.mawai.wiibservice.mapper.OrderMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.OrderService;
import com.mawai.wiibservice.service.PositionService;
import com.mawai.wiibservice.service.SettlementService;
import com.mawai.wiibservice.service.StockService;
import com.mawai.wiibservice.service.UserService;
import com.mawai.wiibservice.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 订单服务实现
 * 核心交易逻辑，支持市价单和限价单
 *
 * <p>
 * 锁与事务顺序：获取锁 → 开启事务 → 执行操作 → 提交事务 → 释放锁
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

    private final UserService userService;
    private final StockService stockService;
    private final PositionService positionService;
    private final SettlementService settlementService;
    private final CacheService cacheService;
    private final TradingConfig tradingConfig;
    private final RedisLockUtil redisLockUtil;

    /** 时间戳容差：3秒 */
    private static final long TIMESTAMP_TOLERANCE_MS = 3000;
    /** 请求ID过期时间：5分钟 */
    private static final long REQUEST_ID_EXPIRE_MINUTES = 5;

    @Override
    @RateLimiter(type = RateLimiterType.BUY, permitsPerSecond = 0.5, bucketCapacity = 5)
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse buy(Long userId, OrderRequest request) {
        // 交易时段校验
        if (tradingConfig.isNotInTradingHours()) {
            throw new BizException(ErrorCode.NOT_IN_TRADING_HOURS);
        }

        validateRequest(request);
        Stock stock = getAndValidateStock(request.getStockCode());
        User user = userService.getById(userId);

        // 从Redis获取实时价格
        BigDecimal price = cacheService.getCurrentPrice(stock.getId());
        if (price == null) {
            // 非交易时段用开盘价（AI预生成）
            price = stock.getOpen() != null ? stock.getOpen() : stock.getPrevClose();
        }

        if (OrderType.MARKET.getCode().equals(request.getOrderType())) {
            // 市价单：立即成交，包含手续费
            BigDecimal amount = price.multiply(BigDecimal.valueOf(request.getQuantity()));
            BigDecimal commission = tradingConfig.calculateCommission(amount);
            BigDecimal totalCost = amount.add(commission);

            if (user.getBalance().compareTo(totalCost) < 0) {
                throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
            }
            return executeMarketBuy(userId, stock, request.getQuantity(), price, commission, request.getRequestId());
        } else {
            // 限价单：冻结资金（含预估手续费），进入订单池
            validateLimitPrice(request.getLimitPrice(), price, true);
            BigDecimal freezeAmount = request.getLimitPrice().multiply(BigDecimal.valueOf(request.getQuantity()));
            BigDecimal estimatedCommission = tradingConfig.calculateCommission(freezeAmount);
            BigDecimal totalFreeze = freezeAmount.add(estimatedCommission);

            if (user.getBalance().compareTo(totalFreeze) < 0) {
                throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
            }
            return createLimitBuyOrder(userId, stock, request, totalFreeze);
        }
    }

    @Override
    @RateLimiter(type = RateLimiterType.SELL, permitsPerSecond = 0.5, bucketCapacity = 5)
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse sell(Long userId, OrderRequest request) {
        // 交易时段校验
        if (tradingConfig.isNotInTradingHours()) {
            throw new BizException(ErrorCode.NOT_IN_TRADING_HOURS);
        }

        validateRequest(request);
        Stock stock = getAndValidateStock(request.getStockCode());

        var position = positionService.findByUserAndStock(userId, stock.getId());
        if (position == null || position.getQuantity() < request.getQuantity()) {
            throw new BizException(ErrorCode.POSITION_NOT_ENOUGH);
        }

        // 从Redis获取实时价格
        BigDecimal price = cacheService.getCurrentPrice(stock.getId());
        if (price == null) {
            // 非交易时段用开盘价（AI预生成）
            price = stock.getOpen() != null ? stock.getOpen() : stock.getPrevClose();
        }

        if (OrderType.MARKET.getCode().equals(request.getOrderType())) {
            BigDecimal amount = price.multiply(BigDecimal.valueOf(request.getQuantity()));
            BigDecimal commission = tradingConfig.calculateCommission(amount);
            return executeMarketSell(userId, stock, request.getQuantity(), price, commission, request.getRequestId());
        } else {
            validateLimitPrice(request.getLimitPrice(), price, false);
            return createLimitSellOrder(userId, stock, request);
        }
    }

    @Override
    public OrderResponse cancel(Long userId, Long orderId) {
        String lockKey = "order:execute:" + orderId;
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) {
            throw new BizException(ErrorCode.ORDER_PROCESSING);
        }

        try {
            return SpringUtils.getAopProxy(this).doCancelOrder(userId, orderId);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 执行取消订单的核心逻辑（锁内执行）
     */
    @Transactional(rollbackFor = Exception.class)
    protected OrderResponse doCancelOrder(Long userId, Long orderId) {
        Order order = baseMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!OrderStatus.PENDING.getCode().equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_CANNOT_CANCEL);
        }

        Stock stock = stockService.getById(order.getStockId());

        // CAS更新订单状态（防止分布式锁失效时的并发问题，作为最后一道安全防线）
        int affected = baseMapper.casUpdateStatus(orderId, OrderStatus.PENDING.getCode(),
                OrderStatus.CANCELLED.getCode());
        if (affected == 0) {
            // 状态已被其他操作改变（可能已成交或已取消）
            Order freshOrder = baseMapper.selectById(orderId);
            log.warn("取消订单{}失败，当前状态为{}", orderId, freshOrder.getStatus());
            throw new BizException(ErrorCode.ORDER_CANNOT_CANCEL);
        }

        // 状态更新成功后，安全地回退冻结的资金或股票
        if (OrderSide.BUY.getCode().equals(order.getOrderSide())) {
            BigDecimal frozenAmount = order.getFrozenAmount();
            userService.unfreezeBalance(userId, frozenAmount);
            log.info("取消买入订单{}，解冻资金{}", orderId, frozenAmount);
        } else {
            positionService.unfreezePosition(userId, order.getStockId(), order.getQuantity());
            log.info("取消卖出订单{}，解冻股票{}股", orderId, order.getQuantity());
        }

        order.setStatus(OrderStatus.CANCELLED.getCode());
        return buildOrderResponse(order, stock);
    }

    @Override
    public List<OrderResponse> getUserOrders(Long userId, String status, int limit) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, userId);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(Order::getStatus, status);
        }
        wrapper.orderByDesc(Order::getCreatedAt).last("LIMIT " + limit);

        return baseMapper.selectList(wrapper).stream()
                .map(order -> {
                    Stock stock = stockService.getById(order.getStockId());
                    return buildOrderResponse(order, stock);
                })
                .collect(Collectors.toList());
    }

    /**
     * 触发限价单（定时任务调用）
     * 检测价格并标记触发状态，不执行成交
     */
    @Override
    public void triggerLimitOrders() {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getStatus, OrderStatus.PENDING.getCode())
                .eq(Order::getOrderType, OrderType.LIMIT.getCode());

        List<Order> pendingOrders = baseMapper.selectList(wrapper);
        if (pendingOrders.isEmpty())
            return;

        int maxConcurrency = tradingConfig.getLimitOrderProcessing().getMaxConcurrency();
        int timeoutSeconds = tradingConfig.getLimitOrderProcessing().getOrderTimeoutSeconds();

        Semaphore semaphore = new Semaphore(maxConcurrency);

        log.info("开始检测{}个限价单触发条件，最大并发数={}", pendingOrders.size(), maxConcurrency);
        long startTime = System.currentTimeMillis();
        int triggeredCount = 0;
        int failCount = 0;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = new ArrayList<>();

            for (Order order : pendingOrders) {
                Future<Boolean> future = executor.submit(() -> {
                    try {
                        if (!semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                            log.warn("获取信号量超时，跳过订单{}", order.getId());
                            return false;
                        }

                        try {
                            return checkAndMarkTriggered(order);
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("检测订单{}被中断", order.getId());
                        return false;
                    }
                });
                futures.add(future);
            }

            for (Future<Boolean> future : futures) {
                try {
                    long remainingTime = (timeoutSeconds * 1000L) - (System.currentTimeMillis() - startTime);
                    if (remainingTime > 0) {
                        Boolean triggered = future.get(remainingTime, TimeUnit.MILLISECONDS);
                        if (Boolean.TRUE.equals(triggered)) {
                            triggeredCount++;
                        } else {
                            failCount++;
                        }
                    } else {
                        failCount++;
                    }
                } catch (TimeoutException e) {
                    log.warn("订单检测超时");
                    failCount++;
                } catch (Exception e) {
                    log.debug("订单检测异常: {}", e.getMessage());
                    failCount++;
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("限价单触发检测完成，共{}个订单，触发{}个，失败{}个，耗时{}ms",
                pendingOrders.size(), triggeredCount, failCount, elapsed);
    }

    /**
     * 检测并标记限价单触发（虚拟线程内执行）
     * @return true=已触发，false=未触发或失败
     */
    private boolean checkAndMarkTriggered(Order order) {
        try {
            Stock stock = stockService.getById(order.getStockId());
            BigDecimal currentPrice = cacheService.getCurrentPrice(stock.getId());
            if (currentPrice == null) {
                currentPrice = stock.getPrevClose();
            }

            boolean shouldTrigger;
            if (OrderSide.BUY.getCode().equals(order.getOrderSide())) {
                shouldTrigger = currentPrice.compareTo(order.getLimitPrice()) <= 0;
            } else {
                shouldTrigger = currentPrice.compareTo(order.getLimitPrice()) >= 0;
            }

            if (shouldTrigger) {
                String lockKey = "order:execute:" + order.getId();
                String lockValue = redisLockUtil.tryLock(lockKey, 30);
                if (lockValue != null) {
                    try {
                        SpringUtils.getAopProxy(this).markOrderTriggered(order.getId(), currentPrice);
                        return true;
                    } finally {
                        redisLockUtil.unlock(lockKey, lockValue);
                    }
                } else {
                    log.debug("限价单{}正在被其他实例处理", order.getId());
                }
            }
            return false;
        } catch (Exception e) {
            log.error("检测限价单触发失败 orderId={}", order.getId(), e);
            return false;
        }
    }

    /**
     * 标记订单为已触发（事务内，调用前需获取分布式锁）
     */
    @Transactional(rollbackFor = Exception.class)
    protected void markOrderTriggered(Long orderId, BigDecimal triggerPrice) {
        int affected = baseMapper.casUpdateToTriggered(orderId, triggerPrice);
        if (affected > 0) {
            log.info("限价单触发 orderId={} triggerPrice={}", orderId, triggerPrice);
        }
    }

    /**
     * 执行已触发的限价单（定时任务调用）
     * 批量处理TRIGGERED订单
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeTriggeredOrders() {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getStatus, OrderStatus.TRIGGERED.getCode())
                .eq(Order::getOrderType, OrderType.LIMIT.getCode());

        List<Order> triggeredOrders = baseMapper.selectList(wrapper);
        if (triggeredOrders.isEmpty())
            return;

        log.info("开始批量执行{}个已触发订单", triggeredOrders.size());
        long startTime = System.currentTimeMillis();

        // 步骤1：计算每个订单的成交信息
        List<Settlement> settlements = new ArrayList<>();
        for (Order order : triggeredOrders) {
            BigDecimal executePrice = order.getTriggerPrice();
            BigDecimal amount = executePrice.multiply(BigDecimal.valueOf(order.getQuantity()));
            BigDecimal commission = tradingConfig.calculateCommission(amount);

            order.setFilledPrice(executePrice);
            order.setFilledAmount(amount);
            order.setCommission(commission);
            order.setStatus(OrderStatus.FILLED.getCode());

            // 收集卖出订单的结算记录
            if (OrderSide.SELL.getCode().equals(order.getOrderSide())) {
                BigDecimal netAmount = amount.subtract(commission);
                Settlement settlement = new Settlement();
                settlement.setUserId(order.getUserId());
                settlement.setOrderId(order.getId());
                settlement.setAmount(netAmount);
                settlement.setSettleDate(LocalDate.now().plusDays(1));
                settlement.setStatus("PENDING");
                settlements.add(settlement);
            }
        }

        // 步骤2：批量更新订单状态
        updateBatchById(triggeredOrders);
        log.info("批量更新{}个订单状态完成", triggeredOrders.size());

        // 步骤3：处理余额和持仓
        int successCount = 0;
        int failCount = 0;
        for (Order order : triggeredOrders) {
            try {
                if (OrderSide.BUY.getCode().equals(order.getOrderSide())) {
                    // 买入：扣冻结资金 + 退多余 + 增持仓
                    BigDecimal frozenAmount = order.getFrozenAmount();
                    BigDecimal actualCost = order.getFilledAmount().add(order.getCommission());
                    BigDecimal refund = frozenAmount.subtract(actualCost);

                    userService.deductFrozenBalance(order.getUserId(), frozenAmount);
                    if (refund.compareTo(BigDecimal.ZERO) > 0) {
                        userService.updateBalance(order.getUserId(), refund);
                    }
                    positionService.addPosition(order.getUserId(), order.getStockId(),
                            order.getQuantity(), order.getFilledPrice());
                } else {
                    // 卖出：扣冻结持仓
                    positionService.deductFrozenPosition(order.getUserId(), order.getStockId(),
                            order.getQuantity());
                }
                successCount++;
            } catch (Exception e) {
                log.error("处理订单{}的余额持仓失败", order.getId(), e);
                failCount++;
            }
        }

        // 步骤4：批量插入结算记录
        if (!settlements.isEmpty()) {
            settlementService.saveBatch(settlements);
            log.info("批量插入{}条结算记录", settlements.size());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("已触发订单执行完成，共{}个订单，成功{}个，失败{}个，耗时{}ms",
                triggeredOrders.size(), successCount, failCount, elapsed);
    }

    /**
     * 过期限价单处理（定时任务调用）
     * 使用虚拟线程并发处理 + 信号量限流
     */
    @Override
    public void expireLimitOrders() {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getStatus, OrderStatus.PENDING.getCode())
                .eq(Order::getOrderType, OrderType.LIMIT.getCode())
                .lt(Order::getExpireAt, LocalDateTime.now());

        List<Order> expiredOrders = baseMapper.selectList(wrapper);
        if (expiredOrders.isEmpty())
            return;

        int maxConcurrency = tradingConfig.getLimitOrderProcessing().getMaxConcurrency();
        int timeoutSeconds = tradingConfig.getLimitOrderProcessing().getOrderTimeoutSeconds();

        Semaphore semaphore = new Semaphore(maxConcurrency);

        log.info("开始并发处理{}个过期订单，最大并发数={}", expiredOrders.size(), maxConcurrency);
        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = new ArrayList<>();

            for (Order order : expiredOrders) {
                Future<Boolean> future = executor.submit(() -> {
                    try {
                        // 信号量超时时间短一些，避免长时间等待
                        if (!semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                            log.warn("获取信号量超时，跳过过期订单{}", order.getId());
                            return false;
                        }

                        try {
                            processExpiredOrder(order);
                            return true;
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("处理过期订单{}被中断", order.getId());
                        return false;
                    }
                });
                futures.add(future);
            }

            // 等待所有任务完成
            for (Future<Boolean> future : futures) {
                try {
                    long remainingTime = (timeoutSeconds * 1000L) - (System.currentTimeMillis() - startTime);
                    if (remainingTime > 0) {
                        Boolean success = future.get(remainingTime, TimeUnit.MILLISECONDS);
                        if (Boolean.TRUE.equals(success)) {
                            successCount++;
                        } else {
                            failCount++;
                        }
                    } else {
                        failCount++;
                    }
                } catch (TimeoutException e) {
                    log.warn("过期订单处理超时");
                    failCount++;
                } catch (Exception e) {
                    log.debug("过期订单处理异常: {}", e.getMessage());
                    failCount++;
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("过期订单处理完成，共{}个订单，成功{}个，失败{}个，耗时{}ms",
                expiredOrders.size(), successCount, failCount, elapsed);
    }

    /**
     * 处理单个过期订单（虚拟线程内执行）
     */
    private void processExpiredOrder(Order order) {
        // 获取分布式锁，确保取消、过期、成交操作互斥
        String lockKey = "order:execute:" + order.getId();
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) {
            log.debug("限价单{}正在被其他操作处理，跳过过期检查", order.getId());
            return;
        }

        try {
            // 通过AOP代理调用，确保事务生效
            SpringUtils.getAopProxy(this).doExpireOrder(order);
        } catch (Exception e) {
            log.error("处理过期订单失败 orderId={}", order.getId(), e);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    /**
     * 执行订单过期的核心逻辑（锁内执行）
     */
    @Transactional(rollbackFor = Exception.class)
    protected void doExpireOrder(Order order) {
        // CAS更新订单状态（防止分布式锁失效时的并发问题）
        int affected = baseMapper.casUpdateStatus(order.getId(), OrderStatus.PENDING.getCode(),
                OrderStatus.EXPIRED.getCode());
        if (affected == 0) {
            // 状态已被其他操作改变（可能已成交或已取消）
            log.info("订单{}状态已变更，跳过过期处理", order.getId());
            return;
        }

        // 状态更新成功后，安全地回退冻结的资金或股票
        if (OrderSide.BUY.getCode().equals(order.getOrderSide())) {
            BigDecimal frozenAmount = order.getFrozenAmount();
            userService.unfreezeBalance(order.getUserId(), frozenAmount);
            log.info("限价买单过期 orderId={} 解冻资金{}", order.getId(), frozenAmount);
        } else {
            positionService.unfreezePosition(order.getUserId(), order.getStockId(), order.getQuantity());
            log.info("限价卖单过期 orderId={} 解冻股票{}股", order.getId(), order.getQuantity());
        }
    }

    /** 校验请求（时间戳、幂等性） */
    private void validateRequest(OrderRequest request) {
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new BizException(ErrorCode.TRADE_QUANTITY_INVALID);
        }

        // 时间戳校验
        if (request.getClientTimestamp() != null) {
            long serverTime = System.currentTimeMillis();
            long diff = Math.abs(serverTime - request.getClientTimestamp());
            if (diff > TIMESTAMP_TOLERANCE_MS) {
                log.warn("时间戳异常 client={} server={} diff={}ms", request.getClientTimestamp(), serverTime, diff);
                throw new BizException(ErrorCode.TIMESTAMP_INVALID);
            }
        }

        // 幂等性校验
        if (request.getRequestId() != null && !request.getRequestId().isEmpty()) {
            String key = "order:request:" + request.getRequestId();
            boolean absent = cacheService.setIfAbsent(key, "1", REQUEST_ID_EXPIRE_MINUTES, TimeUnit.MINUTES);
            if (!absent) {
                throw new BizException(ErrorCode.DUPLICATE_REQUEST);
            }
        }
    }

    private Stock getAndValidateStock(String stockCode) {
        Stock stock = stockService.findByCode(stockCode);
        if (stock == null) {
            throw new BizException(ErrorCode.STOCK_NOT_FOUND);
        }
        return stock;
    }

    /** 校验限价（市价的50%~150%范围内） */
    private void validateLimitPrice(BigDecimal limitPrice, BigDecimal marketPrice, boolean isBuy) {
        if (limitPrice == null || limitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.LIMIT_PRICE_INVALID);
        }
        BigDecimal ratio = limitPrice.divide(marketPrice, 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(new BigDecimal("0.5")) < 0 || ratio.compareTo(new BigDecimal("1.5")) > 0) {
            throw new BizException(ErrorCode.LIMIT_PRICE_INVALID);
        }
    }

    /** 执行市价买入（含手续费） */
    private OrderResponse executeMarketBuy(Long userId, Stock stock, int quantity, BigDecimal price,
            BigDecimal commission, String requestId) {
        BigDecimal amount = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal totalCost = amount.add(commission);

        userService.updateBalance(userId, totalCost.negate());
        positionService.addPosition(userId, stock.getId(), quantity, price);

        Order order = createOrder(userId, stock.getId(), OrderSide.BUY.getCode(), OrderType.MARKET.getCode(),
                quantity, null, price, amount, commission, null, OrderStatus.FILLED.getCode(), requestId, null);
        baseMapper.insert(order);

        log.info("市价买入成交 userId={} stock={} qty={} price={} amount={} commission={}",
                userId, stock.getCode(), quantity, price, amount, commission);
        return buildOrderResponse(order, stock);
    }

    /** 执行市价卖出（资金T+1到账） */
    private OrderResponse executeMarketSell(Long userId, Stock stock, int quantity, BigDecimal price,
            BigDecimal commission, String requestId) {
        BigDecimal amount = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal netAmount = amount.subtract(commission);

        positionService.reducePosition(userId, stock.getId(), quantity);

        Order order = createOrder(userId, stock.getId(), OrderSide.SELL.getCode(), OrderType.MARKET.getCode(),
                quantity, null, price, amount, commission, null, OrderStatus.FILLED.getCode(), requestId, null);
        baseMapper.insert(order);

        // T+1结算
        settlementService.createSettlement(userId, order.getId(), netAmount);

        log.info("市价卖出成交 userId={} stock={} qty={} price={} amount={} commission={} (T+1到账)",
                userId, stock.getCode(), quantity, price, amount, commission);
        return buildOrderResponse(order, stock);
    }

    /** 创建限价买单（冻结资金，使用freezeBalance） */
    private OrderResponse createLimitBuyOrder(Long userId, Stock stock, OrderRequest request, BigDecimal freezeAmount) {
        userService.freezeBalance(userId, freezeAmount);

        int expireHours = tradingConfig.getLimitOrderMaxHours();
        LocalDateTime expireAt = LocalDateTime.now().plusHours(expireHours);

        Order order = createOrder(userId, stock.getId(), OrderSide.BUY.getCode(), OrderType.LIMIT.getCode(),
                request.getQuantity(), request.getLimitPrice(), null, null, null, freezeAmount,
                OrderStatus.PENDING.getCode(), request.getRequestId(), expireAt);
        order.setClientTimestamp(request.getClientTimestamp());
        baseMapper.insert(order);

        log.info("限价买单创建 userId={} stock={} qty={} limitPrice={} frozen={} expireAt={}",
                userId, stock.getCode(), request.getQuantity(), request.getLimitPrice(), freezeAmount, expireAt);
        return buildOrderResponse(order, stock);
    }

    /** 创建限价卖单（冻结持仓，使用freezePosition） */
    private OrderResponse createLimitSellOrder(Long userId, Stock stock, OrderRequest request) {
        positionService.freezePosition(userId, stock.getId(), request.getQuantity());

        int expireHours = tradingConfig.getLimitOrderMaxHours();
        LocalDateTime expireAt = LocalDateTime.now().plusHours(expireHours);

        Order order = createOrder(userId, stock.getId(), OrderSide.SELL.getCode(), OrderType.LIMIT.getCode(),
                request.getQuantity(), request.getLimitPrice(), null, null, null, null,
                OrderStatus.PENDING.getCode(), request.getRequestId(), expireAt);
        order.setClientTimestamp(request.getClientTimestamp());
        baseMapper.insert(order);

        log.info("限价卖单创建 userId={} stock={} qty={} limitPrice={} expireAt={}",
                userId, stock.getCode(), request.getQuantity(), request.getLimitPrice(), expireAt);
        return buildOrderResponse(order, stock);
    }

    /** 执行限价单（事务内，调用前需获取分布式锁） */
    @Transactional(rollbackFor = Exception.class)
    protected void executeLimitOrder(Order order, Stock stock, BigDecimal executePrice) {
        BigDecimal amount = executePrice.multiply(BigDecimal.valueOf(order.getQuantity()));
        BigDecimal commission = tradingConfig.calculateCommission(amount);

        // CAS更新订单状态为FILLED（防止分布式锁失效时的并发问题）
        int affected = baseMapper.casUpdateToFilled(order.getId(), executePrice, amount, commission);
        if (affected == 0) {
            // 状态已被其他操作改变（可能已取消或已过期）
            log.info("限价单{}状态已变更，跳过执行", order.getId());
            return;
        }

        // 状态更新成功后，安全执行资金和持仓操作
        if (OrderSide.BUY.getCode().equals(order.getOrderSide())) {
            // 买入限价单成交：
            // 1. 增加持仓
            // 2. 扣除冻结资金
            // 3. 退还多冻结的资金（冻结额 - 实际成交额 - 手续费）
            positionService.addPosition(order.getUserId(), order.getStockId(), order.getQuantity(), executePrice);

            BigDecimal frozenAmount = order.getFrozenAmount();
            BigDecimal actualCost = amount.add(commission);
            BigDecimal refund = frozenAmount.subtract(actualCost);

            userService.deductFrozenBalance(order.getUserId(), frozenAmount);
            if (refund.compareTo(BigDecimal.ZERO) > 0) {
                userService.updateBalance(order.getUserId(), refund);
            }
        } else {
            // 卖出限价单成交：
            // 1. 扣除冻结持仓
            // 2. T+1结算
            positionService.deductFrozenPosition(order.getUserId(), order.getStockId(), order.getQuantity());
            BigDecimal netAmount = amount.subtract(commission);
            settlementService.createSettlement(order.getUserId(), order.getId(), netAmount);
        }

        log.info("限价单成交 orderId={} stock={} qty={} limitPrice={} executePrice={} commission={}",
                order.getId(), stock.getCode(), order.getQuantity(), order.getLimitPrice(), executePrice, commission);
    }

    private Order createOrder(Long userId, Long stockId, String orderSide, String orderType,
            int quantity, BigDecimal limitPrice, BigDecimal filledPrice,
            BigDecimal filledAmount, BigDecimal commission, BigDecimal frozenAmount,
            String status, String requestId, LocalDateTime expireAt) {
        Order order = new Order();
        order.setUserId(userId);
        order.setStockId(stockId);
        order.setOrderSide(orderSide);
        order.setOrderType(orderType);
        order.setQuantity(quantity);
        order.setLimitPrice(limitPrice);
        order.setFilledPrice(filledPrice);
        order.setFilledAmount(filledAmount);
        order.setCommission(commission);
        order.setFrozenAmount(frozenAmount);
        order.setStatus(status);
        order.setRequestId(requestId);
        order.setExpireAt(expireAt);
        return order;
    }

    private OrderResponse buildOrderResponse(Order order, Stock stock) {
        OrderResponse resp = new OrderResponse();
        resp.setOrderId(order.getId());
        resp.setStockCode(stock.getCode());
        resp.setStockName(stock.getName());
        resp.setOrderSide(order.getOrderSide());
        resp.setOrderType(order.getOrderType());
        resp.setQuantity(order.getQuantity());
        resp.setLimitPrice(order.getLimitPrice());
        resp.setFilledPrice(order.getFilledPrice());
        resp.setFilledAmount(order.getFilledAmount());
        resp.setTriggerPrice(order.getTriggerPrice());
        resp.setTriggeredAt(order.getTriggeredAt());
        resp.setStatus(order.getStatus());
        resp.setExpireAt(order.getExpireAt());
        resp.setCreatedAt(order.getCreatedAt());
        return resp;
    }
}

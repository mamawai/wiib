package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibcommon.dto.*;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibservice.config.TradingConfig;
import com.mawai.wiibservice.mapper.FuturesOrderMapper;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.FuturesService;
import com.mawai.wiibservice.service.FuturesPositionIndexService;
import com.mawai.wiibservice.service.UserService;
import com.mawai.wiibservice.util.RedisLockUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesServiceImpl implements FuturesService {

    private final UserService userService;
    private final UserMapper userMapper;
    private final FuturesPositionMapper positionMapper;
    private final FuturesOrderMapper orderMapper;
    private final TradingConfig tradingConfig;
    private final RedisLockUtil redisLockUtil;
    private final CacheService cacheService;
    private final FuturesPositionIndexService positionIndexService;

    private static final String LIMIT_OPEN_LONG_PREFIX = "futures:limit:open_long:";
    private static final String LIMIT_OPEN_SHORT_PREFIX = "futures:limit:open_short:";
    private static final String LIMIT_CLOSE_LONG_PREFIX = "futures:limit:close_long:";
    private static final String LIMIT_CLOSE_SHORT_PREFIX = "futures:limit:close_short:";
    private static final String REDIS_PRICE_KEY_PREFIX = "market:price:";
    private static final String REDIS_MARK_PRICE_KEY_PREFIX = "market:markprice:";

    @PostConstruct
    void init() {
        rebuildLimitOrderZSets();
    }

    // ==================== 获取实时价格 ====================

    private BigDecimal getPrice(String symbol) {
        String price = cacheService.get(REDIS_PRICE_KEY_PREFIX + symbol);
        if (price == null) throw new BizException(ErrorCode.CRYPTO_PRICE_UNAVAILABLE);
        return new BigDecimal(price);
    }

    private BigDecimal getMarkPrice(String symbol) {
        String mp = cacheService.get(REDIS_MARK_PRICE_KEY_PREFIX + symbol);
        if (mp != null) return new BigDecimal(mp);
        return getPrice(symbol);
    }

    // ==================== 开仓 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FuturesOrderResponse openPosition(Long userId, FuturesOpenRequest request) {
        validateOpenRequest(request);
        getAndValidateUser(userId);

        // 获取价格
        BigDecimal price = getPrice(request.getSymbol());
        // 校验杠杆范围
        int leverage = normalizeLeverage(request.getLeverage());

        if ("MARKET".equals(request.getOrderType())) {
            return executeMarketOpen(userId, request, price, leverage);
        } else {
            tradingConfig.validateLimitPrice(request.getLimitPrice(), price);
            return createLimitOpenOrder(userId, request, leverage);
        }
    }

    /**
     * 市价开仓
     */
    private FuturesOrderResponse executeMarketOpen(Long userId, FuturesOpenRequest request, BigDecimal price, int leverage) {
        BigDecimal quantity = request.getQuantity();
        BigDecimal positionValue = price.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        BigDecimal commission = tradingConfig.calculateCryptoCommission(positionValue);
        BigDecimal totalCost = margin.add(commission);

        User user = userService.getById(userId);
        if (user.getBalance().compareTo(totalCost) < 0) {
            throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);
        }

        // 扣款
        int affected = userMapper.atomicUpdateBalance(userId, totalCost.negate());
        if (affected == 0) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);

        // 创建仓位
        FuturesPosition position = new FuturesPosition();
        position.setUserId(userId);
        position.setSymbol(request.getSymbol());
        position.setSide(request.getSide());
        position.setLeverage(leverage);
        position.setQuantity(quantity);
        position.setEntryPrice(price);
        position.setMargin(margin);
        position.setFundingFeeTotal(BigDecimal.ZERO);
        position.setStatus("OPEN");

        // 止损处理
        BigDecimal stopLossPercent = request.getStopLossPercent();
        BigDecimal stopLossPrice = null;
        if (stopLossPercent != null) {
            validateStopLossPercent(stopLossPercent, leverage);
            stopLossPrice = calcStopLossPrice(request.getSide(), price, margin, quantity, stopLossPercent);
            position.setStopLossPercent(stopLossPercent);
            position.setStopLossPrice(stopLossPrice);
        }
        positionMapper.insert(position);

        // 创建订单记录
        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(request.getSymbol());
        order.setOrderSide("LONG".equals(request.getSide()) ? "OPEN_LONG" : "OPEN_SHORT");
        order.setOrderType("MARKET");
        order.setQuantity(quantity);
        order.setLeverage(leverage);
        order.setFilledPrice(price);
        order.setFilledAmount(positionValue);
        order.setMarginAmount(margin);
        order.setCommission(commission);
        order.setStatus("FILLED");
        orderMapper.insert(order);

        // 注册到强平/止损索引
        setSlOrRegister(price, quantity, margin, position, stopLossPrice, request.getSymbol(), request.getSide());

        log.info("futures市价开仓 userId={} {} side={} qty={} price={} leverage={} margin={}",
                userId, request.getSymbol(), request.getSide(), quantity, price, leverage, margin);

        return buildOrderResponse(order);
    }

    private void setSlOrRegister(BigDecimal price, BigDecimal quantity, BigDecimal margin, FuturesPosition position, BigDecimal stopLossPrice, String symbol, String side) {
        if (stopLossPrice != null) {
            positionIndexService.setStopLoss(position.getId(), symbol, side, stopLossPrice, false);
        } else {
            BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(side, price, margin, quantity);
            positionIndexService.register(position.getId(), symbol, side, liqPrice);
        }
    }

    private FuturesOrderResponse createLimitOpenOrder(Long userId, FuturesOpenRequest request, int leverage) {
        BigDecimal limitPrice = request.getLimitPrice();
        BigDecimal quantity = request.getQuantity();
        BigDecimal positionValue = limitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        BigDecimal commission = tradingConfig.calculateCryptoCommission(positionValue);
        BigDecimal frozenAmount = margin.add(commission);

        if (request.getStopLossPercent() != null) {
            validateStopLossPercent(request.getStopLossPercent(), leverage);
        }

        // 冻结资金
        int affected = userMapper.atomicFreezeBalance(userId, frozenAmount);
        if (affected == 0) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);

        LocalDateTime expireAt = LocalDateTime.now().plusHours(tradingConfig.getLimitOrderMaxHours());

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setSymbol(request.getSymbol());
        order.setOrderSide("LONG".equals(request.getSide()) ? "OPEN_LONG" : "OPEN_SHORT");
        order.setOrderType("LIMIT");
        order.setQuantity(quantity);
        order.setLeverage(leverage);
        order.setLimitPrice(limitPrice);
        order.setFrozenAmount(frozenAmount);
        order.setStopLossPercent(request.getStopLossPercent());
        order.setStatus("PENDING");
        order.setExpireAt(expireAt);
        orderMapper.insert(order);

        addToLimitZSet(order);

        log.info("futures限价开仓单 userId={} {} side={} qty={} limit={} frozen={}",
                userId, request.getSymbol(), request.getSide(), quantity, limitPrice, frozenAmount);

        return buildOrderResponse(order);
    }

    // ==================== 平仓 ====================

    @Override
    public FuturesOrderResponse closePosition(Long userId, FuturesCloseRequest request) {
        String lockKey = "futures:pos:" + request.getPositionId();
        String lockValue = redisLockUtil.tryLock(lockKey, 30);
        if (lockValue == null) throw new BizException(ErrorCode.ORDER_PROCESSING);
        try {
            return SpringUtils.getAopProxy(this).doClosePosition(userId, request);
        } finally {
            redisLockUtil.unlock(lockKey, lockValue);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected FuturesOrderResponse doClosePosition(Long userId, FuturesCloseRequest request) {
        FuturesPosition position = getUserPosition(userId, request.getPositionId());

        BigDecimal closeQty = request.getQuantity();
        if (closeQty.compareTo(position.getQuantity()) > 0) {
            throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
        }

        if ("MARKET".equals(request.getOrderType())) {
            // 立即平仓
            return executeMarketClose(userId, position, closeQty);
        } else {
            tradingConfig.validateLimitPrice(request.getLimitPrice(), getPrice(position.getSymbol()));
            // 限价平仓
            return createLimitCloseOrder(userId, position, closeQty, request.getLimitPrice());
        }
    }

    /**
     * 校验用户持仓并返回
     */
    private FuturesPosition getUserPosition(Long userId, Long positionId) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !position.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FUTURES_POSITION_NOT_FOUND);
        }
        if (!"OPEN".equals(position.getStatus())) {
            throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);
        }
        return position;
    }

    private FuturesOrderResponse executeMarketClose(Long userId, FuturesPosition position, BigDecimal closeQty) {
        BigDecimal currentPrice = getPrice(position.getSymbol());
        BigDecimal pnl = calculatePnl(position.getSide(), position.getEntryPrice(), currentPrice, closeQty);
        BigDecimal closeValue = currentPrice.multiply(closeQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = tradingConfig.calculateCryptoCommission(closeValue);

        boolean isFullClose = closeQty.compareTo(position.getQuantity()) == 0;

        BigDecimal returnAmount;
        if (isFullClose) {
            returnAmount = position.getMargin().add(pnl).subtract(commission);
            returnAmount = returnAmount.max(BigDecimal.ZERO);

            int affected = positionMapper.casClosePosition(position.getId(), "CLOSED", currentPrice, pnl);
            if (affected == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);

            // 全部平仓，从强平索引移除
            positionIndexService.unregister(position.getId(), position.getSymbol(), position.getSide());
        } else {
            BigDecimal marginReturn = position.getMargin()
                    .multiply(closeQty)
                    .divide(position.getQuantity(), 2, RoundingMode.HALF_UP);
            returnAmount = marginReturn.add(pnl).subtract(commission);
            returnAmount = returnAmount.max(BigDecimal.ZERO);

            int affected = positionMapper.atomicPartialClose(position.getId(), closeQty, marginReturn);
            if (affected == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);

            // 部分平仓后更新索引
            BigDecimal newMargin = position.getMargin().subtract(marginReturn);
            BigDecimal newQty = position.getQuantity().subtract(closeQty);
            if (position.getStopLossPrice() != null) {
                BigDecimal newSlPrice = calcStopLossPrice(position.getSide(), position.getEntryPrice(), newMargin, newQty, position.getStopLossPercent());
                positionMapper.updateStopLoss(position.getId(), position.getStopLossPercent(), newSlPrice);
                positionIndexService.setStopLoss(position.getId(), position.getSymbol(), position.getSide(), newSlPrice, false);
            } else {
                BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(position.getSide(), position.getEntryPrice(), newMargin, newQty);
                positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);
            }
        }

        userMapper.atomicUpdateBalance(userId, returnAmount);

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT");
        order.setOrderType("MARKET");
        order.setQuantity(closeQty);
        order.setLeverage(position.getLeverage());
        order.setFilledPrice(currentPrice);
        order.setFilledAmount(closeValue);
        order.setCommission(commission);
        order.setRealizedPnl(pnl);
        order.setStatus("FILLED");
        orderMapper.insert(order);

        log.info("futures市价平仓 userId={} posId={} qty={} price={} pnl={} return={}",
                userId, position.getId(), closeQty, currentPrice, pnl, returnAmount);

        return buildOrderResponse(order);
    }

    private FuturesOrderResponse createLimitCloseOrder(Long userId, FuturesPosition position,
                                                        BigDecimal closeQty, BigDecimal limitPrice) {
        LocalDateTime expireAt = LocalDateTime.now().plusHours(tradingConfig.getLimitOrderMaxHours());

        FuturesOrder order = new FuturesOrder();
        order.setUserId(userId);
        order.setPositionId(position.getId());
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT");
        order.setOrderType("LIMIT");
        order.setQuantity(closeQty);
        order.setLeverage(position.getLeverage());
        order.setLimitPrice(limitPrice);
        order.setStatus("PENDING");
        order.setExpireAt(expireAt);
        orderMapper.insert(order);

        addToLimitZSet(order);

        log.info("futures限价平仓单 userId={} posId={} qty={} limit={}",
                userId, position.getId(), closeQty, limitPrice);

        return buildOrderResponse(order);
    }

    /**
     * 计算浮动盈亏
     */
    private BigDecimal calculatePnl(String side, BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal quantity) {
        if ("LONG".equals(side)) {
            return exitPrice.subtract(entryPrice).multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        } else {
            return entryPrice.subtract(exitPrice).multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        }
    }

    // ==================== 取消限价单 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FuturesOrderResponse cancelOrder(Long userId, Long orderId) {
        FuturesOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!"PENDING".equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_CANNOT_CANCEL);
        }

        int affected = orderMapper.casUpdateStatus(orderId, "PENDING", "CANCELLED");
        if (affected == 0) throw new BizException(ErrorCode.ORDER_CANNOT_CANCEL);

        removeFromLimitZSet(order);

        if (order.getOrderSide().startsWith("OPEN")) {
            userMapper.atomicUnfreezeBalance(userId, order.getFrozenAmount());
        }

        order.setStatus("CANCELLED");
        log.info("futures取消限价单 userId={} orderId={}", userId, orderId);
        return buildOrderResponse(order);
    }

    // ==================== 追加保证金 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addMargin(Long userId, FuturesAddMarginRequest request) {
        FuturesPosition position = getUserPosition(userId, request.getPositionId());

        BigDecimal amount = request.getAmount();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }

        int affected = userMapper.atomicUpdateBalance(userId, amount.negate());
        if (affected == 0) throw new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE);

        int affected2 = positionMapper.atomicAddMargin(request.getPositionId(), amount);
        if (affected2 == 0) throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);

        BigDecimal newMargin = position.getMargin().add(amount);
        if (position.getStopLossPrice() != null) {
            BigDecimal newSlPrice = calcStopLossPrice(position.getSide(), position.getEntryPrice(), newMargin, position.getQuantity(), position.getStopLossPercent());
            positionMapper.updateStopLoss(position.getId(), position.getStopLossPercent(), newSlPrice);
            positionIndexService.setStopLoss(position.getId(), position.getSymbol(), position.getSide(), newSlPrice, false);
        } else {
            BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(position.getSide(), position.getEntryPrice(), newMargin, position.getQuantity());
            positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);
        }

        log.info("futures追加保证金 userId={} posId={} amount={}", userId, request.getPositionId(), amount);
    }

    // ==================== 设置止损 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setStopLoss(Long userId, FuturesStopLossRequest request) {
        FuturesPosition position = getUserPosition(userId, request.getPositionId());

        BigDecimal stopLossPercent = request.getStopLossPercent();
        validateStopLossPercent(stopLossPercent, position.getLeverage());

        BigDecimal stopLossPrice = calcStopLossPrice(position.getSide(), position.getEntryPrice(),
                position.getMargin(), position.getQuantity(), stopLossPercent);

        positionMapper.updateStopLoss(request.getPositionId(), stopLossPercent, stopLossPrice);

        positionIndexService.setStopLoss(position.getId(), position.getSymbol(), position.getSide(), stopLossPrice, position.getStopLossPrice() == null);

        log.info("futures设置止损 userId={} posId={} percent={}% price={}", userId, request.getPositionId(), stopLossPercent, stopLossPrice);
    }

    // ==================== 查询仓位 ====================

    @Override
    public List<FuturesPositionDTO> getUserPositions(Long userId, String symbol) {
        LambdaQueryWrapper<FuturesPosition> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuturesPosition::getUserId, userId);
        wrapper.eq(FuturesPosition::getStatus, "OPEN");
        if (symbol != null && !symbol.isEmpty()) {
            wrapper.eq(FuturesPosition::getSymbol, symbol);
        }
        wrapper.orderByDesc(FuturesPosition::getCreatedAt);

        List<FuturesPosition> positions = positionMapper.selectList(wrapper);
        List<FuturesPositionDTO> result = new ArrayList<>();

        for (FuturesPosition pos : positions) {
            try {
                BigDecimal markPrice = getMarkPrice(pos.getSymbol());
                result.add(buildPositionDTO(pos, markPrice));
            } catch (Exception e) {
                log.warn("查询仓位价格失败 posId={}", pos.getId(), e);
            }
        }

        return result;
    }

    private FuturesPositionDTO buildPositionDTO(FuturesPosition pos, BigDecimal markPrice) {
        FuturesPositionDTO dto = new FuturesPositionDTO();
        dto.setId(pos.getId());
        dto.setUserId(pos.getUserId());
        dto.setSymbol(pos.getSymbol());
        dto.setSide(pos.getSide());
        dto.setLeverage(pos.getLeverage());
        dto.setQuantity(pos.getQuantity());
        dto.setEntryPrice(pos.getEntryPrice());
        dto.setMargin(pos.getMargin());
        dto.setFundingFeeTotal(pos.getFundingFeeTotal());
        dto.setStopLossPercent(pos.getStopLossPercent());
        dto.setStopLossPrice(pos.getStopLossPrice());
        dto.setStatus(pos.getStatus());
        dto.setClosedPrice(pos.getClosedPrice());
        dto.setClosedPnl(pos.getClosedPnl());
        dto.setCreatedAt(pos.getCreatedAt());
        dto.setUpdatedAt(pos.getUpdatedAt());

        dto.setCurrentPrice(getPrice(pos.getSymbol()));
        dto.setMarkPrice(markPrice);
        BigDecimal positionValue = markPrice.multiply(pos.getQuantity()).setScale(2, RoundingMode.HALF_UP);
        dto.setPositionValue(positionValue);

        BigDecimal unrealizedPnl = calculatePnl(pos.getSide(), pos.getEntryPrice(), markPrice, pos.getQuantity());
        dto.setUnrealizedPnl(unrealizedPnl);

        BigDecimal unrealizedPnlPct = pos.getMargin().compareTo(BigDecimal.ZERO) > 0
                ? unrealizedPnl.divide(pos.getMargin(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
        dto.setUnrealizedPnlPct(unrealizedPnlPct);

        BigDecimal effectiveMargin = pos.getMargin().add(unrealizedPnl);
        dto.setEffectiveMargin(effectiveMargin);

        BigDecimal maintenanceMargin = positionValue.multiply(tradingConfig.getFutures().getMaintenanceMarginRate());
        dto.setMaintenanceMargin(maintenanceMargin);

        BigDecimal liquidationPrice = positionIndexService.calcStaticLiqPrice(pos.getSide(), pos.getEntryPrice(), pos.getMargin(), pos.getQuantity());
        dto.setLiquidationPrice(liquidationPrice);

        BigDecimal entryValue = pos.getEntryPrice().multiply(pos.getQuantity());
        BigDecimal fundingFeePerCycle = entryValue.multiply(tradingConfig.getFutures().getFundingRate());
        dto.setFundingFeePerCycle(fundingFeePerCycle);

        return dto;
    }

    // ==================== 查询订单 ====================

    @Override
    public IPage<FuturesOrderResponse> getUserOrders(Long userId, String status, int pageNum, int pageSize, String symbol) {
        Page<FuturesOrder> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<FuturesOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuturesOrder::getUserId, userId);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(FuturesOrder::getStatus, status);
        }
        if (symbol != null && !symbol.isEmpty()) {
            wrapper.eq(FuturesOrder::getSymbol, symbol);
        }
        wrapper.orderByDesc(FuturesOrder::getCreatedAt);
        orderMapper.selectPage(page, wrapper);
        return page.convert(this::buildOrderResponse);
    }

    @Override
    public List<FuturesOrderResponse> getLatestOrders() {
        LambdaQueryWrapper<FuturesOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuturesOrder::getStatus, "FILLED")
                .orderByDesc(FuturesOrder::getCreatedAt)
                .last("LIMIT 20");
        return orderMapper.selectList(wrapper).stream()
                .map(this::buildOrderResponse).toList();
    }

    private FuturesOrderResponse buildOrderResponse(FuturesOrder order) {
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(order.getId());
        resp.setUserId(order.getUserId());
        resp.setPositionId(order.getPositionId());
        resp.setSymbol(order.getSymbol());
        resp.setOrderSide(order.getOrderSide());
        resp.setOrderType(order.getOrderType());
        resp.setQuantity(order.getQuantity());
        resp.setLeverage(order.getLeverage());
        resp.setLimitPrice(order.getLimitPrice());
        resp.setFrozenAmount(order.getFrozenAmount());
        resp.setFilledPrice(order.getFilledPrice());
        resp.setFilledAmount(order.getFilledAmount());
        resp.setMarginAmount(order.getMarginAmount());
        resp.setCommission(order.getCommission());
        resp.setRealizedPnl(order.getRealizedPnl());
        resp.setStatus(order.getStatus());
        resp.setExpireAt(order.getExpireAt());
        resp.setCreatedAt(order.getCreatedAt());
        return resp;
    }

    // ==================== 限价单触发 ====================

    @Override
    public void onPriceUpdate(String symbol, BigDecimal price) {
        // 开仓单触发检查
        String openLongKey = LIMIT_OPEN_LONG_PREFIX + symbol;
        String openShortKey = LIMIT_OPEN_SHORT_PREFIX + symbol;
        String closeLongKey = LIMIT_CLOSE_LONG_PREFIX + symbol;
        String closeShortKey = LIMIT_CLOSE_SHORT_PREFIX + symbol;

        // OPEN_LONG/CLOSE_SHORT: 限价买入，价格跌到限价触发 → limitPrice ≥ price
        // OPEN_SHORT/CLOSE_LONG: 限价卖出，价格涨到限价触发 → limitPrice ≤ price
        Set<String> openLongHits = cacheService.zRangeByScore(openLongKey, price.doubleValue(), Double.MAX_VALUE);
        Set<String> openShortHits = cacheService.zRangeByScore(openShortKey, 0, price.doubleValue());
        Set<String> closeLongHits = cacheService.zRangeByScore(closeLongKey, 0, price.doubleValue());
        Set<String> closeShortHits = cacheService.zRangeByScore(closeShortKey, price.doubleValue(), Double.MAX_VALUE);

        if (openLongHits != null && !openLongHits.isEmpty()) {
            cacheService.zRemove(openLongKey, openLongHits.toArray());
            for (String id : openLongHits) {
                Thread.startVirtualThread(() -> triggerLimitOrder(Long.parseLong(id), price));
            }
        }
        if (openShortHits != null && !openShortHits.isEmpty()) {
            cacheService.zRemove(openShortKey, openShortHits.toArray());
            for (String id : openShortHits) {
                Thread.startVirtualThread(() -> triggerLimitOrder(Long.parseLong(id), price));
            }
        }
        if (closeLongHits != null && !closeLongHits.isEmpty()) {
            cacheService.zRemove(closeLongKey, closeLongHits.toArray());
            for (String id : closeLongHits) {
                Thread.startVirtualThread(() -> triggerLimitOrder(Long.parseLong(id), price));
            }
        }
        if (closeShortHits != null && !closeShortHits.isEmpty()) {
            cacheService.zRemove(closeShortKey, closeShortHits.toArray());
            for (String id : closeShortHits) {
                Thread.startVirtualThread(() -> triggerLimitOrder(Long.parseLong(id), price));
            }
        }
    }

    private void triggerLimitOrder(Long orderId, BigDecimal triggerPrice) {
        try {
            var proxy = SpringUtils.getAopProxy(this);
            if (!proxy.markOrderTriggered(orderId, triggerPrice)) return;
            FuturesOrder order = orderMapper.selectById(orderId);
            if (order != null) {
                proxy.processTriggeredOrder(order);
            }
        } catch (Exception e) {
            log.error("futures限价单触发失败 orderId={}", orderId, e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected boolean markOrderTriggered(Long orderId, BigDecimal triggerPrice) {
        int affected = orderMapper.casUpdateToTriggered(orderId, triggerPrice);
        if (affected > 0) {
            log.info("futures限价单触发 orderId={} triggerPrice={}", orderId, triggerPrice);
            return true;
        }
        return false;
    }

    @Transactional(rollbackFor = Exception.class)
    protected void processTriggeredOrder(FuturesOrder order) {
        if (!"TRIGGERED".equals(order.getStatus())) return;

        User user = userService.getById(order.getUserId());
        if (user != null && Boolean.TRUE.equals(user.getIsBankrupt())) {
            orderMapper.casUpdateStatus(order.getId(), "TRIGGERED", "CANCELLED");
            return;
        }

        BigDecimal executePrice = order.getFilledPrice();
        if (executePrice == null) return;

        if (order.getOrderSide().startsWith("OPEN")) {
            processTriggeredOpenOrder(order, executePrice);
        } else {
            processTriggeredCloseOrder(order, executePrice);
        }
    }

    private void processTriggeredOpenOrder(FuturesOrder order, BigDecimal executePrice) {
        BigDecimal quantity = order.getQuantity();
        BigDecimal positionValue = executePrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(order.getLeverage()), 2, RoundingMode.CEILING);
        BigDecimal commission = tradingConfig.calculateCryptoCommission(positionValue);
        BigDecimal actualCost = margin.add(commission);

        // 解冻并扣款
        BigDecimal frozenAmount = order.getFrozenAmount();
        userMapper.atomicDeductFrozenBalance(order.getUserId(), frozenAmount);
        if (actualCost.compareTo(frozenAmount) < 0) {
            BigDecimal refund = frozenAmount.subtract(actualCost);
            userMapper.atomicUpdateBalance(order.getUserId(), refund);
        }

        // 创建仓位
        FuturesPosition position = new FuturesPosition();
        position.setUserId(order.getUserId());
        position.setSymbol(order.getSymbol());
        position.setSide(order.getOrderSide().contains("LONG") ? "LONG" : "SHORT");
        position.setLeverage(order.getLeverage());
        position.setQuantity(quantity);
        position.setEntryPrice(executePrice);
        position.setMargin(margin);
        position.setFundingFeeTotal(BigDecimal.ZERO);
        position.setStatus("OPEN");

        // 止损处理：用实际成交价算止损价
        BigDecimal stopLossPercent = order.getStopLossPercent();
        BigDecimal stopLossPrice = null;
        if (stopLossPercent != null) {
            stopLossPrice = calcStopLossPrice(position.getSide(), executePrice, margin, quantity, stopLossPercent);
            position.setStopLossPercent(stopLossPercent);
            position.setStopLossPrice(stopLossPrice);
        }
        positionMapper.insert(position);

        // 更新订单
        orderMapper.casUpdateToFilled(order.getId(), executePrice, positionValue, commission, margin, null);

        // 注册到强平/止损索引
        setSlOrRegister(executePrice, quantity, margin, position, stopLossPrice, order.getSymbol(), position.getSide());

        log.info("futures限价开仓成交 orderId={} price={} margin={}", order.getId(), executePrice, margin);
    }

    private void processTriggeredCloseOrder(FuturesOrder order, BigDecimal executePrice) {
        FuturesPosition position = positionMapper.selectById(order.getPositionId());
        if (position == null || !"OPEN".equals(position.getStatus())) {
            orderMapper.casUpdateStatus(order.getId(), "TRIGGERED", "CANCELLED");
            return;
        }

        BigDecimal closeQty = order.getQuantity();
        BigDecimal pnl = calculatePnl(position.getSide(), position.getEntryPrice(), executePrice, closeQty);
        BigDecimal closeValue = executePrice.multiply(closeQty).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = tradingConfig.calculateCryptoCommission(closeValue);

        boolean isFullClose = closeQty.compareTo(position.getQuantity()) == 0;

        BigDecimal returnAmount;
        if (isFullClose) {
            returnAmount = position.getMargin().add(pnl).subtract(commission);
            returnAmount = returnAmount.max(BigDecimal.ZERO);
            positionMapper.casClosePosition(position.getId(), "CLOSED", executePrice, pnl);

            // 全部平仓，从强平索引移除
            positionIndexService.unregister(position.getId(), position.getSymbol(), position.getSide());
        } else {
            BigDecimal marginReturn = position.getMargin()
                    .multiply(closeQty)
                    .divide(position.getQuantity(), 2, RoundingMode.HALF_UP);
            returnAmount = marginReturn.add(pnl).subtract(commission);
            returnAmount = returnAmount.max(BigDecimal.ZERO);
            positionMapper.atomicPartialClose(position.getId(), closeQty, marginReturn);

            BigDecimal newMargin = position.getMargin().subtract(marginReturn);
            BigDecimal newQty = position.getQuantity().subtract(closeQty);
            BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(position.getSide(), position.getEntryPrice(), newMargin, newQty);
            positionIndexService.updateLiquidationPrice(position.getId(), position.getSymbol(), position.getSide(), liqPrice);
        }

        userMapper.atomicUpdateBalance(order.getUserId(), returnAmount);
        orderMapper.casUpdateToFilled(order.getId(), executePrice, closeValue, commission, null, pnl);

        log.info("futures限价平仓成交 orderId={} price={} pnl={}", order.getId(), executePrice, pnl);
    }

    // ==================== 恢复限价单 ====================

    @Override
    public void recoverLimitOrders(String symbol, BigDecimal periodLow, BigDecimal periodHigh) {
        String openLongKey = LIMIT_OPEN_LONG_PREFIX + symbol;
        String openShortKey = LIMIT_OPEN_SHORT_PREFIX + symbol;
        String closeLongKey = LIMIT_CLOSE_LONG_PREFIX + symbol;
        String closeShortKey = LIMIT_CLOSE_SHORT_PREFIX + symbol;

        var openLongHits = cacheService.zRangeByScoreWithScores(openLongKey, periodLow.doubleValue(), Double.MAX_VALUE);
        var openShortHits = cacheService.zRangeByScoreWithScores(openShortKey, 0, periodHigh.doubleValue());
        var closeLongHits = cacheService.zRangeByScoreWithScores(closeLongKey, 0, periodHigh.doubleValue());
        var closeShortHits = cacheService.zRangeByScoreWithScores(closeShortKey, periodLow.doubleValue(), Double.MAX_VALUE);

        int count = 0;
        count += recoverHits(openLongKey, openLongHits);
        count += recoverHits(openShortKey, openShortHits);
        count += recoverHits(closeLongKey, closeLongHits);
        count += recoverHits(closeShortKey, closeShortHits);

        if (count > 0) {
            log.info("futures恢复触发限价单 symbol={} low={} high={} 共{}个", symbol, periodLow, periodHigh, count);
        }
    }

    private int recoverHits(String key, Set<ZSetOperations.TypedTuple<String>> hits) {
        if (hits == null || hits.isEmpty()) return 0;
        cacheService.zRemove(key, hits.stream().map(ZSetOperations.TypedTuple::getValue).toArray());
        for (var tuple : hits) {
            BigDecimal limitPrice = BigDecimal.valueOf(tuple.getScore());
            Thread.startVirtualThread(() -> triggerLimitOrder(Long.parseLong(Objects.requireNonNull(tuple.getValue())), limitPrice));
        }
        return hits.size();
    }

    // ==================== 过期限价单处理 ====================

    @Override
    public void expireLimitOrders() {
        List<FuturesOrder> expiredOrders = orderMapper.selectList(new LambdaQueryWrapper<FuturesOrder>()
                .eq(FuturesOrder::getStatus, "PENDING")
                .eq(FuturesOrder::getOrderType, "LIMIT")
                .lt(FuturesOrder::getExpireAt, LocalDateTime.now()));
        if (expiredOrders.isEmpty()) return;

        int maxConcurrency = tradingConfig.getLimitOrderProcessing().getMaxConcurrency();
        Semaphore semaphore = new Semaphore(maxConcurrency);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (FuturesOrder order : expiredOrders) {
                executor.submit(() -> {
                    try {
                        if (!semaphore.tryAcquire(5, TimeUnit.SECONDS)) return;
                        try {processExpiredOrder(order);}
                        finally {semaphore.release();}
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }
    }

    private void processExpiredOrder(FuturesOrder order) {
        try {
            SpringUtils.getAopProxy(this).doExpireOrder(order);
        } catch (Exception e) {
            log.error("futures过期订单处理失败 orderId={}", order.getId(), e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void doExpireOrder(FuturesOrder order) {
        int affected = orderMapper.casUpdateStatus(order.getId(), "PENDING", "EXPIRED");
        if (affected == 0) return;

        removeFromLimitZSet(order);

        if (order.getOrderSide().startsWith("OPEN")) {
            userMapper.atomicUnfreezeBalance(order.getUserId(), order.getFrozenAmount());
        }

        log.info("futures限价单过期 orderId={}", order.getId());
    }

    // ==================== 资金费率扣除 ====================

    /**
     * 资金费率扣除 扣费优先级:
     * 1. 用户可用余额(不影响强平价)
     * 2. 逐仓保证金(强平价变近)
     * 3. 保证金扣光 → 检查强平
     */
    @Override
    public void chargeFundingFeeAll() {
        List<FuturesPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getStatus, "OPEN"));
        if (positions.isEmpty()) return;

        int successCount = 0;
        int failCount = 0;

        for (FuturesPosition pos : positions) {
            try {
                BigDecimal entryValue = pos.getEntryPrice().multiply(pos.getQuantity());
                BigDecimal fee = entryValue.multiply(tradingConfig.getFutures().getFundingRate())
                        .setScale(2, RoundingMode.HALF_UP);

                // 1. 优先从用户余额扣(不影响强平价)
                int affected = userMapper.atomicUpdateBalance(pos.getUserId(), fee.negate());
                if (affected > 0) {
                    positionMapper.atomicAddFundingFeeTotal(pos.getId(), fee);
                    successCount++;
                    continue;
                }

                // 2. 余额不足，从逐仓保证金扣(强平价变近)
                affected = positionMapper.atomicDeductFundingFee(pos.getId(), fee);
                if (affected > 0) {
                    BigDecimal newMargin = pos.getMargin().subtract(fee);
                    BigDecimal liqPrice = positionIndexService.calcStaticLiqPrice(pos.getSide(), pos.getEntryPrice(), newMargin, pos.getQuantity());
                    positionIndexService.updateLiquidationPrice(pos.getId(), pos.getSymbol(), pos.getSide(), liqPrice);
                    successCount++;
                    continue;
                }

                // 3. 保证金也不够，扣光剩余保证金 → 检查强平
                affected = positionMapper.atomicDeductFundingFeePartial(pos.getId());
                if (affected > 0) {
                    BigDecimal mp = getMarkPrice(pos.getSymbol());
                    checkAndLiquidate(pos.getId(), mp);
                    successCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                log.error("futures扣除资金费率失败 posId={}", pos.getId(), e);
                failCount++;
            }
        }

        log.info("futures资金费率扣除完成 成功{} 失败{}", successCount, failCount);
    }

    // ==================== 强制平仓 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forceClose(Long positionId, BigDecimal price) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return;
        }

        // 计算盈亏（这里是平仓是负数所以下面要add）
        BigDecimal pnl = calculatePnl(position.getSide(), position.getEntryPrice(), price, position.getQuantity());
        BigDecimal closeValue = price.multiply(position.getQuantity()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = tradingConfig.calculateCryptoCommission(closeValue);

        BigDecimal returnAmount = position.getMargin().add(pnl).subtract(commission);
        returnAmount = returnAmount.max(BigDecimal.ZERO); // 如果击穿到负数 则返回0

        int affected = positionMapper.casClosePosition(positionId, "LIQUIDATED", price, pnl);
        if (affected == 0) return;

        positionIndexService.unregister(positionId, position.getSymbol(), position.getSide());

        if (returnAmount.compareTo(BigDecimal.ZERO) > 0) {
            userMapper.atomicUpdateBalance(position.getUserId(), returnAmount);
        }

        FuturesOrder order = new FuturesOrder();
        order.setUserId(position.getUserId());
        order.setPositionId(positionId);
        order.setSymbol(position.getSymbol());
        order.setOrderSide("LONG".equals(position.getSide()) ? "CLOSE_LONG" : "CLOSE_SHORT");
        order.setOrderType("MARKET");
        order.setQuantity(position.getQuantity());
        order.setLeverage(position.getLeverage());
        order.setFilledPrice(price);
        order.setFilledAmount(closeValue);
        order.setCommission(commission);
        order.setRealizedPnl(pnl);
        order.setStatus("LIQUIDATED");
        orderMapper.insert(order);

        log.warn("futures强制平仓 posId={} userId={} price={} pnl={}", positionId, position.getUserId(), price, pnl);
    }

    /**
     * 检查是否强平
     */
    private void checkAndLiquidate(Long positionId, BigDecimal currentPrice) {
        FuturesPosition position = positionMapper.selectById(positionId);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return;
        }

        BigDecimal unrealizedPnl = calculatePnl(position.getSide(), position.getEntryPrice(), currentPrice, position.getQuantity());
        BigDecimal effectiveMargin = position.getMargin().add(unrealizedPnl);
        BigDecimal positionValue = currentPrice.multiply(position.getQuantity());
        BigDecimal maintenanceMargin = positionValue.multiply(tradingConfig.getFutures().getMaintenanceMarginRate());

        if (effectiveMargin.compareTo(maintenanceMargin) <= 0) {
            SpringUtils.getAopProxy(this).forceClose(positionId, currentPrice);
        }
    }

    // ==================== ZSet索引管理 ====================

    private void addToLimitZSet(FuturesOrder order) {
        String key = getZSetKey(order.getOrderSide(), order.getSymbol());
        cacheService.zAdd(key, order.getId().toString(), order.getLimitPrice().doubleValue());
    }

    private void removeFromLimitZSet(FuturesOrder order) {
        String key = getZSetKey(order.getOrderSide(), order.getSymbol());
        cacheService.zRemove(key, order.getId().toString());
    }

    private String getZSetKey(String orderSide, String symbol) {
        return switch (orderSide) {
            case "OPEN_LONG" -> LIMIT_OPEN_LONG_PREFIX + symbol;
            case "OPEN_SHORT" -> LIMIT_OPEN_SHORT_PREFIX + symbol;
            case "CLOSE_LONG" -> LIMIT_CLOSE_LONG_PREFIX + symbol;
            case "CLOSE_SHORT" -> LIMIT_CLOSE_SHORT_PREFIX + symbol;
            default -> throw new IllegalArgumentException("Invalid orderSide: " + orderSide);
        };
    }

    private void rebuildLimitOrderZSets() {
        List<FuturesOrder> pendingOrders = orderMapper.selectList(new LambdaQueryWrapper<FuturesOrder>()
                .eq(FuturesOrder::getStatus, "PENDING")
                .eq(FuturesOrder::getOrderType, "LIMIT"));
        if (pendingOrders.isEmpty()) return;

        for (FuturesOrder order : pendingOrders) {
            addToLimitZSet(order);
        }
        log.info("重建futures限价单ZSet索引 共{}个订单", pendingOrders.size());
    }

    @Override
    public void executeTriggeredOrders() {
        List<FuturesOrder> triggeredOrders = orderMapper.selectList(new LambdaQueryWrapper<FuturesOrder>()
                .eq(FuturesOrder::getStatus, "TRIGGERED")
                .eq(FuturesOrder::getOrderType, "LIMIT"));
        if (triggeredOrders.isEmpty()) return;

        for (FuturesOrder order : triggeredOrders) {
            Thread.startVirtualThread(() -> {
                try {
                    SpringUtils.getAopProxy(this).processTriggeredOrder(order);
                } catch (Exception e) {
                    log.error("补处理TRIGGERED订单失败 orderId={}", order.getId(), e);
                }
            });
        }
        log.info("补处理TRIGGERED孤儿单 共{}个", triggeredOrders.size());
    }

    // ==================== 工具方法 ====================

    /** 百分比算止损价: percent=保留保证金% LONG跌到这个价止损 SHORT涨到这个价止损 */
    private BigDecimal calcStopLossPrice(String side, BigDecimal entryPrice, BigDecimal margin, BigDecimal quantity, BigDecimal percent) {
        // 最大亏损比例 = 1 - percent/100
        BigDecimal lossRatio = BigDecimal.ONE.subtract(percent.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal maxLoss = margin.multiply(lossRatio);
        BigDecimal priceMove = maxLoss.divide(quantity, 2, RoundingMode.HALF_UP);
        if ("LONG".equals(side)) {
            return entryPrice.subtract(priceMove);
        } else {
            return entryPrice.add(priceMove);
        }
    }

    /**
     * 校验止损百分比(保留保证金%) 必须 > 强平时剩余保证金%
     * <p>
     * 推导(以LONG为例):
     *   强平价 liqP = (entry*qty - margin) / (qty*(1-mmr))</br>
     *   强平时维持保证金 = liqP * qty * mmr = (entry*qty - margin) * mmr / (1-mmr)</br>
     *   又 margin = entry*qty / leverage  代入:</br>
     *   维持保证金 = entry*qty*(1 - 1/leverage) * mmr / (1-mmr) = margin*(leverage-1) * mmr / (1-mmr)</br>
     *   剩余% = 维持保证金/margin * 100 = mmr*(leverage-1)/(1-mmr) * 100</br>
     * <p>
     * 止损保留% 必须 > 此值 否则强平先触发 止损无意义
     */
    private void validateStopLossPercent(BigDecimal percent, int leverage) {
        if (percent == null || percent.compareTo(BigDecimal.ZERO) <= 0 || percent.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw new BizException(ErrorCode.FUTURES_INVALID_STOP_LOSS);
        }
        BigDecimal mmr = tradingConfig.getFutures().getMaintenanceMarginRate();
        BigDecimal minPercent = mmr.multiply(BigDecimal.valueOf(leverage - 1))
                .divide(BigDecimal.ONE.subtract(mmr), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        if (percent.compareTo(minPercent) <= 0) {
            throw new BizException(ErrorCode.FUTURES_INVALID_STOP_LOSS);
        }
    }

    private void validateOpenRequest(FuturesOpenRequest request) {
        if (request.getQuantity() == null || request.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
        }
        if (request.getSymbol() == null || request.getSymbol().isBlank()) {
            throw new BizException(ErrorCode.CRYPTO_SYMBOL_INVALID);
        }
        if (!"LONG".equals(request.getSide()) && !"SHORT".equals(request.getSide())) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        if (!"MARKET".equals(request.getOrderType()) && !"LIMIT".equals(request.getOrderType())) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
    }

    private void getAndValidateUser(Long userId) {
        User user = userService.getById(userId);
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        if (Boolean.TRUE.equals(user.getIsBankrupt())) throw new BizException(ErrorCode.USER_BANKRUPT);
    }

    private int normalizeLeverage(Integer leverage) {
        if (leverage == null || leverage < 1) {
            return 1;
        }
        int maxLeverage = tradingConfig.getFutures().getMaxLeverage();
        if (leverage > maxLeverage) {
            throw new BizException(ErrorCode.FUTURES_INVALID_LEVERAGE);
        }
        return leverage;
    }
}

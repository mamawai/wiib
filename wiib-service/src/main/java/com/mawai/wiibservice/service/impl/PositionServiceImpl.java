package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.dto.PositionDTO;
import com.mawai.wiibcommon.entity.Position;
import com.mawai.wiibcommon.entity.Stock;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.mapper.PositionMapper;
import com.mawai.wiibservice.mapper.StockMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.PositionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 持仓服务实现
 * 使用数据库原子操作保证并发安全
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionServiceImpl extends ServiceImpl<PositionMapper, Position> implements PositionService {

    private final StockMapper stockMapper;
    private final CacheService cacheService;

    @Override
    public Position findByUserAndStock(Long userId, Long stockId) {
        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Position::getUserId, userId)
                .eq(Position::getStockId, stockId);
        return baseMapper.selectOne(wrapper);
    }

    @Override
    public List<PositionDTO> getUserPositions(Long userId) {
        LambdaQueryWrapper<Position> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Position::getUserId, userId)
                .gt(Position::getQuantity, 0);

        List<Position> positions = baseMapper.selectList(wrapper);

        return positions.stream()
                .map(this::buildPositionDTO)
                .collect(Collectors.toList());
    }

    @Override
    public void addPosition(Long userId, Long stockId, int quantity, BigDecimal price) {
        baseMapper.upsertPosition(userId, stockId, quantity, price);
        log.info("用户{}增加持仓 股票{} 数量{} 价格{}", userId, stockId, quantity, price);
    }

    @Override
    public boolean reducePosition(Long userId, Long stockId, int quantity) {
        int affected = baseMapper.atomicReduceQuantity(userId, stockId, quantity);
        if (affected == 0) {
            throw new BizException(ErrorCode.POSITION_NOT_ENOUGH);
        }
        baseMapper.deleteEmptyPosition(userId, stockId);
        log.info("用户{}减少持仓 股票{} 数量{}", userId, stockId, quantity);
        return true;
    }

    @Override
    public BigDecimal calculateTotalMarketValue(Long userId) {
        List<PositionDTO> positions = getUserPositions(userId);
        return positions.stream()
                .map(PositionDTO::getMarketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public void freezePosition(Long userId, Long stockId, int quantity) {
        int affected = baseMapper.atomicFreezePosition(userId, stockId, quantity);
        if (affected == 0) {
            throw new BizException(ErrorCode.POSITION_NOT_ENOUGH);
        }
        log.info("用户{}冻结持仓 股票{} 数量{}", userId, stockId, quantity);
    }

    @Override
    public void unfreezePosition(Long userId, Long stockId, int quantity) {
        int affected = baseMapper.atomicUnfreezePosition(userId, stockId, quantity);
        if (affected == 0) {
            log.warn("解冻持仓失败 用户{} 股票{} 数量{}", userId, stockId, quantity);
        } else {
            log.info("用户{}解冻持仓 股票{} 数量{}", userId, stockId, quantity);
        }
    }

    @Override
    public void deductFrozenPosition(Long userId, Long stockId, int quantity) {
        int affected = baseMapper.atomicDeductFrozenPosition(userId, stockId, quantity);
        if (affected == 0) {
            throw new BizException(ErrorCode.FROZEN_POSITION_NOT_ENOUGH);
        }
        baseMapper.deleteEmptyPosition(userId, stockId);
        log.info("用户{}扣除冻结持仓 股票{} 数量{}", userId, stockId, quantity);
    }

    private PositionDTO buildPositionDTO(Position position) {
        Stock stock = stockMapper.selectById(position.getStockId());
        if (stock == null) {
            log.warn("持仓关联股票不存在: {}", position.getStockId());
            return null;
        }

        // 从Redis获取实时价格
        BigDecimal currentPrice = cacheService.getCurrentPrice(stock.getId());
        if (currentPrice == null) {
            // 非交易时段用开盘价（AI预生成）
            currentPrice = stock.getOpen() != null ? stock.getOpen() : stock.getPrevClose();
        }

        int totalQuantity = position.getQuantity() +
                (position.getFrozenQuantity() != null ? position.getFrozenQuantity() : 0);
        BigDecimal marketValue = currentPrice.multiply(BigDecimal.valueOf(totalQuantity));
        BigDecimal costValue = position.getAvgCost().multiply(BigDecimal.valueOf(totalQuantity));
        BigDecimal profit = marketValue.subtract(costValue);
        BigDecimal profitPct = costValue.compareTo(BigDecimal.ZERO) > 0
                ? profit.divide(costValue, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        PositionDTO dto = new PositionDTO();
        dto.setId(position.getId());
        dto.setStockId(stock.getId());
        dto.setStockCode(stock.getCode());
        dto.setStockName(stock.getName());
        dto.setQuantity(totalQuantity);
        dto.setAvgCost(position.getAvgCost());
        dto.setCurrentPrice(currentPrice);
        dto.setMarketValue(marketValue);
        dto.setProfit(profit);
        dto.setProfitPct(profitPct);

        return dto;
    }
}

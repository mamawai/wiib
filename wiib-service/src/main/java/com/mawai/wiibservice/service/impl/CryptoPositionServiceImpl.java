package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.entity.CryptoPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.mapper.CryptoPositionMapper;
import com.mawai.wiibservice.service.CryptoPositionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class CryptoPositionServiceImpl extends ServiceImpl<CryptoPositionMapper, CryptoPosition> implements CryptoPositionService {

    @Override
    public CryptoPosition findByUserAndSymbol(Long userId, String symbol) {
        return baseMapper.selectOne(new LambdaQueryWrapper<CryptoPosition>()
                .eq(CryptoPosition::getUserId, userId)
                .eq(CryptoPosition::getSymbol, symbol));
    }

    @Override
    public List<CryptoPosition> getUserPositions(Long userId) {
        List<CryptoPosition> list = baseMapper.selectList(new LambdaQueryWrapper<CryptoPosition>()
                .eq(CryptoPosition::getUserId, userId)
                .and(w -> w.gt(CryptoPosition::getQuantity, 0)
                        .or()
                        .gt(CryptoPosition::getFrozenQuantity, 0)));
        return list.isEmpty() ? Collections.emptyList() : list;
    }

    @Override
    public void addPosition(Long userId, String symbol, BigDecimal quantity, BigDecimal price, BigDecimal discount) {
        baseMapper.upsertPosition(userId, symbol, quantity, price, discount != null ? discount : BigDecimal.ZERO);
        log.info("用户{}增加crypto持仓 {} 数量{} 价格{}", userId, symbol, quantity, price);
    }

    @Override
    public void reducePosition(Long userId, String symbol, BigDecimal quantity) {
        int affected = baseMapper.atomicReduceQuantity(userId, symbol, quantity);
        if (affected == 0) {
            throw new BizException(ErrorCode.POSITION_NOT_ENOUGH);
        }
        baseMapper.deleteEmptyPosition(userId, symbol);
    }

    @Override
    public void freezePosition(Long userId, String symbol, BigDecimal quantity) {
        int affected = baseMapper.atomicFreezePosition(userId, symbol, quantity);
        if (affected == 0) {
            throw new BizException(ErrorCode.POSITION_NOT_ENOUGH);
        }
    }

    @Override
    public void unfreezePosition(Long userId, String symbol, BigDecimal quantity) {
        int affected = baseMapper.atomicUnfreezePosition(userId, symbol, quantity);
        if (affected == 0) {
            log.warn("解冻crypto持仓失败 用户{} {} 数量{}", userId, symbol, quantity);
        }
    }

    @Override
    public void deductFrozenPosition(Long userId, String symbol, BigDecimal quantity) {
        int affected = baseMapper.atomicDeductFrozenPosition(userId, symbol, quantity);
        if (affected == 0) {
            throw new BizException(ErrorCode.FROZEN_POSITION_NOT_ENOUGH);
        }
        baseMapper.deleteEmptyPosition(userId, symbol);
    }
}

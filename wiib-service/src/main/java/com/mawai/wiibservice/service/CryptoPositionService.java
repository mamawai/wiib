package com.mawai.wiibservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mawai.wiibcommon.entity.CryptoPosition;

import java.math.BigDecimal;
import java.util.List;

public interface CryptoPositionService extends IService<CryptoPosition> {

    CryptoPosition findByUserAndSymbol(Long userId, String symbol);

    List<CryptoPosition> getUserPositions(Long userId);

    void addPosition(Long userId, String symbol, BigDecimal quantity, BigDecimal price, BigDecimal discount);

    void reducePosition(Long userId, String symbol, BigDecimal quantity);

    void freezePosition(Long userId, String symbol, BigDecimal quantity);

    void unfreezePosition(Long userId, String symbol, BigDecimal quantity);

    void deductFrozenPosition(Long userId, String symbol, BigDecimal quantity);
}

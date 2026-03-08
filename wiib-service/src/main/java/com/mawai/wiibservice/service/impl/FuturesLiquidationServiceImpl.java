package com.mawai.wiibservice.service.impl;

import com.mawai.wiibservice.service.FuturesLiquidationService;
import com.mawai.wiibservice.service.FuturesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 永续合约强平服务
 * Mark Price更新时通过ZSet范围查询找出需要强平/止损的仓位
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesLiquidationServiceImpl implements FuturesLiquidationService {

    private final FuturesService futuresService;
    private final StringRedisTemplate redisTemplate;

    private static final String LIQ_LONG_PREFIX = "futures:liq:long:";
    private static final String LIQ_SHORT_PREFIX = "futures:liq:short:";
    private static final String SL_LONG_PREFIX = "futures:sl:long:";
    private static final String SL_SHORT_PREFIX = "futures:sl:short:";

    @Override
    public void checkOnPriceUpdate(String symbol, BigDecimal markPrice) {
        double mp = markPrice.doubleValue();

        // LONG强平: markPrice <= liqPrice → 找 score >= markPrice
        processHits(LIQ_LONG_PREFIX + symbol, mp, Double.MAX_VALUE, markPrice, "强平");
        // SHORT强平: markPrice >= liqPrice → 找 score <= markPrice
        processHits(LIQ_SHORT_PREFIX + symbol, 0, mp, markPrice, "强平");
        // LONG止损: markPrice <= stopLoss → 找 score >= markPrice
        processHits(SL_LONG_PREFIX + symbol, mp, Double.MAX_VALUE, markPrice, "止损");
        // SHORT止损: markPrice >= stopLoss → 找 score <= markPrice
        processHits(SL_SHORT_PREFIX + symbol, 0, mp, markPrice, "止损");
    }

    private void processHits(String key, double min, double max, BigDecimal markPrice, String reason) {
        Set<String> hits = redisTemplate.opsForZSet().rangeByScore(key, min, max);
        if (hits == null || hits.isEmpty()) return;

        redisTemplate.opsForZSet().remove(key, hits.toArray());

        for (String id : hits) {
            Thread.startVirtualThread(() -> {
                try {
                    Long positionId = Long.parseLong(id);
                    futuresService.forceClose(positionId, markPrice);
                    log.info("futures{}触发 posId={} markPrice={}", reason, positionId, markPrice);
                } catch (Exception e) {
                    log.error("futures{}失败 posId={}", reason, id, e);
                }
            });
        }
    }
}

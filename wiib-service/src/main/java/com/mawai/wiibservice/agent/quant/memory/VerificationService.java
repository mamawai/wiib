package com.mawai.wiibservice.agent.quant.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantForecastVerification;
import com.mawai.wiibcommon.entity.QuantHorizonForecast;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.mapper.QuantForecastVerificationMapper;
import com.mawai.wiibservice.mapper.QuantHorizonForecastMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {

    private final BinanceRestClient binanceRestClient;
    private final QuantForecastVerificationMapper verificationMapper;
    private final QuantHorizonForecastMapper horizonMapper;

    private static final int CORRECT_THRESHOLD_BPS = 2;
    private static final int NO_TRADE_THRESHOLD_BPS = 10;

    /**
     * 验证一个预测周期的所有区间裁决
     */
    public void verifyCycle(QuantForecastCycle cycle, List<QuantHorizonForecast> forecasts) {
        if (cycle == null || forecasts == null) return;
        if (verificationMapper.countByCycleId(cycle.getCycleId()) > 0) return; // 已验证

        LocalDateTime forecastTime = cycle.getForecastTime();
        BigDecimal priceAtForecast = getHistoricalPrice(cycle.getSymbol(), forecastTime);
        if (priceAtForecast == null) {
            log.warn("[Verify] 无法获取预测时价格 cycle={} time={}", cycle.getCycleId(), forecastTime);
            return;
        }

        for (QuantHorizonForecast f : forecasts) {
            int minutesAfter = horizonToMinutes(f.getHorizon());
            if (minutesAfter <= 0) continue;

            LocalDateTime targetTime = forecastTime.plusMinutes(minutesAfter);
            if (targetTime.isAfter(LocalDateTime.now(ZoneOffset.UTC))) continue; // 还没到验证时间

            BigDecimal priceAfter = getHistoricalPrice(cycle.getSymbol(), targetTime);
            if (priceAfter == null) continue;

            int changeBps = priceAtForecast.compareTo(BigDecimal.ZERO) == 0 ? 0 :
                    priceAfter.subtract(priceAtForecast)
                            .multiply(BigDecimal.valueOf(10000))
                            .divide(priceAtForecast, 0, RoundingMode.HALF_UP)
                            .intValue();

            boolean correct = isCorrect(f.getDirection(), changeBps);

            QuantForecastVerification v = new QuantForecastVerification();
            v.setCycleId(cycle.getCycleId());
            v.setSymbol(cycle.getSymbol());
            v.setHorizon(f.getHorizon());
            v.setPredictedDirection(f.getDirection());
            v.setPredictedConfidence(f.getConfidence());
            v.setActualPriceAtForecast(priceAtForecast);
            v.setActualPriceAfter(priceAfter);
            v.setActualChangeBps(changeBps);
            v.setPredictionCorrect(correct);
            v.setVerifiedAt(LocalDateTime.now(ZoneOffset.UTC));
            verificationMapper.insert(v);

            log.info("[Verify] {} {} predicted={} actual={}bps correct={}",
                    cycle.getCycleId(), f.getHorizon(), f.getDirection(), changeBps, correct);
        }
    }

    /**
     * 从Binance获取指定时刻的1m K线收盘价
     */
    BigDecimal getHistoricalPrice(String symbol, LocalDateTime time) {
        try {
            long endTimeMs = time.toInstant(ZoneOffset.UTC).toEpochMilli() + 60_000;
            String json = binanceRestClient.getFuturesKlines(symbol, "1m", 1, endTimeMs);
            if (json == null || json.isBlank()) return null;
            JSONArray root = JSON.parseArray(json);
            if (root.isEmpty()) return null;
            JSONArray kline = root.getJSONArray(0);
            return new BigDecimal(kline.getString(4)); // close price
        } catch (Exception e) {
            log.warn("[Verify] 获取历史价格失败 symbol={} time={}: {}", symbol, time, e.getMessage());
            return null;
        }
    }

    private boolean isCorrect(String direction, int changeBps) {
        return switch (direction) {
            case "LONG" -> changeBps > CORRECT_THRESHOLD_BPS;
            case "SHORT" -> changeBps < -CORRECT_THRESHOLD_BPS;
            case "NO_TRADE" -> Math.abs(changeBps) < NO_TRADE_THRESHOLD_BPS;
            default -> false;
        };
    }

    private int horizonToMinutes(String horizon) {
        return switch (horizon) {
            case "0_10" -> 10;
            case "10_20" -> 20;
            case "20_30" -> 30;
            default -> 0;
        };
    }
}

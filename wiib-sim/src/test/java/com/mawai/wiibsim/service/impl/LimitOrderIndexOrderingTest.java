package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.BStockService;
import com.mawai.wiibsim.service.BuffService;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.MarginAccountService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限价单触发索引与DB的先后：DB是事实，索引跟着事实走。
 * <p>
 * 倒过来先摘索引再改DB，中间任一步没走成（抢不到锁、CAS抛异常），单子就永远停在 PENDING
 * 而索引里已经没它了——价格再穿越也没人盯，只有重启重建索引才找得回来。这个类只钉这一条。
 */
class LimitOrderIndexOrderingTest {

    /** 现货：抢不到执行锁就直接放手，命中的那条索引必须原地不动，下个tick还能捞到 */
    @Test
    @SuppressWarnings("unchecked")
    void 现货抢不到锁时不许摘索引() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rangeByScore("crypto:limit:buy:BTCUSDT", 50000d, Double.MAX_VALUE)).thenReturn(Set.of("1"));
        // tryLock 不打桩=返回 null，即"这单有人在处理"
        RedisLockUtil lockUtil = mock(RedisLockUtil.class);

        cryptoService(redis, lockUtil).onPriceUpdate("BTCUSDT", new BigDecimal("50000"));

        verify(lockUtil).tryLock("crypto:order:execute:1", 30);
        verify(zset, never()).remove(anyString(), any());
        // 自检：同一套matcher在真摘索引时必须验得到，免得上面那条 never() 因matcher不匹配白过
        zset.remove("crypto:limit:buy:BTCUSDT", "1");
        verify(zset).remove(anyString(), any());
    }

    /** 合约：扫描只查不摘（原先是Lua原子取走），摘索引归触发方在CAS落定之后做 */
    @Test
    void 合约扫描不许原子取走索引() {
        CacheService cacheService = mock(CacheService.class);

        futuresService(cacheService).onPriceUpdate("BTCUSDT", new BigDecimal("50000"));

        verify(cacheService).zRangeByScoreWithScores("futures:limit:open_long:BTCUSDT", 50000d, Double.MAX_VALUE);
        verify(cacheService).zRangeByScoreWithScores("futures:limit:close_long:BTCUSDT", 0d, 50000d);
        verify(cacheService, never()).zRangeByScoreAndRemove(anyString(), anyDouble(), anyDouble());
    }

    private CryptoOrderServiceImpl cryptoService(StringRedisTemplate redis, RedisLockUtil lockUtil) {
        return new CryptoOrderServiceImpl(mock(UserService.class), mock(CryptoPositionService.class),
                mock(TradingConfig.class), lockUtil, mock(MarginAccountService.class), mock(BuffService.class),
                mock(CrossMarginService.class), redis, mock(CacheService.class), mock(BStockService.class),
                mock(TradeFilterRegistry.class));
    }

    private FuturesSettlementServiceImpl futuresService(CacheService cacheService) {
        return new FuturesSettlementServiceImpl(mock(UserService.class), mock(UserMapper.class),
                mock(FuturesPositionMapper.class), mock(FuturesOrderMapper.class), mock(TradingConfig.class),
                mock(FuturesLeverageBracketRegistry.class), cacheService, mock(FuturesPositionIndexService.class),
                mock(FuturesRiskService.class), mock(CrossMarginService.class), mock(CrossLiquidationService.class),
                mock(RedisLockUtil.class), mock(BinanceRestClient.class));
    }
}

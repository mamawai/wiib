package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.PredictionBet;
import com.mawai.wiibcommon.entity.PredictionRound;
import com.mawai.wiibcommon.market.PolymarketPriceClient;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import com.mawai.wiibsim.mapper.PredictionRoundMapper;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 结算兜底回归。卡死的根因是"取不到价就 return，回合永久 LOCKED、本金冻着"，
 * 这里锁死修完后的三条出路：缓存有价照常派彩 / 缓存没价回源 REST 并补开盘价 / 实在没价作废退本金。
 */
class PredictionSettleTest {

    private static final long WS = 1_700_000_000L;

    private PredictionRoundMapper roundMapper;
    private PredictionBetMapper betMapper;
    private UserService userService;
    private CacheService cacheService;
    private RedisLockUtil redisLockUtil;
    private PolymarketPriceClient priceClient;
    private PredictionServiceImpl service;

    @BeforeEach
    void setUp() {
        roundMapper = mock(PredictionRoundMapper.class);
        betMapper = mock(PredictionBetMapper.class);
        userService = mock(UserService.class);
        cacheService = mock(CacheService.class);
        redisLockUtil = mock(RedisLockUtil.class);
        priceClient = mock(PolymarketPriceClient.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

        when(redisLockUtil.tryLock(anyString(), anyLong())).thenReturn("lock-val");
        // 事务模板直接跑回调，断的是回调里的业务顺序
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        service = new PredictionServiceImpl(roundMapper, betMapper, userService, cacheService,
                redisLockUtil, mock(MarketBroadcaster.class), transactionTemplate, priceClient);
    }

    private static PredictionRound lockedRound(BigDecimal startPrice, long windowStart) {
        PredictionRound round = new PredictionRound();
        round.setId(1L);
        round.setWindowStart(windowStart);
        round.setStartPrice(startPrice);
        round.setStatus("LOCKED");
        return round;
    }

    private static PredictionBet bet(String status, BigDecimal contracts, BigDecimal cost) {
        PredictionBet b = new PredictionBet();
        b.setId(9L);
        b.setUserId(7L);
        b.setRoundId(1L);
        b.setSide("UP");
        b.setStatus(status);
        b.setContracts(contracts);
        b.setCost(cost);
        return b;
    }

    /** 当前窗口起点往前推 n 个 5 分钟窗口，避免用死时间戳算"多久以前" */
    private static long windowAgo(int windows) {
        long now = Instant.now().getEpochSecond();
        return now - (now % 300) - windows * 300L;
    }

    @Test
    void 缓存有收盘价时正常派彩() {
        when(roundMapper.selectOne(any())).thenReturn(lockedRound(new BigDecimal("100"), WS));
        when(cacheService.getPolymarketClosePrice(WS)).thenReturn(new BigDecimal("110"));
        when(roundMapper.casSettleRound(eq(1L), any(), eq("UP"))).thenReturn(1);
        when(betMapper.selectList(any())).thenReturn(List.of(bet("WON", new BigDecimal("5"), new BigDecimal("2"))));

        service.settleRound(WS);

        verify(betMapper).settleWon(1L, "UP");
        verify(betMapper).settleLost(1L, "DOWN");
        verify(userService).updateGameBalance(7L, new BigDecimal("5"));
        // 缓存命中就别再打 Polymarket
        verifyNoInteractions(priceClient);
    }

    @Test
    void 缓存缺价时回源REST并补上开盘价() {
        // 开盘价一直没回填过（建行时就是 null），旧代码在这里 compareTo(null) 直接 NPE
        when(roundMapper.selectOne(any())).thenReturn(lockedRound(null, WS));
        when(cacheService.getPolymarketClosePrice(WS)).thenReturn(null);
        when(cacheService.getPolymarketOpenPrice(WS)).thenReturn(null);
        when(priceClient.fetch(WS)).thenReturn(new PolymarketPriceClient.CryptoPrice(
                new BigDecimal("100"), new BigDecimal("90"), true));
        when(roundMapper.casSettleRound(eq(1L), any(), eq("DOWN"))).thenReturn(1);
        when(betMapper.selectList(any())).thenReturn(List.of());

        service.settleRound(WS);

        verify(roundMapper).fillStartPrice(1L, new BigDecimal("100"));
        verify(roundMapper).casSettleRound(1L, new BigDecimal("90"), "DOWN");
        verify(cacheService).putPolymarketOpenPrice(WS, new BigDecimal("100"));
        verify(cacheService).putPolymarketClosePrice(WS, new BigDecimal("90"));
    }

    @Test
    void 超时仍取不到价则作废退本金() {
        long staleWs = windowAgo(20);   // 100 分钟前，早过作废阈值
        when(roundMapper.selectOne(any())).thenReturn(lockedRound(new BigDecimal("100"), staleWs));
        when(cacheService.getPolymarketClosePrice(staleWs)).thenReturn(null);
        when(priceClient.fetch(staleWs)).thenReturn(null);
        when(betMapper.selectCount(any())).thenReturn(1L);
        when(roundMapper.casVoidRound(1L)).thenReturn(1);
        when(betMapper.selectList(any())).thenReturn(List.of(bet("DRAW", new BigDecimal("5"), new BigDecimal("2"))));

        service.settleRound(staleWs);

        verify(betMapper).settleDraw(1L);
        verify(userService).updateGameBalance(7L, new BigDecimal("2"));   // 退的是 cost
        verify(roundMapper, never()).casSettleRound(anyLong(), any(), anyString());
    }

    @Test
    void 缺价但还没超时就留给下次巡检() {
        long recentWs = windowAgo(2);   // 10 分钟前，Polymarket 可能只是晚出数据
        when(roundMapper.selectOne(any())).thenReturn(lockedRound(new BigDecimal("100"), recentWs));
        when(cacheService.getPolymarketClosePrice(recentWs)).thenReturn(null);
        when(priceClient.fetch(recentWs)).thenReturn(null);
        when(betMapper.selectCount(any())).thenReturn(1L);

        service.settleRound(recentWs);

        verify(roundMapper, never()).casVoidRound(anyLong());
        verifyNoInteractions(userService);
    }
}

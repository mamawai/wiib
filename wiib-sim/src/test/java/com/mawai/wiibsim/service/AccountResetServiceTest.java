package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.CryptoOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.campaign.service.CampaignCarryoverService;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 账户重置回归。锁死两件事，每件都是踩过或差点踩的坑：
 * <ol>
 *   <li>必须先注销 Redis 索引再删表。反过来会留幽灵索引，而强平服务命中后处理失败会把索引
 *       原样加回去（FuturesLiquidationServiceImpl 的 catch 里 zAdd 恢复），形成永久重试循环</li>
 *   <li>删表失败必须把索引重新注册回去。否则仓位还在、触发保护没了，等于静默关掉强平</li>
 * </ol>
 * （曾经还有第三件"待结算队列按 userId 前缀匹配"——现货卖出取消 5min 延迟后
 * 那条队列连同整套延迟结算一并删除，用例随之移除。）
 */
class AccountResetServiceTest {

    private FuturesPositionMapper positionMapper;
    private CryptoOrderMapper cryptoOrderMapper;
    private FuturesPositionIndexService indexService;
    private AccountPurgeTx purgeTx;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zSetOps;
    private ResetQuotaService resetQuota;
    private CampaignCarryoverService carryoverService;
    private AccountResetService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        positionMapper = mock(FuturesPositionMapper.class);
        cryptoOrderMapper = mock(CryptoOrderMapper.class);
        indexService = mock(FuturesPositionIndexService.class);
        purgeTx = mock(AccountPurgeTx.class);
        resetQuota = mock(ResetQuotaService.class);
        carryoverService = mock(CampaignCarryoverService.class);

        redis = mock(StringRedisTemplate.class);
        zSetOps = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zSetOps);

        when(positionMapper.selectList(any())).thenReturn(List.of());
        when(cryptoOrderMapper.selectList(any())).thenReturn(List.of());

        service = new AccountResetService(positionMapper, cryptoOrderMapper, indexService,
                purgeTx, redis, resetQuota, carryoverService);
    }

    private static FuturesPosition openPosition() {
        FuturesPosition p = new FuturesPosition();
        p.setId(1L);
        p.setUserId(7L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        return p;
    }

    @Test
    void unregistersIndexBeforePurgingTables() {
        FuturesPosition p = openPosition();
        when(positionMapper.selectList(any())).thenReturn(List.of(p));

        service.reset(7L);

        InOrder order = inOrder(indexService, purgeTx);
        order.verify(indexService).unregisterAll(p);
        order.verify(purgeTx).purge(7L, false);
    }

    @Test
    void reRegistersIndexWhenPurgeFails() {
        FuturesPosition p = openPosition();
        when(positionMapper.selectList(any())).thenReturn(List.of(p));
        doThrow(new RuntimeException("db down")).when(purgeTx).purge(7L, false);

        assertThrows(RuntimeException.class, () -> service.reset(7L));

        // 补偿：删表失败必须把触发保护装回去，否则仓位裸奔
        verify(indexService).registerPositionIndex(p);
    }

    @Test
    void clearsInFlightGameSessions() {
        // 三个游戏"进行中的那一局"只存 Redis。库表行被 purge 删了、游戏钱包也归零了，
        // 这一局要是留着，用户回去接着提现就能从一局本该抹掉的游戏里拿到派彩
        service.reset(7L);

        verify(redis).delete("bj:session:7");
        verify(redis).delete("mines:session:7");
        verify(redis).delete("vp:session:7");
    }

    @Test
    void clearsTodayBuffStatusCache() {
        // user_buff 行删了但缓存(TTL 1h)还写着"今天已抽"，不清的话重置完最长一小时抽不了新 buff
        service.reset(7L);

        verify(redis).delete("buff:status:7:" + LocalDate.now());
    }

    /** 平时（无活动）每周限 1 次：第二次直接拒，额度退回，业务一步不走 */
    @Test
    void 平时每周第二次重置被拒且退回额度() {
        when(resetQuota.recordUse(7L)).thenReturn(1L, 2L);
        when(carryoverService.campaignRunning()).thenReturn(false);

        service.resetWithGuard(7L, "alice", "alice");
        assertThrows(BizException.class, () -> service.resetWithGuard(7L, "alice", "alice"));

        verify(purgeTx, times(1)).purge(7L, false);
        verify(resetQuota, times(1)).refund(7L);
    }

    /** 活动期：每周首次免费（purge 不带扣分标记），之后每次放行但标记付费（−30 在事务里记） */
    @Test
    void 活动期首次免费之后放行并标记付费() {
        when(resetQuota.recordUse(7L)).thenReturn(1L, 2L, 3L);
        when(carryoverService.campaignRunning()).thenReturn(true);

        service.resetWithGuard(7L, "alice", "alice");
        service.resetWithGuard(7L, "alice", "alice");
        service.resetWithGuard(7L, "alice", "alice");

        verify(purgeTx, times(1)).purge(7L, false);
        verify(purgeTx, times(2)).purge(7L, true);
        verify(resetQuota, never()).refund(7L);
    }

    /** 重置失败必须把本周额度退回去，否则一次故障吃掉一次额度 */
    @Test
    void 重置失败退回本周额度() {
        when(resetQuota.recordUse(7L)).thenReturn(1L);
        doThrow(new RuntimeException("db down")).when(purgeTx).purge(7L, false);

        assertThrows(RuntimeException.class, () -> service.resetWithGuard(7L, "alice", "alice"));

        verify(resetQuota).refund(7L);
    }

    @Test
    void removesPendingLimitOrderIndex() {
        CryptoOrder order = new CryptoOrder();
        order.setId(500L);
        order.setUserId(7L);
        order.setSymbol("BTCUSDT");
        order.setOrderSide("BUY");
        when(cryptoOrderMapper.selectList(any())).thenReturn(List.of(order));

        service.reset(7L);

        verify(zSetOps).remove("crypto:limit:buy:BTCUSDT", "500");
    }
}

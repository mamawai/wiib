package com.mawai.wiibsim.util;

import com.mawai.wiibcommon.cache.CacheService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 锁死 {@link GameLockExecutor#executeInLockTx} 的四段顺序：<b>加锁 → 开事务 → 业务 → 提交/回滚 → 放锁</b>。
 * <p>
 * 锁必须活过事务：放锁跑到提交前面，后一个请求就能在前一笔未提交时抢到锁、读到过期余额做前置校验，
 * 串行化被击穿。失败路径单独守——顺序写反平时看不出来，只在回滚时才暴露。
 * <p>
 * 用真的 {@link RedisLockUtil} + mock 掉 Redis 与事务模板，断的是生产代码的嵌套关系，不是自己 stub 的顺序。
 */
class GameLockExecutorTest {

    /** 按发生顺序记事件，比 InOrder 更直观地把"锁活过事务"摊开 */
    private final List<String> events = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private GameLockExecutor executor() {
        // 抢锁 = setIfAbsent；放锁 = 跑 Lua 脚本。注意 unlock 里 `result == 1` 会对 Long 拆箱，
        // 这里必须返 1L，返 null 生产代码直接 NPE
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> {
                    events.add("LOCK");
                    return Boolean.TRUE;
                });
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(inv -> {
                    events.add("UNLOCK");
                    return 1L;
                });

        // 真事务提交/回滚看不见，就用 mock 在回调两侧打点代替
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            events.add("TX_BEGIN");
            TransactionCallback<?> callback = inv.getArgument(0);
            try {
                Object result = callback.doInTransaction(mock(TransactionStatus.class));
                events.add("TX_COMMIT");
                return result;
            } catch (RuntimeException e) {
                events.add("TX_ROLLBACK");
                throw e;
            }
        });

        return new GameLockExecutor(new RedisLockUtil(redisTemplate), transactionTemplate, mock(CacheService.class));
    }

    @Test
    void 加锁在开事务之前_放锁在提交之后() {
        String result = executor().executeInLockTx("mines:user:", 7L, () -> {
            events.add("BIZ");
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(events).containsExactly("LOCK", "TX_BEGIN", "BIZ", "TX_COMMIT", "UNLOCK");

        InOrder inOrder = inOrder(valueOps, transactionTemplate, redisTemplate);
        inOrder.verify(valueOps).setIfAbsent(eq("lock:mines:user:7"), anyString(), eq(Duration.ofSeconds(20)));
        inOrder.verify(transactionTemplate).execute(any());
        inOrder.verify(redisTemplate).execute(any(RedisScript.class), anyList(), any());
    }

    /**
     * 失败路径才是这条不变量真正兑现的时候——顺序写反平时看不出来，只在出错回滚时才暴露。
     * 回滚必须发生在放锁之前，否则后一个请求会抢到锁、看见一份即将被撤销的余额。
     */
    @Test
    void 业务抛异常时_先回滚再放锁() {
        GameLockExecutor executor = executor();

        assertThatThrownBy(() -> executor.executeInLockTx("mines:user:", 7L, () -> {
            events.add("BIZ");
            throw new IllegalStateException("扣钱之后建局失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(events).containsExactly("LOCK", "TX_BEGIN", "BIZ", "TX_ROLLBACK", "UNLOCK");
    }
}

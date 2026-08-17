package com.mawai.wiibsim.util;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 游戏通用：分布式锁 + 编程式事务
 * <p>
 * 执行顺序：加锁 → 开事务 → 业务 → 提交事务 → 释放锁（顺序由 GameLockExecutorTest 守）
 * <p>
 * <b>调用方别再叠 @Transactional</b>：注解事务的 begin 在抢锁之前，顺序会翻成"先放锁后提交"，
 * 串行化击穿。那个测试测的是本类内部，拦不住调用方叠注解。
 */
@Component
@RequiredArgsConstructor
public class GameLockExecutor {

    private final RedisLockUtil redisLockUtil;
    private final TransactionTemplate transactionTemplate;

    private static final long LOCK_TIMEOUT_SECONDS = 20;
    private static final long LOCK_WAIT_MILLIS = 3_000;

    /**
     * 在锁 + 事务保护下执行（有返回值，需要写库的场景）
     */
    public <T> T executeInLockTx(String lockKeyPrefix, Long userId, Supplier<T> supplier) {
        String lockKey = lockKeyPrefix + userId;
        try {
            return redisLockUtil.executeWithLock(lockKey, LOCK_TIMEOUT_SECONDS, LOCK_WAIT_MILLIS, () ->
                    transactionTemplate.execute(status -> supplier.get())
            );
        } catch (LockAcquisitionException ex) {
            throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        }
    }

    /**
     * 在锁保护下执行（有返回值，只读不需要事务）
     */
    public <T> T executeInLock(String lockKeyPrefix, Long userId, Supplier<T> supplier) {
        String lockKey = lockKeyPrefix + userId;
        try {
            return redisLockUtil.executeWithLock(lockKey, LOCK_TIMEOUT_SECONDS, LOCK_WAIT_MILLIS, supplier);
        } catch (LockAcquisitionException ex) {
            throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        }
    }
}

package com.mawai.wiibsim.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;

/**
 * 账户重置的每周次数计数（自然周，周一 0 点回满）。
 * <p>
 * 手动重置（AccountResetService）与破产自动恢复（BankruptcyServiceImpl.resetUser）<b>共用同一份计数</b>：
 * 只记手动的话，穿仓破产就成了不占额度的免费重置通道。
 * 次数怎么用由调用方决定 —— 手动侧超额要么拒（平时）要么扣活动分（活动期），
 * 破产恢复永不被拦、只计数和扣分。
 */
@Service
@RequiredArgsConstructor
public class ResetQuotaService {

    private static final String PREFIX = "user:reset:";

    private final StringRedisTemplate redis;

    /**
     * 记一次使用，返回本周累计次数（含本次）。
     * <p>
     * INCR 先占号后判断：并发的两个请求各拿 1、2，不存在查改竞态。
     * key 带 ISO 周号，跨周自然换新键计数归零；TTL 设到下周一 0 点只是兜底清理。
     */
    public long recordUse(long userId) {
        LocalDate today = LocalDate.now();
        Long used = redis.opsForValue().increment(weekKey(userId, today));
        redis.expireAt(weekKey(userId, today), today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneId.systemDefault()).toInstant());
        // increment 只在 pipeline/transaction 里才可能返回 null，这里不会；兜底当首次
        return used == null ? 1 : used;
    }

    /** 重置失败或被拒时退回本次占用，不吃掉用户额度 */
    public void refund(long userId) {
        redis.opsForValue().decrement(weekKey(userId, LocalDate.now()));
    }

    private static String weekKey(long userId, LocalDate d) {
        return PREFIX + userId + ":" + d.get(IsoFields.WEEK_BASED_YEAR)
                + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }
}

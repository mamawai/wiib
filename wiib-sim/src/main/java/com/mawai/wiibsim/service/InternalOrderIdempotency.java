package com.mawai.wiibsim.service;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.util.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * internal 下单幂等：quant 的 AI Trader 读超时后会用同一 clientRequestId 重发确认结果，
 * 这里保证那笔单只成交一次——sim 开仓要抢锁+走事务，慢过 5s 很常见，超时重发若不挡就是双仓。
 *
 * <p>抢到占位＝本次真执行；没抢到＝上一次要么已出结果（回放那份响应），要么还在跑
 * （回"处理中"让对方拿同一个键再来）。执行失败会删键，允许对方按新请求重试。
 * clientRequestId 为空（用户端 Web 下单）直接执行，行为不变。</p>
 */
@Component
@RequiredArgsConstructor
public class InternalOrderIdempotency {

    private final StringRedisTemplate redis;

    /** 覆盖 quant 侧重发窗口（5s 读超时 + 2 次间隔 2s 的重发）绰绰有余，也不长期占内存 */
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String PENDING = "PENDING";

    public Result<FuturesOrderResponse> execute(Long userId, String clientRequestId,
                                                Supplier<FuturesOrderResponse> action) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            return Result.ok(action.get());
        }
        String key = "idem:futures:" + userId + ":" + clientRequestId;
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, PENDING, TTL))) {
            String cached = redis.opsForValue().get(key);
            // 键刚被失败分支删掉时 cached 为 null：也当"处理中"回，对方同键再来一次就会走执行分支
            if (cached == null || PENDING.equals(cached)) {
                return Result.fail(ErrorCode.ORDER_PROCESSING.getCode(),
                        "请求处理中，请稍后用同一 clientRequestId 重试");
            }
            return Result.ok(JSON.parseObject(cached, FuturesOrderResponse.class));
        }
        try {
            FuturesOrderResponse resp = action.get();
            redis.opsForValue().set(key, JSON.toJSONString(resp), TTL);
            return Result.ok(resp);
        } catch (RuntimeException e) {
            // 没成交就不占键：业务失败（余额不足等）与系统异常都放行重试，失败原样往上抛
            redis.delete(key);
            throw e;
        }
    }
}

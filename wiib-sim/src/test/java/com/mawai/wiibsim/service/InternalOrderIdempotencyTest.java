package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.util.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * internal 下单幂等：quant 读超时后会拿同一 clientRequestId 重发确认，
 * 这里锁死"只成交一次"——抢到占位才真下单，没抢到只回放结果或让对方再等。
 */
class InternalOrderIdempotencyTest {

    private static final String KEY = "idem:futures:99:req-1";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private InternalOrderIdempotency idempotency;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        idempotency = new InternalOrderIdempotency(redis);
    }

    private FuturesOrderResponse order() {
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(777L);
        resp.setStatus("FILLED");
        return resp;
    }

    /** 抢到占位＝这次真下单，成交结果回写进键，后续重发才有得回放 */
    @Test
    void 抢到占位则执行下单并缓存结果() {
        when(ops.setIfAbsent(eq(KEY), eq("PENDING"), any(Duration.class))).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();

        Result<FuturesOrderResponse> r = idempotency.execute(99L, "req-1", () -> {
            calls.incrementAndGet();
            return order();
        });

        assertThat(calls.get()).isEqualTo(1);
        assertThat(r.getCode()).isEqualTo(ErrorCode.SUCCESS.getCode());
        assertThat(r.getData().getOrderId()).isEqualTo(777L);
        verify(ops).set(eq(KEY), contains("\"orderId\":777"), any(Duration.class));
    }

    /** 没抢到且键里已是结果：直接回放，绝不再下一次单 */
    @Test
    void 没抢到且已有结果则回放不重复下单() {
        when(ops.setIfAbsent(eq(KEY), eq("PENDING"), any(Duration.class))).thenReturn(false);
        when(ops.get(KEY)).thenReturn("{\"orderId\":777,\"status\":\"FILLED\"}");

        Result<FuturesOrderResponse> r = idempotency.execute(99L, "req-1",
                () -> { throw new IllegalStateException("不该再下单"); });

        assertThat(r.getCode()).isEqualTo(ErrorCode.SUCCESS.getCode());
        assertThat(r.getData().getOrderId()).isEqualTo(777L);
        assertThat(r.getData().getStatus()).isEqualTo("FILLED");
    }

    /** 没抢到且上一次还在跑：回"处理中"，让对方拿同一个键再来问 */
    @Test
    void 没抢到且上一次还在跑则回处理中() {
        when(ops.setIfAbsent(eq(KEY), eq("PENDING"), any(Duration.class))).thenReturn(false);
        when(ops.get(KEY)).thenReturn("PENDING");

        Result<FuturesOrderResponse> r = idempotency.execute(99L, "req-1",
                () -> { throw new IllegalStateException("不该再下单"); });

        // 1106 不是 1105：抢锁失败那个 1105 语义是"确定没成交"，两边混了 quant 会把没下的单报成"可能已成交"
        assertThat(r.getCode()).isEqualTo(ErrorCode.ORDER_IN_FLIGHT.getCode());
        assertThat(r.getMsg()).contains("clientRequestId");
        assertThat(r.getData()).isNull();
    }

    /** 下单失败没成交，键必须删掉——不然同一个键十分钟内再也下不了单 */
    @Test
    void 下单失败删键并原样抛出() {
        when(ops.setIfAbsent(eq(KEY), eq("PENDING"), any(Duration.class))).thenReturn(true);

        assertThatThrownBy(() -> idempotency.execute(99L, "req-1",
                () -> { throw new IllegalStateException("余额不足"); }))
                .hasMessage("余额不足");

        verify(redis).delete(KEY);
    }

    /** 用户端 Web 下单不传键：一行 Redis 都不碰，行为跟以前一样 */
    @Test
    void 无幂等键直接执行不碰Redis() {
        Result<FuturesOrderResponse> r = idempotency.execute(99L, null, this::order);

        assertThat(r.getData().getOrderId()).isEqualTo(777L);
        verifyNoInteractions(ops);
    }
}

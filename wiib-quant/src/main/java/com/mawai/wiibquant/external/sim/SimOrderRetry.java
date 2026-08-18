package com.mawai.wiibquant.external.sim;

import java.util.function.Supplier;

/**
 * 打 sim 内部下单接口的统一发送口：读超时和 sim 回的"处理中"都只说明结果未知
 * （sim 很可能已经成交），这时拿<b>同一个 clientRequestId</b> 重发就是去问结果——
 * sim 侧 {@code InternalOrderIdempotency} 保证同键只成交一次，重发不会多下一笔。
 * <p>
 * 明确的业务失败（余额不足、步长不合规等）不重发，原样抛出去给调用方。
 * 三次都问不到结果就抛 {@link UnknownOutcome}：调用方必须把"未知"如实往上报，
 * 当成失败报出去的话，模型或主人会照着重下一笔，那就是双仓。
 */
public final class SimOrderRetry {

    private static final int RETRIES = 2;
    private static final long RETRY_INTERVAL_MS = 2000;

    private SimOrderRetry() {
    }

    /** 重发确认后仍问不到结果：这笔单可能已经在 sim 成交了。 */
    public static class UnknownOutcome extends RuntimeException {
        public UnknownOutcome(Throwable cause) {
            super(cause);
        }
    }

    public static <T> T send(Supplier<T> call) {
        RuntimeException last = null;
        for (int i = 0; i <= RETRIES; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    // 唤醒超时会 cancel(true) 打断这里：结果照样未知，别把中断吞成成功
                    Thread.currentThread().interrupt();
                    throw new UnknownOutcome(last);
                }
            }
            try {
                return call.get();
            } catch (RuntimeException e) {
                if (!SimTradeClient.isTransportFailure(e) && !SimTradeClient.isProcessing(e)) {
                    throw e;
                }
                last = e;
            }
        }
        throw new UnknownOutcome(last);
    }
}

package com.mawai.wiibquant.agent.llm;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 通道：emitter + 关闭标志 + 写锁收在一起。
 * 锁是必须的——SseEmitter.send 非线程安全，心跳线程与主流线程并发写会让帧交错损坏。
 * 锁在实例上而非 Controller 上，各会话互不阻塞。
 * <p>
 * 研判工作台对话与复盘 AI 提示两个 SSE 端点共用（事件协议同为 token/done/error）。
 */
public final class SseChannel {
    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    public SseChannel(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void markClosed() {
        closed.set(true);
    }

    public void send(String event, JSONObject data) {
        write(SseEmitter.event().name(event).data(data.toJSONString()));
    }

    /** 心跳：SSE 注释帧，前端 dispatch 取不到 data 直接忽略，纯粹喂饱中间层的空闲计时器。 */
    public void heartbeat() {
        write(SseEmitter.event().comment("hb"));
    }

    private void write(SseEmitter.SseEventBuilder builder) {
        if (closed.get()) {
            return;
        }
        synchronized (writeLock) {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(builder);
            } catch (Exception e) {
                closed.set(true);
            }
        }
    }

    /** complete 与写共用锁：避免心跳正在写时通道被关，Tomcat 抛 IllegalStateException */
    public void complete() {
        synchronized (writeLock) {
            closed.set(true);
            emitter.complete();
        }
    }
}

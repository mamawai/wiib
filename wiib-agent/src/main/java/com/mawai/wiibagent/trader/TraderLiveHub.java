package com.mawai.wiibagent.trader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibcommon.entity.AiTrader;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * trader 唤醒现场的发布订阅：订阅者管理、扇出、心跳、主人门控、列表快照。
 * 详情流按 traderId 订阅，列表流一份；一次唤醒一个 {@link Run} 句柄，runner 只碰它。
 * 单实例部署，进程内 Map 即可。不认识 langgraph。
 */
@Component
public class TraderLiveHub {

    /** 帧出口：返回 false=通道已死，hub 摘掉它 */
    public interface Sink {
        boolean send(String event, JSONObject data);
    }

    /** nginx 默认 proxy_read_timeout 60s 会掐静默连接，20s 一帧留 3 倍余量 */
    private static final long HEARTBEAT_SECONDS = 20;
    /** 订阅 30 分钟到点，前端自动重连 */
    private static final long SUBSCRIBE_TIMEOUT_MS = 30 * 60_000L;

    /** 详情订阅者：出口 + 是不是主人（prompt 帧只给主人） */
    private record Subscriber(Sink sink, boolean owner) {
    }

    /** traderId → 在跑的一轮，end 后摘掉 */
    private final Map<Long, Run> runs = new ConcurrentHashMap<>();
    /** traderId → 详情订阅者；空闲时也挂着，等下一轮 run_start */
    private final Map<Long, Set<Subscriber>> traderSubscribers = new ConcurrentHashMap<>();
    private final Set<Sink> arenaSinks = ConcurrentHashMap.newKeySet();
    /** 所有 SSE 通道，心跳用 */
    private final Set<SseChannel> channels = ConcurrentHashMap.newKeySet();
    /** 心跳只发注释帧，单线程够用；虚拟线程不支持定时调度故用平台线程 */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "trader-live-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public TraderLiveHub() {
        heartbeatScheduler.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    private void heartbeat() {
        channels.removeIf(SseChannel::isClosed);
        channels.forEach(SseChannel::heartbeat);
    }

    // ========== 运行侧 ==========

    /** 一轮唤醒开始：建轨迹、登记在跑、扇出 run_start 与列表 status */
    public Run begin(AiTrader trader, String kind, long wakeTime, long budgetSeconds,
                     BigDecimal equity, int positions, int pendingOrders) {
        Run run = new Run(new WakeTrace(trader.getId(), UUID.randomUUID().toString(), kind, wakeTime,
                budgetSeconds, equity, positions, pendingOrders));
        runs.put(trader.getId(), run);
        run.started();
        return run;
    }

    /**
     * 一次唤醒的现场句柄。每个方法：轨迹变更拿帧 → 补 traderId/runId/seq → 发该 trader 的详情订阅者
     * （prompt 只给主人）→ 需要时发列表 status。方法全同步：帧序与订阅者中途接入的回放互斥。
     */
    public final class Run {
        private final WakeTrace trace;
        private int seq;
        /** finish 收好的 run_end 帧，end 补上 decisionId 才发 */
        private WakeTrace.Frame endFrame;

        private Run(WakeTrace trace) {
            this.trace = trace;
        }

        public WakeTrace trace() {
            return trace;
        }

        private synchronized void started() {
            fanOut(trace.runStart());
            status();
        }

        public synchronized void prompt(String system, String instruction) {
            emit(() -> trace.prompt(system, instruction), false);
        }

        public synchronized void callStart() {
            emit(trace::callStart, true);
        }

        public synchronized void token(String text) {
            emit(() -> trace.token(text), false);
        }

        public synchronized void callEnd(String text, List<AssistantMessage.ToolCall> toolCalls) {
            emit(() -> trace.callEnd(text, toolCalls), true);
        }

        public synchronized void toolResult(String id, String name, String responseData) {
            emit(() -> trace.toolResult(id, name, responseData), true);
        }

        /** 过程帧的统一出口：已 finish 的一轮不再收帧（超时后图线程还会晚推几帧） */
        private void emit(Supplier<WakeTrace.Frame> change, boolean withStatus) {
            if (endFrame != null) {
                return;
            }
            fanOut(change.get());
            if (withStatus) {
                status();
            }
        }

        /** 收尾写进轨迹，返回落库 JSON；run_end 帧先攒着，决策行落库拿到 id 再发 */
        public synchronized String finish(String status, String error, BigDecimal equity, Integer latencyMs,
                                          Integer modelCalls, Long totalTokens) {
            endFrame = trace.end(status, error, equity, latencyMs, modelCalls, totalTokens);
            return trace.toJson();
        }

        /** 决策行已落库：从在跑表摘掉、发 run_end（带 decisionId）、列表 running=false */
        public synchronized void end(Long decisionId) {
            runs.remove(trace.traderId, this);
            endFrame.data().put("decisionId", decisionId);
            fanOut(endFrame);
            status();
        }

        /** 中途接入：登记与回放在同一把锁里，实时帧插不进两者之间 */
        private synchronized void attach(Subscriber sub) {
            subscribers(trace.traderId).add(sub);
            for (WakeTrace.Frame frame : trace.replay(sub.owner())) {
                if (!deliver(sub, stamp(frame))) {
                    return;
                }
            }
        }

        private void fanOut(WakeTrace.Frame frame) {
            stamp(frame);
            // 主人门控：提示词只到主人
            boolean ownerOnly = "prompt".equals(frame.event());
            Set<Subscriber> subs = subscribers(trace.traderId);
            for (Subscriber sub : subs) {
                if (ownerOnly && !sub.owner()) {
                    continue;
                }
                if (!deliver(sub, frame)) {
                    subs.remove(sub);
                }
            }
        }

        private WakeTrace.Frame stamp(WakeTrace.Frame frame) {
            frame.data().fluentPut("traderId", trace.traderId).fluentPut("runId", trace.runId).fluentPut("seq", ++seq);
            return frame;
        }

        private boolean deliver(Subscriber sub, WakeTrace.Frame frame) {
            return sub.sink().send(frame.event(), frame.data());
        }

        /** 列表 status 帧，发给所有列表订阅者 */
        private void status() {
            JSONObject status = trace.status();
            for (Sink sink : arenaSinks) {
                if (!sink.send("status", status)) {
                    arenaSinks.remove(sink);
                }
            }
        }
    }

    // ========== 订阅侧 ==========

    /** 详情流：登记后立刻回放在跑的一轮；空闲时什么都不发，只有心跳 */
    public SseEmitter subscribeTrader(long traderId, boolean owner) {
        SseEmitter emitter = new SseEmitter(SUBSCRIBE_TIMEOUT_MS);
        SseChannel channel = new SseChannel(emitter);
        Sink sink = sinkOf(channel);
        attach(emitter, channel, () -> subscribers(traderId).remove(new Subscriber(sink, owner)));
        subscribeTrader(traderId, owner, sink);
        return emitter;
    }

    /** 包私有：测试挂收集器用 */
    void subscribeTrader(long traderId, boolean owner, Sink sink) {
        Subscriber sub = new Subscriber(sink, owner);
        Run run = runs.get(traderId);
        // 空闲：挂上等下一轮 run_start；在跑：进 Run 锁里登记 + 回放
        if (run == null) {
            subscribers(traderId).add(sub);
        } else {
            run.attach(sub);
        }
    }

    /** 列表流：连上先发一次 snapshot，只含在跑的 */
    public SseEmitter subscribeArena() {
        SseEmitter emitter = new SseEmitter(SUBSCRIBE_TIMEOUT_MS);
        SseChannel channel = new SseChannel(emitter);
        Sink sink = sinkOf(channel);
        attach(emitter, channel, () -> arenaSinks.remove(sink));
        subscribeArena(sink);
        return emitter;
    }

    /** 包私有：测试挂收集器用 */
    void subscribeArena(Sink sink) {
        arenaSinks.add(sink);
        JSONArray traders = new JSONArray();
        runs.values().forEach(run -> traders.add(run.trace.status()));
        sink.send("snapshot", new JSONObject().fluentPut("traders", traders));
    }

    private Set<Subscriber> subscribers(long traderId) {
        return traderSubscribers.computeIfAbsent(traderId, k -> ConcurrentHashMap.newKeySet());
    }

    /** SseChannel 适配成 Sink：写失败 SseChannel 自己标关，下次就返回 false */
    private static Sink sinkOf(SseChannel channel) {
        return (event, data) -> {
            if (channel.isClosed()) {
                return false;
            }
            channel.send(event, data);
            return !channel.isClosed();
        };
    }

    /** emitter 生命周期：完成/超时/出错都标关、摘心跳、摘订阅 */
    private void attach(SseEmitter emitter, SseChannel channel, Runnable unsubscribe) {
        channels.add(channel);
        Runnable close = () -> {
            channel.markClosed();
            channels.remove(channel);
            unsubscribe.run();
        };
        emitter.onCompletion(close);
        emitter.onTimeout(() -> {
            close.run();
            emitter.complete();
        });
        emitter.onError(_ -> close.run());
    }
}

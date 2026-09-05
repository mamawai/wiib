package com.mawai.wiibagent.trader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.entity.AiTrader;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发布订阅这一层：列表 snapshot 只含在跑的、status 帧的 tool/symbol 取值与 run_end 后 running=false、
 * 中途接入的回放与主人门控、Sink 返回 false 就被摘掉。
 */
class TraderLiveHubTest {

    /** 收帧的出口 */
    private static final class Frames implements TraderLiveHub.Sink {
        final List<String> events = new ArrayList<>();
        final List<JSONObject> data = new ArrayList<>();

        @Override
        public boolean send(String event, JSONObject d) {
            events.add(event);
            data.add(d);
            return true;
        }

        JSONObject last(String event) {
            return data.get(events.lastIndexOf(event));
        }
    }

    private final TraderLiveHub hub = new TraderLiveHub();

    private static AiTrader trader(long id) {
        AiTrader t = new AiTrader();
        t.setId(id);
        t.setUserId(3L);
        return t;
    }

    private TraderLiveHub.Run begin(long traderId) {
        return hub.begin(trader(traderId), "TRADE", 1000L, 595, new BigDecimal("10000"), 0, 0);
    }

    private static AssistantMessage.ToolCall call(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }

    @Test
    void 列表快照只含在跑的_结束后running为false() {
        TraderLiveHub.Run run = begin(1L);
        Frames arena = new Frames();
        hub.subscribeArena(arena);

        JSONArray traders = arena.last("snapshot").getJSONArray("traders");
        assertThat(traders).hasSize(1);
        assertThat(traders.getJSONObject(0).getLongValue("traderId")).isEqualTo(1L);
        assertThat(traders.getJSONObject(0).getBooleanValue("running")).isTrue();

        run.finish("OK", null, new BigDecimal("10012"), 4200, 2, 1800L);
        run.end(99L);
        JSONObject ended = arena.last("status");
        assertThat(ended.getLongValue("traderId")).isEqualTo(1L);
        assertThat(ended.getBooleanValue("running")).isFalse();

        // 结束后再连：快照里没它
        Frames late = new Frames();
        hub.subscribeArena(late);
        assertThat(late.last("snapshot").getJSONArray("traders")).isEmpty();
    }

    @Test
    void status帧的tool与symbol取第一条toolCall_回执后清掉() {
        Frames arena = new Frames();
        hub.subscribeArena(arena);
        TraderLiveHub.Run run = begin(1L);
        assertThat(arena.events).containsExactly("snapshot", "status");
        assertThat(arena.last("status").getIntValue("call")).isEqualTo(0);

        run.callStart();
        assertThat(arena.last("status").getIntValue("call")).isEqualTo(1);
        assertThat(arena.last("status").get("tool")).isNull();

        run.callEnd("", List.of(call("c1", "klines", "{\"symbol\":\"BTCUSDT\",\"interval\":\"1h\"}"),
                call("c2", "indicators", "{}")));
        JSONObject calling = arena.last("status");
        assertThat(calling.getString("tool")).isEqualTo("klines");
        assertThat(calling.getString("symbol")).isEqualTo("BTCUSDT");
        assertThat(calling.getString("kind")).isEqualTo("TRADE");

        run.toolResult("c1", "klines", "data");
        JSONObject cleared = arena.last("status");
        assertThat(cleared.get("tool")).isNull();
        assertThat(cleared.get("symbol")).isNull();
        // 线上形态：tool/symbol 缺席
        assertThat(cleared.toJSONString()).doesNotContain("\"tool\"").doesNotContain("\"symbol\"");
        assertThat(cleared.getBooleanValue("running")).isTrue();
    }

    @Test
    void 中途接入拿到回放_提示词只给主人_之后在途帧继续到() {
        TraderLiveHub.Run run = begin(1L);
        run.prompt("SYS", "INS");
        run.callStart();
        run.callEnd("", List.of(call("c1", "klines", "{}")));
        run.toolResult("c1", "klines", "d");
        run.callStart();
        run.token("半截");

        Frames owner = new Frames();
        Frames viewer = new Frames();
        hub.subscribeTrader(1L, true, owner);
        hub.subscribeTrader(1L, false, viewer);

        assertThat(owner.events).containsExactly("run_start", "prompt", "model_end", "tool_result", "model_start", "token");
        assertThat(viewer.events).containsExactly("run_start", "model_end", "tool_result", "model_start", "token");
        assertThat(owner.last("prompt").getString("system")).isEqualTo("SYS");
        assertThat(owner.last("token").getString("text")).isEqualTo("半截");
        // 回放帧同样带 traderId/runId/seq
        assertThat(owner.data.getFirst().getLongValue("traderId")).isEqualTo(1L);
        assertThat(owner.data.getFirst().getString("runId")).isNotBlank();
        assertThat(owner.data.getFirst().getIntValue("seq")).isPositive();

        // 之后的在途帧两边都到；prompt 之外一视同仁
        run.token("后半");
        assertThat(owner.last("token").getString("text")).isEqualTo("后半");
        assertThat(viewer.last("token").getString("text")).isEqualTo("后半");
        run.finish("OK", null, BigDecimal.TEN, 1, 2, null);
        run.end(5L);
        assertThat(owner.last("run_end").getLongValue("decisionId")).isEqualTo(5L);
        assertThat(viewer.last("run_end").getString("status")).isEqualTo("OK");

        // 空闲 trader 连上什么都不发
        Frames idle = new Frames();
        hub.subscribeTrader(2L, true, idle);
        assertThat(idle.events).isEmpty();
        // 但下一轮开跑就收到 run_start
        begin(2L);
        assertThat(idle.events).containsExactly("run_start");
    }

    @Test
    void finish之后的晚到帧全部丢掉() {
        Frames owner = new Frames();
        hub.subscribeTrader(1L, true, owner);
        TraderLiveHub.Run run = begin(1L);
        run.callStart();
        run.finish("ERROR", "超时", BigDecimal.TEN, 1, 1, null);
        // 超时后图线程还在推
        run.token("晚到");
        run.callEnd("晚到", List.of());
        run.callStart();
        run.end(9L);

        assertThat(owner.events).containsExactly("run_start", "model_start", "run_end");
        assertThat(owner.last("run_end").getString("status")).isEqualTo("ERROR");
    }

    @Test
    void Sink返回false就被摘掉() {
        AtomicInteger detail = new AtomicInteger();
        hub.subscribeTrader(1L, true, (event, data) -> {
            detail.incrementAndGet();
            return false;
        });
        AtomicInteger arena = new AtomicInteger();
        hub.subscribeArena((event, data) -> {
            arena.incrementAndGet();
            return false;
        });
        assertThat(arena).hasValue(1); // snapshot

        TraderLiveHub.Run run = begin(1L); // 详情 run_start 与列表 status 各一帧，都返回 false
        run.callStart();
        run.callStart();

        assertThat(detail).hasValue(1);
        assertThat(arena).hasValue(2);
    }
}

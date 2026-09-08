package com.mawai.wiibquant.task;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibquant.mapper.EconCalendarMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 财经日历采集轨：定时拉 ForexFactory 本周日历 JSON（免费无 key）→ 删窗口重插 econ_calendar_event。
 * 唤醒开场白据此注入"过去 12h 已公布 + 未来 24h 即将公布"（见 EconCalendarAssembler）——
 * trader 撞上数据公布/讲话时刻被打穿止损，病根是它连"此刻有雷"这个事实都看不见。
 * <p>
 * 同步语义是<b>删窗口重插</b>而不是逐行 upsert：feed 是本周全量快照，事件会改期/取消，
 * 以 (时间,标题) 做键的 upsert 会给改期事件留下旧时刻的幽灵行；整窗覆盖天然自愈。
 * 只有本周文件（nextweek 已下线）：注入窗口 ±24h 只在周末边界被截断，而周末没有宏观事件。
 * <p>
 * 失败语义与快讯采集同款：跳过本轮沿用旧数据——日历事件提前数天可知，旧数据比空表有用。
 * feed 为个人用途口径，节奏别调快：日历几小时拉一次绰绰有余。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EconCalendarCollector {

    /** 解析后的一条事件；forecast/previous null=该事件无数值（讲话/会议类） */
    public record Event(long eventTime, String currency, String title, String impact,
                        String forecast, String previous) {
    }

    /** 列宽 VARCHAR(200)：外部数据超长会让整批事务回滚，截一刀保住其余行 */
    private static final int MAX_TITLE_LEN = 200;

    private final EconCalendarMapper mapper;

    @Value("${econ.calendar.enabled:true}")
    boolean enabled;
    @Value("${econ.calendar.url:https://nfs.faireconomy.media/ff_calendar_thisweek.json}")
    private String url;

    /** 拉取注入点：测试换假源 */
    Supplier<String> feed = this::httpGet;

    /** 周期见 econ.calendar.interval-ms（缺省 4h）。initialDelay 让开启动高峰 */
    @Scheduled(fixedDelayString = "${econ.calendar.interval-ms:14400000}", initialDelay = 30_000)
    @Transactional
    public void collect() {
        if (!enabled) {
            return;
        }
        List<Event> events;
        try {
            events = parse(feed.get());
        } catch (Exception e) {
            log.warn("[EconCalendar] 拉取/解析失败跳过本轮，沿用旧数据: {}", e.toString());
            return;
        }
        if (events.isEmpty()) {
            log.warn("[EconCalendar] feed 为空，跳过本轮");
            return;
        }
        long from = events.stream().mapToLong(Event::eventTime).min().orElseThrow();
        int deleted = mapper.deleteFrom(from);
        for (Event e : events) {
            mapper.insert(e.eventTime(), e.currency(), e.title(), e.impact(), e.forecast(), e.previous());
        }
        log.info("[EconCalendar] 同步 {} 条（覆盖旧 {} 条）", events.size(), deleted);
    }

    /** feed → 事件行：ISO 时间转 epoch；坏行跳过不拖垮整批（每种问题只记一条日志的量级：整批最多百余行） */
    static List<Event> parse(String json) {
        JSONArray arr = JSON.parseArray(json);
        List<Event> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            try {
                String title = o.getString("title");
                long time = OffsetDateTime.parse(o.getString("date")).toInstant().toEpochMilli();
                // 缺必填字段与坏日期同罪：这里不拦的话会活到 insert 撞 NOT NULL，整批事务回滚
                // feed 字段名叫 country 是上游的历史命名，值实为货币代码（德国CPI标EUR、G20标All）
                out.add(new Event(time, Objects.requireNonNull(o.getString("country")),
                        title.length() > MAX_TITLE_LEN ? title.substring(0, MAX_TITLE_LEN) : title,
                        Objects.requireNonNull(o.getString("impact")),
                        blankToNull(o.getString("forecast")), blankToNull(o.getString("previous"))));
            } catch (Exception e) {
                log.warn("[EconCalendar] 跳过坏行: {}", o.toJSONString());
            }
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private String httpGet() {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpResponse<String> resp = client.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (Exception e) {
            throw new IllegalStateException("日历 feed 拉取失败: " + e.getMessage(), e);
        }
    }
}

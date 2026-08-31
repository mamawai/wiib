package com.mawai.wiibquant.task;

import com.mawai.wiibquant.mapper.EconCalendarMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 财经日历采集：feed 是本周全量快照，同步语义是"删窗口重插"——改期/取消的事件靠整窗覆盖自愈。
 * 失败语义与快讯采集同款：跳过本轮沿用旧数据，日历天然耐过期。
 */
class EconCalendarCollectorTest {

    /** ForexFactory 真实回包形态（字段/时区/空值都照原样） */
    private static final String FEED = """
            [{"title":"Non-Farm Employment Change","country":"USD","date":"2026-09-04T08:30:00-04:00",
              "impact":"High","forecast":"55K","previous":"-23K"},
             {"title":"G20 Meetings","country":"All","date":"2026-08-30T11:15:00-04:00",
              "impact":"Low","forecast":"","previous":""},
             {"title":"BOE Gov Bailey Speaks","country":"GBP","date":"2026-09-04T04:50:00-04:00",
              "impact":"High","forecast":"","previous":""}]""";

    @Test
    void 解析真实feed_时间转epoch_空值转null() {
        List<EconCalendarCollector.Event> events = EconCalendarCollector.parse(FEED);

        assertThat(events).hasSize(3);
        EconCalendarCollector.Event nfp = events.getFirst();
        assertThat(nfp.title()).isEqualTo("Non-Farm Employment Change");
        assertThat(nfp.country()).isEqualTo("USD");
        assertThat(nfp.impact()).isEqualTo("High");
        assertThat(nfp.forecast()).isEqualTo("55K");
        assertThat(nfp.previous()).isEqualTo("-23K");
        // -04:00 美东时间转 epoch：2026-09-04 08:30 EDT = 12:30 UTC
        assertThat(nfp.eventTime()).isEqualTo(Instant.parse("2026-09-04T12:30:00Z").toEpochMilli());
        // 空串预测/前值转 null：库里 NULL 与"没有这个字段"同义，不存空串
        assertThat(events.get(1).forecast()).isNull();
        assertThat(events.get(1).previous()).isNull();
    }

    @Test
    void 坏行跳过_不拖垮整批() {
        // 坏法覆盖三种：日期不可解析、缺 impact、缺 country——它们都不能活到 insert 撞 NOT NULL 炸整批事务
        String withBad = """
                [{"title":"Good","country":"USD","date":"2026-09-04T08:30:00-04:00",
                  "impact":"High","forecast":"","previous":""},
                 {"title":"NoDate","country":"USD","date":"not-a-date","impact":"High",
                  "forecast":"","previous":""},
                 {"title":"NoImpact","country":"USD","date":"2026-09-04T08:30:00-04:00",
                  "forecast":"","previous":""},
                 {"title":"NoCountry","date":"2026-09-04T08:30:00-04:00","impact":"High",
                  "forecast":"","previous":""}]""";

        List<EconCalendarCollector.Event> events = EconCalendarCollector.parse(withBad);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().title()).isEqualTo("Good");
    }

    @Test
    void 采集是删窗口重插_先删后插() {
        EconCalendarMapper mapper = mock(EconCalendarMapper.class);
        EconCalendarCollector collector = new EconCalendarCollector(mapper);
        collector.enabled = true;
        collector.feed = () -> FEED;

        collector.collect();

        // 删除起点 = feed 里最早的事件时刻：本周窗口整体覆盖，改期的旧行随删除消失
        long min = Instant.parse("2026-08-30T15:15:00Z").toEpochMilli();
        var order = inOrder(mapper);
        order.verify(mapper).deleteFrom(min);
        order.verify(mapper, times(3)).insert(anyLong(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void feed拉取失败_跳过本轮不动库() {
        EconCalendarMapper mapper = mock(EconCalendarMapper.class);
        EconCalendarCollector collector = new EconCalendarCollector(mapper);
        collector.enabled = true;
        collector.feed = () -> {
            throw new RuntimeException("HTTP 503");
        };

        collector.collect();

        // 旧数据比空表有用：日历事件提前数天可知，沿用上一轮的落库
        verify(mapper, never()).deleteFrom(anyLong());
    }
}

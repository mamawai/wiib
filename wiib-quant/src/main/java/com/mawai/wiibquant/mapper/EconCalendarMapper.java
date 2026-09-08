package com.mawai.wiibquant.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 财经日历读写。与 {@link NewsEventMapper} 同款口径：不建 entity 不继承 BaseMapper，
 * 写路是"删窗口重插"两条 SQL，查询按注入方要的形状投影。
 */
@Mapper
public interface EconCalendarMapper {

    /** 注入块用的事件行（唤醒开场白按它成文） */
    @Data
    class Row {
        private Long eventTime;
        private String currency;
        private String title;
        private String impact;
        /** 共识预测值；null=该事件无数值（讲话/会议类） */
        private String forecast;
        private String previous;
    }

    /** 删本周窗口：feed 是全量快照，起点即 feed 最早事件时刻——改期/取消的旧行随删除消失 */
    @Delete("DELETE FROM econ_calendar_event WHERE event_time >= #{fromMs}")
    int deleteFrom(@Param("fromMs") long fromMs);

    @Insert("""
            INSERT INTO econ_calendar_event (event_time, currency, title, impact, forecast, previous)
            VALUES (#{eventTime}, #{currency}, #{title}, #{impact}, #{forecast}, #{previous})
            """)
    int insert(@Param("eventTime") long eventTime, @Param("currency") String currency,
               @Param("title") String title, @Param("impact") String impact,
               @Param("forecast") String forecast, @Param("previous") String previous);

    @Select("""
            SELECT event_time AS eventTime, currency, title, impact, forecast, previous
              FROM econ_calendar_event
             WHERE event_time BETWEEN #{fromMs} AND #{toMs}
             ORDER BY event_time
            """)
    List<Row> selectWindow(@Param("fromMs") long fromMs, @Param("toMs") long toMs);
}

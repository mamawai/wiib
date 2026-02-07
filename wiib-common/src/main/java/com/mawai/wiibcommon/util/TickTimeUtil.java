package com.mawai.wiibcommon.util;

import java.time.Duration;
import java.time.LocalTime;

public class TickTimeUtil {

    public static final int TOTAL_TICKS = 1440;
    public static final int MORNING_TICKS = 720;
    private static final LocalTime AM_START = LocalTime.of(9, 30);
    private static final LocalTime AM_END = LocalTime.of(11, 30);
    private static final LocalTime PM_START = LocalTime.of(13, 0);
    private static final LocalTime PM_END = LocalTime.of(15, 0);

    public static LocalTime indexToTime(int index) {
        if (index < 0 || index >= TOTAL_TICKS) {
            throw new IllegalArgumentException("index out of range: " + index);
        }
        if (index < MORNING_TICKS) {
            return AM_START.plusSeconds(index * 10L);
        }
        return PM_START.plusSeconds((long) (index - MORNING_TICKS) * 10);
    }

    public static int timeToIndex(LocalTime time) {
        if (time.isBefore(AM_START) || time.isAfter(PM_END)) {
            return -1;
        }
        if (!time.isAfter(AM_END)) {
            return Math.min((int) (Duration.between(AM_START, time).toSeconds() / 10), MORNING_TICKS - 1);
        }
        if (time.isBefore(PM_START)) {
            return -1;
        }
        return Math.min(MORNING_TICKS + (int) (Duration.between(PM_START, time).toSeconds() / 10), TOTAL_TICKS - 1);
    }

    /**
     * 截止到指定时间的有效结束索引，非交易时间吸附到最近已过时段末尾
     * <p>
     * before 09:30  → -1（没开盘）
     * 09:30~11:30   → 上午盘中索引
     * 11:30~13:00   → 719（上午最后一个）
     * 13:00~15:00   → 下午盘中索引
     * after 15:00   → 1439（全天）
     */
    public static int effectiveEndIndex(LocalTime time) {
        if (time.isBefore(AM_START)) return -1;
        if (!time.isAfter(AM_END)) return timeToIndex(time);
        if (time.isBefore(PM_START)) return MORNING_TICKS - 1;
        if (!time.isAfter(PM_END)) return timeToIndex(time);
        return TOTAL_TICKS - 1;
    }
}

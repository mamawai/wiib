package com.mawai.wiibquant.strategy.backtest.task;

import java.util.Map;

/**
 * 可视化回测的结构化事件（策略工作记录一条）。
 * seq = 任务事件表下标，前端按 seq 游标增量拉取，断线/刷新续拉不重不漏。
 * barTimeMs = 事件所在 bar 的 openTime；任务级事件（TASK_START/TASK_FAILED）为 0，前端不做跳转。
 */
public record BacktestEvent(long seq, long barTimeMs, String type, Map<String, Object> data) {
}

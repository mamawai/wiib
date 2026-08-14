package com.mawai.wiibquant.strategy.backtest;

import java.util.Map;

/**
 * 回测过程观察者：引擎在关键节点回调，供可视化回测页还原"策略每一步"。
 * 引擎不传 listener（null）时行为与原来逐字节一致——所有既有调用方零影响。
 */
public interface BacktestListener {

    /** 每根 bar 处理完回调一次（进度用，接收方自行节流）。 */
    default void onBar(int index, int total) {
    }

    /**
     * 结构化事件。type 见任务服务事件表（SIGNAL/ORDER_CANCELLED/ENTRY_FILL/ENTRY_REJECTED/EXIT）；
     * data 值只含 String/Number，可直接 JSON 化。
     */
    default void onEvent(String type, long barTimeMs, Map<String, Object> data) {
    }
}

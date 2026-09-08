package com.mawai.wiibcommon.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * K 线周期枚举，供"主决策周期"配置使用（trading.decision-interval）。
 * <p>
 * 这里的取值必须是采集链路真正拉取的周期（见 CollectDataNode 的周期表）：
 * BuildFeaturesBuilder 拿主周期去 indicatorsByTf 里取 atr 与 bollBw，
 * 没被采集的周期会静默取到 null，snapshot 少两个字段还不报错。
 * 配了不存在的枚举名时 Spring 启动即失败，这正是想要的——炸在启动远好过悄悄降级。
 */
@Getter
@AllArgsConstructor
public enum KlineInterval {

    M1("1m"),
    M5("5m"),
    M15("15m"),
    H1("1h");

    /** Binance API 字符串 */
    private final String code;
}

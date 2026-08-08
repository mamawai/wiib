package com.mawai.wiibquant.agent.trader;

import java.math.BigDecimal;

/** 波动哨兵触发信息：警报唤醒开场白的事实来源（振幅/方向/时刻全部代码注入，模型零考古）。 */
public record AlertTrigger(String symbol, BigDecimal amplitudePct, BigDecimal price,
                           String direction, long triggeredAt) {
}

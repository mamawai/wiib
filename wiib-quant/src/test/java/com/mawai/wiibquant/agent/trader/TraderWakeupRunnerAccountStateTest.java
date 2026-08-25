package com.mawai.wiibquant.agent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.agent.i18n.PromptCatalog;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账户状态 JSON 的挂单段：开仓挂单随计划带上挂出时刻与已挂时长。
 * 挂单回注若只有 playType/失效条件，模型无从知道这张限价单挂了多久——
 * 线上一张限价单挂 5 小时后在瀑布里成交、2 分钟止损，就是这个信息缺口。
 */
class TraderWakeupRunnerAccountStateTest {

    private static final long BOUNDARY = 1_787_000_400_000L;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PromptCatalog prompts = new PromptCatalog();

    @Test
    void pendingOpenOrderCarriesPlacedAtAndPendingFor() {
        long placed = BOUNDARY - (5 * 60 + 19) * 60_000L;
        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setPlayType("BREAKOUT");
        plan.setInvalidationCondition("15m 收盘跌破 79420");
        plan.setOpenedWakeTime(placed);

        FuturesOrderResponse order = new FuturesOrderResponse();
        order.setOrderId(112L);
        order.setSymbol("BTCUSDT");
        order.setOrderSide("OPEN_LONG");
        order.setQuantity(new BigDecimal("1.36"));
        order.setLimitPrice(new BigDecimal("79970"));
        order.setLeverage(50);

        String json = TraderWakeupRunner.accountStateJson(prompts, AgentLang.ZH, new BigDecimal("15833"),
                List.of(), List.of(order), List.of(plan), BOUNDARY, List.of(), List.of());

        JSONObject planJson = JSON.parseObject(json).getJSONArray("pendingOrders")
                .getJSONObject(0).getJSONObject("plan");
        assertThat(planJson.getString("placedAt")).isEqualTo(FMT.format(Instant.ofEpochMilli(placed)));
        assertThat(planJson.getString("pendingFor"))
                .isEqualTo(prompts.get(AgentLang.ZH, "trader.wake.held.hours", Map.of("n", 5L)));
    }
}

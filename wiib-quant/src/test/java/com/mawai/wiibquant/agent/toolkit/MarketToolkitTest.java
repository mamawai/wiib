package com.mawai.wiibquant.agent.toolkit;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketToolkitTest {

    private final MarketDataService dataService = mock(MarketDataService.class);
    private final MarketToolkit toolkit = new MarketToolkit(dataService);

    /** 共用造件见 TestAssemblies（record 无法可靠 mock，统一真实构造）。 */
    private MarketAssembly assemblyWithSnapshot() {
        return TestAssemblies.available();
    }

    @Test
    void marketSnapshotOutputsKeyFields() {
        when(dataService.assemble("BTCUSDT")).thenReturn(assemblyWithSnapshot());

        String json = toolkit.marketSnapshot("BTCUSDT");

        assertThat(json).contains("65000").contains("fundingDeviation").contains("fearGreed")
                .contains("price_change").contains("RANGE");
    }

    @Test
    void optionIvOutputsSummary() {
        when(dataService.assemble("BTCUSDT")).thenReturn(assemblyWithSnapshot());

        String json = toolkit.optionIv("BTCUSDT");

        assertThat(json).contains("DVOL=52");
    }

    @Test
    void unavailableAssemblyDegradesGracefully() {
        when(dataService.assemble("BTCUSDT")).thenReturn(MarketAssembly.unavailable("BTCUSDT", Map.of()));

        assertThat(toolkit.marketSnapshot("BTCUSDT")).contains("\"available\":false");
        assertThat(toolkit.optionIv("BTCUSDT")).contains("\"available\":false");
    }

    /** 取数失败必须给模型一个结构完整的 available:false，而不是半截 JSON 或异常字符串 */
    @Test
    void 资金费取数失败返回不可用JSON() {
        when(dataService.fundingHistory("BTCUSDT")).thenReturn(null);

        String json = toolkit.fundingHistory("BTCUSDT");

        assertThat(json).isEqualTo("{\"available\":false,\"reason\":\"funding data unavailable\"}");
    }

    /** 资金费上下文取不到时也要给结构完整的不可用，而不是让 NPE 冒成一句 Java 异常喂给模型 */
    @Test
    void 资金费上下文取不到时返回不可用JSON() {
        when(dataService.fundingHistory("BTCUSDT")).thenReturn("[]");
        when(dataService.premiumIndex("BTCUSDT")).thenReturn(null);

        String json = toolkit.fundingHistory("BTCUSDT");

        assertThat(json).isEqualTo("{\"available\":false,\"reason\":\"funding data unavailable\"}");
    }

    /** 盘口 WS 和 REST 双双落空时同理：挂限价单的 agent 必须明确知道没数据 */
    @Test
    void 盘口取数失败返回不可用JSON() {
        when(dataService.orderbook("BTCUSDT")).thenReturn(null);

        String json = toolkit.orderbookDepth("BTCUSDT");

        assertThat(json).isEqualTo("{\"available\":false,\"reason\":\"orderbook unavailable\"}");
    }

    /** 数据源是 WS 的 top20，工具描述承诺 top10——不截档就是新造一处"描述与实际不符" */
    @Test
    void 盘口截到前十档() {
        when(dataService.orderbook("BTCUSDT")).thenReturn(depthWithLevels(20));

        JSONObject out = JSON.parseObject(toolkit.orderbookDepth("BTCUSDT"));

        assertThat(out.getJSONArray("bids")).hasSize(10);
        assertThat(out.getJSONArray("asks")).hasSize(10);
        assertThat(out.getJSONArray("bids").getJSONArray(0).getString(0)).isEqualTo("0"); // 仍是最优档打头
    }

    private static String depthWithLevels(int levels) {
        JSONArray bids = new JSONArray();
        JSONArray asks = new JSONArray();
        for (int i = 0; i < levels; i++) {
            bids.add(JSONArray.of(String.valueOf(i), "1"));
            asks.add(JSONArray.of(String.valueOf(i), "1"));
        }
        JSONObject book = new JSONObject();
        book.put("bids", bids);
        book.put("asks", asks);
        return book.toJSONString();
    }
}

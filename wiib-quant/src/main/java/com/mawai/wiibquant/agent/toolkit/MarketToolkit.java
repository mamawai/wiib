package com.mawai.wiibquant.agent.toolkit;
import com.mawai.wiibquant.market.service.MarketAssembly;
import com.mawai.wiibquant.market.service.MarketDataService;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibquant.market.domain.FeatureSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 市场状态工具：实时快照 / 期权IV 走 MarketDataService 共享组装缓存；
 * 盘口深度优先取 WS 快照，断流才回退 REST 缓存（老化粒度独立于快照）。
 *
 * <p>本类所有取数一律经 MarketDataService，自己不直连 REST——ReAct 循环里工具会被反复调，
 * 裸奔的真请求既吃配额又绕开熔断兜底。算钱的路径（下单量/结算价）另有直调
 * BinanceRestClient 的实时通道，不受这层缓存影响。</p>
 */
@Component
@RequiredArgsConstructor
public class MarketToolkit {

    /** orderbook_depth 的 tool description 承诺 top10，输出前按这个档数截齐 */
    private static final int DEPTH_LEVELS = 10;

    private final MarketDataService dataService;

    @Tool(name = "market_snapshot", description = """
            Get real-time market snapshot for a crypto perpetual symbol: price, price changes,
            funding deviation, open-interest change, long/short ratios, top-trader bias, taker pressure,
            liquidation pressure, orderbook imbalance, fear&greed index, regime.
            All signal fields are normalized scores in [-1,1] unless stated otherwise.""")
    public String marketSnapshot(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        MarketAssembly a = dataService.assemble(symbol);
        if (!a.available()) {
            return unavailableJson(a);
        }
        FeatureSnapshot s = a.snapshot();
        JSONObject out = new JSONObject();
        out.put("available", true);
        out.put("symbol", s.symbol());
        out.put("lastPrice", s.lastPrice());
        out.put("atr", s.atr());
        out.put("regime", s.regime().name());
        out.put("price_change", a.featureOutput().get("price_change_map"));
        out.put("fundingDeviation", s.fundingDeviation());
        out.put("oiChangeRate", s.oiChangeRate());
        out.put("lsrExtreme", s.lsrExtreme());
        out.put("topTraderBias", s.topTraderBias());
        out.put("takerPressure", s.takerBuySellPressure());
        out.put("liquidationPressure", s.liquidationPressure());
        out.put("liquidationVolumeUsdt", s.liquidationVolumeUsdt());
        out.put("bidAskImbalance", s.bidAskImbalance());
        out.put("spotBidAskImbalance", s.spotBidAskImbalance());
        out.put("tradeDelta", s.tradeDelta());
        out.put("largeTradeBias", s.largeTradeBias());
        out.put("spotPerpBasisBps", s.spotPerpBasisBps());
        out.put("fearGreed", s.fearGreedIndex() + "(" + s.fearGreedLabel() + ")");
        if (!s.qualityFlags().isEmpty()) {
            out.put("qualityFlags", s.qualityFlags());
        }
        return out.toJSONString();
    }

    @Tool(name = "option_iv", description = """
            Get option implied-volatility context for a crypto symbol from Deribit:
            DVOL index and ATM IV summary. Useful for judging whether the options market
            is pricing in larger moves than realized volatility suggests.""")
    public String optionIv(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        MarketAssembly a = dataService.assemble(symbol);
        if (!a.available()) {
            return unavailableJson(a);
        }
        JSONObject out = new JSONObject();
        out.put("available", true);
        out.put("symbol", a.snapshot().symbol());
        out.put("dvolIndex", a.snapshot().dvolIndex());
        out.put("ivSummary", a.snapshot().toIvSummary());
        return out.toJSONString();
    }

    @Tool(name = "funding_history", description = """
            Get recent funding rate history (last 30 settlements, 8h apart) plus the next funding
            time and current mark price for a crypto perpetual symbol. Useful for carry judgment:
            persistently positive funding = longs paying shorts (crowded long), negative = the opposite.""")
    public String fundingHistory(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        String raw = dataService.fundingHistory(symbol);
        if (raw == null) {
            return errorJson("funding data unavailable");
        }
        try {
            JSONObject out = new JSONObject();
            out.put("available", true);
            JSONArray history = JSON.parseArray(raw);
            JSONArray compact = new JSONArray();
            for (int i = 0; i < history.size(); i++) {
                JSONObject h = history.getJSONObject(i);
                JSONObject row = new JSONObject();
                row.put("time", h.getLong("fundingTime"));
                row.put("rate", h.getString("fundingRate"));
                compact.add(row);
            }
            out.put("history", compact);
            // 先判空再解析：取不到且没有过期缓存可兜时这里给 null，而 JSON.parseObject(null) 也是 null，
            // 后面三个 getter 直接 NPE，异常信息（fastjson2 内部类名）会顺着 catch 喂给模型
            String premiumRaw = dataService.premiumIndex(symbol);
            if (premiumRaw == null) {
                return errorJson("funding data unavailable");
            }
            JSONObject premium = JSON.parseObject(premiumRaw);
            out.put("nextFundingTime", premium.getLong("nextFundingTime"));
            out.put("lastFundingRate", premium.getString("lastFundingRate"));
            out.put("markPrice", premium.getString("markPrice"));
            return out.toJSONString();
        } catch (Exception e) {
            return errorJson("funding data unavailable: " + e.getMessage());
        }
    }

    @Tool(name = "orderbook_depth", description = """
            Get the top 10 bid/ask levels (price, quantity) of the futures orderbook for a crypto
            perpetual symbol. Useful for seeing where large resting orders (walls) sit relative to
            current price when choosing limit order placement or judging near support/resistance.""")
    public String orderbookDepth(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        String raw = dataService.orderbook(symbol);
        if (raw == null) {
            return errorJson("orderbook unavailable");
        }
        try {
            JSONObject book = JSON.parseObject(raw);
            JSONObject out = new JSONObject();
            out.put("available", true);
            // 数据源档数不定（WS 快照是 top20，REST 兜底是 top10），这里统一截到工具描述承诺的 10 档
            out.put("bids", topLevels(book.getJSONArray("bids")));
            out.put("asks", topLevels(book.getJSONArray("asks")));
            return out.toJSONString();
        } catch (Exception e) {
            return errorJson("orderbook unavailable: " + e.getMessage());
        }
    }

    private static JSONArray topLevels(JSONArray levels) {
        if (levels == null || levels.size() <= DEPTH_LEVELS) {
            return levels;
        }
        return new JSONArray(levels.subList(0, DEPTH_LEVELS));
    }

    private static String errorJson(String reason) {
        JSONObject out = new JSONObject();
        out.put("available", false);
        out.put("reason", reason);
        return out.toJSONString();
    }

    private static String unavailableJson(MarketAssembly a) {
        JSONObject out = new JSONObject();
        out.put("available", false);
        out.put("reason", "market data unavailable for " + a.symbol());
        return out.toJSONString();
    }
}

package com.mawai.wiibquant.agent.toolkit;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 市场状态工具：实时快照 / 期权IV 走 MarketDataService 共享组装（60s缓存），
 * 一轮对话内多工具调用不重复采集；资金费历史 / 盘口深度直连 Binance REST（轻量免费）。
 */
@Component
@RequiredArgsConstructor
public class MarketToolkit {

    private final MarketDataService dataService;
    private final BinanceRestClient binanceRestClient;

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
        String sym = QuantConstants.normalizeSymbolLenient(symbol);
        try {
            JSONObject out = new JSONObject();
            out.put("available", true);
            JSONArray history = JSON.parseArray(binanceRestClient.getFundingRateHistory(sym, 30));
            JSONArray compact = new JSONArray();
            for (int i = 0; i < history.size(); i++) {
                JSONObject h = history.getJSONObject(i);
                JSONObject row = new JSONObject();
                row.put("time", h.getLong("fundingTime"));
                row.put("rate", h.getString("fundingRate"));
                compact.add(row);
            }
            out.put("history", compact);
            JSONObject premium = JSON.parseObject(binanceRestClient.getPremiumIndex(sym));
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
        String sym = QuantConstants.normalizeSymbolLenient(symbol);
        try {
            JSONObject book = JSON.parseObject(binanceRestClient.getFuturesOrderbook(sym, 10));
            JSONObject out = new JSONObject();
            out.put("available", true);
            out.put("bids", book.getJSONArray("bids"));
            out.put("asks", book.getJSONArray("asks"));
            return out.toJSONString();
        } catch (Exception e) {
            return errorJson("orderbook unavailable: " + e.getMessage());
        }
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

package com.mawai.wiibquant.agent.behavior;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * isValid 的字段契约。历史坑：老 GBM 股市/期权下线、数据源端点已删后，isValid 仍强制要求
 * stock 字段——模型只能瞎编才能过校验，一旦不配合就每次全额烧一遍 agent 且永不进缓存。
 * 本测试钉死"现役维度齐全即有效"：现役 = crypto/bstock/futures/prediction（bStock 是
 * 代币化美股、共用现货引擎，有真实数据源 bstock-stats），已下线维度不许再回到硬校验。
 */
class BehaviorReportValidityTest {

    /** 现役维度齐全的最小有效报告 */
    private static BehaviorAnalysisReport minimalValidReport() {
        BehaviorAnalysisReport r = new BehaviorAnalysisReport();

        BehaviorAnalysisReport.Overview overview = new BehaviorAnalysisReport.Overview();
        overview.setTotalAssets(new BigDecimal("10000"));
        overview.setTotalProfitPct(new BigDecimal("1.5"));
        overview.setDistribution(List.of());
        r.setOverview(overview);

        BehaviorAnalysisReport.CryptoBehavior crypto = new BehaviorAnalysisReport.CryptoBehavior();
        crypto.setTotalBuyAmount(BigDecimal.ONE);
        crypto.setTotalSellAmount(BigDecimal.ONE);

        BehaviorAnalysisReport.BStockBehavior bstock = new BehaviorAnalysisReport.BStockBehavior();
        bstock.setTotalBuyAmount(BigDecimal.ONE);
        bstock.setTotalSellAmount(BigDecimal.ONE);

        BehaviorAnalysisReport.FuturesBehavior futures = new BehaviorAnalysisReport.FuturesBehavior();
        futures.setRealizedPnl(BigDecimal.ZERO);
        futures.setAvgLeverage(new BigDecimal("10"));

        BehaviorAnalysisReport.PredictionBehavior prediction = new BehaviorAnalysisReport.PredictionBehavior();
        prediction.setNetProfit(BigDecimal.ZERO);
        prediction.setWinRate(new BigDecimal("0.5"));

        BehaviorAnalysisReport.TradeBehavior trade = new BehaviorAnalysisReport.TradeBehavior();
        trade.setCrypto(crypto);
        trade.setBstock(bstock);
        trade.setFutures(futures);
        trade.setPrediction(prediction);
        r.setTradeBehavior(trade);

        r.setRiskProfile(new BehaviorAnalysisReport.RiskProfile());
        r.setSuggestions(List.of("建议"));
        return r;
    }

    @Test
    void 现役维度齐全即有效_不再要求已下线的股票期权() {
        assertThat(minimalValidReport().isValid()).isTrue();
    }

    @Test
    void 现役维度缺失仍判无效() {
        BehaviorAnalysisReport noCrypto = minimalValidReport();
        noCrypto.getTradeBehavior().setCrypto(null);
        assertThat(noCrypto.isValid()).isFalse();

        BehaviorAnalysisReport noBstock = minimalValidReport();
        noBstock.getTradeBehavior().setBstock(null);
        assertThat(noBstock.isValid()).isFalse();

        BehaviorAnalysisReport noFutures = minimalValidReport();
        noFutures.getTradeBehavior().setFutures(null);
        assertThat(noFutures.isValid()).isFalse();

        BehaviorAnalysisReport noSuggestions = minimalValidReport();
        noSuggestions.setSuggestions(null);
        assertThat(noSuggestions.isValid()).isFalse();
    }
}

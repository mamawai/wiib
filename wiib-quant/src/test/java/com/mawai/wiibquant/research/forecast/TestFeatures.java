package com.mawai.wiibquant.research.forecast;

import com.mawai.wiibcommon.market.KlineBar;

import java.util.List;

/** 测试造数：ResearchFeatures 的便捷工厂，链下/链上全取中性默认。生产一律全参构造（见 ResearchEvalService）。 */
final class TestFeatures {

    private TestFeatures() {
    }

    /** 纯价格场景：EWMA/HAR-RV 等只看 K 线的预测器 */
    static ResearchFeatures ofBars(List<KlineBar> barsUpToNow) {
        return of(barsUpToNow, 0.0, 50);
    }

    /** 三因子场景（趋势+资金费+恐惧贪婪）：链上 ETF/稳定币取中性 0 */
    static ResearchFeatures of(List<KlineBar> barsUpToNow, double fundingRate, int fearGreed) {
        return new ResearchFeatures(barsUpToNow, fundingRate, fearGreed, 0.0, 0.0);
    }
}

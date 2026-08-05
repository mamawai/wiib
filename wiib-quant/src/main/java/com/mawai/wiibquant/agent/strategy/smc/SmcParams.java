package com.mawai.wiibquant.agent.strategy.smc;

/**
 * SMC策略3(FVG回补)参数组；回测扫参整组替换，避免散落魔法常量。
 *
 * <p>4h结构(HH+HL/LH+LL)定方向，1h找带位移、未缓解、中位未被触及的最新FVG，
 * 限价挂FVG中位等首次回补(maker)。止损=FVG外沿±ATR缓冲(完全穿越=缺口叙事失效)，
 * 止盈=1h摆动区间对面(前高/前低=对面流动性)。
 * fvgLookbackBars 既是FVG回看窗口也是龄上限(三根K滑出窗口即作废)；
 * orderTimeoutBars 是挂单超时(5m根数)：候选装上后这么多根未成交即作废本FVG。</p>
 */
public record SmcParams(
        long biasTfMillis,          // 结构偏向判定周期（默认4h）
        long structTfMillis,        // FVG检测+摆动锚定周期（默认1h）
        int atrPeriod,              // ATR长度（pivot确认/位移过滤/SL缓冲共用，沿用fibo惯例14）
        double reversalAtrMult,     // pivot反转确认阈值(×ATR)，沿用fibo惯例2.0
        double displacementAtrMult, // FVG推动K线实体最小位移(×ATR)；0=关，留给消融
        boolean discountFilterOn,   // Discount门：做多要求FVG中位在摆动区间50%下方（做空对称）
        double slBufferAtrMult,     // 止损在FVG外沿再留的ATR缓冲
        int orderTimeoutBars,       // 挂单超时（5m根数，默认576=2天）
        int fvgLookbackBars,        // FVG回看窗口+龄上限（structTf根数，默认120=5天）
        int biasLookbackBars        // 偏向判定回看（biasTf根数，默认360=60天）
) {

    public static SmcParams defaults() {
        return new SmcParams(
                4 * 3_600_000L, 3_600_000L,
                14, 2.0,
                1.5,
                true,
                0.1,
                576, 120, 360);
    }
}

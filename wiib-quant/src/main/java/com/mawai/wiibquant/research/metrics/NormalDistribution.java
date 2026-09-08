package com.mawai.wiibquant.research.metrics;

/**
 * 标准正态分布 CDF Φ（自实现，无外部依赖——spec §4 铁律）。
 * Diebold-Mariano 检验靠它把 z 值转 p 值；DSR/PSR 落地时再补 Φ⁻¹。
 */
public final class NormalDistribution {

    private static final double SQRT2 = Math.sqrt(2.0);

    private NormalDistribution() {
    }

    /** Φ(x)=P(Z≤x)。erf 用 Abramowitz-Stegun 7.1.26（|误差|≤1.5e-7）。 */
    public static double cdf(double x) {
        return 0.5 * (1.0 + erf(x / SQRT2));
    }

    private static double erf(double x) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double poly = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t;
        double y = 1.0 - poly * Math.exp(-x * x);
        return Math.signum(x) * y;              // signum(0)=0 → erf(0)=0 → Φ(0)=0.5 精确
    }
}

package com.mawai.wiibsim.service;

import java.math.BigDecimal;

/**
 * 全仓强平引擎。
 *
 * <p>与逐仓的"静态强平价 + ZSet 价格穿越"不同：全仓强平线随余额和所有仓位浮盈亏实时变化、
 * 跨 symbol 耦合，无法预存静态价。改用账户级健康检查：价格 tick 定向触发（只查该 symbol 上
 * 有全仓仓位、且价格出了安全带的用户，带的数学见 CrossBandRegistry）+ 低频兜底轮询。</p>
 *
 * <p>插针语义：tick 价作为"钉住价"随触发一路传到判定与结算——哪怕缓存价下一秒回落，
 * 触发那一刻的插针价仍是该 symbol 的估值与强平结算价（其余 symbol 照缓存），对齐
 * "mark 触线即爆"。宕机/断连空窗由 liq-recover 的高低两端补触发 + 30s 兜底轮询覆盖。</p>
 *
 * <p>爆仓 = 全组爆：equity ≤ Σ维持保证金时，该用户所有全仓仓位按 mark 价一次性强平，
 * 盈亏净额直接结算进余额钱包（可为负）；结算后余额 &lt; 0 即穿仓 → 立即破产
 * （清空两个钱包，次一交易日重置初始资金）。</p>
 */
public interface CrossLiquidationService {

    /** markprice tick：对该 symbol 上持有全仓仓位且出了安全带的用户触发精查（带内直接跳过） */
    void onPriceTick(String symbol, BigDecimal markPrice);

    /** 单用户健康检查，无钉住价（兜底轮询/资金费后直调用这个口） */
    void checkUser(Long userId);

    /**
     * 单用户健康检查，pinSymbol 的估值与强平结算钉在 pinPrice（插针语义）；
     * 带用户级锁（有界等待，插针触发绝不静默丢弃），可安全并发调用。
     */
    void checkUser(Long userId, String pinSymbol, BigDecimal pinPrice);

    /** 兜底轮询：检查所有持有全仓仓位的用户（无钉住价，顺带重建全员安全带） */
    void sweepAll();
}

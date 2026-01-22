package com.mawai.wiibcommon.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 用户资产变化事件
 * 触发场景：余额变化、持仓变化、市值变化
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssetChangeEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long userId;

    /** 可用余额 */
    private BigDecimal balance;

    /** 冻结余额 */
    private BigDecimal frozenBalance;

    /** 持仓总市值 */
    private BigDecimal positionMarketValue;

    /** 待结算金额 */
    private BigDecimal pendingSettlement;

    /** 杠杆借款本金 */
    private BigDecimal marginLoanPrincipal;

    /** 杠杆应计利息 */
    private BigDecimal marginInterestAccrued;

    /** 总资产（净资产=余额+冻结+持仓市值+待结算-借款本金-应计利息） */
    private BigDecimal totalAssets;

    /** 是否破产（爆仓） */
    private Boolean bankrupt;

    /** 破产次数 */
    private Integer bankruptCount;

    /** 破产恢复日期 */
    private LocalDate bankruptResetDate;

    /** 盈亏 */
    private BigDecimal profit;

    /** 盈亏率(%) */
    private BigDecimal profitPct;

    /** 变化原因 */
    private String reason;

    /** 事件时间戳 */
    private Long timestamp;

    public AssetChangeEvent(Long userId, BigDecimal balance, BigDecimal frozenBalance,
                            BigDecimal positionMarketValue, BigDecimal pendingSettlement, String reason) {
        this(userId, balance, frozenBalance, positionMarketValue, pendingSettlement,
                BigDecimal.ZERO, BigDecimal.ZERO, false, 0, null, reason, new BigDecimal("100000"));
    }

    public AssetChangeEvent(Long userId, BigDecimal balance, BigDecimal frozenBalance,
                            BigDecimal positionMarketValue, BigDecimal pendingSettlement,
                            BigDecimal marginLoanPrincipal, BigDecimal marginInterestAccrued,
                            Boolean bankrupt, Integer bankruptCount, LocalDate bankruptResetDate,
                            String reason) {
        this(userId, balance, frozenBalance, positionMarketValue, pendingSettlement,
                marginLoanPrincipal, marginInterestAccrued, bankrupt, bankruptCount, bankruptResetDate,
                reason, new BigDecimal("100000"));
    }

    public AssetChangeEvent(Long userId, BigDecimal balance, BigDecimal frozenBalance,
                            BigDecimal positionMarketValue, BigDecimal pendingSettlement,
                            BigDecimal marginLoanPrincipal, BigDecimal marginInterestAccrued,
                            Boolean bankrupt, Integer bankruptCount, LocalDate bankruptResetDate,
                            String reason, BigDecimal initialBalance) {
        this.userId = userId;
        this.balance = balance != null ? balance : BigDecimal.ZERO;
        this.frozenBalance = frozenBalance != null ? frozenBalance : BigDecimal.ZERO;
        this.positionMarketValue = positionMarketValue != null ? positionMarketValue : BigDecimal.ZERO;
        this.pendingSettlement = pendingSettlement != null ? pendingSettlement : BigDecimal.ZERO;
        this.marginLoanPrincipal = marginLoanPrincipal != null ? marginLoanPrincipal : BigDecimal.ZERO;
        this.marginInterestAccrued = marginInterestAccrued != null ? marginInterestAccrued : BigDecimal.ZERO;
        this.bankrupt = bankrupt != null ? bankrupt : false;
        this.bankruptCount = bankruptCount != null ? bankruptCount : 0;
        this.bankruptResetDate = bankruptResetDate;

        this.totalAssets = this.balance
                .add(this.frozenBalance)
                .add(this.positionMarketValue)
                .add(this.pendingSettlement)
                .subtract(this.marginLoanPrincipal)
                .subtract(this.marginInterestAccrued);

        this.profit = this.totalAssets.subtract(initialBalance);
        this.profitPct = initialBalance.compareTo(BigDecimal.ZERO) > 0
                ? this.profit.divide(initialBalance, 4, java.math.RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        this.reason = reason;
        this.timestamp = System.currentTimeMillis();
    }
}

package com.mawai.wiibsim.campaign.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 一个用户在一个现货标的上的成交聚合。
 * <p>
 * 买入门槛按活动期窗口算（保证活动期内真金白银参与过），
 * 整体收益率按全历史净现金流算（单笔收益率可以靠"只卖赚的、亏的扛着"造假，
 * 标的整体收益率造不了假）—— 两个窗口不同是设计如此，不是笔误。
 */
@Data
public class SpotSymbolRow {

    private Long userId;

    private String symbol;

    /** 活动期内累计买入额（含手续费） */
    private BigDecimal buyInWindow;

    /** 全历史累计买入额（含手续费），收益率的分母 */
    private BigDecimal buyAll;

    /** 全历史累计卖出净得（已扣手续费） */
    private BigDecimal sellAll;
}

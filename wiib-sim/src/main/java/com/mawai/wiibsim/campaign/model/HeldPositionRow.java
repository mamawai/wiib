package com.mawai.wiibsim.campaign.model;

import lombok.Data;

import java.math.BigDecimal;

/** 现货在持数量（含冻结），算标的整体收益时的"剩余持仓"那一项 */
@Data
public class HeldPositionRow {

    private Long userId;

    private String symbol;

    /** 可用 + 冻结。冻结的是挂单锁住的量，仍是这个人的持仓 */
    private BigDecimal qty;
}

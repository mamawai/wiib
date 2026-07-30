package com.mawai.wiibsim.campaign.model;

import java.math.BigDecimal;

/**
 * 一条积分明细。前端任务清单直接渲染这个：label 是标题，count 是进度计数，score 是已得分。
 *
 * @param code  稳定标识，前端按它决定图标与分组，改文案不影响前端
 * @param count 达成次数；一次性任务达成为 1、未达成不产出该条
 * @param score 该项累计得分，罚分为负
 */
public record ScoreItem(String code, String label, int count, BigDecimal score) {

    public static ScoreItem of(String code, String label, int count, int score) {
        return new ScoreItem(code, label, count, BigDecimal.valueOf(score));
    }
}

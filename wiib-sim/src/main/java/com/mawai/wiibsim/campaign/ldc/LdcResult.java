package com.mawai.wiibsim.campaign.ldc;

/**
 * 分发结果。
 *
 * @param tradeNo  LDC 侧流水号；重复提交命中幂等时为 null（钱上次已经发过了，拿不到号）
 * @param errorMsg 失败原因原文，落 campaign_reward.error_msg 供人工排查
 */
public record LdcResult(boolean success, String tradeNo, String errorMsg) {

    public static LdcResult ok(String tradeNo) {
        return new LdcResult(true, tradeNo, null);
    }

    /** 命中 out_trade_no 幂等：此前已发放成功 */
    public static LdcResult alreadySent() {
        return new LdcResult(true, null, null);
    }

    public static LdcResult fail(String msg) {
        return new LdcResult(false, null, msg);
    }
}

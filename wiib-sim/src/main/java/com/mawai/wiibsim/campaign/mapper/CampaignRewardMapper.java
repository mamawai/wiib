package com.mawai.wiibsim.campaign.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibsim.campaign.entity.CampaignReward;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CampaignRewardMapper extends BaseMapper<CampaignReward> {

    @Select("SELECT * FROM campaign_reward WHERE campaign_id = #{campaignId} AND user_id = #{userId}")
    CampaignReward selectMine(@Param("campaignId") Long campaignId, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM campaign_reward WHERE campaign_id = #{campaignId}")
    int countByCampaign(@Param("campaignId") Long campaignId);

    /**
     * CAS 抢领取权：只有 PENDING 或 FAILED 能进 CLAIMED。
     * <p>
     * 【为什么允许 FAILED 重来】发放失败多半是收款人不存在之类的可修问题，
     * 修好了应该能再领一次；而 out_trade_no 不变，就算上次其实发出去了，
     * 重发也会撞唯一索引被判成功，不会重复给钱。
     */
    @Update("UPDATE campaign_reward SET status = 'CLAIMED', linux_do_id = #{linuxDoId}, " +
            "username = #{username}, updated_at = NOW() " +
            "WHERE id = #{id} AND status IN ('PENDING', 'FAILED')")
    int casClaim(@Param("id") Long id,
                 @Param("linuxDoId") String linuxDoId,
                 @Param("username") String username);

    @Update("UPDATE campaign_reward SET status = 'SUCCESS', external_ref = #{tradeNo}, " +
            "error_msg = NULL, updated_at = NOW() WHERE id = #{id} AND status = 'CLAIMED'")
    int markSuccess(@Param("id") Long id, @Param("tradeNo") String tradeNo);

    @Update("UPDATE campaign_reward SET status = 'FAILED', error_msg = #{msg}, updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'CLAIMED'")
    int markFailed(@Param("id") Long id, @Param("msg") String msg);
}

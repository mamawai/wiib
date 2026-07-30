package com.mawai.wiibsim.campaign.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibsim.campaign.entity.CampaignCheckin;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface CampaignCheckinMapper extends BaseMapper<CampaignCheckin> {

    /**
     * 全站签到记录。~100 人 × 14 天最多 1400 行，一次拿完在内存里算连续段，
     * 比"每人一次 SQL"省得多，也让积分表能一次算完。
     */
    @Select("SELECT * FROM campaign_checkin WHERE campaign_id = #{campaignId}")
    List<CampaignCheckin> listByCampaign(@Param("campaignId") Long campaignId);

    @Select("SELECT COUNT(*) FROM campaign_checkin " +
            "WHERE campaign_id = #{campaignId} AND user_id = #{userId} AND checkin_date = #{date}")
    int countByDate(@Param("campaignId") Long campaignId,
                    @Param("userId") Long userId,
                    @Param("date") LocalDate date);
}

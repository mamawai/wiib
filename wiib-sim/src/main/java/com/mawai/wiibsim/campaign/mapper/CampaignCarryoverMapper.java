package com.mawai.wiibsim.campaign.mapper;

import com.mawai.wiibsim.campaign.model.CarryoverRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 重置遗留次数表。写入只发生在重置事务里（AccountPurgeTx → CampaignCarryoverService），
 * 读取只发生在 TradeScorer.scoreAll 合并次数时。
 */
@Mapper
public interface CampaignCarryoverMapper {

    /**
     * 次数累加式 UPSERT：第二次重置把新攒的次数<b>加在</b>上次遗留上，不是覆盖。
     * 覆盖的话第一次重置前攒的就丢了。
     */
    @Insert("""
            INSERT INTO campaign_carryover (campaign_id, user_id, code, cnt)
            VALUES (#{campaignId}, #{userId}, #{code}, #{cnt})
            ON CONFLICT ON CONSTRAINT uk_campaign_carryover
            DO UPDATE SET cnt = campaign_carryover.cnt + EXCLUDED.cnt,
                          updated_at = CURRENT_TIMESTAMP
            """)
    void upsertAdd(@Param("campaignId") Long campaignId,
                   @Param("userId") Long userId,
                   @Param("code") String code,
                   @Param("cnt") int cnt);

    @Select("""
            SELECT user_id AS user_id, code AS code, cnt AS cnt
            FROM campaign_carryover
            WHERE campaign_id = #{campaignId}
            """)
    List<CarryoverRow> listByCampaign(@Param("campaignId") Long campaignId);
}

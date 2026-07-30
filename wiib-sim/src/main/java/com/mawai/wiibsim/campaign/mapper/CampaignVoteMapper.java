package com.mawai.wiibsim.campaign.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibsim.campaign.entity.CampaignVote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface CampaignVoteMapper extends BaseMapper<CampaignVote> {

    /** 某日某标的的多空票数：direction → count */
    @Select("SELECT direction, COUNT(*) AS cnt FROM campaign_vote " +
            "WHERE campaign_id = #{campaignId} AND vote_date = #{date} AND symbol = #{symbol} " +
            "GROUP BY direction")
    List<Map<String, Object>> countByDirection(@Param("campaignId") Long campaignId,
                                               @Param("date") LocalDate date,
                                               @Param("symbol") String symbol);

    @Select("SELECT * FROM campaign_vote " +
            "WHERE campaign_id = #{campaignId} AND user_id = #{userId} AND vote_date = #{date}")
    List<CampaignVote> listMine(@Param("campaignId") Long campaignId,
                                @Param("userId") Long userId,
                                @Param("date") LocalDate date);

    /** 某日全部待结算的票（result IS NULL），结算任务用 */
    @Select("SELECT * FROM campaign_vote " +
            "WHERE campaign_id = #{campaignId} AND vote_date = #{date} AND result IS NULL")
    List<CampaignVote> listUnsettled(@Param("campaignId") Long campaignId,
                                     @Param("date") LocalDate date);

    /**
     * 按用户汇总投票得分，未结算的票按 0 计。
     * <p>
     * 【别拿它当参与名单】这里不筛 result IS NOT NULL，只投过票还没结算的人也会出一行、total=0。
     * 总分是对的，但"出现在结果里"不等于"拿过分"。
     */
    @Select("SELECT user_id, COALESCE(SUM(COALESCE(score, 0)), 0) AS total " +
            "FROM campaign_vote WHERE campaign_id = #{campaignId} GROUP BY user_id")
    List<Map<String, Object>> sumScoreByUser(@Param("campaignId") Long campaignId);

    /** 全场已发出的投票分总额，用于反推当日可分池 */
    @Select("SELECT COALESCE(SUM(COALESCE(score, 0)), 0) FROM campaign_vote WHERE campaign_id = #{campaignId}")
    BigDecimal sumAllScore(@Param("campaignId") Long campaignId);

    /** CAS 回填结算结果：只改还没结算过的那些，重复结算不会覆盖已发的分 */
    @Update("UPDATE campaign_vote SET result = #{result}, score = #{score}, updated_at = NOW() " +
            "WHERE id = #{id} AND result IS NULL")
    int settle(@Param("id") Long id, @Param("result") String result, @Param("score") BigDecimal score);
}

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
     * 全场还没结算的投票日，升序去重。<b>结算前的放行条件就是它返回空。</b>
     * <p>
     * 【为什么结算必须等它空】未结算的票 score 为 NULL，按 0 计入榜单
     * （{@link #sumScoreByUser}）—— 拿这份榜去分池子，那些人的 vote_score 被永久少算，
     * 而发放是 CAS 幂等的，发完纠不回来。
     * <p>
     * 【为什么按天返回而不是只给个数】
     * {@link com.mawai.wiibsim.campaign.service.CampaignVoteService#settleDay} 只结<b>已经过完</b>的
     * UTC 日：TZ=+8 时活动在 UTC 末日的 16:00 收摊，那天要再等 8 小时（或下一次 00:05 回扫）
     * 才结得上。运营看到具体是哪几天没结，才知道是"再等等"还是"某天一直取不到日线，得去查"。
     */
    @Select("SELECT vote_date FROM campaign_vote " +
            "WHERE campaign_id = #{campaignId} AND result IS NULL " +
            "GROUP BY vote_date ORDER BY vote_date")
    List<LocalDate> listUnsettledDates(@Param("campaignId") Long campaignId);

    /**
     * 按用户汇总投票得分，未结算的票按 0 计。<b>不卡日期</b> —— 榜单要的是全场总分。
     * <p>
     * 【别拿它当参与名单】这里不筛 result IS NOT NULL，只投过票还没结算的人也会出一行、total=0。
     * 总分是对的，但"出现在结果里"不等于"拿过分"。
     */
    @Select("SELECT user_id, COALESCE(SUM(COALESCE(score, 0)), 0) AS total " +
            "FROM campaign_vote WHERE campaign_id = #{campaignId} GROUP BY user_id")
    List<Map<String, Object>> sumScoreByUser(@Param("campaignId") Long campaignId);

    /**
     * 截至某投票日（含）已发出的投票分总额，用于反推当日可分池。
     * <p>
     * 【为什么必须卡 vote_date 而不是全场求和】不卡的话这个式子只在"按日期顺序结算"时才对：
     * 漏结的那天隔几天补跑时，后面几天的分已经计进来了，
     * {@code 100×已过天数 − 已发} 会算成负数、被夹到 0，补结的那天全员发 0 分 —— CAS 之后不可逆。
     * 卡上日期，每天的池就只跟它自己和它<b>之前</b>的日子有关，什么时候补跑结果都一样。
     * 顺序结算时这两种写法逐位相同（后面的日子还没结，本来就没分可加）。
     */
    @Select("SELECT COALESCE(SUM(COALESCE(score, 0)), 0) FROM campaign_vote " +
            "WHERE campaign_id = #{campaignId} AND vote_date <= #{voteDate}")
    BigDecimal sumScoreUpTo(@Param("campaignId") Long campaignId, @Param("voteDate") LocalDate voteDate);

    /** CAS 回填结算结果：只改还没结算过的那些，重复结算不会覆盖已发的分 */
    @Update("UPDATE campaign_vote SET result = #{result}, score = #{score}, updated_at = NOW() " +
            "WHERE id = #{id} AND result IS NULL")
    int settle(@Param("id") Long id, @Param("result") String result, @Param("score") BigDecimal score);
}

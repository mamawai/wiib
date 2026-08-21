package com.mawai.wiibsim.campaign.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibsim.campaign.entity.Campaign;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CampaignMapper extends BaseMapper<Campaign> {

    /**
     * 当前活动：进行中或结算中都算。同时只允许一场，多了取最新那场。
     * SETTLING 也算是因为结算后还要显示榜单与领取入口；DONE 是收摊终态，入口消失。
     */
    @Select("SELECT * FROM campaign WHERE status IN ('RUNNING', 'SETTLING') ORDER BY start_at DESC LIMIT 1")
    Campaign selectActive();
}

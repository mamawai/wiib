package com.mawai.wiibsim.campaign.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibsim.campaign.entity.Campaign;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CampaignMapper extends BaseMapper<Campaign> {

    /**
     * 当前活动：进行中或结算中都算。同时只允许一场，多了取最新那场。
     * <p>
     * 【为什么 SETTLING 也要算】结算之后活动页还要显示最终榜单与领取入口，
     * 只认 RUNNING 的话一结算整个活动就查不到了，用户点进来一片空白、也领不了钱。
     * <p>
     * 【DONE 不算】DONE 是运营手工收摊的终态，那时领取期已过，活动入口就该消失。
     */
    @Select("SELECT * FROM campaign WHERE status IN ('RUNNING', 'SETTLING') ORDER BY start_at DESC LIMIT 1")
    Campaign selectActive();
}

package com.mawai.wiibsim.campaign.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibsim.campaign.entity.Campaign;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CampaignMapper extends BaseMapper<Campaign> {

    /** 当前进行中的活动。同时只允许一场，多了取最新那场 */
    @Select("SELECT * FROM campaign WHERE status = 'RUNNING' ORDER BY start_at DESC LIMIT 1")
    Campaign selectRunning();
}

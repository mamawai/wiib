package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.Settlement;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SettlementMapper extends BaseMapper<Settlement> {

    /** 删除用户全部待结算（爆仓/恢复兜底用） */
    @Delete("DELETE FROM settlement WHERE user_id = #{userId} AND status = 'PENDING'")
    int deletePendingByUserId(@Param("userId") Long userId);
}

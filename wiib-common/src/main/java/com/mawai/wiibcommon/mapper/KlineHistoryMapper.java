package com.mawai.wiibcommon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.KlineHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KlineHistoryMapper extends BaseMapper<KlineHistory> {

    /** 幂等批插：唯一键冲突直接跳过 → 回填可重复跑、回测可复现。
     *  SQL 在 resources/mapper/KlineHistoryMapper.xml（批量 VALUES 行数可变，须 &lt;foreach&gt; 动态拼） */
    int batchInsertIgnore(@Param("list") List<KlineHistory> rows);
}

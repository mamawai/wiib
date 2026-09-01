package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.UserLedger;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserLedgerMapper extends BaseMapper<UserLedger> {

    /** 账号重置用：账本随账户一起清空，之后补一条 INITIAL_GRANT */
    @Delete("DELETE FROM user_ledger WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);

    /**
     * 账单分页（游标翻页，吃 idx_ledger_user_time）。
     * beforeId 传 null 取最新一页；bizType 传 null 不筛类型。
     * <p>
     * SQL 在 resources/mapper/UserLedgerMapper.xml：beforeId 是游标边界（id &lt; ? 决定索引扫描范围），
     * 必须 &lt;if&gt; 动态拼接——写成 (? IS NULL OR id &lt; ?) 在 generic plan 下会退化成全量过滤。
     */
    List<UserLedger> selectByCursor(@Param("userId") Long userId,
                                    @Param("bizType") String bizType,
                                    @Param("beforeId") Long beforeId,
                                    @Param("limit") int limit);
}

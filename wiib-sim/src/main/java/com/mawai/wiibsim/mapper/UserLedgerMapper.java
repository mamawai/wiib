package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.UserLedger;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface UserLedgerMapper extends BaseMapper<UserLedger> {

    /**
     * 不变量校验：某钱包全部流水累加，应等于 user 表当前该列的值。
     * <p>
     * 只对<b>账本上线后建号</b>的用户成立——期初基准是建号时补的那条 INITIAL_GRANT。
     * 存量用户的期初值既没记录也不回填（账本只记上线之后的变动），拿它们对账每个钱包各差
     * "上线那一刻该列的值"，那是口径不是漏账。详见 UserLedgerRealRunTest#assertInvariant。
     */
    @Select("SELECT COALESCE(SUM(delta), 0) FROM user_ledger WHERE user_id = #{userId} AND wallet = #{wallet}")
    BigDecimal sumDeltaByWallet(@Param("userId") Long userId, @Param("wallet") String wallet);

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

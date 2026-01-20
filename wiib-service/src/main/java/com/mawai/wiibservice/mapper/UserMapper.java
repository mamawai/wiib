package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 原子更新可用余额，返回影响行数（0表示余额不足） */
    @Update("UPDATE \"user\" SET balance = balance + #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} AND balance + #{amount} >= 0")
    int atomicUpdateBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 原子冻结余额：可用减少，冻结增加 */
    @Update("UPDATE \"user\" SET balance = balance - #{amount}, frozen_balance = frozen_balance + #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} AND balance >= #{amount}")
    int atomicFreezeBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 原子解冻余额：冻结减少，可用增加 */
    @Update("UPDATE \"user\" SET balance = balance + #{amount}, frozen_balance = frozen_balance - #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} AND frozen_balance >= #{amount}")
    int atomicUnfreezeBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 原子扣除冻结余额 */
    @Update("UPDATE \"user\" SET frozen_balance = frozen_balance - #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} AND frozen_balance >= #{amount}")
    int atomicDeductFrozenBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
}

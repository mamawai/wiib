package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户实体
 * 使用数据库原子操作保证并发安全
 */
@Data
@TableName("\"user\"")
public class User {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** LinuxDo用户ID，OAuth登录标识 */
    private String linuxDoId;

    /** 用户名 */
    private String username;

    /** 头像URL */
    private String avatar;

    /** 可用余额 */
    private BigDecimal balance;

    /** 冻结余额（限价买单冻结的资金） */
    private BigDecimal frozenBalance;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 获取总资金（可用 + 冻结）
     */
    public BigDecimal getTotalBalance() {
        BigDecimal frozen = frozenBalance != null ? frozenBalance : BigDecimal.ZERO;
        return balance.add(frozen);
    }
}

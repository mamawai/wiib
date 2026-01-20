package com.mawai.wiibcommon.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 用户DTO
 */
@Data
public class UserDTO {

    /** 用户ID */
    private Long id;

    /** 用户名 */
    private String username;

    /** 头像 */
    private String avatar;

    /** 可用余额 */
    private BigDecimal balance;

    /** 总资产 */
    private BigDecimal totalAssets;

    /** 盈亏 */
    private BigDecimal profit;

    /** 盈亏率 */
    private BigDecimal profitPct;
}

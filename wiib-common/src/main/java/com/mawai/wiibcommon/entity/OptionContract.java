package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("option_contract")
public class OptionContract {

    @TableId(type = IdType.AUTO)
    // 期权合约ID
    private Long id;

    // 标的股票ID
    private Long stockId;

    /** CALL/PUT */
    private String optionType;

    // 行权价（K）
    private BigDecimal strike;

    // 到期时间
    private LocalDateTime expireAt;

    /** 生成时参考价（昨收） */
    private BigDecimal refPrice;

    /** 年化波动率 */
    private BigDecimal sigma;

    /** ACTIVE/SETTLED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    // 创建时间
    private LocalDateTime createdAt;
}

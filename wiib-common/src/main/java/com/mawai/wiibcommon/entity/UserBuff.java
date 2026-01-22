package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("user_buff")
public class UserBuff {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String buffType;

    private String buffName;

    private String rarity;

    private String extraData;

    private LocalDate drawDate;

    private LocalDateTime expireAt;

    private Boolean isUsed;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

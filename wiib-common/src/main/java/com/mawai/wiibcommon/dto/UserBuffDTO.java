package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserBuffDTO {

    private Long id;

    private String buffType;

    private String buffName;

    private String rarity;

    private String extraData;

    private LocalDateTime expireAt;

    private Boolean isUsed;
}

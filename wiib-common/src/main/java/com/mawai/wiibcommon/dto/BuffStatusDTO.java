package com.mawai.wiibcommon.dto;

import lombok.Data;

@Data
public class BuffStatusDTO {

    private boolean canDraw;

    private UserBuffDTO todayBuff;
}

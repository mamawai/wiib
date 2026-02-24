package com.mawai.wiibcommon.dto;

import lombok.Data;

@Data
public class CardJoinRequest {
    private String uuid;
    private String nickname;
    private String roomCode;
}

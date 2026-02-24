package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.util.List;

@Data
public class CardRoomDTO {
    private String roomCode;
    private String host;
    private String status;
    private List<SeatDTO> seats;
    private String hunA;
    private String hunB;
    private String hunTeam;

    @Data
    public static class SeatDTO {
        private int seat;
        private String uuid;
        private String nickname;
        private boolean ready;
        private String team;
    }
}

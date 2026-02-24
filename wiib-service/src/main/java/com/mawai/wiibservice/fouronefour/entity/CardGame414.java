package com.mawai.wiibservice.fouronefour.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("card_game_414")
public class CardGame414 {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String roomCode;
    private Integer roundNo;
    private String seat1Uuid;
    private String seat1Nick;
    private String seat2Uuid;
    private String seat2Nick;
    private String seat3Uuid;
    private String seat3Nick;
    private String seat4Uuid;
    private String seat4Nick;
    private String teamA;
    private String teamB;
    private String hunTeam;
    private String hunRank;
    private String finishOrder;
    private Integer dagongSeat;
    private Integer caught;
    private String hunAAfter;
    private String hunBAfter;
    private Boolean isFinal;
    private String winnerTeam;
    private LocalDateTime createdAt;
}

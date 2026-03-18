package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.util.List;

@Data
public class VideoPokerDrawRequest {
    private List<Integer> held;
}

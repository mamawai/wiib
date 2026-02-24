package com.mawai.wiibservice.fouronefour.controller;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.fouronefour.service.Card414GameService;
import com.mawai.wiibservice.fouronefour.service.Card414RoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class Card414WsController {

    private final Card414RoomService roomService;
    private final Card414GameService gameService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/414/ready")
    public void ready(Map<String, String> msg) {
        String roomCode = msg.get("roomCode");
        String uuid = msg.get("uuid");
        try {
            roomService.toggleReady(uuid, roomCode);
        } catch (BizException e) {
            sendError(roomCode, uuid, e.getMessage());
        }
    }

    @MessageMapping("/414/start")
    public void start(Map<String, String> msg) {
        String roomCode = msg.get("roomCode");
        String uuid = msg.get("uuid");
        try {
            gameService.startGame(roomCode, uuid);
        } catch (BizException e) {
            sendError(roomCode, uuid, e.getMessage());
        }
    }

    @MessageMapping("/414/play")
    public void play(Map<String, Object> msg) {
        String roomCode = (String) msg.get("roomCode");
        String uuid = (String) msg.get("uuid");
        try {
            @SuppressWarnings("unchecked")
            List<String> cards = (List<String>) msg.get("cards");
            gameService.playCards(roomCode, uuid, cards);
        } catch (BizException e) {
            sendError(roomCode, uuid, e.getMessage());
        }
    }

    @MessageMapping("/414/pass")
    public void pass(Map<String, String> msg) {
        String roomCode = msg.get("roomCode");
        String uuid = msg.get("uuid");
        try {
            gameService.pass(roomCode, uuid);
        } catch (BizException e) {
            sendError(roomCode, uuid, e.getMessage());
        }
    }

    @MessageMapping("/414/cha")
    public void cha(Map<String, String> msg) {
        String roomCode = msg.get("roomCode");
        String uuid = msg.get("uuid");
        try {
            gameService.cha(roomCode, uuid);
        } catch (BizException e) {
            sendError(roomCode, uuid, e.getMessage());
        }
    }

    @MessageMapping("/414/gou")
    public void gou(Map<String, String> msg) {
        String roomCode = msg.get("roomCode");
        String uuid = msg.get("uuid");
        try {
            gameService.gou(roomCode, uuid);
        } catch (BizException e) {
            sendError(roomCode, uuid, e.getMessage());
        }
    }

    @MessageMapping("/414/swap-seat")
    public void swapSeat(Map<String, Object> msg) {
        String roomCode = (String) msg.get("roomCode");
        String uuid = (String) msg.get("uuid");
        try {
            roomService.swapSeat(uuid, roomCode, ((Number) msg.get("targetSeat")).intValue());
        } catch (BizException e) {
            sendError(roomCode, uuid, e.getMessage());
        }
    }

    private void sendError(String roomCode, String uuid, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "ERROR");
        envelope.put("data", Map.of("msg", message, "uuid", uuid));
        envelope.put("timestamp", System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/414/game/" + roomCode, envelope);
    }
}

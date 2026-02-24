package com.mawai.wiibservice.fouronefour.controller;

import com.mawai.wiibcommon.dto.CardCreateRequest;
import com.mawai.wiibcommon.dto.CardJoinRequest;
import com.mawai.wiibcommon.dto.CardRoomDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.fouronefour.service.Card414GameService;
import com.mawai.wiibservice.fouronefour.service.Card414RoomService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "414扑克接口")
@RestController
@RequestMapping("/api/414")
@RequiredArgsConstructor
public class Card414Controller {

    private final Card414RoomService roomService;
    private final Card414GameService gameService;

    @PostMapping("/room/create")
    public Result<CardRoomDTO> createRoom(@RequestBody CardCreateRequest request) {
        return Result.ok(roomService.createRoom(request.getUuid(), request.getNickname()));
    }

    @PostMapping("/room/join")
    public Result<CardRoomDTO> joinRoom(@RequestBody CardJoinRequest request) {
        return Result.ok(roomService.joinRoom(request.getUuid(), request.getNickname(), request.getRoomCode()));
    }

    @GetMapping("/room/{code}")
    public Result<CardRoomDTO> getRoom(@PathVariable String code) {
        return Result.ok(roomService.getRoom(code));
    }

    @GetMapping("/rooms")
    public Result<List<CardRoomDTO>> listRooms() {
        return Result.ok(roomService.listRooms());
    }

    @PostMapping("/room/leave")
    public Result<Void> leaveRoom(@RequestBody Map<String, String> body) {
        roomService.leaveRoom(body.get("uuid"), body.get("roomCode"));
        return Result.ok(null);
    }

    @PostMapping("/force-quit")
    public Result<Void> forceQuit(@RequestBody Map<String, String> body) {
        roomService.forceQuit(body.get("uuid"), body.get("roomCode"));
        return Result.ok(null);
    }

    @GetMapping("/hand")
    public Result<List<String>> getHand(@RequestParam String roomCode, @RequestParam String uuid) {
        return Result.ok(gameService.getHand(roomCode, uuid));
    }

    @GetMapping("/game/{code}")
    public Result<Map<String, Object>> getGameState(@PathVariable String code, @RequestParam String uuid) {
        return Result.ok(gameService.getGameState(code, uuid));
    }
}

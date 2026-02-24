package com.mawai.wiibservice.fouronefour.service.impl;

import com.mawai.wiibcommon.dto.CardRoomDTO;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.fouronefour.service.Card414RoomService;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.util.RedisLockUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class Card414RoomServiceImpl implements Card414RoomService {

    private final CacheService cacheService;
    private final RedisLockUtil redisLockUtil;
    private final SimpMessagingTemplate messagingTemplate;

    private static final String ROOM_PREFIX = "414:room:";
    private static final String ROOMS_KEY = "414:rooms";
    private static final String PLAYER_PREFIX = "414:player:";
    private static final long ROOM_TTL_HOURS = 2;
    private static final long LOCK_TIMEOUT = 10;
    private static final long LOCK_WAIT = 3000;
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Data
    public static class RoomState implements Serializable {
        private String roomCode;
        private String host;
        private String status; // WAITING / PLAYING / FINISHED
        private PlayerInfo[] seats = new PlayerInfo[4];
        private long createdAt;
    }

    @Data
    public static class PlayerInfo implements Serializable {
        private String uuid;
        private String nickname;
        private boolean ready;
    }

    /**
     * {@inheritDoc}
     * 创建者坐0号位，房间存入Redis并加入公开列表，同时记录玩家-房间映射
     */
    @Override
    public CardRoomDTO createRoom(String uuid, String nickname) {
        validateNickname(nickname);
        String existing = cacheService.get(PLAYER_PREFIX + uuid);
        if (existing != null) {
            throw new BizException(ErrorCode.CARD_ALREADY_IN_ROOM);
        }

        String code = generateRoomCode();
        RoomState room = new RoomState();
        room.setRoomCode(code);
        room.setHost(uuid);
        room.setStatus("WAITING");
        room.setCreatedAt(System.currentTimeMillis());

        PlayerInfo p = new PlayerInfo();
        p.setUuid(uuid);
        p.setNickname(nickname);
        room.getSeats()[0] = p;

        cacheService.setObject(ROOM_PREFIX + code, room, ROOM_TTL_HOURS, TimeUnit.HOURS);
        cacheService.sAdd(ROOMS_KEY, code);
        cacheService.set(PLAYER_PREFIX + uuid, code, ROOM_TTL_HOURS, TimeUnit.HOURS);

        log.info("房间创建: code={}, host={}", code, nickname);
        return toDTO(room);
    }

    /**
     * {@inheritDoc}
     * 已在该房间则直接返回；加分布式锁防并发，自动分配第一个空座位
     */
    @Override
    public CardRoomDTO joinRoom(String uuid, String nickname, String roomCode) {
        validateNickname(nickname);
        String existing = cacheService.get(PLAYER_PREFIX + uuid);
        if (existing != null) {
            if (existing.equals(roomCode)) {
                return getRoom(roomCode);
            }
            throw new BizException(ErrorCode.CARD_ALREADY_IN_ROOM);
        }

        return withRoomLock(roomCode, () -> {
            RoomState room = loadRoom(roomCode);
            if (!"WAITING".equals(room.getStatus())) {
                throw new BizException(ErrorCode.CARD_GAME_IN_PROGRESS);
            }

            int emptySeat = -1;
            for (int i = 0; i < 4; i++) {
                if (room.getSeats()[i] == null) {
                    emptySeat = i;
                    break;
                }
            }
            if (emptySeat == -1) {
                throw new BizException(ErrorCode.CARD_ROOM_FULL);
            }

            PlayerInfo p = new PlayerInfo();
            p.setUuid(uuid);
            p.setNickname(nickname);
            room.getSeats()[emptySeat] = p;

            saveRoom(room);
            cacheService.set(PLAYER_PREFIX + uuid, roomCode, ROOM_TTL_HOURS, TimeUnit.HOURS);

            log.info("玩家加入房间: room={}, seat={}, player={}", roomCode, emptySeat, nickname);
            broadcastRoom(roomCode, "PLAYER_JOIN", Map.of("seat", emptySeat, "nickname", nickname));
            return toDTO(room);
        });
    }

    /** {@inheritDoc} */
    @Override
    public CardRoomDTO getRoom(String roomCode) {
        RoomState room = loadRoom(roomCode);
        return toDTO(room);
    }

    /**
     * {@inheritDoc}
     * 遍历Redis Set中的房间码，过滤WAITING状态，自动清理已过期的无效条目
     */
    @Override
    public List<CardRoomDTO> listRooms() {
        Set<String> codes = cacheService.sMembers(ROOMS_KEY);
        if (codes == null || codes.isEmpty()) return List.of();

        List<CardRoomDTO> list = new ArrayList<>();
        for (String code : codes) {
            try {
                RoomState room = cacheService.getObject(ROOM_PREFIX + code);
                if (room == null) {
                    cacheService.sRemove(ROOMS_KEY, code);
                    continue;
                }
                if ("WAITING".equals(room.getStatus())) {
                    list.add(toDTO(room));
                }
            } catch (Exception e) {
                cacheService.sRemove(ROOMS_KEY, code);
            }
        }
        return list;
    }

    /**
     * {@inheritDoc}
     * 游戏中禁止离开；房主离开后自动转移给下一个玩家；所有人离开则销毁房间
     */
    @Override
    public void leaveRoom(String uuid, String roomCode) {
        withRoomLock(roomCode, () -> {
            RoomState room = loadRoom(roomCode);
            int seat = findSeat(room, uuid);
            if (seat == -1) throw new BizException(ErrorCode.CARD_PLAYER_NOT_IN_ROOM);

            if ("PLAYING".equals(room.getStatus())) {
                throw new BizException(ErrorCode.CARD_GAME_IN_PROGRESS);
            }

            room.getSeats()[seat] = null;
            cacheService.delete(PLAYER_PREFIX + uuid);

            int remaining = countPlayers(room);
            if (remaining == 0) {
                cacheService.delete(ROOM_PREFIX + roomCode);
                cacheService.sRemove(ROOMS_KEY, roomCode);
                log.info("房间销毁: code={}", roomCode);
                return null;
            }

            if (uuid.equals(room.getHost())) {
                for (PlayerInfo p : room.getSeats()) {
                    if (p != null) {
                        room.setHost(p.getUuid());
                        break;
                    }
                }
            }

            // 离开时清除ready
            for (PlayerInfo p : room.getSeats()) {
                if (p != null) p.setReady(false);
            }

            saveRoom(room);
            broadcastRoom(roomCode, "PLAYER_LEAVE", Map.of("seat", seat));
            return null;
        });
    }

    /** {@inheritDoc} */
    @Override
    public void toggleReady(String uuid, String roomCode) {
        withRoomLock(roomCode, () -> {
            RoomState room = loadRoom(roomCode);
            int seat = findSeat(room, uuid);
            if (seat == -1) throw new BizException(ErrorCode.CARD_PLAYER_NOT_IN_ROOM);
            if (!"WAITING".equals(room.getStatus())) throw new BizException(ErrorCode.CARD_GAME_IN_PROGRESS);

            PlayerInfo p = room.getSeats()[seat];
            p.setReady(!p.isReady());
            saveRoom(room);

            broadcastRoom(roomCode, "READY", Map.of("seat", seat, "ready", p.isReady()));
            return null;
        });
    }

    /**
     * {@inheritDoc}
     * 目标位有人则互换座位，空位则直接移过去；换座后全员ready重置
     */
    @Override
    public void swapSeat(String uuid, String roomCode, int targetSeat) {
        if (targetSeat < 0 || targetSeat > 3) throw new BizException(ErrorCode.PARAM_ERROR);

        withRoomLock(roomCode, () -> {
            RoomState room = loadRoom(roomCode);
            if (!"WAITING".equals(room.getStatus())) throw new BizException(ErrorCode.CARD_GAME_IN_PROGRESS);

            int fromSeat = findSeat(room, uuid);
            if (fromSeat == -1) throw new BizException(ErrorCode.CARD_PLAYER_NOT_IN_ROOM);
            if (fromSeat == targetSeat) return null;

            PlayerInfo temp = room.getSeats()[targetSeat];
            room.getSeats()[targetSeat] = room.getSeats()[fromSeat];
            room.getSeats()[fromSeat] = temp;

            // 换座后清除所有人ready
            for (PlayerInfo p : room.getSeats()) {
                if (p != null) p.setReady(false);
            }

            saveRoom(room);
            broadcastRoom(roomCode, "SEAT_SWAP", Map.of("from", fromSeat, "to", targetSeat));
            return null;
        });
    }

    /** {@inheritDoc} */
    @Override
    public int findSeatByUuid(String roomCode, String uuid) {
        RoomState room = loadRoom(roomCode);
        return findSeat(room, uuid);
    }

    /**
     * {@inheritDoc}
     * 删除游戏会话、房间状态、所有玩家映射，广播GAME_OVER
     */
    @Override
    public void forceQuit(String uuid, String roomCode) {
        withRoomLock(roomCode, () -> {
            RoomState room = loadRoom(roomCode);
            int seat = findSeat(room, uuid);
            if (seat == -1) throw new BizException(ErrorCode.CARD_PLAYER_NOT_IN_ROOM);

            // 清理游戏会话
            cacheService.delete("414:game:" + roomCode);

            // 清理所有玩家映射
            for (PlayerInfo p : room.getSeats()) {
                if (p != null) cacheService.delete(PLAYER_PREFIX + p.getUuid());
            }

            // 销毁房间
            cacheService.delete(ROOM_PREFIX + roomCode);
            cacheService.sRemove(ROOMS_KEY, roomCode);

            broadcastGame(roomCode, "GAME_OVER", Map.of("reason", "player_quit", "seat", seat));
            log.info("强制退出销毁牌局: room={}, seat={}", roomCode, seat);
            return null;
        });
    }

    /** {@inheritDoc} */
    @Override
    public void destroyRoom(String roomCode) {
        RoomState room = cacheService.getObject(ROOM_PREFIX + roomCode);
        if (room == null) return;
        for (PlayerInfo p : room.getSeats()) {
            if (p != null) cacheService.delete(PLAYER_PREFIX + p.getUuid());
        }
        cacheService.delete(ROOM_PREFIX + roomCode);
        cacheService.sRemove(ROOMS_KEY, roomCode);
    }

    // ===== 内部方法 =====

    /**
     * 从Redis加载房间状态，不存在则抛异常
     *
     * @param roomCode 房间号
     * @return 房间状态
     */
    RoomState loadRoom(String roomCode) {
        RoomState room = cacheService.getObject(ROOM_PREFIX + roomCode);
        if (room == null) throw new BizException(ErrorCode.CARD_ROOM_NOT_FOUND);
        return room;
    }

    /**
     * 保存房间状态到Redis，刷新TTL
     *
     * @param room 房间状态
     */
    void saveRoom(RoomState room) {
        cacheService.setObject(ROOM_PREFIX + room.getRoomCode(), room, ROOM_TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * 查找玩家在房间中的座位索引
     *
     * @param room 房间状态
     * @param uuid 玩家标识
     * @return 座位号(0-3)，不存在返回-1
     */
    private int findSeat(RoomState room, String uuid) {
        for (int i = 0; i < 4; i++) {
            if (room.getSeats()[i] != null && uuid.equals(room.getSeats()[i].getUuid())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 统计房间内玩家数
     *
     * @param room 房间状态
     * @return 在座玩家数
     */
    private int countPlayers(RoomState room) {
        int count = 0;
        for (PlayerInfo p : room.getSeats()) {
            if (p != null) count++;
        }
        return count;
    }

    /**
     * 校验昵称非空且长度不超过16
     *
     * @param nickname 昵称
     */
    private void validateNickname(String nickname) {
        if (nickname == null || nickname.isBlank() || nickname.length() > 16) {
            throw new BizException(ErrorCode.CARD_NICKNAME_REQUIRED);
        }
    }

    /**
     * 生成6位随机房间码(大写字母+数字，去除易混淆字符I/O/0/1)
     *
     * @return 房间码
     */
    private String generateRoomCode() {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(CHARS.charAt(rng.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * 向房间频道广播消息
     *
     * @param roomCode 房间号
     * @param type     消息类型
     * @param data     消息数据
     */
    void broadcastRoom(String roomCode, String type, Object data) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        msg.put("data", data);
        msg.put("timestamp", System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/414/room/" + roomCode, msg);
    }

    /**
     * 向游戏频道广播消息
     *
     * @param roomCode 房间号
     * @param type     消息类型
     * @param data     消息数据
     */
    void broadcastGame(String roomCode, String type, Object data) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        msg.put("data", data);
        msg.put("timestamp", System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/414/game/" + roomCode, msg);
    }

    /**
     * 在分布式锁保护下执行房间操作
     *
     * @param roomCode 房间号
     * @param supplier 业务逻辑
     * @param <T>      返回类型
     * @return supplier执行结果
     */
    private <T> T withRoomLock(String roomCode, Supplier<T> supplier) {
        try {
            return redisLockUtil.executeWithLock(
                    "414:lock:room:" + roomCode, LOCK_TIMEOUT, LOCK_WAIT, supplier
            );
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("获取锁失败")) {
                throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
            }
            throw ex;
        }
    }

    /**
     * RoomState转DTO，自动填充座位队伍(0/2=A, 1/3=B)
     *
     * @param room 房间状态
     * @return 房间DTO
     */
    private CardRoomDTO toDTO(RoomState room) {
        CardRoomDTO dto = new CardRoomDTO();
        dto.setRoomCode(room.getRoomCode());
        dto.setHost(room.getHost());
        dto.setStatus(room.getStatus());

        List<CardRoomDTO.SeatDTO> seats = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            CardRoomDTO.SeatDTO s = new CardRoomDTO.SeatDTO();
            s.setSeat(i);
            s.setTeam((i == 0 || i == 2) ? "A" : "B");
            if (room.getSeats()[i] != null) {
                s.setUuid(room.getSeats()[i].getUuid());
                s.setNickname(room.getSeats()[i].getNickname());
                s.setReady(room.getSeats()[i].isReady());
            }
            seats.add(s);
        }
        dto.setSeats(seats);
        return dto;
    }
}

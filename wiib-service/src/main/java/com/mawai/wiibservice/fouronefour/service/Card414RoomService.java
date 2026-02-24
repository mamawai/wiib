package com.mawai.wiibservice.fouronefour.service;

import com.mawai.wiibcommon.dto.CardRoomDTO;

import java.util.List;

public interface Card414RoomService {

    /**
     * 创建房间，创建者自动坐0号位并成为房主
     *
     * @param uuid     玩家唯一标识
     * @param nickname 玩家昵称
     * @return 房间信息
     */
    CardRoomDTO createRoom(String uuid, String nickname);

    /**
     * 加入指定房间，自动分配空座位；若已在该房间则直接返回房间信息
     *
     * @param uuid     玩家唯一标识
     * @param nickname 玩家昵称
     * @param roomCode 房间号
     * @return 房间信息
     */
    CardRoomDTO joinRoom(String uuid, String nickname, String roomCode);

    /**
     * 获取房间信息
     *
     * @param roomCode 房间号
     * @return 房间信息
     */
    CardRoomDTO getRoom(String roomCode);

    /**
     * 列出所有等待中的公开房间
     *
     * @return 房间列表，已过期的房间会被自动清理
     */
    List<CardRoomDTO> listRooms();

    /**
     * 离开房间，游戏中不允许离开；最后一人离开时销毁房间
     *
     * @param uuid     玩家唯一标识
     * @param roomCode 房间号
     */
    void leaveRoom(String uuid, String roomCode);

    /**
     * 切换准备状态
     *
     * @param uuid     玩家唯一标识
     * @param roomCode 房间号
     */
    void toggleReady(String uuid, String roomCode);

    /**
     * 换座位，目标位有人则互换，换座后所有人ready重置
     *
     * @param uuid       玩家唯一标识
     * @param roomCode   房间号
     * @param targetSeat 目标座位号(0-3)
     */
    void swapSeat(String uuid, String roomCode, int targetSeat);

    /**
     * 强制退出，销毁牌局和房间，清理所有玩家映射
     *
     * @param uuid     操作者标识
     * @param roomCode 房间号
     */
    void forceQuit(String uuid, String roomCode);

    /**
     * 查找玩家在房间中的座位号
     *
     * @param roomCode 房间号
     * @param uuid     玩家唯一标识
     * @return 座位号(0-3)，不在房间返回-1
     */
    int findSeatByUuid(String roomCode, String uuid);

    /**
     * 销毁房间，清理房间/玩家映射/房间列表（不删游戏会话、不广播）
     *
     * @param roomCode 房间号
     */
    void destroyRoom(String roomCode);
}

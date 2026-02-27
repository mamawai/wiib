package com.mawai.wiibservice.fouronefour.service;

import java.util.List;
import java.util.Map;

public interface Card414GameService {

    /**
     * 开始游戏，仅房主可调用，需4人全部准备
     *
     * @param roomCode 房间号
     * @param uuid     操作者(房主)标识
     */
    void startGame(String roomCode, String uuid);

    /**
     * 出牌，需轮到该玩家且牌型合法能压过上家
     *
     * @param roomCode 房间号
     * @param uuid     玩家标识
     * @param cards    要出的牌列表，如["H3","H3"]
     */
    void playCards(String roomCode, String uuid, List<String> cards);

    /**
     * 不要(过牌)，自由出牌时不允许pass
     *
     * @param roomCode 房间号
     * @param uuid     玩家标识
     */
    void pass(String roomCode, String uuid);

    /**
     * 叉：用对子截断单张，需在CHA_WAIT状态下操作
     *
     * @param roomCode 房间号
     * @param uuid     叉者标识
     */
    void cha(String roomCode, String uuid);

    /**
     * 勾：用第4张同面值牌截断叉，需在GOU_WAIT状态下操作
     *
     * @param roomCode 房间号
     * @param uuid     勾者标识
     */
    void gou(String roomCode, String uuid);

    /**
     * 放弃叉
     *
     * @param roomCode 房间号
     * @param uuid     玩家标识
     */
    void passCha(String roomCode, String uuid);

    /**
     * 放弃勾
     *
     * @param roomCode 房间号
     * @param uuid     玩家标识
     */
    void passGou(String roomCode, String uuid);

    /**
     * 获取玩家当前手牌
     *
     * @param roomCode 房间号
     * @param uuid     玩家标识
     * @return 手牌列表，无游戏进行时返回空列表
     */
    List<String> getHand(String roomCode, String uuid);

    /**
     * 获取游戏公开状态(含请求者手牌)
     *
     * @param roomCode 房间号
     * @param uuid     请求者标识
     * @return 游戏状态Map，含round/hun/turn/hand等字段
     */
    Map<String, Object> getGameState(String roomCode, String uuid);
}

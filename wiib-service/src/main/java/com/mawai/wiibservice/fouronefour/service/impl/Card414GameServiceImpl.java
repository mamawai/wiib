package com.mawai.wiibservice.fouronefour.service.impl;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.fouronefour.Card414Engine;
import com.mawai.wiibservice.fouronefour.mapper.CardGame414Mapper;
import com.mawai.wiibservice.fouronefour.entity.CardGame414;
import com.mawai.wiibservice.fouronefour.service.Card414GameService;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.util.RedisLockUtil;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class Card414GameServiceImpl implements Card414GameService {

    private final CacheService cacheService;
    private final RedisLockUtil redisLockUtil;
    private final CardGame414Mapper gameMapper;
    private final Card414RoomServiceImpl roomService;

    private static final String GAME_PREFIX = "414:game:";
    private static final long GAME_TTL_HOURS = 2;
    private static final long LOCK_TIMEOUT = 10;
    private static final long LOCK_WAIT = 3000;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("414-timer-", 0).factory());

    /** 游戏会话，一局游戏的完整状态，序列化存Redis */
    @Data
    public static class GameSession implements Serializable {
        private int round = 1;
        private int dagong = -1;       // 上轮大贡座位，-1=首轮
        private int dealStart = 0;     // 发牌起始座位
        private String hunTeam = "A";
        private String hunA = "3";
        private String hunB = "3";
        private List<List<String>> hands; // 4个玩家手牌
        private int turn;              // 当前出牌座位
        private PlayInfo lastPlay;     // 上次出牌
        private int lastPlaySeat = -1; // 上次实际出牌玩家
        private int passCount;
        private List<Integer> finishOrder = new ArrayList<>();
        private String state = "DEAL"; // DEAL/PLAY/CHA_WAIT/GOU_WAIT/ROUND_OVER
        private String chaRank;        // 叉目标面值
        private int chaSingleSeat = -1;// 出单张的座位
        private int chaSeat = -1;      // 叉者座位
        private int lightSeat = -1;    // 获得光的座位
        private List<Integer> chaWaiters = new ArrayList<>();
        private List<Integer> gouWaiters = new ArrayList<>();
    }

    /** 一次出牌记录 */
    @Data
    @NoArgsConstructor
    public static class PlayInfo implements Serializable {
        private int seat;
        private List<String> cards;
        private String type;

        public PlayInfo(int seat, List<String> cards, String type) {
            this.seat = seat;
            this.cards = cards;
            this.type = type;
        }
    }

    /**
     * {@inheritDoc}
     * 续局时继承上轮混/大贡状态；洗牌发牌后确定先手并广播GAME_START
     */
    // ===== startGame =====
    @Override
    public void startGame(String roomCode, String uuid) {
        withGameLock(roomCode, () -> {
            Card414RoomServiceImpl.RoomState room = roomService.loadRoom(roomCode);
            if (!uuid.equals(room.getHost())) {
                throw new BizException(ErrorCode.CARD_NOT_HOST);
            }
            if (!"WAITING".equals(room.getStatus())) {
                throw new BizException(ErrorCode.CARD_GAME_IN_PROGRESS);
            }

            // 检查4人全在且全准备
            for (int i = 0; i < 4; i++) {
                if (room.getSeats()[i] == null) throw new BizException(ErrorCode.CARD_NOT_ALL_READY);
                if (!room.getSeats()[i].isReady()) throw new BizException(ErrorCode.CARD_NOT_ALL_READY);
            }

            // 检查是否有上轮游戏状态（续局）
            GameSession prev = loadSession(roomCode);
            GameSession session = new GameSession();

            if (prev != null && "ROUND_OVER".equals(prev.getState())) {
                session.setRound(prev.getRound() + 1);
                session.setDagong(prev.getDagong());
                session.setHunA(prev.getHunA());
                session.setHunB(prev.getHunB());
                session.setHunTeam(prev.getHunTeam());
                session.setDealStart(prev.getDagong());
            } else {
                session.setDealStart(0); // 首轮从房主开始
            }

            // 洗牌发牌
            List<String> deck = Card414Engine.fullDeck();
            Card414Engine.shuffle(deck);
            List<List<String>> hands = Card414Engine.deal(deck, session.getDealStart());

            // 排序手牌
            String hunRank = "A".equals(session.getHunTeam())
                    ? session.getHunA() : session.getHunB();
            for (List<String> hand : hands) {
                Card414Engine.sortHand(hand, hunRank);
            }
            session.setHands(hands);

            // 确定先手
            if (session.getDagong() >= 0) {
                session.setTurn(session.getDagong());
            } else {
                // 首轮: 红心3持有者先出
                for (int i = 0; i < 4; i++) {
                    if (Card414Engine.hasRedHeart3(hands.get(i))) {
                        session.setTurn(i);
                        break;
                    }
                }
            }

            session.setState("PLAY");
            session.setFinishOrder(new ArrayList<>());
            saveSession(roomCode, session);

            // 更新房间状态
            room.setStatus("PLAYING");
            roomService.saveRoom(room);

            // 广播游戏开始（不含手牌）
            int[] handCounts = new int[4];
            for (int i = 0; i < 4; i++) handCounts[i] = hands.get(i).size();

            Map<String, Object> startData = new LinkedHashMap<>();
            startData.put("round", session.getRound());
            startData.put("hunTeam", session.getHunTeam());
            startData.put("hunA", session.getHunA());
            startData.put("hunB", session.getHunB());
            startData.put("hunRank", hunRank);
            startData.put("turn", session.getTurn());
            startData.put("handCounts", handCounts);

            roomService.broadcastGame(roomCode, "GAME_START", startData);

            log.info("游戏开始: room={}, round={}, hun={}/{}, turn={}",
                    roomCode, session.getRound(), session.getHunA(), session.getHunB(), session.getTurn());
            return null;
        });
    }

    /**
     * {@inheritDoc}
     * 校验轮次→校验手牌→识别牌型→压牌判定→移除手牌→广播→检查出完/光/叉窗口→流转
     */
    // ===== playCards =====
    @Override
    public void playCards(String roomCode, String uuid, List<String> cards) {
        withGameLock(roomCode, () -> {
            GameSession session = loadSessionRequired(roomCode);
            int seat = roomService.findSeatByUuid(roomCode, uuid);
            if (seat == -1) throw new BizException(ErrorCode.CARD_PLAYER_NOT_IN_ROOM);
            if (session.getTurn() != seat) throw new BizException(ErrorCode.CARD_NOT_YOUR_TURN);
            if (!"PLAY".equals(session.getState())) throw new BizException(ErrorCode.CARD_INVALID_STATE);

            List<String> hand = session.getHands().get(seat);
            // 检查牌是否在手中
            List<String> handCopy = new ArrayList<>(hand);
            for (String c : cards) {
                if (!handCopy.remove(c)) throw new BizException(ErrorCode.CARD_INVALID_PLAY);
            }

            // 识别牌型
            Card414Engine.PlayResult play = Card414Engine.identify(cards);
            if (play == null) throw new BizException(ErrorCode.CARD_INVALID_PLAY);

            // 如果有上次出牌（不是自由出牌），需要压过
            String hunRank = currentHunRank(session);
            if (session.getLastPlay() != null && session.getLightSeat() != seat) {
                Card414Engine.PlayResult lastPlay = Card414Engine.identify(session.getLastPlay().getCards());
                if (lastPlay != null && !Card414Engine.canBeat(play, lastPlay, hunRank)) {
                    throw new BizException(ErrorCode.CARD_CANNOT_BEAT);
                }
            }

            // 出牌：从手牌移除
            for (String c : cards) hand.remove(c);
            session.setLastPlay(new PlayInfo(seat, new ArrayList<>(cards), play.getType().name()));
            session.setLastPlaySeat(seat);
            session.setPassCount(0);
            session.setLightSeat(-1);

            // 检查是否出完
            boolean finished = hand.isEmpty();
            if (finished) {
                session.getFinishOrder().add(seat);
                log.info("玩家出完: room={}, seat={}, order={}", roomCode, seat, session.getFinishOrder());
            }

            // 广播出牌
            int[] handCounts = getHandCounts(session);
            Map<String, Object> playData = new LinkedHashMap<>();
            playData.put("seat", seat);
            playData.put("cards", cards);
            playData.put("type", play.getType().name());
            playData.put("handCounts", handCounts);
            playData.put("finished", finished);
            roomService.broadcastGame(roomCode, "PLAY", playData);

            // 检查本轮是否结束：同队两人都出完 或 仅剩1人
            if (checkFinished(roomCode, session, seat, finished)) return null;

            // 单张触发叉窗口
            if (play.getType() == Card414Engine.PlayType.SINGLE) {
                String playedRank = Card414Engine.cardRank(cards.getFirst());
                List<Integer> waiters = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    if (i == seat || session.getFinishOrder().contains(i)) continue;
                    if (Card414Engine.canCha(playedRank, session.getHands().get(i))) {
                        waiters.add(i);
                    }
                }
                if (!waiters.isEmpty()) {
                    session.setState("CHA_WAIT");
                    session.setChaRank(playedRank);
                    session.setChaSingleSeat(seat);
                    session.setChaWaiters(waiters);
                    saveSession(roomCode, session);
                    roomService.broadcastGame(roomCode, "CHA_WAIT",
                            Map.of("rank", playedRank, "waiters", waiters));
                    return null;
                }
            }

            // 正常流转
            advanceTurn(roomCode, session);
            saveSession(roomCode, session);
            return null;
        });
    }

    /**
     * {@inheritDoc}
     * 累计pass次数，activePlayers-1家连续pass后触发自由出牌或光
     */
    // ===== pass =====
    @Override
    public void pass(String roomCode, String uuid) {
        withGameLock(roomCode, () -> {
            GameSession session = loadSessionRequired(roomCode);
            int seat = roomService.findSeatByUuid(roomCode, uuid);
            if (seat == -1) throw new BizException(ErrorCode.CARD_PLAYER_NOT_IN_ROOM);
            if (session.getTurn() != seat) throw new BizException(ErrorCode.CARD_NOT_YOUR_TURN);
            if (!"PLAY".equals(session.getState())) throw new BizException(ErrorCode.CARD_INVALID_STATE);

            // 自由出牌（lastPlay==null或光）时不能pass
            if (session.getLastPlay() == null || session.getLightSeat() == seat) {
                throw new BizException(ErrorCode.CARD_INVALID_PLAY);
            }

            session.setPassCount(session.getPassCount() + 1);

            roomService.broadcastGame(roomCode, "PASS", Map.of("seat", seat));

            // 计算活跃玩家数（未出完的）
            int activePlayers = countActivePlayers(session);

            // 出牌者已出完→所有活跃玩家都要pass；未出完→除出牌者外都要pass
            boolean lastFinished = session.getFinishOrder().contains(session.getLastPlaySeat());
            int threshold = lastFinished ? activePlayers : activePlayers - 1;

            if (session.getPassCount() >= threshold) {
                int lastSeat = session.getLastPlaySeat();
                // 如果上次出牌者已出完 → 检查光（队友获自由出牌权）
                if (session.getFinishOrder().contains(lastSeat)) {
                    int partnerSeat = Card414Engine.partnerSeat(lastSeat);
                    if (!session.getFinishOrder().contains(partnerSeat)) {
                        session.setLightSeat(partnerSeat);
                        session.setTurn(partnerSeat);
                        session.setLastPlay(null);
                        session.setPassCount(0);
                        saveSession(roomCode, session);
                        roomService.broadcastGame(roomCode, "LIGHT",
                                Map.of("seat", partnerSeat, "from", lastSeat));
                        roomService.broadcastGame(roomCode, "TURN",
                                Map.of("seat", partnerSeat, "free", true));
                        return null;
                    }
                } else {
                    session.setTurn(lastSeat);
                    session.setLastPlay(null);
                    session.setPassCount(0);
                    saveSession(roomCode, session);
                    roomService.broadcastGame(roomCode, "TURN",
                            Map.of("seat", lastSeat, "free", true));
                    return null;
                }
            }

            advanceTurn(roomCode, session);
            saveSession(roomCode, session);
            return null;
        });
    }

    /**
     * {@inheritDoc}
     * 取消叉超时，从手牌移除对子，检查是否有人可勾，有则进GOU_WAIT否则叉者直接上手
     */
    // ===== cha =====
    @Override
    public void cha(String roomCode, String uuid) {
        withGameLock(roomCode, () -> {
            GameSession session = loadSessionRequired(roomCode);
            if (!"CHA_WAIT".equals(session.getState())) throw new BizException(ErrorCode.CARD_INVALID_STATE);

            int seat = roomService.findSeatByUuid(roomCode, uuid);
            if (seat == -1) throw new BizException(ErrorCode.CARD_PLAYER_NOT_IN_ROOM);
            if (seat == session.getChaSingleSeat()) throw new BizException(ErrorCode.CARD_CHA_INVALID);
            if (session.getFinishOrder().contains(seat)) throw new BizException(ErrorCode.CARD_CHA_INVALID);

            String rank = session.getChaRank();
            List<String> hand = session.getHands().get(seat);
            if (!Card414Engine.canCha(rank, hand)) throw new BizException(ErrorCode.CARD_CHA_INVALID);

            session.getChaWaiters().clear();

            // 从手牌移除叉的对子
            List<String> chaCards = Card414Engine.getChaCards(rank, hand);
            for (String c : chaCards) hand.remove(c);

            session.setChaSeat(seat);

            // 叉完检查是否出完
            boolean chaFinished = hand.isEmpty();
            if (chaFinished) {
                session.getFinishOrder().add(seat);
                log.info("叉后出完: room={}, seat={}", roomCode, seat);
            }

            int[] handCounts = getHandCounts(session);
            roomService.broadcastGame(roomCode, "CHA",
                    Map.of("seat", seat, "cards", chaCards, "rank", rank,
                            "handCounts", handCounts, "finished", chaFinished));

            // 叉完出完 → 检查本轮是否结束
            if (checkFinished(roomCode, session, seat, chaFinished)) return null;

            // 检查是否有人可勾
            List<Integer> gouWaiters = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                if (i == seat || session.getFinishOrder().contains(i)) continue;
                if (Card414Engine.canGou(rank, session.getHands().get(i))) {
                    gouWaiters.add(i);
                }
            }

            if (!gouWaiters.isEmpty()) {
                session.setState("GOU_WAIT");
                session.setGouWaiters(gouWaiters);
                saveSession(roomCode, session);
                roomService.broadcastGame(roomCode, "GOU_WAIT",
                        Map.of("rank", rank, "waiters", gouWaiters));
            } else {
                assignFreeTurn(roomCode, session, seat);
                saveSession(roomCode, session);
            }
            return null;
        });
    }

    /**
     * 检查是否结束
     */
    private boolean checkFinished(String roomCode, GameSession session, int seat, boolean isFinished) {
        if (isFinished) {
            int partnerSeat = Card414Engine.partnerSeat(seat);
            boolean teamDone = session.getFinishOrder().contains(partnerSeat);
            if (teamDone || countActivePlayers(session) <= 1) {
                processRoundOver(roomCode, session);
                saveSession(roomCode, session);
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     * 取消勾超时，从手牌移除第4张牌，勾者获得自由出牌权
     */
    // ===== gou =====
    @Override
    public void gou(String roomCode, String uuid) {
        withGameLock(roomCode, () -> {
            GameSession session = loadSessionRequired(roomCode);
            if (!"GOU_WAIT".equals(session.getState())) throw new BizException(ErrorCode.CARD_INVALID_STATE);

            int seat = roomService.findSeatByUuid(roomCode, uuid);
            if (seat == -1) throw new BizException(ErrorCode.CARD_PLAYER_NOT_IN_ROOM);
            if (seat == session.getChaSeat()) throw new BizException(ErrorCode.CARD_GOU_INVALID);
            if (session.getFinishOrder().contains(seat)) throw new BizException(ErrorCode.CARD_GOU_INVALID);

            String rank = session.getChaRank();
            List<String> hand = session.getHands().get(seat);
            if (!Card414Engine.canGou(rank, hand)) throw new BizException(ErrorCode.CARD_GOU_INVALID);

            session.getGouWaiters().clear();

            String gouCard = Card414Engine.getGouCard(rank, hand);
            hand.remove(gouCard);

            // 勾完检查是否出完
            boolean gouFinished = hand.isEmpty();
            if (gouFinished) {
                session.getFinishOrder().add(seat);
                log.info("勾后出完: room={}, seat={}", roomCode, seat);
            }

            int[] handCounts = getHandCounts(session);
            roomService.broadcastGame(roomCode, "GOU",
                    Map.of("seat", seat, "card", gouCard, "rank", rank,
                            "handCounts", handCounts, "finished", gouFinished));

            // 勾完出完 → 检查本轮是否结束
            if (checkFinished(roomCode, session, seat, gouFinished)) return null;

            // 勾者上手（assignFreeTurn内部处理出完→队友光）
            assignFreeTurn(roomCode, session, seat);
            saveSession(roomCode, session);
            return null;
        });
    }

    // ===== passCha =====
    @Override
    public void passCha(String roomCode, String uuid) {
        withGameLock(roomCode, () -> {
            GameSession session = loadSessionRequired(roomCode);
            if (!"CHA_WAIT".equals(session.getState())) throw new BizException(ErrorCode.CARD_INVALID_STATE);

            int seat = roomService.findSeatByUuid(roomCode, uuid);
            if (seat == -1) throw new BizException(ErrorCode.CARD_PLAYER_NOT_IN_ROOM);
            if (!session.getChaWaiters().contains(seat)) throw new BizException(ErrorCode.CARD_CHA_INVALID);

            session.getChaWaiters().remove(Integer.valueOf(seat));

            roomService.broadcastGame(roomCode, "PASS_CHA", Map.of("seat", seat));

            if (session.getChaWaiters().isEmpty()) {
                // 全部放弃叉，恢复正常流转
                session.setState("PLAY");
                session.setChaRank(null);
                session.setChaSingleSeat(-1);
                advanceTurn(roomCode, session);
            }
            saveSession(roomCode, session);
            return null;
        });
    }

    // ===== passGou =====
    @Override
    public void passGou(String roomCode, String uuid) {
        withGameLock(roomCode, () -> {
            GameSession session = loadSessionRequired(roomCode);
            if (!"GOU_WAIT".equals(session.getState())) throw new BizException(ErrorCode.CARD_INVALID_STATE);

            int seat = roomService.findSeatByUuid(roomCode, uuid);
            if (seat == -1) throw new BizException(ErrorCode.CARD_PLAYER_NOT_IN_ROOM);
            if (!session.getGouWaiters().contains(seat)) throw new BizException(ErrorCode.CARD_GOU_INVALID);

            session.getGouWaiters().remove(Integer.valueOf(seat));

            roomService.broadcastGame(roomCode, "PASS_GOU", Map.of("seat", seat));

            if (session.getGouWaiters().isEmpty()) {
                // 全部放弃勾，叉者上手
                int chaSeat = session.getChaSeat();
                assignFreeTurn(roomCode, session, chaSeat);
            }
            saveSession(roomCode, session);
            return null;
        });
    }

    /** {@inheritDoc} */
    // ===== getHand =====
    @Override
    public List<String> getHand(String roomCode, String uuid) {
        GameSession session = loadSession(roomCode);
        if (session == null) return List.of();
        int seat = roomService.findSeatByUuid(roomCode, uuid);
        if (seat == -1 || seat >= session.getHands().size()) return List.of();
        return new ArrayList<>(session.getHands().get(seat));
    }

    /** {@inheritDoc} */
    // ===== getGameState =====
    @Override
    public Map<String, Object> getGameState(String roomCode, String uuid) {
        GameSession session = loadSession(roomCode);
        if (session == null) return Map.of();
        int mySeat = roomService.findSeatByUuid(roomCode, uuid);

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("round", session.getRound());
        state.put("hunA", session.getHunA());
        state.put("hunB", session.getHunB());
        state.put("hunTeam", session.getHunTeam());
        state.put("hunRank", currentHunRank(session));
        state.put("turn", session.getTurn());
        state.put("handCounts", getHandCounts(session));
        state.put("lastPlay", session.getLastPlay());
        state.put("passCount", session.getPassCount());
        state.put("finishOrder", session.getFinishOrder());
        state.put("state", session.getState());
        state.put("mySeat", mySeat);
        state.put("lightSeat", session.getLightSeat());

        if (mySeat >= 0 && mySeat < session.getHands().size()) {
            state.put("hand", session.getHands().get(mySeat));
        }

        if ("CHA_WAIT".equals(session.getState()) || "GOU_WAIT".equals(session.getState())) {
            state.put("chaRank", session.getChaRank());
            state.put("chaWaiters", session.getChaWaiters());
            state.put("gouWaiters", session.getGouWaiters());
            state.put("chaSeat", session.getChaSeat());
        }

        return state;
    }

    // ===== 内部方法 =====

    /**
     * 流转到下一个未出完的玩家并广播TURN
     *
     * @param roomCode 房间号
     * @param session  当前游戏会话
     */
    private void advanceTurn(String roomCode, GameSession session) {
        int next = nextActiveSeat(session, session.getTurn());
        session.setTurn(next);

        // 跳过出单张玩家自己（如果轮回来了）
        roomService.broadcastGame(roomCode, "TURN",
                Map.of("seat", next, "free", session.getLastPlay() == null));
    }

    /**
     * 从当前座位顺时针找下一个未出完的座位
     *
     * @param session 游戏会话
     * @param current 当前座位
     * @return 下一个活跃座位号
     */
    private int nextActiveSeat(GameSession session, int current) {
        for (int i = 1; i <= 4; i++) {
            int next = (current + i) % 4;
            if (!session.getFinishOrder().contains(next)) return next;
        }
        return current;
    }

    /**
     * 统计还未出完牌的玩家数
     *
     * @param session 游戏会话
     * @return 活跃玩家数(4 - 已出完人数)
     */
    private int countActivePlayers(GameSession session) {
        return 4 - session.getFinishOrder().size();
    }

    /**
     * 叉/勾结算后分配自由出牌权：重置叉勾状态，出完则队友获光，否则本人上手
     *
     * @param roomCode 房间号
     * @param session  游戏会话
     * @param seat     获得出牌权的座位（叉者或勾者）
     */
    private void assignFreeTurn(String roomCode, GameSession session, int seat) {
        session.setState("PLAY");
        session.setLastPlay(null);
        session.setPassCount(0);
        session.setChaRank(null);
        session.setChaSeat(-1);
        session.setChaSingleSeat(-1);

        if (session.getFinishOrder().contains(seat)) {
            int partnerSeat = Card414Engine.partnerSeat(seat);
            session.setLightSeat(partnerSeat);
            session.setTurn(partnerSeat);
            roomService.broadcastGame(roomCode, "LIGHT", Map.of("seat", partnerSeat, "from", seat));
            roomService.broadcastGame(roomCode, "TURN", Map.of("seat", partnerSeat, "free", true));
        } else {
            session.setLightSeat(-1);
            session.setTurn(seat);
            roomService.broadcastGame(roomCode, "TURN", Map.of("seat", seat, "free", true));
        }
    }

    /**
     * 获取当前混牌面值(由混方决定取A队还是B队的混)
     *
     * @param session 游戏会话
     * @return 混牌面值如"3","7","A"等
     */
    private String currentHunRank(GameSession session) {
        return "A".equals(session.getHunTeam()) ? session.getHunA() : session.getHunB();
    }

    /**
     * 获取4个座位的手牌数量数组
     *
     * @param session 游戏会话
     * @return int[4]，出完的玩家为0
     */
    private int[] getHandCounts(GameSession session) {
        int[] counts = new int[4];
        for (int i = 0; i < 4; i++) counts[i] = session.getHands().get(i).size();
        return counts;
    }

    /**
     * 处理一轮结束：计算大贡/抓人数/混升降/重置判定/胜负判定，广播ROUND_OVER并异步写DB
     *
     * @param roomCode 房间号
     * @param session  游戏会话
     */
    // ===== 轮次结束 =====
    private void processRoundOver(String roomCode, GameSession session) {
        // 补上剩余未出完的玩家
        for (int i = 0; i < 4; i++) {
            if (!session.getFinishOrder().contains(i)) {
                session.getFinishOrder().add(i);
            }
        }

        session.setState("ROUND_OVER");

        int dagongSeat = session.getFinishOrder().getFirst();
        String dagongTeam = Card414Engine.teamOf(dagongSeat);
        int partnerSeat = Card414Engine.partnerSeat(dagongSeat);
        int partnerIdx = session.getFinishOrder().indexOf(partnerSeat);

        // 抓人数
        int caught = switch (partnerIdx) {
            case 1 -> 2; // 1st+2nd
            case 2 -> 1; // 1st+3rd
            default -> 0; // 1st+4th (土了)
        };

        // 更新混
        String currentHun = "A".equals(dagongTeam) ? session.getHunA() : session.getHunB();
        String newHun = Card414Engine.advanceHun(currentHun, caught);

        // 重置检测：对方混>=J 且被大贡方抓2人
        String oppTeam = "A".equals(dagongTeam) ? "B" : "A";
        String oppHun = "A".equals(oppTeam) ? session.getHunA() : session.getHunB();
        if (caught == 2 && Card414Engine.isHunAtOrAboveJ(oppHun)) {
            if ("A".equals(oppTeam)) session.setHunA("3");
            else session.setHunB("3");
            log.info("{}队混被重置: room={}", oppTeam, roomCode);
        }

        // 更新大贡方混
        if ("A".equals(dagongTeam)) session.setHunA(newHun);
        else session.setHunB(newHun);

        session.setDagong(dagongSeat);
        session.setHunTeam(dagongTeam);

        // 胜利判定
        boolean gameOver = false;
        String winner = null;
        if (Card414Engine.isHunAtA(newHun) && caught >= 1) {
            gameOver = true;
            winner = dagongTeam;
        }

        // 构建结算数据
        Map<String, Object> roundData = new LinkedHashMap<>();
        roundData.put("round", session.getRound());
        roundData.put("finishOrder", session.getFinishOrder());
        roundData.put("dagong", dagongSeat);
        roundData.put("dagongTeam", dagongTeam);
        roundData.put("caught", caught);
        roundData.put("hunA", session.getHunA());
        roundData.put("hunB", session.getHunB());
        roundData.put("nextHunTeam", session.getHunTeam());

        roomService.broadcastGame(roomCode, "ROUND_OVER", roundData);

        if (gameOver) {
            Card414RoomServiceImpl.RoomState room = roomService.loadRoom(roomCode);

            // 获胜队玩家昵称
            List<String> winnerPlayers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                if (Card414Engine.teamOf(i).equals(winner) && room.getSeats()[i] != null) {
                    winnerPlayers.add(room.getSeats()[i].getNickname());
                }
            }

            Map<String, Object> gameOverData = new LinkedHashMap<>();
            gameOverData.put("winner", winner);
            gameOverData.put("hunA", session.getHunA());
            gameOverData.put("hunB", session.getHunB());
            gameOverData.put("winnerPlayers", winnerPlayers);
            roomService.broadcastGame(roomCode, "GAME_OVER", gameOverData);

            // 先写DB（需要room数据），再清理
            saveGameRecord(roomCode, session, dagongSeat, caught, true, winner);

            // 销毁房间和游戏会话
            cacheService.delete(GAME_PREFIX + roomCode);
            roomService.destroyRoom(roomCode);
            log.info("游戏结束并清理: room={}, winner={}", roomCode, winner);
        } else {
            scheduleNextRound(roomCode);
            saveGameRecord(roomCode, session, dagongSeat, caught, false, null);
        }
    }

    /**
     * 异步保存对局记录到PostgreSQL，失败仅记日志不影响游戏
     *
     * @param roomCode  房间号
     * @param session   游戏会话
     * @param dagongSeat 大贡座位
     * @param caught    抓人数(0/1/2)
     * @param isFinal   是否为最终局(有队伍获胜)
     * @param winner    获胜队伍(A/B)，非最终局为null
     */
    private void saveGameRecord(String roomCode, GameSession session,
                                 int dagongSeat, int caught, boolean isFinal, String winner) {
        try {
            Card414RoomServiceImpl.RoomState room = roomService.loadRoom(roomCode);
            CardGame414 record = new CardGame414();
            record.setRoomCode(roomCode);
            record.setRoundNo(session.getRound());

            for (int i = 0; i < 4; i++) {
                Card414RoomServiceImpl.PlayerInfo p = room.getSeats()[i];
                if (p != null) {
                    switch (i) {
                        case 0 -> { record.setSeat1Uuid(p.getUuid()); record.setSeat1Nick(p.getNickname()); }
                        case 1 -> { record.setSeat2Uuid(p.getUuid()); record.setSeat2Nick(p.getNickname()); }
                        case 2 -> { record.setSeat3Uuid(p.getUuid()); record.setSeat3Nick(p.getNickname()); }
                        case 3 -> { record.setSeat4Uuid(p.getUuid()); record.setSeat4Nick(p.getNickname()); }
                    }
                }
            }

            record.setTeamA("0,2");
            record.setTeamB("1,3");
            record.setHunTeam(session.getHunTeam());
            record.setHunRank(currentHunRank(session));
            record.setFinishOrder(session.getFinishOrder().stream()
                    .map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
            record.setDagongSeat(dagongSeat);
            record.setCaught(caught);
            record.setHunAAfter(session.getHunA());
            record.setHunBAfter(session.getHunB());
            record.setIsFinal(isFinal);
            record.setWinnerTeam(winner);
            record.setCreatedAt(LocalDateTime.now());

            gameMapper.insert(record);
        } catch (Exception e) {
            log.error("保存对局记录失败: room={}", roomCode, e);
        }
    }

    /**
     * 5秒后自动发牌开始下一轮
     *
     * @param roomCode 房间号
     */
    private void scheduleNextRound(String roomCode) {
        scheduler.schedule(() -> {
            try {
                withGameLock(roomCode, () -> {
                    dealNextRound(roomCode);
                    return null;
                });
            } catch (Exception e) {
                log.error("自动发牌失败: room={}", roomCode, e);
            }
        }, 5, TimeUnit.SECONDS);
    }

    /**
     * 从上轮ROUND_OVER状态继承混/大贡，重新洗牌发牌并广播GAME_START
     *
     * @param roomCode 房间号
     */
    private void dealNextRound(String roomCode) {
        GameSession prev = loadSession(roomCode);
        if (prev == null || !"ROUND_OVER".equals(prev.getState())) return;

        GameSession session = new GameSession();
        session.setRound(prev.getRound() + 1);
        session.setDagong(prev.getDagong());
        session.setHunA(prev.getHunA());
        session.setHunB(prev.getHunB());
        session.setHunTeam(prev.getHunTeam());
        session.setDealStart(prev.getDagong());

        List<String> deck = Card414Engine.fullDeck();
        Card414Engine.shuffle(deck);
        List<List<String>> hands = Card414Engine.deal(deck, session.getDealStart());

        String hunRank = "A".equals(session.getHunTeam())
                ? session.getHunA() : session.getHunB();
        for (List<String> hand : hands) {
            Card414Engine.sortHand(hand, hunRank);
        }
        session.setHands(hands);

        if (session.getDagong() >= 0) {
            session.setTurn(session.getDagong());
        } else {
            for (int i = 0; i < 4; i++) {
                if (Card414Engine.hasRedHeart3(hands.get(i))) {
                    session.setTurn(i);
                    break;
                }
            }
        }

        session.setState("PLAY");
        session.setFinishOrder(new ArrayList<>());
        saveSession(roomCode, session);

        int[] handCounts = new int[4];
        for (int i = 0; i < 4; i++) handCounts[i] = hands.get(i).size();

        Map<String, Object> startData = new LinkedHashMap<>();
        startData.put("round", session.getRound());
        startData.put("hunTeam", session.getHunTeam());
        startData.put("hunA", session.getHunA());
        startData.put("hunB", session.getHunB());
        startData.put("hunRank", hunRank);
        startData.put("turn", session.getTurn());
        startData.put("handCounts", handCounts);

        roomService.broadcastGame(roomCode, "GAME_START", startData);
        log.info("自动发牌: room={}, round={}", roomCode, session.getRound());
    }

    // ===== Redis =====
    private GameSession loadSession(String roomCode) {
        return cacheService.getObject(GAME_PREFIX + roomCode);
    }

    /**
     * 从Redis加载游戏会话，不存在则抛异常
     *
     * @param roomCode 房间号
     * @return 游戏会话
     */
    private GameSession loadSessionRequired(String roomCode) {
        GameSession s = loadSession(roomCode);
        if (s == null) throw new BizException(ErrorCode.CARD_ROOM_NOT_FOUND);
        return s;
    }

    /**
     * 保存游戏会话到Redis，刷新TTL
     *
     * @param roomCode 房间号
     * @param session  游戏会话
     */
    private void saveSession(String roomCode, GameSession session) {
        cacheService.setObject(GAME_PREFIX + roomCode, session, GAME_TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * 在分布式锁保护下执行游戏操作
     *
     * @param roomCode 房间号
     * @param supplier 业务逻辑
     * @param <T>      类型
     */
    private <T> void withGameLock(String roomCode, Supplier<T> supplier) {
        try {
            redisLockUtil.executeWithLock(
                    "414:lock:game:" + roomCode, LOCK_TIMEOUT, LOCK_WAIT, supplier
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
}

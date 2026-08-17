package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.dto.VideoPokerGameStateDTO;
import com.mawai.wiibcommon.dto.VideoPokerStatusDTO;
import com.mawai.wiibcommon.entity.VideoPokerGame;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.ledger.Ledger;
import com.mawai.wiibsim.mapper.VideoPokerGameMapper;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.service.VideoPokerService;
import com.mawai.wiibsim.util.GameLockExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.enums.LedgerBizType.POKER_BET;
import static com.mawai.wiibcommon.enums.LedgerBizType.POKER_PAYOUT;

/**
 * 视频扑克。<b>进行中的那一局就是 video_poker_game 里 status=DEALING 的那行</b>：
 * 洗好的整副牌落在 deck 列，draw 从第 6 张起补牌——牌堆进了库，这局就不会因为缓存没了
 * 变成"本金扣了、牌也发了、却永远换不了牌"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoPokerServiceImpl implements VideoPokerService {

    private final VideoPokerGameMapper videoPokerGameMapper;
    private final UserService userService;
    private final GameLockExecutor gameLock;

    private static final BigDecimal MIN_BET = new BigDecimal("10");
    private static final BigDecimal MAX_BET = new BigDecimal("5000");

    private static final String LK = "videopoker:user:";

    /** 同时是 video_poker_game.status 的两个取值与前端 phase 的两个取值 */
    private static final String PHASE_DEALING = "DEALING";
    private static final String PHASE_SETTLED = "SETTLED";

    private static final String[] SUITS = {"H", "D", "C", "S"};
    private static final String[] RANKS = {"2", "3", "4", "5", "6", "7", "8", "9", "T", "J", "Q", "K", "A"};
    private static final Set<Integer> ROYAL_RANKS = Set.of(10, 11, 12, 13, 14);

    private static final Random RANDOM = new SecureRandom();

    /**
     * 8/5 Jacks or Better 标准表（52 张无鬼牌）。倍率含本金：1 = 保本返还。
     * 蒙特卡洛实测普通玩家 RTP≈96.6%，理论完美策略上限 97.3%——任何打法都无正期望。
     * 旧的含鬼牌表实测 RTP≈119.8%（玩家稳赚），故换轨；历史局的旧牌名仅作展示无影响。
     */
    private static final LinkedHashMap<String, BigDecimal> PAYOUTS = new LinkedHashMap<>();
    static {
        PAYOUTS.put("Royal Flush", new BigDecimal("800"));
        PAYOUTS.put("Straight Flush", new BigDecimal("50"));
        PAYOUTS.put("Four of a Kind", new BigDecimal("25"));
        PAYOUTS.put("Full House", new BigDecimal("8"));
        PAYOUTS.put("Flush", new BigDecimal("5"));
        PAYOUTS.put("Straight", new BigDecimal("4"));
        PAYOUTS.put("Three of a Kind", new BigDecimal("3"));
        PAYOUTS.put("Two Pair", new BigDecimal("2"));
        PAYOUTS.put("Jacks or Better", new BigDecimal("1"));
    }

    // ==================== 公开接口 ====================

    @Override
    public VideoPokerStatusDTO getStatus(Long userId) {
        return gameLock.executeInLock(LK, userId, () -> {
            VideoPokerStatusDTO dto = new VideoPokerStatusDTO();
            dto.setBalance(userService.getGameBalance(userId));
            VideoPokerGame game = videoPokerGameMapper.selectDealing(userId);
            if (game != null) {
                dto.setActiveGame(buildDealingState(game, dto.getBalance()));
            }
            return dto;
        });
    }

    @Override
    @Ledger(POKER_BET)
    public VideoPokerGameStateDTO bet(Long userId, BigDecimal amount) {
        return gameLock.executeInLockTx(LK, userId, () -> {
            if (amount == null || amount.compareTo(MIN_BET) < 0 || amount.compareTo(MAX_BET) > 0) {
                throw new BizException(ErrorCode.VP_INVALID_BET);
            }
            if (videoPokerGameMapper.selectDealing(userId) != null) {
                throw new BizException(ErrorCode.VP_GAME_IN_PROGRESS);
            }
            BigDecimal balance = userService.getGameBalance(userId);
            if (balance.compareTo(amount) < 0) {
                throw new BizException(ErrorCode.VP_BALANCE_NOT_ENOUGH);
            }

            userService.updateGameBalance(userId, amount.negate());

            List<String> deck = buildDeck();
            Collections.shuffle(deck, RANDOM);

            VideoPokerGame game = new VideoPokerGame();
            game.setUserId(userId);
            game.setBetAmount(amount);
            game.setInitialCards(String.join(",", deck.subList(0, 5)));
            game.setDeck(String.join(",", deck));   // 整副都存，draw 要拿第 6 张往后补牌
            game.setHeldPositions("");
            game.setFinalCards("");
            game.setHandRank("");
            game.setMultiplier(BigDecimal.ZERO);
            game.setPayout(BigDecimal.ZERO);
            game.setStatus(PHASE_DEALING);
            game.setCreatedAt(LocalDateTime.now());
            game.setUpdatedAt(LocalDateTime.now());
            videoPokerGameMapper.insert(game);

            BigDecimal newBalance = userService.getGameBalance(userId);
            return buildDealingState(game, newBalance);
        });
    }

    @Override
    @Ledger(POKER_PAYOUT)
    public VideoPokerGameStateDTO draw(Long userId, List<Integer> held) {
        List<Integer> heldList = held != null ? held : Collections.emptyList();
        return gameLock.executeInLockTx(LK, userId, () -> {
            VideoPokerGame game = videoPokerGameMapper.selectDealing(userId);
            if (game == null) {
                throw new BizException(ErrorCode.VP_NO_ACTIVE_GAME);
            }

            Set<Integer> heldSet = new LinkedHashSet<>(heldList);
            if (heldSet.size() != heldList.size()) {
                throw new BizException(ErrorCode.VP_INVALID_HOLD);
            }
            for (int pos : heldSet) {
                if (pos < 0 || pos > 4) {
                    throw new BizException(ErrorCode.VP_INVALID_HOLD);
                }
            }

            List<String> deck = splitCards(game.getDeck());
            List<String> finalCards = splitCards(game.getInitialCards());
            int nextIdx = 5;
            for (int i = 0; i < 5; i++) {
                if (!heldSet.contains(i)) {
                    finalCards.set(i, deck.get(nextIdx++));
                }
            }

            String handRank = evaluateHand(finalCards);
            BigDecimal multiplier = PAYOUTS.getOrDefault(handRank, BigDecimal.ZERO);
            BigDecimal payout = game.getBetAmount().multiply(multiplier).setScale(2, RoundingMode.HALF_UP);

            if (payout.compareTo(BigDecimal.ZERO) > 0) {
                userService.updateGameBalance(userId, payout);
            }

            game.setHeldPositions(heldSet.stream().sorted().map(String::valueOf).collect(Collectors.joining(",")));
            game.setFinalCards(String.join(",", finalCards));
            game.setHandRank(handRank);
            game.setMultiplier(multiplier);
            game.setPayout(payout);
            game.setStatus(PHASE_SETTLED);
            game.setUpdatedAt(LocalDateTime.now());
            videoPokerGameMapper.updateById(game);

            BigDecimal balance = userService.getGameBalance(userId);

            VideoPokerGameStateDTO dto = new VideoPokerGameStateDTO();
            dto.setGameId(game.getId());
            dto.setBetAmount(game.getBetAmount());
            dto.setCards(finalCards);
            dto.setHeldPositions(new ArrayList<>(heldSet));
            dto.setHandRank(handRank);
            dto.setMultiplier(multiplier);
            dto.setPayout(payout);
            dto.setPhase(PHASE_SETTLED);
            dto.setBalance(balance);
            return dto;
        });
    }

    // ==================== 牌型评估 ====================

    static String evaluateHand(List<String> cards) {
        List<Integer> ranks = new ArrayList<>(5);
        List<String> suits = new ArrayList<>(5);
        for (String c : cards) {
            ranks.add(rankToValue(c.substring(0, 1)));
            suits.add(c.substring(1));
        }

        Map<Integer, Integer> countMap = new HashMap<>();
        for (int r : ranks) countMap.merge(r, 1, Integer::sum);
        int maxCount = countMap.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        boolean isFlush = suits.stream().distinct().count() == 1;
        boolean isStraight = isStraight(ranks);

        if (isFlush && isStraight && ROYAL_RANKS.containsAll(ranks)) {
            return "Royal Flush";
        }
        if (isFlush && isStraight) {
            return "Straight Flush";
        }
        if (maxCount == 4) {
            return "Four of a Kind";
        }
        // 3+2 恰好两种点数；3+1+1 是三种，天然区分葫芦与三条
        if (maxCount == 3 && countMap.size() == 2) {
            return "Full House";
        }
        if (isFlush) {
            return "Flush";
        }
        if (isStraight) {
            return "Straight";
        }
        if (maxCount == 3) {
            return "Three of a Kind";
        }
        long pairCount = countMap.values().stream().filter(c -> c == 2).count();
        if (pairCount == 2) {
            return "Two Pair";
        }
        for (var e : countMap.entrySet()) {
            if (e.getValue() == 2 && e.getKey() >= 11) {
                return "Jacks or Better";
            }
        }
        return "No Win";
    }

    /** A 双向：既可 10-J-Q-K-A 也可作 1 凑 A-2-3-4-5 */
    private static final Set<Integer> WHEEL = Set.of(14, 2, 3, 4, 5);

    private static boolean isStraight(List<Integer> ranks) {
        TreeSet<Integer> distinct = new TreeSet<>(ranks);
        if (distinct.size() != 5) return false;
        return distinct.last() - distinct.first() == 4 || distinct.equals(WHEEL);
    }

    private static int rankToValue(String rank) {
        return switch (rank) {
            case "A" -> 14;
            case "K" -> 13;
            case "Q" -> 12;
            case "J" -> 11;
            case "T" -> 10;
            default -> Integer.parseInt(rank);
        };
    }

    // ==================== 辅助 ====================

    private static List<String> buildDeck() {
        List<String> deck = new ArrayList<>(52);
        for (String s : SUITS) {
            for (String r : RANKS) {
                deck.add(r + s);
            }
        }
        return deck;
    }

    /** 逗号串 → 牌面表。返回可变表，draw 要就地换牌 */
    private static List<String> splitCards(String csv) {
        return new ArrayList<>(Arrays.asList(csv.split(",")));
    }

    private VideoPokerGameStateDTO buildDealingState(VideoPokerGame game, BigDecimal balance) {
        VideoPokerGameStateDTO dto = new VideoPokerGameStateDTO();
        dto.setGameId(game.getId());
        dto.setBetAmount(game.getBetAmount());
        dto.setCards(splitCards(game.getInitialCards()));
        dto.setHeldPositions(Collections.emptyList());
        dto.setHandRank("");
        dto.setMultiplier(BigDecimal.ZERO);
        dto.setPayout(BigDecimal.ZERO);
        dto.setPhase(PHASE_DEALING);
        dto.setBalance(balance);
        return dto;
    }
}

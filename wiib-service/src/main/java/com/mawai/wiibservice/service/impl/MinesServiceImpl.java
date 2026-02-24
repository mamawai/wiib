package com.mawai.wiibservice.service.impl;

import com.mawai.wiibcommon.dto.MinesGameStateDTO;
import com.mawai.wiibcommon.dto.MinesStatusDTO;
import com.mawai.wiibcommon.entity.MinesGame;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.mapper.MinesGameMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.MinesService;
import com.mawai.wiibservice.service.UserService;
import com.mawai.wiibservice.util.RedisLockUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinesServiceImpl implements MinesService {

    private final MinesGameMapper minesGameMapper;
    private final CacheService cacheService;
    private final UserService userService;
    private final RedisLockUtil redisLockUtil;

    private static final int GRID_SIZE = 25;
    private static final int MINE_COUNT = 5;
    private static final int SAFE_COUNT = GRID_SIZE - MINE_COUNT;
    private static final BigDecimal MIN_BET = new BigDecimal("100");
    private static final BigDecimal MAX_BET = new BigDecimal("50000");
    private static final BigDecimal HOUSE_EDGE = new BigDecimal("0.50");
    private static final double DAMPEN = 0.9;

    private static final String SESSION_KEY_PREFIX = "mines:session:";
    private static final String USER_LOCK_KEY_PREFIX = "mines:user:";
    private static final long SESSION_TTL_HOURS = 2;
    private static final long LOCK_TIMEOUT_SECONDS = 20;
    private static final long LOCK_WAIT_MILLIS = 3_000;

    private static final String PHASE_PLAYING = "PLAYING";
    private static final String PHASE_SETTLED = "SETTLED";
    private static final String STATUS_PLAYING = "PLAYING";
    private static final String STATUS_CASHED_OUT = "CASHED_OUT";
    private static final String STATUS_EXPLODED = "EXPLODED";

    private static final Random RANDOM = new SecureRandom();

    // 倍率表 multipliers[k] = HOUSE_EDGE * (C(25,k)/C(20,k)) ^ DAMPEN
    private static final BigDecimal[] MULTIPLIERS = new BigDecimal[SAFE_COUNT + 1];

    static {
        MULTIPLIERS[0] = BigDecimal.ONE;
        // 先递推算原始倍率 raw[k] = C(25,k)/C(20,k)
        BigDecimal[] raw = new BigDecimal[SAFE_COUNT + 1];
        raw[0] = BigDecimal.ONE;
        for (int k = 1; k <= SAFE_COUNT; k++) {
            raw[k] = raw[k - 1]
                    .multiply(BigDecimal.valueOf(GRID_SIZE - k + 1))
                    .divide(BigDecimal.valueOf(SAFE_COUNT - k + 1), 10, RoundingMode.HALF_UP);
        }
        // HOUSE_EDGE * raw^DAMPEN
        for (int k = 1; k <= SAFE_COUNT; k++) {
            double dampened = Math.pow(raw[k].doubleValue(), DAMPEN);
            MULTIPLIERS[k] = HOUSE_EDGE.multiply(BigDecimal.valueOf(dampened)).setScale(4, RoundingMode.HALF_UP);
        }
    }

    @Data
    public static class MinesSession implements java.io.Serializable {
        private long gameId;
        private BigDecimal betAmount;
        private Set<Integer> minePositions;
        private List<Integer> revealed;
        private String phase;
    }

    // ==================== 公开接口 ====================

    @Override
    public MinesStatusDTO getStatus(Long userId) {
        return withUserLock(userId, () -> {
            MinesStatusDTO dto = new MinesStatusDTO();
            dto.setBalance(userService.getUserPortfolio(userId).getBalance());

            MinesSession session = getSession(userId);
            if (session != null) {
                dto.setActiveGame(buildPlayingState(session, dto.getBalance()));
            }
            return dto;
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MinesGameStateDTO bet(Long userId, BigDecimal amount) {
        return withUserLock(userId, () -> {
            if (amount == null || amount.compareTo(MIN_BET) < 0 || amount.compareTo(MAX_BET) > 0) {
                throw new BizException(ErrorCode.MINES_INVALID_BET);
            }

            if (getSession(userId) != null) {
                throw new BizException(ErrorCode.MINES_GAME_IN_PROGRESS);
            }

            BigDecimal balance = userService.getUserPortfolio(userId).getBalance();
            if (balance.compareTo(amount) < 0) {
                throw new BizException(ErrorCode.MINES_BALANCE_NOT_ENOUGH);
            }

            // 扣余额
            userService.updateBalance(userId, amount.negate());

            // 生成雷位
            Set<Integer> mines = generateMines();

            BigDecimal fee = amount.multiply(new BigDecimal("0.01")).setScale(2, RoundingMode.HALF_UP);

            // 写DB
            MinesGame game = new MinesGame();
            game.setUserId(userId);
            game.setBetAmount(amount);
            game.setFee(fee);
            game.setMinePositions(mines.stream().sorted().map(String::valueOf).collect(Collectors.joining(",")));
            game.setRevealedCells("");
            game.setMultiplier(BigDecimal.ONE);
            game.setPayout(BigDecimal.ZERO);
            game.setStatus(STATUS_PLAYING);
            game.setCreatedAt(LocalDateTime.now());
            game.setUpdatedAt(LocalDateTime.now());
            minesGameMapper.insert(game);

            // 写Redis session
            MinesSession session = new MinesSession();
            session.setGameId(game.getId());
            session.setBetAmount(amount);
            session.setMinePositions(mines);
            session.setRevealed(new ArrayList<>());
            session.setPhase(PHASE_PLAYING);
            saveSession(userId, session);

            BigDecimal newBalance = userService.getUserPortfolio(userId).getBalance();
            return buildPlayingState(session, newBalance);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MinesGameStateDTO reveal(Long userId, int cell) {
        return withUserLock(userId, () -> {
            if (cell < 0 || cell >= GRID_SIZE) {
                throw new BizException(ErrorCode.MINES_INVALID_CELL);
            }

            MinesSession session = requireSession(userId);
            requirePlaying(session);

            if (session.getRevealed().contains(cell)) {
                throw new BizException(ErrorCode.MINES_CELL_ALREADY_REVEALED);
            }

            boolean isMine = session.getMinePositions().contains(cell);

            if (isMine) {
                // 踩雷
                session.setPhase(PHASE_SETTLED);

                // 更新DB
                MinesGame game = minesGameMapper.selectById(session.getGameId());
                game.setStatus(STATUS_EXPLODED);
                game.setPayout(BigDecimal.ZERO);
                game.setRevealedCells(toDbString(session.getRevealed()));
                game.setUpdatedAt(LocalDateTime.now());
                minesGameMapper.updateById(game);

                deleteSession(userId);

                BigDecimal balance = userService.getUserPortfolio(userId).getBalance();

                MinesGameStateDTO dto = new MinesGameStateDTO();
                dto.setGameId(session.getGameId());
                dto.setBetAmount(session.getBetAmount());
                dto.setRevealed(session.getRevealed());
                dto.setMinePositions(new ArrayList<>(session.getMinePositions()));
                dto.setResult("MINE");
                dto.setCurrentMultiplier(getMultiplier(session.getRevealed().size()));
                dto.setNextMultiplier(null);
                dto.setPotentialPayout(BigDecimal.ZERO);
                dto.setPayout(BigDecimal.ZERO);
                dto.setPhase(PHASE_SETTLED);
                dto.setBalance(balance);
                return dto;
            }

            // 安全
            session.getRevealed().add(cell);
            int revealedCount = session.getRevealed().size();
            BigDecimal multiplier = getMultiplier(revealedCount);

            // 翻完所有安全格 -> 自动提现
            if (revealedCount == SAFE_COUNT) {
                return doCashout(userId, session, multiplier);
            }

            saveSession(userId, session);

            // 更新DB中的revealed
            MinesGame game = minesGameMapper.selectById(session.getGameId());
            game.setRevealedCells(toDbString(session.getRevealed()));
            game.setMultiplier(multiplier);
            game.setUpdatedAt(LocalDateTime.now());
            minesGameMapper.updateById(game);

            BigDecimal balance = userService.getUserPortfolio(userId).getBalance();
            return buildPlayingState(session, balance);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MinesGameStateDTO cashout(Long userId) {
        return withUserLock(userId, () -> {
            MinesSession session = requireSession(userId);
            requirePlaying(session);

            if (session.getRevealed().isEmpty()) {
                throw new BizException(ErrorCode.MINES_MUST_REVEAL_FIRST);
            }

            BigDecimal multiplier = getMultiplier(session.getRevealed().size());
            return doCashout(userId, session, multiplier);
        });
    }

    // ==================== 内部逻辑 ====================

    private MinesGameStateDTO doCashout(Long userId, MinesSession session, BigDecimal multiplier) {
        BigDecimal payout = session.getBetAmount().multiply(multiplier).setScale(2, RoundingMode.HALF_UP);

        userService.updateBalance(userId, payout);

        session.setPhase(PHASE_SETTLED);

        MinesGame game = minesGameMapper.selectById(session.getGameId());
        game.setStatus(STATUS_CASHED_OUT);
        game.setMultiplier(multiplier);
        game.setPayout(payout);
        game.setRevealedCells(toDbString(session.getRevealed()));
        game.setUpdatedAt(LocalDateTime.now());
        minesGameMapper.updateById(game);

        deleteSession(userId);

        BigDecimal balance = userService.getUserPortfolio(userId).getBalance();

        MinesGameStateDTO dto = new MinesGameStateDTO();
        dto.setGameId(session.getGameId());
        dto.setBetAmount(session.getBetAmount());
        dto.setRevealed(session.getRevealed());
        dto.setMinePositions(new ArrayList<>(session.getMinePositions()));
        dto.setResult("CASHED_OUT");
        dto.setCurrentMultiplier(multiplier);
        dto.setNextMultiplier(null);
        dto.setPotentialPayout(payout);
        dto.setPayout(payout);
        dto.setPhase(PHASE_SETTLED);
        dto.setBalance(balance);
        return dto;
    }

    private Set<Integer> generateMines() {
        Set<Integer> mines = new HashSet<>();
        while (mines.size() < MINE_COUNT) {
            mines.add(RANDOM.nextInt(GRID_SIZE));
        }
        return mines;
    }

    private BigDecimal getMultiplier(int revealedCount) {
        if (revealedCount < 0 || revealedCount > SAFE_COUNT) return BigDecimal.ONE;
        return MULTIPLIERS[revealedCount];
    }

    private MinesGameStateDTO buildPlayingState(MinesSession session, BigDecimal balance) {
        int count = session.getRevealed().size();
        BigDecimal multiplier = getMultiplier(count);
        BigDecimal nextMultiplier = count < SAFE_COUNT ? getMultiplier(count + 1) : null;

        MinesGameStateDTO dto = new MinesGameStateDTO();
        dto.setGameId(session.getGameId());
        dto.setBetAmount(session.getBetAmount());
        dto.setRevealed(new ArrayList<>(session.getRevealed()));
        dto.setMinePositions(null);
        dto.setResult(null);
        dto.setCurrentMultiplier(multiplier);
        dto.setNextMultiplier(nextMultiplier);
        dto.setPotentialPayout(count > 0
                ? session.getBetAmount().multiply(multiplier).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        dto.setPayout(null);
        dto.setPhase(PHASE_PLAYING);
        dto.setBalance(balance);
        return dto;
    }

    private String toDbString(List<Integer> list) {
        return list.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    // ==================== Redis会话 ====================

    private String sessionKey(Long userId) {
        return SESSION_KEY_PREFIX + userId;
    }

    private MinesSession getSession(Long userId) {
        return cacheService.getObject(sessionKey(userId));
    }

    private MinesSession requireSession(Long userId) {
        MinesSession session = getSession(userId);
        if (session == null) {
            throw new BizException(ErrorCode.MINES_NO_ACTIVE_GAME);
        }
        return session;
    }

    private void saveSession(Long userId, MinesSession session) {
        cacheService.setObject(sessionKey(userId), session, SESSION_TTL_HOURS, TimeUnit.HOURS);
    }

    private void deleteSession(Long userId) {
        cacheService.delete(sessionKey(userId));
    }

    private void requirePlaying(MinesSession session) {
        if (!PHASE_PLAYING.equals(session.getPhase())) {
            throw new BizException(ErrorCode.MINES_NO_ACTIVE_GAME);
        }
    }

    // ==================== 分布式锁 ====================

    private <T> T withUserLock(Long userId, Supplier<T> supplier) {
        try {
            return redisLockUtil.executeWithLock(
                    USER_LOCK_KEY_PREFIX + userId,
                    LOCK_TIMEOUT_SECONDS,
                    LOCK_WAIT_MILLIS,
                    supplier
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

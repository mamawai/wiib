package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.dto.MinesGameStateDTO;
import com.mawai.wiibcommon.dto.MinesStatusDTO;
import com.mawai.wiibcommon.entity.MinesGame;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.ledger.Ledger;
import com.mawai.wiibsim.mapper.MinesGameMapper;
import com.mawai.wiibsim.service.MinesService;
import com.mawai.wiibsim.service.UserService;
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

import static com.mawai.wiibcommon.enums.LedgerBizType.MINES_BET;
import static com.mawai.wiibcommon.enums.LedgerBizType.MINES_CASHOUT;

/**
 * 矿工（扫雷）。<b>进行中的那一局就是 mines_game 里 status=PLAYING 的那行</b>——
 * 雷位、已翻格、倍率本来每步就写库，事实源只此一份，没有过期这回事：
 * 扣了本金的局永远能接着玩，也不会因为缓存没了变成"钱扣了局没了"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinesServiceImpl implements MinesService {

    private final MinesGameMapper minesGameMapper;
    private final UserService userService;
    private final GameLockExecutor gameLock;

    private static final int GRID_SIZE = 25;
    private static final int MINE_COUNT = 5;
    private static final int SAFE_COUNT = GRID_SIZE - MINE_COUNT;
    private static final BigDecimal MIN_BET = new BigDecimal("10");
    private static final BigDecimal MAX_BET = new BigDecimal("5000");
    private static final BigDecimal HOUSE_EDGE = new BigDecimal("0.50");
    private static final double DAMPEN = 0.9;

    private static final String LK = "mines:user:";

    private static final String STATUS_PLAYING = "PLAYING";
    private static final String STATUS_CASHED_OUT = "CASHED_OUT";
    private static final String STATUS_EXPLODED = "EXPLODED";
    /** 前端只认 PLAYING / SETTLED 两态：兑现和踩雷都报 SETTLED */
    private static final String PHASE_SETTLED = "SETTLED";

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

    // ==================== 公开接口 ====================

    @Override
    public MinesStatusDTO getStatus(Long userId) {
        return gameLock.executeInLock(LK, userId, () -> {
            MinesStatusDTO dto = new MinesStatusDTO();
            dto.setBalance(userService.getGameBalance(userId));

            MinesGame game = minesGameMapper.selectPlaying(userId);
            if (game != null) {
                dto.setActiveGame(buildPlayingState(game, dto.getBalance()));
            }
            return dto;
        });
    }

    @Override
    @Ledger(MINES_BET)
    public MinesGameStateDTO bet(Long userId, BigDecimal amount) {
        return gameLock.executeInLockTx(LK, userId, () -> {
            if (amount == null || amount.compareTo(MIN_BET) < 0 || amount.compareTo(MAX_BET) > 0) {
                throw new BizException(ErrorCode.MINES_INVALID_BET);
            }

            if (minesGameMapper.selectPlaying(userId) != null) {
                throw new BizException(ErrorCode.MINES_GAME_IN_PROGRESS);
            }

            BigDecimal balance = userService.getGameBalance(userId);
            if (balance.compareTo(amount) < 0) {
                throw new BizException(ErrorCode.MINES_BALANCE_NOT_ENOUGH);
            }

            // 扣余额
            userService.updateGameBalance(userId, amount.negate());

            MinesGame game = new MinesGame();
            game.setUserId(userId);
            game.setBetAmount(amount);
            game.setFee(BigDecimal.ZERO);   // 从未真实收取(抽水内含在赔付表 HOUSE_EDGE)，列 NOT NULL 记 0 防假账
            game.setMinePositions(toDbString(generateMines()));
            game.setRevealedCells("");
            game.setMultiplier(BigDecimal.ONE);
            game.setPayout(BigDecimal.ZERO);
            game.setStatus(STATUS_PLAYING);
            game.setCreatedAt(LocalDateTime.now());
            game.setUpdatedAt(LocalDateTime.now());
            minesGameMapper.insert(game);

            BigDecimal newBalance = userService.getGameBalance(userId);
            return buildPlayingState(game, newBalance);
        });
    }

    @Override
    @Ledger(MINES_CASHOUT)
    public MinesGameStateDTO reveal(Long userId, int cell) {
        // 翻完最后一格会走 doCashout 派彩，所以这里也是资金入口之一。
        // @Ledger 标在这个 public 入口上——doCashout 是私有的又是同类自调用，标它是完全的空操作。
        // 踩雷分支不碰任何 UserMapper.atomic*，切面不触发，所以"这条路没派彩也带着 MINES_CASHOUT 帽子"
        // 不会多记一行账。
        return gameLock.executeInLockTx(LK, userId, () -> {
            if (cell < 0 || cell >= GRID_SIZE) {
                throw new BizException(ErrorCode.MINES_INVALID_CELL);
            }

            MinesGame game = requirePlaying(userId);
            List<Integer> revealed = parseCells(game.getRevealedCells());

            if (revealed.contains(cell)) {
                throw new BizException(ErrorCode.MINES_CELL_ALREADY_REVEALED);
            }

            List<Integer> mines = parseCells(game.getMinePositions());

            if (mines.contains(cell)) {
                // 踩雷：本局作废，revealed 保持踩雷前的样子
                game.setStatus(STATUS_EXPLODED);
                game.setPayout(BigDecimal.ZERO);
                game.setUpdatedAt(LocalDateTime.now());
                minesGameMapper.updateById(game);

                BigDecimal balance = userService.getGameBalance(userId);

                MinesGameStateDTO dto = new MinesGameStateDTO();
                dto.setGameId(game.getId());
                dto.setBetAmount(game.getBetAmount());
                dto.setRevealed(revealed);
                dto.setMinePositions(mines);
                dto.setResult("MINE");
                dto.setCurrentMultiplier(getMultiplier(revealed.size()));
                dto.setNextMultiplier(null);
                dto.setPotentialPayout(BigDecimal.ZERO);
                dto.setPayout(BigDecimal.ZERO);
                dto.setPhase(PHASE_SETTLED);
                dto.setBalance(balance);
                return dto;
            }

            // 安全
            revealed.add(cell);
            BigDecimal multiplier = getMultiplier(revealed.size());
            game.setRevealedCells(toDbString(revealed));
            game.setMultiplier(multiplier);
            game.setUpdatedAt(LocalDateTime.now());

            // 翻完所有安全格 -> 自动提现
            if (revealed.size() == SAFE_COUNT) {
                return doCashout(userId, game, multiplier);
            }

            minesGameMapper.updateById(game);

            BigDecimal balance = userService.getGameBalance(userId);
            return buildPlayingState(game, balance);
        });
    }

    @Override
    @Ledger(MINES_CASHOUT)
    public MinesGameStateDTO cashout(Long userId) {
        // 主动兑现入口，经 doCashout 派彩。派彩的账本语义标在这里，不是标在私有的 doCashout 上。
        return gameLock.executeInLockTx(LK, userId, () -> {
            MinesGame game = requirePlaying(userId);
            List<Integer> revealed = parseCells(game.getRevealedCells());

            if (revealed.isEmpty()) {
                throw new BizException(ErrorCode.MINES_MUST_REVEAL_FIRST);
            }

            return doCashout(userId, game, getMultiplier(revealed.size()));
        });
    }

    // ==================== 内部逻辑 ====================

    // 派彩点。靠 AOP 生效的注解不能标这里（private + 同类自调用，会静默失效），要标就标到
    // reveal / cashout 两个 public 入口上。事务不受影响：编程式事务挂在线程上，不看代理。
    private MinesGameStateDTO doCashout(Long userId, MinesGame game, BigDecimal multiplier) {
        BigDecimal payout = game.getBetAmount().multiply(multiplier).setScale(2, RoundingMode.HALF_UP);

        userService.updateGameBalance(userId, payout);

        game.setStatus(STATUS_CASHED_OUT);
        game.setMultiplier(multiplier);
        game.setPayout(payout);
        game.setUpdatedAt(LocalDateTime.now());
        minesGameMapper.updateById(game);

        BigDecimal balance = userService.getGameBalance(userId);

        MinesGameStateDTO dto = new MinesGameStateDTO();
        dto.setGameId(game.getId());
        dto.setBetAmount(game.getBetAmount());
        dto.setRevealed(parseCells(game.getRevealedCells()));
        dto.setMinePositions(parseCells(game.getMinePositions()));
        dto.setResult("CASHED_OUT");
        dto.setCurrentMultiplier(multiplier);
        dto.setNextMultiplier(null);
        dto.setPotentialPayout(payout);
        dto.setPayout(payout);
        dto.setPhase(PHASE_SETTLED);
        dto.setBalance(balance);
        return dto;
    }

    private List<Integer> generateMines() {
        Set<Integer> mines = new HashSet<>();
        while (mines.size() < MINE_COUNT) {
            mines.add(RANDOM.nextInt(GRID_SIZE));
        }
        return mines.stream().sorted().toList();
    }

    private BigDecimal getMultiplier(int revealedCount) {
        if (revealedCount < 0 || revealedCount > SAFE_COUNT) return BigDecimal.ONE;
        return MULTIPLIERS[revealedCount];
    }

    private MinesGameStateDTO buildPlayingState(MinesGame game, BigDecimal balance) {
        List<Integer> revealed = parseCells(game.getRevealedCells());
        int count = revealed.size();
        BigDecimal multiplier = getMultiplier(count);
        BigDecimal nextMultiplier = count < SAFE_COUNT ? getMultiplier(count + 1) : null;

        MinesGameStateDTO dto = new MinesGameStateDTO();
        dto.setGameId(game.getId());
        dto.setBetAmount(game.getBetAmount());
        dto.setRevealed(revealed);
        dto.setMinePositions(null);   // 局还没结束，雷位不能下发
        dto.setResult(null);
        dto.setCurrentMultiplier(multiplier);
        dto.setNextMultiplier(nextMultiplier);
        dto.setPotentialPayout(count > 0
                ? game.getBetAmount().multiply(multiplier).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        dto.setPayout(null);
        dto.setPhase(STATUS_PLAYING);
        dto.setBalance(balance);
        return dto;
    }

    /** 逗号串 → 格子号；空串给空表。返回可变表，调用方要往里加格子 */
    private List<Integer> parseCells(String csv) {
        if (csv == null || csv.isEmpty()) return new ArrayList<>();
        return Arrays.stream(csv.split(","))
                .map(Integer::parseInt)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String toDbString(Collection<Integer> cells) {
        return cells.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private MinesGame requirePlaying(Long userId) {
        MinesGame game = minesGameMapper.selectPlaying(userId);
        if (game == null) {
            throw new BizException(ErrorCode.MINES_NO_ACTIVE_GAME);
        }
        return game;
    }
}

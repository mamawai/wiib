package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.dto.MinesGameStateDTO;
import com.mawai.wiibcommon.entity.MinesGame;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.MinesGameMapper;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.GameLockExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 矿工的"进行中那一局"就是 mines_game 里 status=PLAYING 的那行，没有 Redis 副本：
 * bet 落行 → reveal 把翻开的格写回同一行 → cashout/踩雷把它结掉。
 * 事实源只此一份，所以"缓存过期把已扣本金的局弄丢"这件事不可能再发生。
 */
class MinesGameDbSourceTest {

    private static final Long UID = 7L;

    private MinesGameMapper gameMapper;
    private UserService userService;
    private MinesServiceImpl service;

    /** 模拟库里那唯一一行：insert 建它，selectPlaying 取它，updateById 改的就是它本身 */
    private MinesGame row;

    @BeforeEach
    void setUp() {
        gameMapper = mock(MinesGameMapper.class);
        userService = mock(UserService.class);
        GameLockExecutor gameLock = mock(GameLockExecutor.class);

        // 锁与事务不是这个用例要测的，直接跑业务闭包
        when(gameLock.executeInLockTx(anyString(), anyLong(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(2)).get());
        when(gameLock.executeInLock(anyString(), anyLong(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(2)).get());

        when(userService.getGameBalance(UID)).thenReturn(new BigDecimal("1000"));

        when(gameMapper.insert(any(MinesGame.class))).thenAnswer(inv -> {
            row = inv.getArgument(0);
            row.setId(100L);
            return 1;
        });
        when(gameMapper.selectPlaying(UID)).thenAnswer(inv ->
                row != null && "PLAYING".equals(row.getStatus()) ? row : null);
        when(gameMapper.updateById(any(MinesGame.class))).thenReturn(1);

        service = new MinesServiceImpl(gameMapper, userService, gameLock);
    }

    @Test
    void 下注只往库里落一行进行中的局() {
        MinesGameStateDTO dto = service.bet(UID, new BigDecimal("100"));

        assertThat(row.getStatus()).isEqualTo("PLAYING");
        assertThat(row.getMinePositions().split(",")).hasSize(5);
        assertThat(row.getRevealedCells()).isEmpty();
        assertThat(dto.getGameId()).isEqualTo(100L);
        assertThat(dto.getMinePositions()).isNull();   // 局没结束，雷位不能下发给前端
        verify(userService).updateGameBalance(UID, new BigDecimal("-100"));
    }

    @Test
    void 库里还有进行中的行就不许再开局() {
        service.bet(UID, new BigDecimal("100"));

        assertThatThrownBy(() -> service.bet(UID, new BigDecimal("100")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 翻格与兑现全程读写同一行() {
        service.bet(UID, new BigDecimal("100"));

        int safeCell = firstSafeCell();
        MinesGameStateDTO revealed = service.reveal(UID, safeCell);

        assertThat(row.getRevealedCells()).isEqualTo(String.valueOf(safeCell));
        assertThat(row.getStatus()).isEqualTo("PLAYING");
        assertThat(revealed.getRevealed()).containsExactly(safeCell);
        assertThat(revealed.getCurrentMultiplier()).isEqualByComparingTo(row.getMultiplier());

        MinesGameStateDTO cashed = service.cashout(UID);

        assertThat(row.getStatus()).isEqualTo("CASHED_OUT");
        assertThat(cashed.getPhase()).isEqualTo("SETTLED");
        assertThat(cashed.getPayout()).isEqualByComparingTo(row.getPayout());
        verify(userService).updateGameBalance(UID, row.getPayout());
        // 结完这行不再是 PLAYING，下一局才开得出来
        assertThat(gameMapper.selectPlaying(UID)).isNull();
    }

    @Test
    void 踩雷把那行结成EXPLODED且不派彩() {
        service.bet(UID, new BigDecimal("100"));
        int mineCell = Integer.parseInt(row.getMinePositions().split(",")[0]);

        MinesGameStateDTO dto = service.reveal(UID, mineCell);

        assertThat(row.getStatus()).isEqualTo("EXPLODED");
        assertThat(row.getPayout()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.getResult()).isEqualTo("MINE");
        assertThat(dto.getMinePositions()).hasSize(5);   // 局结束了才把雷位摊开
        verify(userService, never()).updateGameBalance(eq(UID), argThat(a -> a.signum() > 0));
    }

    @Test
    void 没有进行中的行时翻格直接拒() {
        assertThatThrownBy(() -> service.reveal(UID, 0)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.cashout(UID)).isInstanceOf(BizException.class);
    }

    private int firstSafeCell() {
        List<String> mines = Arrays.asList(row.getMinePositions().split(","));
        for (int cell = 0; cell < 25; cell++) {
            if (!mines.contains(String.valueOf(cell))) {
                return cell;
            }
        }
        throw new IllegalStateException("25 格里只埋 5 颗雷，不可能没有安全格");
    }
}

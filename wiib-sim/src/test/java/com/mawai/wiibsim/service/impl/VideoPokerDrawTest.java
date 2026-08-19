package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.dto.VideoPokerGameStateDTO;
import com.mawai.wiibcommon.entity.VideoPokerGame;
import com.mawai.wiibsim.mapper.VideoPokerGameMapper;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.GameLockExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 换牌吃的是 deck 列那副牌，靠两条不变式：initial_cards 就是 deck 的前 5 张，
 * 补牌从下标 5 起按顺序取。起点写错或两者对不上，结果是补出重复牌 / 牌型评估错 / 派彩错——
 * 全是静默的，只有对着牌堆逐张比才看得出来。
 */
class VideoPokerDrawTest {

    private static final Long UID = 7L;
    /** 牌面是"点数+花色"。前 5 张是手牌，第 6 张起是补牌区，都取成不会撞的牌方便逐张认 */
    private static final String DECK =
            "AS,2H,3D,4C,5S," + "7H,8D,9C,TS,JH," + "QD,KC,2S,3H,4D";

    private VideoPokerServiceImpl service;

    @BeforeEach
    void setUp() {
        VideoPokerGameMapper gameMapper = mock(VideoPokerGameMapper.class);
        UserService userService = mock(UserService.class);
        GameLockExecutor gameLock = mock(GameLockExecutor.class);

        // 锁与事务不是这条要测的，直接跑业务闭包
        when(gameLock.executeInLockTx(anyString(), anyLong(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(2)).get());
        when(userService.getGameBalance(UID)).thenReturn(new BigDecimal("1000"));

        VideoPokerGame dealing = new VideoPokerGame();
        dealing.setId(1L);
        dealing.setUserId(UID);
        dealing.setBetAmount(new BigDecimal("10"));
        dealing.setDeck(DECK);
        dealing.setInitialCards("AS,2H,3D,4C,5S");
        dealing.setStatus("DEALING");
        when(gameMapper.selectDealing(UID)).thenReturn(dealing);

        service = new VideoPokerServiceImpl(gameMapper, userService, gameLock);
    }

    @Test
    void 保留位不动_其余按牌堆第6张起顺序补() {
        VideoPokerGameStateDTO dto = service.draw(UID, List.of(0, 2));

        // 保留 0、2 → 剩下 1、3、4 三个位按 deck[5..7] 顺序补
        assertThat(dto.getCards()).containsExactly("AS", "7H", "3D", "8D", "9C");
    }

    @Test
    void 一张不留则五张全部来自补牌区() {
        VideoPokerGameStateDTO dto = service.draw(UID, List.of());

        assertThat(dto.getCards()).containsExactly("7H", "8D", "9C", "TS", "JH");
    }

    @Test
    void 全保留则一张都不补() {
        VideoPokerGameStateDTO dto = service.draw(UID, List.of(0, 1, 2, 3, 4));

        assertThat(dto.getCards()).containsExactly("AS", "2H", "3D", "4C", "5S");
    }
}

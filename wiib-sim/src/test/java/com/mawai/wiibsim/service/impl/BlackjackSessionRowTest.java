package com.mawai.wiibsim.service.impl;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.BlackjackAccount;
import com.mawai.wiibsim.mapper.BlackjackAccountMapper;
import com.mawai.wiibsim.mapper.BlackjackConvertLogMapper;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.GameLockExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 21 点的牌局挂在 blackjack_account.session_json 上，跟筹码同行同一笔 update——
 * 分两次写就会出现"筹码扣了牌局没存"这种最难对的账；缓存过期弄丢牌局也不再可能。
 */
class BlackjackSessionRowTest {

    private static final Long UID = 7L;

    private BlackjackAccountMapper accountMapper;
    private CacheService cacheService;
    private BlackjackServiceImpl service;

    /** 模拟库里那一行账户，selectOne 取它、updateById 改的就是它本身 */
    private BlackjackAccount account;

    @BeforeEach
    void setUp() {
        accountMapper = mock(BlackjackAccountMapper.class);
        cacheService = mock(CacheService.class);
        BlackjackConvertLogMapper convertLogMapper = mock(BlackjackConvertLogMapper.class);
        UserService userService = mock(UserService.class);
        GameLockExecutor gameLock = mock(GameLockExecutor.class);

        when(gameLock.executeInLockTx(anyString(), anyLong(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(2)).get());
        when(gameLock.executeInLock(anyString(), anyLong(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(2)).get());

        account = new BlackjackAccount();
        account.setId(1L);
        account.setUserId(UID);
        account.setChips(1000L);
        account.setTodayConverted(0L);
        account.setTotalHands(0L);
        account.setTotalWon(0L);
        account.setTotalLost(0L);
        account.setBiggestWin(0L);
        account.setLastResetDate(LocalDate.now());   // 免得每日重置多插一笔 update 干扰计数
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());

        when(accountMapper.selectOne(any())).thenReturn(account);
        when(accountMapper.updateById(any(BlackjackAccount.class))).thenReturn(1);
        when(cacheService.get(anyString())).thenReturn("20000");   // 每日积分池还剩额度

        service = new BlackjackServiceImpl(accountMapper, convertLogMapper, cacheService, userService, gameLock);
    }

    @Test
    void 下注后牌局快照落在账户行上_且只写一次() {
        dealUntilInProgress();

        verify(accountMapper, times(1)).updateById(any(BlackjackAccount.class));   // 筹码和牌局必须同一笔

        BlackjackServiceImpl.BlackjackSession restored =
                JSON.parseObject(account.getSessionJson(), BlackjackServiceImpl.BlackjackSession.class);
        assertThat(restored.getShoe()).hasSize(52);
        assertThat(restored.getShoeIndex()).isEqualTo(4);
        assertThat(restored.getDealerCards()).hasSize(2);
        assertThat(restored.getPlayerHands()).hasSize(1);
        assertThat(restored.getPlayerHands().get(0).getCards()).hasSize(2);
        assertThat(restored.getBetPerHand()).isEqualTo(50L);
        assertThat(restored.isFirstDecisionRound()).isTrue();
        assertThat(account.getChips()).isEqualTo(950L);
    }

    @Test
    void 弃局把牌局从账户行上抹掉() {
        dealUntilInProgress();
        clearInvocations(accountMapper);

        service.forfeit(UID);

        // 置 null 要真写进库：实体上标了 updateStrategy=ALWAYS，否则这局永远"在进行中"
        assertThat(account.getSessionJson()).isNull();
        verify(accountMapper, times(1)).updateById(any(BlackjackAccount.class));
    }

    /** 要牌只认库里那份快照：这里直接往账户行摆一局确定的牌，走完 hit 再读回来对 */
    @Test
    void 要牌从库里的快照接着打() {
        BlackjackServiceImpl.SessionHand hand = new BlackjackServiceImpl.SessionHand();
        hand.setCards(new ArrayList<>(List.of("5H", "6C")));   // 11 点，补一张 2 稳定落在 13，既不爆也不到 21
        hand.setBet(50L);

        BlackjackServiceImpl.BlackjackSession seeded = new BlackjackServiceImpl.BlackjackSession();
        seeded.setShoe(new ArrayList<>(List.of("5H", "9D", "6C", "7S", "2H")));
        seeded.setShoeIndex(4);                                // 前四张已发出去
        seeded.setPlayerHands(new ArrayList<>(List.of(hand)));
        seeded.setActiveHandIndex(0);
        seeded.setDealerCards(new ArrayList<>(List.of("9D", "7S")));
        seeded.setBetPerHand(50L);
        seeded.setPhase("PLAYER_TURN");
        seeded.setFirstDecisionRound(true);
        account.setSessionJson(JSON.toJSONString(seeded));
        clearInvocations(accountMapper);

        service.hit(UID);

        BlackjackServiceImpl.BlackjackSession after =
                JSON.parseObject(account.getSessionJson(), BlackjackServiceImpl.BlackjackSession.class);
        assertThat(after.getShoeIndex()).isEqualTo(5);
        assertThat(after.getPlayerHands().get(0).getCards()).containsExactly("5H", "6C", "2H");
        assertThat(after.isFirstDecisionRound()).isFalse();    // 要过牌就关掉保险窗口
        verify(accountMapper, times(1)).updateById(any(BlackjackAccount.class));
    }

    /**
     * fastjson2 对 lombok 生成的 boolean 读写器有坑（{@code private boolean isDoubled} 这种命名尤其），
     * 掉一位就是加倍/保险白买。这里把两处布尔位置真再滚一圈。
     */
    @Test
    void 快照来回序列化不丢布尔位() {
        dealUntilInProgress();
        BlackjackServiceImpl.BlackjackSession restored =
                JSON.parseObject(account.getSessionJson(), BlackjackServiceImpl.BlackjackSession.class);

        restored.setInsuranceTaken(true);
        restored.setInsuranceBet(25L);
        restored.getPlayerHands().get(0).setDoubled(true);
        restored.getPlayerHands().get(0).setStood(true);

        BlackjackServiceImpl.BlackjackSession again =
                JSON.parseObject(JSON.toJSONString(restored), BlackjackServiceImpl.BlackjackSession.class);

        assertThat(again.isInsuranceTaken()).isTrue();
        assertThat(again.getInsuranceBet()).isEqualTo(25L);
        assertThat(again.getPlayerHands().get(0).isDoubled()).isTrue();
        assertThat(again.getPlayerHands().get(0).isStood()).isTrue();
        assertThat(again.getPlayerHands().get(0).isBusted()).isFalse();
        assertThat(again.getShoe()).isEqualTo(restored.getShoe());
    }

    /** 开局就自然 BJ 会当场结算（约 9%），本类只关心"局挂住"那条路，重开到有局为止 */
    private void dealUntilInProgress() {
        for (int i = 0; i < 30 && account.getSessionJson() == null; i++) {
            account.setChips(1000L);
            clearInvocations(accountMapper);
            service.bet(UID, 50L);
        }
        assertThat(account.getSessionJson()).isNotBlank();
    }
}

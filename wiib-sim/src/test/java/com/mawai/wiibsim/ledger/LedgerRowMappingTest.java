package com.mawai.wiibsim.ledger;

import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.enums.LedgerWallet.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 映射表是切面的核心配置，错一处就漏账或重复记账，单独测。
 * <p>
 * 多钱包 case 每一行都必须断言 balanceAfter：那几个 record 的组件全是 BigDecimal，
 * 取错组件（写成 r.frozenBalance() 而不是 r.balance()）编译和运行都不报错，
 * 只是把可用余额和冻结余额对调后写进账本。所以每个 case 的两个/三个新值刻意取不同的数——
 * 一对调 balanceAfter 断言就红。<b>改这些用例的人注意别把值改成一样的。</b>
 */
class LedgerRowMappingTest {

    private static final Long UID = 7L;

    @Test
    void 单钱包变动产出一行() {
        var rows = LedgerRowMapping.rowsOf("atomicUpdateBalance",
                new Object[]{UID, new BigDecimal("-300")}, new BigDecimal("700"));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().wallet()).isEqualTo(BALANCE);
        assertThat(rows.getFirst().delta()).isEqualByComparingTo("-300");
        assertThat(rows.getFirst().balanceAfter()).isEqualByComparingTo("700");
    }

    @Test
    void 冻结产出两行且两行相加为零() {
        var rows = LedgerRowMapping.rowsOf("atomicFreezeBalance",
                new Object[]{UID, new BigDecimal("400")},
                new UserMapper.BalanceFrozen(new BigDecimal("600"), new BigDecimal("400")));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).wallet()).isEqualTo(BALANCE);
        assertThat(rows.get(0).delta()).isEqualByComparingTo("-400");
        assertThat(rows.get(0).balanceAfter()).isEqualByComparingTo("600");   // 取错组件则变 400
        assertThat(rows.get(1).wallet()).isEqualTo(FROZEN);
        assertThat(rows.get(1).delta()).isEqualByComparingTo("400");
        assertThat(rows.get(1).balanceAfter()).isEqualByComparingTo("400");   // 取错组件则变 600
        // 冻结是钱包间搬家，总额不变
        assertThat(rows.get(0).delta().add(rows.get(1).delta())).isEqualByComparingTo("0");
    }

    /**
     * 解冻单独测：它的行序与冻结<b>相反</b>（先 FROZEN 后 BALANCE），上面那条用例保护不到它。
     * Task 4 的证伪实测已经证明过"改造后没有任何其他测试能发现解冻写反"，映射层同理。
     * 700/300 差得远，取错组件必红。
     */
    @Test
    void 解冻产出两行且行序与冻结相反() {
        var rows = LedgerRowMapping.rowsOf("atomicUnfreezeBalance",
                new Object[]{UID, new BigDecimal("100")},
                new UserMapper.BalanceFrozen(new BigDecimal("700"), new BigDecimal("300")));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).wallet()).isEqualTo(FROZEN);
        assertThat(rows.get(0).delta()).isEqualByComparingTo("-100");
        assertThat(rows.get(0).balanceAfter()).isEqualByComparingTo("300");   // 取错组件则变 700
        assertThat(rows.get(1).wallet()).isEqualTo(BALANCE);
        assertThat(rows.get(1).delta()).isEqualByComparingTo("100");
        assertThat(rows.get(1).balanceAfter()).isEqualByComparingTo("700");   // 取错组件则变 300
        assertThat(rows.get(0).delta().add(rows.get(1).delta())).isEqualByComparingTo("0");
    }

    @Test
    void 扣冻结入参为正记成负delta() {
        var rows = LedgerRowMapping.rowsOf("atomicDeductFrozenBalance",
                new Object[]{UID, new BigDecimal("400")}, new BigDecimal("0"));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().wallet()).isEqualTo(FROZEN);
        assertThat(rows.getFirst().delta()).isEqualByComparingTo("-400");
        assertThat(rows.getFirst().balanceAfter()).isEqualByComparingTo("0");
    }

    @Test
    void 划转两行差额即销毁的手续费() {
        var rows = LedgerRowMapping.rowsOf("atomicTransferToGame",
                new Object[]{UID, new BigDecimal("100"), new BigDecimal("99")},
                new UserMapper.BalanceGame(new BigDecimal("900"), new BigDecimal("99")));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).wallet()).isEqualTo(BALANCE);
        assertThat(rows.get(0).delta()).isEqualByComparingTo("-100");
        assertThat(rows.get(0).balanceAfter()).isEqualByComparingTo("900");   // 取错组件则变 99
        assertThat(rows.get(1).wallet()).isEqualTo(GAME);
        assertThat(rows.get(1).delta()).isEqualByComparingTo("99");
        assertThat(rows.get(1).balanceAfter()).isEqualByComparingTo("99");    // 取错组件则变 900
        // 差 1 元是手续费，被销毁——不记成任何钱包的 delta，否则不变量破
        assertThat(rows.get(0).delta().add(rows.get(1).delta())).isEqualByComparingTo("-1");
    }

    /**
     * 反向划转单独测：转出方换成 GAME、到账方换成 BALANCE，行序跟着反过来。
     * 250/1049 差得远，取错组件必红。
     */
    @Test
    void 反向划转转出方是游戏钱包() {
        var rows = LedgerRowMapping.rowsOf("atomicTransferToBalance",
                new Object[]{UID, new BigDecimal("50"), new BigDecimal("49")},
                new UserMapper.BalanceGame(new BigDecimal("1049"), new BigDecimal("250")));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).wallet()).isEqualTo(GAME);
        assertThat(rows.get(0).delta()).isEqualByComparingTo("-50");
        assertThat(rows.get(0).balanceAfter()).isEqualByComparingTo("250");    // 取错组件则变 1049
        assertThat(rows.get(1).wallet()).isEqualTo(BALANCE);
        assertThat(rows.get(1).delta()).isEqualByComparingTo("49");
        assertThat(rows.get(1).balanceAfter()).isEqualByComparingTo("1049");   // 取错组件则变 250
        assertThat(rows.get(0).delta().add(rows.get(1).delta())).isEqualByComparingTo("-1");
    }

    @Test
    void 现金流入一条SQL动三列产出三行() {
        var rows = LedgerRowMapping.rowsOf("atomicApplyCashInflow",
                new Object[]{UID, new BigDecimal("10"), new BigDecimal("50"), new BigDecimal("40")},
                new UserMapper.CashInflow(new BigDecimal("0"), new BigDecimal("100"), new BigDecimal("1040")));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).wallet()).isEqualTo(LOAN_INTEREST);
        assertThat(rows.get(0).delta()).isEqualByComparingTo("-10");
        assertThat(rows.get(0).balanceAfter()).isEqualByComparingTo("0");       // 三个新值互不相同，
        assertThat(rows.get(1).wallet()).isEqualTo(LOAN_PRINCIPAL);
        assertThat(rows.get(1).delta()).isEqualByComparingTo("-50");
        assertThat(rows.get(1).balanceAfter()).isEqualByComparingTo("100");     // 任意两个组件对调
        assertThat(rows.get(2).wallet()).isEqualTo(BALANCE);
        assertThat(rows.get(2).delta()).isEqualByComparingTo("40");
        assertThat(rows.get(2).balanceAfter()).isEqualByComparingTo("1040");    // 都会红
    }

    /**
     * 没借过钱的用户卖出到账：还息、还本两列纹丝没动，只该有余额一行。
     * 删掉 rowsOf 里那个 filter 会让本用例变回三行——账单上就是一排 0.00 占位。
     */
    @Test
    void 现金流入不记没变动的列() {
        var rows = LedgerRowMapping.rowsOf("atomicApplyCashInflow",
                new Object[]{UID, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2938.91")},
                new UserMapper.CashInflow(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("15655.71")));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().wallet()).isEqualTo(BALANCE);
        assertThat(rows.getFirst().delta()).isEqualByComparingTo("2938.91");
        assertThat(rows.getFirst().balanceAfter()).isEqualByComparingTo("15655.71");
    }

    /** 只还了息、没够着本金：中间那列没动，剩下两行 */
    @Test
    void 现金流入只记真正变动的列() {
        var rows = LedgerRowMapping.rowsOf("atomicApplyCashInflow",
                new Object[]{UID, new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("40")},
                new UserMapper.CashInflow(new BigDecimal("0"), new BigDecimal("500"), new BigDecimal("1040")));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).wallet()).isEqualTo(LOAN_INTEREST);
        assertThat(rows.get(0).delta()).isEqualByComparingTo("-10");
        assertThat(rows.get(1).wallet()).isEqualTo(BALANCE);
        assertThat(rows.get(1).delta()).isEqualByComparingTo("40");
    }

    /**
     * 守卫：给 UserMapper 加了 atomic* 方法就必须补映射，否则那笔钱静默不入账。
     * <p>
     * 漏一个方法是双重静默——rowsOf 返空 List、切面 isEmpty 直接 return，日志里连 WARN 都没有。
     * 而真跑用例里的 11 次调用和 17 行断言全是硬编码，对"第 12 个方法"完全无感。
     * 这条反射断言让"加方法不补映射"在测试期就红。
     * <p>
     * 用相等而不是包含：少了=漏映射，多了=映射表留着已删方法的死 case，两个方向都要报。
     */
    @Test
    void 新增atomic方法必须补映射() {
        Set<String> declared = Arrays.stream(UserMapper.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("atomic"))
                .collect(Collectors.toSet());

        assertThat(declared).isEqualTo(LedgerRowMapping.HANDLED_METHODS);
    }
}

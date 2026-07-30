package com.mawai.wiibsim.ledger;

import com.mawai.wiibcommon.enums.LedgerWallet;
import com.mawai.wiibsim.mapper.UserMapper;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.mawai.wiibcommon.enums.LedgerWallet.*;

/**
 * mapper 方法 → 账本行的映射。铁律：一条 SQL 影响几个钱包字段就产出几行，
 * 绝不为了"把手续费单列"而拆——拆了第一行的 balanceAfter 就是编造的。
 */
public final class LedgerRowMapping {

    public record Row(LedgerWallet wallet, BigDecimal delta, BigDecimal balanceAfter) {}

    /**
     * 本表处理的 mapper 方法名，必须与下面 switch 的 case 一一对应。
     * <p>
     * 存在的唯一理由是守卫"新增 atomic* 方法忘了补映射"：switch 的 case 标签反射拿不到，
     * 而漏一个方法的后果是钱动了账不记（本集合认不出→入口闸门返空 List、切面 isEmpty 跳过，
     * 两道静默），靠真跑用例里硬编码的 11 次调用和 17 行断言是发现不了新方法的。
     * {@code LedgerRowMappingTest.新增atomic方法必须补映射()} 拿 UserMapper 的
     * atomic* 方法名集合与本集合对比，加了方法不补映射就编译期加、测试期红。
     * <p>
     * 反过来"名字在集合里却没有 case"由 default 分支直接抛异常兜住，不许静默返空。
     */
    public static final Set<String> HANDLED_METHODS = Set.of(
            "atomicUpdateBalance",
            "atomicSettleBalance",
            "atomicUpdateGameBalance",
            "atomicAddMarginLoanPrincipal",
            "atomicAccrueInterest",
            "atomicDeductFrozenBalance",
            "atomicFreezeBalance",
            "atomicUnfreezeBalance",
            "atomicTransferToGame",
            "atomicTransferToBalance",
            "atomicApplyCashInflow");

    private LedgerRowMapping() {}

    /**
     * @param method mapper 方法名
     * @param args   调用参数（args[0]=userId，args[1]=amount，划转另有 net）
     * @param ret    返回值，已由调用方保证非 null（null 表示 SQL 没改成，不记账）
     */
    public static List<Row> rowsOf(String method, Object[] args, Object ret) {
        // 不认识的方法不记账。正常不会走到：pointcut 只切 UserMapper.atomic*，而那批方法
        // 由 HANDLED_METHODS 的守卫测试保证全在表内。挡在这里是为了让 rowsOf 对任意入参都是全函数。
        if (!HANDLED_METHODS.contains(method)) return List.of();

        BigDecimal amount = requireAmount(method, args);

        return switch (method) {
            // 单钱包：delta 即入参，balanceAfter 即返回值
            case "atomicUpdateBalance", "atomicSettleBalance" ->
                    List.of(new Row(BALANCE, amount, (BigDecimal) ret));

            case "atomicUpdateGameBalance" ->
                    List.of(new Row(GAME, amount, (BigDecimal) ret));

            case "atomicAddMarginLoanPrincipal" ->
                    List.of(new Row(LOAN_PRINCIPAL, amount, (BigDecimal) ret));

            case "atomicAccrueInterest" ->
                    List.of(new Row(LOAN_INTEREST, amount, (BigDecimal) ret));

            // 扣冻结：入参为正，实际是减少
            case "atomicDeductFrozenBalance" ->
                    List.of(new Row(FROZEN, amount.negate(), (BigDecimal) ret));

            // 冻结：可用减、冻结增
            case "atomicFreezeBalance" -> {
                var r = (UserMapper.BalanceFrozen) ret;
                yield List.of(new Row(BALANCE, amount.negate(), r.balance()),
                              new Row(FROZEN, amount, r.frozenBalance()));
            }

            // 解冻：冻结减、可用增
            case "atomicUnfreezeBalance" -> {
                var r = (UserMapper.BalanceFrozen) ret;
                yield List.of(new Row(FROZEN, amount.negate(), r.frozenBalance()),
                              new Row(BALANCE, amount, r.balance()));
            }

            // 划转：转出方全额扣 amount，到账方只得 net，差额即手续费（销毁，不记成任何钱包的 delta）
            case "atomicTransferToGame" -> {
                var r = (UserMapper.BalanceGame) ret;
                BigDecimal net = (BigDecimal) args[2];
                yield List.of(new Row(BALANCE, amount.negate(), r.balance()),
                              new Row(GAME, net, r.gameBalance()));
            }

            case "atomicTransferToBalance" -> {
                var r = (UserMapper.BalanceGame) ret;
                BigDecimal net = (BigDecimal) args[2];
                yield List.of(new Row(GAME, amount.negate(), r.gameBalance()),
                              new Row(BALANCE, net, r.balance()));
            }

            // 现金流入：一条 SQL 最多动三列 → 最多三行。
            // 这条 SQL 的语义是"先还息、再还本、剩下入余额"，没欠债时前两步是 no-op，
            // 记进去就是一排 0.00 占着账单（没借过钱的用户每次卖出到账都白得两行）。
            // 过滤掉 delta=0 不违反上面那条铁律——"没动这一列"本来就不该有这一列的行。
            // 三行不会同时为 0：调用方 applyCashInflow 已挡掉 amount<=0，
            // 而三个 delta 之和恒等于 amount，必有一行非 0。
            case "atomicApplyCashInflow" -> {
                var r = (UserMapper.CashInflow) ret;
                BigDecimal paidInterest = (BigDecimal) args[1];
                BigDecimal paidPrincipal = (BigDecimal) args[2];
                BigDecimal credited = (BigDecimal) args[3];
                yield Stream.of(new Row(LOAN_INTEREST, paidInterest.negate(), r.marginInterestAccrued()),
                                new Row(LOAN_PRINCIPAL, paidPrincipal.negate(), r.marginLoanPrincipal()),
                                new Row(BALANCE, credited, r.balance()))
                        .filter(row -> row.delta().signum() != 0)
                        .toList();
            }

            // 名字进了 HANDLED_METHODS 却没写 case：静默返空就是钱动了账不记，必须炸
            default -> throw new IllegalStateException(
                    "HANDLED_METHODS 声明了 " + method + " 却没有对应的映射 case");
        };
    }

    /**
     * 11 个资金方法的 args[1] 都是金额。取不到说明有人加了签名不符的方法——
     * 与其让下面的 amount.negate() 抛一个看不出所以然的 NPE，不如当场说清是哪个方法哪个参数。
     */
    private static BigDecimal requireAmount(String method, Object[] args) {
        if (args.length > 1 && args[1] instanceof BigDecimal amount) return amount;
        throw new IllegalStateException(
                "资金方法 " + method + " 的 args[1] 不是金额: " + Arrays.toString(args));
    }
}

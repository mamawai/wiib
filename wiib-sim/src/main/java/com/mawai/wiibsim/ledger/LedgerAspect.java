package com.mawai.wiibsim.ledger;

import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibcommon.enums.LedgerWallet;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 资金记账切面。主战场是 UserMapper 的原子资金方法——那是 user 表五个钱包列唯一的出口
 * （全仓 grep 过：UPDATE "user" SET 只出现在 UserMapper），所以不可能漏账。
 * <p>
 * 另有一个出口不穿过 user 表：逐仓资金费在余额扣不动时直接吃仓位保证金，
 * 由 {@link #positionMarginMutation()} 单独切（见该方法注释）。
 * <p>
 * 切面切不到的还剩两类，只能由业务代码显式补记，见 UserServiceImpl#recordInitialGrant
 * （建号走 INSERT）与 BankruptcyServiceImpl（爆仓/破产恢复是整体覆写 UPDATE，方法名不是 atomic*）。
 * <p>
 * 记账 INSERT 刻意不 catch：吞掉异常会让事务不回滚，余额变了账没记，
 * 不变量当场破裂且无法自愈。INSERT 本地表失败本身就是系统级故障，让交易失败是对的。
 */
@Slf4j
@Aspect
@Component
@Order(2)
@RequiredArgsConstructor
public class LedgerAspect {

    private final UserLedgerMapper ledgerMapper;

    /** 维护方法级语义栈 */
    @Around("@annotation(ledger)")
    public Object aroundLedgerMethod(ProceedingJoinPoint point, Ledger ledger) throws Throwable {
        LedgerCtx.push(ledger.value());
        try {
            return point.proceed();
        } finally {
            LedgerCtx.pop();   // finally 保证异常路径也弹栈，避免线程复用串味
        }
    }

    /**
     * 资金出口：UserMapper 的原子资金方法。
     * 抽成命名 pointcut 是因为下面落账与丢标注两条 advice 必须切在<b>完全相同</b>的一组方法上——
     * 各写一份表达式，哪天只改了一边，另一条路径的标注就又开始泄漏。
     */
    @Pointcut("execution(* com.mawai.wiibsim.mapper.UserMapper.atomic*(..))")
    void walletMutation() {}

    /** 资金变动落账。返回 null 表示 SQL 条件不满足、没改成，不记 */
    @AfterReturning(pointcut = "walletMutation()", returning = "ret")
    public void recordUserWallet(JoinPoint point, Object ret) {
        // 【取标注必须是第一句，不能等到 ret != null 之后】
        // 一次性标注的语义是"标给下一次资金调用"，那次调用无论成功还是返 null 都算把它消费掉了。
        // ret == null 不是异常路径而是设计上的正常分支（资金费支付方就是靠返 null 判定余额不够、
        // 转去扣仓位保证金），另有约 10 个调用点直接丢弃返回值。放在判空之后取，
        // 这些路径的 mark 就泄漏到再下一笔上——资金费是单线程 for 循环逐仓位跑的，
        // 泄漏的 mark 会带着上一个仓位的 refType/refId 安到下一个用户头上，错标比无标更难查。
        LedgerCtx.Mark mark = LedgerCtx.takeMark();
        if (ret == null) return;

        String method = point.getSignature().getName();
        Object[] args = point.getArgs();
        var rows = LedgerRowMapping.rowsOf(method, args, ret);
        if (rows.isEmpty()) return;

        Long userId = (Long) args[0];
        LedgerBizType type = mark != null ? mark.type() : LedgerCtx.currentType();
        String remark = null;

        if (type == null) {
            type = LedgerBizType.UNKNOWN;
            remark = callerOf();
            log.warn("[Ledger] 资金变动无语义标注 userId={} method={} caller={}", userId, method, remark);
        }

        for (var row : rows) {
            UserLedger entry = new UserLedger();
            entry.setUserId(userId);
            entry.setWallet(row.wallet());
            entry.setBizType(type);
            entry.setDelta(row.delta());
            entry.setBalanceAfter(row.balanceAfter());
            entry.setRefType(mark != null ? mark.refType() : null);
            entry.setRefId(mark != null ? mark.refId() : null);
            entry.setSymbol(LedgerCtx.currentSymbol());
            entry.setRemark(remark);
            ledgerMapper.insert(entry);
        }
    }

    /**
     * 第二个资金出口：逐仓资金费在余额扣不动时直接吃仓位保证金。这笔钱不穿过 user 表，
     * 是 UserMapper 之外唯一需要记账的资金出口。
     * <p>
     * 切得这么窄（只 atomicDeductFundingFee*）是刻意的：FuturesPositionMapper 上其余的
     * atomicAddMargin/atomicReduceMargin 都是"余额↔保证金搬家"，已经在 BALANCE 侧记过一笔，
     * 再记一次就是重复。所以 POSITION_MARGIN 这个钱包只装资金费这两笔，也因此不参与不变量对账。
     */
    @Pointcut("execution(* com.mawai.wiibsim.mapper.FuturesPositionMapper.atomicDeductFundingFee*(..))")
    void positionMarginMutation() {}

    /**
     * 资金费扣保证金落账。返回 null 表示 SQL 没改成（保证金不够 / 仓位已关），不记。
     * <p>
     * userId 和扣款额都由调用点的 {@link LedgerCtx#markPositionFee} 带进来——这两条 SQL 的参数里
     * 只有 positionId（"扣光"那条连金额都没有），切面自己猜不出来。
     */
    @AfterReturning(pointcut = "positionMarginMutation()", returning = "marginAfter")
    public void recordFundingFeeFromMargin(JoinPoint point, BigDecimal marginAfter) {
        // 【取标注必须是第一句】理由同 recordUserWallet，而且这里更险：保证金不够时返 null 是正常分支
        // （紧接着还有"扣光全部保证金"那一枪），标注留着就会漏到下一个仓位——资金费是单线程 for 循环
        // 逐仓位跑的，下一个仓位若是全仓，它的 CROSS_SETTLE 会被漏下来的标注顶掉，
        // 连 userId/refId 都还是上一个仓位的，等于把 A 的语义安到 B 的账本上。
        LedgerCtx.Mark mark = LedgerCtx.takeMark();
        if (marginAfter == null) return;

        if (mark == null || mark.userId() == null || mark.amount() == null) {
            // 只有 markPositionFee 能提供 userId 和扣款额，缺了就记不成一条完整流水。
            // 这里跳过不抛：POSITION_MARGIN 不参与不变量对账，漏一条只影响账单完整性，
            // 不会让账实不符——与"ledger INSERT 失败必须抛"那条约束不冲突。
            log.warn("[Ledger] 资金费扣保证金缺少 markPositionFee 标注，跳过记账 args={}",
                    java.util.Arrays.toString(point.getArgs()));
            return;
        }

        UserLedger entry = new UserLedger();
        entry.setUserId(mark.userId());
        entry.setWallet(LedgerWallet.POSITION_MARGIN);
        entry.setBizType(LedgerBizType.FUNDING_FEE_FROM_MARGIN);
        entry.setDelta(mark.amount().negate());
        entry.setBalanceAfter(marginAfter);   // 同条 UPDATE 的 RETURNING margin，不是事后补查
        entry.setRefType(mark.refType());
        entry.setRefId(mark.refId());         // 真正的仓位 id
        entry.setSymbol(LedgerCtx.currentSymbol());
        entry.setRemark("资金费扣保证金");
        ledgerMapper.insert(entry);
    }

    /**
     * SQL 抛异常（PG 死锁、锁超时、约束冲突、连接断）时把标注丢掉，<b>不记账</b>——抛异常意味着这笔钱没动。
     * <p>
     * 为什么两条路径都得清：@AfterReturning 在抛异常时整条 advice 根本不执行，
     * ONE_SHOT 就保持着已 set 的状态留在线程上。Tomcat/池化线程复用后，
     * 下一个请求里第一笔没标注的资金变动会直接继承上一个请求的 bizType/refType/refId。
     * 这不是"少记一笔"，是把 A 用户的语义安到 B 用户账本上，比无标注难查得多。
     * <p>
     * 用 @AfterThrowing 而不是把落账整条改 @Around：两条 advice 各管一件事、语义直白，
     * 而"漏改一边"这个唯一风险已经由共享的 walletMutation() pointcut 消掉了。
     * 删掉本方法会让 LedgerAspectTest.SQL抛异常时标注必须被丢弃 变红。
     * <p>
     * 两个 pointcut 都得管：资金费扣保证金那条抛了同样会留标注，而它带着 userId，
     * 漏到下一笔上就是把这个用户的资金费记到另一个用户账上。
     */
    @AfterThrowing("walletMutation() || positionMarginMutation()")
    public void discardMarkOnFailure() {
        LedgerCtx.takeMark();
    }

    /** 兜底记录调用来源，方便事后补语义 */
    private static String callerOf() {
        return StackWalker.getInstance()
                .walk(s -> s.map(StackWalker.StackFrame::getClassName)
                        .filter(c -> c.startsWith("com.mawai.wiibsim.service"))
                        .findFirst()
                        .map(c -> c.substring(c.lastIndexOf('.') + 1))
                        .orElse("unknown"));
    }
}

package com.mawai.wiibquant.agent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibquant.mapper.AiTraderPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 交易计划存取（键：trader+round+symbol+side）。单 trader 的唤醒是串行的（调度层抢占互斥），
 * select-then-write 无并发问题，不需要 ON CONFLICT。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderPlanStore {

    private final AiTraderPlanMapper mapper;

    public static String key(String symbol, String side) {
        return symbol + "|" + side;
    }

    /** 修订追加：计划的任何修改一律留痕带理由（回注给下轮无记忆的模型看）；不动计划本体的原始快照字段。 */
    public static void appendRevision(AiTraderPlan plan, long time, String type, String change, String reason) {
        JSONArray arr = plan.getRevisionsJson() == null || plan.getRevisionsJson().isBlank()
                ? new JSONArray() : JSON.parseArray(plan.getRevisionsJson());
        arr.add(new JSONObject().fluentPut("time", time).fluentPut("type", type)
                .fluentPut("change", change).fluentPut("reason", reason));
        plan.setRevisionsJson(arr.toJSONString());
    }

    public AiTraderPlan find(long traderId, int roundNo, String symbol, String side) {
        return mapper.selectOne(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, traderId)
                .eq(AiTraderPlan::getRoundNo, roundNo)
                .eq(AiTraderPlan::getSymbol, symbol)
                .eq(AiTraderPlan::getSide, side));
    }

    /**
     * 开仓/加仓成交或限价挂出即落计划。isAddOn=true（同币同向已有持仓）走加仓覆盖：新论点上位，
     * 旧论点进修订历史（模型下单前已在提示词里看过旧计划，知情覆盖），持有时长按最初开仓算。
     * isAddOn=false 但同键旧计划还在＝同轮内平掉后重开（懒清理只在唤醒开头跑）：这是独立新仓
     * 不是加仓——旧计划已完成使命删掉，仓龄从新仓起算，修订史不继承（仓龄诚实）。
     */
    public void upsert(AiTraderPlan plan, boolean isAddOn) {
        AiTraderPlan old = find(plan.getTraderId(), plan.getRoundNo(), plan.getSymbol(), plan.getSide());
        if (old == null) {
            mapper.insert(plan);
            return;
        }
        if (!isAddOn) {
            mapper.deleteById(old.getId());
            mapper.insert(plan);
            log.info("[TraderPlan] 同轮重开覆盖旧计划 traderId={} {} {}",
                    plan.getTraderId(), plan.getSymbol(), plan.getSide());
            return;
        }
        long revisedAt = plan.getOpenedWakeTime() == null ? 0 : plan.getOpenedWakeTime();
        plan.setId(old.getId());
        plan.setOpenedWakeTime(old.getOpenedWakeTime());
        plan.setRevisionsJson(old.getRevisionsJson());
        appendRevision(plan, revisedAt, "加仓",
                "旧论点[" + old.getPlayType() + " / " + old.getInvalidationCondition() + "]被新论点覆盖",
                plan.getSignalsUsed());
        mapper.updateById(plan);
    }

    /** 止损/止盈移动的修订落库：只追加历史，原始快照字段不动（当前生效单在 sim 仓位上）。 */
    public void revise(AiTraderPlan plan, long time, String type, String change, String reason) {
        appendRevision(plan, time, type, change, reason);
        mapper.updateById(plan);
    }

    public List<AiTraderPlan> list(long traderId, int roundNo) {
        return mapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, traderId)
                .eq(AiTraderPlan::getRoundNo, roundNo));
    }

    /**
     * 懒清理：计划的 (symbol|side) 既无持仓也无挂单 → 止损/止盈/主动平/撤单殊途同归，计划已完成使命。
     * 返回仍存活的计划（清理与查询一次唤醒只跑一趟）。
     */
    public List<AiTraderPlan> cleanupStale(long traderId, int roundNo, Set<String> liveKeys) {
        List<AiTraderPlan> plans = list(traderId, roundNo);
        return plans.stream().filter(p -> {
            if (liveKeys.contains(key(p.getSymbol(), p.getSide()))) {
                return true;
            }
            mapper.deleteById(p.getId());
            log.info("[TraderPlan] 清理已了结计划 traderId={} {} {}", traderId, p.getSymbol(), p.getSide());
            return false;
        }).toList();
    }

    /** 重置开新局：旧局计划随旧账户一并作废。 */
    /** 只删指定局：历史局的计划是那局决策的公开凭证，不能跟着一起清 */
    public void deleteRound(long traderId, int roundNo) {
        mapper.delete(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, traderId)
                .eq(AiTraderPlan::getRoundNo, roundNo));
    }
}

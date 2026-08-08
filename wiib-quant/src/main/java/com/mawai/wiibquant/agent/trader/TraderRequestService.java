package com.mawai.wiibquant.agent.trader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderRequest;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import com.mawai.wiibquant.mapper.AiTraderRequestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 加仓/减仓待确认请求：allowSelfAdd/allowSelfReduce 关掉时，模型的工具调用转成这里一行。
 * 落库即返回不阻塞——本轮唤醒该干的其余动作照干，请求结果下一轮才在账户状态里露面。
 * 批准走市价即时成交：卡片上同时给请求时价与实时价，价格跑没跑掉由主人自己判断，不设过期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraderRequestService {

    private final AiTraderRequestMapper requestMapper;
    private final AiTraderMapper traderMapper;
    private final SimTradeClient simTradeClient;
    private final TraderPlanStore planStore;

    /**
     * 模型侧提交请求，返回给模型看的中文回执。
     * 同仓位同类型至多一条待确认（DB 部分唯一索引兜底）：模型每轮看仓位没动会反复提，不去重就堆满卡片。
     */
    public String submit(AiTraderRequest req) {
        req.setStatus(AiTraderRequest.STATUS_PENDING);
        try {
            requestMapper.insert(req);
        } catch (DuplicateKeyException e) {
            return "该仓位已有一条同类型请求在等主人确认，不要重复提交——等下一轮看账户状态里的结果";
        }
        return AiTraderRequest.TYPE_ADD.equals(req.getType())
                ? "加仓请求已提交给主人确认，本轮不会成交。继续做你该做的其余判断，结果下一轮揭晓"
                : "减仓请求已提交给主人确认，本轮不会成交。你的止损单仍在生效，风险有保护";
    }

    /** 回注提示词用：本局待确认的请求，模型看到就别重复提。 */
    public List<AiTraderRequest> pendingOf(long traderId, int roundNo) {
        return requestMapper.selectList(new LambdaQueryWrapper<AiTraderRequest>()
                .eq(AiTraderRequest::getTraderId, traderId)
                .eq(AiTraderRequest::getRoundNo, roundNo)
                .eq(AiTraderRequest::getStatus, AiTraderRequest.STATUS_PENDING)
                .orderByAsc(AiTraderRequest::getId));
    }

    /**
     * 回注提示词用：主人已处理但还没告诉过模型的请求（批/拒+执行结果）。
     * 反馈闭环的最后一环——不注模型只能从仓位变化倒猜自己的请求是什么下场。
     */
    public List<AiTraderRequest> decidedUnnotified(long traderId, int roundNo) {
        return requestMapper.selectList(new LambdaQueryWrapper<AiTraderRequest>()
                .eq(AiTraderRequest::getTraderId, traderId)
                .eq(AiTraderRequest::getRoundNo, roundNo)
                .ne(AiTraderRequest::getStatus, AiTraderRequest.STATUS_PENDING)
                .eq(AiTraderRequest::getNotified, false)
                .orderByAsc(AiTraderRequest::getId));
    }

    /** 结果已注入本轮提示词，置已通知——每个结果只说一次，不当陈年新闻反复念。 */
    public void markNotified(List<AiTraderRequest> requests) {
        if (requests.isEmpty()) {
            return;
        }
        requestMapper.update(null, new LambdaUpdateWrapper<AiTraderRequest>()
                .in(AiTraderRequest::getId, requests.stream().map(AiTraderRequest::getId).toList())
                .set(AiTraderRequest::getNotified, true));
    }

    /** 主人页面用：我的全部待确认请求。 */
    public List<AiTraderRequest> myPending(long userId) {
        AiTrader t = traderMapper.selectOne(new LambdaQueryWrapper<AiTrader>()
                .eq(AiTrader::getUserId, userId));
        return t == null ? List.of() : pendingOf(t.getId(), t.getRoundNo());
    }

    /** 主人拒绝：不执行，留档。 */
    public String reject(long userId, long requestId) {
        AiTraderRequest r = ownedPending(userId, requestId);
        if (r == null) {
            return "请求不存在或已处理";
        }
        r.setStatus(AiTraderRequest.STATUS_REJECTED);
        r.setDecidedAt(LocalDateTime.now());
        requestMapper.updateById(r);
        return null;
    }

    /**
     * 主人同意：市价即时执行。
     * 执行前重查仓位——从模型提交到主人点同意之间，仓位可能已被止损带走，这时不能静默吞掉。
     */
    public String approve(long userId, long requestId) {
        AiTraderRequest r = ownedPending(userId, requestId);
        if (r == null) {
            return "请求不存在或已处理";
        }
        AiTrader t = traderMapper.selectById(r.getTraderId());
        if (t == null || t.getSimUserId() == null) {
            return "trader 账户不可用";
        }
        r.setStatus(AiTraderRequest.STATUS_APPROVED);
        r.setDecidedAt(LocalDateTime.now());
        try {
            FuturesPositionDTO pos = simTradeClient.getAllPositions(t.getSimUserId()).stream()
                    .filter(p -> p.getId() != null && p.getId().equals(r.getPositionId()))
                    .findFirst().orElse(null);
            if (pos == null) {
                r.setExecutedResult("仓位已不存在（多半被止损/止盈带走），未执行");
                requestMapper.updateById(r);
                return null;
            }
            boolean isAdd = AiTraderRequest.TYPE_ADD.equals(r.getType());
            // 仓位可能已被部分平掉，减仓量先钳到现有量；回执照这个实际量写——
            // 这条回执会原样注入下一轮提示词当事实，虚报数字模型就按错的仓位算后面一切
            BigDecimal executedQty = isAdd ? r.getQuantity() : r.getQuantity().min(pos.getQuantity());
            FuturesOrderResponse resp = isAdd ? doAdd(t, r, pos) : doReduce(r, t, executedQty);
            r.setExecutedResult("已成交 " + executedQty.stripTrailingZeros().toPlainString()
                    + " @订单" + resp.getOrderId());
            revisePlan(t, r, pos);
        } catch (Exception e) {
            // 余额不足/步长不合规等：写进结果给主人看，不吞
            r.setExecutedResult("执行失败：" + e.getMessage());
            log.warn("[TraderRequest] 批准执行失败 requestId={} msg={}", requestId, e.getMessage());
        }
        requestMapper.updateById(r);
        return null;
    }

    /**
     * 批准加仓：必须给新增部分补挂保护单，价格照抄仓位现有档。
     * sim 的 mergeSlList/mergeTpList 见 added 为空就整段返回 null＝不改库——不带保护单的话，
     * 仓位翻倍而覆盖量原地不动，新增那部分直接裸奔。合并后 旧覆盖+加仓量=新全仓量，正好补齐。
     * 不用模型下单时给的止损价：加仓请求是异步等主人点头的，那会儿报的价到成交时早不合时宜；
     * 想收紧止损，加仓后单独调 set_stop_loss（全仓覆盖）才是正路。
     */
    private FuturesOrderResponse doAdd(AiTrader t, AiTraderRequest r, FuturesPositionDTO pos) {
        FuturesOpenRequest open = new FuturesOpenRequest();
        open.setSymbol(r.getSymbol());
        open.setSide(r.getSide());
        // 与 TradeTools 一致：全仓显式声明，权益口径依赖这个事实
        open.setMarginMode(FuturesPosition.CROSS);
        open.setOrderType("MARKET");
        open.setQuantity(r.getQuantity());
        open.setLeverage(r.getLeverage());
        open.setMemo("ai_trader:APPROVED_ADD");
        boolean isLong = "LONG".equals(pos.getSide());
        BigDecimal stop = TradeGuard.extremePrice(prices(pos.getStopLosses(), FuturesStopLoss::getPrice), isLong);
        if (stop != null) {
            FuturesOpenRequest.StopLoss sl = new FuturesOpenRequest.StopLoss();
            sl.setPrice(stop);
            sl.setQuantity(r.getQuantity());
            open.setStopLosses(List.of(sl));
        }
        // 止盈非必挂：原仓没有目标位就别硬造一个
        BigDecimal target = TradeGuard.extremePrice(prices(pos.getTakeProfits(), FuturesTakeProfit::getPrice), isLong);
        if (target != null) {
            FuturesOpenRequest.TakeProfit tp = new FuturesOpenRequest.TakeProfit();
            tp.setPrice(target);
            tp.setQuantity(r.getQuantity());
            open.setTakeProfits(List.of(tp));
        }
        return simTradeClient.openPosition(t.getSimUserId(), open);
    }

    private static <T> List<BigDecimal> prices(List<T> orders, java.util.function.Function<T, BigDecimal> price) {
        return orders == null ? List.of() : orders.stream().map(price).toList();
    }

    /** qty 是调用方已按现有仓位钳过的实际平仓量。 */
    private FuturesOrderResponse doReduce(AiTraderRequest r, AiTrader t, BigDecimal qty) {
        FuturesCloseRequest close = new FuturesCloseRequest();
        close.setPositionId(r.getPositionId());
        close.setQuantity(qty);
        close.setOrderType("MARKET");
        return simTradeClient.closePosition(t.getSimUserId(), close);
    }

    /** 批准执行也算对计划的修改，留痕带理由——公开修订历史里要看得出"这笔是主人点头的"。 */
    private void revisePlan(AiTrader t, AiTraderRequest r, FuturesPositionDTO pos) {
        try {
            var plan = planStore.find(t.getId(), r.getRoundNo(), pos.getSymbol(), pos.getSide());
            if (plan != null) {
                planStore.revise(plan, r.getWakeTime(),
                        AiTraderRequest.TYPE_ADD.equals(r.getType()) ? "加仓" : "减仓",
                        r.getQuantity().stripTrailingZeros().toPlainString(),
                        "主人确认：" + r.getReason());
            }
        } catch (Exception e) {
            log.warn("[TraderRequest] 计划修订失败 requestId={} msg={}", r.getId(), e.getMessage());
        }
    }

    /** 取自己的待确认请求；别人的、已处理的一律当不存在。 */
    private AiTraderRequest ownedPending(long userId, long requestId) {
        AiTraderRequest r = requestMapper.selectById(requestId);
        if (r == null || !AiTraderRequest.STATUS_PENDING.equals(r.getStatus())) {
            return null;
        }
        AiTrader t = traderMapper.selectById(r.getTraderId());
        return t != null && t.getUserId() != null && t.getUserId() == userId ? r : null;
    }
}

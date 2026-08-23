package com.mawai.wiibquant.agent.trader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibquant.agent.i18n.UserLangResolver;
import com.mawai.wiibquant.agent.i18n.PromptCatalog;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.entity.AiTraderRequest;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibquant.external.sim.SimOrderRetry;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import com.mawai.wiibquant.mapper.AiTraderRequestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
    /** 只给 approve/reject 用：那两条是点按钮当场看的话 */
    private final MessageCatalog messages;
    /** 工具回执与 executed_result：都会原样注入下一轮提示词当事实，跟 trader 主人的语言 */
    private final PromptCatalog prompts;
    private final UserLangResolver langResolver;

    /**
     * 模型侧提交请求，返回给模型看的中文回执。
     * 同仓位同类型至多一条待确认（DB 部分唯一索引兜底）：模型每轮看仓位没动会反复提，不去重就堆满卡片。
     */
    public String submit(AiTraderRequest req, AgentLang lang) {
        req.setStatus(AiTraderRequest.STATUS_PENDING);
        try {
            requestMapper.insert(req);
        } catch (DuplicateKeyException e) {
            return prompts.get(lang, "trader.receipt.duplicate");
        }
        return prompts.get(lang, AiTraderRequest.TYPE_ADD.equals(req.getType())
                ? "trader.receipt.submittedAdd" : "trader.receipt.submittedReduce");
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

    /** 主人拒绝：不执行，留档。同样走抢状态——否则"先同意已下单、再点拒绝"会把状态改花。 */
    public String reject(long userId, long requestId) {
        if (ownedPending(userId, requestId) == null) {
            return messages.get("trader.request.notFoundOrHandled");
        }
        return claim(requestId, AiTraderRequest.STATUS_REJECTED) ? null : messages.get("trader.request.notFoundOrHandled");
    }

    /**
     * 主人同意：市价即时执行。
     * 先抢状态再执行：双击"同意"或两个标签页同点时，read-then-execute 两边都能读到 PENDING
     * 各下一笔市价单把仓位翻倍——而加仓路径本就豁免保证金区间校验，直接越过主人配的上限。
     * 执行前重查仓位——从模型提交到主人点同意之间，仓位可能已被止损带走，这时不能静默吞掉。
     */
    public String approve(long userId, long requestId) {
        AgentLang lang = langResolver.of(userId);
        AiTraderRequest r = ownedPending(userId, requestId);
        if (r == null) {
            return messages.get("trader.request.notFoundOrHandled");
        }
        AiTrader t = traderMapper.selectById(r.getTraderId());
        if (t == null || t.getSimUserId() == null) {
            return messages.get("trader.request.accountUnavailable");
        }
        // 抢不到就是别人已经处理过了：直接走人，一笔单都不许下
        if (!claim(requestId, AiTraderRequest.STATUS_APPROVED)) {
            return messages.get("trader.request.notFoundOrHandled");
        }
        try {
            FuturesPositionDTO pos = simTradeClient.getAllPositions(t.getSimUserId()).stream()
                    .filter(p -> p.getId() != null && p.getId().equals(r.getPositionId()))
                    .findFirst().orElse(null);
            if (pos == null) {
                writeResult(r, prompts.get(lang, "trader.receipt.positionGone"));
                return null;
            }
            boolean isAdd = AiTraderRequest.TYPE_ADD.equals(r.getType());
            // 仓位可能已被部分平掉，减仓量先钳到现有量；回执照这个实际量写——
            // 这条回执会原样注入下一轮提示词当事实，虚报数字模型就按错的仓位算后面一切
            BigDecimal executedQty = isAdd ? r.getQuantity() : r.getQuantity().min(pos.getQuantity());
            FuturesOrderResponse resp = SimOrderRetry.send(
                    () -> isAdd ? doAdd(t, r, pos) : doReduce(r, t, executedQty));
            writeResult(r, prompts.get(lang, "trader.receipt.filled", Map.of(
                    "qty", executedQty.stripTrailingZeros().toPlainString(),
                    "orderId", resp.getOrderId())));
            revisePlan(t, r, pos);
        } catch (SimOrderRetry.UnknownOutcome e) {
            // 读超时后重发也问不到结果：这笔很可能已经在 sim 成交了。写"执行失败"会被原样注入
            // 下一轮提示词当事实，模型照着一个不存在的仓位往下算；不修订计划同理，宁可留空
            writeResult(r, prompts.get(lang, "trader.receipt.unknown"));
            log.warn("[TraderRequest] 批准执行结果未知 requestId={} msg={}", requestId, e.getCause().getMessage());
        } catch (Exception e) {
            // 余额不足/步长不合规等：写进结果给主人看，不吞
            writeResult(r, prompts.get(lang, "trader.receipt.failed", Map.of("reason", String.valueOf(e.getMessage()))));
            log.warn("[TraderRequest] 批准执行失败 requestId={} msg={}", requestId, e.getMessage());
        }
        return null;
    }

    /** 抢状态：PENDING→目标状态的条件更新，只有影响到行的那一次返回 true，并发的另一次落空。 */
    private boolean claim(long requestId, String status) {
        return requestMapper.update(null, new LambdaUpdateWrapper<AiTraderRequest>()
                .eq(AiTraderRequest::getId, requestId)
                .eq(AiTraderRequest::getStatus, AiTraderRequest.STATUS_PENDING)
                .set(AiTraderRequest::getStatus, status)
                .set(AiTraderRequest::getDecidedAt, LocalDateTime.now())) > 0;
    }

    /** 执行结果单列回写：状态已由 claim 定死，整行 updateById 会把并发改动（如 notified）一起盖掉。 */
    private void writeResult(AiTraderRequest r, String result) {
        r.setExecutedResult(result);
        requestMapper.update(null, new LambdaUpdateWrapper<AiTraderRequest>()
                .eq(AiTraderRequest::getId, r.getId())
                .set(AiTraderRequest::getExecutedResult, result));
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
        open.setClientRequestId(idemKey(r));
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
        close.setClientRequestId(idemKey(r));
        close.setPositionId(r.getPositionId());
        close.setQuantity(qty);
        close.setOrderType("MARKET");
        return simTradeClient.closePosition(t.getSimUserId(), close);
    }

    /** 幂等键绑请求行：读超时后重发是拿它去问同一笔的结果，sim 侧同键只成交一次 */
    private static String idemKey(AiTraderRequest r) {
        return "req-" + r.getId();
    }

    /** 批准执行也算对计划的修改，留痕带理由——公开修订历史里要看得出"这笔是主人点头的"。 */
    private void revisePlan(AiTrader t, AiTraderRequest r, FuturesPositionDTO pos) {
        try {
            var plan = planStore.find(t.getId(), r.getRoundNo(), pos.getSymbol(), pos.getSide());
            if (plan != null) {
                AgentLang lang = langResolver.of(t.getUserId());
                planStore.revise(plan, r.getWakeTime(),
                        prompts.get(lang, AiTraderRequest.TYPE_ADD.equals(r.getType())
                                ? "trader.revise.addOn" : "trader.revise.reduce"),
                        r.getQuantity().stripTrailingZeros().toPlainString(),
                        prompts.get(lang, "trader.revise.ownerApproved",
                                Map.of("reason", String.valueOf(r.getReason()))));
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

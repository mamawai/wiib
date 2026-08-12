package com.mawai.wiibquant.agent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import com.mawai.wiibquant.mapper.AiTraderPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 同侪只读查询（learning agent 的眼睛）：排行榜快照 + 单 trader 深看详情，两个方法都返回拼好的中文文本块。
 * 只读——不碰账本、不写任何人的数据，包括看的人自己的。
 * 事实裁定归代码、模型只做甄别：收益率/笔数/论点→结局配对全在这里算死，模型拿到的是既成事实，
 * 它要判断的是"这份战绩值不值得学"，而不是"这个数对不对"。
 * 每行硬带已了结笔数：样本量不摆出来，模型就会把 1 笔的运气当成方法论。
 */
@Component
@RequiredArgsConstructor
public class PeerInsightService {

    /** 初始资金，与 TraderService.INITIAL_BALANCE 同一口径（每局子账户都按这个数注资，收益率的分母） */
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000");
    /** 排行榜里复盘摘要的截断长度：一行一句话画像，全文去 detail 看 */
    static final int DIGEST_MAX_CHARS = 80;
    /** 详情页配对表条数上限：够看出手法就行，全部战绩不是这里的活 */
    static final int DETAIL_TRADES = 8;

    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final AiTraderPlanMapper planMapper;
    private final SimTradeClient simTradeClient;
    private final ReviewMaterialAssembler assembler;

    /** 榜单一行的已算好事实（排序要先算完再排，所以先落成对象） */
    private record Row(AiTrader trader, BigDecimal returnPct, int closed, String digest) {
    }

    /**
     * 本局排行榜快照：全体 trader，好的坏的都上榜。
     * 每行 = 谁 + 什么状态 + 赚亏多少 + 几笔样本 + 一句话复盘画像，selfTraderId 那行标出来。
     * 每个 trader 三次查询（权益/复盘/已平仓）不合并：trader 数量级几十，省这点查询不值得把 SQL 绕复杂。
     */
    public String leaderboard(long selfTraderId) {
        List<Row> rows = new ArrayList<>();
        for (AiTrader t : traderMapper.selectList(new LambdaQueryWrapper<AiTrader>()
                .orderByAsc(AiTrader::getId))) {
            AiTraderDecision review = assembler.lastReview(t.getId(), t.getRoundNo());
            rows.add(new Row(t, returnPct(t), closedPositions(t).size(),
                    review == null ? null : review.getReasoning()));
        }
        rows.sort(Comparator.comparing(Row::returnPct).reversed());

        StringBuilder sb = new StringBuilder("""
                【同侪排行榜】（本局快照，按收益率降序）
                口径：收益率＝该 trader 本局最新权益 vs 初始资金 10000；已了结笔数＝这份战绩的样本量，\
                引用同侪战绩必须连笔数一起说，3 笔的胜率不叫方法论。
                已暂停与已爆仓的照样在榜上，它们的经历同样是素材。
                """);
        int i = 1;
        for (Row r : rows) {
            sb.append(i++).append(". [id=").append(r.trader().getId()).append("] ")
                    .append(r.trader().getName())
                    .append(" ｜ ").append(statusText(r.trader().getStatus()))
                    .append(" ｜ 本局收益率 ").append(ReviewMaterialAssembler.signed(r.returnPct())).append('%')
                    .append(" ｜ 已了结 ").append(countText(r.closed())).append(" 笔")
                    .append(" ｜ 最新复盘: ").append(digest(r.digest()));
            // 不标出自己那行，模型会把自己的战绩当外人的经验学一遍
            if (r.trader().getId() == selfTraderId) {
                sb.append("（这是你）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 单 trader 深看：复盘全文 / 学习笔记 / 在场计划 / 最近已了结交易的论点→结局配对。
     * 查无此人返回中文错误文本而不是抛异常——调用方是工具，这段话要原样透传给模型自己纠正。
     */
    public String detail(long traderId) {
        AiTrader t = traderMapper.selectById(traderId);
        if (t == null) {
            return "查无此 trader（id=" + traderId + "）：可能已被删除，请回排行榜取有效 id。";
        }
        List<FuturesPositionDTO> closed = closedPositions(t);
        // 一次拉本局全部计划在内存里分用：LIVE 的进在场计划块，其余的给已了结交易配对
        List<AiTraderPlan> plans = planMapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, t.getId())
                .eq(AiTraderPlan::getRoundNo, t.getRoundNo()));

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(t.getName()).append("】[id=").append(t.getId()).append("] ")
                .append(statusText(t.getStatus()))
                .append(" ｜ 本局收益率 ").append(ReviewMaterialAssembler.signed(returnPct(t))).append('%')
                .append(" ｜ 已了结 ").append(countText(closed.size())).append(" 笔\n\n");

        AiTraderDecision review = assembler.lastReview(t.getId(), t.getRoundNo());
        sb.append("【最新复盘（全文）】\n")
                .append(blank(review == null ? null : review.getReasoning())
                        ? "（尚无复盘）" : review.getReasoning().strip())
                .append("\n\n");

        sb.append("【学习笔记】（它向同侪学到的）\n")
                .append(blank(t.getLearningNotes()) ? "（尚无学习笔记）" : t.getLearningNotes().strip())
                .append("\n\n");

        sb.append("【当前在场计划】（论点与失效条件）\n");
        List<AiTraderPlan> live = plans.stream()
                .filter(p -> AiTraderPlan.STATUS_LIVE.equals(p.getStatus())).toList();
        if (live.isEmpty()) {
            sb.append("（当前空仓，无在场计划）\n");
        }
        for (AiTraderPlan p : live) {
            sb.append("- ").append(p.getSymbol()).append(' ').append(p.getSide())
                    .append(" [").append(ReviewMaterialAssembler.nullSafe(p.getPlayType())).append("]\n")
                    .append("  论点: ").append(ReviewMaterialAssembler.nullSafe(p.getSignalsUsed()))
                    .append(" ｜ 失效条件: ").append(ReviewMaterialAssembler.nullSafe(p.getInvalidationCondition()))
                    .append('\n');
        }

        sb.append("\n【最近已了结交易】（论点→结局，代码配对，最近 ").append(DETAIL_TRADES).append(" 笔，时间倒序）\n");
        // sim 侧已按 updatedAt 倒序返回（见 SimTradeClient.getClosedPositions），直接取前 N 就是最近 N 笔
        List<FuturesPositionDTO> recent = closed.stream().limit(DETAIL_TRADES).toList();
        if (recent.isEmpty()) {
            sb.append("（本局尚无已了结交易）\n");
        }
        Set<AiTraderPlan> used = new HashSet<>();
        int i = 1;
        for (FuturesPositionDTO pos : recent) {
            AiTraderPlan plan = ReviewMaterialAssembler.bestMatch(plans, pos, used);
            sb.append(i++).append(". ").append(pos.getSymbol()).append(' ').append(pos.getSide());
            if (plan != null && plan.getPlayType() != null) {
                sb.append(" [").append(plan.getPlayType()).append(']');
            }
            sb.append(" 入场 ").append(ReviewMaterialAssembler.plain(pos.getEntryPrice()))
                    .append(" → 出场 ").append(ReviewMaterialAssembler.plain(pos.getClosedPrice()))
                    .append("，毛盈亏 ").append(ReviewMaterialAssembler.signed(pos.getClosedPnl()))
                    .append("，持有 ").append(ReviewMaterialAssembler.humanize(
                            ReviewMaterialAssembler.msOf(pos.getUpdatedAt())
                                    - ReviewMaterialAssembler.msOf(pos.getCreatedAt())))
                    .append("，").append(ReviewMaterialAssembler.closeManner(pos)).append('\n');
            if (plan != null) {
                sb.append("   论点: ").append(ReviewMaterialAssembler.nullSafe(plan.getSignalsUsed()))
                        .append(" ｜ 失效条件: ")
                        .append(ReviewMaterialAssembler.nullSafe(plan.getInvalidationCondition())).append('\n');
            } else {
                sb.append("   （无计划记录）\n");
            }
        }
        return sb.toString();
    }

    // ==================== 硬事实计算 ====================

    /** 本局收益率% = 最新一条带 equity 的决策行 vs 初始资金；一次没醒过（无决策行）就是 0，不是负 */
    private BigDecimal returnPct(AiTrader t) {
        AiTraderDecision d = decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .isNotNull(AiTraderDecision::getEquity)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
        if (d == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return d.getEquity().subtract(INITIAL_BALANCE)
                .multiply(BigDecimal.valueOf(100))
                .divide(INITIAL_BALANCE, 2, RoundingMode.HALF_UP);
    }

    /** 每局一个独立 sim 子账户，所以这个账户的已平仓位就是本局全部战绩，不用再按时间过滤 */
    private List<FuturesPositionDTO> closedPositions(AiTrader t) {
        return simTradeClient.getClosedPositions(t.getSimUserId(), ReviewMaterialAssembler.CLOSED_FETCH_LIMIT);
    }

    /** 顶到拉取上限时写成 "200+"：样本量说小了是保守，说死了是假事实 */
    private static String countText(int closed) {
        return closed >= ReviewMaterialAssembler.CLOSED_FETCH_LIMIT
                ? ReviewMaterialAssembler.CLOSED_FETCH_LIMIT + "+" : String.valueOf(closed);
    }

    /** 状态中文：三种状态都得有词，爆仓的同侪照样上榜（前车之鉴） */
    private static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case AiTrader.STATUS_RUNNING -> "运行中";
            case AiTrader.STATUS_PAUSED -> "已暂停";
            case AiTrader.STATUS_LIQUIDATED -> "已爆仓";
            default -> "未知";
        };
    }

    /** 复盘一句话画像：跳过【本期复盘】这类段标题，取首个有实质内容的行截断 */
    private static String digest(String reasoning) {
        if (blank(reasoning)) {
            return "（尚无复盘）";
        }
        String line = reasoning.lines()
                .map(l -> stripTitle(l.strip()))
                .filter(l -> !l.isEmpty())
                .findFirst().orElse("");
        if (line.isEmpty()) {
            return "（尚无复盘）";
        }
        return line.length() > DIGEST_MAX_CHARS ? line.substring(0, DIGEST_MAX_CHARS) + "…" : line;
    }

    /** 去掉行首的【段标题】：标题独占一行就变空行被跳过，标题后接着写正文就只留正文 */
    private static String stripTitle(String line) {
        if (!line.startsWith("【")) {
            return line;
        }
        int end = line.indexOf('】');
        return end < 0 ? line : line.substring(end + 1).strip();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

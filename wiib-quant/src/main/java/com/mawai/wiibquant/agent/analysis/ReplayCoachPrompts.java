package com.mawai.wiibquant.agent.analysis;

import java.math.BigDecimal;
import java.util.List;

/**
 * 复盘 AI 教练的提示词与入参校验：纯函数，不碰模型不碰网络，单测直接钉。
 * <p>
 * 两个硬约束：
 * <ul>
 *   <li><b>只依据给定数据</b>：盲测局的时间是相对标签，系统提示明确禁止猜日期/引用真实历史事件——
 *       模型认得历史行情，一旦联想到具体日期就等于把答案告诉了正在盲测的用户；</li>
 *   <li><b>中性、不下单</b>：提示只讲结构/关键位/量能/风险点和"什么走势会确认或否定"，不给买卖指令；
 *       评估只对着成交与走势评行为，用户不用先写看法（一点就评）。</li>
 * </ul>
 */
public final class ReplayCoachPrompts {

    /** 一次最多送多少根 K 线：HINT 一屏上下文够用，REVIEW 前端会把整局聚合到这个数以内 */
    public static final int MAX_BARS = 400;
    public static final int MAX_TRADES = 300;

    private static final String HINT_SYSTEM = """
            你是加密货币 K 线复盘教练。用户正在逐根回放历史行情练盘感，只能看到已经揭示的 K 线（最后一根就是"现在"），看不到后面。
            时间可能只是 "D2 14:30" 这种相对标签（盲测）：绝不要猜测这是哪一天，也不要引用任何真实历史事件或行情记忆；
            即使给了真实日期，也只依据这里给出的数据作答，不引用外部信息。
            你的角色是站在旁边的教练：帮他把盘面读清楚，但不替他做决定。用简体中文、250 字以内、分点直接说，不用客套：
            1. 结构：现在是趋势还是震荡、最近一段的高低点与关键价位（写具体价格），当前价处在结构里的什么位置
            2. 量能：近段成交量与价格的配合说明了什么（放量突破/缩量回调/背离等）
            3. 持仓：若有持仓，说清它面对的主要风险——浮盈亏占权益的比例、有效杠杆，以及大致再反向走多远权益就见底
               （全仓口径：权益 = 现金 + 保证金 + 浮盈亏，≤0 即爆仓）；没有持仓就跳过这条
            4. 关注点：接下来什么走势会确认当前结构、什么走势会否定它，各给一个具体价位
            结构不明就直说不明并给出判定条件，不要两头都说；不给买卖指令，不做确定性预测。""";

    private static final String REVIEW_SYSTEM = """
            你是加密货币 K 线复盘教练。用户刚跑完一局手动复盘：逐根揭示历史 K 线、只能按收盘价开/加/平/减仓，
            杠杆每次下单时自选，多空可双开，全仓口径（权益 ≤0 爆仓）。下面是整局 K 线、他的全部成交和结算统计。
            请只依据这些数据评估他这一局的交易行为：
            1. 逐笔看：进场时处在什么结构位置（顺势/逆势、突破/回调、追高/抄底），出场是主动的还是被动的
               （扛到爆仓/结算强平算被动），持仓时长与走势是否匹配——用具体时间和价格说明；成交多就挑最典型的 3~5 笔
            2. 行为模式：追涨杀跌、逆势扛单、频繁进出、过早止盈/过晚止损、加仓是摊平亏损还是顺势加码、
               杠杆与仓位是否与当时的波动匹配；一笔没做也要评——错过了什么、观望合不合理
            3. 数据事实：胜率、盈亏比、最大回撤说明了什么，盈亏主要来自哪几笔
            4. 改进：1~3 条具体、可执行的建议
            用简体中文、350 字以内，直接说结论，不要空泛鼓励。只依据给定数据，不引用外部行情记忆。""";

    private ReplayCoachPrompts() {
    }

    /** 入参校验：返回错误文案，合法返回 null（口径同 TraderService/UserLlmConfigService） */
    public static String validate(ReplayCoachRequest r) {
        if (r == null) {
            return "请求为空";
        }
        if (!ReplayCoachRequest.MODE_REVIEW.equals(r.mode()) && !ReplayCoachRequest.MODE_HINT.equals(r.mode())) {
            return "mode 只能是 HINT/REVIEW";
        }
        if (r.symbol() == null || r.symbol().isBlank() || r.symbol().length() > 20) {
            return "symbol 无效";
        }
        if (r.intervalMin() == null || r.intervalMin() <= 0) {
            return "intervalMin 无效";
        }
        if (r.bars() == null || r.bars().isEmpty()) {
            return "K 线为空";
        }
        if (r.bars().size() > MAX_BARS) {
            return "K 线过多（上限 " + MAX_BARS + " 根）";
        }
        if (r.positions() != null && r.positions().size() > 2) {
            return "持仓最多多空各一";
        }
        if (r.trades() != null && r.trades().size() > MAX_TRADES) {
            return "成交过多（上限 " + MAX_TRADES + " 笔）";
        }
        return null;
    }

    public static String system(ReplayCoachRequest r) {
        return ReplayCoachRequest.MODE_REVIEW.equals(r.mode()) ? REVIEW_SYSTEM : HINT_SYSTEM;
    }

    /** 用户消息：紧凑的表格文本，token 省着用（几百根 K 线一行一根） */
    public static String user(ReplayCoachRequest r) {
        StringBuilder sb = new StringBuilder(64 + r.bars().size() * 48);
        sb.append("标的 ").append(r.symbol()).append(" · 周期 ").append(r.intervalMin()).append("m · ")
                .append(Boolean.TRUE.equals(r.blind()) ? "盲测（时间为 D几 时:分 的相对标签，无真实日期）" : "真实时间")
                .append('\n');
        if (r.startAt() != null && !r.startAt().isBlank()) {
            sb.append("复盘段从 ").append(r.startAt()).append(" 开始，更早的 K 线是开局给的上下文\n");
        }
        sb.append("K线（时间 开 高 低 收 量），共 ").append(r.bars().size()).append(" 根，最后一根是当前：\n");
        for (ReplayCoachRequest.Bar b : r.bars()) {
            sb.append(b.t()).append(' ').append(num(b.o())).append(' ').append(num(b.h())).append(' ')
                    .append(num(b.l())).append(' ').append(num(b.c())).append(' ').append(num(b.v())).append('\n');
        }
        List<ReplayCoachRequest.Position> positions = r.positions() == null ? List.of() : r.positions();
        if (ReplayCoachRequest.MODE_HINT.equals(r.mode())) {
            if (r.equity() != null) {
                sb.append("当前权益 ").append(num(Math.round(r.equity() * 100) / 100.0)).append('\n');
            }
            sb.append("当前持仓：");
            if (positions.isEmpty()) {
                sb.append("无");
            }
            for (ReplayCoachRequest.Position p : positions) {
                sb.append(side(p.side())).append(' ').append(num(p.qty())).append(" @ 均价 ").append(num(p.entryPrice()))
                        .append(' ').append(lev(p.leverage())).append(" 浮盈 ").append(signed(p.unrealizedPnl())).append("；");
            }
            sb.append('\n');
            return sb.toString();
        }

        List<ReplayCoachRequest.Trade> trades = r.trades() == null ? List.of() : r.trades();
        sb.append("成交记录（共 ").append(trades.size()).append(" 笔）：\n");
        if (trades.isEmpty()) {
            sb.append("整局没有交易\n");
        }
        int i = 1;
        for (ReplayCoachRequest.Trade t : trades) {
            sb.append(i++).append(". ").append(side(t.side())).append(' ').append(lev(t.leverage()))
                    .append(" 开 ").append(t.openAt()).append(" @ ").append(num(t.entryPrice()))
                    .append(" → ").append(reason(t.reason(), t.partial())).append(' ').append(t.closeAt()).append(" @ ").append(num(t.exitPrice()))
                    .append(" 数量 ").append(num(t.qty())).append(" 盈亏 ").append(signed(t.pnl())).append('\n');
        }
        ReplayCoachRequest.Stats s = r.stats();
        if (s != null) {
            sb.append("结算：初始 ").append(num(s.initialBalance())).append(" → 最终 ").append(num(s.finalEquity()))
                    .append("，净利 ").append(signed(s.netProfit())).append("（").append(pct(s.returnPct())).append("），")
                    .append(s.totalTrades()).append(" 笔 ").append(s.wins()).append(" 胜 ").append(s.losses()).append(" 负，最大回撤 ")
                    .append(pct(s.maxDrawdownPct())).append("，手续费 ").append(num(s.totalFees())).append('\n');
        }
        return sb.toString();
    }

    /** 去掉浮点尾巴：前端已按币种精度取整，这里只负责别把 3450.0 打成 3450.0000000001 */
    static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "-";
        }
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static String signed(double v) {
        return (v >= 0 ? "+" : "") + num(Math.round(v * 100) / 100.0);
    }

    private static String pct(double ratio) {
        return (ratio >= 0 ? "+" : "") + num(Math.round(ratio * 10000) / 100.0) + "%";
    }

    /** 有效杠杆：整数照常，加仓换档产生的小数留一位 */
    private static String lev(double leverage) {
        return num(Math.round(leverage * 10) / 10.0) + "x";
    }

    private static String side(String side) {
        return "SHORT".equals(side) ? "空" : "多";
    }

    private static String reason(String reason, boolean partial) {
        if ("LIQUIDATION".equals(reason)) {
            return "爆仓";
        }
        if ("END".equals(reason)) {
            return "结算强平";
        }
        return partial ? "减仓" : "平仓";
    }
}

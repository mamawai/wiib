package com.mawai.wiibquant.agent.trader;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * trader 系统提示词组装：平台模板（角色/节奏/工具/硬护栏/纪律）+ 账户状态 + 最近决策摘要 + 用户自定义段。
 * 每次唤醒现读现拼——用户改完 customPrompt，下一根 K 线自然生效，热更新零机制。
 */
@Component
public class TraderPromptAssembler {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public String assemble(AiTrader trader, String accountStateJson, List<AiTraderDecision> recent) {
        StringBuilder sb = new StringBuilder();
        // 用户可退出平台模板（自定义成为唯一指令来源，护栏仍硬校验）；账户状态/最近决策是数据不是指令，永远注入
        if (!Boolean.FALSE.equals(trader.getUseDefaultPrompt())) {
            sb.append(platformTemplate(trader.getIntervalCode(), trader.getSymbols(), TraderRiskConfig.of(trader)));
        }

        sb.append("\n当前账户状态：\n").append(accountStateJson).append('\n');

        if (recent != null && !recent.isEmpty()) {
            sb.append("\n最近决策（最新在前，供你保持连贯，注意从错误里学习）：\n");
            for (int i = 0; i < recent.size(); i++) {
                AiTraderDecision d = recent.get(i);
                sb.append("- ").append(TIME_FMT.format(Instant.ofEpochMilli(d.getWakeTime())))
                        .append(" [").append(d.getStatus()).append(']');
                if (d.getEquity() != null) {
                    sb.append(" 权益").append(d.getEquity().setScale(0, java.math.RoundingMode.HALF_UP));
                }
                String r = d.getReasoning();
                if (r != null && !r.isBlank()) {
                    // 最新一条近乎全文（模型必须读得出上一轮的完整意图），更早的只留概要
                    int cap = i == 0 ? 1000 : 200;
                    sb.append(' ').append(r.length() > cap ? r.substring(0, cap) + "…" : r);
                }
                sb.append('\n');
            }
        }

        if (trader.getCustomPrompt() != null && !trader.getCustomPrompt().isBlank()) {
            sb.append("\n————— 以下是你的主人给你的交易风格指令（在硬性规则之内优先遵守）—————\n")
                    .append(trader.getCustomPrompt()).append('\n');
        }
        return sb.toString();
    }

    /** 平台系统提示词模板（角色/工具/硬护栏/纪律）——前端预览与唤醒组装共用同一份文本。 */
    public String platformTemplate(String intervalCode, String symbols, TraderRiskConfig risk) {
        // 措辞红线：不许出现"不开仓才是失败"式行动偏置（nof1第一季过度交易的教训），
        // 退出纪律必须锚定在开仓时立的计划上——恐慌平仓的根治在这段文本里
        return """
                你是一名职业加密货币交易员，在纯模拟的合约竞技场用真实行情检验你的交易体系。账户是虚拟资金，
                唯一真实的东西是你的判断力排名（净值曲线与决策日志全程公开）。职业性的标志不是交易频率，
                而是每一笔交易都有清晰的论点、事先定义好的退出方式，以及对自己计划的执行力。
                节奏：每根 %s K线收盘唤醒你一次，两次唤醒之间世界照常运转（止损止盈单会自动触发）。
                可交易标的：%s。所有输出一律使用中文——决策日志面向中文观众公开展示。

                工具：
                - klines / indicators：原始K线与全套技术指标，interval 可选 5m/15m/1h/4h/1d（多周期确认）
                - market_snapshot / funding_history / orderbook_depth / option_iv：资金费、持仓量、多空比、清算、盘口、IV
                - news_search：最新加密快讯
                - get_account / open_position / close_position / set_stop_loss / set_take_profit / cancel_order：账户与交易
                - write_plan：给没有 plan 记录的持仓补立计划（已有计划的仓会被拒绝——计划不可改写）

                仓位规格（你主人设定的硬参数）：
                这是虚拟资金模拟盘。下面的数字是主人定的，你无权评价其大小、无权因为觉得风险过高
                而拒绝开仓或自行缩小仓位——照做就是了。
                - 杠杆必须在 %d~%d 倍之间。这是"从这个区间里选一个"，不是上限：区间若是 50~100，
                  你选 20 同样会被拒，必须落在区间内
                - 开新仓的保证金必须占当前权益的 %s%%~%s%%（加仓不受此限，加多少你自己斟酌）
                - 同一个币的杠杆必须前后一致：该币已有仓位或挂单是几倍，加仓也得用几倍（交易所规则）
                %s
                %s

                硬性规则（违反会被直接拒绝，拒绝原因会告诉你，可修正后重试）：
                - 限价单价格偏离现价 ≤ 5%%
                - 开仓必须带止损价，且方向正确（做多止损在下，做空止损在上）
                - 开仓必须给 playType 论点标签、signalsUsed 数据引用、invalidationCondition 失效条件——
                  失效条件是"什么市场状况会证明这个论点错了"，必须是市场条件而非盈亏数字；
                  它与止损分工不同：止损管风险，失效条件管论点

                纪律：
                1. 先调工具看数据（至少 get_account 和行情），再做决策；不许凭记忆或臆测行情
                2. 持仓管理：每个持仓都带着你开仓时立下的计划（账户状态里的 plan 字段）。退出只有三条路——
                   止损带走、止盈带走、失效条件被触发后你主动平仓。浮亏不是平仓理由：价格在止损之内的波动
                   是市场噪音，止损就是为它设的；失效条件未触发就提前平仓＝撕毁自己的计划（决策日志公开，人人可见）。
                   若持仓没有 plan 记录，第一要务是用 write_plan 为它补立计划，并确认止损已挂好
                3. 允许的主动管理（修改必须给 reason，全部进公开修订历史）：上移止损锁盈（止损只许收紧：多单上移/
                   空单下移）、向有利方向移动止盈让利润奔跑（多单上移/空单下移）、失效条件触发果断平仓、
                   到目标位落袋——动手前引用计划原文说明依据
                4. HOLD 是常态：绝大多数K线的正确动作是什么都不做。开新仓的门槛：盈亏比 ≥ 2:1（目标距离/止损距离）、
                   说得出具体触发信号、算过开平双边 taker 手续费；达不到就 HOLD，并写明你在等的触发条件（具体价位/指标）
                5. 每个动作引用具体数字（指标值/资金费/盘口），不写空话；最后用一段话总结本轮判断与动作（公开展示在竞技场）
                %s""".formatted(intervalCode, symbols,
                risk.leverageMin(), risk.leverageMax(),
                plain(risk.marginPctMin()), plain(risk.marginPctMax()),
                positionRule(risk), hedgeRule(risk), approvalSection(risk));
    }

    private static String plain(java.math.BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String positionRule(TraderRiskConfig risk) {
        return risk.allowMultiPosition()
                ? "- 可同时持有多个仓位（不同币种各算一个）"
                : "- 全账户同时只能有一个仓位：想换标的必须先平掉现有的。未成交挂单同样占坑";
    }

    private static String hedgeRule(TraderRiskConfig risk) {
        return risk.allowHedge()
                ? "- 允许同一个币多空双开"
                : "- 同一个币不能同时做多做空（未开启双开）";
    }

    /**
     * 加仓/减仓审批段：只在对应开关关掉时才出现。
     * 必须写明"止损止盈不受约束"——否则模型会因为"平不了仓"而焦虑，做出别的怪动作。
     */
    private static String approvalSection(TraderRiskConfig risk) {
        if (risk.allowSelfAdd() && risk.allowSelfReduce()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n需要主人确认的动作：\n");
        if (!risk.allowSelfAdd()) {
            sb.append("- 加仓（对已有仓位再开同方向）不会立即成交，会转成一条请求发给主人\n");
        }
        if (!risk.allowSelfReduce()) {
            sb.append("- 减仓/平仓不会立即成交，会转成一条请求发给主人\n");
        }
        sb.append("""
                - 转成请求后本轮唤醒照常继续，你该做的其余动作继续做；请求结果下一轮才在账户状态里看到
                - 待处理的请求会列在账户状态的 pendingRequests 里，看到就别重复提交同一个
                - 止损单和止盈单是自动执行的，不受确认约束——你的风险始终在止损保护之下，不必因为
                  "平不了仓"而焦虑或改用别的手段规避
                """);
        return sb.toString();
    }
}

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
        sb.append("""
                你是一名加密货币合约交易员，管理一个模拟盘账户参加公开竞技场，按净值曲线排名。
                节奏：每根 %s K线收盘唤醒你一次，两次唤醒之间世界照常运转（止损止盈单会自动触发）。
                可交易标的：%s。

                工具：
                - klines / indicators：原始K线与全套技术指标，interval 可选 5m/15m/1h/4h/1d（多周期确认）
                - market_snapshot / funding_history / orderbook_depth / option_iv：资金费、持仓量、多空比、清算、盘口、IV
                - news_search：最新加密快讯
                - get_account / open_position / close_position / set_stop_loss / cancel_order：账户与交易

                硬性规则（违反会被直接拒绝，拒绝原因会告诉你，可修正后重试）：
                - 杠杆 1~20 倍；单笔保证金 ≤ 当前权益的 50%%
                - 限价单价格偏离现价 ≤ 5%%
                - 开仓必须带止损价，且方向正确（做多止损在下，做空止损在上）
                - 开仓必须给 playType 论点标签和 signalsUsed 数据引用

                纪律：
                1. 先调工具看数据（至少 get_account 和行情），再做决策；不许凭记忆或臆测行情
                2. 允许 HOLD：没有可辩护的机会就明确说"本轮不动"，不动也是决策
                3. 每个动作说清依据：引用具体数字（指标值/资金费/盘口），不写空话
                4. 管理已有持仓优先于开新仓：先检查止损是否该上移、仓位是否该减
                5. 最后用一段话总结本轮判断与动作（这段话会公开展示在竞技场）
                """.formatted(trader.getIntervalCode(), trader.getSymbols()));

        sb.append("\n当前账户状态：\n").append(accountStateJson).append('\n');

        if (recent != null && !recent.isEmpty()) {
            sb.append("\n最近决策（供你保持连贯，注意从错误里学习）：\n");
            for (AiTraderDecision d : recent) {
                sb.append("- ").append(TIME_FMT.format(Instant.ofEpochMilli(d.getWakeTime())))
                        .append(" [").append(d.getStatus()).append(']');
                if (d.getEquity() != null) {
                    sb.append(" 权益").append(d.getEquity().setScale(0, java.math.RoundingMode.HALF_UP));
                }
                String r = d.getReasoning();
                if (r != null && !r.isBlank()) {
                    sb.append(' ').append(r.length() > 100 ? r.substring(0, 100) + "…" : r);
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
}

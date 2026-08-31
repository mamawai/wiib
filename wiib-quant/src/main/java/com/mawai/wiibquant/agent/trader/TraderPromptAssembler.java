package com.mawai.wiibquant.agent.trader;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.agent.i18n.PromptCatalog;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * trader 系统提示词组装：平台模板 + 账户状态 + 最近决策 + 论点战绩统计 + 复盘笔记 + 学习笔记 + 用户自定义段。
 * 每次唤醒现读现拼——用户改完 customPrompt，下一根 K 线自然生效，热更新零机制。
 * <p>
 * 文本全在 {@link PromptCatalog} 的 {@code trader.*}，按用户的 {@link AgentLang} 取；骨架
 * （条件分支、循环、插值、换行框）留在这里。<b>用户自己写的字不翻译</b>——customPrompt 与
 * ownerNote 原样注入。
 * <p>
 * 模板的认知设计（顺序即优先级）：
 * ① 身份与记分牌先行——角色决定推理先验，没有身份锚点模型会滑回"有帮助的助手"默认态；
 * ② 单问题框架——每次唤醒只回答"计划需要改变吗"，问题边界越清晰分析越聚焦；
 * ③ 状态与指令分层——账户状态是系统陈述的数据，工具预算留给行情求证，不花在查户口；
 * ④ 检验先于发明——先对上一轮的承诺（等待条件/失效条件）做检验，再考虑新机会，治翻烙饼；
 * ⑤ 固定收尾格式——结论块既是公开展示单元，也是下一轮回注后的检验基准；
 * ⑥ 用户风格指令放最后（近因权重最高）且明示优先级：风格冲突听主人的，硬规格不可覆盖；
 * ⑦ 主人留言压轴：阶段性交代，比常驻风格更近因；情况允许且合理则尽量履行、不许忽视，观点不成立可以不听但要写理由，按剩余轮次逐轮注入、减到 0 清空。
 * 这七条是 agent 行为的决定因素，不是文案——加语言时逐条对照着写，不是逐字翻译。
 * <p>
 * ⑧ 输出语言硬收尾排在⑦之后：⑥⑦是主人亲笔、不翻译，可能与平台模板不同语言，且近因权重最高。
 * 语言指令因此说两次——模板正文一次，整篇最末一行再一次。
 */
@Component
@RequiredArgsConstructor
public class TraderPromptAssembler {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 只为留言而来：注入的同一处就得把轮次减掉，见 {@link #consumeOwnerNote} */
    private final AiTraderMapper traderMapper;
    private final PromptCatalog prompts;
    private final PlayStatsAssembler playStats;

    public String assemble(AiTrader trader, String accountStateJson, List<AiTraderDecision> recent, AgentLang lang) {
        StringBuilder sb = new StringBuilder();
        WakeWindow window = WakeWindow.of(trader);
        String windowText = window == null ? null : window.text();
        // 用户可退出平台模板（自定义成为唯一指令来源，护栏仍硬校验）；账户状态/最近决策/复盘笔记是数据不是指令，永远注入
        if (!Boolean.FALSE.equals(trader.getUseDefaultPrompt())) {
            sb.append(platformTemplate(lang, trader.getIntervalCode(), trader.getSymbols(),
                    TraderRiskConfig.of(trader), windowText));
        } else if (windowText != null) {
            // 退出平台模板时节奏行不在了，时段是事实不是指令，单独补一行——否则模型按"每根K线都醒"管仓位，休眠 12 小时它却不知道
            sb.append('\n').append(prompts.get(lang, "trader.label.wakeWindow",
                    Map.of("window", windowText))).append('\n');
        }

        sb.append('\n').append(prompts.get(lang, "trader.label.accountState")).append('\n')
                .append(accountStateJson).append('\n');

        if (recent != null && !recent.isEmpty()) {
            sb.append('\n').append(prompts.get(lang, "trader.label.recentDecisions")).append('\n');
            for (int i = 0; i < recent.size(); i++) {
                AiTraderDecision d = recent.get(i);
                sb.append("- ").append(TIME_FMT.format(Instant.ofEpochMilli(d.getWakeTime())))
                        .append(" [").append(d.getStatus()).append(']');
                if (d.getEquity() != null) {
                    sb.append(' ').append(prompts.get(lang, "trader.label.equity", Map.of("value",
                            d.getEquity().setScale(0, java.math.RoundingMode.HALF_UP))));
                }
                String r = d.getReasoning();
                if (r != null && !r.isBlank()) {
                    // 最新一条近乎全文，更早的只留概要；截断一律保尾不保头——
                    // 结论块按纪律收在末尾，保头正好把结论切掉，只剩行情铺垫
                    int cap = i == 0 ? 1000 : 200;
                    sb.append(' ').append(r.length() > cap ? "…" + r.substring(r.length() - cap) : r);
                }
                sb.append('\n');
            }
        }

        // 数据档连排：账户状态/最近决策/论点战绩都是本局事实；null=本局无可统计或取数失败，整块缺席
        String stats = playStats.assemble(trader, lang);
        if (stats != null) {
            sb.append('\n').append(stats);
        }

        if (trader.getMemory() != null && !trader.getMemory().isBlank()) {
            sb.append('\n').append(prompts.get(lang, "trader.label.memory")).append('\n')
                    .append(trader.getMemory()).append('\n');
        }

        // 与复盘笔记并列注入不合并：来源分开，模型才分得清"自己的教训"与"从别人学的"
        if (trader.getLearningNotes() != null && !trader.getLearningNotes().isBlank()) {
            sb.append('\n').append(prompts.get(lang, "trader.label.learningNotes")).append('\n')
                    .append(trader.getLearningNotes()).append('\n');
        }

        // 用户自己写的字：原样注入不过词表。紧跟一句 ownerWritten 交代"这段是主人亲笔、
        // 可能是另一门语言、照意思做但输出语言不变"，位置要在他的字之前
        if (trader.getCustomPrompt() != null && !trader.getCustomPrompt().isBlank()) {
            sb.append('\n').append(prompts.get(lang, "trader.label.customPrompt")).append('\n')
                    .append(prompts.get(lang, "trader.label.ownerWritten")).append('\n')
                    .append(trader.getCustomPrompt()).append('\n');
        }

        String note = trader.getOwnerNote();
        if (note != null && !note.isBlank()) {
            // 正文非空才叫"有待读留言"，轮次异常一律当 1 轮：迁移时 ALTER 跑了而回填 UPDATE 漏跑，
            // 库里就会出现"有正文、轮次是 0/null"。当 1 处理最坏只是退化回一次性留言，不炸也不吞
            Integer raw = trader.getOwnerNoteRounds();
            int rounds = raw == null || raw <= 0 ? 1 : raw;
            int left = rounds - 1;
            String roundsText = left == 0 ? prompts.get(lang, "trader.label.ownerNoteOnce")
                    : prompts.get(lang, "trader.label.ownerNoteMore", Map.of("left", left));
            sb.append('\n').append(prompts.get(lang, "trader.label.ownerNote", Map.of("rounds", roundsText)))
                    .append('\n').append(prompts.get(lang, "trader.label.ownerWritten")).append('\n')
                    .append(note).append('\n')
                    // 明说还剩几次：持续叮嘱，不是"现在就执行一次"。footer 另管两件事——
                    // 合理则履行、观点不成立可不听；一次性动作做过不要再做（防"平 ETH"念三次平三次）
                    .append(prompts.get(lang, "trader.label.ownerNoteFooter")).append('\n');
            consumeOwnerNote(trader, note, left);
        }
        // 输出语言硬收尾：整篇最末一行，排在自定义指令与主人留言之后。
        // 退出平台模板时模板正文那次不在了，只剩这一行
        sb.append('\n').append(prompts.get(lang, "trader.label.outputLanguage")).append('\n');
        return sb.toString();
    }

    /**
     * 消费一轮：递减必须紧贴注入写在一起。
     * 拆成两处（比如让唤醒回路事后减）迟早会掉进两个坑之一——注了没减，留言每轮重念、
     * 模型把阶段性交代当成长期规则；减了没注，主人的话直接蒸发且无人知晓。
     * <p>
     * 代价是<b>注入即消费</b>：这一轮唤醒后面若失败，那一轮也算用掉了。选它是因为反过来更糟——
     * 不减就会重放，而"可能重复执行一条主人指令"比"偶发丢一轮念诵"危险得多。
     */
    private void consumeOwnerNote(AiTrader trader, String injected, int left) {
        // 条件 SQL，以"库里的正文仍是我注入的这条"为前置：trader 是调度时刻的快照，
        // 取到这里之间隔着数次 HTTP 与多条 SQL、并发满槽时还会在信号量上等几分钟，
        // 期间主人可能已在面板改写或撤回。正文对不上就影响 0 行，只递减自己念过的那条。
        //
        // 两个 SET 都读旧行值（SQL 标准），CASE 判的是递减前的轮次：
        // 旧值 <=1 即本次是最后一次，正文一并清空；GREATEST 保证轮次不落到负数。
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, trader.getId())
                .eq(AiTrader::getOwnerNote, injected)
                .setSql("owner_note_rounds = GREATEST(owner_note_rounds - 1, 0), "
                        + "owner_note = CASE WHEN owner_note_rounds <= 1 THEN NULL ELSE owner_note END")
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        // 内存副本与库写保持同构：同一次唤醒里这个对象还会被别处读到，别让它再露一次面。
        // 无条件按 left 走——就算上面因正文被改写而没更新库，这一轮也确实已经注入过了
        if (left == 0) {
            trader.setOwnerNote(null);
        }
        trader.setOwnerNoteRounds(left);
    }

    /**
     * 平台系统提示词模板（身份/工具/规格/成本/分析流程/纪律）——前端预览与唤醒组装共用同一份文本。
     * 措辞红线（两门语言同守）：不许出现"不开仓才是失败"式行动偏置（nof1 第一季过度交易的教训），
     * 退出纪律必须锚定在开仓时立的计划上——恐慌平仓的根治在这段文本里。
     *
     * @param wakeWindowText 唤醒时段"HH:mm-HH:mm"，null=全天——节奏行按它说真话，模板与事实不许打架
     */
    public String platformTemplate(AgentLang lang, String intervalCode, String symbols,
                                   TraderRiskConfig risk, String wakeWindowText) {
        return prompts.get(lang, "trader.template", Map.of(
                "rhythm", rhythmText(lang, intervalCode, wakeWindowText),
                "symbols", symbols,
                "leverageMin", risk.leverageMin(),
                "leverageMax", risk.leverageMax(),
                "marginPctMin", plain(risk.marginPctMin()),
                "marginPctMax", plain(risk.marginPctMax()),
                "positionRule", positionRule(lang, risk),
                "hedgeRule", hedgeRule(lang, risk),
                "approval", approvalSection(lang, risk)));
    }

    /** 节奏行按时段说真话：全天=每根K线醒一次；有时段就把"只在时段内醒、时段外例行与警报都停、手动例外"写进同一句 */
    private String rhythmText(AgentLang lang, String intervalCode, String wakeWindowText) {
        return wakeWindowText == null
                ? prompts.get(lang, "trader.rhythm.allDay", Map.of("interval", intervalCode))
                : prompts.get(lang, "trader.rhythm.window",
                        Map.of("window", wakeWindowText, "interval", intervalCode));
    }

    private static String plain(java.math.BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private String positionRule(AgentLang lang, TraderRiskConfig risk) {
        return prompts.get(lang, risk.allowMultiPosition()
                ? "trader.positionRule.multi" : "trader.positionRule.single");
    }

    private String hedgeRule(AgentLang lang, TraderRiskConfig risk) {
        return prompts.get(lang, risk.allowHedge() ? "trader.hedgeRule.allow" : "trader.hedgeRule.deny");
    }

    /**
     * 加仓/减仓审批段：只在对应开关关掉时才出现。
     * footer 必须写明"止损止盈不受约束"——否则模型会因为"平不了仓"而焦虑，做出别的怪动作。
     */
    private String approvalSection(AgentLang lang, TraderRiskConfig risk) {
        if (risk.allowSelfAdd() && risk.allowSelfReduce()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n")
                .append(prompts.get(lang, "trader.approval.header")).append('\n');
        if (!risk.allowSelfAdd()) {
            sb.append(prompts.get(lang, "trader.approval.add")).append('\n');
        }
        if (!risk.allowSelfReduce()) {
            sb.append(prompts.get(lang, "trader.approval.reduce")).append('\n');
        }
        return sb.append(prompts.get(lang, "trader.approval.footer")).toString();
    }
}

package com.mawai.wiibquant.agent.i18n;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.learning.PeerInsightService;
import com.mawai.wiibquant.agent.learning.ReviewMaterialAssembler;
import com.mawai.wiibquant.agent.trader.TraderPromptAssembler;
import com.mawai.wiibquant.agent.trader.TraderRiskConfig;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import com.mawai.wiibquant.mapper.AiTraderPlanMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 交易链三处提示词的双语契约。三条底线，每条对应一类"上线才发现"的事故：
 * <ul>
 *   <li><b>英文用户不许收到一个中文字</b>——逐码点扫 CJK。提示词里混一段中文，模型多半会跟着
 *       用中文回答，产出直接串语言；靠肉眼看是看不全的（段名藏在代码拼接里）。</li>
 *   <li><b>中文侧的关键约束一条都不许在搬家时丢</b>——这批是"外置 + 加英文版"，不是重写中文提示词。
 *       钉住的是行为约束句，不是文案措辞。</li>
 *   <li><b>分隔符按语言认对应那一套</b>——见 {@code PromptMarkParsingTest}（要包内可见的
 *       parse/missingMarks/waitSection，放在 learning 包里）。</li>
 * </ul>
 */
class PromptI18nTest {

    private static final long FROM = 1785110400000L;
    private static final long TO = FROM + 86_400_000L;

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final PromptCatalog prompts = new PromptCatalog();

    /** 逐码点扫 CJK：汉字、全角标点、中文书名号/引号一个都不许漏进英文提示词 */
    private static void assertNoCjk(String label, String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            boolean cjk = (cp >= 0x4E00 && cp <= 0x9FFF)      // 基本汉字
                    || (cp >= 0x3400 && cp <= 0x4DBF)          // 扩展A
                    || (cp >= 0x3000 && cp <= 0x303F)          // 中日韩符号（【】、。等）
                    || (cp >= 0xFF00 && cp <= 0xFFEF);         // 全角形式（％｜（）等）
            assertThat(cjk)
                    .as("%s 里混进了中日韩字符 U+%04X（%s）", label, cp, new String(Character.toChars(cp)))
                    .isFalse();
        }
    }

    // ==================== ① 英文用户拿到的提示词零中文 ====================

    @Test
    void 英文trader提示词全文无中文() {
        TraderPromptAssembler assembler =
                new TraderPromptAssembler(mock(AiTraderMapper.class), prompts);
        AiTrader t = enTrader();
        t.setMemory("突破回踩不守住颈线就别追。");            // 旧笔记是中文：原样注入不算违规
        t.setLearningNotes("同侪A的BREAKOUT 12笔8胜。");
        t.setCustomPrompt("Breakouts only.");
        t.setOwnerNote("Watch CPI tonight.");
        t.setOwnerNoteRounds(3);
        t.setWakeWindow("21:00-08:30");

        String full = assembler.assemble(t, "{\"equity\":10000}", List.of(), AgentLang.EN);
        // 用户自己写的字与旧笔记原样注入，扫描前剥掉——它们本来就不该被翻译
        String platform = full.replace(t.getMemory(), "").replace(t.getLearningNotes(), "");
        assertNoCjk("英文 trader 提示词", platform);

        // 各条件分支的片段也得干净：单仓 / 禁双开 / 审批段只在开关关掉时才出现
        AiTrader strict = enTrader();
        strict.setAllowMultiPosition(false);
        strict.setAllowHedge(false);
        strict.setAllowSelfAdd(false);
        strict.setAllowSelfReduce(false);
        assertNoCjk("英文 trader 模板（严格档）", assembler.platformTemplate(
                AgentLang.EN, "4h", "BTCUSDT", TraderRiskConfig.of(strict), "21:00-08:30"));
    }

    @Test
    void 英文reviewer提示词与素材块全文无中文() {
        assertNoCjk("英文 reviewer 系统提示词", prompts.get(AgentLang.EN, "reviewer.system"));

        ReviewMaterialAssembler.ReviewMaterial m = enMaterial();
        assertNoCjk("英文战绩表", m.statsBlock());
        assertNoCjk("英文配对表", m.tradesBlock());
        assertNoCjk("英文时间线", m.timelineBlock());
        assertNoCjk("英文价格路径", m.pricePathBlock());
    }

    @Test
    void 英文learning提示词与同侪块全文无中文() {
        assertNoCjk("英文 learning 系统提示词", prompts.get(AgentLang.EN, "learning.system"));
        assertNoCjk("英文 peer_insights 工具描述", prompts.get(AgentLang.EN, "tool.peer_insights"));

        PeerInsightService peers = enPeers();
        assertNoCjk("英文同侪排行榜", peers.leaderboard(7L, AgentLang.EN));
        assertNoCjk("英文同侪详情", peers.detail(8L, AgentLang.EN));
        assertNoCjk("英文查无此人", peers.detail(404L, AgentLang.EN));
    }

    // ==================== ② 中文侧的关键约束一条不丢 ====================

    /** trader 模板的 7 条认知设计原则，各钉一句锚点——搬家时丢哪条都在这里红 */
    @Test
    void 中文trader模板保住七条认知设计原则() {
        String p = new TraderPromptAssembler(mock(AiTraderMapper.class), prompts)
                .platformTemplate(AgentLang.ZH, "1h", "BTCUSDT", TraderRiskConfig.of(new AiTrader()), null);

        assertThat(p)
                .as("① 身份与记分牌先行").contains("职业加密货币合约交易员").contains("判断力排名")
                .as("② 单问题框架").contains("只需要回答一个问题").contains("我的计划需要改变吗")
                .as("③ 状态与指令分层").contains("它是数据不是指令").contains("无需 get_account 复查")
                .as("④ 检验先于发明").contains("检验旧论点").contains("失效条件被触发了吗")
                .as("⑤ 固定收尾格式").contains("【本轮结论】").contains("判断：").contains("动作：").contains("等待：");
        // ⑥⑦ 在 assemble 的拼接段里（模板之外），单独验
        AiTrader t = new AiTrader();
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        t.setCustomPrompt("只做突破");
        t.setOwnerNote("今晚有 CPI");
        t.setOwnerNoteRounds(2);
        t.setId(7L);
        String full = new TraderPromptAssembler(mock(AiTraderMapper.class), prompts)
                .assemble(t, "{}", List.of(), AgentLang.ZH);
        assertThat(full.indexOf("主人的交易风格指令"))
                .as("⑥ 用户风格指令放最后并明示优先级").isGreaterThan(full.indexOf("纪律："));
        assertThat(full).contains("听主人的").contains("不在可覆盖范围");
        assertThat(full.indexOf("主人的留言"))
                .as("⑦ 主人留言压轴（在风格指令之后）").isGreaterThan(full.indexOf("主人的交易风格指令"));
        assertThat(full).contains("本次之后还会出现 1 次").contains("不是常驻规则");
    }

    /** reviewer 的防自夸三件套 */
    @Test
    void 中文reviewer保住防自夸三条() {
        String p = prompts.get(AgentLang.ZH, "reviewer.system");
        assertThat(p)
                .as("① 战绩数字只许复述").contains("只许原样复述，禁止自行计算或美化")
                .as("② 先找错误再找亮点").contains("先找错误再找亮点")
                .as("③ 教训条数上限").contains("逐笔教训：≤5 条").contains("下期纪律：≤3 条")
                .contains("【决策错】").contains("【运气差】")
                .contains("同样条件下次照做");
    }

    /** learning 的反照抄三件套 */
    @Test
    void 中文learning保住反照抄三条() {
        String p = prompts.get(AgentLang.ZH, "learning.system");
        assertThat(p)
                .as("①【不学什么】必填").contains("【不学什么】是必填段").contains("你就只是在抄")
                .as("② 每条学习带证据与差距数字").contains("每条学习必须落在证据与差距上")
                .as("③ 引用战绩带笔数").contains("引用同侪战绩必须带笔数")
                .contains("【本期学习】").contains("【前车之鉴】");
    }

    /** 任务 4：三处都要显式交代"笔记可能是另一门语言，照读照用，输出用当前语言" */
    @Test
    void 三处提示词都交代了跨语言笔记() {
        assertThat(new TraderPromptAssembler(mock(AiTraderMapper.class), prompts)
                .platformTemplate(AgentLang.ZH, "1h", "BTCUSDT", TraderRiskConfig.of(new AiTrader()), null))
                .contains("可能是另一门语言写的").contains("本轮输出一律用中文");
        assertThat(prompts.get(AgentLang.ZH, "reviewer.system")).contains("可能是另一门语言写的");
        assertThat(prompts.get(AgentLang.ZH, "learning.system")).contains("可能是另一门语言写的");
        for (String key : List.of("reviewer.system", "learning.system")) {
            assertThat(prompts.get(AgentLang.EN, key)).contains("another language");
        }
    }

    /** 英文侧不是漏翻回落：两门语言的同一 key 一字不差就说明英文没写 */
    @Test
    void 三个域的英文词表都真有自己的文案() {
        for (String key : List.of("trader.template", "trader.label.memory", "trader.mark.conclusion",
                "reviewer.system", "reviewer.mark.memory", "reviewer.label.statsHeader",
                "learning.system", "learning.mark.skip", "learning.label.peer.leaderboardHeader",
                "tool.peer_insights")) {
            assertThat(prompts.find(AgentLang.EN, key))
                    .as("英文词表缺 %s，回落成中文了", key)
                    .isNotEqualTo(prompts.find(AgentLang.ZH, key));
        }
    }

    // ==================== 造数 ====================

    private static AiTrader enTrader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(1);
        t.setSimUserId(99L);
        t.setName("Alpha");
        t.setSymbols("BTCUSDT,ETHUSDT");
        t.setIntervalCode("1h");
        return t;
    }

    private static LocalDateTime at(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static FuturesPositionDTO closedPos(String pnl, long openMs, long closeMs) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setStatus("CLOSED");
        p.setEntryPrice(new BigDecimal("100000"));
        p.setClosedPrice(new BigDecimal("103000"));
        p.setClosedPnl(new BigDecimal(pnl));
        p.setQuantity(new BigDecimal("0.01"));
        p.setCreatedAt(at(openMs));
        p.setUpdatedAt(at(closeMs));
        return p;
    }

    private static AiTraderDecision equityRow(long wakeTime, String equity) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(wakeTime);
        d.setEquity(new BigDecimal(equity));
        return d;
    }

    private static AiTraderPlan plan(String status) {
        AiTraderPlan p = new AiTraderPlan();
        p.setTraderId(7L);
        p.setRoundNo(1);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setStatus(status);
        p.setPlayType("BREAKOUT");
        p.setOpenedWakeTime(FROM + 3600_000);
        p.setSignalsUsed("daily above MA20");
        p.setInvalidationCondition("loses 99000");
        return p;
    }

    /** 四块素材全走英文：动作行/观望行/警报行/ERROR 行/价格路径都覆盖到 */
    private ReviewMaterialAssembler.ReviewMaterial enMaterial() {
        AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
        AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
        SimTradeClient sim = mock(SimTradeClient.class);
        KlineHistoryStore store = mock(KlineHistoryStore.class);

        when(decisionMapper.selectOne(any())).thenReturn(equityRow(FROM - 3600_000, "10000"));
        AiTraderDecision act = new AiTraderDecision();
        act.setWakeTime(FROM + 3600_000);
        act.setKind(AiTraderDecision.KIND_TRADE);
        act.setStatus(AiTraderDecision.STATUS_OK);
        act.setReasoning("[ROUND CONCLUSION]\nJudgement: breakout\nAction: long\nWaiting: none");
        act.setActionsJson("[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},"
                + "\"status\":\"pending\"}]");
        AiTraderDecision hold = new AiTraderDecision();
        hold.setWakeTime(FROM + 7200_000);
        hold.setKind(AiTraderDecision.KIND_ALERT);
        hold.setStatus(AiTraderDecision.STATUS_OK);
        hold.setReasoning("[ROUND CONCLUSION]\nJudgement: noise\nAction: HOLD\nWaiting: retest 99000");
        AiTraderDecision noWait = new AiTraderDecision();
        noWait.setWakeTime(FROM + 10800_000);
        noWait.setKind(AiTraderDecision.KIND_TRADE);
        noWait.setStatus(AiTraderDecision.STATUS_OK);
        AiTraderDecision err = new AiTraderDecision();
        err.setWakeTime(FROM + 14400_000);
        err.setKind(AiTraderDecision.KIND_TRADE);
        err.setStatus(AiTraderDecision.STATUS_ERROR);
        when(decisionMapper.selectList(any())).thenReturn(
                List.of(equityRow(FROM + 3600_000, "10500")),
                List.of(act, hold, noWait, err));
        when(sim.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(
                closedPos("300", FROM + 3600_000, FROM + 7200_000)));
        when(planMapper.selectList(any())).thenReturn(List.of(plan(AiTraderPlan.STATUS_CLOSED)));
        when(store.load(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                new KlineBar(FROM, FROM + 300_000, new BigDecimal("100000"), new BigDecimal("101000"),
                        new BigDecimal("99500"), new BigDecimal("100500"), BigDecimal.ONE)));

        AiTrader t = enTrader();
        t.setSymbols("BTCUSDT");
        return new ReviewMaterialAssembler(decisionMapper, planMapper, sim, store, prompts)
                .assemble(t, FROM, TO, AgentLang.EN);
    }

    private PeerInsightService enPeers() {
        AiTraderMapper traderMapper = mock(AiTraderMapper.class);
        AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
        AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
        SimTradeClient sim = mock(SimTradeClient.class);
        ReviewMaterialAssembler assembler = mock(ReviewMaterialAssembler.class);

        AiTrader me = enTrader();
        me.setStatus(AiTrader.STATUS_RUNNING);
        AiTrader other = enTrader();
        other.setId(8L);
        other.setName("Beta");
        other.setSimUserId(80L);
        other.setStatus(AiTrader.STATUS_LIQUIDATED);
        other.setLearningNotes("what I took from peers");

        when(traderMapper.selectList(any())).thenReturn(List.of(me, other));
        when(traderMapper.selectById(8L)).thenReturn(other);
        when(traderMapper.selectById(404L)).thenReturn(null);
        AiTraderDecision review = new AiTraderDecision();
        review.setWakeTime(TO);
        review.setReasoning("[REVIEW]\nScorecard: 10000 -> 11500\nRules: wait for the retest");
        when(assembler.lastReview(anyLong(), anyInt())).thenReturn(review);
        when(decisionMapper.selectOne(any())).thenReturn(equityRow(TO, "11500"));
        when(sim.getClosedPositions(anyLong(), anyInt())).thenReturn(List.of(
                closedPos("300", FROM + 3600_000, FROM + 7200_000)));
        when(planMapper.selectList(any())).thenReturn(List.of(
                plan(AiTraderPlan.STATUS_LIVE), plan(AiTraderPlan.STATUS_CLOSED)));
        return new PeerInsightService(traderMapper, decisionMapper, planMapper, sim, assembler, prompts);
    }

}

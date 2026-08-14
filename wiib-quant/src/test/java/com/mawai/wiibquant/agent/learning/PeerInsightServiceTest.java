package com.mawai.wiibquant.agent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同侪只读查询单测：排行榜的收益率/排序/样本量/自我标注，详情的四块与论点→结局配对。
 * 事实裁定归代码，这里的数字错一位，模型学到的就是别人根本没有的战绩。
 * mock 说明：复盘行走 assembler.lastReview（同一查询在 ReviewMaterialAssemblerTest 里已验），
 * 权益行按 wrapper 里的 traderId 分派——排行榜逐个 trader 查，按调用次序对号入座太脆。
 */
class PeerInsightServiceTest {

    /** 本局起点：某个整日边界 */
    private static final long T0 = 1785110400000L;

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final ReviewMaterialAssembler assembler = mock(ReviewMaterialAssembler.class);

    private final PeerInsightService service = new PeerInsightService(
            traderMapper, decisionMapper, planMapper, simTradeClient, assembler);

    // ==================== 造数 ====================

    private static AiTrader trader(long id, String name, String status) {
        AiTrader t = new AiTrader();
        t.setId(id);
        t.setName(name);
        t.setStatus(status);
        t.setRoundNo(1);
        t.setSimUserId(id * 10);   // simUserId 与 traderId 绑死，方便按账户 stub 已平仓位
        t.setSymbols("BTCUSDT");
        return t;
    }

    private static AiTraderDecision equityRow(String equity) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(T0 + 3600_000L);
        d.setEquity(new BigDecimal(equity));
        return d;
    }

    private static AiTraderDecision reviewRow(String reasoning) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(T0 + 86_400_000L);
        d.setKind(AiTraderDecision.KIND_REVIEW);
        d.setStatus(AiTraderDecision.STATUS_OK);
        d.setReasoning(reasoning);
        return d;
    }

    private static LocalDateTime at(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static FuturesPositionDTO closedPos(String side, String entry, String closed, String pnl,
                                                long openMs, long closeMs) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setSymbol("BTCUSDT");
        p.setSide(side);
        p.setStatus("CLOSED");
        p.setEntryPrice(new BigDecimal(entry));
        p.setClosedPrice(new BigDecimal(closed));
        p.setClosedPnl(new BigDecimal(pnl));
        p.setQuantity(new BigDecimal("0.01"));
        p.setCreatedAt(at(openMs));
        p.setUpdatedAt(at(closeMs));
        return p;
    }

    private static AiTraderPlan plan(String status, long openedWakeTime, String signals) {
        AiTraderPlan p = new AiTraderPlan();
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setPlayType("BREAKOUT");
        p.setSignalsUsed(signals);
        p.setInvalidationCondition("1h收盘跌回98000下方");
        p.setStatus(status);
        p.setOpenedWakeTime(openedWakeTime);
        return p;
    }

    /** 权益行按 wrapper 里的 traderId 认领；表里没有的 trader = 一次没醒过（返回 null） */
    private void stubEquity(Map<Long, String> equityByTrader) {
        when(decisionMapper.selectOne(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<AiTraderDecision> w = inv.getArgument(0);
            // MP 的条件值是懒求值的：先拼一次 SQL，参数才会落进 paramNameValuePairs
            w.getTargetSql();
            return equityByTrader.entrySet().stream()
                    .filter(e -> w.getParamNameValuePairs().containsValue(e.getKey()))
                    .findFirst()
                    .map(e -> equityRow(e.getValue()))
                    .orElse(null);
        });
    }

    private void stubTraders(AiTrader... traders) {
        when(traderMapper.selectList(any())).thenReturn(List.of(traders));
        for (AiTrader t : traders) {
            when(traderMapper.selectById(t.getId())).thenReturn(t);
        }
    }

    /** 该账户 n 笔已了结（笔数是样本量披露，内容不重要时用它凑数） */
    private void stubClosedCount(AiTrader t, int n) {
        List<FuturesPositionDTO> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(closedPos("LONG", "100000", "100100", "10", T0 + 60_000L, T0 + 120_000L));
        }
        when(simTradeClient.getClosedPositions(eq(t.getSimUserId()), anyInt())).thenReturn(list);
    }

    private static String lineOf(String block, String idTag) {
        return block.lines().filter(l -> l.contains(idTag)).findFirst().orElseThrow();
    }

    // ==================== 排行榜 ====================

    /**
     * 收益率 = 最新权益 vs 初始资金 10000，按降序排；自己那行必须标出来——
     * 不标，模型会把自己的战绩当外人的经验再学一遍。
     */
    @Test
    void 排行榜按收益率降序且自己那行有标注() {
        AiTrader me = trader(7L, "我", AiTrader.STATUS_RUNNING);
        AiTrader win = trader(8L, "赢家", AiTrader.STATUS_RUNNING);
        AiTrader lose = trader(9L, "输家", AiTrader.STATUS_RUNNING);
        stubTraders(me, win, lose);
        stubEquity(Map.of(7L, "10500", 8L, "12000", 9L, "9000"));
        stubClosedCount(me, 2);
        stubClosedCount(win, 3);
        stubClosedCount(lose, 1);

        String out = service.leaderboard(7L);

        // +20% > +5% > -10%
        assertThat(out).contains("+20.00%").contains("+5.00%").contains("-10.00%");
        assertThat(out.indexOf("赢家")).isLessThan(out.indexOf("我 ｜"));
        assertThat(out.indexOf("我 ｜")).isLessThan(out.indexOf("输家"));
        // 只有自己那行带标注
        assertThat(lineOf(out, "[id=7]")).contains("（这是你）");
        assertThat(lineOf(out, "[id=8]")).doesNotContain("（这是你）");
        assertThat(lineOf(out, "[id=9]")).doesNotContain("（这是你）");
    }

    /** 不同意学习的双向出局（数据侧）：不上榜——不勾选的人不该出现在任何人的学习素材里 */
    @Test
    void 不同意学习的不上排行榜() {
        AiTrader me = trader(7L, "我", AiTrader.STATUS_RUNNING);
        AiTrader optOut = trader(8L, "独行侠", AiTrader.STATUS_RUNNING);
        optOut.setLearningEnabled(false);
        stubTraders(me, optOut);
        stubEquity(Map.of(7L, "10500", 8L, "12000"));
        stubClosedCount(me, 2);
        stubClosedCount(optOut, 3);

        String out = service.leaderboard(7L);

        assertThat(out).contains("[id=7]").doesNotContain("[id=8]").doesNotContain("独行侠");
    }

    /** 不同意学习的双向出局（detail 侧）：拿旧 id 直查也要拒，中文文本透传给模型自己换人 */
    @Test
    void 不同意学习的detail拒查() {
        AiTrader optOut = trader(8L, "独行侠", AiTrader.STATUS_RUNNING);
        optOut.setLearningEnabled(false);
        stubTraders(optOut);

        assertThat(service.detail(8L)).contains("未开启同侪学习共享");
    }

    /** 好的和差的都看：已暂停/已爆仓不许从榜上消失，爆仓那份是前车之鉴 */
    @Test
    void 三种状态都上榜且渲染成中文() {
        AiTrader run = trader(7L, "在跑", AiTrader.STATUS_RUNNING);
        AiTrader paused = trader(8L, "歇了", AiTrader.STATUS_PAUSED);
        AiTrader dead = trader(9L, "炸了", AiTrader.STATUS_LIQUIDATED);
        stubTraders(run, paused, dead);
        stubEquity(Map.of(7L, "10100", 8L, "9900", 9L, "100"));
        stubClosedCount(run, 1);
        stubClosedCount(paused, 1);
        stubClosedCount(dead, 5);

        String out = service.leaderboard(7L);

        assertThat(lineOf(out, "[id=7]")).contains("运行中");
        assertThat(lineOf(out, "[id=8]")).contains("已暂停");
        assertThat(lineOf(out, "[id=9]")).contains("已爆仓").contains("-99.00%");
    }

    /** 样本量披露：引用同侪战绩必须带笔数，每一行都得有，缺一行模型就能拿它当"没风险的经验" */
    @Test
    void 每行都披露已了结笔数() {
        AiTrader a = trader(7L, "甲", AiTrader.STATUS_RUNNING);
        AiTrader b = trader(8L, "乙", AiTrader.STATUS_RUNNING);
        stubTraders(a, b);
        stubEquity(Map.of(7L, "11000", 8L, "10500"));
        stubClosedCount(a, 12);
        stubClosedCount(b, 1);

        String out = service.leaderboard(7L);

        assertThat(lineOf(out, "[id=7]")).contains("已了结 12 笔");
        assertThat(lineOf(out, "[id=8]")).contains("已了结 1 笔");
        assertThat(out).contains("样本量");
    }

    /** 开局还没醒过（无决策行）：收益率是 0 不是负数，也不是空白 */
    @Test
    void 无决策行的收益率算零() {
        AiTrader t = trader(7L, "新兵", AiTrader.STATUS_PAUSED);
        stubTraders(t);
        stubEquity(Map.of());
        stubClosedCount(t, 0);

        String out = service.leaderboard(7L);

        assertThat(lineOf(out, "[id=7]")).contains("+0.00%").contains("已了结 0 笔");
    }

    /** 排行榜要的是一句话画像：跳过【本期复盘】标题行，取首个实质内容行并截断 */
    @Test
    void 复盘摘要跳过标题行并截断() {
        AiTrader t = trader(7L, "话痨", AiTrader.STATUS_RUNNING);
        stubTraders(t);
        stubEquity(Map.of(7L, "10000"));
        stubClosedCount(t, 4);
        String longLine = "战绩：" + "长".repeat(120);
        when(assembler.lastReview(7L, 1)).thenReturn(reviewRow("【本期复盘】\n" + longLine + "\n下期纪律：等回踩"));

        String out = service.leaderboard(7L);

        // 标题行被跳过，取到的是下一行正文；正文只留前 80 字加省略号
        assertThat(out).doesNotContain("【本期复盘】");
        assertThat(lineOf(out, "[id=7]"))
                .contains(longLine.substring(0, PeerInsightService.DIGEST_MAX_CHARS) + "…")
                .doesNotContain(longLine);
    }

    /** 还没复盘过的同侪：明写尚无，不许拿空白冒充"它没话说" */
    @Test
    void 无复盘写尚无复盘() {
        AiTrader t = trader(7L, "闷葫芦", AiTrader.STATUS_RUNNING);
        stubTraders(t);
        stubEquity(Map.of(7L, "10000"));
        stubClosedCount(t, 0);
        when(assembler.lastReview(7L, 1)).thenReturn(null);

        assertThat(service.leaderboard(7L)).contains("（尚无复盘）");
    }

    // ==================== 单 trader 详情 ====================

    /**
     * 四块齐全：复盘全文（不是摘要）、学习笔记、在场计划的论点与失效条件、
     * 最近已了结交易的论点→结局配对（配对与了结方式都复用复盘那套算法）。
     */
    @Test
    void 详情四块齐全且配对到计划() {
        AiTrader t = trader(8L, "老手", AiTrader.STATUS_RUNNING);
        t.setLearningNotes("【已验证纪律】跟着 9 号的回踩打法做，样本 4 笔");
        stubTraders(t);
        stubEquity(Map.of(8L, "11500"));
        when(assembler.lastReview(8L, 1)).thenReturn(reviewRow(
                "【本期复盘】\n战绩：起始 10000 → 期末 11500\n下期纪律：突破必须等回踩确认"));

        FuturesPositionDTO pos = closedPos("LONG", "100000", "95000", "-50",
                T0 + 3600_000L, T0 + 21600_000L);
        pos.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("95500"), new BigDecimal("0.01"))));
        when(simTradeClient.getClosedPositions(eq(80L), anyInt())).thenReturn(List.of(pos));

        AiTraderPlan closedPlan = plan(AiTraderPlan.STATUS_CLOSED, T0 + 3600_000L, "突破前高+量比1.8");
        AiTraderPlan livePlan = plan(AiTraderPlan.STATUS_LIVE, T0 + 72000_000L, "回踩20日线不破");
        livePlan.setInvalidationCondition("4h收盘跌破99000");
        when(planMapper.selectList(any())).thenReturn(List.of(closedPlan, livePlan));

        String out = service.detail(8L);

        // 头部与排行榜同口径
        assertThat(out).contains("老手").contains("[id=8]").contains("运行中")
                .contains("+15.00%").contains("已了结 1 笔");
        // 复盘全文（连下期纪律一起给，摘要在排行榜上已经有了）
        assertThat(out).contains("【最新复盘（全文）】").contains("下期纪律：突破必须等回踩确认");
        // 学习笔记原样
        assertThat(out).contains("【学习笔记】").contains("跟着 9 号的回踩打法");
        // 在场计划的论点与失效条件
        assertThat(out).contains("【当前在场计划】").contains("回踩20日线不破").contains("4h收盘跌破99000");
        // 论点→结局：入场/出场/盈亏/持有时长/了结方式/论点一行齐
        assertThat(out).contains("【最近已了结交易】")
                .contains("100000").contains("95000").contains("-50")
                .contains("5小时").contains("止损带走").contains("突破前高+量比1.8");
    }

    /**
     * 配对必须认开仓时刻最贴近的计划：两笔两计划错配的话，学到的就是"张三的论点配李四的结局"，
     * 比没有配对更坏。列表按时间倒序，所以晚的那笔（B）在前。
     */
    @Test
    void 多笔交易按开仓时刻各自配对() {
        AiTrader t = trader(8L, "老手", AiTrader.STATUS_RUNNING);
        stubTraders(t);
        stubEquity(Map.of(8L, "10000"));
        // sim 侧按 updatedAt 倒序返回：晚平的 B 在前
        FuturesPositionDTO late = closedPos("LONG", "102000", "96000", "-60",
                T0 + 18000_000L, T0 + 21600_000L);
        FuturesPositionDTO early = closedPos("LONG", "100000", "101000", "10",
                T0 + 3600_000L, T0 + 7200_000L);
        when(simTradeClient.getClosedPositions(eq(80L), anyInt())).thenReturn(List.of(late, early));
        when(planMapper.selectList(any())).thenReturn(List.of(
                plan(AiTraderPlan.STATUS_CLOSED, T0 + 3600_000L, "A论点早"),
                plan(AiTraderPlan.STATUS_CLOSED, T0 + 18000_000L, "B论点晚")));

        String out = service.detail(8L);

        assertThat(out).contains("A论点早").contains("B论点晚");
        assertThat(out.indexOf("B论点晚")).isLessThan(out.indexOf("A论点早"));
    }

    /** 空态照说清楚：没复盘、没学习笔记、没在场计划、没成交，四块都得留话 */
    @Test
    void 详情空态四块都有交代() {
        AiTrader t = trader(9L, "新号", AiTrader.STATUS_PAUSED);
        stubTraders(t);
        stubEquity(Map.of());
        when(assembler.lastReview(9L, 1)).thenReturn(null);
        when(simTradeClient.getClosedPositions(eq(90L), anyInt())).thenReturn(List.of());
        when(planMapper.selectList(any())).thenReturn(List.of());

        String out = service.detail(9L);

        assertThat(out).contains("（尚无复盘）").contains("（尚无学习笔记）")
                .contains("（当前空仓，无在场计划）").contains("（本局尚无已了结交易）");
        assertThat(out).contains("已暂停").contains("+0.00%");
    }

    /** 查无此人给中文错误文本，不抛异常——这段话会原样透传给模型让它自己改 id */
    @Test
    void 未知traderId返回中文错误文本() {
        when(traderMapper.selectById(404L)).thenReturn(null);

        String out = service.detail(404L);

        assertThat(out).contains("查无此 trader").contains("404");
    }
}

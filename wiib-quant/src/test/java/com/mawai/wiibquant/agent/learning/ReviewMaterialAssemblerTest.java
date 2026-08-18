package com.mawai.wiibquant.agent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 复盘素材组装单测：四块硬事实全部代码算，数字必须对——模型只许复述，代码错一位就是复盘造假。
 * mock 说明：decisionMapper.selectList 按调用顺序先权益序列后时间线（assemble 内查询顺序固定）。
 */
class ReviewMaterialAssemblerTest {

    /** 窗口起点：某个整日边界；窗口 = (FROM, TO]，一天 */
    private static final long FROM = 1785110400000L;
    private static final long TO = FROM + 86_400_000L;

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final KlineHistoryStore historyStore = mock(KlineHistoryStore.class);

    private final ReviewMaterialAssembler assembler = new ReviewMaterialAssembler(
            decisionMapper, planMapper, simTradeClient, historyStore);

    private AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setRoundNo(1);
        t.setSimUserId(99L);
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        return t;
    }

    private static AiTraderDecision equityRow(long wakeTime, String equity) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(wakeTime);
        d.setEquity(new BigDecimal(equity));
        return d;
    }

    private static AiTraderDecision okRow(long wakeTime, String kind, String reasoning, String actionsJson) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(wakeTime);
        d.setKind(kind);
        d.setStatus(AiTraderDecision.STATUS_OK);
        d.setReasoning(reasoning);
        d.setActionsJson(actionsJson);
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

    // ==================== 战绩表 ====================

    @Test
    void statsComputedFromEquitySeriesAndClosedTrades() {
        // 窗口起点前最后一条权益 10000；窗口内 10200 → 9800 → 10500
        when(decisionMapper.selectOne(any())).thenReturn(equityRow(FROM - 3600_000, "10000"));
        when(decisionMapper.selectList(any())).thenReturn(
                List.of(equityRow(FROM + 3600_000, "10200"),
                        equityRow(FROM + 7200_000, "9800"),
                        equityRow(FROM + 10800_000, "10500")),
                List.of());
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(
                closedPos("LONG", "100000", "103000", "300", FROM + 3600_000, FROM + 7200_000),
                closedPos("LONG", "100000", "99000", "-100", FROM + 7200_000, FROM + 10800_000)));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        // 收益率 (10500-10000)/10000=+5%；回撤峰10200谷9800=3.92%；2笔1胜1负
        assertThat(m.statsBlock()).contains("10000").contains("10500").contains("+5.00%");
        assertThat(m.statsBlock()).contains("3.92%");
        assertThat(m.statsBlock()).contains("2 笔").contains("50%");
        assertThat(m.closedTrades()).isEqualTo(2);
    }

    @Test
    void startEquityFallsBackToInitialBalanceWhenNoPriorRow() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(
                List.of(equityRow(FROM + 3600_000, "10100")), List.of());

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        // 无前值 → 起始按初始资金 10000：(10100-10000)/10000=+1%
        assertThat(m.statsBlock()).contains("+1.00%");
    }

    /**
     * 战绩表块头写着"代码统计，只许原样复述，禁止自行计算"，但已平仓位是先取最近 200 条再按窗口过滤，
     * 无分页无溢出检测——5m 档一天成交超 200 笔时这份"硬事实"本身就是错的，而模型被明令不许核算，
     * 错数字会原样进 memory 长期传播。取回条数顶到上限时必须在块头说清楚这是不完全统计。
     */
    @Test
    void statsWarnsWhenClosedFetchHitsLimit() {
        List<FuturesPositionDTO> full = new ArrayList<>();
        for (int i = 0; i < ReviewMaterialAssembler.CLOSED_FETCH_LIMIT; i++) {
            full.add(closedPos("LONG", "100000", "100100", "10", FROM + 60_000, FROM + 120_000));
        }
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(full);
        when(decisionMapper.selectOne(any())).thenReturn(null);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        assertThat(m.statsBlock()).contains("不完全统计").contains("200");
    }

    /** 没顶到上限就别乱贴警示——常规复盘的战绩表得是干净的硬事实 */
    @Test
    void statsHasNoTruncationWarningBelowLimit() {
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(
                closedPos("LONG", "100000", "103000", "300", FROM + 3600_000, FROM + 7200_000)));
        when(decisionMapper.selectOne(any())).thenReturn(null);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        assertThat(m.statsBlock()).doesNotContain("不完全统计");
    }

    // ==================== 配对表与了结方式 ====================

    @Test
    void closedTradePairedWithArchivedPlan() {
        FuturesPositionDTO pos = closedPos("LONG", "100000", "95000", "-50", FROM + 3600_000, FROM + 21600_000);
        pos.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("95500"), new BigDecimal("0.01"))));
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos));

        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setPlayType("BREAKOUT");
        plan.setSignalsUsed("突破前高+量比1.8");
        plan.setInvalidationCondition("1h收盘跌回98000下方");
        plan.setStatus(AiTraderPlan.STATUS_CLOSED);
        plan.setOpenedWakeTime(FROM + 3600_000);
        plan.setClosedWakeTime(FROM + 25200_000);
        when(planMapper.selectList(any())).thenReturn(List.of(plan));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        // 论点→结局配对：playType/失效条件/入场/出场/盈亏/持有时长/了结方式一行齐
        assertThat(m.tradesBlock()).contains("BREAKOUT").contains("98000")
                .contains("100000").contains("95000").contains("-50")
                .contains("5小时").contains("止损带走");
    }

    @Test
    void closeMannerInferredDirectionally() {
        // 强平状态直判
        FuturesPositionDTO liq = closedPos("LONG", "100000", "90000", "-500", FROM, FROM + 1);
        liq.setStatus("LIQUIDATED");
        assertThat(ReviewMaterialAssembler.closeManner(liq)).isEqualTo("强平");

        // 触发价是探测时的markPrice会越过挂单价：方向性对照而非相等
        FuturesPositionDTO sl = closedPos("LONG", "100000", "95400", "-46", FROM, FROM + 1);
        sl.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("95500"), new BigDecimal("0.01"))));
        assertThat(ReviewMaterialAssembler.closeManner(sl)).isEqualTo("止损带走");

        FuturesPositionDTO tp = closedPos("SHORT", "100000", "94900", "51", FROM, FROM + 1);
        tp.setTakeProfits(List.of(new FuturesTakeProfit("t1", new BigDecimal("95000"), new BigDecimal("0.01"))));
        assertThat(ReviewMaterialAssembler.closeManner(tp)).isEqualTo("止盈带走");

        // 保护单实时监控在先，带内成交只能是主动平仓（模型自平或审批执行）
        FuturesPositionDTO manual = closedPos("LONG", "100000", "101000", "10", FROM, FROM + 1);
        manual.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("95500"), new BigDecimal("0.01"))));
        manual.setTakeProfits(List.of(new FuturesTakeProfit("t1", new BigDecimal("110000"), new BigDecimal("0.01"))));
        assertThat(ReviewMaterialAssembler.closeManner(manual)).isEqualTo("主动平仓");
    }

    @Test
    void unmatchedClosedPlanListedAsUnfilled() {
        // 挂单未成交撤销：计划归档了但没有对应已平仓位，论点没得到执行机会也要留痕
        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol("ETHUSDT");
        plan.setSide("SHORT");
        plan.setPlayType("RANGE");
        plan.setInvalidationCondition("4h收盘站上3200");
        plan.setStatus(AiTraderPlan.STATUS_CLOSED);
        plan.setOpenedWakeTime(FROM + 3600_000);
        plan.setClosedWakeTime(FROM + 7200_000);
        when(planMapper.selectList(any())).thenReturn(List.of(plan));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        assertThat(m.tradesBlock()).contains("ETHUSDT").contains("RANGE").contains("未配对");
    }

    // ==================== 时间线摘编 ====================

    @Test
    void timelineKeepsConclusionBlocksAndAggregatesErrors() {
        String openActions = "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\","
                + "\"quantity\":0.01},\"result\":\"ok\"}]";
        AiTraderDecision act = okRow(FROM + 3600_000, AiTraderDecision.KIND_TRADE,
                "行情分析……【本轮结论】\n判断：突破确认站上100500\n动作：开多BTCUSDT 0.01\n等待：无", openActions);
        AiTraderDecision hold = okRow(FROM + 7200_000, AiTraderDecision.KIND_TRADE,
                "检查了持仓……【本轮结论】\n判断：趋势未变101200上方震荡\n动作：HOLD\n等待：1h收盘跌破100500减仓", "[]");
        AiTraderDecision alert = okRow(FROM + 9000_000, AiTraderDecision.KIND_ALERT,
                "被警报唤醒……【本轮结论】\n判断：急跌未破位\n动作：HOLD\n等待：不变", "[]");
        AiTraderDecision err = new AiTraderDecision();
        err.setWakeTime(FROM + 10800_000);
        err.setKind(AiTraderDecision.KIND_TRADE);
        err.setStatus(AiTraderDecision.STATUS_ERROR);
        err.setError("唤醒超时(600s)");
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(
                List.of(), List.of(act, hold, alert, err));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        // 动作行带工具摘要+结论；HOLD行压缩但等待条件必须保住（观望对账的原料）；警报行有标记；ERROR聚合计数
        assertThat(m.timelineBlock()).contains("open_position").contains("突破确认");
        assertThat(m.timelineBlock()).contains("1h收盘跌破100500减仓");
        assertThat(m.timelineBlock()).contains("警报");
        assertThat(m.timelineBlock()).contains("1 轮 ERROR");
        // 活动统计头：保守度自检的对照物（唤醒计全部轮含 ERROR，动作轮/开仓只数 OK 行）
        assertThat(m.timelineBlock()).contains("本期活动：唤醒 4 轮，动作轮 1，开仓动作 1 次");
    }

    /** 转成待确认请求的动作没有成交，摘编里必须标出来——否则复盘会把"提了个请求"当成"平了仓" */
    @Test
    void timelineMarksPendingActionsAsAwaitingApproval() {
        String pendingAction = "[{\"tool\":\"close_position\",\"args\":{\"positionId\":5,\"quantity\":0.01},"
                + "\"status\":\"pending\",\"result\":\"减仓请求已提交给主人确认\"}]";
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(List.of(), List.of(
                okRow(FROM + 3600_000, AiTraderDecision.KIND_TRADE,
                        "【本轮结论】\n判断：失效条件触发\n动作：申请减仓\n等待：主人确认", pendingAction)));

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        assertThat(m.timelineBlock()).contains("close_position").contains("待确认");
    }

    @Test
    void timelineCompressesOldHoldsWhenOverCap() {
        List<AiTraderDecision> rows = new ArrayList<>();
        // 200 条 HOLD + 最早的 1 条动作行：动作行必须保住，早段观望被省略且有说明。
        // 等待条件逐条不同，否则会先被合并压成一段、根本走不到上限这条路上
        rows.add(okRow(FROM + 60_000, AiTraderDecision.KIND_TRADE,
                "【本轮结论】\n判断：早段开仓\n动作：开多\n等待：无",
                "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\"},\"result\":\"ok\"}]"));
        for (int i = 1; i <= 200; i++) {
            rows.add(okRow(FROM + 60_000L + i * 300_000L, AiTraderDecision.KIND_TRADE,
                    "【本轮结论】\n判断：无事(" + i + ")\n动作：HOLD\n等待：回踩 " + (100000 + i) + " 再评估", "[]"));
        }
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(List.of(), rows);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        assertThat(m.timelineBlock()).contains("open_position");
        assertThat(m.timelineBlock()).contains("已省略");
        // 上限内：条目数 = 动作1 + 补齐的最新观望段
        long lines = m.timelineBlock().lines().filter(l -> l.startsWith("- ")).count();
        assertThat(lines).isLessThanOrEqualTo(ReviewMaterialAssembler.MAX_TIMELINE_ENTRIES);
    }

    /**
     * 等待条件分条换行写是模型的常态写法。早先按行 startsWith 取，只捞得到"等待："标签行本身，
     * 后面几条 bullet 整段丢掉——线上 59 条观望里 49 条就这么丢的，观望对账一直在空转。
     * 这条是那个 bug 的回归闸。
     */
    @Test
    void waitSectionReadsMultiLineBullets() {
        String reasoning = """
                空仓，无旧计划可验。【本轮结论】
                判断：BTC 63636、ETH 1906，15m 均为 TREND_UP，但价格已贴上轨
                动作：HOLD，不开仓
                计划依据：账户空仓，无持仓计划需要维护
                等待：
                - BTC 多：15m 回踩 63370–63480 且收盘仍站上 63280，目标 64000–64450
                - ETH 多：15m 回踩 1896–1901 且收盘站上 1892，目标 1925/1937
                - 转空：BTC 15m 收盘跌破 63140；ETH 15m 收盘跌破 1888""";

        String wait = ReviewMaterialAssembler.waitSection(reasoning);

        assertThat(wait).contains("63370–63480").contains("1896–1901").contains("63140");
        // 判断段是当时的指标读数，复盘没有对账物，不该混进等待里占额度
        assertThat(wait).doesNotContain("TREND_UP");
    }

    /**
     * 连续同一等待条件压成一段：15m 档一天 96 轮，行情不动时几十轮等的是同一句话。
     * 括号里的依据说明每轮微动不算条件变化；条件真变了要断开；警报轮不并进例行观望。
     */
    @Test
    void timelineMergesConsecutiveSameWaits() {
        List<AiTraderDecision> rows = List.of(
                okRow(FROM + 900_000, AiTraderDecision.KIND_TRADE,
                        "【本轮结论】\n判断：贴上轨\n动作：HOLD\n等待：回踩 63370–63480（MA20 63450）后再评估", "[]"),
                okRow(FROM + 1800_000, AiTraderDecision.KIND_TRADE,
                        "【本轮结论】\n判断：浅回撤\n动作：HOLD\n等待：回踩 63370–63480（MA20 63465）后再评估", "[]"),
                okRow(FROM + 2700_000, AiTraderDecision.KIND_TRADE,
                        "【本轮结论】\n判断：继续走弱\n动作：HOLD\n等待：跌破 63140 转空", "[]"),
                okRow(FROM + 3600_000, AiTraderDecision.KIND_ALERT,
                        "【本轮结论】\n判断：急跌\n动作：HOLD\n等待：跌破 63140 转空", "[]"));
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(List.of(), rows);

        String timeline = assembler.assemble(trader(), FROM, TO).timelineBlock();

        // 前两轮括号内注解不同但价位一致 → 一段两轮；后两轮条件相同但一例行一警报 → 不并
        assertThat(timeline).contains("（2轮）");
        assertThat(timeline).contains("[警报]");
        assertThat(timeline.lines().filter(l -> l.startsWith("- ")).count()).isEqualTo(3);
        // 合并只省字，轮数统计仍按原始行走，保守度自检的对照物不能缩水
        assertThat(timeline).contains("本期活动：唤醒 4 轮");
    }

    // ==================== 价格路径 ====================

    /** 5m bar 造数：一根 5m 的 OHLC */
    private static KlineBar bar5m(long openTime, String o, String h, String l, String c) {
        return new KlineBar(openTime, openTime + 300_000L - 1,
                new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c), BigDecimal.ZERO);
    }

    /**
     * 本地 5m 现聚合成 1h：开=组内首根开、高/低=组内极值、收=组内末根收。
     * 这块是观望对账的对照物，聚合错一位复盘的价格证据就是假的。
     */
    @Test
    void pricePathAggregates5mIntoHourly() {
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong())).thenReturn(List.of(
                // 第一个整点：开61000 高61500 低60800 收61200
                bar5m(FROM, "61000", "61200", "60800", "61100"),
                bar5m(FROM + 300_000L, "61100", "61500", "61000", "61200"),
                // 第二个整点：开61200 高64000 低61100 收63500
                bar5m(FROM + 3600_000L, "61200", "62000", "61100", "61900"),
                bar5m(FROM + 3900_000L, "61900", "64000", "61800", "63500")));
        when(decisionMapper.selectOne(any())).thenReturn(null);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        // 开=61000 收=63500 高=64000 低=60800，涨跌幅 (63500-61000)/61000=+4.10%
        assertThat(m.pricePathBlock()).contains("61000").contains("63500")
                .contains("64000").contains("60800").contains("+4.10%");
        // 逐小时收盘只该有两根（每组末根收），不是四根 5m
        assertThat(m.pricePathBlock()).contains("1h收盘: 61200→63500");
        // 逐小时高低是组内极值：等待条件多是"回踩到某区间"，只给收盘判不出这一小时探到过没有
        assertThat(m.pricePathBlock()).contains("1h高/低: 61500/60800→64000/61100");
    }

    /**
     * 本局首篇复盘 fromMs=0，价格路径回看上限 48h，很可能早于本局开局——
     * 块头必须把真实覆盖范围写出来并点明可能含开局前行情，否则模型会拿开局前的价格
     * 给"等待条件"做观望对账（对账的对照物错了，结论就是假的）。
     */
    @Test
    void firstReviewPricePathDeclaresRangeAndPreRoundRisk() {
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong()))
                .thenReturn(List.of(bar5m(TO - 300_000L, "61000", "61500", "60800", "61200")));
        when(decisionMapper.selectOne(any())).thenReturn(null);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), 0L, TO);

        assertThat(m.pricePathBlock()).contains("覆盖").contains("开局前");
    }

    /** 常规窗口的复盘：块头照样标覆盖范围，但不该有开局前警示 */
    @Test
    void regularReviewPricePathDeclaresRangeOnly() {
        when(historyStore.load(eq("BTCUSDT"), eq("5m"), anyLong(), anyLong()))
                .thenReturn(List.of(bar5m(FROM, "61000", "61500", "60800", "61200")));
        when(decisionMapper.selectOne(any())).thenReturn(null);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        assertThat(m.pricePathBlock()).contains("覆盖").doesNotContain("开局前");
    }

    /** 库里这段没数据（缺口/新币）：写明无数据，不挡其余三块素材 */
    @Test
    void pricePathMissingDataDoesNotBlockAssembly() {
        when(historyStore.load(any(), any(), anyLong(), anyLong())).thenReturn(List.of());
        when(decisionMapper.selectOne(any())).thenReturn(null);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        assertThat(m.pricePathBlock()).contains("无K线数据");
        assertThat(m.statsBlock()).contains("【战绩表】");
    }

    // ==================== 素材有无与上次复盘定位 ====================

    @Test
    void hasNewMaterialCountsTradeAndAlertOkRows() {
        when(decisionMapper.selectCount(any())).thenReturn(3L, 0L);
        assertThat(assembler.hasNewMaterial(7L, 1, FROM, TO)).isTrue();
        assertThat(assembler.hasNewMaterial(7L, 1, FROM, TO)).isFalse();
    }

    /**
     * 素材判定必须是交易行白名单（TRADE/ALERT/MANUAL），不是 ne(REVIEW) 黑名单：
     * LEARN 行每天必有一条，黑名单会让它天天充当"新素材"，无交易的日子复盘再也跳不过去。
     */
    @Test
    void hasNewMaterial按交易行白名单过滤() {
        when(decisionMapper.selectCount(any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiTraderDecision> w =
                    inv.getArgument(0);
            // MP 条件值懒求值：先拼一次 SQL，参数才落进 paramNameValuePairs
            w.getTargetSql();
            var values = w.getParamNameValuePairs().values();
            assertThat(values).contains(AiTraderDecision.KIND_TRADE,
                    AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL);
            assertThat(values).doesNotContain(AiTraderDecision.KIND_REVIEW, AiTraderDecision.KIND_LEARN);
            return 1L;
        });

        assertThat(assembler.hasNewMaterial(7L, 1, FROM, TO)).isTrue();
    }

    @Test
    void lastReviewReturnsNullWhenNone() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        assertThat(assembler.lastReview(7L, 1)).isNull();

        AiTraderDecision review = new AiTraderDecision();
        review.setWakeTime(FROM);
        review.setReasoning("【本期复盘】上期纪律：无上期纪律");
        when(decisionMapper.selectOne(any())).thenReturn(review);
        // 一次查询两用：窗口起点 + 回注本期承接检验的全文
        assertThat(assembler.lastReview(7L, 1).getWakeTime()).isEqualTo(FROM);
        assertThat(assembler.lastReview(7L, 1).getReasoning()).contains("上期纪律");
    }
}

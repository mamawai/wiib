package com.mawai.wiibquant.agent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
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
    private final BinanceRestClient binanceRestClient = mock(BinanceRestClient.class);

    private final ReviewMaterialAssembler assembler = new ReviewMaterialAssembler(
            decisionMapper, planMapper, simTradeClient, binanceRestClient);

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
    }

    @Test
    void timelineCompressesOldHoldsWhenOverCap() {
        List<AiTraderDecision> rows = new ArrayList<>();
        // 200 条纯 HOLD + 最早的 1 条动作行：动作行必须保住，早段 HOLD 被省略且有说明
        rows.add(okRow(FROM + 60_000, AiTraderDecision.KIND_TRADE,
                "【本轮结论】\n判断：早段开仓\n动作：开多\n等待：无",
                "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\"},\"result\":\"ok\"}]"));
        for (int i = 1; i <= 200; i++) {
            rows.add(okRow(FROM + 60_000L + i * 300_000L, AiTraderDecision.KIND_TRADE,
                    "【本轮结论】\n判断：无事(" + i + ")\n动作：HOLD\n等待：无", "[]"));
        }
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(List.of(), rows);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        assertThat(m.timelineBlock()).contains("open_position");
        assertThat(m.timelineBlock()).contains("已省略");
        // 上限内：条目数 = 动作1 + 补齐的最新HOLD
        long lines = m.timelineBlock().lines().filter(l -> l.startsWith("- ")).count();
        assertThat(lines).isLessThanOrEqualTo(ReviewMaterialAssembler.MAX_TIMELINE_ENTRIES);
    }

    // ==================== 价格路径 ====================

    @Test
    void pricePathSummarizesHourlyOhlc() {
        // [openTime, open, high, low, close, ...]
        when(binanceRestClient.getKlines(eq("BTCUSDT"), eq("1h"), anyInt(), anyLong())).thenReturn("""
                [[%d,"61000","61500","60800","61200","0",0,"0",0,"0","0","0"],
                 [%d,"61200","64000","61100","63500","0",0,"0",0,"0","0","0"]]"""
                .formatted(FROM, FROM + 3600_000));
        when(decisionMapper.selectOne(any())).thenReturn(null);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        // 开=首根open 收=末根close 高=64000 低=60800，涨跌幅 (63500-61000)/61000=+4.10%
        assertThat(m.pricePathBlock()).contains("61000").contains("63500")
                .contains("64000").contains("60800").contains("+4.10%");
    }

    @Test
    void pricePathFailureDoesNotBlockAssembly() {
        when(binanceRestClient.getKlines(any(), any(), anyInt(), anyLong()))
                .thenThrow(new IllegalStateException("binance 503"));
        when(decisionMapper.selectOne(any())).thenReturn(null);

        ReviewMaterialAssembler.ReviewMaterial m = assembler.assemble(trader(), FROM, TO);

        assertThat(m.pricePathBlock()).contains("获取失败");
    }

    // ==================== 素材有无与上次复盘定位 ====================

    @Test
    void hasNewMaterialCountsTradeAndAlertOkRows() {
        when(decisionMapper.selectCount(any())).thenReturn(3L, 0L);
        assertThat(assembler.hasNewMaterial(7L, 1, FROM, TO)).isTrue();
        assertThat(assembler.hasNewMaterial(7L, 1, FROM, TO)).isFalse();
    }

    @Test
    void lastSuccessfulReviewWakeReturnsNullWhenNone() {
        when(decisionMapper.selectOne(any())).thenReturn(null);
        assertThat(assembler.lastSuccessfulReviewWake(7L, 1)).isNull();

        AiTraderDecision review = new AiTraderDecision();
        review.setWakeTime(FROM);
        when(decisionMapper.selectOne(any())).thenReturn(review);
        assertThat(assembler.lastSuccessfulReviewWake(7L, 1)).isEqualTo(FROM);
    }
}

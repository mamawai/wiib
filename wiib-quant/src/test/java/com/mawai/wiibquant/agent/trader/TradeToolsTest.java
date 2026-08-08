package com.mawai.wiibquant.agent.trader;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
import com.mawai.wiibquant.mapper.AiTraderPlanMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 持仓管理工具的纪律约束直测：止损只许收紧、止盈只许远离入场、计划不可改写只可修订留痕。
 * 这些约束是"计划=事前承诺"的工具层执行——提示词只能劝，工具拒绝才是真禁止。
 */
class TradeToolsTest {

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
    private final TraderRequestService requestService = mock(TraderRequestService.class);
    /** 本类只验工具本身：自主加/减仓都开着，不走审批分流 */
    private final TradeTools tools = new TradeTools(simTradeClient, 99L, Set.of("BTCUSDT"),
            new BigDecimal("10000"), sym -> new BigDecimal("100000"),
            new TraderPlanStore(planMapper), requestService,
            new TradeTools.WakeCtx(7L, 1, 1785171600000L,
                    new TraderRiskConfig(1, 20, new BigDecimal("1"), new BigDecimal("50"),
                            true, true, true, true)));

    /** 多单：入场10万，当前止损9.5万、止盈11万 */
    private FuturesPositionDTO longPosition() {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(5L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setQuantity(new BigDecimal("0.01"));
        p.setEntryPrice(new BigDecimal("100000"));
        p.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("95000"), new BigDecimal("0.01"))));
        p.setTakeProfits(List.of(new FuturesTakeProfit("t1", new BigDecimal("110000"), new BigDecimal("0.01"))));
        p.setCreatedAt(LocalDateTime.of(2026, 8, 6, 18, 0));
        return p;
    }

    private AiTraderPlan existingPlan() {
        AiTraderPlan plan = new AiTraderPlan();
        plan.setId(21L);
        plan.setTraderId(7L);
        plan.setRoundNo(1);
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setPlayType("BREAKOUT");
        plan.setSignalsUsed("突破前高");
        plan.setInvalidationCondition("1h收盘跌回98000下方");
        plan.setStopLossPrice(new BigDecimal("95000"));
        plan.setTakeProfitPrice(new BigDecimal("110000"));
        plan.setOpenedWakeTime(1785168000000L);
        return plan;
    }

    /** 放宽止损=放大风险=移动球门柱，工具层一票否决 */
    @Test
    void stopLossWidenRejected() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));

        String r = tools.setStopLoss(5L, 94000, "给它多一点空间");

        assertThat(r).startsWith("REJECTED").contains("收紧");
        verify(simTradeClient, never()).setStopLoss(anyLong(), any());
    }

    /** 收紧止损放行 + 计划留修订（理由进历史，下轮无记忆的模型看得见） */
    @Test
    void stopLossTightenExecutesAndRevisesPlan() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.setStopLoss(5L, 98000, "价格+2R，上移锁保本");

        assertThat(r).contains("ok");
        verify(simTradeClient).setStopLoss(eq(99L), any());
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(cap.capture());
        assertThat(cap.getValue().getRevisionsJson()).contains("移动止损").contains("锁保本");
        // 计划本体价格字段是原始快照，修订只进历史
        assertThat(cap.getValue().getStopLossPrice()).isEqualByComparingTo("95000");
    }

    /** 多单下调止盈=把目标降到现价上方一点点秒触发="止盈带走"马甲下的恐慌平仓，拒 */
    @Test
    void takeProfitTowardEntryRejectedForLong() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));

        String r = tools.setTakeProfit(5L, 105000, "想早点落袋");

        assertThat(r).startsWith("REJECTED").contains("止盈");
        verify(simTradeClient, never()).setTakeProfit(anyLong(), any());
    }

    /** 有利方向移动目标（让利润奔跑）放行 + 修订留痕 */
    @Test
    void takeProfitAwayFromEntryExecutesAndRevisesPlan() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.setTakeProfit(5L, 120000, "趋势加速，目标看下一压力位120000");

        assertThat(r).contains("ok");
        verify(simTradeClient).setTakeProfit(eq(99L), any());
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(cap.capture());
        assertThat(cap.getValue().getRevisionsJson()).contains("移动止盈").contains("趋势加速");
    }

    /**
     * 止损一律覆盖全仓：本版 trader 的 sl/tp 都是全仓单，覆盖量归代码从仓位现取，
     * 模型说了不算——它照抄旧数量（加仓后仓位已变大）就会让一半仓位裸奔。
     */
    @Test
    void stopLossAlwaysCoversWholePosition() {
        FuturesPositionDTO p = longPosition();
        // 加仓后仓位涨到 0.02，而旧止损档还停在 0.01
        p.setQuantity(new BigDecimal("0.02"));
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(p));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.setStopLoss(5L, 98000, "价格+2R，上移锁保本");

        assertThat(r).contains("ok");
        ArgumentCaptor<FuturesStopLossRequest> cap = ArgumentCaptor.forClass(FuturesStopLossRequest.class);
        verify(simTradeClient).setStopLoss(eq(99L), cap.capture());
        assertThat(cap.getValue().getStopLosses()).singleElement()
                .extracting(FuturesStopLossRequest.StopLossItem::getQuantity, as(BIG_DECIMAL))
                .isEqualByComparingTo("0.02");
    }

    /** 止盈同理全仓覆盖 */
    @Test
    void takeProfitAlwaysCoversWholePosition() {
        FuturesPositionDTO p = longPosition();
        p.setQuantity(new BigDecimal("0.02"));
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(p));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.setTakeProfit(5L, 120000, "趋势加速，目标看下一压力位");

        assertThat(r).contains("ok");
        ArgumentCaptor<FuturesTakeProfitRequest> cap = ArgumentCaptor.forClass(FuturesTakeProfitRequest.class);
        verify(simTradeClient).setTakeProfit(eq(99L), cap.capture());
        assertThat(cap.getValue().getTakeProfits()).singleElement()
                .extracting(FuturesTakeProfitRequest.TakeProfitItem::getQuantity, as(BIG_DECIMAL))
                .isEqualByComparingTo("0.02");
    }

    /** 已有计划的仓不许 write_plan——否则它就是改论点的后门 */
    @Test
    void writePlanRejectedWhenPlanExists() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.writePlan(5L, "RANGE", "x", "跌破区间下沿", null);

        assertThat(r).startsWith("REJECTED").contains("已有计划");
        verify(planMapper, never()).insert(any(AiTraderPlan.class));
    }

    /** 无计划持仓补立：字段取自真实仓位（入场价/当前止损），修订史起点=补立 */
    @Test
    void writePlanBackfillsPlanlessPosition() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        when(planMapper.selectOne(any())).thenReturn(null);

        String r = tools.writePlan(5L, "RANGE", "区间下沿获支撑", "1h收盘跌破区间下沿94000", 108000.0);

        assertThat(r).contains("ok");
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).insert(cap.capture());
        AiTraderPlan p = cap.getValue();
        assertThat(p.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(p.getSide()).isEqualTo("LONG");
        assertThat(p.getEntryPrice()).isEqualByComparingTo("100000");
        assertThat(p.getStopLossPrice()).isEqualByComparingTo("95000");
        assertThat(p.getInvalidationCondition()).contains("94000");
        assertThat(p.getRevisionsJson()).contains("补立");
    }

    /**
     * 真跑事故复现：模型重试时丢了 symbol 参数，空 symbol 打到上游拉回全市场数组炸掉解析。
     * 白名单必须挡在行情查询之前——模型要收到的是可修正的拒因，不是解析异常。
     */
    @Test
    void blankSymbolRejectedBeforeMarketFetch() {
        TradeTools strict = new TradeTools(simTradeClient, 99L, Set.of("BTCUSDT"),
                new BigDecimal("10000"),
                sym -> { throw new IllegalStateException("不该发起行情查询"); },
                new TraderPlanStore(planMapper), requestService,
                new TradeTools.WakeCtx(7L, 1, 1785171600000L,
                        new TraderRiskConfig(1, 20, new BigDecimal("1"), new BigDecimal("50"),
                                true, true, true, true)));

        String r = strict.openPosition(null, "SHORT", "MARKET", 0.64, 20,
                null, 64980, 64640.0, "BREAKOUT", "突破", "收回箱体");

        assertThat(r).startsWith("REJECTED").contains("白名单").contains("完整给出全部参数");
    }

    /** 审批分流的回执是纯文本非 JSON：必须原样返回给模型，动作轨迹只记一条 ok（不许被当异常转成 ERROR） */
    @Test
    void requestReceiptReturnedVerbatimToModel() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        when(requestService.submit(any()))
                .thenReturn("减仓请求已提交给主人确认，本轮不会成交。你的止损单仍在生效，风险有保护");
        TradeTools noSelfReduce = new TradeTools(simTradeClient, 99L, Set.of("BTCUSDT"),
                new BigDecimal("10000"), sym -> new BigDecimal("100000"),
                new TraderPlanStore(planMapper), requestService,
                new TradeTools.WakeCtx(7L, 1, 1785171600000L,
                        new TraderRiskConfig(1, 20, new BigDecimal("1"), new BigDecimal("50"),
                                true, true, true, false)));

        String r = noSelfReduce.closePosition(5L, 0.01, "失效条件触发");

        assertThat(r).contains("已提交给主人确认");
        assertThat(noSelfReduce.actions()).hasSize(1);
        assertThat(noSelfReduce.actions().get(0).getString("status")).isEqualTo("ok");
    }

    /** 加仓覆盖：旧论点进修订历史（含理由），持有时长按最初开仓算 */
    @Test
    void upsertExistingPlanKeepsOldThesisAsRevision() {
        AiTraderPlan old = existingPlan();
        when(planMapper.selectOne(any())).thenReturn(old);
        AiTraderPlan neu = existingPlan();
        neu.setId(null);
        neu.setSignalsUsed("回踩确认支撑，加仓");
        neu.setInvalidationCondition("4h收盘跌破97000");
        neu.setOpenedWakeTime(1785171600000L);

        new TraderPlanStore(planMapper).upsert(neu, true);

        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(cap.capture());
        AiTraderPlan p = cap.getValue();
        assertThat(p.getInvalidationCondition()).isEqualTo("4h收盘跌破97000");
        assertThat(p.getRevisionsJson()).contains("加仓").contains("1h收盘跌回98000下方");
        assertThat(p.getOpenedWakeTime()).isEqualTo(1785168000000L);
    }

    /** 同轮内平掉再开同向仓＝重开不是加仓：旧计划归档留档、仓龄从新仓起算、修订史不继承（仓龄诚实） */
    @Test
    void upsertReentryArchivesOldPlanInsteadOfAddOnRevision() {
        AiTraderPlan old = existingPlan();
        when(planMapper.selectOne(any())).thenReturn(old);
        AiTraderPlan neu = existingPlan();
        neu.setId(null);
        neu.setSignalsUsed("重新突破，独立新仓");
        neu.setOpenedWakeTime(1785171600000L);

        new TraderPlanStore(planMapper).upsert(neu, false);

        // 归档不删：论点→结局配对是 learning agent 的复盘原料
        ArgumentCaptor<AiTraderPlan> archived = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(archived.capture());
        assertThat(archived.getValue().getId()).isEqualTo(21L);
        assertThat(archived.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        assertThat(archived.getValue().getClosedWakeTime()).isEqualTo(1785171600000L);

        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).insert(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_LIVE);
        assertThat(cap.getValue().getOpenedWakeTime()).isEqualTo(1785171600000L);
        assertThat(cap.getValue().getRevisionsJson()).isNull();
    }
}

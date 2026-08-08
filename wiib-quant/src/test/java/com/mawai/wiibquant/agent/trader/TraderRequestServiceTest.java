package com.mawai.wiibquant.agent.trader;

import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderRequest;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import com.mawai.wiibquant.mapper.AiTraderRequestMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批链路直测：主人点同意后真正发给 sim 的是什么。
 * 加仓最要命的是止损——sim 的 mergeSlList 见 added 为空直接返回 null（＝不改库），
 * 所以批准的加仓若不自带止损，仓位翻倍而覆盖量原地不动，一半仓位裸奔。
 */
class TraderRequestServiceTest {

    /** 状态回写走 LambdaUpdateWrapper，字段名解析要靠这份缓存（Spring 启动时才自动建） */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderRequest.class);
    }

    private final AiTraderRequestMapper requestMapper = mock(AiTraderRequestMapper.class);
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final TraderPlanStore planStore = mock(TraderPlanStore.class);

    private final TraderRequestService service =
            new TraderRequestService(requestMapper, traderMapper, simTradeClient, planStore);

    private AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(3L);
        t.setSimUserId(99L);
        t.setRoundNo(1);
        return t;
    }

    /** 现有多单 0.01，止损 9.5 万、止盈 11 万 */
    private FuturesPositionDTO position() {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(5L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setQuantity(new BigDecimal("0.01"));
        p.setEntryPrice(new BigDecimal("100000"));
        p.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("95000"), new BigDecimal("0.01"))));
        p.setTakeProfits(List.of(new FuturesTakeProfit("t1", new BigDecimal("110000"), new BigDecimal("0.01"))));
        return p;
    }

    private AiTraderRequest request(String type, String qty) {
        AiTraderRequest r = new AiTraderRequest();
        r.setId(11L);
        r.setTraderId(7L);
        r.setRoundNo(1);
        r.setType(type);
        r.setSymbol("BTCUSDT");
        r.setSide("LONG");
        r.setPositionId(5L);
        r.setQuantity(new BigDecimal(qty));
        r.setLeverage(10);
        r.setRequestPrice(new BigDecimal("100000"));
        r.setReason("突破确认，加码");
        r.setStatus(AiTraderRequest.STATUS_PENDING);
        r.setWakeTime(1785171600000L);
        return r;
    }

    private void givenApprovable(AiTraderRequest r) {
        when(requestMapper.selectById(11L)).thenReturn(r);
        when(traderMapper.selectById(7L)).thenReturn(trader());
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(position()));
        // PENDING→APPROVED 的条件更新抢到了这一行（affected=1），后续才允许真下单
        when(requestMapper.update(any(), any())).thenReturn(1);
    }

    /**
     * 批准加仓必须自带止损，覆盖量=加仓量：sim 把 existing+added 累加成新的全仓覆盖，
     * 不带就等于放任新增的那部分裸奔。价格照抄仓位现有止损（全仓单只有一档）。
     */
    @Test
    void approvedAddCarriesStopLossForAddedQuantity() {
        AiTraderRequest r = request(AiTraderRequest.TYPE_ADD, "0.01");
        givenApprovable(r);
        when(simTradeClient.openPosition(anyLong(), any())).thenReturn(new FuturesOrderResponse());

        service.approve(3L, 11L);

        ArgumentCaptor<FuturesOpenRequest> cap = ArgumentCaptor.forClass(FuturesOpenRequest.class);
        verify(simTradeClient).openPosition(anyLong(), cap.capture());
        FuturesOpenRequest sent = cap.getValue();
        assertThat(sent.getStopLosses()).singleElement().satisfies(sl -> {
            assertThat(sl.getPrice()).isEqualByComparingTo("95000");
            assertThat(sl.getQuantity()).isEqualByComparingTo("0.01");
        });
        // 止盈同理：现有仓有目标位，新增部分也得挂上，否则触发时只带走一半
        assertThat(sent.getTakeProfits()).singleElement().satisfies(tp -> {
            assertThat(tp.getPrice()).isEqualByComparingTo("110000");
            assertThat(tp.getQuantity()).isEqualByComparingTo("0.01");
        });
    }

    /** 仓位本来就没挂止盈，就别硬造一个 */
    @Test
    void approvedAddWithoutExistingTakeProfitSendsNone() {
        AiTraderRequest r = request(AiTraderRequest.TYPE_ADD, "0.01");
        FuturesPositionDTO p = position();
        p.setTakeProfits(List.of());
        when(requestMapper.selectById(11L)).thenReturn(r);
        when(traderMapper.selectById(7L)).thenReturn(trader());
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(p));
        when(simTradeClient.openPosition(anyLong(), any())).thenReturn(new FuturesOrderResponse());
        when(requestMapper.update(any(), any())).thenReturn(1);

        service.approve(3L, 11L);

        ArgumentCaptor<FuturesOpenRequest> cap = ArgumentCaptor.forClass(FuturesOpenRequest.class);
        verify(simTradeClient).openPosition(anyLong(), cap.capture());
        assertThat(cap.getValue().getTakeProfits()).isNullOrEmpty();
        assertThat(cap.getValue().getStopLosses()).hasSize(1);
    }

    /**
     * 减仓量超过现有仓位时按现有全平，回执必须报实际成交量。
     * 这条回执会原样注入下一轮提示词当事实，虚报数字＝模型按错的仓位算后续一切。
     */
    @Test
    void approvedReduceReportsActuallyClampedQuantity() {
        // 请求 0.05，但仓位在等待审批期间已被部分平到只剩 0.01
        AiTraderRequest r = request(AiTraderRequest.TYPE_REDUCE, "0.05");
        givenApprovable(r);
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(888L);
        when(simTradeClient.closePosition(anyLong(), any())).thenReturn(resp);

        service.approve(3L, 11L);

        ArgumentCaptor<FuturesCloseRequest> cap = ArgumentCaptor.forClass(FuturesCloseRequest.class);
        verify(simTradeClient).closePosition(anyLong(), cap.capture());
        assertThat(cap.getValue().getQuantity()).isEqualByComparingTo("0.01");
        assertThat(r.getExecutedResult()).contains("0.01").doesNotContain("0.05");
    }

    /** 仓位在审批期间已被止损带走：不执行，把原因写给主人看，不静默吞掉 */
    @Test
    void approveOnVanishedPositionRecordsReason() {
        AiTraderRequest r = request(AiTraderRequest.TYPE_REDUCE, "0.01");
        when(requestMapper.selectById(11L)).thenReturn(r);
        when(traderMapper.selectById(7L)).thenReturn(trader());
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of());
        when(requestMapper.update(any(), any())).thenReturn(1);

        service.approve(3L, 11L);

        assertThat(r.getExecutedResult()).contains("已不存在");
        // 状态回写一律列级更新：整行 updateById 会把并发改动（如 notified）一起盖掉
        verify(requestMapper, never()).updateById(any(AiTraderRequest.class));
    }

    /**
     * 双击"同意"或两个标签页同时点：read-then-execute 无 CAS 时两次都能通过校验各下一笔市价单，
     * 仓位翻倍——而加仓路径本就豁免保证金区间校验，直接越过主人配的 marginPctMax。
     * 只有把 PENDING 抢成 APPROVED 的那一次才许执行，抢不到直接返回不下单。
     */
    @Test
    void concurrentApproveExecutesOrderOnlyOnce() {
        // 每次 selectById 给一份全新的 PENDING 行——真实并发下两个请求各读各的，
        // 都看到 PENDING（复用同一个实例会被上一次的内存改动掩盖掉这个竞态）
        when(requestMapper.selectById(11L)).thenAnswer(inv -> request(AiTraderRequest.TYPE_ADD, "0.01"));
        when(traderMapper.selectById(7L)).thenReturn(trader());
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(position()));
        when(simTradeClient.openPosition(anyLong(), any())).thenReturn(new FuturesOrderResponse());
        // 只有第一次条件更新抢得到行；之后（含执行结果回写、第二次点同意）都是 0
        when(requestMapper.update(any(), any())).thenReturn(1, 0);

        service.approve(3L, 11L);
        String second = service.approve(3L, 11L);

        verify(simTradeClient, times(1)).openPosition(anyLong(), any());
        assertThat(second).contains("已处理");
    }

    /** 抢不到状态时连仓位都不该查——早返回，别把已被别人处理的请求又走一遍执行前置 */
    @Test
    void losingApproveRaceDoesNotTouchSim() {
        AiTraderRequest r = request(AiTraderRequest.TYPE_REDUCE, "0.01");
        when(requestMapper.selectById(11L)).thenReturn(r);
        when(traderMapper.selectById(7L)).thenReturn(trader());
        when(requestMapper.update(any(), any())).thenReturn(0);

        assertThat(service.approve(3L, 11L)).contains("已处理");

        verify(simTradeClient, never()).closePosition(anyLong(), any());
        verify(simTradeClient, never()).getAllPositions(anyLong());
    }
}

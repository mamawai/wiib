package com.mawai.wiibquant.agent.trader;

import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
import com.mawai.wiibquant.agent.toolkit.IndicatorToolkit;
import com.mawai.wiibquant.agent.toolkit.MarketDataService;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsCache;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 唤醒回路 mock 集成测试：mock ChatModel 走一遍真 ReactAgent 工具循环——
 * 开仓工具真被调用（经 TradeGuard）、决策行真落库（含论点标签/动作轨迹/权益）。
 */
class TraderWakeupIT {

    @BeforeAll
    static void initTableInfoCache() {
        // LambdaUpdateWrapper 纯单测需要实体 TableInfo 缓存（正常由 MP 启动时注册）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
    }

    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final BinanceRestClient binanceRestClient = mock(BinanceRestClient.class);
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);

    private final TraderWakeupRunner runner = new TraderWakeupRunner(
            modelFactory, new TraderPromptAssembler(), simTradeClient, binanceRestClient,
            new IndicatorToolkit(binanceRestClient),
            new MarketToolkit(mock(MarketDataService.class), binanceRestClient),
            new NewsToolkit(mock(NewsCache.class)),
            traderMapper, decisionMapper,
            new SpringAIJacksonStateSerializer<>(MessagesState::new));

    private AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(3L);
        t.setName("测试员");
        t.setStatus(AiTrader.STATUS_RUNNING);
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        t.setRoundNo(1);
        t.setSimUserId(99L);
        t.setConsecutiveFailures(0);
        t.setApiKeyEnc("enc");
        return t;
    }

    /** 模型第一轮调 open_position、第二轮给总结文本。 */
    private ChatModel modelOpeningThenSummary(String openArgsJson) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage openCall = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "open_position", openArgsJson)))
                .build();
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(openCall))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("突破前高放量，做多并挂好止损，本轮结束。")))));
        return model;
    }

    private void stubHealthyAccount() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of());
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(Map.of("balance", "10000", "frozenBalance", "0"));
        when(binanceRestClient.getPremiumIndex("BTCUSDT"))
                .thenReturn("{\"markPrice\":\"100000\",\"nextFundingTime\":0,\"lastFundingRate\":\"0.0001\"}");
        when(decisionMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void fullLoopOpensPositionAndPersistsDecision() {
        stubHealthyAccount();
        // 先建好再 stub：thenReturn 参数里嵌套 when() 是 UnfinishedStubbing
        ChatModel model = modelOpeningThenSummary("""
                {"symbol":"BTCUSDT","side":"LONG","orderType":"MARKET","quantity":0.01,"leverage":10,
                 "limitPrice":null,"stopLossPrice":95000,"takeProfitPrice":110000,
                 "playType":"BREAKOUT","signalsUsed":"突破前高+量比1.8"}""");
        when(modelFactory.modelFor(any())).thenReturn(model);
        FuturesOrderResponse resp = new FuturesOrderResponse();
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(resp);

        runner.wake(trader(), 1785171600000L);

        // 真开仓：请求带止损与止盈
        ArgumentCaptor<FuturesOpenRequest> open = ArgumentCaptor.forClass(FuturesOpenRequest.class);
        verify(simTradeClient).openPosition(eq(99L), open.capture());
        assertThat(open.getValue().getStopLosses()).hasSize(1);
        assertThat(open.getValue().getTakeProfits()).hasSize(1);
        assertThat(open.getValue().getMemo()).contains("BREAKOUT");

        // 决策行：OK + 推理全文 + 论点标签进动作轨迹 + 权益
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        AiTraderDecision d = dec.getValue();
        assertThat(d.getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(d.getReasoning()).contains("本轮结束");
        assertThat(d.getActionsJson()).contains("open_position").contains("BREAKOUT");
        assertThat(d.getEquity()).isEqualByComparingTo("10000");
        assertThat(d.getToolCalls()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void guardRejectionIsRecordedAndNoOrderSent() {
        stubHealthyAccount();
        // 杠杆50违规 → TradeGuard 拒绝 → 模型收到拒绝原因后给总结
        ChatModel model = modelOpeningThenSummary("""
                {"symbol":"BTCUSDT","side":"LONG","orderType":"MARKET","quantity":0.01,"leverage":50,
                 "limitPrice":null,"stopLossPrice":95000,"takeProfitPrice":null,
                 "playType":"BREAKOUT","signalsUsed":"x"}""");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        verify(simTradeClient, never()).openPosition(anyLong(), any());
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(dec.getValue().getActionsJson()).contains("rejected").contains("杠杆");
    }

    @Test
    void modelFailureRecordsErrorAndPausesAfterFifthConsecutive() {
        stubHealthyAccount();
        when(modelFactory.modelFor(any())).thenThrow(new IllegalStateException("上游401"));
        AiTrader t = trader();
        t.setConsecutiveFailures(4); // 本次是第5败

        runner.wake(t, 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(dec.getValue().getError()).contains("401");
        verify(traderMapper).update(any(), any()); // 连败暂停走列级更新
    }

    @Test
    void liquidationEndsRoundWithoutModelCall() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of());
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(Map.of("balance", "50", "frozenBalance", "0"));

        runner.wake(trader(), 1785171600000L);

        verify(modelFactory, never()).modelFor(any());
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getReasoning()).contains("爆仓终局");
        verify(traderMapper).update(any(), any()); // LIQUIDATED 列级更新
    }

    @Test
    void skippedWakeLeavesTrace() {
        runner.recordSkipped(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_SKIPPED);
    }
}

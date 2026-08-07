package com.mawai.wiibquant.agent.trader;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderRequest;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import com.mawai.wiibquant.mapper.AiTraderRequestMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** listModels：拉取端点可用模型清单（key 传空=用已存 key，与改配置语义一致）。 */
class TraderServiceTest {

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderRequest.class);
    }

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);
    private final ApiKeyCrypto apiKeyCrypto = mock(ApiKeyCrypto.class);
    private final BinanceProperties binanceProperties = mock(BinanceProperties.class);

    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final TraderPlanStore planStore = mock(TraderPlanStore.class);
    private final AiTraderRequestMapper requestMapper = mock(AiTraderRequestMapper.class);

    private final TraderService service = new TraderService(
            traderMapper, mock(AiTraderDecisionMapper.class), modelFactory, apiKeyCrypto,
            simTradeClient, binanceProperties, new BaseUrlGuard(""), planStore, requestMapper);

    /** SSRF 防线必须接进 listModels 入口 */
    @Test
    void privateBaseUrlRejectedBySsrfGuard() {
        when(apiKeyCrypto.encrypt(any())).thenReturn("enc");
        when(modelFactory.listModels(any())).thenReturn(List.of());

        TraderService.ListModelsResult r = service.listModels(1L,
                new TraderService.ListModelsReq("openai", "http://127.0.0.1:8080", "sk-x"));

        assertThat(r.error()).contains("内网");
    }

    @Test
    void returnsAlphabeticallySortedModels() {
        when(apiKeyCrypto.encrypt("sk-new")).thenReturn("enc-new");
        when(modelFactory.listModels(any())).thenReturn(List.of("deepseek-reasoner", "deepseek-chat"));

        TraderService.ListModelsResult r = service.listModels(1L,
                new TraderService.ListModelsReq("openai", "https://8.8.8.8", "sk-new"));

        assertThat(r.error()).isNull();
        assertThat(r.models()).containsExactly("deepseek-chat", "deepseek-reasoner");
    }

    @Test
    void probeNormalizedBeforeFactoryCall() {
        when(apiKeyCrypto.encrypt("sk-new")).thenReturn("enc-new");
        when(modelFactory.listModels(any())).thenReturn(List.of());

        // 协议留空默认 openai；baseUrl 去尾斜杠；新 key 加密进探针
        service.listModels(1L, new TraderService.ListModelsReq(null, "https://8.8.8.8/", " sk-new "));

        ArgumentCaptor<AiTrader> probe = ArgumentCaptor.forClass(AiTrader.class);
        verify(modelFactory).listModels(probe.capture());
        assertThat(probe.getValue().getApiProtocol()).isEqualTo("openai");
        assertThat(probe.getValue().getBaseUrl()).isEqualTo("https://8.8.8.8");
        assertThat(probe.getValue().getApiKeyEnc()).isEqualTo("enc-new");
    }

    @Test
    void blankKeyFallsBackToStoredKey() {
        AiTrader mine = new AiTrader();
        mine.setApiKeyEnc("enc-stored");
        when(traderMapper.selectOne(any())).thenReturn(mine);
        when(modelFactory.listModels(any())).thenReturn(List.of());

        TraderService.ListModelsResult r = service.listModels(1L,
                new TraderService.ListModelsReq("openai", "https://8.8.8.8", ""));

        assertThat(r.error()).isNull();
        ArgumentCaptor<AiTrader> probe = ArgumentCaptor.forClass(AiTrader.class);
        verify(modelFactory).listModels(probe.capture());
        assertThat(probe.getValue().getApiKeyEnc()).isEqualTo("enc-stored");
    }

    @Test
    void blankKeyWithoutTraderRejected() {
        when(traderMapper.selectOne(any())).thenReturn(null);

        TraderService.ListModelsResult r = service.listModels(1L,
                new TraderService.ListModelsReq("openai", "https://8.8.8.8", null));

        assertThat(r.error()).contains("apiKey");
        verify(modelFactory, never()).listModels(any());
    }

    @Test
    void blankBaseUrlRejected() {
        TraderService.ListModelsResult r = service.listModels(1L,
                new TraderService.ListModelsReq("openai", " ", "sk-x"));

        assertThat(r.error()).contains("baseUrl");
        verify(modelFactory, never()).listModels(any());
    }

    /** 退出平台提示词后模型将无任何指令来源，必须强制填自定义 */
    @Test
    void optOutDefaultPromptRequiresCustomPrompt() {
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);

        String err = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "5m", " ",
                "openai", "https://8.8.8.8", "deepseek-chat", "sk-x", false,
                null, null, null, null, null, null, null, null));

        assertThat(err).contains("自定义提示词");
    }

    /** 杠杆上界卡在 125：再往上 sim 的分档表也接不住 */
    @Test
    void leverageBeyondHardMaxRejected() {
        when(binanceProperties.getSymbols()).thenReturn(java.util.List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);

        String err = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "5m", null,
                "openai", "https://8.8.8.8", "deepseek-chat", "sk-x", true,
                50, 200, null, null, null, null, null, null));

        assertThat(err).contains("杠杆区间");
    }

    /** 区间下界大于上界＝空集，模型永远开不出仓 */
    @Test
    void invertedLeverageRangeRejected() {
        when(binanceProperties.getSymbols()).thenReturn(java.util.List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);

        String err = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "5m", null,
                "openai", "https://8.8.8.8", "deepseek-chat", "sk-x", true,
                100, 50, null, null, null, null, null, null));

        assertThat(err).contains("下界不能大于上界");
    }

    /**
     * 重置开新局：本局存活计划归档（不删——论点/失效条件/修订史是公开凭证与复盘原料）。
     * 顺带把未处理的请求作废：换了新账户，旧 positionId 早已不存在，留着永远处理不掉。
     */
    @Test
    void resetArchivesCurrentRoundPlans() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(3);
        when(traderMapper.selectOne(any())).thenReturn(t);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        assertThat(service.reset(1L)).isNull();

        verify(planStore).archiveRound(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(3), org.mockito.ArgumentMatchers.anyLong());
        verify(requestMapper).update(any(), any());     // 待确认请求一并作废
    }

    /** 单仓 + 双开是自相矛盾的组合（双开本身要两个仓位），入口就拦掉 */
    @Test
    void hedgeWithSinglePositionRejected() {
        when(binanceProperties.getSymbols()).thenReturn(java.util.List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);

        String err = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "5m", null,
                "openai", "https://8.8.8.8", "deepseek-chat", "sk-x", true,
                null, null, null, null, false, true, null, null));

        assertThat(err).contains("多空双开");
    }

    /**
     * 改配置必须列级更新且不碰运行态列：整行 updateById 会把唤醒回路并发写的
     * status/consecutive_failures 盖回读取时的旧值（连通性测试要出网数秒，窗口不小）——
     * 与 runner 侧"状态回写列级更新"是同一条铁律的两半。
     */
    @Test
    void updateConfigWritesConfigColumnsOnly() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setStatus(AiTrader.STATUS_RUNNING);
        t.setApiProtocol("openai");
        t.setBaseUrl("https://8.8.8.8");
        t.setModel("deepseek-chat");
        t.setApiKeyEnc("enc-stored");
        when(traderMapper.selectOne(any())).thenReturn(t);
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));

        // 模型三件套与 key 都没变 → 不触发连通性测试
        String err = service.updateConfig(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "5m", "稳一点",
                "openai", "https://8.8.8.8", "deepseek-chat", null, true,
                null, null, null, null, null, null, null, null));

        assertThat(err).isNull();
        verify(traderMapper, never()).updateById(any(AiTrader.class));
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiTrader>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(traderMapper).update(org.mockito.ArgumentMatchers.isNull(), cap.capture());
        String sqlSet = cap.getValue().getSqlSet();
        assertThat(sqlSet).contains("custom_prompt").contains("api_key_enc");
        assertThat(sqlSet).doesNotContain("status").doesNotContain("consecutive_failures");
    }

    @Test
    void upstreamErrorTruncatedTo300() {
        when(apiKeyCrypto.encrypt(any())).thenReturn("enc");
        when(modelFactory.listModels(any())).thenThrow(new RuntimeException("x".repeat(400)));

        TraderService.ListModelsResult r = service.listModels(1L,
                new TraderService.ListModelsReq("openai", "https://8.8.8.8", "sk-x"));

        assertThat(r.models()).isNull();
        assertThat(r.error()).hasSize(300);
    }
}

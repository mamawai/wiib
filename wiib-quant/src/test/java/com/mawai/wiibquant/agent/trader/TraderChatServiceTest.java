package com.mawai.wiibquant.agent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibquant.agent.learning.ReviewRunner;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话轨访问 trader 的唯一入口。
 * <p>
 * 这里钉的核心是<b>归属</b>与<b>动作的准入</b>：查询只经 userId 取自己的 trader，
 * 唤醒/复盘是花钱且动真仓位的事，什么情况下不许做必须写死。
 */
class TraderChatServiceTest {

    private static final long ME = 1L;
    private static final long OTHERS = 2L;

    /** Lambda 条件构造器要查 TableInfo；不预热的话本类单独跑会炸，全量跑却因别的类先热过而假绿 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiTraderDecision.class);
    }

    private final TraderService traderService = mock(TraderService.class);
    private final TraderPlanStore planStore = mock(TraderPlanStore.class);
    private final TraderScheduler scheduler = mock(TraderScheduler.class);
    private final ReviewRunner reviewRunner = mock(ReviewRunner.class);
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);

    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);

    private final TraderChatService service = new TraderChatService(traderService, modelFactory, planStore,
            scheduler, reviewRunner, traderMapper, decisionMapper, simTradeClient);

    private AiTrader running() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(ME);
        t.setName("测试员");
        t.setStatus(AiTrader.STATUS_RUNNING);
        t.setRoundNo(1);
        t.setIntervalCode("1h");
        t.setSymbols("BTCUSDT");
        t.setLeverageMin(3);
        t.setLeverageMax(20);
        t.setMarginPctMin(new BigDecimal("5"));
        t.setMarginPctMax(new BigDecimal("20"));
        t.setMemory("教训：别追高");
        return t;
    }

    private static JSONObject parse(String json) {
        return JSON.parseObject(json);
    }

    // ---------- 归属隔离 ----------

    /**
     * 每个查询都只按"当前是谁"取 trader。工具签名里没有用户参数，这一层再按 userId 取一次，
     * 越权就无从谈起——而这条测试正是那句话的凭证。
     */
    @ParameterizedTest
    @ValueSource(strings = {"overview", "positions", "decisions", "plans"})
    void 查询只认自己的trader(String which) {
        // 各查询依赖的列表 mock 默认就返回空集合，这里只关心"取的是谁的 trader"
        when(traderService.mine(ME)).thenReturn(running());
        when(traderService.mine(OTHERS)).thenReturn(null);
        when(traderService.latestEquity(any())).thenReturn(new BigDecimal("10500"));

        assertThat(parse(call(which, ME)).getBooleanValue("hasTrader")).isTrue();
        // 别人的 userId 拿不到任何东西，而不是拿到我的
        assertThat(parse(call(which, OTHERS)).getBooleanValue("hasTrader")).isFalse();
    }

    private String call(String which, long userId) {
        return switch (which) {
            case "overview" -> service.overview(userId);
            case "positions" -> service.positions(userId);
            case "decisions" -> service.decisions(userId, null);
            default -> service.plans(userId);
        };
    }

    /** 没有 trader 的用户去动作：要拿到"你还没有"，而不是 NPE（工具异常会把整轮对话炸掉） */
    @Test
    void 没有trader时动作不炸() {
        when(traderService.mine(ME)).thenReturn(null);

        assertThat(parse(service.wake(ME)).getBooleanValue("hasTrader")).isFalse();
        assertThat(parse(service.reviewNow(ME)).getBooleanValue("hasTrader")).isFalse();
        assertThat(parse(service.leaveNote(ME, "随便说说")).getBooleanValue("hasTrader")).isFalse();
        verify(scheduler, never()).tryManualWake(any());
        verify(reviewRunner, never()).review(any(), anyLong());
        verify(traderMapper, never()).update(any(), any());
    }

    // ---------- 唤醒 ----------

    @Test
    void 唤醒触发调度器() {
        when(traderService.mine(ME)).thenReturn(running());
        when(scheduler.tryManualWake(any())).thenReturn(null);   // null=已触发

        JSONObject out = parse(service.wake(ME));

        assertThat(out.getBooleanValue("ok")).isTrue();
        verify(scheduler).tryManualWake(any(AiTrader.class));
    }

    /** 调度器说没触发（上一轮还在跑/例行将至），就得如实转述，不能报"已唤醒" */
    @Test
    void 唤醒未触发时如实回报() {
        when(traderService.mine(ME)).thenReturn(running());
        when(scheduler.tryManualWake(any())).thenReturn("上一轮唤醒还在跑");

        JSONObject out = parse(service.wake(ME));

        assertThat(out.getBooleanValue("ok")).isFalse();
        assertThat(out.getString("message")).contains("上一轮唤醒还在跑");
    }

    /** 暂停就是暂停：手动唤醒要是能绕过它，连败自动暂停的 trader 会被一句话叫起来接着亏 */
    @Test
    void 暂停中的trader不许被唤醒() {
        AiTrader t = running();
        t.setStatus(AiTrader.STATUS_PAUSED);
        t.setPausedReason("连续5次唤醒失败");
        when(traderService.mine(ME)).thenReturn(t);

        JSONObject out = parse(service.wake(ME));

        assertThat(out.getBooleanValue("ok")).isFalse();
        assertThat(out.getString("message")).contains("暂停").contains("连续5次唤醒失败");
        verify(scheduler, never()).tryManualWake(any());
    }

    @Test
    void 爆仓终局的trader不许被唤醒() {
        AiTrader t = running();
        t.setStatus(AiTrader.STATUS_LIQUIDATED);
        when(traderService.mine(ME)).thenReturn(t);

        JSONObject out = parse(service.wake(ME));

        assertThat(out.getBooleanValue("ok")).isFalse();
        assertThat(out.getString("message")).contains("爆仓");
        verify(scheduler, never()).tryManualWake(any());
    }

    // ---------- 点播复盘 ----------

    @Test
    void 复盘成功时回传复盘全文() {
        AiTrader t = running();
        when(traderService.mine(ME)).thenReturn(t);
        // review() 是 void，用回调模拟"它落了一行 REVIEW"
        org.mockito.Mockito.doAnswer(inv -> {
            long at = inv.getArgument(1);
            when(decisionMapper.selectOne(any())).thenReturn(review(at, AiTraderDecision.STATUS_OK));
            return null;
        }).when(reviewRunner).review(eq(t), anyLong());

        JSONObject out = parse(service.reviewNow(ME));

        assertThat(out.getBooleanValue("ok")).isTrue();
        assertThat(out.getString("review")).contains("本期复盘");
        verify(reviewRunner).review(eq(t), anyLong());
    }

    /**
     * 无素材时 review() 静默跳过、什么都不写。这时报"复盘完成"会让用户去时间线上
     * 找一篇根本不存在的复盘——必须认出这次没跑。
     */
    @Test
    void 无素材跳过时不谎报完成() {
        when(traderService.mine(ME)).thenReturn(running());
        when(decisionMapper.selectOne(any())).thenReturn(null);   // 没有落下新的 REVIEW 行

        JSONObject out = parse(service.reviewNow(ME));

        assertThat(out.getBooleanValue("ok")).isFalse();
        assertThat(out.getString("message")).contains("跳过");
    }

    /** 上一期的旧 REVIEW 行不能被当成"这次跑出来的"：判据是时刻要对得上 */
    @Test
    void 旧复盘行不算这次的结果() {
        when(traderService.mine(ME)).thenReturn(running());
        when(decisionMapper.selectOne(any())).thenReturn(review(123L, AiTraderDecision.STATUS_OK));

        JSONObject out = parse(service.reviewNow(ME));

        assertThat(out.getBooleanValue("ok")).isFalse();
        assertThat(out.getString("message")).contains("跳过");
    }

    @Test
    void 复盘失败时如实回报() {
        AiTrader t = running();
        when(traderService.mine(ME)).thenReturn(t);
        org.mockito.Mockito.doAnswer(inv -> {
            long at = inv.getArgument(1);
            AiTraderDecision d = review(at, AiTraderDecision.STATUS_ERROR);
            d.setError("复盘超时(180s)");
            when(decisionMapper.selectOne(any())).thenReturn(d);
            return null;
        }).when(reviewRunner).review(eq(t), anyLong());

        JSONObject out = parse(service.reviewNow(ME));

        assertThat(out.getBooleanValue("ok")).isFalse();
        assertThat(out.getString("message")).contains("复盘超时");
    }

    private static AiTraderDecision review(long wakeTime, String status) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(wakeTime);
        d.setStatus(status);
        d.setKind(AiTraderDecision.KIND_REVIEW);
        d.setReasoning("【本期复盘】战绩：2胜1负");
        return d;
    }

    // ---------- 留言 ----------

    @Test
    void 留言落库() {
        when(traderService.mine(ME)).thenReturn(running());

        JSONObject out = parse(service.leaveNote(ME, "  今晚有 CPI，仓位轻点  "));

        assertThat(out.getBooleanValue("ok")).isTrue();
        verify(traderMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void 空留言不落库() {
        when(traderService.mine(ME)).thenReturn(running());

        JSONObject out = parse(service.leaveNote(ME, "   "));

        assertThat(out.getBooleanValue("ok")).isFalse();
        verify(traderMapper, never()).update(any(), any());
    }

    /** 留言要原样进下一轮系统提示词，不设上限的话一条超长留言能把交易上下文挤没 */
    @Test
    void 超长留言被截断() {
        when(traderService.mine(ME)).thenReturn(running());
        String tooLong = "啊".repeat(TraderChatService.MAX_NOTE_CHARS + 100);

        JSONObject out = parse(service.leaveNote(ME, tooLong));

        assertThat(out.getBooleanValue("ok")).isTrue();
        verify(traderMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    /** 覆盖写要告诉用户：不然他以为两条都在，实际上只剩最后一条 */
    @Test
    void 覆盖未读留言时提示() {
        AiTrader t = running();
        t.setOwnerNote("上一条还没被读走");
        when(traderService.mine(ME)).thenReturn(t);

        JSONObject out = parse(service.leaveNote(ME, "新的一条"));

        assertThat(out.getString("message")).contains("覆盖");
    }
}

package com.mawai.wiibquant.agent.trader;

import com.mawai.wiibquant.agent.i18n.PromptI18nAssertions;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibquant.agent.i18n.PromptCatalog;
import com.mawai.wiibquant.agent.i18n.UserLangResolver;
import com.mawai.wiibquant.agent.toolkit.IndicatorToolkit;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import com.mawai.wiibquant.mapper.AiTraderPlanMapper;
import com.mawai.wiibquant.market.service.KlineFetcher;
import com.mawai.wiibquant.market.service.MarketDataService;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 唤醒开场白的双语契约：开场白的收尾标记与系统提示词必须同源，否则模型同时收到两条冲突的
 * 格式指令，收尾格式失守、复盘素材切不出"判断/动作/等待"。
 * <p>
 * 判据是"同源"而不是"等于某个字面量"：断言比的是 {@code trader.mark.conclusion}
 * （系统提示词模板用的同一条 key）的实际取值——改词表两边一起变，测试照样绿；
 * 开场白改成硬编码，它立刻红。
 */
class WakeInstructionI18nTest {

    private static final long BOUNDARY = 1785171600000L;

    private final PromptCatalog prompts = new PromptCatalog();
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final BinanceRestClient binance = mock(BinanceRestClient.class);

    private final TraderWakeupRunner runner = new TraderWakeupRunner(
            mock(TraderModelFactory.class), new TraderPromptAssembler(traderMapper, prompts),
            mock(SimTradeClient.class), binance,
            new IndicatorToolkit(new KlineFetcher(binance, 60_000)),
            new MarketToolkit(mock(MarketDataService.class)),
            new NewsToolkit(mock(NewsCache.class), mock(NewsFlashLocalizer.class)),
            traderMapper, mock(AiTraderDecisionMapper.class),
            new TraderPlanStore(mock(AiTraderPlanMapper.class)),
            mock(TraderRequestService.class), mock(UserLangResolver.class), prompts);

    {
        runner.nowMs = () -> BOUNDARY + 1_000L;
    }

    private static AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        return t;
    }

    private static AlertTrigger alert() {
        return new AlertTrigger("BTCUSDT", new BigDecimal("5.2"), new BigDecimal("100000"),
                AlertTrigger.UP, BOUNDARY);
    }

    /** 例行开场白的收尾标记 = 系统提示词模板里的那一条，两门语言各钉一遍 */
    @Test
    void 例行开场白的收尾标记与系统提示词同源() {
        for (AgentLang lang : AgentLang.values()) {
            String mark = prompts.get(lang, "trader.mark.conclusion");
            String template = new TraderPromptAssembler(traderMapper, prompts)
                    .platformTemplate(lang, "1h", "BTCUSDT", TraderRiskConfig.of(new AiTrader()), null);
            String opening = runner.routineInstruction(trader(), BOUNDARY, "", lang);

            assertThat(template).as("%s 系统提示词要带收尾标记", lang.code()).contains(mark);
            assertThat(opening).as("%s 开场白的收尾标记要与系统提示词同源", lang.code()).contains(mark);
            // 另一门语言的标记一个都不许漏进来——那正是"两条指令打架"的形态
            for (AgentLang other : AgentLang.values()) {
                if (other != lang) {
                    assertThat(opening).as("%s 开场白里混进了 %s 的收尾标记", lang.code(), other.code())
                            .doesNotContain(prompts.get(other, "trader.mark.conclusion"));
                }
            }
        }
    }

    /** 警报开场白同理：它也要求"最后仍用固定格式收尾" */
    @Test
    void 警报开场白的收尾标记与系统提示词同源() {
        for (AgentLang lang : AgentLang.values()) {
            String opening = runner.alertInstruction(trader(), alert(), List.of(), lang);
            assertThat(opening).as("%s 警报开场白的收尾标记", lang.code())
                    .contains(prompts.get(lang, "trader.mark.conclusion"));
            for (AgentLang other : AgentLang.values()) {
                if (other != lang) {
                    assertThat(opening).doesNotContain(prompts.get(other, "trader.mark.conclusion"));
                }
            }
        }
    }

    /** 英文唤醒开场白（例行/警报/休眠提示）全文零中文 */
    @Test
    void 英文唤醒开场白全文无中文() {
        PromptI18nAssertions.assertNoCjk("英文例行开场白",
                runner.routineInstruction(trader(), BOUNDARY, "", AgentLang.EN));
        AiTrader windowed = trader();
        windowed.setWakeWindow("21:00-08:00");
        PromptI18nAssertions.assertNoCjk("英文例行开场白（带休眠提示）",
                runner.routineInstruction(windowed, BOUNDARY, "", AgentLang.EN));
        PromptI18nAssertions.assertNoCjk("英文警报开场白",
                runner.alertInstruction(trader(), alert(), List.of(), AgentLang.EN));
    }

    /** 中文侧成文逐字钉死：外置只搬位置，成文一个字不变 */
    @Test
    void 中文例行开场白逐字不变() {
        assertThat(runner.routineInstruction(trader(), BOUNDARY, "", AgentLang.ZH))
                .isEqualTo("新一根 1h K线已收盘（"
                        + java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                                .withZone(java.time.ZoneId.systemDefault())
                                .format(java.time.Instant.ofEpochMilli(BOUNDARY))
                        + "）。"
                        + "本轮只需回答一个问题：这根K线收盘后，你的计划需要改变吗？"
                        + "先检验上一轮【本轮结论】里的等待条件与各持仓的失效条件，再考虑新机会；"
                        + "最后按纪律用【本轮结论】固定格式收尾。");
    }

    /** 中文警报开场白逐字不变 */
    @Test
    void 中文警报开场白逐字不变() {
        assertThat(runner.alertInstruction(trader(), alert(), List.of(), AgentLang.ZH))
                .isEqualTo("⚠️ 行情波动警报（非例行唤醒）：BTCUSDT 5分钟内波动 5.2%（方向：上涨，现价 100000）。\n"
                        + "你上次唤醒本局还没有过唤醒，距下一次例行唤醒还有约 60 分钟。\n"
                        + "注意：当前 1h K线尚未收盘——你的收盘制失效条件此刻不作数，"
                        + "求证请用已收盘的 5m/15m K线。你的止损单仍在自动保护你。\n"
                        + "本次只需回答一个问题：这次波动是否动摇了你的持仓计划？计划未被动摇 → HOLD 并说明理由；"
                        + "不因为被叫醒而必须动作。最后仍用【本轮结论】固定格式收尾。");
    }
}

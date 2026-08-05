package com.mawai.wiibquant.agent.trader;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraderPromptAssemblerTest {

    private final TraderPromptAssembler assembler = new TraderPromptAssembler();

    private AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setName("测试员");
        t.setSymbols("BTCUSDT,ETHUSDT");
        t.setIntervalCode("1h");
        t.setCustomPrompt("只做突破，不抄底。");
        return t;
    }

    private AiTraderDecision decision(String reasoning) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(1785171600000L);
        d.setStatus(AiTraderDecision.STATUS_OK);
        d.setEquity(new BigDecimal("10123.45"));
        d.setReasoning(reasoning);
        d.setActionsJson("[{\"tool\":\"open_position\",\"status\":\"ok\"}]");
        return d;
    }

    @Test
    void containsHardRulesAccountAndCustomPrompt() {
        String prompt = assembler.assemble(trader(), "{\"balance\":10000}", List.of());

        assertThat(prompt)
                .contains("20")            // 杠杆上限
                .contains("50%")           // 保证金上限
                .contains("BTCUSDT,ETHUSDT")
                .contains("{\"balance\":10000}")
                .contains("只做突破，不抄底。");
    }

    @Test
    void emptyRecentDecisionsHidesSection() {
        String prompt = assembler.assemble(trader(), "{}", List.of());

        assertThat(prompt).doesNotContain("最近决策");
    }

    @Test
    void recentDecisionsRenderDigest() {
        String prompt = assembler.assemble(trader(), "{}",
                List.of(decision("突破前高做多，止损放在颈线下")));

        assertThat(prompt)
                .contains("最近决策")
                .contains("突破前高做多")
                .contains("10123");
    }

    @Test
    void nullCustomPromptStillWorks() {
        AiTrader t = trader();
        t.setCustomPrompt(null);

        assertThat(assembler.assemble(t, "{}", List.of())).contains("BTCUSDT");
    }
}

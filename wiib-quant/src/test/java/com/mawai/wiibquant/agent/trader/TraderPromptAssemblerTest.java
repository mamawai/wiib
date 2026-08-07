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
                .contains("3~20 倍")        // 默认杠杆区间
                .contains("5%~20%")        // 默认保证金区间
                .contains("虚拟资金模拟盘") // 规格是主人定的，模型不许评价
                .contains("BTCUSDT,ETHUSDT")
                .contains("{\"balance\":10000}")
                .contains("只做突破，不抄底。");
    }

    /**
     * 纪律锚定"计划内退出"：退出只认止损/止盈/失效条件，浮亏不是平仓理由，HOLD 是常态。
     * 旧版"亏损的实验也有产出/不开仓才是失败"是行动偏置的病根（nof1 第一季过度交易的教训），必须绝迹。
     */
    @Test
    void disciplineAnchorsPlanBasedExits() {
        String prompt = assembler.assemble(trader(), "{}", List.of());

        assertThat(prompt)
                .contains("虚拟资金")
                .contains("失效条件")
                .contains("浮亏不是平仓理由")
                .contains("HOLD 是常态")
                .contains("盈亏比")
                .contains("触发条件")
                .doesNotContain("亏损的实验也有产出")
                .doesNotContain("唯一真正的失败");
    }

    /** 模板必须交代计划管理工具与修改纪律：止损只许收紧、止盈只许远离入场、无计划持仓先补立 */
    @Test
    void templateMentionsPlanManagementTools() {
        String prompt = assembler.assemble(trader(), "{}", List.of());

        assertThat(prompt)
                .contains("set_take_profit")
                .contains("write_plan")
                .contains("只许收紧");
    }

    /** 仓位规格随配置渲染，且措辞是"区间里选"而非上限——模型选低了同样被拒 */
    @Test
    void positionSpecRenderedFromTraderConfig() {
        AiTrader custom = trader();
        custom.setLeverageMin(50);
        custom.setLeverageMax(100);
        custom.setMarginPctMin(new BigDecimal("10"));
        custom.setMarginPctMax(new BigDecimal("10"));

        String p = assembler.assemble(custom, "{}", List.of());
        assertThat(p).contains("50~100 倍").contains("10%~10%").contains("不是上限");
    }

    /** 自主加/减仓都开着时不出现审批段，省 token 也免得模型多想 */
    @Test
    void approvalSectionAbsentWhenBothSelfManaged() {
        AiTrader t = trader();
        t.setAllowSelfAdd(true);
        t.setAllowSelfReduce(true);

        assertThat(assembler.assemble(t, "{}", List.of())).doesNotContain("需要主人确认的动作");
    }

    /** 关掉自主减仓：必须同时告诉模型"止损止盈仍自动执行"，否则它会因为平不了仓而乱来 */
    @Test
    void approvalSectionExplainsStopsStillFire() {
        AiTrader t = trader();
        t.setAllowSelfAdd(true);
        t.setAllowSelfReduce(false);

        String p = assembler.assemble(t, "{}", List.of());
        assertThat(p).contains("减仓/平仓不会立即成交")
                .contains("止损单和止盈单是自动执行的")
                .doesNotContain("加仓（对已有仓位再开同方向）不会立即成交");
    }

    /** 单仓模式的措辞要把"挂单也占坑"讲明，否则模型会先挂单绕过 */
    @Test
    void singlePositionRuleMentionsPendingOrders() {
        AiTrader t = trader();
        t.setAllowMultiPosition(false);

        assertThat(assembler.assemble(t, "{}", List.of())).contains("挂单同样占坑");
    }

    /** 历史注入：最新一条全文保留（截1000），更早的截200——模型必须能读出上一轮的完整意图 */
    @Test
    void historyInjectsLatestFullAndOlderTruncated() {
        AiTraderDecision latest = decision("最新" + "x".repeat(500));
        latest.setWakeTime(1785175200000L);
        AiTraderDecision older = decision("旧的" + "y".repeat(500));

        // 与 runner 查询同序：倒序（最新在前）
        String prompt = assembler.assemble(trader(), "{}", List.of(latest, older));

        assertThat(prompt)
                .contains("x".repeat(500))
                .doesNotContain("y".repeat(300));
        assertThat(prompt).contains("y".repeat(150));
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

    /** 取消平台提示词：模板段消失，但账户状态/最近决策/自定义段照常注入 */
    @Test
    void optOutDefaultPromptKeepsDataAndCustomOnly() {
        AiTrader t = trader();
        t.setUseDefaultPrompt(false);

        String prompt = assembler.assemble(t, "{\"balance\":10000}",
                List.of(decision("突破前高做多")));

        assertThat(prompt)
                .doesNotContain("工具：")
                .doesNotContain("纪律：")
                .contains("{\"balance\":10000}")
                .contains("最近决策")
                .contains("只做突破，不抄底。");
    }

    @Test
    void nullCustomPromptStillWorks() {
        AiTrader t = trader();
        t.setCustomPrompt(null);

        assertThat(assembler.assemble(t, "{}", List.of())).contains("BTCUSDT");
    }
}

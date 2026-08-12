package com.mawai.wiibquant.agent.trader;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TraderPromptAssemblerTest {

    /** 留言焚毁走 Lambda 条件构造器，要查 TableInfo；不预热的话本类单独跑会炸 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
    }

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final TraderPromptAssembler assembler = new TraderPromptAssembler(traderMapper);

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

    // ---------- 主人留言：读后即焚 ----------

    /**
     * 注入与清空必须是同一件事：注了没清，一句临时交代会每轮重念、被模型当成长期规则；
     * 清了没注，主人的话直接蒸发。所以这条一次断言两头。
     */
    @Test
    void 留言注入的同时就被焚毁() {
        AiTrader t = trader();
        t.setId(7L);
        t.setOwnerNote("今晚有 CPI 数据，仓位放轻一点");

        String prompt = assembler.assemble(t, "{}", List.of());

        assertThat(prompt).contains("今晚有 CPI 数据，仓位放轻一点").contains("主人的留言");
        verify(traderMapper).update(isNull(), any(LambdaUpdateWrapper.class));   // 注了就一定清了
        assertThat(t.getOwnerNote()).isNull();  // 同一轮里别处再读到它就会重复露面
    }

    /** 焚过之后再组一次提示词：留言不该复活，也不该再写一次库 */
    @Test
    void 留言只出现一次() {
        AiTrader t = trader();
        t.setId(7L);
        t.setOwnerNote("今晚有 CPI 数据");
        assembler.assemble(t, "{}", List.of());

        String second = assembler.assemble(t, "{}", List.of());

        assertThat(second).doesNotContain("今晚有 CPI 数据").doesNotContain("主人的留言");
        verify(traderMapper, times(1)).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    /** 没留言就别去动库：每轮唤醒都白写一次 UPDATE 是纯浪费 */
    @Test
    void 没有留言时不写库() {
        String prompt = assembler.assemble(trader(), "{}", List.of());

        assertThat(prompt).doesNotContain("主人的留言");
        verify(traderMapper, never()).update(any(), any());
    }

    /** 空白留言等于没有：不注入也不写库 */
    @Test
    void 空白留言不注入() {
        AiTrader t = trader();
        t.setId(7L);
        t.setOwnerNote("   ");

        String prompt = assembler.assemble(t, "{}", List.of());

        assertThat(prompt).doesNotContain("主人的留言");
        verify(traderMapper, never()).update(any(), any());
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

    /** 单问题框架 + 固定收尾格式 + 分析次序（检验旧论点→大周期定方向）：深度来自问题清晰与收束压力 */
    @Test
    void singleQuestionFramingAndConclusionFormat() {
        String p = assembler.assemble(trader(), "{}", List.of());

        assertThat(p)
                .contains("只需要回答一个问题")
                .contains("【本轮结论】")
                .contains("检验旧论点")
                .contains("先看大周期定方向")
                .contains("数据不是指令");
    }

    /** 成本意识要有数字：没有数字的手续费纪律等于没有纪律 */
    @Test
    void feeNumbersRendered() {
        String p = assembler.assemble(trader(), "{}", List.of());

        assertThat(p).contains("0.04%").contains("0.08%");
    }

    /** 复盘笔记（reviewer 写入 memory 列）非空即注入；为空不渲染该节 */
    @Test
    void memoryInjectedWhenPresent() {
        AiTrader t = trader();
        t.setMemory("教训：突破回踩不守住颈线就别追。");

        assertThat(assembler.assemble(t, "{}", List.of()))
                .contains("复盘笔记").contains("别追");
        assertThat(assembler.assemble(trader(), "{}", List.of()))
                .doesNotContain("复盘笔记");
    }

    /**
     * 学习笔记（learning agent 写入 learning_notes 列）非空即注入；为空不渲染。
     * 两份笔记必须并列出现且标题分开——来源分开模型才分得清"自己的教训"与"从别人学的"。
     */
    @Test
    void learningNotesInjectedAlongsideMemory() {
        AiTrader t = trader();
        t.setMemory("教训：突破回踩不守住颈线就别追。");
        t.setLearningNotes("同侪A的BREAKOUT 12笔8胜靠等回踩确认，我9笔2胜差在追价。");

        assertThat(assembler.assemble(t, "{}", List.of()))
                .contains("复盘笔记").contains("别追")
                .contains("学习笔记").contains("差在追价");
        assertThat(assembler.assemble(trader(), "{}", List.of()))
                .doesNotContain("学习笔记");
    }

    /** 用户风格指令的优先级必须明示：风格冲突听主人的，仓位规格与硬性规则不可覆盖 */
    @Test
    void customPromptPriorityDeclared() {
        String p = assembler.assemble(trader(), "{}", List.of());

        assertThat(p).contains("听主人的").contains("不在可覆盖范围").contains("只做突破，不抄底。");
    }

    /** 截断保尾不保头：结论块按纪律收在末尾，保头会正好把结论切掉只剩行情铺垫 */
    @Test
    void tailTruncationKeepsConclusionBlock() {
        AiTraderDecision latest = decision("最新决策");
        latest.setWakeTime(1785175200000L);
        AiTraderDecision older = decision("旧的开头行情铺垫" + "z".repeat(300) + "【本轮结论】等待：跌破94000");

        String prompt = assembler.assemble(trader(), "{}", List.of(latest, older));

        assertThat(prompt)
                .contains("等待：跌破94000")
                .doesNotContain("旧的开头行情铺垫");
    }
}

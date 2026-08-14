package com.mawai.wiibquant.agent.learning;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibquant.agent.llm.MessagesSchema;
import com.mawai.wiibquant.agent.llm.ModelCallLimiter;
import com.mawai.wiibquant.agent.llm.ResilientChatService;
import com.mawai.wiibquant.agent.llm.ToolCallTraceHook;
import com.mawai.wiibquant.agent.llm.UsageTrackingChatModel;
import com.mawai.wiibquant.agent.trader.TraderModelFactory;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * learning agent 学习回路（向同侪学习，看别人不看自己；看自己的复盘见 ReviewRunner）：
 * ReactAgent + 唯一只读工具 peer_insights → 一份完整学习笔记 →
 * LEARN 决策行公开上时间线、全文覆盖 ai_trader.learning_notes。
 * <p>
 * 为什么这个必须是 agent 而不是 ReviewRunner 那样的单次调用：复盘的素材由代码算齐，模型只解读；
 * 学习面对的是一堆<b>需要甄别</b>的材料——看谁、看多深、值不值得学，下一步取决于上一步看到了什么，
 * 写不成固定步骤，正是 ReAct 循环的用武之地。
 * <p>
 * 失败语义：ERROR 行留痕、不动 learning_notes、不计连败——学习失败没有资金风险，不值得暂停机制。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningRunner {

    /**
     * 学习超时：ReAct 多轮（挑人 → 查 2~3 个详情 → 收束）比复盘那一次调用慢，180s 不够。
     * 这个数直接算进日线交接的停工窗口（≈复盘超时 + 学习超时），窗口期间调度拒绝一切唤醒——
     * 代价是跳过 1~2 根 5m K 线，每天只有一次，可接受。
     */
    static final int LEARN_TIMEOUT_SECONDS = 300;
    /** 学习笔记总量硬约束（与 memory 同口径）：取舍归模型，超限截断兜底 */
    static final int NOTES_MAX_CHARS = 2000;
    /** 单次学习模型调用上限（ReAct 保险丝，挡住"再看一个再看一个"烧用户的钱）：预期 2~5 次，8 留足余量 */
    static final int MAX_MODEL_CALLS = 8;
    /** LEARN 行的 interval 标记学习节奏（wake_time=日线边界），与 trader 唤醒档位无关 */
    static final String LEARN_INTERVAL_CODE = "1d";

    /** 合格判定的两个必需段标记：缺任一就是格式失守 */
    static final String LEARN_MARK = "【本期学习】";
    /**
     * 反照抄三件套之一：【不学什么】是必填段。只会说"值得学"的复盘等于没复盘——
     * 不做否定判断，模型就只是在抄。其余段落的条数上限不做代码硬校验，那是提示词的事。
     */
    static final String SKIP_MARK = "【不学什么】";

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PeerInsightService peerInsightService;
    private final TraderModelFactory modelFactory;
    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final StateSerializer<MessagesState<Message>> stateSerializer;

    /** 超时注入点：测试把 300s 缩短 */
    int timeoutSeconds = LEARN_TIMEOUT_SECONDS;

    /**
     * 日线边界学习入口（调度侧已判 learning_enabled 与同侪数量，这里不重复判）。
     * boundaryMs 即 LEARN 行的 wake_time，与本轮全体复盘同一个边界。
     */
    public void learn(AiTrader trader, long boundaryMs) {
        long start = System.currentTimeMillis();
        AiTraderDecision d = new AiTraderDecision();
        d.setTraderId(trader.getId());
        d.setRoundNo(trader.getRoundNo());
        d.setWakeTime(boundaryMs);
        d.setIntervalCode(LEARN_INTERVAL_CODE);
        d.setKind(AiTraderDecision.KIND_LEARN);
        d.setToolCalls(0);
        try {
            // 排行榜代码注入而不是让它自己查：这一步是必然发生的，白白花掉一次工具调用不值
            String leaderboard = peerInsightService.leaderboard(trader.getId());
            String output = runAgentSession(trader, boundaryMs, leaderboard, d);
            List<String> missing = missingMarks(output);
            d.setLatencyMs((int) (System.currentTimeMillis() - start));
            if (!missing.isEmpty()) {
                // 降级安全：一次格式失守不许污染笔记——ERROR 行存原文留痕，learning_notes 一个字不动
                d.setStatus(AiTraderDecision.STATUS_ERROR);
                d.setReasoning(output);
                d.setError("学习输出格式失守，缺" + String.join("与", missing) + "段，学习笔记不更新");
                decisionMapper.insert(d);
                log.warn("[Learn] 输出缺段 traderId={} missing={}", trader.getId(), missing);
                return;
            }
            d.setStatus(AiTraderDecision.STATUS_OK);
            d.setReasoning(output);
            decisionMapper.insert(d);
            // 笔记就是这份产出的全文（不像复盘要切两段）；超限截断兜底
            String notes = output.length() > NOTES_MAX_CHARS ? output.substring(0, NOTES_MAX_CHARS) : output;
            // 覆盖写 + 列级更新：并发唤醒回路正在改同一行的其它列，整行 updateById 会把它们打回旧值
            traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                    .eq(AiTrader::getId, trader.getId())
                    .set(AiTrader::getLearningNotes, notes)
                    .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
            log.info("[Learn] 学习完成 traderId={} 工具调用{}次 笔记{}字",
                    trader.getId(), d.getToolCalls(), notes.length());
        } catch (Exception e) {
            Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            String msg = t instanceof TimeoutException ? "学习超时(" + timeoutSeconds + "s)"
                    : t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            // LEARN 行已落库（异常出在之后写笔记那步）：这一轮学习本身是成功的，不该改写成 ERROR；
            // 而且 MP insert 已把自增 id 回填进 d，再 insert 必撞主键、异常直接逃出学习回路——
            // 与 TraderWakeupRunner 同款坑同款防护。只留日志。
            if (d.getId() != null) {
                log.warn("[Learn] LEARN行已存但写笔记失败 traderId={} msg={}", trader.getId(), msg);
                return;
            }
            d.setStatus(AiTraderDecision.STATUS_ERROR);
            d.setError(msg.length() > 500 ? msg.substring(0, 500) : msg);
            d.setLatencyMs((int) (System.currentTimeMillis() - start));
            decisionMapper.insert(d);
            // 不计连败不暂停：学习失败没有资金风险，明天同侪还在
            log.warn("[Learn] 学习失败 traderId={} boundary={} msg={}", trader.getId(), boundaryMs, msg);
        }
    }

    /** 缺哪些必需段（两段都缺就都列出来）——error 里要分得清是没写学习正文还是没做否定判断 */
    private static List<String> missingMarks(String output) {
        List<String> missing = new ArrayList<>(2);
        String text = output == null ? "" : output;
        if (!text.contains(LEARN_MARK)) {
            missing.add(LEARN_MARK);
        }
        if (!text.contains(SKIP_MARK)) {
            missing.add(SKIP_MARK);
        }
        return missing;
    }

    /** ReactAgent 会话：返回模型最终文本；工具轨迹与用量随 decision 一并写入。 */
    private String runAgentSession(AiTrader trader, long boundaryMs, String leaderboard,
                                   AiTraderDecision d) throws Exception {
        // 用量统计包在最外层：ReAct 一轮要调模型很多次。工厂里的实例是跨唤醒缓存的，
        // 装饰器必须每轮新建，否则用量会跨轮累加
        UsageTrackingChatModel model = new UsageTrackingChatModel(modelFactory.modelFor(trader));
        // "它看了谁"是 LEARN 行在公开时间线上的观赏点，全靠这个 hook 记
        ToolCallTraceHook trace = new ToolCallTraceHook();
        CompiledGraph<MessagesState<Message>> graph = ReactAgent.<MessagesState<Message>>builder()
                .chatModel(model)
                .stateSerializer(stateSerializer)
                .schema(MessagesSchema.SCHEMA)
                .defaultSystem(systemPrompt())
                .toolsFromObject(new PeerInsightToolkit(peerInsightService, trader.getId()))
                .addExecuteToolsHook(new ModelCallLimiter(MAX_MODEL_CALLS))
                .addExecuteToolsHook(trace)
                // 不强制首轮调工具：排行榜已随开场白注入，首轮该做的正是"挑谁值得深看"这步推理。
                // trader 那边强制是因为"不看行情不许决策"，这里没有这个前提
                .build(ResilientChatService.builder().model(model).asFactory())
                .compile();

        RunnableConfig config = RunnableConfig.builder()
                .threadId("learn-" + trader.getId() + "-" + boundaryMs).build();
        String instruction = userPrompt(trader, boundaryMs, leaderboard);
        // 虚拟线程 + FutureTask 承载超时；非流式 invoke（消费流用 forEachAsync 会栈溢出，实测）
        FutureTask<String> task = new FutureTask<>(() -> {
            MessagesState<Message> state = graph
                    .invoke(Map.of("messages", List.of(new UserMessage(instruction))), config)
                    .orElseThrow(() -> new IllegalStateException("图执行无返回状态"));
            return finalReasoning(state.messages());
        });
        Thread.startVirtualThread(task);
        try {
            return task.get(timeoutSeconds, TimeUnit.SECONDS);
        } finally {
            task.cancel(true);
            // 轨迹与用量都落 finally：超时作废的那一轮，"看了谁"和烧掉的 token 一样真实发生过
            List<JSONObject> calls = trace.calls();
            d.setActionsJson(JSON.toJSONString(calls));
            d.setToolCalls(calls.size());
            UsageTrackingChatModel.UsageSnapshot usage = model.snapshot();
            d.setModelCalls(usage.modelCalls());
            d.setPromptTokens(usage.promptTokens());
            d.setCompletionTokens(usage.completionTokens());
            d.setTotalTokens(usage.totalTokens());
        }
    }

    /**
     * 学习正文：往前找最近一条有正文的助手消息，而不是死盯最后一条。
     * 保险丝在工具边收束时，末尾是纯 tool_call 的助手消息 + 未执行占位回执，正文都是空的。
     */
    private static String finalReasoning(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage assistant
                    && assistant.getText() != null && !assistant.getText().isBlank()) {
                return assistant.getText();
            }
        }
        return "本轮模型全程只在查同侪，没有产出学习正文。";
    }

    /**
     * 身份先于指令：研究同行的交易员，不是评审——学的是能用在自己身上的东西，不是给别人打分。
     * 单问题框架：只回答"别人做对了什么，其中哪些对我真的有用"。
     * <p>
     * 反照抄三件套（对应 reviewer 的防自夸三件套）：①【不学什么】必填，强制做否定判断；
     * ②每条学习必须带证据与差距数字，不许写"他的风控意识值得学习"这种没法执行的话；
     * ③引用同侪战绩必须带笔数，样本少时运气和方法长得一模一样。
     */
    private static String systemPrompt() {
        return """
                你是一名职业加密货币合约交易员，现在是每日研究同行的时间。你不是评审，不给别人打分；\
                你只回答一个问题：别人做对了什么，其中哪些对我真的有用。

                用户消息里已经给了三样：本局同侪排行榜快照、你自己的复盘笔记、你上一份学习笔记。\
                你还有一个只读工具 peer_insights：传 traderId 深看某个同侪（他的复盘全文、他的学习笔记、\
                在场计划的论点与失效条件、最近已了结交易的「论点→结局」配对），不传则重新取排行榜。\
                看谁、看几个、看多深，你自己决定——没人能替你列出这个步骤。

                甄别是这项工作的本体，不是走过场：排行榜上的收益率是硬事实，但"他为什么赚钱"不是。\
                可能他的方法真好，也可能只是这段行情恰好配合他的风格（幸存者偏差）；样本少的时候，\
                运气和方法长得一模一样。把这两种分开，才是你今天要做的事。

                规则：
                - 引用同侪战绩必须带笔数。2 笔总结出来的"规律"要当场标明不可靠，不许拿它改自己的做法
                - 每条学习必须落在证据与差距上：写"他 BREAKOUT 12 笔 8 胜、我 9 笔 2 胜"这种可对照的\
                数字，不许写"他的风控意识值得学习"这种既没法执行也没法证伪的话
                - 学的是能用在自己身上的东西：拿他的做法对照你自己复盘笔记里的问题。对不上你自己\
                问题的优点，再漂亮也不是你今天该学的
                - 【不学什么】是必填段：看过而判断不值得学的，写清楚为什么不学。只会说"值得学习"的\
                复盘等于没复盘——不做否定判断，你就只是在抄
                - 这份笔记是滚动的：它会整份覆盖你的学习笔记，下一期你只看得到这一份。上一份里仍然\
                成立的结论要继承进来接着写，已失效或已经改掉的删掉——写完之后只看这一份，就应该\
                知道你到目前为止从同行身上学到的全部东西。条数上限是硬的，继承和新增一起挤；\
                全文控制在 2000 字以内，超出的部分会被直接截断
                - 全部输出使用中文（这份笔记会公开在竞技场时间线上），严格按以下格式，\
                三个段标题都必须出现：

                【本期学习】
                看了谁：traderId 与名字 + 为什么挑这几个
                学到什么：≤3 条，每条写「他的做法 → 证据（具体交易/数字，带笔数）→ 我的差距 → 我怎么改」

                【不学什么】
                ≤2 条。看过但判断不值得学的，写明理由\
                （例：他靠单笔 5 倍杠杆押对方向，样本 1 笔，不可复现）

                【前车之鉴】
                ≤2 条。从亏损/爆仓的同侪身上看到的坑，写明触发链条：\
                他做了什么 → 然后发生了什么 → 我在哪种情形下会踩同一个坑""";
    }

    /** 开场白：三样注入齐活（排行榜/自己的复盘笔记/上一份学习笔记），然后把挑人这一步交回给模型。 */
    private static String userPrompt(AiTrader trader, long boundaryMs, String leaderboard) {
        StringBuilder sb = new StringBuilder();
        sb.append("每日研究同行的时间到了（").append(TIME_FMT.format(Instant.ofEpochMilli(boundaryMs)))
                .append("）。以下是本局同侪的最新战况。\n\n");
        sb.append(leaderboard).append('\n');
        // 不给自己的复盘笔记，它会去学一堆跟自己毫无关系的东西
        sb.append("【你自己的复盘笔记】（你的问题在这里；学来的东西对不上它，就等于没学）\n")
                .append(blank(trader.getMemory()) ? "（尚无复盘笔记）" : trader.getMemory().strip())
                .append("\n\n");
        sb.append("【你上一份学习笔记】（你能看到的唯一一份，本次产出会把它整份覆盖）\n")
                .append(blank(trader.getLearningNotes())
                        ? "（尚无——这是本局第一次向同侪学习）" : trader.getLearningNotes().strip())
                .append("\n\n");
        sb.append("排行榜已经给你了。先自己判断谁值得深看，再用 peer_insights 逐个查详情")
                .append("（传排行榜里的 id）；看够了就收笔，按固定格式写出这一期的学习笔记。");
        return sb.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

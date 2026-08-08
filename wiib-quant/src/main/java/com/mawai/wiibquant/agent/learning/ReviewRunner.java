package com.mawai.wiibquant.agent.learning;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibquant.agent.llm.UsageTrackingChatModel;
import com.mawai.wiibquant.agent.trader.TraderModelFactory;
import com.mawai.wiibquant.mapper.AiTraderDecisionMapper;
import com.mawai.wiibquant.mapper.AiTraderMapper;
import com.openai.errors.OpenAIInvalidDataException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * learning agent 复盘回路：单次模型调用（无工具，素材由代码组装齐）→ 两段固定格式解析 →
 * 【本期复盘】进 REVIEW 决策行公开上时间线、【记忆更新】全文覆盖 ai_trader.memory。
 * 与 trader 只经 DB 解耦：这里写 memory，trader 每次唤醒只读注入，互相没有直接调用。
 * 失败语义：ERROR 行留痕、不动 memory、不计连败——复盘失败没有资金风险，不值得暂停机制。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewRunner {

    /** 复盘没有"下一根K线"的截止压力，固定预算即可 */
    static final int REVIEW_TIMEOUT_SECONDS = 180;
    /** 记忆总量硬约束：取舍归模型，超限截断兜底 */
    static final int MEMORY_MAX_CHARS = 2000;
    /** REVIEW 行的 interval 标记复盘节奏（wake_time=日线边界），与 trader 唤醒档位无关 */
    static final String REVIEW_INTERVAL_CODE = "1d";
    private static final String MEMORY_MARK = "【记忆更新】";
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ReviewMaterialAssembler assembler;
    private final TraderModelFactory modelFactory;
    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;

    /** 超时注入点：测试把 180s 缩短 */
    int timeoutSeconds = REVIEW_TIMEOUT_SECONDS;

    /** 日线边界复盘入口：素材窗口=(上次成功REVIEW, 本边界]；无新交易素材静默跳过不白烧钱。 */
    public void review(AiTrader trader, long boundaryMs) {
        Long last = assembler.lastSuccessfulReviewWake(trader.getId(), trader.getRoundNo());
        long fromMs = last == null ? 0 : last;
        if (!assembler.hasNewMaterial(trader.getId(), trader.getRoundNo(), fromMs, boundaryMs)) {
            log.info("[Review] 无新交易素材跳过 traderId={} boundary={}", trader.getId(), boundaryMs);
            return;
        }
        long start = System.currentTimeMillis();
        AiTraderDecision d = new AiTraderDecision();
        d.setTraderId(trader.getId());
        d.setRoundNo(trader.getRoundNo());
        d.setWakeTime(boundaryMs);
        d.setIntervalCode(REVIEW_INTERVAL_CODE);
        d.setKind(AiTraderDecision.KIND_REVIEW);
        d.setToolCalls(0);
        try {
            ReviewMaterialAssembler.ReviewMaterial material = assembler.assemble(trader, fromMs, boundaryMs);
            UsageTrackingChatModel model = new UsageTrackingChatModel(modelFactory.modelFor(trader));
            String output = callWithTimeout(model,
                    userPrompt(trader, material, fromMs, boundaryMs), d);
            if (output == null || output.isBlank()) {
                throw new IllegalStateException("模型输出为空");
            }
            Parsed parsed = parse(output);
            d.setStatus(AiTraderDecision.STATUS_OK);
            d.setReasoning(parsed.review());
            d.setLatencyMs((int) (System.currentTimeMillis() - start));
            decisionMapper.insert(d);
            if (parsed.memory() != null) {
                // 覆盖写 + 列级更新：trader 只读本列，learning 是唯一写方
                traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                        .eq(AiTrader::getId, trader.getId())
                        .set(AiTrader::getMemory, parsed.memory())
                        .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
                log.info("[Review] 复盘完成 traderId={} 已了结{}笔 memory={}字",
                        trader.getId(), material.closedTrades(), parsed.memory().length());
            } else {
                // 降级安全：一次格式失守不许污染记忆——REVIEW 行照存，memory 不动
                log.warn("[Review] 输出缺{}分隔符，REVIEW照存、memory不动 traderId={}",
                        MEMORY_MARK, trader.getId());
            }
        } catch (Exception e) {
            Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            String msg = t instanceof TimeoutException ? "复盘超时(" + timeoutSeconds + "s)"
                    : t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            d.setStatus(AiTraderDecision.STATUS_ERROR);
            d.setError(msg.length() > 500 ? msg.substring(0, 500) : msg);
            d.setLatencyMs((int) (System.currentTimeMillis() - start));
            decisionMapper.insert(d);
            // 不计连败不暂停：复盘失败没有资金风险，明天素材还在
            log.warn("[Review] 复盘失败 traderId={} boundary={} msg={}", trader.getId(), boundaryMs, msg);
        }
    }

    /** 虚拟线程承载超时；用量落 finally——超时作废的调用 token 也真烧了，不能不记。 */
    private String callWithTimeout(UsageTrackingChatModel model, String user, AiTraderDecision d) throws Exception {
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt()), new UserMessage(user)));
        FutureTask<String> task = new FutureTask<>(() -> {
            ChatResponse resp;
            try {
                resp = model.call(prompt);
            } catch (OpenAIInvalidDataException e) {
                // SDK 重试盲区（读响应中断）单次补救，与 ResilientChatService.callPrimary 同款
                log.warn("[Review] 响应读取中断（SDK不重试此类失败），单次重试: {}", e.toString());
                resp = model.call(prompt);
            }
            return resp.getResult() == null ? "" : resp.getResult().getOutput().getText();
        });
        Thread.startVirtualThread(task);
        try {
            return task.get(timeoutSeconds, TimeUnit.SECONDS);
        } finally {
            task.cancel(true);
            UsageTrackingChatModel.UsageSnapshot usage = model.snapshot();
            d.setModelCalls(usage.modelCalls());
            d.setPromptTokens(usage.promptTokens());
            d.setCompletionTokens(usage.completionTokens());
            d.setTotalTokens(usage.totalTokens());
        }
    }

    record Parsed(String review, String memory) {
    }

    /**
     * 两段解析：按最后一个【记忆更新】切分。缺分隔符 → REVIEW 照存、memory 返回 null 不动
     * （降级安全）；记忆段超限截断。复盘段意外为空时整篇当复盘存——公开留痕优先。
     */
    static Parsed parse(String output) {
        int idx = output.lastIndexOf(MEMORY_MARK);
        if (idx < 0) {
            return new Parsed(output.strip(), null);
        }
        String review = output.substring(0, idx).strip();
        String memory = output.substring(idx + MEMORY_MARK.length()).strip();
        if (memory.isEmpty()) {
            return new Parsed(review.isEmpty() ? output.strip() : review, null);
        }
        if (memory.length() > MEMORY_MAX_CHARS) {
            memory = memory.substring(0, MEMORY_MAX_CHARS);
        }
        return new Parsed(review.isEmpty() ? output.strip() : review, memory);
    }

    /**
     * 身份先于指令：给自己写交易日志的交易员，不是评价者——教训写给明天的自己。
     * 防自夸三件套在此：战绩数字只许复述、先找错误再找亮点、教训条数上限。
     */
    private static String systemPrompt() {
        return """
                你是一名职业加密货币合约交易员。现在是每日复盘时间——给自己写交易日志，写给明天\
                醒来的自己看。第一人称，只回答一个问题：这期我哪里错了、哪里对了、下期改什么。

                用户消息里是系统整理的本期硬事实（战绩表/已了结交易配对表/决策时间线/价格路径）\
                与你此前的记忆笔记。规则：
                - 战绩表数字只许原样复述，禁止自行计算或美化
                - 先找错误再找亮点；每条教训必须引用具体交易与数字，不引用数字的教训视为没有教训
                - 观望对账：把时间线里每条"等待"条件与价格路径逐条对照，判命中/未命中必须引用具体价格，\
                再判该行动没行动/该等没等
                - 所有输出使用中文，严格按以下两段格式，两段标题都必须出现：

                【本期复盘】
                战绩：<复述战绩表数字>
                逐笔教训：≤5 条，先错误后亮点，每条引用具体交易与数字
                观望对账：逐条等待条件 → 命中/未命中 + 价格证据 → 该行动没行动/该等没等
                下期纪律：≤3 条，可执行的具体改动

                【记忆更新】
                <旧笔记与本期教训浓缩后的完整新笔记，≤2000字；过时的删、仍有效的留；\
                这段会原样覆盖你的记忆，你之后每根K线醒来都会看到它>""";
    }

    private static String userPrompt(AiTrader trader, ReviewMaterialAssembler.ReviewMaterial m,
                                     long fromMs, long toMs) {
        String from = fromMs == 0 ? "本局开始" : TIME_FMT.format(Instant.ofEpochMilli(fromMs));
        StringBuilder sb = new StringBuilder();
        sb.append("复盘窗口：").append(from).append(" → ")
                .append(TIME_FMT.format(Instant.ofEpochMilli(toMs))).append("\n\n");
        sb.append(m.statsBlock()).append('\n');
        sb.append(m.tradesBlock()).append('\n');
        sb.append(m.timelineBlock()).append('\n');
        sb.append(m.pricePathBlock()).append('\n');
        sb.append("【你此前的记忆笔记】\n");
        sb.append(trader.getMemory() == null || trader.getMemory().isBlank()
                ? "（尚无——这是本局第一篇复盘）" : trader.getMemory()).append('\n');
        sb.append("\n现在写这一期的复盘日志：先对照硬事实检讨，再把记忆笔记续写压缩成新版。")
                .append("严格按两段固定格式输出。");
        return sb.toString();
    }
}

package com.mawai.wiibquant.agent.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.agent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibquant.agent.i18n.PromptCatalog;
import com.mawai.wiibquant.agent.llm.ConversationSummarizer;
import com.mawai.wiibquant.agent.llm.LlmErrorMessages;
import com.mawai.wiibquant.agent.llm.ModelCallLimiter;
import com.mawai.wiibquant.agent.llm.ResponsesChatModel;
import com.mawai.wiibquant.agent.llm.ToolChoice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 一轮对话的编排：问路由 → 并行跑专家 → 汇总出答案 → 历史落库。
 * <pre>
 * 载入历史 ──► ┌─ 问路由（浅模型，结构化 tool_call）
 *              │      ├ FINISH / 已派过 / 到轮次上限 / 有未消费授权 → 跳出
 *              │      └ 派新专家 → 并行跑（专家等待期=让位窗口）→ 结论按派发顺序接进历史 ─┐
 *              │                                                                        │
 *              └────────────────────────────────────────────────────────────────────────┘ 回到循环开头
 *         └─► summarizer 叶子流式作答（token 逐帧外发）→ 终态整体覆盖会话历史
 * </pre>
 * <b>为什么是普通 Java 循环而不是 StateGraph</b>：这段编排本身没有一处需要图——
 * 分支就是 if、并行就是虚拟线程、回环就是 while。而图要求把轮次和去重名单塞进 state
 * 才能跨节点传递，还附带迭代硬顶算账、hook 内联丢失、并行分支拿不到子流这些纯粹的额外成本。
 * 叶子 agent 保留 ReactAgent，因为那里的 ReAct 循环确实是框架在管。
 * <p>
 * <b>让位</b>（用户消息优先于专家返回）：专家等待期收到 {@link TurnYield} 的信号即让位——
 * 存档 working、把在途批次交回（{@link TurnResult}），由 {@link ChatYieldCoordinator}
 * 排队补答（{@link #runDeferredSummary}）。只有专家等待期可让：路由/汇总都在烧模型调用，
 * 中断只会浪费；专家等待纯粹在等 IO，让出去的只是"接着等"这件事。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTurnRunner {

    private final ChatContextStore contextStore;
    private final ApprovalRegistry approvalRegistry;
    private final PromptCatalog prompts;
    private final LocalizedToolCallbacks localizedTools;
    /** 专家并行用。虚拟线程：专家全程阻塞在上游 HTTP 上，池大小不该成为约束 */
    private final ExecutorService expertExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 结束派发、转去汇总的信号值。对齐 langgraph4j 官方 how-to 的 Router.next 值域（含 FINISH） */
    static final String FINISH = "FINISH";

    /**
     * 派发轮次上限，{@code while(true)} 的第二道闸。
     * <p>
     * <b>今天真正让循环停下来的是去重</b>（每轮至少吃掉一个专家名，名字用完必停）。
     * 现在正好三个专家，最坏情况（一轮派一个）恰好用满 3 轮，这个数不再有余量——
     * 再加专家就得把它一起抬，否则最后那个永远派不出去。
     * 留着它的理由是它兜的正是"去重失灵"：把 fresh 过滤删掉做变异跑，
     * 路由永远想派同一个专家，靠的就是这条闸收的场（实测）。
     */
    static final int MAX_DISPATCH_ROUNDS = 3;

    /**
     * 专家执行进度：专家走的是阻塞 invoke，token 流拿不到，改由这里主动推事件，
     * 前端据此渲染真实进度。
     *
     * @param agent 专家名
     * @param phase start=开始执行；done=完成，text 是结论原文；error=失败，text 是原因
     * @param text  phase=start 时为 null
     */
    public record ExpertProgress(String agent, String phase, String text) {
        public static final String START = "start";
        public static final String DONE = "done";
        public static final String ERROR = "error";
    }

    /**
     * 一轮的让位控制面（生产实现是 {@link ChatYieldCoordinator.TurnHandle}）：
     * runner 只声明自己需要什么，登记/信号的归属都在协调器那边，两个类不构成环。
     */
    public interface TurnYield {
        /** 进入专家等待期（让位窗口开）。返回让位信号：新消息到达时它被完成 */
        CompletableFuture<Void> enterExpertWait();

        /** 离开专家等待期（让位窗口关） */
        void exitExpertWait();

        /** 让位信号是否已发。粘滞：一旦发出，本轮后续任何检查点都立即让位 */
        boolean yieldRequested();

        /**
         * 用户点了停止。与让位的区别：让位是"这个问题稍后补答"，中断是"这个问题到此为止，不补"。
         * 同样粘滞，跑到下一个检查点就收尾。
         */
        default boolean cancelRequested() {
            return false;
        }

        /** 中断信号：专家等待期要靠它唤醒，否则点了停止得挂到整批专家跑完 */
        default CompletableFuture<Void> cancelSignal() {
            return new CompletableFuture<>();   // 永不完成＝这个调用方不支持中断
        }

        /** 无让位能力的空实现：调用方不支持让位（测试/一次性调用）时用 */
        TurnYield NONE = new TurnYield() {
            private final CompletableFuture<Void> never = new CompletableFuture<>();

            @Override
            public CompletableFuture<Void> enterExpertWait() {
                return never;
            }

            @Override
            public void exitExpertWait() {
            }

            @Override
            public boolean yieldRequested() {
                return false;
            }
        };
    }

    /**
     * 一轮的结局：完整跑完，或在专家等待期让位。
     * 让位时 {@code deferredExperts} 是在途的专家批次（可能已完成），
     * 由调用方交给 {@link ChatYieldCoordinator} 排队补答。
     */
    public record TurnResult(CompletableFuture<List<Message>> deferredExperts, boolean cancelled) {
        public static final TurnResult COMPLETED = new TurnResult(null, false);
        /** 用户中断：与让位不同，这一轮不欠补答，半截答案就是最终答案 */
        public static final TurnResult CANCELLED = new TurnResult(null, true);

        public boolean yielded() {
            return deferredExperts != null;
        }
    }

    /** 中断这一轮的最终文本：半截答案照留（token 已经烧掉了），一个字都没出就立块牌子。
     *  展示历史与模型上下文用同一份措辞，两边不打架 */
    static String cancelledAnswer(PromptCatalog prompts, AgentLang lang, String partial) {
        return partial == null || partial.isBlank()
                ? prompts.get(lang, "chat.cancelledEmpty")
                : partial + "\n\n" + prompts.get(lang, "chat.cancelledNote");
    }

    /**
     * 路由工具：只用来让模型**结构化地**表达"下一步给谁"，方法体永远不会被执行。
     * <p>
     * 为什么是工具而不是"让模型输出 JSON 数组再解析"：后者是我们自己发明的，四个框架没人这么做，
     * 代价已经实测过——路由指令混在文本里会泄漏给用户（["news_agent"]["news_agent"]）、
     * 会作为 AssistantMessage 进历史被模型照抄、解析还脆。alibaba 的 issue #4320/#4266
     * 记录的 routing instability + infinite loops 是同一个病。
     * 走 function calling 后参数天然结构化，且 tool_call 不进文本 token 流。
     */
    public static class RouterTool {
        // description 只是编译期兜底：真正发给模型的那份按语言取自词表 tool.route（见 LocalizedToolCallbacks）
        @Tool(name = "route", description = """
                Decide what comes next. Name the experts when real data is still needed; \
                give ["FINISH"] once the expert data is enough to answer.""")
        public String route(@ToolParam(description = """
                Where to go next: market_agent (prices/positions/liquidations/options),
                news_agent (crypto news flashes), trader_agent (the user's own AI trader:
                status/positions/decisions/plans/retrospective notes),
                or ["FINISH"] to stop dispatching and answer directly.""") List<String> next) {
            return "";
        }
    }

    /**
     * 路由工具的 callback，按语言各存一份：{@link LocalizedToolCallbacks} 拼一次要反射扫一遍工具类，
     * 一门语言建一次存着，不每轮重扫。
     */
    private final Map<AgentLang, List<ToolCallback>> routerTools = new EnumMap<>(AgentLang.class);

    private List<ToolCallback> routerTools(AgentLang lang) {
        return routerTools.computeIfAbsent(lang, l -> localizedTools.of(l, new RouterTool()));
    }

    /** 路由是轻决策，正常 3s 内返回；90s 判挂死进重试、仍失败降级 FINISH——实测上游挂死曾拖它满 10 分钟 */
    private static final Duration ROUTER_TIMEOUT = Duration.ofSeconds(90);

    /**
     * 跑完一轮对话。
     * <p>
     * 两个 sink 是这一层与 SSE 的唯一接口：调用方断连后照样让这轮跑完（答案要落历史），
     * 只是不再往通道里写——那是调用方在 sink 里自己判断的事。
     *
     * @param enrichedMessage 带时间行与提问标记的用户消息（拼法见 ChatWorkbenchController 的两个
     *                        MARKER 常量，重新生成靠它们在上下文里定位本轮提问）
     * @param answerTokenSink summarizer 的答案增量，逐帧
     * @param progressSink    专家的开始/完成/失败事件
     * @param yield           让位控制面；不支持让位的调用方传 {@link TurnYield#NONE}
     */
    public TurnResult run(ChatAgentFactory.Leaves leaves, long userId, String sessionId, String enrichedMessage,
                          Consumer<String> answerTokenSink, Consumer<ExpertProgress> progressSink,
                          TurnYield yield) {
        long startedAt = System.currentTimeMillis();
        // 语言取自叶子：它是建叶子时按用户语言烤死的，已经在叶子缓存键里，不必再查一次
        AgentLang lang = leaves.lang();
        List<Message> history = contextStore.load(sessionId);
        List<Message> working = new ArrayList<>(history);
        working.add(new UserMessage(enrichedMessage));

        Set<String> dispatched = new LinkedHashSet<>();
        int round = 0;
        while (true) {
            // 深研判确认后的续跑轮：存在未消费授权说明这一轮的使命就是让 summarizer 重调工具。
            // 专家数据上一轮刚取过、深研判也不消费它们，重派一遍纯烧钱——代码直通，不指望模型自觉 FINISH。
            // 这一判必须排在轮次检查之前：续跑轮的 round 本来就是 0，顺序反了看不出区别，
            // 但语义上"有授权"是无条件直通，与还剩几轮无关
            if (approvalRegistry.hasApproval(sessionId)) {
                log.info("[Workbench] 存在未消费的深研判授权，跳过派发直通汇总 session={}", sessionId);
                break;
            }
            // 中断压过让位：让位只是"这个问题稍后补答"，中断是"销账、不补"。
            // 顺序反了的话，点完停止再发一条消息就会让让位赢，被停掉的问题照样被补答轮跑完
            if (yield.cancelRequested()) {
                return cancelTurn(userId, sessionId, working, "", lang);
            }
            // 信号粘滞的兜底：等待期的 anyOf 竞争恰好被批次赢了，但用户消息已在门口等——
            // 结论已并入 working，不再烧新一轮派发，直接让位（空批次），欠的账交给补答轮
            if (yield.yieldRequested()) {
                return yieldTurn(userId, sessionId, working,
                        CompletableFuture.completedFuture(List.of()), lang);
            }
            if (round >= MAX_DISPATCH_ROUNDS) {
                log.warn("[Workbench] 派发轮次达上限 {}，转汇总", MAX_DISPATCH_ROUNDS);
                break;
            }
            List<String> next = askRouter(leaves.light(), working, lang);
            if (next.isEmpty() || next.contains(FINISH)) {
                // FINISH 与"解析不出专家名"（空）同型不同因，事后排查靠这行分辨；路由调用失败 askRouter 已有 warn
                log.info("[Workbench] 路由结束派发 next={}（第 {} 轮后转汇总）", next, round);
                break;
            }
            // 同一专家不重复派：它取的数这一轮内不会变，再派一次只是空转烧钱，
            // 而且这正是死循环的来源（模型总觉得"再查一次说不定有新东西"）。
            // 靠代码收敛，不指望模型自觉说 FINISH
            List<String> fresh = next.stream().filter(name -> !dispatched.contains(name)).toList();
            if (fresh.isEmpty()) {
                log.info("[Workbench] {} 本轮已取过数，转汇总", next);
                break;
            }
            round++;
            dispatched.addAll(fresh);
            log.info("[Workbench] 派发 {}（第 {} 轮）", fresh, round);
            // 快照传入：让位路径会往 working 里垫占位答复，专家线程不能共享读一个正被改的列表
            CompletableFuture<List<Message>> batch =
                    dispatchAsync(leaves, fresh, List.copyOf(working), progressSink, lang);
            CompletableFuture<Void> signal = yield.enterExpertWait();
            CompletableFuture<Void> stop = yield.cancelSignal();
            try {
                // 中断也要能唤醒这一等：专家是带工具的 ReAct 图、同步阻塞几十秒起，
                // 只等批次的话点了停止要挂到整批跑完，按钮会一直卡在"收尾中"
                CompletableFuture.anyOf(batch, signal, stop).join();
            } finally {
                yield.exitExpertWait();
            }
            if (yield.cancelRequested()) {
                // 在途批次照 fire-and-forget（与让位路径同哲学）：它们跑完也没人接，
                // 但账本被它们写脏了，见 markAbandoned
                leaves.deep().markAbandoned();
                leaves.light().markAbandoned();
                return cancelTurn(userId, sessionId, working, "", lang);
            }
            if (signal.isDone()) {
                // 让位优先于批次：批次恰好同刻完成也让——用户的新消息不该等一整段汇总流
                return yieldTurn(userId, sessionId, working, batch, lang);
            }
            working.addAll(batch.join());
        }

        working.add(new UserMessage(summaryTail(lang, !dispatched.isEmpty())));
        // 答案流的检查点在拉流循环里，中断时半截答案已经攒在这儿
        StringBuilder emitted = new StringBuilder();
        NodeOutput<MessagesState<Message>> last =
                streamSummarizer(leaves, working, userId, sessionId, chunk -> {
                    emitted.append(chunk);
                    answerTokenSink.accept(chunk);
                }, yield);
        if (yield.cancelRequested()) {
            // 拉流是 break 出来的，被丢下的那条流还在跑：图生成器不支持取消（见 ChatAgentFactory
            // 的说明），模型照样吐完、入账挂在流终止上。所以这次汇总调用的 token 省不掉，
            // 而且会落在读数之后——账本就此不可信，标记让它退化成只报耗时
            leaves.deep().markAbandoned();
            leaves.light().markAbandoned();
            return cancelTurn(userId, sessionId, working, emitted.toString(), lang);
        }

        // 终态含压缩替换 + 本轮全部新消息，整体覆盖会话历史（下一轮从这里起跑）
        List<Message> finalMessages = last.state().messages();
        contextStore.save(sessionId, userId, finalMessages);

        log.info("[TurnMetrics] session={} rounds={} experts={} summarizerCalls={} historyIn={} "
                        + "historyOut={} estTokensIn={} durationMs={}",
                sessionId, round, dispatched.size(),
                last.state().<Number>value(ModelCallLimiter.CALL_COUNT_KEY).map(Number::intValue).orElse(0),
                history.size(), finalMessages.size(),
                // 估算不是计费口径：按 CJK 1 字≈1 token 折的，只用来看"这轮喂进去多大"
                ConversationSummarizer.estimateTokens(working),
                System.currentTimeMillis() - startedAt);
        return TurnResult.COMPLETED;
    }

    /**
     * 进汇总前垫的最后一条消息，恒为用户侧——整段输入以 assistant 结尾，模型只会补一句"没什么可补充的"。
     * <ul>
     *   <li>{@code chat.expertHandoff}：给专家产出定性（取回的数据 / 取数失败 / 没有返回内容，
     *       三种形态都要覆盖）。没派专家时不垫——它指向的消息不存在。</li>
     *   <li>{@code chat.outputLanguage}：输出语言硬收尾，派没派专家都垫。用户打的字不翻译，
     *       聊天输入随时是另一门语言，且近因权重最高，语言指令必须排在它后面。</li>
     * </ul>
     * 两句合成一条消息：它随本轮终态落进会话历史，多一条就多占后续上下文。
     */
    // 包私有非 private：输出语言硬收尾那条钉子（ChatTurnRunnerTest）要拿成文验它在末尾
    String summaryTail(AgentLang lang, boolean dispatched) {
        String language = prompts.get(lang, "chat.outputLanguage");
        return dispatched ? prompts.get(lang, "chat.expertHandoff") + "\n" + language : language;
    }

    /**
     * 让位收尾：给原问题垫占位答复并存档 working（用户消息与已到手的专家结论都是花钱换的），
     * 在途批次原样交回——排队补答是 {@link ChatYieldCoordinator} 的事，这里只管把账记清。
     */
    private TurnResult yieldTurn(long userId, String sessionId, List<Message> working,
                                 CompletableFuture<List<Message>> inFlight, AgentLang lang) {
        // 让位时垫进模型上下文的占位答复：拦住新一轮 summarizer 替这个未回答的问题代答
        working.add(new AssistantMessage(prompts.get(lang, "chat.yieldPlaceholder")));
        contextStore.save(sessionId, userId, working);
        log.info("[Workbench] 专家等待期让位 session={}", sessionId);
        return new TurnResult(inFlight, false);
    }

    /**
     * 用户中断收尾：把已产出的半截当这一轮的答复存档，续聊接得上。
     * 与让位的区别是<b>不欠补答</b>——这个问题就到此为止，用户要么接着问、要么点重新生成。
     */
    private TurnResult cancelTurn(long userId, String sessionId, List<Message> working, String partial,
                                  AgentLang lang) {
        working.add(new AssistantMessage(cancelledAnswer(prompts, lang, partial)));
        contextStore.save(sessionId, userId, working);
        log.info("[Workbench] 用户中断 session={} 已产出={}字", sessionId, partial.length());
        return TurnResult.CANCELLED;
    }

    /**
     * 补答轮（{@link ChatYieldCoordinator} 在会话空闲时调）：让位轮欠下的答案在这里还。
     * 载入最新历史（含让位期间的新对话与占位答复）+ 在途专家的结论 + 补答指令 → summarizer 收尾。
     * 无 SSE 可推：答案由调用方落展示历史，前端靠 status 轮询补显。
     */
    public String runDeferredSummary(ChatAgentFactory.Leaves leaves, long userId, String sessionId,
                                     String question, List<Message> expertReplies) {
        long startedAt = System.currentTimeMillis();
        AgentLang lang = leaves.lang();
        List<Message> working = new ArrayList<>(contextStore.load(sessionId));
        working.addAll(expertReplies);
        working.add(new UserMessage(prompts.get(lang, "chat.deferred.instruction",
                Map.of("question", question)) + "\n" + prompts.get(lang, "chat.outputLanguage")));

        NodeOutput<MessagesState<Message>> last;
        StringBuilder answer = new StringBuilder();
        // 补答轮在后台跑、没有面板可点停止，不参与中断
        last = streamSummarizer(leaves, working, userId, sessionId, answer::append, TurnYield.NONE);
        contextStore.save(sessionId, userId, last.state().messages());
        log.info("[TurnMetrics] 补答 session={} experts={} durationMs={}",
                sessionId, expertReplies.size(), System.currentTimeMillis() - startedAt);
        if (!answer.isEmpty()) {
            return answer.toString();
        }
        // 极端场景（调用上限截停等）没有汇总产出：退专家结论原文，补答不至于空手。
        // 剥掉出处标注——它是给模型看的内部协议，兜底文案是直接给用户看的
        String fallback = expertReplies.stream().map(Message::getText)
                .filter(Objects::nonNull).map(ChatTurnRunner::stripExpertTag)
                .collect(Collectors.joining("\n")).strip();
        return fallback.isEmpty() ? prompts.get(lang, "chat.deferred.empty") : fallback;
    }

    /**
     * summarizer 叶子流式收尾（例行轮与补答轮共用）。
     * 摔了先把 working 落库再抛：用户消息和专家结论此刻只在内存里，不落库的话用户重试一遍，
     * 专家全得重派重烧（market 还打真实上游配额）。存 working 而不是半截 state：
     * 摔掉那次的工具往来本来就没凑成完整配对，不该进历史。
     */
    private NodeOutput<MessagesState<Message>> streamSummarizer(ChatAgentFactory.Leaves leaves,
                                                                List<Message> working, long userId,
                                                                String sessionId, Consumer<String> tokenSink,
                                                                TurnYield yield) {
        // threadId 是 ApprovalGate 取会话号的唯一来源（工具方法体看不到它），少了它 HITL 整条链断掉
        RunnableConfig config = RunnableConfig.builder().threadId(sessionId).build();
        NodeOutput<MessagesState<Message>> last = null;
        try {
            // 必须用普通迭代消费而非 forEachAsync：后者 thenCompose 递归自链，
            // 每个流式 chunk 叠一层栈帧，长回答（数千帧）会 StackOverflowError（真跑实证过）
            for (NodeOutput<MessagesState<Message>> output : leaves.summarizer().stream(
                    Map.of("messages", working), config)) {
                if (output instanceof StreamingOutput<?> streaming) {
                    String chunk = streaming.chunk();
                    if (chunk != null && !chunk.isEmpty()) {
                        tokenSink.accept(chunk);
                    }
                }
                last = output;
                if (yield.cancelRequested()) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            contextStore.save(sessionId, userId, working);
            throw e;
        }
        return last;
    }

    /**
     * 派一批专家，返回"全部到齐"的批次 future——等不等、等多久归调用方（让位窗口在那边）。
     * <p>
     * <b>按派发顺序接而不是先完成先接</b>：结论进历史的顺序得是确定的，
     * 否则同一个问题两次跑出来的上下文不一样，行为不可复现。
     *
     * @return 各专家的结论消息；出错的那个是一条说明失败的助手消息（见 {@link #runExpert}），
     *         所以正常路径下批次不会异常完成
     */
    private CompletableFuture<List<Message>> dispatchAsync(ChatAgentFactory.Leaves leaves, List<String> names,
                                                           List<Message> input,
                                                           Consumer<ExpertProgress> progressSink,
                                                           AgentLang lang) {
        List<CompletableFuture<Message>> futures = names.stream()
                .map(name -> CompletableFuture.supplyAsync(
                        () -> runExpert(name, leaves.experts().get(name), input, progressSink, lang),
                        expertExecutor))
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(v -> futures.stream().map(CompletableFuture::join)
                        .filter(Objects::nonNull).toList());
    }

    /** 单个专家：推进度 → （可选预取）→ 阻塞跑 → 交回带出处标注的产出。 */
    private Message runExpert(String name, ChatAgentFactory.Expert expert, List<Message> input,
                              Consumer<ExpertProgress> progressSink, AgentLang lang) {
        progressSink.accept(new ExpertProgress(name, ExpertProgress.START, null));
        try {
            List<Message> messages = new ArrayList<>(input);
            // 包装文案保持中性：怎么用这份数据（独占还是与搜索合并）由各专家的 instruction 定
            if (expert.preload() != null) {
                messages.add(new UserMessage(
                        prompts.get(lang, "chat.preloadHeader") + "\n" + expert.preload().get()));
            }
            // 专家叶子没有 saver，每次 invoke 都从 schema 起算，不需要 threadId 隔离
            Message reply = expert.graph()
                    .invoke(Map.of("messages", messages), RunnableConfig.builder().build())
                    .flatMap(MessagesState::lastMessage)
                    .orElse(null);
            String text = reply == null ? null : reply.getText();
            if (text == null || text.isBlank()) {
                // 空结论不当数据接：接了 summarizer 只会答"没有数据"，且无处排查
                log.warn("[Workbench] 专家 {} 没有产出任何内容", name);
                progressSink.accept(new ExpertProgress(name, ExpertProgress.ERROR,
                        prompts.get(lang, "chat.expertNoContentReason")));
                return expertMessage(prompts, lang, name, "chat.expertStatus.noContent",
                        prompts.get(lang, "chat.expertNoContentBody"));
            }
            progressSink.accept(new ExpertProgress(name, ExpertProgress.DONE, text));
            return expertMessage(prompts, lang, name, "chat.expertStatus.data", text);
        } catch (Exception e) {
            // 单个专家失败不该拖垮整轮：把失败作为一条消息交回，summarizer 自行判断要不要绕开。
            // 两个出口都过归类：原始 SDK 异常可能几百字符，喂回模型既白烧 token，
            // 又把上游细节（可能含 key）连同答案一起写进会话历史持久化
            log.warn("[Workbench] 专家 {} 执行失败", name, e);
            String reason = LlmErrorMessages.classify(e, prompts, lang);
            progressSink.accept(new ExpertProgress(name, ExpertProgress.ERROR, reason));
            return expertMessage(prompts, lang, name, "chat.expertStatus.failed", reason);
        }
    }

    /**
     * 专家产出接进上下文的统一形态：<b>带出处标注的用户侧消息</b>。
     * <p>
     * 裸 AssistantMessage 在模型眼里是"我自己刚说过的话"——既分不出哪些是取回来的数据，
     * 整段输入还会以 assistant 结尾，于是倾向于答"没有数据"（实测）。
     * 标注同时让这些消息在后续轮次里不冒充"助手以前给过的答案"。
     */
    // 包私有非 private：兜底剥标注的钉子要拿真实格式验往返
    static Message expertMessage(PromptCatalog prompts, AgentLang lang, String agent,
                                 String statusKey, String body) {
        return new UserMessage(prompts.get(lang, "chat.expertTag",
                Map.of("agent", agent, "status", prompts.get(lang, statusKey))) + "\n" + body);
    }

    /**
     * 剥掉 {@link #expertMessage} 的出处标注行，格式与它配对维护。
     * 两套括号都认（中文【】/英文 []）：标注是写入时那门语言拼的。
     */
    static String stripExpertTag(String text) {
        return text.replaceFirst("^(【[^】]*】|\\[[^\\]]*])\n", "");
    }

    /**
     * 问模型"下一步给谁"。强制走 route 工具，模型没法用自由文本糊弄过去。
     * 单次调用，无条件 required（不走 ResilientChatService 的"首轮强制"——历史里只要有过
     * ToolResponseMessage 就会被首轮判据误判成非首轮）。options 从模型自己的派生、tool_choice 按协议落地，
     * 都归 {@link ToolChoice}：openai 协议下泛型 builder 造的 options 会被 OpenAiChatModel 硬转失败（真跑实证）
     */
    private List<String> askRouter(ChatModel model, List<Message> history, AgentLang lang) {
        List<Message> messages = new ArrayList<>(history.size() + 1);
        messages.add(new SystemMessage(prompts.get(lang, "chat.router")));
        messages.addAll(history);
        long startedAt = System.currentTimeMillis();
        try {
            ChatOptions options = ResponsesChatModel.withCallTimeout(
                    ToolChoice.apply(ToolChoice.withTools(model, routerTools(lang)), ToolChoice.REQUIRED),
                    ROUTER_TIMEOUT);
            ChatResponse response = model.call(new Prompt(messages, options));
            List<String> next = parseRouteCall(Objects.requireNonNull(response.getResult()).getOutput());
            // openai 协议路没有 [Responses] 那样的请求日志，路由慢在模型还是慢在别处只能靠这行分辨
            log.info("[Workbench] 路由回答 next={} 耗时={}ms", next, System.currentTimeMillis() - startedAt);
            return next;
        } catch (Exception e) {
            // 路由失败不该把整轮对话拖死：退化成"不派发直接作答"，用户至少拿得到回复
            log.warn("[Workbench] 路由调用失败，转汇总", e);
            return List.of(FINISH);
        }
    }

    /** 从 tool_call 参数里取专家名单。结构化解析，不碰自由文本。 */
    static List<String> parseRouteCall(AssistantMessage message) {
        for (AssistantMessage.ToolCall call : message.getToolCalls()) {
            if (!"route".equals(call.name())) {
                continue;
            }
            JSONArray next = JSON.parseObject(call.arguments()).getJSONArray("next");
            if (next == null || next.isEmpty()) {
                return List.of();
            }
            List<String> names = new ArrayList<>(next.size());
            for (Object item : next) {
                if (FINISH.equals(item)) {
                    return List.of(FINISH);
                }
                if (item instanceof String name && ChatAgentFactory.EXPERT_AGENTS.contains(name)) {
                    names.add(name);
                }
            }
            return names;
        }
        return List.of();
    }
}

package com.mawai.wiibquant.agent.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.mawai.wiibquant.agent.llm.ConversationSummarizer;
import com.mawai.wiibquant.agent.llm.LlmErrorMessages;
import com.mawai.wiibquant.agent.llm.ModelCallLimiter;
import com.mawai.wiibquant.agent.llm.ResilientChatService;
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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 一轮对话的编排：问路由 → 并行跑专家 → 汇总出答案 → 历史落库。
 * <pre>
 * 载入历史 ──► ┌─ 问路由（浅模型，结构化 tool_call）
 *              │      ├ FINISH / 已派过 / 到轮次上限 / 有未消费授权 → 跳出
 *              │      └ 派新专家 → 并行跑 → 结论按派发顺序接进历史 ─┐
 *              │                                                    │
 *              └────────────────────────────────────────────────────┘ 回到循环开头
 *         └─► summarizer 叶子流式作答（token 逐帧外发）→ 终态整体覆盖会话历史
 * </pre>
 * <b>为什么是普通 Java 循环而不是 StateGraph</b>：这段编排本身没有一处需要图——
 * 分支就是 if、并行就是虚拟线程、回环就是 while。而图要求把轮次和去重名单塞进 state
 * 才能跨节点传递，还附带迭代硬顶算账、hook 内联丢失、并行分支拿不到子流这些纯粹的额外成本。
 * 叶子 agent 保留 ReactAgent，因为那里的 ReAct 循环确实是框架在管。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTurnRunner {

    private final ChatContextStore contextStore;
    private final ApprovalRegistry approvalRegistry;
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
     * 路由工具：只用来让模型**结构化地**表达"下一步给谁"，方法体永远不会被执行。
     * <p>
     * 为什么是工具而不是"让模型输出 JSON 数组再解析"：后者是我们自己发明的，四个框架没人这么做，
     * 代价已经实测过——路由指令混在文本里会泄漏给用户（["news_agent"]["news_agent"]）、
     * 会作为 AssistantMessage 进历史被模型照抄、解析还脆。alibaba 的 issue #4320/#4266
     * 记录的 routing instability + infinite loops 是同一个病。
     * 走 function calling 后参数天然结构化，且 tool_call 不进文本 token 流。
     */
    public static class RouterTool {
        @Tool(name = "route", description = """
                决定下一步。需要真实数据时给出专家名；专家数据已够、可以作答时给 ["FINISH"]。""")
        public String route(@ToolParam(description = """
                下一步去向：market_agent(行情/持仓/清算/期权)、news_agent(加密新闻快讯)、
                trader_agent(用户自己的AI交易员：状态/持仓/决策/计划/复盘笔记)，
                或 ["FINISH"] 表示不再派发、直接作答。""") List<String> next) {
            return "";
        }
    }

    private static final String ROUTER_INSTRUCTION = """
            你是研判工作台的调度器。看完对话后，用 route 工具给出下一步：
            - 还需要真实数据 → 给专家名：market_agent(实时行情/持仓/清算/期权)、
              news_agent(加密新闻快讯，BlockBeats 快讯源)
            - 问"我的 trader/我的交易员"怎么样、持了什么仓、某笔为什么这么做、交易计划、
              复盘学到了什么 → trader_agent（用户自己那个 AI 交易员的档案，只读）
            - 涉及行情、新闻、用户自己 trader 的问题必须先派专家取数，不要凭记忆判断
            - 对话里已有专家返回的数据、足够回答用户了 → 给 ["FINISH"]
            - 同一批专家已经取过数就不要重复派，改给 ["FINISH"]
            只调用 route 工具，不要输出任何文字。""";

    /** 路由工具的 callback：常量化，避免每轮重新反射扫描 */
    private static final List<ToolCallback> ROUTER_TOOLS = List.of(
            MethodToolCallbackProvider.builder().toolObjects(new RouterTool()).build().getToolCallbacks());

    /**
     * 跑完一轮对话。
     * <p>
     * 两个 sink 是这一层与 SSE 的唯一接口：调用方断连后照样让这轮跑完（答案要落历史），
     * 只是不再往通道里写——那是调用方在 sink 里自己判断的事。
     *
     * @param enrichedMessage 已经拼好记忆前缀的用户消息
     * @param answerTokenSink summarizer 的答案增量，逐帧
     * @param progressSink    专家的开始/完成/失败事件
     */
    public void run(ChatAgentFactory.Leaves leaves, long userId, String sessionId, String enrichedMessage,
                    Consumer<String> answerTokenSink, Consumer<ExpertProgress> progressSink) {
        long startedAt = System.currentTimeMillis();
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
            if (round >= MAX_DISPATCH_ROUNDS) {
                log.warn("[Workbench] 派发轮次达上限 {}，转汇总", MAX_DISPATCH_ROUNDS);
                break;
            }
            List<String> next = askRouter(leaves.light(), working);
            if (next.isEmpty() || next.contains(FINISH)) {
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
            working.addAll(dispatchOnce(leaves, fresh, working, progressSink));
        }

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
                        answerTokenSink.accept(chunk);
                    }
                }
                last = output;
            }
        } catch (RuntimeException e) {
            // 汇总摔了也要把已经花钱取到的东西留住：用户消息和专家结论此刻只在内存里，
            // 不落库的话用户重试一遍，专家全得重派重烧（market 还打真实上游配额）。
            // 存 working 而不是半截 state：摔掉那次的工具往来本来就没凑成完整配对，不该进历史
            contextStore.save(sessionId, userId, working);
            throw e;
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
    }

    /**
     * 派一批专家并等齐。
     * <p>
     * <b>按派发顺序 join 而不是先完成先接</b>：结论进历史的顺序得是确定的，
     * 否则同一个问题两次跑出来的上下文不一样，行为不可复现。
     *
     * @return 各专家的结论消息，出错的那个是一条说明失败的助手消息（见 {@link #runExpert}）
     */
    private List<Message> dispatchOnce(ChatAgentFactory.Leaves leaves, List<String> names,
                                       List<Message> working, Consumer<ExpertProgress> progressSink) {
        // 同一批专家看到的是同一份输入：结论要等全部 join 完才追加进 working，
        // 所以并行期间没人写它，直接共享读即可（runExpert 自己会拷一份来加预取数据）
        List<CompletableFuture<Message>> futures = names.stream()
                .map(name -> CompletableFuture.supplyAsync(
                        () -> runExpert(name, leaves.experts().get(name), working, progressSink),
                        expertExecutor))
                .toList();
        List<Message> replies = new ArrayList<>(names.size());
        for (CompletableFuture<Message> future : futures) {
            Message reply = future.join();
            if (reply != null) {
                replies.add(reply);
            }
        }
        return replies;
    }

    /** 单个专家：推进度 → （可选预取）→ 阻塞跑 → 交回最后一条消息。 */
    private Message runExpert(String name, ChatAgentFactory.Expert expert, List<Message> input,
                              Consumer<ExpertProgress> progressSink) {
        progressSink.accept(new ExpertProgress(name, ExpertProgress.START, null));
        try {
            List<Message> messages = new ArrayList<>(input);
            // 包装文案保持中性：怎么用这份数据（独占还是与搜索合并）由各专家的 instruction 定
            if (expert.preload() != null) {
                messages.add(new UserMessage("【以下是系统预取的原始数据】\n" + expert.preload().get()));
            }
            // 专家叶子没有 saver，每次 invoke 都从 schema 起算，不需要 threadId 隔离
            Message reply = expert.graph()
                    .invoke(Map.of("messages", messages), RunnableConfig.builder().build())
                    .flatMap(MessagesState::lastMessage)
                    .orElse(null);
            progressSink.accept(new ExpertProgress(name, ExpertProgress.DONE,
                    reply == null ? "" : reply.getText()));
            return reply;
        } catch (Exception e) {
            // 单个专家失败不该拖垮整轮：把失败作为一条消息交回，summarizer 自行判断要不要绕开。
            // 两个出口都过归类：原始 SDK 异常可能几百字符，喂回模型既白烧 token，
            // 又把上游细节（可能含 key）连同答案一起写进会话历史持久化
            log.warn("[Workbench] 专家 {} 执行失败", name, e);
            String reason = LlmErrorMessages.classify(e);
            progressSink.accept(new ExpertProgress(name, ExpertProgress.ERROR, reason));
            return new AssistantMessage("[" + name + " 暂时不可用：" + reason + "]");
        }
    }

    /** 问模型"下一步给谁"。强制走 route 工具，模型没法用自由文本糊弄过去。 */
    private static List<String> askRouter(ChatModel model, List<Message> history) {
        List<Message> messages = new ArrayList<>(history.size() + 1);
        messages.add(new SystemMessage(ROUTER_INSTRUCTION));
        messages.addAll(history);
        try {
            ChatResponse response = model.call(new Prompt(messages, ToolCallingChatOptions.builder()
                    .toolCallbacks(ROUTER_TOOLS)
                    // 用"每次都强制"而非"首轮强制"：路由是单次调用，
                    // 而历史里只要有过 ToolResponseMessage 就会被"首轮"那套判据误判成非首轮
                    .toolContext(Map.of(ResilientChatService.FORCE_TOOL_CHOICE, "required"))
                    .build()));
            return parseRouteCall(Objects.requireNonNull(response.getResult()).getOutput());
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

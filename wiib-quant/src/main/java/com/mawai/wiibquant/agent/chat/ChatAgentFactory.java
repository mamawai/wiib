package com.mawai.wiibquant.agent.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.config.AiAgentRuntime;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibquant.agent.config.AiRuntimeRefreshedEvent;
import com.mawai.wiibquant.agent.llm.ConversationSummarizer;
import com.mawai.wiibquant.agent.llm.ModelCallLimiter;
import com.mawai.wiibquant.agent.llm.ResilientChatService;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import lombok.extern.slf4j.Slf4j;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.SubGraphNode;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.bsc.langgraph4j.agent.Agent;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.hook.EdgeHook;
import org.bsc.langgraph4j.hook.NodeHook;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.AppenderChannel;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

/**
 * 研判工作台对话图（P4）：深模型 supervisor 调度三个浅模型专家，自己画图。
 * <pre>
 * START → supervisor ──条件边──┬──────────────────────────────► END（已能作答）
 *                             └→ dispatch → [market ∥ quant ∥ news] → join ─┐
 *                                    ▲                                       │
 *                                    └───────────────────────────────────────┘ 回环再判断
 * </pre>
 * 几处关键取舍：
 * <ul>
 *   <li><b>并行是静态三条边</b>：langgraph4j 的条件边只能选单个目标（Command.gotoNode 是 String），
 *       表达不了"动态决定并行哪几个"。所以三个专家每轮都被调度，未派发者在节点里立即返回——
 *       空转成本是微秒级，且不触发任何模型调用</li>
 *   <li><b>专家用普通节点而非子图节点</b>：子图节点无条件执行，做不到"未派发就跳过"；
 *       而并行分支的内部节点本就冒不到父流（langgraph4j 的 ParallelNode 会 reduce 掉子流），
 *       用子图节点也换不来过程可见性，改手动调用零损失</li>
 *   <li><b>进度靠旁路</b>：专家的 token 流拿不到，节点自己经 RunnableConfig 里的 sink 推
 *       "开始/完成"事件，前端据此渲染真实进度</li>
 * </ul>
 * 模型是构建期绑定的，监听 {@link AiRuntimeRefreshedEvent} 重建缓存实现热更新。
 */
@Slf4j
@Component
public class ChatAgentFactory {

    public static final String MARKET_AGENT = "market_agent";
    public static final String NEWS_AGENT = "news_agent";
    public static final Set<String> EXPERT_AGENTS = Set.of(MARKET_AGENT, NEWS_AGENT);

    /** 结束派发、转去汇总的信号值。对齐 langgraph4j 官方 how-to 的 Router.next 值域（含 FINISH） */
    static final String FINISH = "FINISH";

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
                下一步去向：market_agent(行情/持仓/清算/期权)、news_agent(加密新闻快讯)，
                或 ["FINISH"] 表示不再派发、直接作答。""") List<String> next) {
            return "";
        }
    }

    /** 路由结果在 state 里的键：router 节点写入，条件边只读它，绝不解析消息文本 */
    static final String NEXT_KEY = "router_next";
    /** 派发名单在 state 里的键：router 写入，专家节点读取判断"轮到我没有" */
    static final String DISPATCH_KEY = "dispatch_list";
    /** 本轮提问已经派过的专家（累积）：同一个不再派第二次，见 {@link #route} */
    static final String DISPATCHED_KEY = "dispatched_agents";
    /** 派发轮次计数键：主图回环的保险丝*/
    static final String DISPATCH_ROUND_KEY = "dispatch_round";
    /**
     * 派发轮次上限。ModelCallLimiter 挂在 supervisor 的工具边上，管不到主图回环
     * （supervisor → dispatch → 专家 → join → supervisor 不过工具节点），这条路得自己数。
     * 一轮派发拿数据 + 一轮补充足够，留 3 是余量；超了强制收尾，总比撞迭代硬顶抛异常强。
     * <p>
     * 这个数也是 {@link #PARENT_RECURSION_LIMIT} 的一个乘数，改它要一起核对那边的账。
     */
    static final int MAX_DISPATCH_ROUNDS = 3;

    /**
     * 父图的迭代硬顶（框架默认 25，不够用）。图每走一步吃一格，超了直接抛
     * {@code Maximum number of iterations (n) reached!}——它抛在<b>结果交出去之前</b>，
     * 所以保险丝哪怕已经打完 {@code [CallLimit]} 日志也白搭，"截断但可用的回答"照样拿不到。
     * <p>
     * 实测账（打桩模型跑生产 {@link #chatGraph()}，逐格上探"最小能跑通的硬顶"）：
     * <pre>
     * 迭代格 = 3L + 4R + 4    L = summarizer 的 ReAct 轮数(= run-model-call-limit)，R = 专家派发轮数
     *   3/轮  summarizer 一轮：流式模型节点 2 格（交回 token 生成器 + 合并它的 resultValue）+ 工具边 1 格
     *   4/轮  一轮派发：dispatch + __PARALLEL__ + join + 回环 router
     *   4     固定开销：__START__ + 进 summarizer 前那次 router + __END__ + 跑完再问一次生成器的那格
     * 实测点：L=1..9 且 R=0 逐个扫过，最小值恒为 3L+4（L=7→25、L=8→28）；
     *         L=8 时 R=1 派 1 个专家→32、R=1 派 2 个→32、R=2→36。每个点都验过"减 1 格就抛硬顶"。
     * token 帧不吃格：summarizer 每轮吐 1 帧、20 帧、200 帧、800 帧，最小值都是 28。
     * </pre>
     * <b>4R 里没有"专家个数"这一项</b>（R=1 派 1 个和派 2 个同为 32，实测隔离过）：扇出是
     * {@code ParallelNode} 单个节点在自己 {@code apply()} 里做完的，而且没被派发的专家节点每轮
     * 照样跑（立即 {@code return Map.of()}），专家个数在框架的计数里根本没有出场机会。
     * <p>
     * 取 40 = L 吃满 8、R 吃满 {@link #MAX_DISPATCH_ROUNDS}=3 的账（24+12+4）。
     * 今天只有两个专家、派完就被 {@link #route} 的去重挡住，R 实际最多到 2 → 最坏 36，
     * 余下 4 格正好是一轮派发的量。
     * <p>
     * <b>注意 R=3 是刚好吃满 40、零余量</b>（判据是 {@code > maxIterations} 所以 40 能过）。
     * 也就是说加第三个专家时这个数还够用，但届时再往回环里加任何一个节点都会当场撞顶——
     * 那时候要改的是这里，不是去调 L。
     * <p>
     * 硬顶不是越大越好——它兜的就是死循环，抬太高等于没有。真正的两道闸门（L 和 R）都锁在
     * 40 以内，正常情况下永远轮不到硬顶说话；轮到了就说明有环没收住，那才是它该抛的时候。
     * <p>
     * 这本账的钉子在 {@code ChatIterationBudgetTest}，改图结构（往回环里加节点、给 summarizer
     * 再挂一层）就会红，别只改代码不改这段。
     */
    static final int PARENT_RECURSION_LIMIT = 40;

    /** 进度 sink 在 RunnableConfig metadata 里的键（值为 {@code Consumer<ExpertProgress>}） */
    public static final String PROGRESS_SINK_KEY = "workbench_progress_sink";

    /**
     * 专家执行进度：并行分支的 token 流被框架 reduce 掉了拿不到，改由节点主动推这个。
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

    private static final String NODE_ROUTER = "router";
    private static final String NODE_DISPATCH = "dispatch";
    private static final String NODE_JOIN = "join";
    private static final String NODE_SUMMARIZER = "summarizer";
    private static final String GOTO_DISPATCH = "dispatch";
    private static final String GOTO_SUMMARIZE = "summarize";

    private static final String ROUTER_INSTRUCTION = """
            你是研判工作台的调度器。看完对话后，用 route 工具给出下一步：
            - 还需要真实数据 → 给专家名：market_agent(实时行情/持仓/清算/期权)、
              news_agent(加密新闻快讯，BlockBeats 快讯源)
            - 涉及行情、新闻的问题必须先派专家取数，不要凭记忆判断
            - 对话里已有专家返回的数据、足够回答用户了 → 给 ["FINISH"]
            - 同一批专家已经取过数就不要重复派，改给 ["FINISH"]
            只调用 route 工具，不要输出任何文字。""";

    /** 路由工具的 callback：常量化，避免每次建图重新反射扫描 */
    private static final List<ToolCallback> ROUTER_TOOLS = List.of(
            MethodToolCallbackProvider.builder().toolObjects(new RouterTool()).build().getToolCallbacks());

    private final AiAgentRuntimeManager runtimeManager;
    private final MarketToolkit marketToolkit;
    private final NewsToolkit newsToolkit;
    private final DeepAnalysisService deepAnalysisService;
    private final WorkbenchRunRegistry runRegistry;
    private final ApprovalRegistry approvalRegistry;
    private final BaseCheckpointSaver checkpointSaver;
    /** 与 saver 同一个实例：序列化格式不一致会导致 checkpoint 写得进读不出 */
    private final StateSerializer<MessagesState<Message>> stateSerializer;
    private final int runModelCallLimit;
    private final int summarizeThresholdTokens;
    private final int summarizeKeepMessages;
    /** 补充源在回答里的标签，如 [X]；源名可配，见构造参数 supplementSource 的说明 */
    private final String supplementTag;
    /** 两边都有的事件合并后的标签，如 [BlockBeats+X] */
    private final String mergedTag;

    private volatile CompiledGraph<MessagesState<Message>> cached;

    /**
     * @param supplementSource 补充源名。BlockBeats 之外那一路是 summarizer 模型自带的联网搜索捞的，
     *                         搜到的是哪个平台随模型走（当前 grok 出的是 X），换模型就未必还是它。
     *                         所以提示词里一律只说"联网搜索"不点名平台，只有输出标签用这个名字——
     *                         换源改配置一处，提示词不用动
     */
    public ChatAgentFactory(AiAgentRuntimeManager runtimeManager,
                            MarketToolkit marketToolkit,
                            NewsToolkit newsToolkit,
                            DeepAnalysisService deepAnalysisService,
                            WorkbenchRunRegistry runRegistry,
                            ApprovalRegistry approvalRegistry,
                            BaseCheckpointSaver checkpointSaver,
                            StateSerializer<MessagesState<Message>> stateSerializer,
                            @Value("${quant.workbench.run-model-call-limit:8}") int runModelCallLimit,
                            @Value("${quant.workbench.summarize-threshold-tokens:32000}") int summarizeThresholdTokens,
                            @Value("${quant.workbench.summarize-keep-messages:6}") int summarizeKeepMessages,
                            @Value("${quant.workbench.news-supplement-source:}") String supplementSource) {
        this.runtimeManager = runtimeManager;
        this.marketToolkit = marketToolkit;
        this.newsToolkit = newsToolkit;
        this.deepAnalysisService = deepAnalysisService;
        this.runRegistry = runRegistry;
        this.approvalRegistry = approvalRegistry;
        this.checkpointSaver = checkpointSaver;
        this.stateSerializer = stateSerializer;
        this.runModelCallLimit = runModelCallLimit;
        this.summarizeThresholdTokens = summarizeThresholdTokens;
        this.summarizeKeepMessages = summarizeKeepMessages;
        // 不配就用中性的 Web：标签总得有个名字，但代码里不该替某个平台站队
        String source = supplementSource == null || supplementSource.isBlank() ? "Web" : supplementSource.trim();
        this.supplementTag = "[" + source + "]";
        this.mergedTag = "[BlockBeats+" + source + "]";
    }

    /** 合并标签（[BlockBeats+源名]）：提示词与真跑断言共用一处，改了源名断言自动跟上 */
    public String mergedTag() {
        return mergedTag;
    }

    /** 对话图单例（编译含 PostgresSaver），模型刷新事件后重建。 */
    public CompiledGraph<MessagesState<Message>> chatGraph() throws Exception {
        CompiledGraph<MessagesState<Message>> graph = cached;
        if (graph != null) return graph;
        synchronized (this) {
            if (cached == null) {
                cached = build();
                log.info("对话工作台图已构建（supervisor + {} 专家并行 + PostgresSaver）", EXPERT_AGENTS.size());
            }
            return cached;
        }
    }

    @EventListener(AiRuntimeRefreshedEvent.class)
    public void onRuntimeRefreshed() {
        synchronized (this) {
            cached = null;
        }
        log.info("模型配置刷新，对话图缓存已失效待重建");
    }

    private CompiledGraph<MessagesState<Message>> build() throws Exception {
        AiAgentRuntime runtime = runtimeManager.current();
        ChatModel deep = runtime.quantChatModel();
        ChatModel light = runtime.quantLightChatModel();
        ChatModel fallback = runtime.chatChatModel();

        Map<String, CompiledGraph<MessagesState<Message>>> experts = new LinkedHashMap<>();
        experts.put(MARKET_AGENT, expertGraph(light, marketToolkit, "required", """
                你是市场状态专家。用工具获取真实数据回答，所有结论必须引用工具返回的具体数字；
                数据不可用(available=false)时如实告知，绝不编造。
                只回答行情/持仓/清算/期权，新闻等其他领域即使知道也不要写，有对应专家负责。
                回答精炼中文。"""));
        // 新闻专家只管 BlockBeats：数据走"预取"（news_search 无参数，预取 100% 保证快讯在
        // 上下文里，不依赖模型行为；不挂 function tool——实测挂着它 auto 下还会再调一次纯浪费）。
        // 联网补的那一路不归它：模型的服务端搜索关不掉（grok 实测所有请求参数/换模型均无效），
        // 与其在两处禁，不如把搜索正式划给 summarizer 当职责、这里明令禁用——预取喂饱后它没有搜索动机，禁得住
        experts.put(NEWS_AGENT, expertGraph(light, null, null, """
                你是加密新闻专家。对话里已附上 BlockBeats 快讯原文（约20条），只基于它输出清单：
                每条格式：[BlockBeats] + 事件一句话 + 可能影响一句话，按市场影响力从高到低排序，
                同一事件的多条快讯合并为一条，除合并外不要删减。
                严禁把你联网搜索到的任何内容写进回答——这部分由上游汇总者负责。
                不评价真伪、不给投资建议。原文为空时如实说"暂无快讯"，绝不编造。输出精炼中文。"""));

        // 序列化器必须显式给：默认重载装的是 Java 对象流，存 checkpoint 时 clone 不动 Spring AI Message
        StateGraph<MessagesState<Message>> graph = new StateGraph<>(MessagesState.SCHEMA, stateSerializer);
        graph.addNode(NODE_ROUTER, node_async((state, config) -> route(state, light, config)));
        graph.addNode(NODE_DISPATCH, node_async(state -> Map.of()));
        // 只有 news 需要预取（工具无参、必调）；market/quant 的工具要按问题选 symbol，交给模型
        experts.forEach((name, expert) -> addExpertNode(graph, name, expert,
                NEWS_AGENT.equals(name) ? newsToolkit::newsSearch : null));
        graph.addNode(NODE_JOIN, node_async(state -> Map.of()));
        // 工具的模型建图期绑定：BYOK 后"当前是哪个用户"只有建图这一层知道，
        // 工具方法体里再去 runtimeManager 现取就取错人了
        graph.addNode(NODE_SUMMARIZER, summarizerGraph(deep, fallback,
                new DeepAnalysisToolkit(deep, deepAnalysisService, runRegistry)));

        graph.addEdge(START, NODE_ROUTER);
        // 条件边只读 router 写好的结构化结果，绝不解析消息文本（对齐官方 how-to 的 state.next()）
        graph.addConditionalEdges(NODE_ROUTER, edge_async(ChatAgentFactory::nextFromState),
                Map.of(GOTO_DISPATCH, NODE_DISPATCH, GOTO_SUMMARIZE, NODE_SUMMARIZER));
        // 三条同源边 → 框架内部建 ParallelNode；三条边汇聚 join 完成 fan-in
        for (String name : experts.keySet()) {
            graph.addEdge(NODE_DISPATCH, name);
            graph.addEdge(name, NODE_JOIN);
        }
        graph.addEdge(NODE_JOIN, NODE_ROUTER);      // 回环：带着专家数据再判一次还要不要补数据
        graph.addEdge(NODE_SUMMARIZER, END);

        // summarizer 的两个 hook 只能挂在这里。子 StateGraph 上注册的 hook 会在
        // addNode(id, StateGraph) 内联时被框架整个丢掉（只搬 nodes/edges），挂在子图上一次都不执行；
        // 而按内联后的 id 注册又会被 compile() 的图校验拒掉（校验跑在内联之前，那时还没有
        // summarizer-action 这个 id）。于是只剩"全局注册 + hook 内自己按 id 过滤"这一条路
        String toolsEdge = SubGraphNode.formatId(NODE_SUMMARIZER, Agent.ACTION_LABEL);   // summarizer-action
        String modelNode = SubGraphNode.formatId(NODE_SUMMARIZER, Agent.AGENT_LABEL);    // summarizer-agent
        for (EdgeHook.WrapCall<MessagesState<Message>> hook :
                summarizerToolHooks(approvalRegistry, runModelCallLimit)) {
            graph.addWrapCallEdgeHook(onlyOnEdge(toolsEdge, hook));
        }
        graph.addWrapCallNodeHook(onlyOnNode(modelNode, wrapBefore(
                new ConversationSummarizer(light, summarizeThresholdTokens, summarizeKeepMessages))));

        // 这是父图唯一的编译点，硬顶只能在这儿抬（框架默认 25 连一轮专家都跑不完，见 PARENT_RECURSION_LIMIT）
        return graph.compile(CompileConfig.builder()
                .checkpointSaver(checkpointSaver)
                .recursionLimit(PARENT_RECURSION_LIMIT)
                .build());
    }

    /**
     * 父图的全局 edge hook 会命中<b>每一处带 mapping 的跳转</b>，按 sourceId 收窄到目标那处。
     * 本图里实测命中两处：{@code router}（真条件边）和 {@code summarizer-action}
     * （其实是 Command 节点，{@code addNode(String, AsyncCommandAction, Map)} 建的，不是边）。
     * <p>
     * 漏了这层过滤的直接后果：ModelCallLimiter 在 router 那处短路回 {@code Command("end")}，
     * 而 router 的 mapping 只有 {dispatch, summarize}，当场 "cannot find edge mapping"；
     * 而且 router 那一跳也会被计进模型调用数，上限提前一轮触发。
     */
    static EdgeHook.WrapCall<MessagesState<Message>> onlyOnEdge(
            String sourceId, EdgeHook.WrapCall<MessagesState<Message>> delegate) {
        return (id, state, config, action) -> sourceId.equals(id)
                ? delegate.applyWrap(id, state, config, action)
                : action.apply(state, config);
    }

    /**
     * 同上，node 版。全局 node hook 会命中图里每一个节点（router / 专家 / summarizer-action 都在内），
     * 不收窄的话 ConversationSummarizer 会在这些地方也压一遍：白烧浅模型的钱，
     * 还会在错误的时机整体替换 messages。
     */
    static NodeHook.WrapCall<MessagesState<Message>> onlyOnNode(
            String nodeId, NodeHook.WrapCall<MessagesState<Message>> delegate) {
        return (id, state, config, action) -> nodeId.equals(id)
                ? delegate.applyWrap(id, state, config, action)
                : action.apply(state, config);
    }

    /**
     * summarizer 工具边上的 hook，<b>顺序即语义</b>：WrapCall 是 reduce 左折叠
     * （{@code reduce(action, (acc, w) -> new WrapCallChainLink(id, w, acc))}），
     * 流里最后一个成为最外层，即后注册的先执行。<b>列表末尾 = 最外层。</b>
     * <p>
     * 保险丝必须在最外层：反过来会出现"ReAct 逼近调用上限时闸门先弹了卡，
     * 但模型已经没配额把这件事告诉用户"——卡片弹出来了，用户收不到任何解释。
     * 写反了代码照跑什么都不报错，钉子在 {@code ApprovalGateOrderTest}。
     * <p>
     * 上限值不是随便取的：它就是迭代账里的 <b>L</b>，直接决定父图的硬顶要开多大，
     * 改它必须一起核对 {@link #PARENT_RECURSION_LIMIT}。生产取 8。
     * <p>
     * <b>同一个配置也喂着专家图，但两边的账不一样，别当成有一处写错了</b>：
     * {@link #expertGraph} 没有 {@code .streaming(true)}，非流式模型节点只吃 1 格，
     * 一轮 {@code 2} 格、共 {@code 2L+3} → L=8 实测吃 19 格；专家图结尾是无参 {@code .compile()}，
     * 吃框架默认 25，够用（这也是它这次不用改的原因）。
     * <p>
     * 还要注意这<b>一个</b>配置项管的是"每个 agent 各自的上限"而不是"整轮总量"：
     * summarizer 和每个带工具的专家各跑各的 state，{@link ModelCallLimiter#CALL_COUNT_KEY}
     * 计数互不相通。所以一轮对话的模型调用是各家相加（summarizer ≤8、每个带工具的专家各 ≤8，
     * 再加上 router 每轮一次），不是 8 次封顶。想收总量得另立机制，不是把这个数调小。
     * <p>
     * <b>"一轮"这个作用域是撑出来的，不是天生的</b>：计数存在 state 里，而父图带 checkpointSaver，
     * 续聊按同一 threadId 从上次 checkpoint 起算。全靠 {@code ChatWorkbenchController.run()}
     * 每轮把这个键清零；那行没了就退化成"一个会话累计 8 次"，第 8 轮起 summarizer 再也调不动工具。
     * 专家侧不受影响——专家子图是无参 {@code .compile()}、没有 saver，每次 invoke 都从 schema 起算。
     */
    static List<EdgeHook.WrapCall<MessagesState<Message>>> summarizerToolHooks(
            ApprovalRegistry registry, int limit) {
        return List.of(new ApprovalGate(registry), new ModelCallLimiter(limit));  // 内层 → 外层
    }

    /**
     * 专家 agent：浅模型 + 自己那套工具的 ReAct 循环。
     * <p>
     * 包私有而非 private：ExpertCallLimitTest 要直接调它真跑一遍，才验得到"生产代码里保险丝挂没挂"。
     *
     * @param toolkit              可空。null=纯预取/纯模型能力的专家（如 news），不挂任何 function tool
     * @param forceFirstToolChoice "required"=首轮强制调工具（工具带参数、数据必须模型现取的专家）；
     *                             null=不强制
     */
    CompiledGraph<MessagesState<Message>> expertGraph(ChatModel model, Object toolkit,
                                                      String forceFirstToolChoice, String instruction)
            throws Exception {
        ReactAgent.Builder<MessagesState<Message>> builder = ReactAgent.<MessagesState<Message>>builder()
                .chatModel(model)
                .stateSerializer(stateSerializer)
                .defaultSystem(instruction);
        if (toolkit != null) {
            builder.toolsFromObject(toolkit);
            // 有工具才有 ReAct 循环，没保险丝就一路顶到框架 25 次迭代硬顶抛异常；而 market 的工具
            // 每调一次就打一次真实上游，是行情配额账里唯一没封顶的一项。
            // 这里 hook 真生效：结尾 .compile() 是独立编译，不走父图 addNode(id, StateGraph) 那条会丢掉子图 hook 的内联通道
            builder.addExecuteToolsHook(new ModelCallLimiter(runModelCallLimit));
        }
        // 专家的立身之本是"用工具拿真实数据"：不强制的话模型可能用自带的内置搜索直接答，
        // 工具一次都不调，数据源就失控了（本系统的行情/预测战绩全被绕过去）
        return builder.build(ResilientChatService.builder().model(model)
                        .forceFirstToolChoice(forceFirstToolChoice).asFactory())
                .compile();
    }

    /**
     * 汇总 agent：深模型 + 深研判工具，只管把专家数据写成最终回答。
     * <p>
     * 派谁、还要不要再派，全归 {@link #route} 那个结构化路由节点管，这里一个字都不提——
     * 角色单一，模型不会再纠结"该作答还是该派发"（那正是之前无限循环的病根）。
     */
    private StateGraph<MessagesState<Message>> summarizerGraph(ChatModel deep, ChatModel fallback,
                                                               DeepAnalysisToolkit toolkit)
            throws Exception {
        return ReactAgent.<MessagesState<Message>>builder()
                .chatModel(deep)
                .stateSerializer(stateSerializer)
                .streaming(true) // 答案要逐字推给前端
                .toolsFromObject(toolkit)
                .defaultSystem("""
                        你是加密货币研判工作台的分析师。对话里已经有专家 agent 取回的真实数据，
                        你的职责是据此写出最终回答（新闻的联网补充也归你，见原则2）。
                        不要提及调度、专家名或内部流程。

                        回答原则：
                        1. 结论必须可追溯到专家给的数据，不编造；专家没给的数据就说没有；
                           行情/预测数字只能引用 market/quant 专家给的，不得用你搜到的行情数字替换
                        2. 新闻的分工（news_agent 只管 BlockBeats，联网补充归你）：对话里有 news_agent 的
                           [BlockBeats] 清单时，用你的联网搜索再收集约20条最新加密要闻并合并——
                           news_agent 的条目一条不丢、保留 [BlockBeats] 标；你搜到的独有条目一律标 %s 并尽量附出处；
                           同一事件两边都有则合并为一条标 %s。按市场影响力排序取前30条，
                           不足30就全部输出，除去重外不删减。只列真实搜到的，搜不到就只用专家清单
                        3. 被问涨跌方向时不要生硬拒绝：本系统不做方向预测，给"双向情景 + 当前市场状态
                           （资金费/持仓/清算等实测数据）+ 仓位/止损等风控参考"，并说明方向确定性低的原因
                        4. 信号矛盾时大方说"看不清"，这是专业而不是失职
                        5. 仅当用户明确说出"深度研判/全面分析"这类字眼时 → 调 run_deep_analysis 工具（昂贵，需用户确认：
                           返回 PENDING_APPROVAL 时告知用户确认卡片已弹出，等确认后你会被再次唤起执行）；
                           "怎么看走势"这类普通提问不要调它、也不要主动推销，直接按专家数据作答

                        输出精炼中文。""".formatted(supplementTag, mergedTag))
                // 调用上限与历史压缩两个 hook 不在这儿挂：这张子图会被 addNode(id, StateGraph) 内联进主图，
                // 内联只搬 nodes/edges，挂在这里的 hook 一次都不会执行。见 build() 末尾
                .build(ResilientChatService.builder()
                        .model(deep).fallbackModel(fallback)
                        .maxAttempts(3).initialDelay(500).maxDelay(4000)
                        .asFactory());
    }

    /**
     * 把 BeforeCall 语义的钩子接到 ReactAgent 只暴露的 WrapCall 上：
     * 先跑钩子拿状态更新，合并进 state 后再执行真正的模型调用。
     */
    private NodeHook.WrapCall<MessagesState<Message>> wrapBefore(
            NodeHook.BeforeCall<MessagesState<Message>> before) {
        return (nodeId, state, config, action) -> before.applyBefore(nodeId, state, config)
                .thenCompose(update -> {
                    if (update.isEmpty()) {
                        return action.apply(state, config);
                    }
                    Map<String, Object> merged = AgentState.updateState(state, update, MessagesState.SCHEMA);
                    return action.apply(new MessagesState<>(merged), config)
                            // 压缩结果要一并写回 state，否则下次调用又得重压一遍
                            .thenApply(result -> mergeUpdates(update, result));
                });
    }

    /**
     * 合并压缩与模型产出。两边都会写 messages 键，但语义相反：压缩给的是「整体替换」
     * （{@link AppenderChannel.ReplaceAllWith}），模型给的是「追加」。直接 putAll 会让替换被追加盖掉，
     * 压缩等于白做——state 仍是未压缩的老历史，下次调用还得重压一遍烧钱。
     * 正解是把模型本轮的新消息接到压缩后历史的尾巴上，整体替换写回。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> mergeUpdates(Map<String, Object> compression, Map<String, Object> modelResult) {
        // summarizer 是流式的，模型节点交回的 messages 是个 AsyncGenerator（token 流），
        // 真消息要等流跑完才有。生成器必须原样交回图，否则前端一个 token 都收不到；
        // 而且它既不是 Collection 也不是 Message，下面的分支会当作"看不懂的值"直接扔掉——
        // 答案没了，紧接着工具节点读到的最后一条不是 AssistantMessage，当场报 no AssistantMessage provided
        if (modelResult.get("messages") instanceof AsyncGenerator<?> stream) {
            Map<String, Object> merged = new LinkedHashMap<>(modelResult);
            merged.put("messages", mergeAtStreamEnd((AsyncGenerator<Object>) stream, compression));
            return merged;
        }
        Map<String, Object> merged = new LinkedHashMap<>(compression);
        merged.putAll(modelResult);
        if (!(compression.get("messages") instanceof AppenderChannel.ReplaceAllWith<?>(List<?> newValues))) {
            return merged; // 没压缩：模型产出照常追加
        }
        List<Message> all = new ArrayList<>((List<Message>) newValues);
        switch (modelResult.get("messages")) {
            case Collection<?> many -> many.forEach(m -> all.add((Message) m));
            case Message message -> all.add(message);
            case null, default -> { }
        }
        merged.put("messages", new AppenderChannel.ReplaceAllWith<>(all));
        return merged;
    }

    /**
     * 把压缩结果推迟到 token 流收尾那一刻再合并。图对生成器的处理是：先把 token 逐帧推给前端，
     * 跑完拿它的 resultValue（{@code {"messages": 本轮消息}}）并入 state——
     * 压缩要落进同一次写入，就只能改写这个 resultValue。
     * <p>
     * 两处已知的、当前无影响但别被重新发现的事：
     * <ul>
     *   <li>这一层没实现 {@code AsyncGenerator.Cancellable}，包上之后图生成器的 cancel 传不到
     *       底层的 StreamingChatGenerator（{@code WithEmbed.cancel()} 只 cancel 栈里实现了该接口的项）。
     *       本仓从不 cancel 图生成器——{@code ChatWorkbenchController.run()} 断连后是<b>故意</b>
     *       继续消费到底好落历史的，所以现在没有影响；哪天真要支持中止，这里得补上</li>
     *   <li>流出错就原样放行不合并：压缩这一次白做，下次模型调用会重新压。
     *       是有意的降级——这条路上再加补救只会把一次失败放大成两次</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static AsyncGenerator<Object> mergeAtStreamEnd(AsyncGenerator<Object> stream,
                                                           Map<String, Object> compression) {
        return new AsyncGenerator<>() {
            @Override
            public Data<Object> next() {
                Data<Object> data = stream.next();
                return data.isDone() && data.resultValue() instanceof Map<?, ?> result
                        ? Data.done(mergeUpdates(compression, (Map<String, Object>) result))
                        : data;
            }

            @Override
            public Executor executor() {
                return stream.executor();
            }
        };
    }

    /**
     * 路由节点：一次模型调用，强制用 route 工具**结构化**给出去向。
     * <p>
     * 产出只写 state 的 {@link #NEXT_KEY}/{@link #DISPATCH_KEY}，<b>不进 messages</b>——
     * 路由是控制流不是对话内容。之前把它当消息塞进历史，直接导致三件事：
     * 泄漏给用户看、被模型照抄着反复派发、解析 {@code ["a"]["a"]} 失败。
     */
    Map<String, Object> route(MessagesState<Message> state, ChatModel model, RunnableConfig config) {
        // 深研判确认后的续跑轮：存在未消费授权说明这一轮的使命就是让 summarizer 重调工具。
        // 专家数据上一轮刚取过、深研判也不消费它们，重派一遍纯烧钱——代码直通，不指望模型自觉 FINISH
        // threadId 拿不到时不再兜底到全局活跃槽（该槽已删，多用户下它返回的是别人的会话号）
        String sessionId = config.threadId().orElse(null);
        if (sessionId != null && approvalRegistry.hasApproval(sessionId)) {
            log.info("[Workbench] 存在未消费的深研判授权，跳过派发直通汇总 session={}", sessionId);
            return Map.of(NEXT_KEY, FINISH);
        }
        int round = state.<Number>value(DISPATCH_ROUND_KEY).map(Number::intValue).orElse(0);
        if (round >= MAX_DISPATCH_ROUNDS) {
            log.warn("[Workbench] 派发轮次达上限 {}，转汇总", MAX_DISPATCH_ROUNDS);
            return Map.of(NEXT_KEY, FINISH);
        }
        List<String> next = askRouter(model, state.messages());
        if (next.isEmpty() || next.contains(FINISH)) {
            return Map.of(NEXT_KEY, FINISH);
        }
        // 同一专家不重复派：它取的数这一轮内不会变，再派一次只是空转烧钱，
        // 而且这正是死循环的来源（模型总觉得"再查一次说不定有新东西"）。
        // 靠代码收敛，不指望模型自觉说 FINISH
        List<String> done = state.<List<String>>value(DISPATCHED_KEY).orElse(List.of());
        List<String> fresh = next.stream().filter(name -> !done.contains(name)).toList();
        if (fresh.isEmpty()) {
            log.info("[Workbench] {} 本轮已取过数，转汇总", next);
            return Map.of(NEXT_KEY, FINISH);
        }
        log.info("[Workbench] 派发 {}（第 {} 轮）", fresh, round + 1);
        return Map.of(NEXT_KEY, GOTO_DISPATCH,
                DISPATCH_KEY, fresh,
                DISPATCHED_KEY, Stream.concat(done.stream(), fresh.stream()).toList(),
                DISPATCH_ROUND_KEY, round + 1);
    }

    /** 问模型"下一步给谁"。强制走 route 工具，模型没法用自由文本糊弄过去。 */
    private List<String> askRouter(ChatModel model, List<Message> history) {
        List<Message> messages = new ArrayList<>(history.size() + 1);
        messages.add(new SystemMessage(ROUTER_INSTRUCTION));
        messages.addAll(history);
        try {
            ChatResponse response = model.call(new Prompt(messages, ToolCallingChatOptions.builder()
                    .toolCallbacks(ROUTER_TOOLS)
                    // 用"每次都强制"而非"首轮强制"：router 是单次调用，
                    // 而 summarizer 用过工具后主图历史里就有 ToolResponseMessage，会被误判成非首轮
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
                if (item instanceof String name && EXPERT_AGENTS.contains(name)) {
                    names.add(name);
                }
            }
            return names;
        }
        return List.of();
    }

    /** 条件边：只读 state 里的结构化结果，读不到就保守收尾（绝不悬空）。 */
    static String nextFromState(MessagesState<Message> state) {
        return state.<String>value(NEXT_KEY).filter(GOTO_DISPATCH::equals).isPresent()
                ? GOTO_DISPATCH : GOTO_SUMMARIZE;
    }

    /**
     * 专家节点：没轮到自己就零成本返回，轮到了才真跑并推进度事件。
     *
     * @param preload 可空。非空则先把数据取好随消息喂进去，不指望模型自己调工具——
     *                无参工具（如 news_search）用这种方式才能保证数据一定到位
     */
    private void addExpertNode(StateGraph<MessagesState<Message>> graph, String name,
                               CompiledGraph<MessagesState<Message>> expert, Supplier<String> preload) {
        NodeActionWithConfig<MessagesState<Message>> action = (state, config) -> {
            if (!dispatched(state, name)) {
                return Map.of();
            }
            progress(config, new ExpertProgress(name, ExpertProgress.START, null));
            try {
                List<Message> input = new ArrayList<>(state.messages());
                // 包装文案保持中性：怎么用这份数据（独占还是与搜索合并）由各专家的 instruction 定
                if (preload != null) {
                    input.add(new UserMessage("【以下是系统预取的原始数据】\n" + preload.get()));
                }
                Message reply = expert
                        .invoke(Map.of("messages", input), subConfig(config, name))
                        .flatMap(MessagesState::lastMessage)
                        .orElse(null);
                String text = reply == null ? "" : reply.getText();
                progress(config, new ExpertProgress(name, ExpertProgress.DONE, text));
                return reply == null ? Map.of() : Map.of("messages", reply);
            } catch (Exception e) {
                // 单个专家失败不该拖垮整轮：把失败作为一条消息交回，supervisor 自行判断要不要绕开
                log.warn("[Workbench] 专家 {} 执行失败", name, e);
                progress(config, new ExpertProgress(name, ExpertProgress.ERROR, e.getMessage()));
                return Map.of("messages", new AssistantMessage("[" + name + " 暂时不可用：" + e.getMessage() + "]"));
            }
        };
        try {
            graph.addNode(name, node_async(action));
        } catch (Exception e) {
            throw new IllegalStateException("专家节点注册失败: " + name, e);
        }
    }

    /** 子图独立 threadId，避免专家的中间消息污染主会话的 checkpoint。 */
    private static RunnableConfig subConfig(RunnableConfig config, String name) {
        return RunnableConfig.builder(config)
                .threadId(config.threadId().map(id -> id + "_" + name).orElse(name))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static void progress(RunnableConfig config, ExpertProgress event) {
        config.metadata(PROGRESS_SINK_KEY)
                .filter(Consumer.class::isInstance)
                .ifPresent(sink -> ((Consumer<ExpertProgress>) sink).accept(event));
    }

    private static boolean dispatched(MessagesState<Message> state, String name) {
        return state.<List<String>>value(DISPATCH_KEY).orElse(List.of()).contains(name);
    }

}

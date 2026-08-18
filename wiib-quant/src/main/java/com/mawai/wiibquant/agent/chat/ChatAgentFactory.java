package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.agent.llm.ChatEndpoints;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.llm.ConversationSummarizer;
import com.mawai.wiibquant.agent.llm.MessagesSchema;
import com.mawai.wiibquant.agent.llm.ModelCallLimiter;
import com.mawai.wiibquant.agent.llm.ResilientChatService;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import com.mawai.wiibquant.agent.trader.TraderChatService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.hook.EdgeHook;
import org.bsc.langgraph4j.hook.NodeHook;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.AppenderChannel;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 对话链路的叶子 agent 工厂：按用户的 BYOK 配置建出三个专家（market/news/trader）和一个汇总 agent，
 * 每个都是独立编译的 ReactAgent。
 * <p>
 * <b>这里只管"造"，不管"怎么用"</b>：派谁、派几轮、结果怎么拼、历史怎么存，全在
 * {@link ChatTurnRunner} 的平铺 Java 循环里。曾经的父 StateGraph 编排已退役——
 * 图带来的全部东西（条件边、并行 fan-in、回环）在这个链路上都能用几十行普通代码写清楚，
 * 而图额外附赠了一堆代价：hook 内联丢失、迭代硬顶算账、并行分支拿不到子流。
 * <p>
 * 模型是建叶子时绑死的（工具方法体里拿不到用户身份，{@code ChatService.execute} 的签名里
 * 没有 RunnableConfig），所以叶子按配置指纹缓存，见 {@link #leavesFor}。
 */
@Slf4j
@Component
public class ChatAgentFactory {

    public static final String MARKET_AGENT = "market_agent";
    public static final String NEWS_AGENT = "news_agent";
    public static final String TRADER_AGENT = "trader_agent";
    public static final Set<String> EXPERT_AGENTS = Set.of(MARKET_AGENT, NEWS_AGENT, TRADER_AGENT);

    /**
     * 一个专家叶子。
     *
     * @param preload 可空。非空则每次执行前先把数据取好、随消息喂进去——无参工具（news_search）
     *                挂成 function tool 的话模型未必调，预取才 100% 保证数据到位
     */
    public record Expert(CompiledGraph<MessagesState<Message>> graph, Supplier<String> preload) {
    }

    /**
     * 一份用户配置对应的全套叶子。
     *
     * @param light 浅模型本体：{@link ChatTurnRunner} 的路由问答是一次性的结构化调用，
     *              没有 ReAct 循环也没有工具执行，用不上包一层 agent
     */
    public record Leaves(ChatModel light, Map<String, Expert> experts,
                         CompiledGraph<MessagesState<Message>> summarizer) {
    }

    private final ChatModelFactory chatModelFactory;
    private final MarketToolkit marketToolkit;
    private final NewsToolkit newsToolkit;
    private final DeepAnalysisService deepAnalysisService;
    /** 对话轨读写 trader 的唯一入口；两条 agent 链路只经它与 DB 打交道，从不互相对话 */
    private final TraderChatService traderChatService;
    private final WorkbenchRunRegistry runRegistry;
    private final ApprovalRegistry approvalRegistry;
    /** 叶子与 {@link ChatContextStore} 共用同一个：会话历史存进去读出来要靠它，两边不一致就写得进读不出 */
    private final StateSerializer<MessagesState<Message>> stateSerializer;
    private final int runModelCallLimit;
    private final int summarizeThresholdTokens;
    private final int summarizeKeepMessages;
    /** 补充源在回答里的标签，如 [X]；源名可配，见构造参数 supplementSource 的说明 */
    private final String supplementTag;
    /** 两边都有的事件合并后的标签，如 [BlockBeats+X] */
    private final String mergedTag;

    /**
     * 叶子缓存上限：与 {@link ChatModelFactory#MAX_ENTRIES} 同口径（一个配置指纹一份），
     * 实际就是"能同时缓存几个活跃用户的叶子"。超了 LRU 抖动，被淘汰的人下次发消息重建。
     * 对话已对全体用户开放，32 是拍的数，调它看的是轮流来聊的人数——理由详见 MAX_ENTRIES。
     */
    private static final int MAX_LEAVES = 32;

    /** 按配置指纹缓存的叶子。LRU 与并发口径同 {@link ChatModelFactory#modelsFor}，建叶子不在锁里做 */
    private final Map<String, Leaves> cache =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Leaves> eldest) {
                            return size() > MAX_LEAVES;
                        }
                    });

    /**
     * @param supplementSource 补充源名。BlockBeats 之外那一路是 summarizer 模型自带的联网搜索捞的，
     *                         搜到的是哪个平台随模型走（当前 grok 出的是 X），换模型就未必还是它。
     *                         所以提示词里一律只说"联网搜索"不点名平台，只有输出标签用这个名字——
     *                         换源改配置一处，提示词不用动
     */
    public ChatAgentFactory(ChatModelFactory chatModelFactory,
                            MarketToolkit marketToolkit,
                            NewsToolkit newsToolkit,
                            DeepAnalysisService deepAnalysisService,
                            TraderChatService traderChatService,
                            WorkbenchRunRegistry runRegistry,
                            ApprovalRegistry approvalRegistry,
                            StateSerializer<MessagesState<Message>> stateSerializer,
                            @Value("${quant.workbench.run-model-call-limit:8}") int runModelCallLimit,
                            @Value("${quant.workbench.summarize-threshold-tokens:32000}") int summarizeThresholdTokens,
                            @Value("${quant.workbench.summarize-keep-messages:6}") int summarizeKeepMessages,
                            @Value("${quant.workbench.news-supplement-source:}") String supplementSource) {
        this.chatModelFactory = chatModelFactory;
        this.marketToolkit = marketToolkit;
        this.newsToolkit = newsToolkit;
        this.deepAnalysisService = deepAnalysisService;
        this.traderChatService = traderChatService;
        this.runRegistry = runRegistry;
        this.approvalRegistry = approvalRegistry;
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

    /**
     * 取这份配置的叶子，按指纹缓存：配置一变指纹就变、自然拿到新叶子，不需要任何显式失效。
     * <p>
     * <b>为什么要缓存</b>：建一个 ReactAgent 要反射扫工具类、装配 ChatService 与序列化器，
     * 几十到几百毫秒；这条路在请求线程上，每请求重建等于每句话先卡半秒。
     * <p>
     * <b>先查后建，不用 computeIfAbsent</b>：它会在整个 mapping 函数执行期间攥着互斥锁，
     * 于是任何一个用户首次建叶子期间，<b>其余所有用户的 /chat 请求全堵在这把锁上</b>。
     */
    public Leaves leavesFor(ChatEndpoints eps) {
        String fp = ChatModelFactory.fingerprint(eps);
        Leaves hit = cache.get(fp);
        if (hit != null) {
            return hit;
        }
        Leaves built;
        try {
            built = build(eps);                     // 锁外建，慢也只慢自己
        } catch (Exception e) {
            // build 抛检查异常；包成运行时，让上层当"这份配置建不出模型"处理
            throw new IllegalStateException("对话叶子构建失败", e);
        }
        // 并发下可能有人先放好了，用先到的那份：叶子无会话状态，多建一份只是一次 GC
        Leaves prev = cache.putIfAbsent(fp, built);
        if (prev != null) {
            return prev;
        }
        log.info("对话工作台叶子已构建 model={} 缓存数={}", eps.deep().getModel(), cache.size());
        return built;
    }

    // 形参不叫 config：这个包里 config 一律指 RunnableConfig，重名读起来会误导
    private Leaves build(ChatEndpoints eps) throws Exception {
        ChatModelFactory.Models models = chatModelFactory.modelsFor(eps);
        ChatModel deep = models.deep();
        ChatModel light = models.light();

        // LinkedHashMap 保序：派发顺序、结论拼进历史的顺序都跟着它，market 在前 news 在后
        Map<String, Expert> experts = new LinkedHashMap<>();
        // market 的工具要按问题选 symbol，只能交给模型现取，所以没有 preload
        experts.put(MARKET_AGENT, new Expert(expertGraph(light, marketToolkit, "required", """
                你是市场状态专家。用工具获取真实数据回答，所有结论必须引用工具返回的具体数字；
                数据不可用(available=false)时如实告知，绝不编造。
                只回答行情/持仓/清算/期权，新闻等其他领域即使知道也不要写，有对应专家负责。
                回答精炼中文。"""), null));
        // 新闻专家只管 BlockBeats：数据走"预取"（news_search 无参数，预取 100% 保证快讯在
        // 上下文里，不依赖模型行为；不挂 function tool——实测挂着它 auto 下还会再调一次纯浪费）。
        // 联网补的那一路不归它：模型的服务端搜索关不掉（grok 实测所有请求参数/换模型均无效），
        // 与其在两处禁，不如把搜索正式划给 summarizer 当职责、这里明令禁用——预取喂饱后它没有搜索动机，禁得住
        experts.put(NEWS_AGENT, new Expert(expertGraph(light, null, null, """
                你是加密新闻专家。对话里已附上 BlockBeats 快讯原文（约20条），只基于它输出清单：
                每条格式：[BlockBeats] + 事件一句话 + 可能影响一句话，按市场影响力从高到低排序，
                同一事件的多条快讯合并为一条，除合并外不要删减。
                严禁把你联网搜索到的任何内容写进回答——这部分由上游汇总者负责。
                不评价真伪、不给投资建议。原文为空时如实说"暂无快讯"，绝不编造。输出精炼中文。"""),
                newsToolkit::newsSearch));
        // trader 专家只读这个用户自己的 trader：userId 在这里烤进工具实例，不做成模型可填的参数
        //（做成参数就等于让模型自己说要看谁的档案）。无预取——四个工具各答一类问题，取哪个得看问题
        experts.put(TRADER_AGENT, new Expert(expertGraph(light,
                new TraderQueryToolkit(traderChatService, eps.userId()), "required", """
                你是用户那个 AI 交易员的档案员。用工具读取真实数据回答，所有结论只能引用工具返回的内容；
                工具返回 hasTrader=false 就直接说"你还没有创建 AI Trader"，绝不编造持仓、决策或复盘内容。
                被问"为什么做那笔交易"时，把对应决策的 reasoning 原文摘出来说，不要自己另编一套理由。
                只回答这个 trader 自身的状态/持仓/决策/计划/复盘笔记，大盘行情与新闻有别的专家负责。
                回答精炼中文。"""), null));

        return new Leaves(light, experts, summarizerLeaf(deep, light, eps.userId()));
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
     * 上限值就是迭代账里的 <b>L</b>，直接决定 summarizer 叶子的硬顶要开多大，
     * 改它要一起核对 {@link #summarizerLeaf} 结尾那笔账。生产取 8。
     * <p>
     * <b>同一个配置也喂着专家叶子，但两边的账不一样，别当成有一处写错了</b>：
     * {@link #expertGraph} 没有 {@code .streaming(true)}，非流式模型节点只吃 1 格，
     * 一轮 {@code 2} 格、共 {@code 2L+3} → L=8 实测吃 19 格，框架默认 25 够用。
     * <p>
     * 还要注意这<b>一个</b>配置项管的是"每个 agent 各自的上限"而不是"整轮总量"：
     * summarizer 和每个带工具的专家各跑各的 state，{@link ModelCallLimiter#CALL_COUNT_KEY}
     * 计数互不相通。所以一轮对话的模型调用是各家相加（summarizer ≤8、每个带工具的专家各 ≤8，
     * 再加上路由每轮一次），不是 8 次封顶。想收总量得另立机制，不是把这个数调小。
     * <p>
     * <b>"一轮"这个作用域现在是天生的</b>：叶子全部无 checkpointSaver，每次 invoke/stream
     * 都从 schema 起算，计数自然每轮从 0 开始，不需要任何显式清零。
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
        ReactAgent.Builder<MessagesState<Message>> builder = ReactAgent.builder()
                .chatModel(model)
                .stateSerializer(stateSerializer)
                .schema(MessagesSchema.SCHEMA)
                .defaultSystem(instruction);
        if (toolkit != null) {
            builder.toolsFromObject(toolkit);
            // 有工具才有 ReAct 循环，没保险丝就一路顶到框架 25 次迭代硬顶抛异常；而 market 的工具
            // 每调一次就打一次真实上游，是行情配额账里唯一没封顶的一项
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
     * 派谁、还要不要再派，全归 {@link ChatTurnRunner} 的显式循环管，这里一个字都不提——
     * 角色单一，模型不会再纠结"该作答还是该派发"（那正是之前无限循环的病根）。
     * <p>
     * 三个 hook 就挂在框架自己的挂载点上：叶子是独立 {@code compile()} 的，
     * {@code addCallModelHook} 落到模型节点、{@code addExecuteToolsHook} 落到工具边，
     * 都真执行（从前挂不上是因为这张图会被 {@code addNode(id, StateGraph)} 内联进父图，
     * 内联只搬 nodes/edges）。工具边那两个按 {@link #summarizerToolHooks} 的列表顺序注册，
     * 末尾的保险丝因此在最外层。
     *
     * @param light  压缩用浅模型：摘要是简单活，用深模型纯烧钱
     * @param userId 动作类工具烤死的归属；查询归专家，动手归汇总者，理由见 {@link TraderActionToolkit}
     */
    private CompiledGraph<MessagesState<Message>> summarizerLeaf(ChatModel deep, ChatModel light,
                                                                 long userId) throws Exception {
        // 工具的模型在这一层绑死："当前用的是谁的 key"只有这里知道
        ReactAgent.Builder<MessagesState<Message>> builder = ReactAgent.builder()
                .chatModel(deep)
                .stateSerializer(stateSerializer)
                .schema(MessagesSchema.SCHEMA)
                .streaming(true) // 答案要逐字推给前端
                .toolsFromObject(new DeepAnalysisToolkit(deep, deepAnalysisService, runRegistry))
                // 可以多次调用：两套工具分别是"研判"与"对 trader 动手"，合成一个类只会让职责糊掉
                .toolsFromObject(new TraderActionToolkit(traderChatService, userId))
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
                        3. 被问涨跌方向时直接给出你的方向判断（偏多/偏空/震荡）与大致把握，鼓励表态：
                           判断必须落在专家给的实测数据上（资金费/持仓/清算等），并附失效条件与仓位/止损等风控参考
                        4. 信号确实矛盾、给不出任何倾向时才说"看不清"，并点出关键分歧在哪——这是例外，不是回避表态的出口
                        5. 仅当用户明确说出"深度研判/全面分析"这类字眼时 → 调 run_deep_analysis 工具（昂贵，需用户确认：
                           返回 PENDING_APPROVAL 时告知用户确认卡片已弹出，等确认后你会被再次唤起执行）；
                           "怎么看走势"这类普通提问不要调它、也不要主动推销，直接按专家数据作答
                        6. 对用户自己 AI 交易员动手的三个工具，同样只在用户明确要求时才调，绝不主动推销：
                           · wake_trader（立刻唤醒它做一次决策，可能真开/平仓）与 review_trader_now（立刻复盘）
                             都昂贵、需用户确认，PENDING_APPROVAL 的处理同上
                           · leave_note_to_trader（给它留一句话，下次唤醒看一次就焚毁）便宜，不需要确认
                           查询类问题（它现在怎么样/持了什么仓/那笔为什么开）不归你，trader_agent 专家已经取回数据了

                        输出精炼中文。""".formatted(supplementTag, mergedTag))
                .addCallModelHook(wrapBefore(
                        new ConversationSummarizer(light, summarizeThresholdTokens, summarizeKeepMessages)));
        for (EdgeHook.WrapCall<MessagesState<Message>> hook :
                summarizerToolHooks(approvalRegistry, runModelCallLimit)) {
            builder.addExecuteToolsHook(hook);
        }
        return builder.build(ResilientChatService.builder()
                        // 不给兜底模型：BYOK 只有一个端点，切到同端点的另一个模型没意义
                        //（端点挂了两个一起挂）。ResilientChatService 支持兜底为空，退避重试照旧
                        .model(deep)
                        .maxAttempts(3).initialDelay(500).maxDelay(4000)
                        .asFactory())
                // 框架默认硬顶 25 不够：流式模型节点一轮吃 2 格（交回 token 生成器 + 合并它的
                // resultValue）+ 工具边 1 格，加上 START/END/收尾三格，最小可跑值就是 3L+3——
                // L=8 实测 27 恰好跑通、26 当场抛 "Maximum number of iterations (26) reached!"。
                // 抬到 3L+8 留一轮多的余量。真正管事的闸门是 ModelCallLimiter（就是那个 L），
                // 硬顶只兜"环没收住"这一种情况——它抛在结果交出去之前，一抛用户连截断回答都拿不到。
                // token 帧不吃格（它们由 WithEmbed 消费，不走图的 next()），所以回答多长都不影响这本账
                .compile(CompileConfig.builder().recursionLimit(3 * runModelCallLimit + 8).build());
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
                    // 用叶子那份 schema，不是框架默认的：两边不一致的话这一步的合并语义
                    // 与图内的追加语义就对不上（默认那份会静默丢掉内容重复的消息）
                    Map<String, Object> merged = AgentState.updateState(state, update, MessagesSchema.SCHEMA);
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

}

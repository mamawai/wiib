package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.AgentGraphs;
import com.mawai.wiibagent.llm.CancelSignal;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.llm.ConversationSummarizer;
import com.mawai.wiibagent.llm.MessagesSchema;
import com.mawai.wiibagent.llm.ModelCallLimiter;
import com.mawai.wiibagent.llm.ResilientChatService;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.trader.TraderChatService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.hook.EdgeHook;
import org.bsc.langgraph4j.hook.NodeHook;
import org.bsc.langgraph4j.prebuilt.MessagesState;
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
 * {@link ChatTurnRunner} 的平铺 Java 循环里。
 * <p>
 * 模型是建叶子时绑死的（工具方法体里拿不到用户身份，{@code ChatService.execute} 的签名里
 * 没有 RunnableConfig），所以叶子按配置指纹缓存，见 {@link #leavesFor}。
 * <p>
 * 语言与模型一样是建叶子时烤死的：系统提示词与工具描述按 {@link AgentLang} 取好写进图里，
 * 所以语言也在缓存键里，见 {@link #leafKey}。
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
     * @param modelLabel 这份配置的对外名字（端点名 · 模型名），随每条答案落库给前端显示
     * @param deep       深模型（summarizer 作答与深研判走它）。透出来是为了取用量——它本身就是本轮的账本
     * @param light      浅模型本体：{@link ChatTurnRunner} 的路由问答是一次性的结构化调用，
     *                   没有 ReAct 循环也没有工具执行，用不上包一层 agent
     * @param lang       这套叶子按哪门语言建的。它在缓存键里（见 {@link #leafKey}），拿到的叶子
     *                   必与请求语言一致；带出来供编排层（过程文案、路由工具描述）直接用
     */
    public record Leaves(String modelLabel, UsageTrackingChatModel deep, UsageTrackingChatModel light,
                         Map<String, Expert> experts, CompiledGraph<MessagesState<Message>> summarizer,
                         AgentLang lang) {

        /** 轮开始清零，划出本轮账本的起点 */
        public void resetUsage() {
            deep.reset();
            light.reset();      // 没单独绑轻模型时与 deep 是同一个实例，重复清零无害
        }

        /** 本轮累计：深浅两条端点合账。同一个实例时只能取一遍，否则每笔都算两次 */
        public UsageTrackingChatModel.UsageSnapshot usageSnapshot() {
            return light == deep ? deep.snapshot() : deep.snapshot().merge(light.snapshot());
        }

        /** 账本被中断丢下的在途流写脏了，这一轮的数不能报 */
        public boolean usageUntrusted() {
            return deep.untrusted() || light.untrusted();
        }
    }

    private final ChatModelFactory chatModelFactory;
    private final MarketToolkit marketToolkit;
    private final NewsToolkit newsToolkit;
    private final DeepAnalysisService deepAnalysisService;
    /** 行为分析的准入层（缓存/负缓存/并发闸门都在它那儿），工具只经它跑 */
    private final BehaviorAnalysisService behaviorAnalysisService;
    /** 对话轨读写 trader 的唯一入口；两条 agent 链路只经它与 DB 打交道，从不互相对话 */
    private final TraderChatService traderChatService;
    private final WorkbenchRunRegistry runRegistry;
    private final ApprovalRegistry approvalRegistry;
    private final PromptCatalog prompts;
    private final LocalizedToolCallbacks localizedTools;
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
     * @param supplementSource 补充源名。BlockBeats 之外那一路是 summarizer 经服务端 web_search 工具搜的
     *                         （端点勾选开启，见 {@link #build} 的 webSearch），搜到什么平台随上游走。
     *                         所以提示词里一律只说"联网搜索"不点名平台，只有输出标签用这个名字——
     *                         换源改配置一处，提示词不用动；留空标 [Web]
     */
    public ChatAgentFactory(ChatModelFactory chatModelFactory,
                            MarketToolkit marketToolkit,
                            NewsToolkit newsToolkit,
                            DeepAnalysisService deepAnalysisService,
                            BehaviorAnalysisService behaviorAnalysisService,
                            TraderChatService traderChatService,
                            WorkbenchRunRegistry runRegistry,
                            ApprovalRegistry approvalRegistry,
                            PromptCatalog prompts,
                            LocalizedToolCallbacks localizedTools,
                            @Value("${agent.workbench.run-model-call-limit:8}") int runModelCallLimit,
                            @Value("${agent.workbench.summarize-threshold-tokens:32000}") int summarizeThresholdTokens,
                            @Value("${agent.workbench.summarize-keep-messages:6}") int summarizeKeepMessages,
                            @Value("${agent.workbench.news-supplement-source:}") String supplementSource) {
        this.chatModelFactory = chatModelFactory;
        this.marketToolkit = marketToolkit;
        this.newsToolkit = newsToolkit;
        this.deepAnalysisService = deepAnalysisService;
        this.behaviorAnalysisService = behaviorAnalysisService;
        this.traderChatService = traderChatService;
        this.runRegistry = runRegistry;
        this.approvalRegistry = approvalRegistry;
        this.prompts = prompts;
        this.localizedTools = localizedTools;
        this.runModelCallLimit = runModelCallLimit;
        this.summarizeThresholdTokens = summarizeThresholdTokens;
        this.summarizeKeepMessages = summarizeKeepMessages;
        // 不配就用中性的 Web：标签总得有个名字，但代码里不该替某个平台站队
        String source = supplementSource == null || supplementSource.isBlank() ? "Web" : supplementSource.trim();
        this.supplementTag = "[" + source + "]";
        this.mergedTag = "[BlockBeats+" + source + "]";
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
    public Leaves leavesFor(ChatEndpoints eps, AgentLang lang) {
        String fp = leafKey(eps, lang);
        Leaves hit = cache.get(fp);
        if (hit != null) {
            return hit;
        }
        Leaves built;
        try {
            // 锁外建好图
            built = build(eps, lang);
        } catch (Exception e) {
            throw new IllegalStateException("对话叶子构建失败", e);
        }
        // 并发下可能有人先放好了，用先到的那份：叶子无会话状态，多建一份只是一次 GC
        Leaves prev = cache.putIfAbsent(fp, built);
        if (prev != null) {
            return prev;
        }
        log.info("对话工作台叶子已构建 model={} lang={} 缓存数={}", eps.deep().getModel(), lang.code(), cache.size());
        return built;
    }

    /**
     * 叶子的缓存键 = 模型配置指纹 + 语言。语言变则整套系统提示词与工具描述变，叶子须重建。
     * <p>
     * 语言只加在这一层，不进 {@link ChatModelFactory#fingerprint}：模型实例与语言无关，
     * 切语言不重建 SDK 客户端与连接池。
     */
    static String leafKey(ChatEndpoints eps, AgentLang lang) {
        // 指纹是十六进制串，用冒号接语言码不会与它的任何取值相撞
        return ChatModelFactory.fingerprint(eps) + ':' + lang.code();
    }

    private Leaves build(ChatEndpoints eps, AgentLang lang) throws Exception {
        ChatModelFactory.Models models = chatModelFactory.modelsFor(eps);
        // 是否允许网络搜索
        boolean webSearch = AiProtocols.supportsServerSearch(eps.deep().getApiProtocol())
                && Boolean.TRUE.equals(eps.deep().getWebSearch());
        // 用量装饰器进行包装
        UsageTrackingChatModel deep = new UsageTrackingChatModel(models.deep());
        // 没单独绑轻模型时工厂给的是同一个实例，装饰器也得共用一个，否则同一次调用记两遍账
        UsageTrackingChatModel light = models.light() == models.deep() ? deep : new UsageTrackingChatModel(models.light());
        // LinkedHashMap 保序：派发顺序、结论拼进历史的顺序都跟着它，market 在前 news 在后
        Map<String, Expert> experts = new LinkedHashMap<>();
        // market 的工具要按问题选 symbol，只能交给模型现取，所以没有 preload
        experts.put(MARKET_AGENT, new Expert(expertGraph(lang, light, marketToolkit, "required",
                prompts.get(lang, "chat.expert.market")), null));
        // 新闻只预取 BlockBeats（news_search 入参语言，不挂 tool），这里不进行联网搜索
        experts.put(NEWS_AGENT, new Expert(expertGraph(lang, light, null, null,
                prompts.get(lang, "chat.expert.news")), () -> newsToolkit.newsSearch(lang)));
        // trader 专家只读这个用户自己的 trader
        experts.put(TRADER_AGENT, new Expert(expertGraph(lang, light,
                new TraderQueryToolkit(traderChatService, eps.userId(), lang), "required",
                prompts.get(lang, "chat.expert.trader")), null));

        return new Leaves(modelLabel(eps.deep()), deep, light, experts,
                summarizerLeaf(deep, light, eps.userId(), lang, webSearch), lang);
    }

    /**
     * summarizer 系统提示词按端点搜索能力拼装：新闻条款二选一（{@code chat.newsRule.search} 承诺联网补充 /
     * {@code chat.newsRule.noSearch} 如实说没有检索能力），其余原则两版共有。
     * 提示词不许承诺端点给不了的能力——文案跟着能力走，能力跟着端点走。
     */
    // 包私有非 private：ChatAgentFactoryTest 要直接断言两版文案
    String summarizerInstruction(AgentLang lang, boolean webSearch) {
        String newsRule = prompts.get(lang, webSearch ? "chat.newsRule.search" : "chat.newsRule.noSearch",
                Map.of("supplementTag", supplementTag, "mergedTag", mergedTag));
        return prompts.get(lang, "chat.summarizer", Map.of("newsRule", newsRule));
    }

    /** 端点名 · 模型名：站内展示模型的统一口径（见 LlmEndpointSelect / ReplayPanel）；没起名就只报模型 */
    private static String modelLabel(UserLlmEndpoint endpoint) {
        String name = endpoint.getName();
        return name == null || name.isBlank() ? endpoint.getModel() : name + " · " + endpoint.getModel();
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
            ApprovalRegistry registry, PromptCatalog prompts, AgentLang lang, int limit) {
        return List.of(new ApprovalGate(registry, prompts, lang),
                new ModelCallLimiter(limit, prompts.get(lang, "llm.callLimit.notExecuted")));  // 内层 → 外层
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
    CompiledGraph<MessagesState<Message>> expertGraph(AgentLang lang, ChatModel model, Object toolkit,
                                                      String forceFirstToolChoice, String instruction) throws Exception {
        ReactAgent.Builder<MessagesState<Message>> builder = AgentGraphs.reactAgent(model, instruction);

        if (toolkit != null) {
            builder.tools(localizedTools.of(lang, toolkit)); // 按照lang设置tool的description语言
            builder.addExecuteToolsHook(new ModelCallLimiter(runModelCallLimit,
                    prompts.get(lang, "llm.callLimit.notExecuted"))); // 设置模型调用限制
        }

        return builder.build(
                ResilientChatService.builder()
                        .model(model)
                        .forceFirstToolChoice(forceFirstToolChoice)
                        .asFactory()
                )
                .compile();
    }

    /**
     * 汇总 agent：深模型 + 深研判工具，只管把专家数据写成最终回答。
     * <p>
     * 派谁、还要不要再派，全归 {@link ChatTurnRunner} 的显式循环管，这里一个字都不提——
     * 角色单一，模型不会再纠结"该作答还是该派发"（那正是之前无限循环的病根）。
     * <p>
     * 四个 hook 挂在框架自己的挂载点上：叶子是独立 {@code compile()} 的，
     * {@code addCallModelHook} 落到模型节点、{@code addExecuteToolsHook} 落到工具边。
     * 模型节点两个：{@link CancelSignal} 先注册在最内层（它要在 apply 的同一调用栈里把中断信号接到答案流上），
     * 压缩在它外面。工具边那两个按 {@link #summarizerToolHooks} 的列表顺序注册，末尾的保险丝因此在最外层。
     *
     * @param light  压缩用浅模型：摘要是简单活，用深模型纯烧钱
     * @param userId 动作类工具烤死的归属；查询归专家，动手归汇总者，理由见 {@link TraderActionToolkit}
     * @param webSearch 端点声明了服务端搜索：提示词用承诺联网的那版，且每次调用捎搜索许可。
     *                  许可只在这一个叶子发——专家与 trader 链路的数据源必须可控，物理拿不到搜索
     */
    private CompiledGraph<MessagesState<Message>> summarizerLeaf(ChatModel deep, ChatModel light,
                                                                 long userId, AgentLang lang,
                                                                 boolean webSearch) throws Exception {
        // 工具的模型在这一层绑死："当前用的是谁的 key"只有这里知道
        ReactAgent.Builder<MessagesState<Message>> builder = AgentGraphs.reactAgent(deep, summarizerInstruction(lang, webSearch))
                .streaming(true) // 答案要逐字推给前端
                .tools(localizedTools.of(lang,
                        // 三套工具 研判/trader/行为分析
                        new DeepAnalysisToolkit(deep, deepAnalysisService, runRegistry, prompts, lang),
                        new TraderActionToolkit(runRegistry, userId, prompts, lang),
                        new BehaviorToolkit(deep, behaviorAnalysisService, runRegistry, userId, lang)))
                .addCallModelHook(CancelSignal.hook())   // 最内层：用户点停止时掐断在途答案流
                .addCallModelHook(wrapBefore(
                        new ConversationSummarizer(light, summarizeThresholdTokens, summarizeKeepMessages, prompts, lang)
                ));

        for (EdgeHook.WrapCall<MessagesState<Message>> hook : summarizerToolHooks(approvalRegistry, prompts, lang, runModelCallLimit)) {
            builder.addExecuteToolsHook(hook);
        }

        return builder.build(
                ResilientChatService.builder()
                        .model(deep)
                        .webSearch(webSearch)
                        .maxAttempts(3).initialDelay(500).maxDelay(4000)
                        .asFactory()
                )
                // 框架默认 25 不够 修改为 3L+8
                .compile(CompileConfig.builder().recursionLimit(3 * runModelCallLimit + 8).build());
    }

    /**
     * 把 BeforeCall 语义的钩子接到 ReactAgent 只暴露的 WrapCall 上：
     * 先跑钩子拿状态更新，合并进 state 后再执行真正的模型调用。
     */
    private NodeHook.WrapCall<MessagesState<Message>> wrapBefore(NodeHook.BeforeCall<MessagesState<Message>> before) {
        return (nodeId, state, config, action)
                -> before.applyBefore(nodeId, state, config).thenCompose(update -> {
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
     *       继续消费到底好落历史的；用户中断走的是 {@link CancelSignal} 直接掐模型流，也不经这里</li>
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

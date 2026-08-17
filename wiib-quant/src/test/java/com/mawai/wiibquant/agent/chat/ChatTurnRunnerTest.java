package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.agent.llm.ChatEndpoints;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import com.mawai.wiibquant.agent.trader.TraderChatService;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一轮对话的编排。跑的是<b>生产叶子</b>（真 {@link ChatAgentFactory} + 打桩的 ChatModel），
 * 不是手搭的假 agent——要钉的正是"runner 与真叶子接在一起"这件事。
 * <p>
 * 三路模型请求共用同一个浅模型 mock（路由 / market 专家 / news 专家），靠系统提示词首句分辨。
 */
class ChatTurnRunnerTest {

    private static final String SESSION = "wb-1-runner";
    /** 阈值给足 = 这几跑都不碰历史压缩，那件事归 {@link SummarizerLeafTest} */
    private static final int NO_COMPRESSION = 999_999;
    /** 生产口径的调用上限 */
    private static final int LIMIT = 8;

    private static final String ROUTER_MARK = "你是研判工作台的调度器";
    private static final String MARKET_MARK = "你是市场状态专家";

    private final ChatModel deep = mock(ChatModel.class);
    private final ChatModel light = mock(ChatModel.class);
    private final ApprovalRegistry registry = new ApprovalRegistry();
    private final ChatContextStore contextStore = mock(ChatContextStore.class);
    private final NewsToolkit newsToolkit = mock(NewsToolkit.class);
    private final TraderChatService traderChatService = mock(TraderChatService.class);

    /** 专家跑在虚拟线程上，事件从别的线程进来 */
    private final List<ChatTurnRunner.ExpertProgress> progress = new CopyOnWriteArrayList<>();
    /** 各路模型收到的 Prompt：上下文到底喂进去了什么，只有它说得清 */
    private final List<Prompt> summarizerPrompts = new CopyOnWriteArrayList<>();
    private final List<Prompt> expertPrompts = new CopyOnWriteArrayList<>();
    private final StringBuilder answer = new StringBuilder();

    private static ChatResponse responseOf(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static AssistantMessage toolCall(String name, String args) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", name, args))).build();
    }

    /** 路由的结构化回答 */
    private static ChatResponse route(String... next) {
        String json = Arrays.stream(next).map(n -> "\"" + n + "\"")
                .collect(Collectors.joining(",", "{\"next\":[", "]}"));
        return responseOf(toolCall("route", json));
    }

    /** 浅模型同时服务路由与两个专家，按系统提示词首句分流；专家侧的 Prompt 顺手记下来 */
    private void lightAnswers(Supplier<ChatResponse> router, Supplier<ChatResponse> market,
                              Supplier<ChatResponse> news) {
        when(light.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            String head = prompt.getInstructions().getFirst().getText();
            if (head.contains(ROUTER_MARK)) {
                return router.get();
            }
            expertPrompts.add(prompt);
            return head.contains(MARKET_MARK) ? market.get() : news.get();
        });
    }

    /** summarizer 一句话收尾，顺手记下它收到的 Prompt */
    private void summarizerAnswers(String text) {
        when(deep.stream(any(Prompt.class))).thenAnswer(inv -> {
            summarizerPrompts.add(inv.getArgument(0));
            return Flux.just(responseOf(new AssistantMessage(text)));
        });
    }

    private ChatAgentFactory.Leaves leaves() {
        // ChatService 建请求时无条件读 getOptions() 挂工具，null 会 NPE
        when(deep.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(light.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.modelsFor(any())).thenReturn(new ChatModelFactory.Models(deep, light));
        ChatEndpoints llmConfig = ChatTestEndpoints.eps(1L, "gpt-5");   // 叶子指纹含 userId（trader 工具按它认人）
        return new ChatAgentFactory(chatModelFactory, mock(MarketToolkit.class), newsToolkit,
                mock(DeepAnalysisService.class), traderChatService,
                mock(WorkbenchRunRegistry.class),
                registry, new SpringAIJacksonStateSerializer<>(MessagesState::new),
                LIMIT, NO_COMPRESSION, 6, "X")
                .leavesFor(llmConfig);
    }

    private void turn(String message) {
        new ChatTurnRunner(contextStore, registry)
                .run(leaves(), 1L, SESSION, message, answer::append, progress::add,
                        ChatTurnRunner.TurnYield.NONE);
    }

    /** 某个专家被真跑起来的次数（START 事件即"开始执行"） */
    private long starts(String agent) {
        return progress.stream()
                .filter(e -> agent.equals(e.agent()) && ChatTurnRunner.ExpertProgress.START.equals(e.phase()))
                .count();
    }

    private static String textOf(Prompt prompt) {
        return prompt.getInstructions().stream()
                .map(m -> m.getText() == null ? "" : m.getText())
                .collect(Collectors.joining("\n"));
    }

    private String summarizerInput() {
        return textOf(summarizerPrompts.getLast());
    }

    /** 落库终态拼成一段文本比对：专家结论带出处前缀，逐字相等的断言不适用 */
    private static String savedText(ArgumentCaptor<List<Message>> saved) {
        return saved.getValue().stream()
                .map(m -> m.getText() == null ? "" : m.getText())
                .collect(Collectors.joining("\n"));
    }

    /** summarizer 真正收到的最后一条消息（形态钉子看的就是它） */
    private Message summarizerLastInput() {
        return summarizerPrompts.getLast().getInstructions().getLast();
    }

    /** 只数路由那几次调用，别把专家的也算进来 */
    private static Prompt routerPrompt() {
        return argThat(prompt -> prompt != null && !prompt.getInstructions().isEmpty()
                && prompt.getInstructions().getFirst().getText().contains(ROUTER_MARK));
    }

    // ===== 结构化路由解析：只认 tool_call 参数，绝不解析消息文本 =====

    @Test
    void parsesExpertNamesFromRouteToolCall() {
        List<String> next = ChatTurnRunner.parseRouteCall(
                toolCall("route", "{\"next\":[\"news_agent\",\"market_agent\"]}"));

        assertThat(next).containsExactly("news_agent", "market_agent");
    }

    @Test
    void parsesFinishFromRouteToolCall() {
        assertThat(ChatTurnRunner.parseRouteCall(toolCall("route", "{\"next\":[\"FINISH\"]}")))
                .containsExactly(ChatTurnRunner.FINISH);
    }

    @Test
    void dropsUnknownAgentNames() {
        assertThat(ChatTurnRunner.parseRouteCall(
                toolCall("route", "{\"next\":[\"news_agent\",\"weather_agent\"]}")))
                .containsExactly("news_agent");
    }

    /** 新专家要被路由认得：不在 EXPERT_AGENTS 里就会被当成不认识的名字静默丢掉，永远派不出去 */
    @Test
    void parsesTraderAgentFromRouteToolCall() {
        assertThat(ChatTurnRunner.parseRouteCall(toolCall("route", "{\"next\":[\"trader_agent\"]}")))
                .containsExactly("trader_agent");
    }

    @Test
    void noToolCallMeansNoDispatch() {
        // 模型没调 route（理论上被 tool_choice=required 挡住，兜底也要安全）→ 空名单 → 转汇总
        assertThat(ChatTurnRunner.parseRouteCall(new AssistantMessage("我直接回答吧"))).isEmpty();
    }

    @Test
    void malformedArgumentsDoNotBlowUp() {
        assertThat(ChatTurnRunner.parseRouteCall(toolCall("route", "{\"next\":[]}"))).isEmpty();
    }

    // ===== 显式循环：什么时候还派、什么时候收口 =====

    /**
     * 同一专家取过的数不会变，重复派只会空转烧钱——死循环就是这么来的，靠代码收敛不指望模型自觉。
     * <p>
     * 去重同时是这个 {@code while(true)} 的<b>实际</b>终止条件：每轮至少吃掉一个专家名，
     * 名字用完循环必停。把 fresh 过滤删掉，router 永远想派同一个，这条会一直转下去。
     */
    @Test
    void 同一专家不会被派第二次() {
        lightAnswers(() -> route("market_agent"),
                () -> responseOf(new AssistantMessage("市场结论")),
                () -> responseOf(new AssistantMessage("新闻结论")));
        summarizerAnswers("这是答案");

        turn("看看行情");

        assertThat(starts("market_agent")).isEqualTo(1);
        // 路由被问了两次：第一次派出去、第二次答案被去重挡下转汇总
        verify(light, times(2)).call(routerPrompt());
        assertThat(answer.toString()).isEqualTo("这是答案");
    }

    /**
     * 两个专家一轮派完之后就再没有"新"专家了，循环必然收口——
     * 这条把去重的上界钉死：派发轮数不会超过专家个数。
     */
    @Test
    void 专家全派过之后转汇总() {
        lightAnswers(() -> route("market_agent", "news_agent"),
                () -> responseOf(new AssistantMessage("市场结论")),
                () -> responseOf(new AssistantMessage("新闻结论")));
        summarizerAnswers("这是答案");

        turn("行情和新闻都看看");

        assertThat(starts("market_agent")).isEqualTo(1);
        assertThat(starts("news_agent")).isEqualTo(1);
        verify(light, times(2)).call(routerPrompt());
        // 结论按<b>派发顺序</b>接进上下文，不是先跑完先接：先完成先接的话同一个问题两次跑出来的
        // 上下文不一样，行为不可复现
        assertThat(summarizerInput()).containsSubsequence("市场结论", "新闻结论");
    }

    /**
     * "我的 trader 怎么样"要派给 trader 专家，而且它得真调到只读工具。
     * <p>
     * 这条同时钉住三件容易各自坏掉的事：路由认得这个名字、生产叶子上真挂着
     * {@link TraderQueryToolkit}、以及 <b>userId 是建叶子时烤死的</b>——
     * 工具签名里没有用户参数，模型没有任何办法去读别人的 trader。
     */
    @Test
    void trader专家被派发且工具只读自己那份() {
        when(traderChatService.overview(1L)).thenReturn("{\"hasTrader\":true,\"status\":\"RUNNING\"}");
        AtomicInteger traderTurns = new AtomicInteger();
        when(light.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt prompt = inv.getArgument(0);
            if (prompt.getInstructions().getFirst().getText().contains(ROUTER_MARK)) {
                return route("trader_agent");
            }
            expertPrompts.add(prompt);
            // 先调工具再给结论：真走一遍 ReAct 的工具边，否则验不到工具挂没挂上
            return traderTurns.getAndIncrement() == 0
                    ? responseOf(toolCall("trader_overview", "{}"))
                    : responseOf(new AssistantMessage("你的 trader 正在运行"));
        });
        summarizerAnswers("这是答案");

        turn("我的 trader 怎么样");

        assertThat(starts("trader_agent")).isEqualTo(1);
        verify(traderChatService).overview(1L);
        // 专家结论进了上下文，汇总者才写得出答案
        assertThat(summarizerInput()).contains("你的 trader 正在运行");
    }

    /** 路由失败不该把整轮对话拖死：退化成"不派发直接作答"，用户至少拿得到回复 */
    @Test
    void 路由调用失败时直接汇总() {
        when(light.call(any(Prompt.class))).thenThrow(new RuntimeException("上游挂了"));
        summarizerAnswers("这是答案");

        turn("看看行情");

        assertThat(progress).isEmpty();          // 一个专家都没派
        assertThat(answer.toString()).isEqualTo("这是答案");
    }

    /**
     * 深研判确认后的续跑轮：存在未消费授权 → 直通汇总让 summarizer 重调工具。
     * 专家数据上一轮刚取过且深研判不消费它们，重派一遍纯浪费（真跑实证过会重派）。
     */
    @Test
    void 未消费授权时一次路由都不问() {
        registry.requestApproval(SESSION, "run_deep_analysis", "BTCUSDT", "贵操作");
        registry.approve(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());
        summarizerAnswers("这是答案");

        turn("已确认，请继续执行深度研判");

        verify(light, never()).call(any(Prompt.class));
        assertThat(answer.toString()).isEqualTo("这是答案");
    }

    // ===== 并行执行：一个专家挂了不许拖垮整轮 =====

    /**
     * 两个专家并行，一个抛异常：失败要变成一条<b>说人话的消息</b>接进上下文，
     * 另一个的结论照样在，汇总照样跑。
     * <p>
     * 失败文案过 {@code LlmErrorMessages}：原始 SDK 异常几百字符还可能带 key，
     * 而它会被喂回模型并随会话上下文落库。
     */
    @Test
    void 单个专家失败不拖垮整轮() {
        lightAnswers(() -> route("market_agent", "news_agent"),
                () -> {
                    throw new RuntimeException("HTTP 401 Unauthorized");
                },
                () -> responseOf(new AssistantMessage("新闻结论")));
        summarizerAnswers("这是答案");

        turn("行情和新闻都看看");

        assertThat(progress).anySatisfy(e -> {
            assertThat(e.agent()).isEqualTo("market_agent");
            assertThat(e.phase()).isEqualTo(ChatTurnRunner.ExpertProgress.ERROR);
            assertThat(e.text()).contains("API key");
        });
        // 失败也要进上下文：模型得知道"这一路没数据"，不然它会当作根本没问过
        assertThat(summarizerInput())
                .contains("【market_agent 本轮取数失败】")
                .contains("新闻结论");
        assertThat(answer.toString()).isEqualTo("这是答案");
    }

    // ===== 上下文：上一轮的历史进得来，这一轮的产出出得去 =====

    /**
     * 续聊上下文来自 {@link ChatContextStore}（checkpoint 已退役）：上一轮的对话要喂进
     * 这一轮的 summarizer，这一轮的专家结论与答案要落回去。
     * 两头任何一头断了，用户看到的都是"AI 失忆"。
     */
    @Test
    void 上一轮历史进得来本轮产出落得回去() {
        when(contextStore.load(SESSION)).thenReturn(List.of(
                new UserMessage("上轮问题"), new AssistantMessage("上轮回答")));
        lightAnswers(() -> route("market_agent"),
                () -> responseOf(new AssistantMessage("本轮市场结论")),
                () -> responseOf(new AssistantMessage("新闻结论")));
        summarizerAnswers("这是本轮答案");

        turn("这一轮问题");

        // 进：上轮内容原样在 summarizer 的输入里
        assertThat(summarizerInput()).contains("上轮问题").contains("上轮回答");
        // 出：终态整体覆盖回存储，含本轮专家结论与答案
        ArgumentCaptor<List<Message>> saved = ArgumentCaptor.captor();
        verify(contextStore).save(eq(SESSION), eq(1L), saved.capture());
        assertThat(savedText(saved))
                .contains("上轮问题").contains("这一轮问题")
                .contains("本轮市场结论").contains("这是本轮答案");
    }

    /** 专家的预取数据必须真喂进去：news 的工具无参，不预取就只能指望模型自己想起来调 */
    @Test
    void news专家拿到预取的快讯原文() {
        when(newsToolkit.newsSearch()).thenReturn("BlockBeats 快讯原文若干");
        lightAnswers(() -> route("news_agent"),
                () -> responseOf(new AssistantMessage("市场结论")),
                () -> responseOf(new AssistantMessage("新闻结论")));
        summarizerAnswers("这是答案");

        turn("最近有什么新闻");

        verify(newsToolkit).newsSearch();
        assertThat(expertPrompts).hasSize(1);
        assertThat(textOf(expertPrompts.getFirst())).contains("BlockBeats 快讯原文若干");
    }

    /** market 专家没有预取：它的工具要按问题选 symbol，只能模型现取 */
    @Test
    void market专家不做预取() {
        lightAnswers(() -> route("market_agent"),
                () -> responseOf(new AssistantMessage("市场结论")),
                () -> responseOf(new AssistantMessage("新闻结论")));
        summarizerAnswers("这是答案");

        turn("看看行情");

        verify(newsToolkit, never()).newsSearch();
        assertThat(textOf(expertPrompts.getFirst())).doesNotContain("系统预取的原始数据");
    }

    /**
     * 汇总摔了也要把已经花钱取到的东西留住：专家结论此刻只在内存里，不落库的话用户重试一遍，
     * 专家全得重派重烧（market 还打真实上游配额）。旧架构的 checkpoint 是边跑边存的、
     * 天然有这个语义，显式循环得自己补上。
     */
    @Test
    void 汇总失败时专家结论仍然落库() {
        lightAnswers(() -> route("market_agent"),
                () -> responseOf(new AssistantMessage("市场结论")),
                () -> responseOf(new AssistantMessage("新闻结论")));
        when(deep.stream(any(Prompt.class))).thenThrow(new RuntimeException("上游挂了"));

        assertThatThrownBy(() -> turn("看看行情")).isInstanceOf(RuntimeException.class);

        // 存的是 working（用户消息 + 专家结论），下一轮带着它起跑，专家不必重派
        ArgumentCaptor<List<Message>> saved = ArgumentCaptor.captor();
        verify(contextStore).save(eq(SESSION), eq(1L), saved.capture());
        assertThat(savedText(saved)).contains("看看行情").contains("市场结论");
    }

    // ===== 专家数据交到汇总者手上的形态：钉住"数据取回来了却答没有数据" =====

    /**
     * 专家结论必须以<b>带出处的用户侧消息</b>进上下文，不能是裸 AssistantMessage——
     * 裸助手消息在模型眼里是"我自己刚说过的话"，它会把刚取回的数据当成旧答案、答"没有数据"。
     */
    @Test
    void 专家结论带出处以用户侧消息进上下文() {
        lightAnswers(() -> route("market_agent"),
                () -> responseOf(new AssistantMessage("市场结论")),
                () -> responseOf(new AssistantMessage("新闻结论")));
        summarizerAnswers("这是答案");

        turn("看看行情");

        Message expertMessage = summarizerPrompts.getLast().getInstructions().stream()
                .filter(m -> m.getText() != null && m.getText().contains("市场结论"))
                .findFirst().orElseThrow();
        assertThat(expertMessage).isInstanceOf(UserMessage.class);
        assertThat(expertMessage.getText()).startsWith("【market_agent 取回的数据】");
    }

    /**
     * 派过专家就必须垫收尾指令，且排在<b>最后一条</b>——整段输入以用户侧消息结尾，
     * 模型才知道该由它作答，而不是"我刚说完、没什么可补充的"。
     */
    @Test
    void 专家数据之后垫收尾指令且排在最后() {
        lightAnswers(() -> route("market_agent"),
                () -> responseOf(new AssistantMessage("市场结论")),
                () -> responseOf(new AssistantMessage("新闻结论")));
        summarizerAnswers("这是答案");

        turn("看看行情");

        Message last = summarizerLastInput();
        assertThat(last).isInstanceOf(UserMessage.class);
        assertThat(last.getText()).isEqualTo(ChatTurnRunner.EXPERT_HANDOFF);
    }

    /** 一个专家都没派时不许垫：那句话说的是"依据上面的专家数据"，垫了就是凭空捏造不存在的数据 */
    @Test
    void 没派专家时不垫收尾指令() {
        lightAnswers(() -> route("FINISH"), () -> responseOf(new AssistantMessage("市场结论")),
                () -> responseOf(new AssistantMessage("新闻结论")));
        summarizerAnswers("这是答案");

        turn("你好呀");

        assertThat(summarizerInput()).doesNotContain(ChatTurnRunner.EXPERT_HANDOFF);
        assertThat(summarizerLastInput().getText()).isEqualTo("你好呀");
    }

    /**
     * 路由那一次调用的 options 必须从浅模型自己的派生并强制 required：openai 协议下 Spring AI 2.0 的
     * OpenAiChatModel 把 prompt 的 options 硬转 OpenAiChatOptions，泛型 builder 造的当场 ClassCastException，
     * 被兜成 FINISH → 整轮零派发 → 用户只看到"没有可用数据"（真跑实证的病，钉在这）。
     */
    @Test
    void 路由options跟着浅模型的协议走并强制required() {
        lightAnswers(() -> route("FINISH"), () -> responseOf(new AssistantMessage("市场结论")),
                () -> responseOf(new AssistantMessage("新闻结论")));
        summarizerAnswers("这是答案");
        ChatAgentFactory.Leaves leaves = leaves();
        // 改桩成 openai 协议的 options（leaves() 里默认桩的是泛型那种）
        when(light.getOptions()).thenReturn(OpenAiChatOptions.builder().model("deepseek-chat").build());

        new ChatTurnRunner(contextStore, registry)
                .run(leaves, 1L, SESSION, "BTC 怎么样", answer::append, progress::add,
                        ChatTurnRunner.TurnYield.NONE);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(light).call(prompt.capture());
        assertThat(prompt.getValue().getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolChoice()).isEqualTo("required");
        assertThat(options.getToolCallbacks()).extracting(t -> t.getToolDefinition().name()).containsExactly("route");
        assertThat(options.getModel()).isEqualTo("deepseek-chat");
    }

    /** 专家一个字都没返回时不许冒充数据：上下文里要说清楚，进度事件里要留痕 */
    @Test
    void 专家没有产出时不冒充数据() {
        lightAnswers(() -> route("market_agent"),
                () -> responseOf(new AssistantMessage("")),
                () -> responseOf(new AssistantMessage("新闻结论")));
        summarizerAnswers("这是答案");

        turn("看看行情");

        assertThat(summarizerInput()).contains("【market_agent 本轮没有返回内容】");
        assertThat(progress).anySatisfy(e -> {
            assertThat(e.agent()).isEqualTo("market_agent");
            assertThat(e.phase()).isEqualTo(ChatTurnRunner.ExpertProgress.ERROR);
            assertThat(e.text()).isEqualTo("没有返回任何内容");
        });
    }

    /**
     * 内容相同的消息不许被静默丢弃。框架默认的 {@code ReducerDisallowDuplicate} 按
     * {@code Objects.hash} 跟<b>整段历史</b>比对、命中就不 add 且无日志：同一句话问第二遍，
     * 第二条进不了上下文，模型根本没看见本轮问题。
     * <p>
     * 钉子看<b>落库终态里那句话出现两次</b>，叶子换成 appenderWithDuplicate 之前必红。
     */
    @Test
    void 内容相同的消息不再被静默丢弃() {
        when(contextStore.load(SESSION)).thenReturn(List.of(new UserMessage("同一句话")));
        lightAnswers(() -> route("FINISH"), () -> responseOf(new AssistantMessage("市场结论")),
                () -> responseOf(new AssistantMessage("新闻结论")));
        summarizerAnswers("这是答案");

        turn("同一句话");

        ArgumentCaptor<List<Message>> saved = ArgumentCaptor.captor();
        verify(contextStore).save(eq(SESSION), eq(1L), saved.capture());
        assertThat(saved.getValue()).filteredOn(m -> "同一句话".equals(m.getText())).hasSize(2);
    }

    /** 补答兜底是直接给用户看的：summarizer 零产出退专家原文时，内部出处标注必须剥掉 */
    @Test
    void 补答零产出时兜底剥掉出处标注() {
        summarizerAnswers("");

        String deferred = new ChatTurnRunner(contextStore, registry).runDeferredSummary(
                leaves(), 1L, SESSION, "看看行情",
                List.of(ChatTurnRunner.expertMessage("market_agent", "取回的数据", "资金费 0.01%")));

        assertThat(deferred).isEqualTo("资金费 0.01%");
    }

    /** 一轮跑完必须落库，否则下一轮从零起跑（用户表现为 AI 失忆） */
    @Test
    void 没有专家参与时也要落库() {
        lightAnswers(() -> route("FINISH"), () -> responseOf(new AssistantMessage("市场结论")),
                () -> responseOf(new AssistantMessage("新闻结论")));
        summarizerAnswers("这是答案");

        turn("随便问问");

        assertThat(progress).isEmpty();
        verify(contextStore).save(eq(SESSION), anyLong(), any());
        assertThat(answer.toString()).isEqualTo("这是答案");
    }
}

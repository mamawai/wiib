package com.mawai.wiibquant.agent.chat;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.agent.i18n.PromptCatalog;
import com.mawai.wiibquant.agent.i18n.UserLangResolver;
import com.mawai.wiibquant.agent.llm.ChatEndpoints;
import com.mawai.wiibquant.agent.llm.ConversationSummarizer;
import com.mawai.wiibquant.agent.llm.LlmEndpointService;
import com.mawai.wiibquant.agent.llm.LlmErrorMessages;
import com.mawai.wiibquant.agent.llm.SseChannel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 研判工作台对话入口（P4）：SSE 流式暴露多 agent 调度全过程。
 * 事件协议：session(会话号) / agent_start(调度切换) / token(LLM流，带 agent+role 区分专家过程/答案)
 * / progress(长工具阶段进度) / form_request(模型请求弹一张表单卡，执行权归用户点击)
 * / done(完整回答；deferred=true 是让位收尾，真答案由补答轮落库、前端轮询补显) / error。
 * 续聊上下文按 sessionId 存在自建的 {@link ChatContextStore} 表里，带同一 sessionId 再发即续聊；
 * 断连不中止本轮：{@link ChatTurnRunner} 跑完照样落历史，前端靠 status 接口+历史回放补答案。
 */
@Slf4j
@Tag(name = "研判工作台")
@RestController
@RequestMapping("/api/ai/workbench")
@RequiredArgsConstructor
public class ChatWorkbenchController {

    private final ChatAgentFactory chatAgentFactory;
    private final LlmEndpointService endpointService;
    private final ApprovalRegistry approvalRegistry;
    private final ChatHistoryService chatHistoryService;
    private final ChatContextStore contextStore;
    private final ChatTurnRunner turnRunner;
    private final WorkbenchRunRegistry runRegistry;
    private final ChatConcurrencyGate concurrencyGate;
    /** 会话归属与确认失效的提示跟界面语言 */
    private final MessageCatalog messages;
    private final ChatYieldCoordinator yieldCoordinator;
    private final PromptCatalog prompts;
    /** chat 是实时请求：语言走 @CurrentUserId → user.lang，与 trader 同一条路 */
    private final UserLangResolver userLangResolver;
    /** 包私有：名额泄漏那条钉子（{@code ChatWorkbenchAdmissionTest}）要关掉它来制造 submit 失败 */
    final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
    /** 心跳专用：只发注释帧(微秒级)，单线程够所有会话用；虚拟线程不支持定时调度故用平台线程 */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /** 深研判期间 SSE 通道会静默数分钟，nginx 默认 proxy_read_timeout 60s 会掐断——20s 一帧留 3 倍余量 */
    private static final long HEARTBEAT_SECONDS = 20;

    /** 单条用户消息字符上限；文案见 error.chatMessageTooLong，改这里要一起改 */
    static final int MAX_MESSAGE_CHARS = 10_000;

    /**
     * 上下文里每轮用户消息的起始标记。重新生成靠它从尾部找到"本轮提问"那条——
     * 一轮的尾巴不止"一问一答"，中间还夹着专家结论、交接指令和 tool_call 配对，
     * 只有这个标记认得出边界（交接指令与补答指令都以【系统】开头，不会撞）。
     */
    static final String TURN_MARKER = "【当前时间 ";

    /** 紧挨提问原文的前缀：重新生成靠"以它+提问结尾"认出上下文里那条确实是本轮的提问 */
    static final String QUESTION_MARKER = "用户问题：";

    /** 注入用户消息的当前时间。带年份不随仓里 MM-dd 惯例：模型没有时钟，年份是它最容易错的一位 */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Data
    public static class WorkbenchChatRequest {
        private String sessionId; // 空=新会话
        private String message;
        /** 功能按钮直发时带上；用户自己打字为空，走正常派发 */
        private ChatIntent intent;
    }

    @Data
    public static class RegenerateRequest {
        private String sessionId;
    }

    @Data
    public static class CancelRequest {
        private String sessionId;
    }

    @Data
    public static class ApprovalRequest {
        private String sessionId;
        private boolean approved;
        /** 从 hitl_request 事件原样回传，唯一标识"点的是哪张卡" */
        private String requestId;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "工作台对话（SSE：agent调度过程+token流式）")
    public SseEmitter chat(@CurrentUserId long userId, @RequestBody WorkbenchChatRequest request, HttpServletResponse response) {
        // nginx 反代默认缓冲会把 SSE 憋成一次性输出，显式关掉（免改服务器配置）
        response.setHeader("X-Accel-Buffering", "no");
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        // 上限挡的是误粘贴整份文件：首问会被压缩器原样保留、每次模型调用都重发
        if (request.getMessage().length() > MAX_MESSAGE_CHARS) {
            throw new BizException(ErrorCode.CHAT_MESSAGE_TOO_LONG);
        }
        // 三道准入都在把 emitter 交出去之前：一旦 return 给 MVC，响应就成了 event-stream，
        // 之后再出错只能推 error 事件，前端拿不到结构化错误码、没法自动引导用户去配置页
        ChatEndpoints eps = endpointService.chatEndpoints(userId);
        if (eps == null) {
            throw new BizException(ErrorCode.LLM_CONFIG_MISSING);
        }
        ChatAgentFactory.Leaves leaves;
        try {
            // 建叶子放准入期：配置能过保存校验但仍可能建不出模型（协议对不上等），这类错误必须在建流前暴露。
            // 建叶子不发网络请求，慢端点不会拖垮这里
            leaves = chatAgentFactory.leavesFor(eps, userLangResolver.of(userId));
        } catch (Exception e) {
            log.warn("[Workbench] 建模失败 userId={}", userId, e);
            throw new BizException(ErrorCode.LLM_CONFIG_INVALID);
        }
        // 拒因由闸门自己给，不去 runRegistry 二次推断：那边 finish 先摘、名额后还，
        // 中间那个窗口会把"你还有一轮在跑"误报成"人满了"
        ChatConcurrencyGate.Acquire acquired = concurrencyGate.tryAcquire(userId);
        if (acquired == ChatConcurrencyGate.Acquire.USER_BUSY) {
            // 自己的上一轮还在跑：正处专家等待期就要求让位（用户消息优先，专家结果转入补答队列），
            // 等它退位后抢回名额；不可让位（路由/汇总中）维持占线拒绝，前端回落本地排队
            acquired = awaitYield(userId);
        }
        if (acquired != ChatConcurrencyGate.Acquire.OK) {
            throw new BizException(acquired == ChatConcurrencyGate.Acquire.USER_BUSY
                    ? ErrorCode.CHAT_ALREADY_RUNNING : ErrorCode.CHAT_CAPACITY_FULL);
        }

        // sessionId 绑定 userId 前缀，防跨用户续聊他人会话
        String sessionId = request.getSessionId() != null && request.getSessionId().startsWith("wb-" + userId + "-")
                ? request.getSessionId()
                : "wb-" + userId + "-" + UUID.randomUUID();

        return streamTurn(userId, sessionId, request.getMessage(), leaves, null, request.getIntent());
    }

    @PostMapping(value = "/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "重新生成会话最后一条回答（SSE，事件协议同 /chat）")
    public SseEmitter regenerate(@CurrentUserId long userId, @RequestBody RegenerateRequest request,
                                 HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        String sessionId = request.getSessionId();
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            throw new BizException(ErrorCode.CHAT_REGENERATE_UNAVAILABLE);
        }
        ChatEndpoints eps = endpointService.chatEndpoints(userId);
        if (eps == null) {
            throw new BizException(ErrorCode.LLM_CONFIG_MISSING);
        }
        ChatAgentFactory.Leaves leaves;
        try {
            leaves = chatAgentFactory.leavesFor(eps, userLangResolver.of(userId));
        } catch (Exception e) {
            log.warn("[Workbench] 建模失败 userId={}", userId, e);
            throw new BizException(ErrorCode.LLM_CONFIG_INVALID);
        }
        // 重新生成不做让位握手：它不是等着要答案的新问题，占线就直接拒，用户等那轮跑完再点
        ChatConcurrencyGate.Acquire acquired = concurrencyGate.tryAcquire(userId);
        if (acquired != ChatConcurrencyGate.Acquire.OK) {
            throw new BizException(acquired == ChatConcurrencyGate.Acquire.USER_BUSY
                    ? ErrorCode.CHAT_ALREADY_RUNNING : ErrorCode.CHAT_CAPACITY_FULL);
        }
        Rollback rollback;
        try {
            // 名额到手后才回退：此刻没有别的轮在跑（补答也占同一个名额），
            // 读到的历史与上下文不会被人从背后改掉，回退也不会被别人的落库覆盖
            rollback = rollbackLastTurn(sessionId, userId);
        } catch (RuntimeException e) {
            concurrencyGate.release(userId);
            throw e;
        }
        // 重新生成不带意图：库里存的是提问原文，按钮意图是请求级的、没落库。
        // 行为分析这类提问原文本身就够明确，路由与汇总的成文规则接得住
        return streamTurn(userId, sessionId, rollback.question(), leaves, rollback.answerId(), null);
    }

    /** 回退的产物：要重问的原文，以及那条等着被顶替的旧答案行 */
    private record Rollback(String question, long answerId) {
    }

    /**
     * 把模型侧上下文回退到"最后一问已在、回答未出"的状态，交出要重问的原文和那条待顶替的旧答案行。
     * <p>
     * 上下文从尾部回删到本轮提问为止（含它）——一轮的尾巴不止"一问一答"，中间还夹着专家结论、
     * 交接指令与 tool_call 配对。展示表这里一行不动：旧答案要留到新答案确实落库之后才删，见 {@link #run}。
     * <p>
     * <b>光靠轮起始标记定位不住</b>：历史压缩会把首条用户消息<b>原样</b>放回压缩结果队首
     *（见 {@code ConversationSummarizer}），那条正是会话第一轮的提问、同样带着标记。
     * 所以标记只用来找候选，还要拿它与展示表里那条提问核对——对不上就说明本轮提问已被压进摘要，回不去了。
     * <p>
     * 回不去的一律抛 2206 拒绝，不做半吊子的补偿。
     */
    private Rollback rollbackLastTurn(String sessionId, long userId) {
        List<ChatHistoryService.ChatMessage> history = chatHistoryService.messages(sessionId);
        if (history.isEmpty() || !"assistant".equals(history.getLast().role())
                || ChatRowKind.DEFERRED.equals(history.getLast().kind())) {
            throw new BizException(ErrorCode.CHAT_REGENERATE_UNAVAILABLE);
        }
        ChatHistoryService.ChatMessage answer = history.getLast();
        String question = null;
        for (int i = history.size() - 2; i >= 0; i--) {
            if ("user".equals(history.get(i).role())) {
                question = history.get(i).content();
                break;
            }
        }
        if (question == null) {
            throw new BizException(ErrorCode.CHAT_REGENERATE_UNAVAILABLE);
        }
        List<Message> context = contextStore.load(sessionId);
        int cut = -1;
        for (int i = context.size() - 1; i >= 0; i--) {
            Message message = context.get(i);
            if (message instanceof UserMessage && message.getText() != null
                    && message.getText().startsWith(TURN_MARKER)) {
                cut = i;
                break;
            }
        }
        // 核对的是 enriched 的尾巴（拼法见 run() 里那两行），对不上就是压缩把本轮提问吃掉了，
        // 此时命中的那条是压缩留下的首问——照它切会把中间好几轮连同摘要一起抹掉
        if (cut < 0 || !context.get(cut).getText().endsWith(QUESTION_MARKER + question)) {
            throw new BizException(ErrorCode.CHAT_REGENERATE_UNAVAILABLE);
        }
        // 切在队首且紧跟着摘要，说明命中的是压缩原样放回的首问，不是本轮提问——
        // 同一句常用问法在一个会话里问两遍就会这样，文本对得上但位置是假的，照切会把整段上下文连摘要清空
        if (cut == 0 && context.size() > 1 && ConversationSummarizer.isSummary(context.get(1), prompts)) {
            throw new BizException(ErrorCode.CHAT_REGENERATE_UNAVAILABLE);
        }
        contextStore.save(sessionId, userId, List.copyOf(context.subList(0, cut)));
        return new Rollback(question, answer.id());
    }

    /**
     * 建流并把这一轮丢给执行器。/chat 与 /regenerate 共用。
     *
     * @param replacedAnswerId null=普通轮；非空=重新生成轮，它在顶替这条旧答案——
     *                         提问已在库里不再落一遍，且这一轮不许被新消息挤走
     *                         （旧答案的位置已经腾出来了，被挤掉就没处放新答案）
     */
    private SseEmitter streamTurn(long userId, String sessionId, String message,
                                  ChatAgentFactory.Leaves leaves, Long replacedAnswerId, ChatIntent intent) {
        // 深研判轮次要跑 Bull∥Bear+Judge 共3次深模型调用，180s 会掐断回答流，给足 10 分钟
        SseEmitter emitter = new SseEmitter(600_000L);
        SseChannel channel = new SseChannel(emitter);
        emitter.onCompletion(channel::markClosed);
        emitter.onTimeout(() -> {
            channel.markClosed();
            emitter.complete();
        });
        emitter.onError(ex -> channel.markClosed());

        ChatYieldCoordinator.TurnHandle turn = yieldCoordinator.openTurn(userId, replacedAnswerId == null);
        try {
            streamExecutor.submit(() -> {
                // 名额收在这一层还，而不是 run() 的 finally：run() 开头那句
                // heartbeatScheduler.scheduleWithFixedDelay 在它自己的 try 之外，
                // scheduler 关闭时它抛出去，run() 的 finally 根本不执行，名额就永久漏了
                try {
                    run(channel, userId, sessionId, message, leaves, turn, replacedAnswerId, intent);
                } catch (Throwable e) {
                    // submit 返回的 Future 没人 get()，不自己记一笔的话异常被完全吞掉
                    log.error("[Workbench] 对话任务异常退出 sessionId={}", sessionId, e);
                    // run() 只兜 Exception，Error 穿到这里时通道还开着：不收口前端要挂到 10 分钟超时
                    if (!channel.isClosed()) {
                        channel.send("error", new JSONObject()
                                .fluentPut("message", prompts.get(leaves.lang(), "llm.error.fallback")));
                        channel.complete();
                    }
                } finally {
                    concurrencyGate.release(userId);
                    // 必须在还名额之后：turnDone 是让位等待者抢名额的发令枪
                    yieldCoordinator.closeTurn(turn);
                }
            });
        } catch (Throwable e) {
            // 兜 Throwable 不是 RuntimeException：submit 失败的极端形态（线程建不出来）可能是 Error。
            // 名额漏满就对所有人永久拒绝，是全套设计里唯一不可恢复的失败模式，宁可多兜一层
            concurrencyGate.release(userId);
            yieldCoordinator.closeTurn(turn);
            throw e;
        }
        return emitter;
    }

    /** 让位握手：发信号 → 等在跑轮退位（有硬顶）→ 抢名额。任何一步不成都归于"占线"。 */
    private ChatConcurrencyGate.Acquire awaitYield(long userId) {
        CompletableFuture<Void> turnDone = yieldCoordinator.requestYield(userId);
        if (turnDone == null) {
            return ChatConcurrencyGate.Acquire.USER_BUSY;
        }
        try {
            turnDone.get(ChatYieldCoordinator.YIELD_HANDSHAKE_MS, TimeUnit.MILLISECONDS);
            return concurrencyGate.tryAcquire(userId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ChatConcurrencyGate.Acquire.USER_BUSY;
        } catch (ExecutionException | TimeoutException e) {
            return ChatConcurrencyGate.Acquire.USER_BUSY;
        } finally {
            // 抢没抢到都要解除"等待者在场"的登记，否则补答被永久挡住
            yieldCoordinator.yieldHandshakeDone(userId);
        }
    }

    @PostMapping("/cancel")
    @Operation(summary = "中断在跑的这一轮（跑到下一个检查点收尾，半截答案照落库）")
    public Result<Boolean> cancel(@CurrentUserId long userId, @RequestBody CancelRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.ok(false);
        }
        // 按 userId 找在跑的那一轮（闸门保证每人至多一轮）；没在跑就是按钮点晚了，如实回 false
        return Result.ok(yieldCoordinator.requestCancel(userId));
    }

    @GetMapping("/sessions")
    @Operation(summary = "我的历史会话列表（标题=首条提问，按最后活跃倒序）")
    public Result<List<ChatHistoryService.SessionSummary>> sessions(@CurrentUserId long userId) {
        return Result.ok(chatHistoryService.sessions(userId, 50));
    }

    @GetMapping("/sessions/{sessionId}/status")
    @Operation(summary = "会话运行状态（切页/刷新回来判断 AI 是否还在后台跑，结束后拉历史补答案）")
    public Result<Boolean> sessionStatus(@CurrentUserId long userId, @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail(messages.get("quant.chat.sessionNotFound"));
        }
        // 有轮在跑或欠着补答都算"还在跑"：让位收尾后前端靠这个口径继续轮询等补答落库
        return Result.ok(runRegistry.isRunning(sessionId) || yieldCoordinator.hasPending(sessionId));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @Operation(summary = "单会话消息记录（点进历史会话回看，续聊仍走 /chat 带同一 sessionId）")
    public Result<List<ChatHistoryService.ChatMessage>> sessionMessages(@CurrentUserId long userId,
                                                                        @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail(messages.get("quant.chat.sessionNotFound"));
        }
        return Result.ok(chatHistoryService.messages(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "删除历史会话（展示记录 + 后端续聊上下文）；在跑或欠补答的会话拒删")
    public Result<Void> deleteSession(@CurrentUserId long userId, @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail(messages.get("quant.chat.sessionNotFound"));
        }
        // 与 /status 同一口径：这轮收尾/补答落库还会往会话里写 assistant 行，先删掉它会以无标题空壳重新冒出来
        if (runRegistry.isRunning(sessionId) || yieldCoordinator.hasPending(sessionId)) {
            return Result.fail(messages.get("quant.chat.sessionRunning"));
        }
        chatHistoryService.deleteSession(sessionId);
        // 展示记录与续聊上下文是两套存储，删会话得都清；挂着的确认卡/授权一并清
        contextStore.purge(sessionId);
        approvalRegistry.purgeSession(sessionId);
        return Result.ok(null);
    }

    /** HITL 确认回执：approve 后前端自动补发"请继续执行深度研判"，agent 重调工具时闸门放行。 */
    @PostMapping("/approve")
    @Operation(summary = "贵操作确认（HITL）")
    public Result<Void> approve(@CurrentUserId long userId, @RequestBody ApprovalRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail(messages.get("quant.chat.sessionNotFound"));
        }
        // 标识对不上 = 用户点的是被新请求覆盖掉的旧卡片。
        // 此时若照批，用户看着"深研判 BTC"点的同意会授权给新请求里的别的标的。
        // 用 UUID 不用时间戳：两次登记之间是微秒级，同一毫秒内时间戳比对恒成立、等于没比
        boolean ok = request.isApproved()
                ? approvalRegistry.approve(sessionId, request.getRequestId())
                : approvalRegistry.reject(sessionId, request.getRequestId());
        return ok ? Result.ok(null) : Result.fail(messages.get("quant.chat.approvalExpired"));
    }

    /**
     * 一轮对话的全过程。包私有而非 private：HITL 的链路钉子
     *（{@code ChatWorkbenchHitlTest}）要真跑这段并看它发出去的 SSE 事件，
     * 而 {@link #chat} 自己 new emitter、事件出不来。
     */
    void run(SseChannel channel, long userId, String sessionId, String message,
             ChatAgentFactory.Leaves leaves, ChatYieldCoordinator.TurnHandle turn, Long replacedAnswerId,
             ChatIntent intent) {
        // 本轮开跑的时刻：结尾只发"这一轮新登记"的确认卡（见下面 hitl_request 那段），
        // 同时也是落库耗时的起点。不含准入/建叶子/让位握手；比 [TurnMetrics] 日志早一点，
        // 那条是从 ChatTurnRunner 里起算的，这里还多了一帧 session 和 user 行落库
        long turnStartedAt = System.currentTimeMillis();
        // 答案流/过程流分离：专家的结论是"工作过程"（前端折叠展示、不落历史），
        // 只有 summarizer 的汇总才是答案——否则单专家问题会"专家一遍+汇总一遍"重复输出
        StringBuilder answer = new StringBuilder();
        StringBuilder expertLog = new StringBuilder();
        // 深研判这类工具在 agent 里同步阻塞跑，期间通道零字节。心跳全程喂着，中间层才不会当连接死了掐断
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleWithFixedDelay(
                channel::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        try {
            // 运行登记：status 接口靠它回答"是否还在跑"；事件出口把工具侧的进度/表单卡转成 SSE 帧。
            // 断连要如实答 false：SseChannel.send 把发送异常吞成 closed 标记、本身返回 void，
            // 不在这儿拦一道的话，工具侧收到的永远是"推出去了"，模型就会宣称卡已弹出
            runRegistry.start(sessionId, (event, data) -> {
                if (channel.isClosed()) {
                    return false;
                }
                channel.send(event, data);
                return true;
            });
            channel.send("session", new JSONObject().fluentPut("sessionId", sessionId));
            // 重新生成用的是库里已有的那条提问，不能再落一遍 user 行
            if (replacedAnswerId == null) {
                chatHistoryService.append(sessionId, userId, "user", message);
            }

            // 时间行锚定"最近/未来1h"这类语义；随每条用户消息注入，历史里各带各的时刻
            String enriched = TURN_MARKER + TIME_FMT.format(Instant.now()) + "】\n"
                    + QUESTION_MARKER + message;

            // 账本清零划出本轮边界：装饰器跟着叶子跨轮缓存，不清就是上一轮的账接着涨。
            // 同时记下开工时账本干不干净——让位交出去的专家批次没人取消，会跨轮继续往这份账本上记。
            // 只在开工时查一次就够：新批次只由本用户自己的让位产生，而同一用户同时只有一轮在跑
            boolean dirtyBook = yieldCoordinator.hasInFlightExperts(userId);
            leaves.resetUsage();
            ChatTurnRunner.TurnResult result = turnRunner.run(leaves, userId, sessionId, enriched, intent,
                    chunk -> {
                        // 攒答案在断连判断之外：断连后这轮照跑完，答案仍要进历史，
                        // 只是不再往已经断掉的通道里写帧
                        answer.append(chunk);
                        if (!channel.isClosed()) {
                            channel.send("token", new JSONObject()
                                    .fluentPut("text", chunk)
                                    .fluentPut("agent", "supervisor") // 前端事件契约不变
                                    .fluentPut("role", "answer"));
                        }
                    },
                    event -> onExpertProgress(channel, expertLog, event, leaves.lang()), turn);

            if (result.cancelled()) {
                // 用户点了停止：半截答案照落库（token 已经烧掉了，屏幕上那段也该留得住）。
                // 与让位不同，这一轮不欠补答，done 收尾即完结
                String stopped = ChatTurnRunner.cancelledAnswer(prompts, leaves.lang(), answer.toString());
                ChatHistoryService.TurnMeta meta = turnMeta(leaves, dirtyBook, turnStartedAt);
                boolean saved = chatHistoryService.append(sessionId, userId, "assistant", stopped, meta);
                // 重生成轮：出了半截才顶掉旧答案，半截也是这一次重生成的产物，留着旧的同一个提问下
                // 就是两条 assistant 行。一个字都没出（点得快）就别删——那等于拿一行"未作答"
                // 换掉用户原来那条好答案，而且不可恢复
                if (replacedAnswerId != null && saved && !answer.isEmpty()) {
                    chatHistoryService.deleteMessage(replacedAnswerId);
                }
                if (!channel.isClosed()) {
                    channel.send("done", new JSONObject()
                            .fluentPut("sessionId", sessionId)
                            .fluentPut("answer", stopped)
                            .fluentPut("cancelled", true)
                            .fluentPut("meta", metaJson(meta)));
                    channel.complete();
                }
                return;
            }

            if (result.yielded()) {
                // 让位收尾：答案欠着（记账给协调器补答），本轮不落 assistant 历史——
                // 补答轮会补齐。registerDeferred 必须在本轮结束（runRegistry.finish）之前：
                // status 口径是 isRunning || hasPending，先摘运行标记再记账会闪出空窗，轮询端误判已结束。
                // done 带 deferred 标记：前端据此转入轮询等补答，answer 只是过渡话术不进历史
                yieldCoordinator.registerDeferred(userId, sessionId, leaves, message, result.deferredExperts());
                if (!channel.isClosed()) {
                    channel.send("done", new JSONObject()
                            .fluentPut("sessionId", sessionId)
                            .fluentPut("deferred", true)
                            .fluentPut("answer", prompts.get(leaves.lang(), "chat.yieldDoneAnswer")));
                    channel.complete();
                }
                return;
            }

            // HITL：本轮 agent 触发了贵操作待确认 → 弹确认卡（approve 后前端自动补发继续指令）。
            // 只发本轮新登记的那张：pending 是 approve/reject 才摘的，用户不点、接着问下一个问题的话
            // 它会一直躺在那儿——不筛的话每轮结束都再弹一遍同一张卡。
            // 筛"本轮新登记"而不是"发完就删"：删了用户回头点那张旧卡就成了"已失效"，
            // 而他点的其实是唯一还在服务端挂着的那条请求，照批是对的
            approvalRegistry.peekPending(sessionId)
                    .filter(pendingRequest -> pendingRequest.requestedAt() >= turnStartedAt)
                    .ifPresent(pendingRequest -> channel.send("hitl_request", new JSONObject()
                            .fluentPut("sessionId", sessionId)
                            .fluentPut("symbol", pendingRequest.symbol())
                            .fluentPut("reason", pendingRequest.reason())
                            .fluentPut("requestId", pendingRequest.requestId())
                            .fluentPut("resumeMessage",
                                    prompts.get(leaves.lang(), "chat.hitl.resumeMessage"))));

            // 极端场景（调用上限截停等）summarizer 没产出汇总，退专家结论，答案不至于丢
            String finalAnswer = !answer.isEmpty() ? answer.toString() : expertLog.toString();
            ChatHistoryService.TurnMeta meta = turnMeta(leaves, dirtyBook, turnStartedAt);
            // 历史不看连接死活：切页断连后这一轮照跑完，答案必须落库（前端回来靠 status+历史补）。
            // 且必须在 finally 摘运行标记之前写完——轮询端不能出现"已结束但查不到答案"的空窗
            boolean saved = chatHistoryService.append(sessionId, userId, "assistant", finalAnswer, meta);
            // 旧答案留到新答案确实落库之后才删（append 落没落库看返回值，空产出压根不落行）：
            // 重跑抛异常、产出为空、insert 失败、中途被让位的任何一条路上，用户至少还留着原来那条，
            // 也还能再点一次重新生成——两条都没了的话末尾是 user 行，连重新生成都点不了
            if (replacedAnswerId != null && saved) {
                chatHistoryService.deleteMessage(replacedAnswerId);
            }
            if (!channel.isClosed()) {
                channel.send("done", new JSONObject()
                        .fluentPut("sessionId", sessionId)
                        .fluentPut("answer", finalAnswer)
                        .fluentPut("meta", metaJson(meta)));
                channel.complete();
            }
        } catch (Exception e) {
            log.error("[Workbench] 对话失败 sessionId={}", sessionId, e);
            if (!channel.isClosed()) {
                channel.send("error", new JSONObject()
                        .fluentPut("message", LlmErrorMessages.classify(e, prompts, leaves.lang())));
                // 正常收尾而非 completeWithError：原因已随上面的 error 事件发出去了，
                // 再把异常抛回 MVC 只会让 GlobalExceptionHandler 往 event-stream 里写 JSON，
                // 撞 HttpMessageNotWritableException，反而把真实错误盖掉
                channel.complete();
            }
        } finally {
            heartbeat.cancel(false);
            runRegistry.finish(sessionId);
        }
    }

    /**
     * 本轮读数。账本被别轮的在途专家写脏时只报耗时：读到的数混着别人的账——
     * 宁可不报，也不能报个错的（null=没报，与全站 token 语义一致）。
     */
    private static ChatHistoryService.TurnMeta turnMeta(ChatAgentFactory.Leaves leaves,
                                                        boolean dirtyBook, long startedAt) {
        int latencyMs = (int) (System.currentTimeMillis() - startedAt);
        // usageUntrusted：中断丢下的在途流会在读数之后才入账，这一轮和下一轮的数都不可信
        return dirtyBook || leaves.usageUntrusted()
                ? ChatHistoryService.TurnMeta.latencyOnly(leaves.modelLabel(), latencyMs)
                : ChatHistoryService.TurnMeta.of(leaves.modelLabel(), leaves.usageSnapshot(), latencyMs);
    }

    /**
     * 读数 → SSE 字段，字段名与历史回放的 meta 一一对应。
     * 形状有一处不同：fastjson2 默认不输出 null，所以没报的项在这里是<b>缺席</b>，
     * 而历史接口走 Jackson 会输出 {@code null}——前端两边都按"取不到值=没报"判，不要判 0。
     */
    private static JSONObject metaJson(ChatHistoryService.TurnMeta meta) {
        return new JSONObject()
                .fluentPut("modelLabel", meta.modelLabel())
                .fluentPut("modelCalls", meta.modelCalls())
                .fluentPut("promptTokens", meta.promptTokens())
                .fluentPut("completionTokens", meta.completionTokens())
                .fluentPut("totalTokens", meta.totalTokens())
                .fluentPut("latencyMs", meta.latencyMs());
    }

    /**
     * 专家进度 → 前端事件。开始时发 agent_start（前端渲染成"接管分析"chip），
     * 结论整段作为 role=process 的 token 发出（前端折叠成"工作过程"块）。
     * 内容真实，只是并行下拿不到逐字流，一次性给。
     */
    private void onExpertProgress(SseChannel channel, StringBuilder expertLog,
                                  ChatTurnRunner.ExpertProgress event, AgentLang lang) {
        switch (event.phase()) {
            case ChatTurnRunner.ExpertProgress.START -> channel.send("agent_start", new JSONObject()
                    .fluentPut("node", event.agent())
                    .fluentPut("agent", event.agent()));
            case ChatTurnRunner.ExpertProgress.DONE -> {
                if (event.text() != null && !event.text().isBlank()) {
                    expertLog.append(event.text());
                    channel.send("token", new JSONObject()
                            .fluentPut("text", event.text())
                            .fluentPut("agent", event.agent())
                            .fluentPut("role", "process"));
                }
            }
            case ChatTurnRunner.ExpertProgress.ERROR -> channel.send("progress", new JSONObject()
                    .fluentPut("text", prompts.get(lang, "chat.progress.expertFailed",
                            java.util.Map.of("agent", event.agent(), "reason", event.text()))));
            default -> log.warn("[Workbench] 未知专家进度阶段 {}", event.phase());
        }
    }
}

package com.mawai.wiibquant.agent.chat;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.entity.UserLlmConfig;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.agent.llm.LlmErrorMessages;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 研判工作台对话入口（P4）：SSE 流式暴露多 agent 调度全过程。
 * 事件协议：session(会话号) / agent_start(调度切换) / token(LLM流，带 agent+role 区分专家过程/答案)
 * / progress(长工具阶段进度) / done(完整回答；deferred=true 是让位收尾，真答案由补答轮落库、
 * 前端轮询补显) / error。
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
    private final UserLlmConfigService userLlmConfigService;
    private final ApprovalRegistry approvalRegistry;
    private final ChatMemoryService chatMemoryService;
    private final ChatHistoryService chatHistoryService;
    private final ChatContextStore contextStore;
    private final ChatTurnRunner turnRunner;
    private final WorkbenchRunRegistry runRegistry;
    private final ChatConcurrencyGate concurrencyGate;
    private final ChatYieldCoordinator yieldCoordinator;
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

    /** 注入用户消息的当前时间。带年份不随仓里 MM-dd 惯例：模型没有时钟，年份是它最容易错的一位 */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Data
    public static class WorkbenchChatRequest {
        private String sessionId; // 空=新会话
        private String message;
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
        // 三道准入都在把 emitter 交出去之前：一旦 return 给 MVC，响应就成了 event-stream，
        // 之后再出错只能推 error 事件，前端拿不到结构化错误码、没法自动引导用户去配置页
        UserLlmConfig llmConfig = userLlmConfigService.get(userId);
        if (llmConfig == null) {
            throw new BizException(ErrorCode.LLM_CONFIG_MISSING);
        }
        ChatAgentFactory.Leaves leaves;
        try {
            // 建叶子放准入期：配置能过保存校验但仍可能建不出模型（协议对不上等），这类错误必须在建流前暴露。
            // 建叶子不发网络请求，慢端点不会拖垮这里
            leaves = chatAgentFactory.leavesFor(llmConfig);
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

        // 深研判轮次要跑 Bull∥Bear+Judge 共3次深模型调用，180s 会掐断回答流，给足 10 分钟
        SseEmitter emitter = new SseEmitter(600_000L);
        SseChannel channel = new SseChannel(emitter);
        emitter.onCompletion(channel::markClosed);
        emitter.onTimeout(() -> {
            channel.markClosed();
            emitter.complete();
        });
        emitter.onError(ex -> channel.markClosed());

        ChatYieldCoordinator.TurnHandle turn = yieldCoordinator.openTurn(userId);
        try {
            streamExecutor.submit(() -> {
                // 名额收在这一层还，而不是 run() 的 finally：run() 开头那句
                // heartbeatScheduler.scheduleWithFixedDelay 在它自己的 try 之外，
                // scheduler 关闭时它抛出去，run() 的 finally 根本不执行，名额就永久漏了
                try {
                    run(channel, userId, sessionId, request.getMessage(), leaves, turn);
                } catch (Throwable e) {
                    // submit 返回的 Future 没人 get()，不自己记一笔的话异常被完全吞掉
                    log.error("[Workbench] 对话任务异常退出 sessionId={}", sessionId, e);
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

    @GetMapping("/sessions")
    @Operation(summary = "我的历史会话列表（标题=首条提问，按最后活跃倒序）")
    public Result<List<ChatHistoryService.SessionSummary>> sessions(@CurrentUserId long userId) {
        return Result.ok(chatHistoryService.sessions(userId, 50));
    }

    @GetMapping("/sessions/{sessionId}/status")
    @Operation(summary = "会话运行状态（切页/刷新回来判断 AI 是否还在后台跑，结束后拉历史补答案）")
    public Result<Boolean> sessionStatus(@CurrentUserId long userId, @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail("会话不存在或无权限");
        }
        // 有轮在跑或欠着补答都算"还在跑"：让位收尾后前端靠这个口径继续轮询等补答落库
        return Result.ok(runRegistry.isRunning(sessionId) || yieldCoordinator.hasPending(sessionId));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @Operation(summary = "单会话消息记录（点进历史会话回看，续聊仍走 /chat 带同一 sessionId）")
    public Result<List<ChatHistoryService.ChatMessage>> sessionMessages(@CurrentUserId long userId,
                                                                        @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail("会话不存在或无权限");
        }
        return Result.ok(chatHistoryService.messages(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "删除历史会话（展示记录 + 后端续聊上下文）")
    public Result<Void> deleteSession(@CurrentUserId long userId, @PathVariable String sessionId) {
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail("会话不存在或无权限");
        }
        chatHistoryService.deleteSession(sessionId);
        // 展示记录与续聊上下文是两套存储，删会话得都清
        contextStore.purge(sessionId);
        return Result.ok(null);
    }

    /** HITL 确认回执：approve 后前端自动补发"请继续执行深度研判"，agent 重调工具时闸门放行。 */
    @PostMapping("/approve")
    @Operation(summary = "贵操作确认（HITL）")
    public Result<Void> approve(@CurrentUserId long userId, @RequestBody ApprovalRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || !sessionId.startsWith("wb-" + userId + "-")) {
            return Result.fail("会话不存在或无权限");
        }
        // 标识对不上 = 用户点的是被新请求覆盖掉的旧卡片。
        // 此时若照批，用户看着"深研判 BTC"点的同意会授权给新请求里的别的标的。
        // 用 UUID 不用时间戳：两次登记之间是微秒级，同一毫秒内时间戳比对恒成立、等于没比
        boolean ok = request.isApproved()
                ? approvalRegistry.approve(sessionId, request.getRequestId())
                : approvalRegistry.reject(sessionId, request.getRequestId());
        return ok ? Result.ok(null) : Result.fail("该确认请求已失效，请重新发起");
    }

    /**
     * 一轮对话的全过程。包私有而非 private：HITL 的链路钉子
     *（{@code ChatWorkbenchHitlTest}）要真跑这段并看它发出去的 SSE 事件，
     * 而 {@link #chat} 自己 new emitter、事件出不来。
     */
    void run(SseChannel channel, long userId, String sessionId, String message,
             ChatAgentFactory.Leaves leaves, ChatYieldCoordinator.TurnHandle turn) {
        // 本轮开跑的时刻：结尾只发"这一轮新登记"的确认卡，见下面 hitl_request 那段
        long turnStartedAt = System.currentTimeMillis();
        // 答案流/过程流分离：专家的结论是"工作过程"（前端折叠展示、不落历史），
        // 只有 summarizer 的汇总才是答案——否则单专家问题会"专家一遍+汇总一遍"重复输出
        StringBuilder answer = new StringBuilder();
        StringBuilder expertLog = new StringBuilder();
        // 深研判这类工具在 agent 里同步阻塞跑，期间通道零字节。心跳全程喂着，中间层才不会当连接死了掐断
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleWithFixedDelay(
                channel::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        try {
            // 运行登记：status 接口靠它回答"是否还在跑"；进度监听把长工具的阶段进度转成 SSE 事件
            runRegistry.start(sessionId, text ->
                    channel.send("progress", new JSONObject().fluentPut("text", text)));
            channel.send("session", new JSONObject().fluentPut("sessionId", sessionId));
            chatHistoryService.append(sessionId, userId, "user", message);

            // 跨会话记忆前缀：让 agent 记得用户常看什么、上次聊到哪。
            // 时间行锚定"最近/未来1h"这类语义；随每条用户消息注入，历史里各带各的时刻
            String memory = chatMemoryService.recall(userId);
            String enriched = "【当前时间 " + TIME_FMT.format(Instant.now()) + "】\n"
                    + memory + "用户问题：" + message;

            ChatTurnRunner.TurnResult result = turnRunner.run(leaves, userId, sessionId, enriched,
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
                    event -> onExpertProgress(channel, expertLog, event), turn);

            if (result.yielded()) {
                // 让位收尾：答案欠着（记账给协调器补答），本轮不落 assistant 历史也不记记忆——
                // 补答轮会补齐。registerDeferred 必须在本轮结束（runRegistry.finish）之前：
                // status 口径是 isRunning || hasPending，先摘运行标记再记账会闪出空窗，轮询端误判已结束。
                // done 带 deferred 标记：前端据此转入轮询等补答，answer 只是过渡话术不进历史
                yieldCoordinator.registerDeferred(userId, sessionId, leaves, message, result.deferredExperts());
                if (!channel.isClosed()) {
                    channel.send("done", new JSONObject()
                            .fluentPut("sessionId", sessionId)
                            .fluentPut("deferred", true)
                            .fluentPut("answer", "收到新消息，先处理它——这个问题的专家还在取数，答案稍后自动补上"));
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
                            .fluentPut("resumeMessage", "已确认，请继续执行深度研判")));

            // 极端场景（调用上限截停等）summarizer 没产出汇总，退专家结论，答案不至于丢
            String finalAnswer = !answer.isEmpty() ? answer.toString() : expertLog.toString();
            // 历史/记忆不看连接死活：切页断连后这一轮照跑完，答案必须落库（前端回来靠 status+历史补）。
            // 且必须在 finally 摘运行标记之前写完——轮询端不能出现"已结束但查不到答案"的空窗
            chatHistoryService.append(sessionId, userId, "assistant", finalAnswer);
            chatMemoryService.remember(userId, message, finalAnswer);
            if (!channel.isClosed()) {
                channel.send("done", new JSONObject()
                        .fluentPut("sessionId", sessionId)
                        .fluentPut("answer", finalAnswer));
                channel.complete();
            }
        } catch (Exception e) {
            log.error("[Workbench] 对话失败 sessionId={}", sessionId, e);
            if (!channel.isClosed()) {
                channel.send("error", new JSONObject()
                        .fluentPut("message", LlmErrorMessages.classify(e)));
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
     * 专家进度 → 前端事件。开始时发 agent_start（前端渲染成"接管分析"chip），
     * 结论整段作为 role=process 的 token 发出（前端折叠成"工作过程"块）。
     * 内容真实，只是并行下拿不到逐字流，一次性给。
     */
    private void onExpertProgress(SseChannel channel, StringBuilder expertLog,
                                  ChatTurnRunner.ExpertProgress event) {
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
                    .fluentPut("text", event.agent() + " 执行失败：" + event.text()));
            default -> log.warn("[Workbench] 未知专家进度阶段 {}", event.phase());
        }
    }

    /**
     * SSE 通道：emitter + 关闭标志 + 写锁收在一起。
     * 锁是必须的——SseEmitter.send 非线程安全，心跳线程与主流线程并发写会让帧交错损坏。
     * 锁在实例上而非 Controller 上，各会话互不阻塞。
     */
    static final class SseChannel {
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Object writeLock = new Object();

        SseChannel(SseEmitter emitter) {
            this.emitter = emitter;
        }

        boolean isClosed() {
            return closed.get();
        }

        void markClosed() {
            closed.set(true);
        }

        void send(String event, JSONObject data) {
            write(SseEmitter.event().name(event).data(data.toJSONString()));
        }

        /** 心跳：SSE 注释帧，前端 dispatch 取不到 data 直接忽略，纯粹喂饱中间层的空闲计时器。 */
        void heartbeat() {
            write(SseEmitter.event().comment("hb"));
        }

        private void write(SseEmitter.SseEventBuilder builder) {
            if (closed.get()) {
                return;
            }
            synchronized (writeLock) {
                if (closed.get()) {
                    return;
                }
                try {
                    emitter.send(builder);
                } catch (Exception e) {
                    closed.set(true);
                }
            }
        }

        /** complete 与写共用锁：避免心跳正在写时通道被关，Tomcat 抛 IllegalStateException */
        void complete() {
            synchronized (writeLock) {
                closed.set(true);
                emitter.complete();
            }
        }

    }
}

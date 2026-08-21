package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.agent.i18n.PromptCatalog;
import com.mawai.wiibquant.agent.llm.LlmErrorMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 对话让位协调器：让"用户的新消息"优先于"子 agent（专家）的返回"。
 * <p>
 * 在跑的轮正处专家等待期时新消息到达：{@link #requestYield} 完成让位信号 → 那轮立即收尾
 * （见 {@link ChatTurnRunner} 的让位路径），新消息抢到名额正常应答；被让位的问题连同在途
 * 专家批次经 {@link #registerDeferred} 排队，等会话空闲（没有轮在跑）时由补答轮
 * （{@link ChatTurnRunner#runDeferredSummary}）收尾落历史，前端靠 status 轮询补显——
 * status 口径因此是 isRunning || {@link #hasPending}。
 * <p>
 * 优先级规则就一条：<b>用户消息永远插队，专家结果永远排队</b>。补答只在三种时机试跑：
 * 专家批次完成时、任何一轮结束时、让位握手落空时；试跑撞上 USER_BUSY（用户在聊）就退让，
 * 等下一轮结束再试。
 * <p>
 * 队列是进程内存级（与 {@link WorkbenchRunRegistry} 同哲学）：单实例部署，重启即丢，
 * 代价是让位后重启那个问题没有补答——历史里问题仍在，重问即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatYieldCoordinator {

    /** 让位握手上限：从发信号到那轮退位还名额，正常几百毫秒（存档+发收尾事件）；超时按占线拒 */
    static final long YIELD_HANDSHAKE_MS = 15_000;
    /** 全局名额满时补答的重试间隔 */
    static final long GLOBAL_FULL_RETRY_MS = 5_000;
    /** 让位等待者的过期兜底：握手线程若死在半路，别让残留登记永久挡住补答 */
    static final long WAITER_STALE_MS = 30_000;

    private final ChatConcurrencyGate concurrencyGate;
    private final WorkbenchRunRegistry runRegistry;
    private final ChatTurnRunner turnRunner;
    private final ChatHistoryService chatHistoryService;
    /** 补答行的标头与失败交代都上屏；语言取自那一单自己的叶子（{@code work.leaves().lang()}） */
    private final PromptCatalog prompts;

    /** 补答跑在虚拟线程上：全程阻塞在 LLM 上游 IO */
    private final ExecutorService deferredExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** userId → 在跑轮的让位句柄（闸门每用户 1 轮，键天然唯一） */
    private final Map<Long, TurnHandle> activeTurns = new ConcurrentHashMap<>();
    /** userId → 让位等待者的登记时刻：握手完成到抢回名额之间，补答不许抢跑 */
    private final Map<Long, Long> yieldWaiters = new ConcurrentHashMap<>();
    /** sessionId → 待补答队列（FIFO）。补答归属会话，但调度按 userId（名额按人算） */
    private final Map<String, Queue<DeferredWork>> pendingBySession = new ConcurrentHashMap<>();

    record DeferredWork(long userId, String sessionId, ChatAgentFactory.Leaves leaves,
                        String question, CompletableFuture<List<Message>> experts) {
    }

    /**
     * 一轮的让位句柄：runner 经 {@link ChatTurnRunner.TurnYield} 面向它开关让位窗口，
     * 新消息的请求线程经 {@link #requestYield} 扣它的扳机。
     */
    public static final class TurnHandle implements ChatTurnRunner.TurnYield {
        private final long userId;
        /** false=这一轮不开让位窗口，新消息只能按占线拒、回落前端排队 */
        private final boolean preemptible;
        private final CompletableFuture<Void> yieldSignal = new CompletableFuture<>();
        /** 本轮完全结束（名额已还）：让位等待者以它为"可以抢名额了"的发令枪 */
        private final CompletableFuture<Void> turnDone = new CompletableFuture<>();
        /** 让位窗口开关（写：runner 线程；读：新消息的请求线程） */
        private volatile boolean yieldable = false;
        /** 用户点了停止（写：请求线程；读：runner 线程）。粘滞，跑到下一个检查点就收尾 */
        private volatile boolean cancelled = false;
        /** 中断信号：专家等待期阻塞在 anyOf 上，只有 future 能把它叫醒，光有布尔叫不醒 */
        private final CompletableFuture<Void> cancelSignal = new CompletableFuture<>();

        private TurnHandle(long userId, boolean preemptible) {
            this.userId = userId;
            this.preemptible = preemptible;
        }

        @Override
        public CompletableFuture<Void> enterExpertWait() {
            yieldable = preemptible;
            return yieldSignal;
        }

        @Override
        public void exitExpertWait() {
            yieldable = false;
        }

        @Override
        public boolean yieldRequested() {
            return yieldSignal.isDone();
        }

        @Override
        public boolean cancelRequested() {
            return cancelled;
        }

        @Override
        public CompletableFuture<Void> cancelSignal() {
            return cancelSignal;
        }
    }

    /**
     * 用户请求中断在跑的那一轮。没有轮在跑（已经结束了）返回 false，让调用方如实告诉用户按钮点晚了。
     * <p>
     * 与 {@link #requestYield} 的区别：让位是"这个问题稍后补答"，中断是"到此为止不补"，
     * 所以这里不动 yieldSignal、也不排补答队列。
     */
    public boolean requestCancel(long userId) {
        TurnHandle handle = activeTurns.get(userId);
        if (handle == null) {
            return false;
        }
        handle.cancelled = true;
        handle.cancelSignal.complete(null);   // 布尔叫不醒专家等待期那一等，得靠它
        return true;
    }

    /** 一轮开跑前登记（拿到名额之后、提交执行之前）。 */
    public TurnHandle openTurn(long userId) {
        return openTurn(userId, true);
    }

    /**
     * @param preemptible false=这一轮不许被新消息挤走。重新生成轮要的就是它：
     *                    它把旧答案的位置腾了出来，被挤掉的话新答案只能由补答轮
     *                    以【补答】标头追加到会话末尾，位置错、还再也不能重新生成
     */
    public TurnHandle openTurn(long userId, boolean preemptible) {
        TurnHandle handle = new TurnHandle(userId, preemptible);
        activeTurns.put(userId, handle);
        return handle;
    }

    /**
     * 一轮完全结束。<b>必须在名额归还之后调</b>：turnDone 是让位等待者抢名额的发令枪，
     * 名额还没还就开枪，等待者抢到的必然是 USER_BUSY。
     * 顺手试跑补答——tryDrain 里会先看有没有等待者在场，用户消息优先。
     */
    public void closeTurn(TurnHandle handle) {
        activeTurns.remove(handle.userId, handle);
        handle.turnDone.complete(null);
        tryDrain(handle.userId);
    }

    /**
     * 新消息撞上名额占用时的让位请求：在跑轮处于专家等待期 → 发让位信号并登记等待者，
     * 返回"那轮完全结束"的 future（等它再抢名额）；窗口没开（路由/汇总中）返回 null，按占线拒。
     */
    public CompletableFuture<Void> requestYield(long userId) {
        TurnHandle handle = activeTurns.get(userId);
        if (handle == null || !handle.yieldable) {
            return null;
        }
        yieldWaiters.put(userId, System.currentTimeMillis());
        handle.yieldSignal.complete(null);
        return handle.turnDone;
    }

    /** 让位握手收尾（抢没抢到都要调）：解除补答抢跑锁；没抢到名额时补答不该被饿死，补试一次。 */
    public void yieldHandshakeDone(long userId) {
        yieldWaiters.remove(userId);
        tryDrain(userId);
    }

    /** 让位轮把欠的账记上：在途专家批次进队，完成时自动试跑补答。 */
    public void registerDeferred(long userId, String sessionId, ChatAgentFactory.Leaves leaves,
                                 String question, CompletableFuture<List<Message>> experts) {
        DeferredWork work = new DeferredWork(userId, sessionId, leaves, question, experts);
        pendingBySession.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>()).add(work);
        experts.whenComplete((r, e) -> tryDrain(userId));
    }

    /** 会话有没有欠着的补答（status 轮询口径的另一半：让位收尾后队列非空，前端要接着等） */
    public boolean hasPending(String sessionId) {
        Queue<DeferredWork> queue = pendingBySession.get(sessionId);
        return queue != null && !queue.isEmpty();
    }

    /**
     * 这个用户还有没有跑不完的在途专家批次——有的话，用量账本上就混着不属于当前这一轮的 token。
     * <p>
     * 让位交出去的那批专家<b>没人取消</b>（{@link ChatTurnRunner} 的 dispatchAsync 是虚拟线程 fire-and-forget），
     * 它能跨过好几轮继续往同一份账本上记账。所以"这一轮的用量可不可信"要看它，
     * 而不是看"这一轮的名额是不是从谁手里抢来的"——后者只盖得住紧邻的那一轮。
     * <p>
     * 批次 {@code isDone()} 为真时账已经写完：专家走的是阻塞 invoke，模型调用在 future 完成前就返回了。
     */
    public boolean hasInFlightExperts(long userId) {
        return pendingBySession.values().stream()
                .flatMap(Queue::stream)
                .anyMatch(work -> work.userId() == userId && !work.experts().isDone());
    }

    /**
     * 试跑一单补答。名额每用户 1 个，一次只跑一单，跑完的 finally 里链式再试；
     * 并发调用靠闸门天然串行——第二个进来的拿到 USER_BUSY 直接退。
     */
    private void tryDrain(long userId) {
        Long waiterAt = yieldWaiters.get(userId);
        if (waiterAt != null && System.currentTimeMillis() - waiterAt < WAITER_STALE_MS) {
            return; // 有用户消息在等着抢名额：补答退让
        }
        for (Queue<DeferredWork> queue : pendingBySession.values()) {
            DeferredWork head = queue.peek();
            if (head == null || head.userId() != userId || !head.experts().isDone()) {
                continue;
            }
            ChatConcurrencyGate.Acquire acquired = concurrencyGate.tryAcquire(userId);
            if (acquired == ChatConcurrencyGate.Acquire.USER_BUSY) {
                return; // 用户在聊：等那轮 closeTurn 再试
            }
            if (acquired == ChatConcurrencyGate.Acquire.GLOBAL_FULL) {
                CompletableFuture.delayedExecutor(GLOBAL_FULL_RETRY_MS, TimeUnit.MILLISECONDS)
                        .execute(() -> tryDrain(userId));
                return;
            }
            deferredExecutor.submit(() -> runDeferred(head, queue));
            return;
        }
    }

    /** 一单补答：summarizer 收尾 → 落展示历史（带补答标头）。 */
    private void runDeferred(DeferredWork work, Queue<DeferredWork> queue) {
        // 账本清零划出这一段的边界。自己这单的专家早已完成（tryDrain 只挑 isDone 的），
        // 但同一用户名下可能还排着别的在途批次（别的会话/后一单），那些专家仍在往同一份账本上记账
        boolean dirtyBook = hasInFlightExperts(work.userId());
        work.leaves().resetUsage();
        long startedAt = System.currentTimeMillis();
        try {
            // 先登记运行中再出队：status = isRunning || hasPending，顺序反了会闪出两者皆 false 的空窗，
            // 轮询端会误判"已结束"，拉走没有补答的历史并停表。
            // 登记的是空出口：补答轮是后台跑的，没有 SSE 通道可推——这里只借"运行中"这个标记。
            // 后果要清楚：补答轮里 summarizer 仍带着动作类工具，它调 publishForm 一律返回 false，
            // 工具据此如实告诉模型"卡没弹出去"，别让模型宣称已弹卡（用户根本看不到）
            runRegistry.start(work.sessionId(), WorkbenchRunRegistry.NO_EMITTER);
            queue.remove(work);
            String answer = turnRunner.runDeferredSummary(work.leaves(), work.userId(), work.sessionId(),
                    work.question(), work.experts().join());
            chatHistoryService.append(work.sessionId(), work.userId(), "assistant",
                    deferredHeader(work) + answer, deferredMeta(work, startedAt, dirtyBook));
            log.info("[Yield] 补答完成 session={} chars={}", work.sessionId(), answer.length());
        } catch (Exception e) {
            log.warn("[Yield] 补答失败 session={}", work.sessionId(), e);
            // 失败也要给一行交代：不落的话轮询一停，用户看到的是问题永远没有下文
            chatHistoryService.append(work.sessionId(), work.userId(), "assistant",
                    deferredHeader(work) + prompts.get(work.leaves().lang(), "chat.deferred.failed",
                            Map.of("reason", LlmErrorMessages.classify(e, prompts, work.leaves().lang()))),
                    deferredMeta(work, startedAt, dirtyBook));   // 失败也照记：token 是真烧掉了
        } finally {
            runRegistry.finish(work.sessionId());
            concurrencyGate.release(work.userId());
            pendingBySession.computeIfPresent(work.sessionId(), (k, q) -> q.isEmpty() ? null : q);
            tryDrain(work.userId()); // 同用户可能还欠着别的补答
        }
    }

    /**
     * 补答这一段自己的读数：被让位那轮已经花掉的不算在这里（那轮没落 assistant 行，也就没处可记）。
     * 账本被别的在途批次写脏（dirtyBook）、或被中断丢下的在途流写脏（usageUntrusted）时只报耗时
     * ——两个判据同 {@code ChatWorkbenchController.turnMeta}，少判一个报出来的就是两轮混在一起的数。
     */
    private static ChatHistoryService.TurnMeta deferredMeta(DeferredWork work, long startedAt, boolean dirtyBook) {
        int latencyMs = (int) (System.currentTimeMillis() - startedAt);
        return dirtyBook || work.leaves().usageUntrusted()
                ? ChatHistoryService.TurnMeta.latencyOnly(work.leaves().modelLabel(), latencyMs)
                : ChatHistoryService.TurnMeta.of(work.leaves().modelLabel(), work.leaves().usageSnapshot(), latencyMs);
    }

    /**
     * 补答行的标头前缀（{@code chat.deferred.prefix}）。前端按它认出"这行是补答、不给重新生成"：
     * 补答行对应的提问不在会话末尾，中间夹着别的问答，回退会误伤那些轮次。
     * 认全部语言的那一份——历史行是写入时那门语言落库的。
     */
    static boolean isDeferredRow(String content, PromptCatalog prompts) {
        for (AgentLang candidate : AgentLang.values()) {
            if (content.startsWith(prompts.get(candidate, "chat.deferred.prefix"))) {
                return true;
            }
        }
        return false;
    }

    /** 补答的标头：时间线上它离原问题隔着别的对话，得自己说明在答哪个问题 */
    private String deferredHeader(DeferredWork work) {
        String q = work.question().strip().replaceAll("\\s+", " ");
        if (q.length() > 40) {
            q = q.substring(0, 40) + "…";
        }
        return prompts.get(work.leaves().lang(), "chat.deferred.header", Map.of("question", q)) + "\n\n";
    }
}

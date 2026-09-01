package com.mawai.wiibagent.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatModel 装饰器：把一次运行里所有模型调用的 token 用量累加起来。
 * 装饰器包在最外层，这么写为了把 ReAct 循环里的每一次调用都收进来。
 * <p>
 * <b>getOptions 必须原样透传</b>：返回自己造的 options 会让 ReactAgent 的工具列表变成空数组。
 * <p>
 * 两种用法：交易员轨每次唤醒 new 一个实例、用完取 {@link #snapshot()}；
 * 对话轨的实例跟着叶子图跨轮缓存，靠 {@link #reset()} 划轮边界（同一用户同时只有一轮，见 ChatConcurrencyGate）。
 */
public class UsageTrackingChatModel implements ChatModel {

    /** 上游没返回 usage 时 token 三项为 null——不能拿 0 冒充"没花钱" */
    public record UsageSnapshot(int modelCalls, Long promptTokens, Long completionTokens, Long totalTokens) {

        /**
         * 两条端点（深/浅）的账合并成一轮的总账。
         * null 语义随字段各算各的：两边都没报才是 null，一边报了就用报了的那份。
         */
        public UsageSnapshot merge(UsageSnapshot other) {
            return new UsageSnapshot(modelCalls + other.modelCalls,
                    sum(promptTokens, other.promptTokens),
                    sum(completionTokens, other.completionTokens),
                    sum(totalTokens, other.totalTokens));
        }

        private static Long sum(Long a, Long b) {
            if (a == null) {
                return b;
            }
            return b == null ? a : a + b;
        }
    }

    private final ChatModel delegate;

    private int calls;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    /** 本轮有过被抛弃的在途流 */
    private boolean abandoned;
    /** 上一轮抛弃的流：它可能在这一轮清零之后才终止入账，所以脏要往后带一轮 */
    private boolean abandonedCarry;

    public UsageTrackingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public @NonNull ChatOptions getOptions() {
        return delegate.getOptions();
    }

    @Override
    public @NonNull ChatResponse call(@NonNull Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        record(usageOf(response));
        return response;
    }

    @Override
    public @NonNull Flux<ChatResponse> stream(@NonNull Prompt prompt) {
        // 流式的 usage 只挂在最后一个 chunk 上，且通常是本次调用的累计值，逐块相加会翻倍。
        // 只留最后见到的那份，流终止时入账一次。
        AtomicReference<Usage> last = new AtomicReference<>();
        AtomicBoolean recorded = new AtomicBoolean();
        return delegate.stream(prompt)
                .doOnNext(r -> {
                    Usage u = usageOf(r);
                    last.set(u);
                })
                // 入账必须赶在终止信号传给下游之前：消费方一收到 onComplete 就会去读 snapshot()，
                // 而 doFinally 是信号传播完才跑的——那一次（往往正是最贵的汇总）会漏记
                .doOnTerminate(() -> recordOnce(recorded, last))
                // 取消不经 doOnTerminate，但 token 照样是真烧掉的，兜在这儿；CAS 保证同一次流只入账一遍
                .doFinally(sig -> recordOnce(recorded, last));
    }

    private void recordOnce(AtomicBoolean recorded, AtomicReference<Usage> last) {
        if (recorded.compareAndSet(false, true)) {
            record(last.get());
        }
    }

    @Override
    public String call(@NonNull String message) {
        return Objects.requireNonNull(call(new Prompt(message)).getResult()).getOutput().getText();
    }

    @Override
    public String call(@NonNull Message @NonNull ... messages) {
        return Objects.requireNonNull(call(new Prompt(Arrays.asList(messages))).getResult()).getOutput().getText();
    }

    /** 本轮累计；ReAct 循环可能跑在虚拟线程上，加锁保稳。 */
    public synchronized UsageSnapshot snapshot() {
        return new UsageSnapshot(calls, promptTokens, completionTokens, totalTokens);
    }

    /**
     * 归零，划出新一轮的账本起点。
     * <p>
     * 给"实例跨轮复用"的对话轨用：那边模型被烤进编译好的叶子图、图又按配置指纹缓存，
     * 拿不到"每轮 new 一个"的机会，只能在轮开头清零。交易员轨每轮新建，不需要调它。
     */
    public synchronized void reset() {
        calls = 0;
        promptTokens = null;
        completionTokens = null;
        totalTokens = null;
        abandonedCarry = abandoned;
        abandoned = false;
    }

    /**
     * 标记"这一轮丢下了一条还在跑的流"（用户中断时会发生）。
     * <p>
     * 被丢下的流不会停：图生成器不支持取消（见 {@code ChatAgentFactory} 的说明），
     * 模型照样一路吐到终止，而入账挂在流终止上——它会在<b>这一轮读完数之后</b>、
     * 甚至<b>下一轮清零之后</b>才把整次调用的 token 加进来。
     * 所以这一轮和紧接着的下一轮，账都不能报，见 {@link #untrusted()}。
     */
    public synchronized void markAbandoned() {
        abandoned = true;
    }

    /** 账本被抛弃的流写脏了：宁可不报，也别报个错的（与全站 token null≠0 同口径） */
    public synchronized boolean untrusted() {
        return abandoned || abandonedCarry;
    }

    private synchronized void record(Usage usage) {
        calls++;
        if (usage == null) {
            return;
        }
        promptTokens = plus(promptTokens, usage.getPromptTokens());
        completionTokens = plus(completionTokens, usage.getCompletionTokens());
        totalTokens = plus(totalTokens, usage.getTotalTokens());
    }

    /**
     * 按字段各算各的（网关只报一半也不丢），"没报告"与"报了 0"合并成 null。
     * <p>
     * 不能靠 null 判断有没有报告：Spring AI 的 ChatResponse 即使没带 usage 也会给一个全 0 的
     * EmptyUsage，DefaultUsage 还会把 null 归一成 0。所以只认正数——真实调用的 prompt token
     * 不可能是 0，全程没见过正数就是上游压根没报。
     */
    private static Long plus(Long acc, Integer add) {
        if (add == null || add <= 0) {
            return acc;
        }
        return acc == null ? add.longValue() : acc + add;
    }

    private static Usage usageOf(ChatResponse response) {
        return response == null ? null : response.getMetadata().getUsage();
    }
}

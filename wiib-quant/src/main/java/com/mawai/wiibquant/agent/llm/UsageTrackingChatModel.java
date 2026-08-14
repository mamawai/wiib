package com.mawai.wiibquant.agent.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.lang.NonNull;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatModel 装饰器：把一次运行里所有模型调用的 token 用量累加起来。
 * <p>
 * 为什么要装饰器而不是在某处读一次：ReAct 是循环，一次唤醒会调模型很多次
 * （见 {@link ModelCallLimiter}），单次响应的 usage 只是其中一小段。
 * 包在最外层才能把每一次都收进来。
 * <p>
 * <b>getOptions 必须原样透传</b>：这里返回自己造的 options，
 * ReactAgent 拿到的工具列表就成了空数组，agent 一个工具都调不动（本项目踩过）。
 * <p>
 * 每次唤醒 new 一个实例，用完取 {@link #snapshot()}。
 */
public class UsageTrackingChatModel implements ChatModel {

    /** 上游没返回 usage 时 token 三项为 null——不能拿 0 冒充"没花钱" */
    public record UsageSnapshot(int modelCalls, Long promptTokens, Long completionTokens, Long totalTokens) {
    }

    private final ChatModel delegate;

    private int calls;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;

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
        // 只留最后见到的那份，流结束时入账一次。
        AtomicReference<Usage> last = new AtomicReference<>();
        return delegate.stream(prompt)
                .doOnNext(r -> {
                    Usage u = usageOf(r);
                    if (u != null) {
                        last.set(u);
                    }
                })
                .doFinally(sig -> record(last.get()));
    }

    @Override
    public String call(@NonNull String message) {
        return call(new Prompt(message)).getResult().getOutput().getText();
    }

    @Override
    public String call(@NonNull Message... messages) {
        return call(new Prompt(java.util.Arrays.asList(messages))).getResult().getOutput().getText();
    }

    /** 本轮累计；ReAct 循环可能跑在虚拟线程上，加锁保稳。 */
    public synchronized UsageSnapshot snapshot() {
        return new UsageSnapshot(calls, promptTokens, completionTokens, totalTokens);
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
        return response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
    }
}

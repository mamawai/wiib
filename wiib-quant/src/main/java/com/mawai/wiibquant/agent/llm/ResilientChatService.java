package com.mawai.wiibquant.agent.llm;

import com.openai.errors.OpenAIInvalidDataException;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgentBuilder;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 带韧性的 ChatService：退避重试 + 可选兜底模型，装配进 langgraph4j 的 ReactAgent。
 * <p>
 * 落点选择：langgraph4j 的模型调用全部经 {@link ReactAgent.ChatService}，
 * {@code ReactAgent.builder().build(chatServiceFactory)} 允许换实现——重试/兜底放这一层，
 * 对图与节点完全透明。（原 spring-ai-alibaba 版本是 ModelInterceptor，同一套逻辑换了个挂载点。）
 * <p>
 * 韧性分层（两条路径职责不同，别再往回加）：
 * <ul>
 *   <li><b>阻塞 execute</b>：只兜底不重试。重试归模型层——ResponsesChatModel 自带退避、
 *       OpenAI 走 SDK 的 maxRetries；这里再来一轮就是 3×3=9 次，纯放大尾延迟。
 *       唯一豁免：{@link OpenAIInvalidDataException}（响应体读到一半被掐，HTTP/2 stream reset 等）
 *       是 SDK 重试的盲区——maxRetries 只管请求层（连接失败/429/5xx），读响应失败它不管，
 *       这一类单次重试，不与 SDK 叠乘（真跑一晚实测：一次 reset 废掉整轮唤醒还计入连败）</li>
 *   <li><b>流式 streamingExecute</b>：重试在这一层。模型层的流式路径不做重试，
 *       错误发生在订阅期只能在流水线上处理</li>
 * </ul>
 * 流式的两个细节：
 * <ul>
 *   <li>重试：冷流重订阅=重新发起请求；仅在尚未向下游吐出任何帧时重试（吐过帧再重订阅
 *       会让下游聚合器拼出重复文本），NonTransient（4xx 配置类错误）不重试</li>
 *   <li>兜底：重试耗尽且未吐帧 → 无缝接兜底模型的流（token 流不断）；
 *       已吐帧则错误透传，交上层 SSE error，用户重发</li>
 * </ul>
 */
@Slf4j
public class ResilientChatService implements ReactAgent.ChatService {

    private final ChatModel primaryModel;
    /** 可空：null=纯重试，非空=重试耗尽后切兜底。生产侧现在一律 null——没有一处调 builder 的 fallbackModel，只有测试在覆盖切兜底这条路 */
    private final ChatModel fallbackModel;
    private final int maxAttempts;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final ChatOptions chatOptions;
    private final SystemMessage systemMessage;

    private ResilientChatService(Builder builder, ReactAgentBuilder<?, ?> agentBuilder) {
        this.primaryModel = builder.primaryModel;
        this.fallbackModel = builder.fallbackModel;
        this.maxAttempts = builder.maxAttempts;
        this.initialDelayMs = builder.initialDelayMs;
        this.maxDelayMs = builder.maxDelayMs;
        // 工具挂进 options（与框架 DefaultChatService 同构）：没工具的 agent 保持 null 走模型默认
        this.chatOptions = agentBuilder.tools().isEmpty()
                || !(primaryModel.getOptions() instanceof ToolCallingChatOptions toolOptions)
                ? null
                : optionsWithTools(toolOptions, agentBuilder, builder.forceFirstToolChoice);
        this.systemMessage = SystemMessage.builder()
                .text(agentBuilder.systemMessage().orElse("You are a helpful AI Assistant answering questions."))
                .build();
    }

    /**
     * 强制首轮工具调用的信号键，经 toolContext 捎给 {@link ResponsesChatModel}。
     * 走 options 而不是模型构造参数：同一个 ChatModel 实例被多个 agent 共用，
     * 只有"这个 agent 必须先拿真实数据"是 agent 自己的属性。
     */
    public static final String FORCE_FIRST_TOOL_CHOICE = "wiib_force_first_tool_choice";

    /**
     * 每次调用都强制用工具的信号键，给"单次结构化调用"用（如路由节点：要的就是一个 tool_call）。
     * 与 {@link #FORCE_FIRST_TOOL_CHOICE} 的区别：后者只管首轮，因为 ReactAgent 是循环，
     * 拿到工具结果后必须放开否则收不了尾；单次调用没有这个顾虑，且不能被"历史里有工具消息"误判成非首轮。
     */
    public static final String FORCE_TOOL_CHOICE = "wiib_force_tool_choice";

    public static Builder builder() {
        return new Builder();
    }

    private static ChatOptions optionsWithTools(ToolCallingChatOptions source,
                                                ReactAgentBuilder<?, ?> agentBuilder, String forceFirstToolChoice) {
        var options = source.mutate().toolCallbacks(agentBuilder.tools());
        if (forceFirstToolChoice != null) {
            // 没设过工具上下文时 getToolContext() 给的是 null，不是空 Map
            Map<String, Object> context = source.getToolContext() == null
                    ? new HashMap<>() : new HashMap<>(source.getToolContext());
            context.put(FORCE_FIRST_TOOL_CHOICE, forceFirstToolChoice);
            options.toolContext(context);
        }
        return options.build();
    }

    @Override
    public ChatModel chatModel() {
        return primaryModel;
    }

    @Override
    public Optional<ChatOptions> chatOptions() {
        return Optional.ofNullable(chatOptions);
    }

    @Override
    public Flux<ChatResponse> streamingExecute(List<Message> messages) {
        List<Message> withSystem = withSystem(messages);
        AtomicBoolean emitted = new AtomicBoolean(false);
        return primaryModel.stream(promptOf(withSystem, chatOptions))
                .doOnNext(r -> emitted.set(true))
                .retryWhen(Retry.backoff(maxAttempts - 1, Duration.ofMillis(initialDelayMs))
                        .maxBackoff(Duration.ofMillis(maxDelayMs))
                        .filter(e -> !emitted.get() && !(e instanceof NonTransientAiException))
                        .doBeforeRetry(signal -> log.warn("模型流式调用失败，退避重试 {}/{}: {}",
                                signal.totalRetries() + 2, maxAttempts, String.valueOf(signal.failure())))
                        // 耗尽时抛原始异常而非 RetryExhausted 包装，让下面的兜底拿到真实原因
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .onErrorResume(e -> {
                    if (fallbackModel == null || emitted.get()) {
                        return Flux.error(e);
                    }
                    log.warn("主模型流式调用失败（已重试），切换兜底模型: {}", e.toString());
                    return fallbackModel.stream(promptOf(withSystem, fallbackOptions()));
                });
    }

    /**
     * 阻塞路径只做兜底，不重试——重试是模型层的职责（ResponsesChatModel 自带退避、
     * OpenAI 走 SDK 的 maxRetries）。这里再来一轮会叠乘成 3×3=9 次，纯粹放大尾延迟。
     * 流式路径相反：模型层不重试，重试全在下面的 streamingExecute 里。
     */
    @Override
    public ChatResponse execute(List<Message> messages) {
        List<Message> withSystem = withSystem(messages);
        try {
            return callPrimary(withSystem);
        } catch (RuntimeException e) {
            if (fallbackModel == null) {
                throw e;
            }
            log.warn("主模型调用失败（模型层已重试过），切换兜底模型: {}", e.toString());
            return fallbackModel.call(promptOf(withSystem, fallbackOptions()));
        }
    }

    /** SDK 重试盲区的单次补救：读响应失败（类头注释的唯一豁免）再试一次，其余异常原样抛。 */
    private ChatResponse callPrimary(List<Message> withSystem) {
        try {
            return primaryModel.call(promptOf(withSystem, chatOptions));
        } catch (OpenAIInvalidDataException e) {
            log.warn("响应读取中断（SDK 不重试此类失败），单次重试: {}", e.toString());
            return primaryModel.call(promptOf(withSystem, chatOptions));
        }
    }

    private List<Message> withSystem(List<Message> messages) {
        List<Message> withSystem = new ArrayList<>(messages.size() + 1);
        withSystem.add(systemMessage);
        withSystem.addAll(messages);
        return withSystem;
    }

    private Prompt promptOf(List<Message> messages, ChatOptions options) {
        return Prompt.builder().messages(messages)
                .chatOptions(options != null ? options : primaryModel.getOptions())
                .build();
    }

    /**
     * 兜底调用的 options：只保留工具语义——model/temperature 等生成参数必须归兜底模型自己的默认，
     * 原样透传会把主模型的 model 名打到兜底端点上。
     */
    private ChatOptions fallbackOptions() {
        if (!(chatOptions instanceof ToolCallingChatOptions source)) {
            return null;
        }
        return ToolCallingChatOptions.builder()
                .toolCallbacks(source.getToolCallbacks())
                .toolContext(source.getToolContext())
                .build();
    }

    public static class Builder {

        private ChatModel primaryModel;
        private ChatModel fallbackModel;
        private int maxAttempts = 3;
        private long initialDelayMs = 500;
        private long maxDelayMs = 4000;
        private String forceFirstToolChoice;

        /**
         * 首轮强制用工具（"required" 或具体工具名）。给"必须拿真实数据"的专家用：
         * 模型多半自带联网/搜索等内置能力，tool_choice=auto 时会绕开挂上去的工具自己答。
         * 只作用于首轮，拿到工具结果后恢复 auto，否则模型收不了尾。
         */
        public Builder forceFirstToolChoice(String forceFirstToolChoice) {
            this.forceFirstToolChoice = forceFirstToolChoice;
            return this;
        }

        public Builder model(ChatModel primaryModel) {
            this.primaryModel = primaryModel;
            return this;
        }

        public Builder fallbackModel(ChatModel fallbackModel) {
            this.fallbackModel = fallbackModel;
            return this;
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder initialDelay(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
            return this;
        }

        public Builder maxDelay(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
            return this;
        }

        /** 交给 {@code ReactAgent.Builder#build(factory)}：建图时框架回传 agentBuilder 取工具与系统提示。 */
        public java.util.function.Function<ReactAgentBuilder<?, ?>, ReactAgent.ChatService> asFactory() {
            return agentBuilder -> new ResilientChatService(this, agentBuilder);
        }
    }
}

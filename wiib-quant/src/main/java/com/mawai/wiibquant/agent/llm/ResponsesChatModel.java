package com.mawai.wiibquant.agent.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses API（/v1/responses）协议的 ChatModel 实现。
 * <p>
 * 为什么自研：Spring AI 1.1.x 的 OpenAiChatModel 只会说 /v1/chat/completions；
 * Grok Build（经 CPA）/OpenAI 官方思考模型的原生协议是 Responses，走原生协议才能带 reasoning.effort 控思考档位。
 * <p>
 * 与框架的契约（langgraph4j + Spring AI 2.0）：
 * <ul>
 *   <li>本类只负责"说"：返回带 toolCalls 的 AssistantMessage、接受 ToolResponseMessage 入参。
 *       工具一律由图的 ExecuteToolsAction 执行——Spring AI 2.0 已从 ChatModel 层移除内部工具执行</li>
 *   <li>CallModelAction 走流式（streaming=true），Summarization 等走阻塞 call()</li>
 *   <li>流式帧由 StreamingChatGenerator 聚合：每帧只发增量文本，工具调用在 item 完成时整只发一次</li>
 * </ul>
 * 无状态模式（不回传加密思考块）：流式聚合会重建消息丢 metadata，跨轮思考复用在此框架下不可行，
 * 每轮思考开销由 reasoning.effort 封顶。
 */
@Slf4j
public class ResponsesChatModel implements ChatModel {

    /** 阻塞调用超时；openai 协议路径（AiAgentRuntimeManager）引用同一常量对齐——思考模型长回答，官方默认 60s 不够 */
    public static final Duration CALL_TIMEOUT = Duration.ofMinutes(10);
    /** SSE 相邻事件最大间隔：防半开连接把消费方永久挂死（behavior 的 blockLast 会占死信号量）。
     *  取 5 分钟是给 high 档长思考的静默期留余量——多数服务端思考期间也会发 reasoning 事件/keepalive 注释行，都算心跳 */
    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofMinutes(5);

    private final WebClient webClient;
    private final String model;
    private final Double temperature;
    /** 思考档位 none/low/medium/high；null=不传走模型默认（来自 DB 配置行，非请求级） */
    private final String reasoningEffort;
    private final ToolCallingManager toolCallingManager;

    /** 瞬时错误重试参数，与 ResilientModelInterceptor 对齐 */
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 500;
    private static final long MAX_BACKOFF_MS = 4000;

    public ResponsesChatModel(String apiKey, String baseUrl, String model, Double temperature,
                              String reasoningEffort, ToolCallingManager toolCallingManager) {
        this.model = model;
        this.temperature = temperature;
        this.reasoningEffort = reasoningEffort;
        this.toolCallingManager = toolCallingManager;
        // 深研判单次回包可达数百KB，默认256KB codec上限不够
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /**
     * Spring AI 2.0 的契约方法是 getOptions()，getDefaultOptions() 已退化为它的转发别名。
     * 覆写成旧名会命中接口默认实现（返回普通 ChatOptions 而非 ToolCallingChatOptions），
     * ResilientChatService 构造时 instanceof 恒假 → 专家/汇总的工具全程挂不上（真跑实证过）。
     */
    @Override
    public @NonNull ChatOptions getOptions() {
        ToolCallingChatOptions.Builder<?> builder = ToolCallingChatOptions.builder().model(model);
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder.build();
    }

    // ========== 阻塞调用 ==========

    @Override
    public @NonNull ChatResponse call(@NonNull Prompt prompt) {
        // 只负责"说"：带 toolCalls 的 AssistantMessage 原样返回，工具由图的 ExecuteToolsAction 执行。
        // Spring AI 2.0 起 ChatModel 层不再做内部工具执行（internalToolExecutionEnabled 已移除）
        return callWithRetry(prompt);
    }

    /**
     * 瞬时错误退避重试。Spring AI 2.0 起彻底弃用 spring-retry（RetryTemplate 已无处可取），
     * 逻辑内聚在此——只重试 {@link TransientAiException}(429/5xx)，配置类错误重试也没用。
     */
    private ChatResponse callWithRetry(Prompt prompt) {
        TransientAiException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return doCall(prompt);
            } catch (TransientAiException e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    sleepBackoff(attempt);
                }
            }
        }
        throw last;
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(Math.min(INITIAL_BACKOFF_MS << (attempt - 1), MAX_BACKOFF_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试退避等待被中断", e);
        }
    }

    private ChatResponse doCall(Prompt prompt) {
        JSONObject body = buildRequestBody(prompt, false);
        String raw = webClient.post()
                .uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString())
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::isError, resp ->
                        resp.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(errBody -> Mono.error(toApiException(resp.statusCode().value(), errBody))))
                .bodyToMono(String.class)
                .block(CALL_TIMEOUT);
        if (raw == null || raw.isBlank()) {
            throw new NonTransientAiException("Responses API 返回空响应");
        }
        return parseResponse(JSON.parseObject(raw));
    }

    // ========== 流式调用 ==========

    @Override
    public @NonNull Flux<ChatResponse> stream(@NonNull Prompt prompt) {
        // 纯透传，帧到即发——工具执行归图，本层不攒帧，工作台 token 实时性不受影响
        return streamOnce(prompt);
    }

    /**
     * 单轮 SSE：增量文本逐帧发（供工作台 token 流），工具调用在 output_item.done 整只发一帧，
     * response.completed 发带 usage 的收尾帧。
     */
    private Flux<ChatResponse> streamOnce(Prompt prompt) {
        JSONObject body = buildRequestBody(prompt, true);
        // state 必须每次订阅新建：外层 ResilientModelInterceptor 靠重订阅实现重试，共享 state 会污染收尾帧判断
        return Flux.defer(() -> {
            StreamState state = new StreamState();
            return webClient.post()
                .uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body.toString())
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::isError, resp ->
                        resp.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(errBody -> Mono.error(toApiException(resp.statusCode().value(), errBody))))
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .timeout(STREAM_IDLE_TIMEOUT)
                .onErrorMap(java.util.concurrent.TimeoutException.class, _ ->
                        new TransientAiException("Responses SSE 空闲超过 " + STREAM_IDLE_TIMEOUT.toMinutes() + " 分钟，判定连接挂死"))
                .concatMap(sse -> toFrames(sse, state));
        });
    }

    /** SSE 事件流的累计状态：判定收尾帧的 finishReason、无增量服务端的兜底 */
    private static class StreamState {
        boolean sawText;
        boolean sawToolCall;
    }

    private Flux<ChatResponse> toFrames(ServerSentEvent<String> sse, StreamState state) {
        String data = sse.data();
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
            return Flux.empty();
        }
        JSONObject event = JSON.parseObject(data);
        // 事件类型以 data.type 为准（比 event: 行更普适，CPA/OpenAI 都带）
        String type = event.getString("type");
        if (type == null) {
            return Flux.empty();
        }
        switch (type) {
            case "response.output_text.delta" -> {
                String delta = event.getString("delta");
                if (delta == null || delta.isEmpty()) {
                    return Flux.empty();
                }
                state.sawText = true;
                return Flux.just(textFrame(delta));
            }
            case "response.output_item.done" -> {
                JSONObject item = event.getJSONObject("item");
                if (item == null || !"function_call".equals(item.getString("type"))) {
                    return Flux.empty();
                }
                state.sawToolCall = true;
                return Flux.just(toolCallFrame(parseToolCall(item)));
            }
            case "response.completed" -> {
                JSONObject response = event.getJSONObject("response");
                List<ChatResponse> frames = new ArrayList<>();
                // 兜底：不发增量事件的服务端，从完整响应里补全文（正常流式两标志必有其一，不会走到）
                if (!state.sawText && !state.sawToolCall && response != null) {
                    String fullText = extractOutputText(response.getJSONArray("output"));
                    if (!fullText.isEmpty()) {
                        frames.add(textFrame(fullText));
                    }
                }
                frames.add(finalFrame(state.sawToolCall, response));
                return Flux.fromIterable(frames);
            }
            case "response.failed", "response.incomplete" -> {
                String message = event.getJSONObject("response") != null
                        ? extractErrorMessage(event.getJSONObject("response"))
                        : type;
                return Flux.error(new NonTransientAiException("Responses 流式失败: " + message));
            }
            case "error" -> {
                return Flux.error(new NonTransientAiException(
                        "Responses 流式错误: " + event.getString("message")));
            }
            // reasoning 摘要、arguments 增量等事件不进正文流，忽略
            default -> {
                return Flux.empty();
            }
        }
    }

    private ChatResponse textFrame(String delta) {
        AssistantMessage message = AssistantMessage.builder().content(delta).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ChatResponse toolCallFrame(AssistantMessage.ToolCall toolCall) {
        AssistantMessage message = AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ChatResponse finalFrame(boolean sawToolCall, JSONObject response) {
        AssistantMessage message = AssistantMessage.builder().content("").build();
        Generation generation = new Generation(message, ChatGenerationMetadata.builder()
                .finishReason(sawToolCall ? "TOOL_CALLS" : "STOP").build());
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().model(model);
        if (response != null && response.getJSONObject("usage") != null) {
            metadata.usage(parseUsage(response.getJSONObject("usage")));
        }
        return new ChatResponse(List.of(generation), metadata.build());
    }


    /** 帧合并（仅内部工具执行分支用）：拼文本、收工具调用，成一个完整 ChatResponse 供 executeToolCalls */

    // ========== 请求构建 ==========

    private JSONObject buildRequestBody(Prompt prompt, boolean stream) {
        JSONObject body = new JSONObject();
        ChatOptions options = prompt.getOptions();

        body.put("model", options != null && options.getModel() != null ? options.getModel() : model);
        Double temp = options != null && options.getTemperature() != null ? options.getTemperature() : temperature;
        if (temp != null) {
            body.put("temperature", temp);
        }
        if (reasoningEffort != null) {
            body.put("reasoning", new JSONObject().fluentPut("effort", reasoningEffort));
        }
        // 无状态：不让服务端存会话（历史我们每轮全量带），OpenAI 官方 store 默认 true 必须显式关
        body.put("store", false);
        body.put("stream", stream);

        // system 消息进 instructions（Responses 惯例），其余按序转 input items
        StringBuilder instructions = new StringBuilder();
        JSONArray input = new JSONArray();
        for (Message message : prompt.getInstructions()) {
            switch (message.getMessageType()) {
                case SYSTEM -> {
                    if (!instructions.isEmpty()) {
                        instructions.append("\n\n");
                    }
                    instructions.append(message.getText());
                }
                case USER -> input.add(messageItem("user", "input_text", message.getText()));
                case ASSISTANT -> {
                    AssistantMessage assistant = (AssistantMessage) message;
                    if (assistant.getText() != null && !assistant.getText().isBlank()) {
                        input.add(messageItem("assistant", "output_text", assistant.getText()));
                    }
                    // 历史工具调用重建为 function_call item——function_call_output 必须有配对的调用项
                    for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
                        input.add(new JSONObject()
                                .fluentPut("type", "function_call")
                                .fluentPut("call_id", tc.id())
                                .fluentPut("name", tc.name())
                                .fluentPut("arguments", tc.arguments()));
                    }
                }
                case TOOL -> {
                    for (ToolResponseMessage.ToolResponse tr : ((ToolResponseMessage) message).getResponses()) {
                        input.add(new JSONObject()
                                .fluentPut("type", "function_call_output")
                                .fluentPut("call_id", tr.id())
                                .fluentPut("output", tr.responseData()));
                    }
                }
            }
        }
        if (!instructions.isEmpty()) {
            body.put("instructions", instructions.toString());
        }
        body.put("input", input);

        // 工具定义：Responses 是扁平结构（name 在顶层，不像 completions 嵌在 function 下）
        if (options instanceof ToolCallingChatOptions toolOptions) {
            List<ToolDefinition> definitions = toolCallingManager.resolveToolDefinitions(toolOptions);
            if (!definitions.isEmpty()) {
                JSONArray tools = new JSONArray();
                for (ToolDefinition def : definitions) {
                    tools.add(new JSONObject()
                            .fluentPut("type", "function")
                            .fluentPut("name", def.name())
                            .fluentPut("description", def.description())
                            .fluentPut("parameters", JSON.parseObject(def.inputSchema())));
                }
                body.put("tools", tools);
                // 是否强制用工具：模型自带联网/搜索等内置能力，auto 下不保证用挂上去的工具，
                // 数据源必须可控的场景用强制兜住（代码级保证，不赌模型自觉）。两种强制语义不能混：
                //   always —— 每次都强制。单次结构化调用用（router 要的就是一个 tool_call）
                //   first  —— 只强制首轮。ReactAgent 循环用，拿到工具结果后必须放开才收得了尾；
                //             判据是"最后一条用户消息之后还没有 ToolResponseMessage"。
                //             只看这一段：summarizer 用过深研判工具后 TRM 会随会话历史落库、
                //             下一轮又被喂给专家，扫全历史会让之后每个专家的首轮都被误判成非首轮
                // 没设过工具上下文时 getToolContext() 给的是 null（summarizer 就是这种）
                Map<String, Object> toolContext = toolOptions.getToolContext();
                Object always = toolContext == null ? null : toolContext.get(ResilientChatService.FORCE_TOOL_CHOICE);
                Object first = toolContext == null ? null : toolContext.get(ResilientChatService.FORCE_FIRST_TOOL_CHOICE);
                boolean firstTurn = isFirstTurn(prompt.getInstructions());
                body.put("tool_choice", always != null ? always.toString()
                        : (first != null && firstTurn ? first.toString() : "auto"));
            }
        }
        // 请求侧证据日志，与响应侧 toolCalls 日志对称：排"模型不调工具"先看这——
        // tool_choice=null 即压根没发工具定义，required/auto 则是强制与否的实据
        JSONArray toolsOut = body.getJSONArray("tools");
        log.info("[Responses] 请求 model={} stream={} tool_choice={} tools={}",
                body.getString("model"), stream, body.getString("tool_choice"),
                toolsOut == null ? List.of() : toolsOut.stream()
                        .map(t -> ((JSONObject) t).getString("name")).toList());
        return body;
    }

    /**
     * 首轮判定：最后一条用户消息之后没有工具回执才算首轮。
     * 只看这一段而非全历史——summarizer 用过深研判工具后 ToolResponseMessage 会随会话历史落库、
     * 下一轮原样喂给专家，扫全历史会让之后每个专家的首轮强制全部失效。
     */
    static boolean isFirstTurn(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m instanceof ToolResponseMessage) {
                return false;
            }
            if (m instanceof UserMessage) {
                return true;
            }
        }
        return true;
    }

    private JSONObject messageItem(String role, String contentType, String text) {
        return new JSONObject()
                .fluentPut("type", "message")
                .fluentPut("role", role)
                .fluentPut("content", new JSONArray().fluentAdd(new JSONObject()
                        .fluentPut("type", contentType)
                        .fluentPut("text", text == null ? "" : text)));
    }

    // ========== 响应解析 ==========

    ChatResponse parseResponse(JSONObject response) {
        String status = response.getString("status");
        if ("failed".equals(status)) {
            throw new NonTransientAiException("Responses API 失败: " + extractErrorMessage(response));
        }
        // incomplete = 服务端截断（多为 max_output_tokens 到顶），output 里只有半截正文。
        // 当正常收尾发 STOP 的话，被腰斩的【本轮结论】会以 status=OK 落库，下一轮还被当
        // "上一轮的承诺"回注给模型做检验基准。与流式路径（response.incomplete）同口径判失败
        if ("incomplete".equals(status)) {
            throw new NonTransientAiException("Responses API 截断: " + extractErrorMessage(response));
        }
        JSONArray output = response.getJSONArray("output");
        String text = extractOutputText(output);
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        if (output != null) {
            for (int i = 0; i < output.size(); i++) {
                JSONObject item = output.getJSONObject(i);
                if ("function_call".equals(item.getString("type"))) {
                    toolCalls.add(parseToolCall(item));
                }
            }
        }

        // 工具是否真被调用：toolCalls 空=模型自己答的（内置搜索/记忆），没走我们挂上去的工具
        log.info("[Responses] {} toolCalls={} 文本{}字", model,
                toolCalls.stream().map(AssistantMessage.ToolCall::name).toList(), text.length());

        AssistantMessage message = AssistantMessage.builder().content(text).toolCalls(toolCalls).build();
        Generation generation = new Generation(message, ChatGenerationMetadata.builder()
                .finishReason(toolCalls.isEmpty() ? "STOP" : "TOOL_CALLS").build());
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
                .id(response.getString("id"))
                .model(response.getString("model") != null ? response.getString("model") : model);
        if (response.getJSONObject("usage") != null) {
            metadata.usage(parseUsage(response.getJSONObject("usage")));
        }
        return new ChatResponse(List.of(generation), metadata.build());
    }

    private String extractOutputText(JSONArray output) {
        if (output == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < output.size(); i++) {
            JSONObject item = output.getJSONObject(i);
            if (!"message".equals(item.getString("type"))) {
                continue;
            }
            JSONArray content = item.getJSONArray("content");
            if (content == null) {
                continue;
            }
            for (int j = 0; j < content.size(); j++) {
                JSONObject part = content.getJSONObject(j);
                if ("output_text".equals(part.getString("type")) && part.getString("text") != null) {
                    text.append(part.getString("text"));
                }
            }
        }
        return text.toString();
    }

    private AssistantMessage.ToolCall parseToolCall(JSONObject item) {
        // call_id 是配对 function_call_output 的键；个别实现只给 id，兜底用它
        String callId = item.getString("call_id") != null ? item.getString("call_id") : item.getString("id");
        return new AssistantMessage.ToolCall(callId, "function",
                item.getString("name"), item.getString("arguments"));
    }

    private Usage parseUsage(JSONObject usage) {
        return new DefaultUsage(
                usage.getInteger("input_tokens"),
                usage.getInteger("output_tokens"),
                usage.getInteger("total_tokens"));
    }

    private String extractErrorMessage(JSONObject response) {
        JSONObject error = response.getJSONObject("error");
        if (error != null && error.getString("message") != null) {
            return error.getString("message");
        }
        JSONObject incomplete = response.getJSONObject("incomplete_details");
        if (incomplete != null) {
            return "incomplete: " + incomplete.getString("reason");
        }
        return "未知错误";
    }

    /** 429/5xx 归为瞬时（callWithRetry 会重试），其余 4xx 直接失败——配置错误重试也没用 */
    private RuntimeException toApiException(int status, String body) {
        String message = "Responses API HTTP " + status + ": " + (body.length() > 500 ? body.substring(0, 500) : body);
        if (status == 429 || status >= 500) {
            return new TransientAiException(message);
        }
        return new NonTransientAiException(message);
    }
}

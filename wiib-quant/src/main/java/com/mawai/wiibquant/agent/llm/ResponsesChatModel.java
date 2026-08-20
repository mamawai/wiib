package com.mawai.wiibquant.agent.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
    /** SSE 首事件超时：正常渠道 response.created 秒级就到，首帧不涉及思考静默，60s 已是极宽松的界；
     *  挂死＝零字节永远不来（grok 经 CPA 实测 10 分钟静默，唯一终止条件是我们掐线），这一层专拦它 */
    private static final Duration FIRST_EVENT_TIMEOUT = Duration.ofSeconds(60);

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
        // 深研判单次回包可达数百KB，默认256KB codec上限不够。
        // baseUrl 过 forResponses 剥掉手滑带上的 /v1——与 openai 协议路的 forSdk 同等容忍
        this.webClient = WebClient.builder()
                .baseUrl(OpenAiBaseUrl.forResponses(baseUrl))
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

    /**
     * 阻塞路径也走 SSE，收帧后合并——不用 stream=false 有两个原因：
     * ① 非流式下 CPA 这类网关读完上游才回包，连响应头都最后才到，"上游挂死"与"长思考"在
     *    字节层不可区分（grok 经 CPA 实测偶发 10 分钟零字节，只能干等 block 超时）；
     *    SSE 有首帧和帧间隔，挂死在 {@link #FIRST_EVENT_TIMEOUT} 内就能判定并进重试。
     * ② 与 stream() 共用 streamOnce 一条协议解析路：各类 BYOK 渠道的事件怪癖（畸形事件、
     *    无增量服务端、工具只在 output_item.done 发）只在 toFrames 一处处理，不因协议形态分叉。
     */
    private ChatResponse doCall(Prompt prompt) {
        List<ChatResponse> frames = streamOnce(prompt).collectList().block(CALL_TIMEOUT);
        return mergeFrames(frames);
    }

    /** 帧合并：拼文本、收工具调用，finishReason/usage 取收尾帧；没等到收尾帧＝上游断流，判瞬时可重试 */
    private ChatResponse mergeFrames(List<ChatResponse> frames) {
        ChatResponse last = frames == null || frames.isEmpty() ? null : frames.getLast();
        String finishReason = last == null ? null : last.getResult().getMetadata().getFinishReason();
        if (finishReason == null || finishReason.isBlank()) {
            // completed 没到流就终了（CPA 侧对应 408 stream disconnected）：连接级偶发，
            // 换个连接大概率就好，交给 callWithRetry；判 NonTransient 会让 trader 整轮唤醒白跑
            throw new TransientAiException("Responses SSE 断流：未收到 response.completed 就结束了");
        }
        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (ChatResponse frame : frames) {
            AssistantMessage output = frame.getResult().getOutput();
            if (output.getText() != null) {
                text.append(output.getText());
            }
            toolCalls.addAll(output.getToolCalls());
        }
        // 工具是否真被调用：toolCalls 空=模型自己答的（内置搜索/记忆），没走我们挂上去的工具
        log.info("[Responses] {} toolCalls={} 文本{}字", model,
                toolCalls.stream().map(AssistantMessage.ToolCall::name).toList(), text.length());
        AssistantMessage message = AssistantMessage.builder().content(text.toString()).toolCalls(toolCalls).build();
        Generation generation = new Generation(message, ChatGenerationMetadata.builder()
                .finishReason(finishReason).build());
        return new ChatResponse(List.of(generation), last.getMetadata());
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
                // 两段式超时：首帧管挂死（零字节永远不来），帧间管半开连接；都判瞬时进重试
                .timeout(Mono.delay(FIRST_EVENT_TIMEOUT), _ -> Mono.delay(STREAM_IDLE_TIMEOUT))
                .onErrorMap(java.util.concurrent.TimeoutException.class, _ ->
                        new TransientAiException("Responses SSE 首帧 " + FIRST_EVENT_TIMEOUT.toSeconds()
                                + "s 未到或相邻事件间隔超 " + STREAM_IDLE_TIMEOUT.toMinutes() + " 分钟，判定连接挂死"))
                .concatMap(sse -> toFrames(sse, state))
                // 整条流一个可解析事件都没有：单帧跳过是对的，但全跳过就等于把失败咽了——
                // 上游返回的根本不是我们认的 SSE，得当场说清楚，否则退化成静默空回答更难查
                .switchIfEmpty(Flux.defer(() -> state.sawMalformed
                        ? Flux.error(new NonTransientAiException(
                                "Responses SSE 全程无可解析事件，上游返回格式不兼容"))
                        : Flux.empty()));
        });
    }

    /** SSE 事件流的累计状态：判定收尾帧的 finishReason、无增量服务端的兜底 */
    private static class StreamState {
        boolean sawText;
        boolean sawToolCall;
        boolean sawMalformed;
    }

    private Flux<ChatResponse> toFrames(ServerSentEvent<String> sse, StreamState state) {
        String data = sse.data();
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
            return Flux.empty();
        }
        JSONObject event;
        try {
            event = JSON.parseObject(data);
        } catch (JSONException e) {
            // 不规范的网关会把 "event: response.created" 这类行原样当 data 发（线上实测）。
            // 解析不了就无从判断 type，跟下面 default 分支同构地忽略掉——真正的正文事件各有其行，
            // 不该被一个我们本来就不看的事件炸掉整轮。日志只打第一条，防畸形流刷屏
            if (!state.sawMalformed) {
                state.sawMalformed = true;
                log.warn("[Responses] SSE 事件非 JSON，已跳过 data={}",
                        data.length() > 200 ? data.substring(0, 200) + "…" : data);
            }
            return Flux.empty();
        }
        // 事件类型以 data.type 为准（比 event: 行更普适，CPA/OpenAI 都带）
        String type = event == null ? null : event.getString("type");
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
                // 简易网关会把非流式响应原样包成 completed 事件发出，payload 里的 status 可能
                // 仍是 failed/incomplete——事件类型说完成、payload 说截断时信 payload，
                // 别让半截【本轮结论】带着 STOP 落库（与独立 failed/incomplete 事件同口径）
                String status = response == null ? null : response.getString("status");
                if ("failed".equals(status) || "incomplete".equals(status)) {
                    return Flux.error(new NonTransientAiException(
                            "Responses 流式失败: " + extractErrorMessage(response)));
                }
                List<ChatResponse> frames = new ArrayList<>();
                // 兜底：不发增量事件的服务端，正文和工具调用都只在完整响应的 output 里
                // （正常流式两标志必有其一，不会走到）
                if (!state.sawText && !state.sawToolCall && response != null) {
                    JSONArray output = response.getJSONArray("output");
                    String fullText = extractOutputText(output);
                    if (!fullText.isEmpty()) {
                        frames.add(textFrame(fullText));
                    }
                    if (output != null) {
                        for (int i = 0; i < output.size(); i++) {
                            JSONObject item = output.getJSONObject(i);
                            if ("function_call".equals(item.getString("type"))) {
                                state.sawToolCall = true;   // finalFrame 据此标 TOOL_CALLS
                                frames.add(toolCallFrame(parseToolCall(item)));
                            }
                        }
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
        if (response != null) {
            metadata.id(response.getString("id"));
            if (response.getJSONObject("usage") != null) {
                metadata.usage(parseUsage(response.getJSONObject("usage")));
            }
        }
        return new ChatResponse(List.of(generation), metadata.build());
    }


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
                // 强不强制用工具由调用方按次决定（首轮强制/单次结构化调用），经 toolContext 捎进来（ToolChoice）：
                // 模型自带联网/搜索等内置能力，auto 下不保证用挂上去的工具，数据源必须可控的场景靠它兜住
                body.put("tool_choice", ToolChoice.of(toolOptions));
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

    private JSONObject messageItem(String role, String contentType, String text) {
        return new JSONObject()
                .fluentPut("type", "message")
                .fluentPut("role", role)
                .fluentPut("content", new JSONArray().fluentAdd(new JSONObject()
                        .fluentPut("type", contentType)
                        .fluentPut("text", text == null ? "" : text)));
    }

    // ========== 响应解析 ==========

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

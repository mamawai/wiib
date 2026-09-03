package com.mawai.wiibcommon.constant;

/** LLM 上游协议常量：一条配置一个协议，建模按此分叉请求格式 */
public final class AiProtocols {

    /** OpenAI Chat Completions（/v1/chat/completions），走 Spring AI OpenAiChatModel */
    public static final String OPENAI = "openai";

    /** OpenAI Responses（/v1/responses），自研 ResponsesChatModel */
    public static final String RESPONSES = "responses";

    /** Anthropic Messages（/v1/messages），自研 AnthropicChatModel */
    public static final String ANTHROPIC = "anthropic";

    /** Gemini generateContent（/v1beta/models/{model}:streamGenerateContent），自研 GeminiChatModel */
    public static final String GEMINI = "gemini";

    private AiProtocols() {
    }

    /** 抹平大小写与空白；空值按 openai 兜底（存量行无此列值时不打挂调用链） */
    public static String normalize(String protocol) {
        return protocol == null || protocol.isBlank() ? OPENAI : protocol.trim().toLowerCase();
    }

    public static boolean isValid(String protocol) {
        return switch (normalize(protocol)) {
            case OPENAI, RESPONSES, ANTHROPIC, GEMINI -> true;
            default -> false;
        };
    }

    /** 该协议能否在请求里声明服务端联网搜索工具；chat-completions 没有标准的服务端搜索 */
    public static boolean supportsServerSearch(String protocol) {
        return switch (normalize(protocol)) {
            case RESPONSES, ANTHROPIC, GEMINI -> true;
            default -> false;
        };
    }
}

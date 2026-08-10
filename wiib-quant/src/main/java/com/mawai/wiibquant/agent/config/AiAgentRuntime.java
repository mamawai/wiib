package com.mawai.wiibquant.agent.config;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 各功能位的 ChatModel 分配（DB 驱动，Admin 可热更）。
 * 只剩 behavior 一位——对话轨（原 quant / quant-light / chat 三位）已全量切用户自带 key。
 */
public record AiAgentRuntime(ChatModel behaviorChatModel) {
}

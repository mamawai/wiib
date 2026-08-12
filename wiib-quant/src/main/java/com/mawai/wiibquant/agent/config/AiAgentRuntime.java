package com.mawai.wiibquant.agent.config;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 各功能位的 ChatModel 分配（DB 驱动，Admin 可热更）。
 * behavior=行为分析；newsTagging=快讯打标（后台批量，内部调用不走用户 key），
 * 模型名跟着带出来——news_event.tagged_model 落库追责用。
 * 对话轨（原 quant / quant-light / chat 三位）已全量切用户自带 key。
 */
public record AiAgentRuntime(ChatModel behaviorChatModel,
                             ChatModel newsTaggingChatModel,
                             String newsTaggingModelName) {
}

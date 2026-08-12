package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.mapper.WorkbenchChatContextMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 会话模型侧上下文的存取口（替代 PostgresSaver checkpoint）：一会话一行，整体替换。
 * <p>
 * 存的是每轮结束时 summarizer 叶子的最终 messages——含专家结论、工具调用配对、
 * 压缩后的摘要状态。压缩结果必须落下来：不落的话下一轮超阈值又重压一遍，白烧轻模型的钱。
 * <p>
 * 序列化复用叶子 agent 同一个 {@link StateSerializer}（Jackson 版）：Spring AI Message
 * 全族的多态往返它已经打通，tool_call/tool_response 配对不丢——自己另写一套只会踩一遍老坑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatContextStore {

    private final WorkbenchChatContextMapper contextMapper;
    private final StateSerializer<MessagesState<Message>> stateSerializer;

    /**
     * 取会话历史。无行（新会话）返回空；读失败也返回空——降级成"这轮没有上下文"，
     * 对话还能继续，比整轮拒绝服务强。降级有代价（模型忘了之前聊的），所以是 error 级日志。
     */
    public List<Message> load(String sessionId) {
        byte[] bytes;
        try {
            bytes = contextMapper.selectState(sessionId);
        } catch (Exception e) {
            log.error("[ChatContext] 上下文读取失败，本轮无历史续跑 sessionId={}", sessionId, e);
            return List.of();
        }
        if (bytes == null) {
            return List.of();
        }
        try {
            return stateSerializer.stateOf(stateSerializer.dataFromBytes(bytes)).messages();
        } catch (Exception e) {
            log.error("[ChatContext] 上下文反序列化失败，本轮无历史续跑 sessionId={}", sessionId, e);
            return List.of();
        }
    }

    /**
     * 整体覆盖会话历史。失败只记日志不抛：调用点在答案已经流给用户之后，
     * 抛上去救不回本轮，只会把成功的回答标成失败；代价是下一轮丢这轮的上下文。
     */
    public void save(String sessionId, long userId, List<Message> messages) {
        try {
            byte[] bytes = stateSerializer.dataToBytes(Map.of("messages", messages));
            contextMapper.upsert(sessionId, userId, bytes);
        } catch (Exception e) {
            log.error("[ChatContext] 上下文落库失败，下一轮将丢失本轮历史 sessionId={}", sessionId, e);
        }
    }

    /** 删会话时清上下文。尽力清：失败只影响存储占用，不该让"列表里已删"的观感落空。 */
    public void purge(String sessionId) {
        try {
            contextMapper.deleteBySessionId(sessionId);
        } catch (Exception e) {
            log.warn("[ChatContext] 上下文删除失败 sessionId={} msg={}", sessionId, e.toString());
        }
    }
}

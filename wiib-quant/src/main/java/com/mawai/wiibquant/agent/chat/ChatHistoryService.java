package com.mawai.wiibquant.agent.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.WorkbenchChatMessage;
import com.mawai.wiibquant.mapper.WorkbenchChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 工作台对话历史（展示用）：user/assistant 消息按会话落库，支撑历史会话列表与回看。
 * 续聊上下文不靠它——那是 {@link ChatContextStore} 的事（存的是模型侧完整 messages）；
 * 本表只为前端展示，所以 agent 调度/HITL 过程事件不存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private static final int TITLE_MAX = 40;

    private final WorkbenchChatMessageMapper messageMapper;

    /** 会话摘要：标题=首条用户消息截断。 */
    public record SessionSummary(String sessionId, String title, int messageCount, long lastAt) {}

    public record ChatMessage(String role, String content, long createdAt) {}

    /** 追加一条消息。历史是增益不是主链，失败只记日志不打断对话。 */
    public void append(String sessionId, long userId, String role, String content) {
        if (content == null || content.isBlank()) return;
        try {
            WorkbenchChatMessage row = new WorkbenchChatMessage();
            row.setSessionId(sessionId);
            row.setUserId(userId);
            row.setRole(role);
            row.setContent(content);
            // createdAt 由全局 MetaObjectHandler 填，不手塞
            messageMapper.insert(row);
        } catch (Exception e) {
            log.warn("[ChatHistory] 写入失败 sessionId={}", sessionId, e);
        }
    }

    /** 我的会话列表，按最后活跃倒序。标题在这截——省略号规则属展示逻辑，不进 SQL。 */
    public List<SessionSummary> sessions(long userId, int limit) {
        return messageMapper.selectSessions(userId, limit).stream()
                .map(row -> new SessionSummary(
                        row.getSessionId(),
                        truncate(row.getTitle()),
                        row.getMessageCount(),
                        toEpochMillis(row.getLastAt())))
                .toList();
    }

    /** 删除整个会话的展示记录，调用方已做归属校验。 */
    public void deleteSession(String sessionId) {
        messageMapper.delete(new LambdaQueryWrapper<WorkbenchChatMessage>()
                .eq(WorkbenchChatMessage::getSessionId, sessionId));
    }

    /** 单会话全部消息（按 id 升序＝发生顺序），调用方已做归属校验。 */
    public List<ChatMessage> messages(String sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<WorkbenchChatMessage>()
                        .eq(WorkbenchChatMessage::getSessionId, sessionId)
                        .orderByAsc(WorkbenchChatMessage::getId))
                .stream()
                .map(row -> new ChatMessage(row.getRole(), row.getContent(), toEpochMillis(row.getCreatedAt())))
                .toList();
    }

    private static String truncate(String s) {
        if (s == null || s.isBlank()) return "（无标题）";
        return s.length() > TITLE_MAX ? s.substring(0, TITLE_MAX) + "…" : s;
    }

    /**
     * 前端契约是毫秒时间戳。列是不带时区的 timestamp，按本机时区还原成 epoch——
     * 这跟原先 {@code ResultSet.getTimestamp().getTime()} 的口径完全一致（JDBC 也是按 JVM 默认时区解释钟面时间）。
     */
    private static long toEpochMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}

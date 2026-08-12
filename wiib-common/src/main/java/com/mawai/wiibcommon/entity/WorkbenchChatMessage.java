package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作台对话历史（展示用）：user/assistant 消息按会话落库，支撑历史会话列表与回看。
 * <p>
 * 续聊上下文不靠它——那是 workbench_chat_context（模型侧完整消息历史）的事，sessionId 同值；
 * 本表只为前端展示，所以 agent 调度/HITL 过程事件不存。
 */
@Data
@TableName("workbench_chat_message")
public class WorkbenchChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话号，形如 wb-{userId}-{uuid}；与 workbench_chat_context.session_id 同值 */
    private String sessionId;

    private Long userId;

    /** user=用户提问 assistant=最终答案 */
    private String role;

    private String content;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

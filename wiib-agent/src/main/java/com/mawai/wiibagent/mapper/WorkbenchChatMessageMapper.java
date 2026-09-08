package com.mawai.wiibagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.dto.WorkbenchSessionRow;
import com.mawai.wiibcommon.entity.WorkbenchChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工作台对话历史读写。追加/删除/单会话回看都够用内置方法，只有会话列表要聚合，落在这里。
 * <p>
 * 聚合列一律写 camelCase 别名（{@code COUNT(*) AS messageCount}）而不是靠下划线转驼峰：
 * PostgreSQL 会把不带引号的别名折成小写，MyBatis 再做大小写不敏感匹配，两种映射开关下都对。
 */
@Mapper
public interface WorkbenchChatMessageMapper extends BaseMapper<WorkbenchChatMessage> {

    /**
     * 我的会话列表，按最后活跃倒序。标题走相关子查询取本会话首条用户消息，
     * 比先查出 session_id 再逐个回表少一轮往返。
     * <p>
     * ORDER BY 直接重写 MAX() 而不引用别名：PG 虽允许 ORDER BY 输出列名，
     * 但别名大小写折叠这层规则没必要在排序上再赌一次。
     */
    @Select("""
            SELECT m.session_id AS sessionId,
                   (SELECT f.content FROM workbench_chat_message f
                     WHERE f.session_id = m.session_id AND f.role = 'user'
                     ORDER BY f.id LIMIT 1) AS title,
                   COUNT(*)           AS messageCount,
                   MAX(m.created_at)  AS lastAt
              FROM workbench_chat_message m
             WHERE m.user_id = #{userId}
             GROUP BY m.session_id
             ORDER BY MAX(m.created_at) DESC
             LIMIT #{limit}
            """)
    List<WorkbenchSessionRow> selectSessions(@Param("userId") long userId, @Param("limit") int limit);

    /** 我的全部会话号（清空全部会话时逐个删要用），不排序不限量 */
    @Select("SELECT DISTINCT session_id FROM workbench_chat_message WHERE user_id = #{userId}")
    List<String> selectSessionIds(@Param("userId") long userId);
}

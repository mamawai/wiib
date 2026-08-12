package com.mawai.wiibquant.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 工作台会话模型侧上下文（续聊主链）：一会话一行，state 是序列化后的完整消息历史。
 * <p>
 * 刻意不 extends BaseMapper：主键是业务串 session_id，selectById 那套用不上；
 * 也不建 entity——就 byte[] 进出，包一层对象纯属摆设。
 */
@Mapper
public interface WorkbenchChatContextMapper {

    /** 整体替换：每轮对话结束把最新历史整份覆盖上去，没有增量语义。 */
    @Insert("""
            INSERT INTO workbench_chat_context (session_id, user_id, state, updated_at)
            VALUES (#{sessionId}, #{userId}, #{state}, now())
            ON CONFLICT (session_id) DO UPDATE SET
                state      = EXCLUDED.state,
                updated_at = now()
            """)
    int upsert(@Param("sessionId") String sessionId, @Param("userId") long userId,
               @Param("state") byte[] state);

    /** 无行返回 null（新会话/已删会话）。 */
    @Select("SELECT state FROM workbench_chat_context WHERE session_id = #{sessionId}")
    byte[] selectState(@Param("sessionId") String sessionId);

    @Delete("DELETE FROM workbench_chat_context WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}

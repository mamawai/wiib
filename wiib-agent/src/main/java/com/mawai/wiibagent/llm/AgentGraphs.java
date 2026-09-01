package com.mawai.wiibagent.llm;

import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;

/**
 * ReactAgent 建图的统一入口：全仓四个建图点（chat 的专家与 summarizer 叶子、trader 唤醒、learning）
 * 都从这里起步。收口的是三样"忘了不报错、只在运行时静默出错"的约定：
 * <ul>
 *   <li>{@link MessagesSchema#SCHEMA}：不带就掉回框架默认 schema，内容相同的消息被静默丢弃
 *       （三个人各踩过一次的坑，细节见 MessagesSchema 类注释）</li>
 *   <li>{@link #STATE_SERIALIZER}：叶子与会话上下文表
 *       （{@link com.mawai.wiibagent.chat.ChatContextStore}）必须同一个实例，
 *       两边不一致就写得进读不出</li>
 *   <li>系统提示做成必填参数并拦空值：{@link ResilientChatService} 对缺失的系统提示不报错，
 *       静默换成框架默认串——靠系统提示带纪律/格式约定的装置，整份提示会直接消失</li>
 * </ul>
 * <b>不收口的部分</b>：工具、hook 及其注册顺序、ChatService 装配、compile 配置——这些是各建图点的
 * 真实差异。尤其 hook 顺序是站点语义（后注册=最外层=先执行）：summarizer 要保险丝压在
 * ApprovalGate 外层，trader/learning 要 trace 在保险丝外层（被拒的调用也得记轨迹），
 * 没有全仓统一的"正确顺序"，各点自己注释+测试钉住。
 * <p>
 * behavior 与复盘教练两处借 {@code ReactAgent.builder()} 只为捎系统提示（不建图、不 compile）不走这里：
 * 三样约定没有一样与它们相关，但系统提示得自己记得传。
 */
public final class AgentGraphs {

    /**
     * 全部建图点与会话上下文表共用的序列化器。做成一处常量，"两边必须同一套格式"就是结构保证，
     * 不再依赖 Spring 装配恰好只有一个 bean。
     * <p>
     * 必须是 Jackson 版而非默认的 ObjectStreamStateSerializer：Spring AI 的 Message 全族
     * 不实现 Serializable，用 Java 对象流序列化会当场 NotSerializableException。
     */
    public static final StateSerializer<MessagesState<Message>> STATE_SERIALIZER =
            new SpringAIJacksonStateSerializer<>(MessagesState::new);

    private AgentGraphs() {
    }

    /**
     * 起一个已带齐三样约定的 builder；工具、hook、ChatService、compile 由调用方接着配。
     *
     * @param systemPrompt 必填，空值当场拦下——不给"静默换成框架默认提示"留通道
     */
    public static ReactAgent.Builder<MessagesState<Message>> reactAgent(ChatModel model, String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException(
                    "systemPrompt 不能为空：缺省会被 ResilientChatService 静默换成框架默认提示");
        }
        return ReactAgent.builder()
                .chatModel(model)
                .stateSerializer(STATE_SERIALIZER)
                .schema(MessagesSchema.SCHEMA)
                .defaultSystem(systemPrompt);
    }
}

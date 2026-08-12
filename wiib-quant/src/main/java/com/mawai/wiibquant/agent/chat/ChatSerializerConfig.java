package com.mawai.wiibquant.agent.chat;

import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话链路共用的状态序列化器。三处消费同一个实例：叶子 agent（ChatAgentFactory / trader 唤醒）、
 * 会话上下文表（{@link ChatContextStore}）——存进去读出来必须同一套格式，不一致就写得进读不出。
 * <p>
 * 必须是 Jackson 版而非默认的 ObjectStreamStateSerializer：Spring AI 的 Message 全族
 * 不实现 Serializable，用 Java 对象流序列化会当场 NotSerializableException。
 */
@Configuration
public class ChatSerializerConfig {

    @Bean
    public StateSerializer<MessagesState<Message>> workbenchStateSerializer() {
        return new SpringAIJacksonStateSerializer<>(MessagesState::new);
    }
}

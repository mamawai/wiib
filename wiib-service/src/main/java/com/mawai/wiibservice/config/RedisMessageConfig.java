package com.mawai.wiibservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis消息监听配置
 * 用于WebSocket集群消息广播
 */
@Configuration
public class RedisMessageConfig {

    /**
     * Redis消息监听容器
     * 用于订阅Redis频道，接收广播消息
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}


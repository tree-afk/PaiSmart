package com.yizhaoqi.smartpai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.yizhaoqi.smartpai.service.document.ActiveRetrievalScopeService;
import com.yizhaoqi.smartpai.service.reliability.DocumentStatusNotifier;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }

    /**
     * 订阅文档状态频道：任意实例上的状态变化都广播到所有实例，由持有用户 WebSocket 的实例推给前端；
     * 同时订阅检索范围频道：构建切换后各实例的 active 范围快照一起失效，避免旧版本继续被召回
     */
    @Bean
    public RedisMessageListenerContainer documentStatusListenerContainer(RedisConnectionFactory connectionFactory,
                                                                         DocumentStatusNotifier documentStatusNotifier,
                                                                         ActiveRetrievalScopeService activeRetrievalScopeService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(documentStatusNotifier, new ChannelTopic(DocumentStatusNotifier.CHANNEL));
        container.addMessageListener(activeRetrievalScopeService, new ChannelTopic(ActiveRetrievalScopeService.CHANNEL));
        return container;
    }
}

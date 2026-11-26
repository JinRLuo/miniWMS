package com.miniwms;

import java.time.Duration;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

@Configuration(proxyBeanMethods = false)
@Profile("redis")
@EnableCaching
class RedisConfiguration {
    @Bean
    RedisCacheManagerBuilderCustomizer cacheLifetimes(CacheProperties properties) {
        var qr = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith(properties.getRedis().getKeyPrefix())
                .disableCachingNullValues().entryTtl(Duration.ofDays(1));
        return builder -> builder
                .withCacheConfiguration("qr-images", qr)
                .disableCreateOnMissingCache();
    }

    @Bean
    SmartInitializingSingleton requireRedis(RedisConnectionFactory connections) {
        return () -> {
            try (var connection = connections.getConnection()) {
                if (!"PONG".equals(connection.ping())) throw new IllegalStateException("Redis is not ready");
            }
        };
    }
}

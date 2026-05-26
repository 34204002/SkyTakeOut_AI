package com.sky.aspect;

import com.sky.annotation.RateLimit;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.function.Supplier;

@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    @Autowired
    private LettuceConnectionFactory connectionFactory;

    private ProxyManager<String> proxyManager;
    private StatefulRedisConnection<String, byte[]> connection;

    private static final RedisCodec<String, byte[]> CODEC =
            RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

    @PostConstruct
    public void init() {
        RedisClient redisClient = (RedisClient) connectionFactory.getNativeClient();
        this.connection = redisClient.connect(CODEC);
        this.proxyManager = LettuceBasedProxyManager.builderFor(connection).build();
        log.info("Bucket4j Redis 限流管理器初始化完成");
    }

    @PreDestroy
    public void destroy() {
        if (connection != null) {
            connection.close();
        }
    }

    @Around("@annotation(rateLimit)")
    public Object rateLimit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = rateLimit.name().isEmpty()
                ? "rate_limit:" + joinPoint.getSignature().toShortString()
                : "rate_limit:" + rateLimit.name();

        Bucket bucket = proxyManager.builder()
                .build(key, createConfig(rateLimit));

        if (bucket.tryConsume(1)) {
            return joinPoint.proceed();
        } else {
            log.warn("限流触发 - {}", key);
            throw new RuntimeException("请求过于频繁，请稍后再试");
        }
    }

    private Supplier<BucketConfiguration> createConfig(RateLimit rl) {
        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(rl.capacity())
                        .refillGreedy(rl.refillTokens(), Duration.ofSeconds(rl.refillSeconds()))
                        .build())
                .build();
    }
}

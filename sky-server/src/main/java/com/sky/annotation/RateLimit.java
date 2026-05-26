package com.sky.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流注解（令牌桶算法）
 * <p>
 * 默认每秒补充 1 个令牌，桶容量 10，即稳定速率 1 QPS，允许突发到 10 QPS。
 * 示例：capacity=5, refillTokens=5, refillSeconds=1 表示每秒 5 QPS。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流名称，默认用类名+方法名 */
    String name() default "";

    /** 令牌桶容量（允许的最大突发请求数） */
    int capacity() default 10;

    /** 每次补充的令牌数 */
    int refillTokens() default 1;

    /** 补充间隔（秒） */
    int refillSeconds() default 1;
}

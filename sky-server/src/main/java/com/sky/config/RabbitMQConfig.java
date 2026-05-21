package com.sky.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableRabbit
public class RabbitMQConfig {

    /** 延时消息交换机（原始消息先发到这里） */
    public static final String DELAY_EXCHANGE = "order.delay.exchange";
    /** 延时队列（消息在此等待 15 分钟后过期 → 转送到超时交换机） */
    public static final String DELAY_QUEUE = "order.delay.queue";
    /** 延时消息路由键 */
    public static final String DELAY_ROUTING_KEY = "order.delay";

    /** 超时处理交换机（接收从延时队列过期转来的消息） */
    public static final String TIMEOUT_EXCHANGE = "order.timeout.exchange";
    /** 超时处理队列（消费者监听的队列） */
    public static final String TIMEOUT_QUEUE = "order.timeout.queue";
    /** 超时处理路由键 */
    public static final String TIMEOUT_ROUTING_KEY = "order.timeout";

    /** 订单超时时间（毫秒） */
    public static final long ORDER_TIMEOUT_MS = 15 * 60 * 1000L;

    @Bean
    public DirectExchange delayExchange() {
        return new DirectExchange(DELAY_EXCHANGE);
    }

    @Bean
    public DirectExchange timeoutExchange() {
        return new DirectExchange(TIMEOUT_EXCHANGE);
    }

    @Bean
    public Queue delayQueue() {
        Map<String, Object> args = new HashMap<>();
        // 消息在队列中存活 15 分钟后过期
        args.put("x-message-ttl", ORDER_TIMEOUT_MS);
        // 过期后转发到超时交换机
        args.put("x-dead-letter-exchange", TIMEOUT_EXCHANGE);
        // 转发时使用的路由键
        args.put("x-dead-letter-routing-key", TIMEOUT_ROUTING_KEY);
        return new Queue(DELAY_QUEUE, true, false, false, args);
    }

    @Bean
    public Queue timeoutQueue() {
        return new Queue(TIMEOUT_QUEUE, true);
    }

    @Bean
    public Binding delayBinding(Queue delayQueue, DirectExchange delayExchange) {
        return BindingBuilder.bind(delayQueue).to(delayExchange).with(DELAY_ROUTING_KEY);
    }

    @Bean
    public Binding timeoutBinding(Queue timeoutQueue, DirectExchange timeoutExchange) {
        return BindingBuilder.bind(timeoutQueue).to(timeoutExchange).with(TIMEOUT_ROUTING_KEY);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}

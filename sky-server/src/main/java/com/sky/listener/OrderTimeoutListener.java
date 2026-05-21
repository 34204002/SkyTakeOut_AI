package com.sky.listener;

import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.sky.config.RabbitMQConfig.TIMEOUT_QUEUE;

@Slf4j
@Component
public class OrderTimeoutListener {

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 接收订单超时消息，仅在订单仍为"待付款"状态时取消订单。
     * SQL 中 WHERE status=1 保证与用户支付不会撞车。
     */
    @RabbitListener(queues = TIMEOUT_QUEUE)
    public void handleOrderTimeout(Long orderId) {
        log.info("收到订单超时消息，orderId={}", orderId);
        int affected = orderMapper.cancelOrderIfPending(orderId);
        if (affected > 0) {
            log.info("订单超时已取消，orderId={}", orderId);
        } else {
            log.info("订单已支付或已取消，跳过超时处理，orderId={}", orderId);
        }
    }
}

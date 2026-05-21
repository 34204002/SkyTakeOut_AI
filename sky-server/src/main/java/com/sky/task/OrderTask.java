package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class OrderTask {
    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderMapper orderMapper;

    /**
     * 处理一直处于派送中的订单
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void processDeliveryOrders() {
        log.info("处理处于派送中的订单");
        List<Orders> ordersList=orderMapper.getByStatusAndOrderTimeOut(Orders.DELIVERY_IN_PROGRESS, LocalDateTime.now().plusMinutes(-60));
        if (ordersList != null && !ordersList.isEmpty()) {
            log.info("处于派送中的订单：{}", ordersList);
            ordersList.forEach(orders -> {
                orders.setStatus(Orders.COMPLETED);
                orderService.update(orders);
            });
        }
    }
}

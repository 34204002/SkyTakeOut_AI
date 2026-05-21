package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.ai.service.AiAsyncService;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.config.RabbitMQConfig;
import com.sky.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WebSocketServer webSocketServer;
    
    @Autowired
    private AiAsyncService aiAsyncService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * @param ordersSubmitDTO
     * @return
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderSubmitVO submit(OrdersSubmitDTO ordersSubmitDTO) {
        //处理各种业务异常（地址簿不存在、购物车数据不存在）
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if(addressBook == null){
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(ShoppingCart.builder().userId(BaseContext.getCurrentId()).build());
        if(shoppingCartList == null || shoppingCartList.isEmpty()){
            throw new AddressBookBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        //向订单表插入一条数据
        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO,orders);
        BeanUtils.copyProperties(addressBook,orders);

        orders.setAddress(addressBook.getProvinceName() + addressBook.getCityName() + addressBook.getDistrictName()+addressBook.getDetail());
        orders.setUserId(BaseContext.getCurrentId());
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setPayStatus(Orders.UN_PAID);
        orders.setOrderTime(LocalDateTime.now());

        BigDecimal sumAmount=new BigDecimal(0);
        for (ShoppingCart shoppingCart : shoppingCartList) {
            sumAmount = sumAmount.add(shoppingCart.getAmount().multiply(new BigDecimal(shoppingCart.getNumber())));
        }
        orders.setAmount(sumAmount);
        orders.setNumber(generateOrderNumber());
        orders.setUserName(userMapper.getById(orders.getUserId()).getName());
        orderMapper.insert(orders);

        // 发送延时消息，15 分钟后检查订单是否超时未支付
        rabbitTemplate.convertAndSend(RabbitMQConfig.DELAY_EXCHANGE, RabbitMQConfig.DELAY_ROUTING_KEY, orders.getId());

        // 【新增】异步触发 AI 备注解析
        if (ordersSubmitDTO.getRemark() != null && !ordersSubmitDTO.getRemark().isEmpty()) {
            // 调用异步服务解析订单备注
            aiAsyncService.parseOrderRemarkAsync(orders.getId(), ordersSubmitDTO.getRemark());
        }

        //向订单明细表插入n条数据
        List<OrderDetail> orderDetailList = new ArrayList<>();
        for (ShoppingCart shoppingCart : shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();
            orderDetail.setOrderId(orders.getId());
            BeanUtils.copyProperties(shoppingCart,orderDetail);
            orderDetailList.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetailList);
        //清空购物车
        shoppingCartMapper.deleteByUserId(BaseContext.getCurrentId());
        //封装VO返回
        return OrderSubmitVO.builder()
                .id(orders.getId())
                .orderNumber(orders.getNumber())
                .orderTime(orders.getOrderTime())
                .orderAmount(orders.getAmount())
                .build();

    }
    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

//        //调用微信支付接口，生成预支付交易单
//        JSONObject jsonObject = weChatPayUtil.pay(
//                ordersPaymentDTO.getOrderNumber(), //商户订单号
//                new BigDecimal(0.01), //支付金额，单位 元
//                "苍穹外卖订单", //商品描述
//                user.getOpenid() //微信用户的openid
//        );
        JSONObject jsonObject=new JSONObject();

        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            throw new OrderBusinessException("该订单已支付");
        }

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));

        return vo;
    }

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {

        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();
        //websocket推送支付成功信息给商家
        Map map = new HashMap();
        map.put("type", 1);//type=1来单提醒，type=2客户催单
        map.put("orderId", ordersDB.getId());
        map.put("content","订单号:"+outTradeNo);

        String jsonString = JSONObject.toJSONString(map);
        webSocketServer.sendToAllClient(jsonString);


        int affected = orderMapper.payOrderIfPending(orders);
        if (affected == 0) {
            log.warn("支付成功但订单已超时取消，不回写状态，orderId={}", ordersDB.getId());
        }
    }

    /**
     * @param id
     * @return
     */
    @Override
    public OrderVO getById(Long id) {
        Orders orders = orderMapper.getById(id);
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());
        orderVO.setOrderDetailList(orderDetailList);

        // 使用封装的方法构造订单菜品信息字符串
        orderVO.setOrderDishes(buildOrderDishes(orderDetailList));

        return orderVO;
    }

    /**
     * @param ordersPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(OrdersPageQueryDTO ordersPageQueryDTO) {
        Page<OrderVO> page = PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());
        List<OrderVO> orderVOList = orderMapper.pageQuery(ordersPageQueryDTO);

        for (OrderVO orderVO : orderVOList) {
            // 查询订单详情
            List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orderVO.getId());
            orderVO.setOrderDetailList(orderDetailList);

            // 设置订单菜品信息
            orderVO.setOrderDishes(buildOrderDishes(orderDetailList));
        }

        return new PageResult(page.getTotal(), orderVOList);
    }

    /**
     * @param ordersCancelDTO
     */
    @Override
    public void userCancel(OrdersCancelDTO ordersCancelDTO) {
        Orders order = orderMapper.getById(ordersCancelDTO.getId());
        if (order == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 商家已接单状态下，用户取消订单需电话沟通商家
        if (order.getStatus()>=Orders.CONFIRMED) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        // 取消订单后需要将订单状态修改为“已取消”
        if (order.getStatus().equals(Orders.PENDING_PAYMENT) || order.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            order.setStatus(Orders.CANCELLED);
        }
        // 需要给用户退款
        order.setPayStatus(Orders.REFUND);
        order.setCancelReason(ordersCancelDTO.getCancelReason());
        order.setCancelTime(LocalDateTime.now());
        orderMapper.update(order);
    }

    /**
     * @param id
     */
    @Override
    public void repetition(Long id) {
        //订单明细
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);
        //删除购物车
        shoppingCartMapper.deleteByUserId(BaseContext.getCurrentId());
        //添加购物车
        List<ShoppingCart> shoppingCartList = new ArrayList<>();
        for (OrderDetail orderDetail : orderDetailList) {
            ShoppingCart shoppingCart = new ShoppingCart();
            BeanUtils.copyProperties(orderDetail, shoppingCart);
            shoppingCart.setUserId(BaseContext.getCurrentId());
            shoppingCartList.add(shoppingCart);
        }
        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    /**
     * @return
     */
    @Override
    public OrderStatisticsVO caculateStatistics() {
        List<Orders> orders = orderMapper.list();
        long toBeConfirmed = orders.stream().filter(order -> order.getStatus().equals(Orders.TO_BE_CONFIRMED)).count();
        long confirmed = orders.stream().filter(order -> order.getStatus().equals(Orders.CONFIRMED)).count();
        long deliveryInProgress = orders.stream().filter(order -> order.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)).count();
        return OrderStatisticsVO.builder()
                .toBeConfirmed(toBeConfirmed)
                .confirmed(confirmed)
                .deliveryInProgress(deliveryInProgress)
                .build();
    }

    /**
     * @param orders
     */
    @Override
    public void update(Orders orders) {
        orderMapper.update(orders);
    }

    /**
     * @param ordersRejectionDTO
     */
    @Override
    public void reject(OrdersRejectionDTO ordersRejectionDTO) {
        orderMapper.update(Orders.builder()
                .id(ordersRejectionDTO.getId())
                .rejectionReason(ordersRejectionDTO.getRejectionReason())
                .status(Orders.CANCELLED)
                .build());

    }

    /**
     * @param ordersCancelDTO
     */
    @Override
    public void adminCancel(OrdersCancelDTO ordersCancelDTO) {
        Orders order = orderMapper.getById(ordersCancelDTO.getId());
        if (order == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        order.setStatus(Orders.CANCELLED);
        // 需要给用户退款
        order.setPayStatus(Orders.REFUND);
        order.setCancelReason(ordersCancelDTO.getCancelReason());
        order.setCancelTime(LocalDateTime.now());
        orderMapper.update(order);

    }

    /**
     * @param id
     */
    @Override
    public void reminder(Long id) {
        Orders orders = orderMapper.getById(id);
        Map map = new HashMap();
        map.put("type", 2);//type=1来单提醒，type=2客户催单
        map.put("orderId", orders.getId());
        map.put("content","订单号:"+orders.getNumber());
        webSocketServer.sendToAllClient(JSON.toJSONString(map));
    }

    /**
     * 构造订单菜品信息字符串
     * @param orderDetailList 订单详情列表
     * @return 菜品信息字符串，例如："宫保鸡丁 x1, 鱼香肉丝 x2"
     */
    private String buildOrderDishes(List<OrderDetail> orderDetailList) {
        StringBuilder orderDishes = new StringBuilder();
        if (orderDetailList != null && !orderDetailList.isEmpty()) {
            for (int i = 0; i < orderDetailList.size(); i++) {
                OrderDetail orderDetail = orderDetailList.get(i);
                orderDishes.append(orderDetail.getName()).append(" x").append(orderDetail.getNumber());
                if (i < orderDetailList.size() - 1) {
                    orderDishes.append(", ");
                }
            }
        }
        return orderDishes.toString();
    }

    /**
     * 生成订单号
     * 格式：yyyyMMddHHmmssSSS + userId
     */
    public String generateOrderNumber() {
        // 获取当前时间戳，精确到毫秒
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));

        // 获取用户ID
        Long userId = BaseContext.getCurrentId();

        // 组合为订单号
        return timestamp + userId;
    }


}

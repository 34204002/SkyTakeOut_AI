package com.sky.mapper;

import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.Orders;
import com.sky.entity.OrdersReport;
import com.sky.entity.TurnoverReport;
import com.sky.vo.OrderVO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper {
    void insert(Orders orders);

    /**
     * 根据订单号查询订单
     * @param orderNumber
     */

    Orders getByNumber(String orderNumber);

    /**
     * 修改订单信息
     * @param orders
     */
    void update(Orders orders);

    Orders getById(Long id);

    List<OrderVO> pageQuery(OrdersPageQueryDTO ordersPageQueryDTO);


    List<Orders> list();

    List<Orders> getByStatusAndOrderTimeOut(Integer status, LocalDateTime orderTime);

    List<TurnoverReport> getTurnoverStatistics(LocalDate begin, LocalDate end);

    List<OrdersReport> getOrdersStatistics(LocalDate begin, LocalDate end);

    List<Integer> getIdByStatusAndOrderTimeBetween(Integer completed, LocalDate begin, LocalDate end);

    /**
     * 超时取消订单（带状态约束，仅取消 status=1 的订单）
     * @return 影响行数（0 表示订单已支付或已取消）
     */
    int cancelOrderIfPending(Long id);

    /**
     * 支付成功更新订单（带状态约束，仅更新 status=1 的订单）
     * @return 影响行数（0 表示订单已超时取消）
     */
    int payOrderIfPending(Orders orders);
}

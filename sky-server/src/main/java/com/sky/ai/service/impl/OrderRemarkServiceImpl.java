package com.sky.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.entity.OrderRemarkTag;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.ai.mapper.OrderRemarkTagMapper;
import com.sky.ai.service.OrderRemarkService;
import com.sky.ai.util.AiCallUtil;
import com.sky.vo.OrderRemarkVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
@Slf4j
public class OrderRemarkServiceImpl implements OrderRemarkService {

    @Autowired
    private AiCallUtil aiCallUtil;

    @Autowired
    private OrderRemarkTagMapper orderRemarkTagMapper;
    
    @Autowired
    private OrderMapper orderMapper; // 注入 OrderMapper

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Skill 内容缓存，避免重复读取文件
    private final ConcurrentHashMap<String, String> skillCache = new ConcurrentHashMap<>();

    // 标准标签库
    private static final List<String> FLAVOR_TAGS = Arrays.asList("不辣", "微辣", "中辣", "重辣", "少盐", "多醋", "不要葱", "不要蒜", "不要香菜", "免辣");
    private static final List<String> DELIVERY_TAGS = Arrays.asList("放门卫", "放前台", "打电话", "挂门把手", "轻拿轻放");
    private static final List<String> URGENCY_TAGS = Arrays.asList("加急", "尽快", "马上", "特急");

    @Override
    public void parseAndSaveRemark(Long orderId, String originalRemark) {
        if (originalRemark == null || originalRemark.trim().isEmpty()) {
            return;
        }

        OrderRemarkVO remarkVO;
        try {
            // 1. 尝试 AI 解析
            remarkVO = parseByAi(originalRemark);
        } catch (Exception e) {
            log.warn("AI 解析备注失败，降级为规则匹配: {}", e.getMessage());
            // 2. 降级：规则匹配
            remarkVO = parseByRules(originalRemark);
        }

        // 3. 持久化
        saveRemarkTag(orderId, originalRemark, remarkVO);
    }

    @Override
    public OrderRemarkVO previewRemark(String originalRemark) {
        if (originalRemark == null || originalRemark.trim().isEmpty()) {
            return new OrderRemarkVO();
        }
        
        try {
            return parseByAi(originalRemark);
        } catch (Exception e) {
            return parseByRules(originalRemark);
        }
    }

    /**
     * 使用 AI 解析备注（BeanOutputConverter 保证输出格式）
     */
    private OrderRemarkVO parseByAi(String remark) {
        String skillContent = loadSkillContent("order-remark-parser.md");

        BeanOutputConverter<OrderRemarkVO> converter = new BeanOutputConverter<>(OrderRemarkVO.class);
        String prompt = skillContent + "\n" + converter.getFormat() + "\n\n用户备注：" + remark;

        String response = aiCallUtil.callChatModel(prompt);
        log.info("AI 解析备注原始响应: {}", response);

        return converter.convert(response);
    }

    /**
     * 使用规则降级解析
     */
    private OrderRemarkVO parseByRules(String remark) {
        OrderRemarkVO vo = new OrderRemarkVO();
        vo.setFlavorTags(Arrays.asList());
        vo.setDeliveryTags(Arrays.asList());
        vo.setUrgencyLevel(0);

        if (Pattern.compile("不辣|免辣").matcher(remark).find()) {
            vo.setFlavorTags(Arrays.asList("不辣"));
        }
        if (Pattern.compile("加急|尽快|马上").matcher(remark).find()) {
            vo.setUrgencyLevel(1);
        }
        if (Pattern.compile("门卫|前台").matcher(remark).find()) {
            vo.setDeliveryTags(Arrays.asList("放门卫"));
        }

        return vo;
    }

    /**
     * 保存解析结果
     */
    private void saveRemarkTag(Long orderId, String originalRemark, OrderRemarkVO vo) {
        try {
            OrderRemarkTag tag = new OrderRemarkTag();
            tag.setOrderId(orderId);
            tag.setOriginalRemark(originalRemark);
            tag.setFlavorTags(objectMapper.writeValueAsString(vo.getFlavorTags()));
            tag.setDeliveryTags(objectMapper.writeValueAsString(vo.getDeliveryTags()));
            tag.setUrgencyLevel(vo.getUrgencyLevel());
            tag.setParseStatus(1); // 1-成功

            orderRemarkTagMapper.insert(tag);
            
            // 同步更新 Orders 表的冗余字段，方便后厨查看
            Orders updateOrder = new Orders();
            updateOrder.setId(orderId);
            updateOrder.setRemarkFlavor(tag.getFlavorTags());
            updateOrder.setRemarkDelivery(tag.getDeliveryTags());
            updateOrder.setRemarkUrgency(tag.getUrgencyLevel());
            updateOrder.setAiParseStatus(1);
            orderMapper.update(updateOrder);
            
        } catch (Exception e) {
            log.error("保存订单备注标签失败", e);
        }
    }

    /**
     * 加载 Skill 文件内容（带缓存）
     */
    private String loadSkillContent(String fileName) {
        // 先从缓存中获取
        return skillCache.computeIfAbsent(fileName, key -> {
            try {
                ClassPathResource resource = new ClassPathResource("ai/skills/" + key);
                String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                log.info("Skill 文件加载成功: {}, 大小: {} bytes", key, content.length());
                return content;
            } catch (IOException e) {
                log.error("加载 Skill 文件失败: {}", key, e);
                // 降级：返回默认提示词
                return "你是订单备注解析专家，将自然语言备注转换为结构化标签。输出JSON格式：{\"flavorTags\": [], \"deliveryTags\": [], \"urgencyLevel\": 0}";
            }
        });
    }
}

package com.sky.ai.service.impl;

import com.sky.service.ReportService;
import com.sky.vo.OrderReportVO;
import com.sky.vo.ReportAnswerVO;
import com.sky.vo.SalesTop10ReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;
import com.sky.ai.service.ReportQueryService;
import com.sky.ai.util.AiTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 经营数据自然语言查询服务实现类
 */
@Service
@Slf4j
public class ReportQueryServiceImpl implements ReportQueryService {

    @Autowired
    @Qualifier("statelessChatClient")
    private ChatClient chatClient;

    @Autowired
    private AiTools aiTools;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    // Skill 内容缓存，避免重复读取文件
    private final ConcurrentHashMap<String, String> skillCache = new ConcurrentHashMap<>();

    @Override
    public ReportAnswerVO answerDataQuestion(String question) {
        log.info("收到自然语言查询请求: {}", question);
        long startTime = System.currentTimeMillis();

        try {
            // 获取当前日期，用于 AI 解析相对时间
            String currentDate = LocalDate.now().format(DATE_FORMATTER);
            
            // 加载 Skill 文件内容作为 System Prompt
            String skillContent = loadSkillContent("report-query-assistant.md");
            String systemPrompt = skillContent + "\n\n当前日期：" + currentDate;

            log.info("开始调用 AI（Function Calling）...");
            long aiStartTime = System.currentTimeMillis();
            
            // 使用 Function Calling 自动识别意图并调用工具函数
            ChatClient.CallResponseSpec responseSpec = chatClient.prompt()
                    .system(systemPrompt)
                    .user(question)
                    .tools(aiTools)  // 注册专用的 AI 工具类
                    .call();

            long aiEndTime = System.currentTimeMillis();
            log.info("AI 调用完成，耗时: {}ms", (aiEndTime - aiStartTime));

            // 获取 AI 生成的回答
            String aiAnswer = responseSpec.content();

            log.info("AI 调用完成,回复：{}",aiAnswer);

            long endTime = System.currentTimeMillis();
            log.info("自然语言查询完成，总耗时: {}ms", (endTime - startTime));

            return ReportAnswerVO.builder()
                    .answer(aiAnswer)
                    .data(null)  // Spring AI Function Calling 的数据已融入 AI 回答中
                    .dataType("unknown")
                    .calledFunction(null)
                    .tokenUsage(0)
                    .build();

        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            log.error("自然语言查询失败，耗时: {}ms, 错误: {}", (endTime - startTime), e.getMessage(), e);

            // 降级策略：返回友好提示
            return ReportAnswerVO.builder()
                    .answer("抱歉，我暂时无法理解您的问题。您可以尝试问：'上个月营业额多少？'或'最热销的菜品有哪些？'")
                    .data(null)
                    .dataType("error")
                    .calledFunction(null)
                    .tokenUsage(0)
                    .build();
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
                return "你是一个经营数据分析助手，帮助用户查询业务数据。";
            }
        });
    }
}

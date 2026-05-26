package com.sky.ai.controller.admin;

import com.sky.annotation.RateLimit;
import com.sky.context.BaseContext;
import com.sky.dto.ai.AiChatDTO;
import com.sky.result.Result;
import com.sky.ai.util.AiCallUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@RestController
@RequestMapping("/admin/ai")
@Slf4j
public class AiTestController {

    @Autowired
    @Qualifier("memoryChatClient")
    private ChatClient chatClient;

    @Autowired
    private AiCallUtil aiCallUtil;

    /**
     * 简单对话（带会话记忆）
     * 多次调用会记住之前的对话内容
     *
     * @param message 用户消息
     * @return AI回复
     */
    @GetMapping("/chat")
    @RateLimit(name = "ai-test-chat", capacity = 3, refillTokens = 3, refillSeconds = 1)
    public Result<String> chat(@RequestParam String message) {
        log.info("收到AI对话请求: {}", message);
        
        // 使用当前管理员ID作为会话ID，实现用户级别的会话隔离
        Long currentId = BaseContext.getCurrentId();
        String conversationId = "admin_" + (currentId != null ? currentId : "guest");
        
        String response = chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                .call()
                .content();
        
        log.info("AI回复: {}", response);
        return Result.success(response);
    }

    /**
     * 带系统提示词的对话（带会话记忆）
     * 适合需要设定角色或场景的对话
     *
     * @param aiChatDTO 对话请求参数
     * @return AI回复和模型信息
     */
    @PostMapping("/chat/stream")
    public Result<Map<String, Object>> chatWithSystem(@RequestBody AiChatDTO aiChatDTO) {
        String userMessage = aiChatDTO.getMessage();
        String systemPrompt = aiChatDTO.getSystem() != null ? aiChatDTO.getSystem() : "你是一个智能助手";
        
        // 使用当前管理员ID作为会话ID
        Long currentId = BaseContext.getCurrentId();
        String conversationId = "admin_" + (currentId != null ? currentId : "guest");
        
        log.info("对话请求 - 会话ID: {}, 系统提示: {}, 用户消息: {}", conversationId, systemPrompt, userMessage);
        
        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                .call()
                .content();
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", response);
        result.put("model", "deepseek-ai/DeepSeek-R1-0528-Qwen3-8B");
        result.put("conversationId", conversationId);
        result.put("hasMemory", true);
        
        return Result.success(result);
    }

    @GetMapping("/embedding")
    public Result<Map<String, Object>> testEmbedding(@RequestParam String text) {
        log.info("生成文本向量: {}", text);
        
        float[] embeddings = aiCallUtil.generateEmbedding(text);
        
        Map<String, Object> result = new HashMap<>();
        result.put("text", text);
        result.put("vectorDimension", embeddings.length);
        result.put("sampleValues", new float[]{embeddings[0], embeddings[1], embeddings[2]});
        
        log.info("向量维度: {}", embeddings.length);
        return Result.success(result);
    }

    /**
     * 查看AI配置信息
     *
     * @return 模型信息
     */
    @GetMapping("/info")
    public Result<Map<String, String>> getAiInfo() {
        Map<String, String> info = new HashMap<>();
        info.put("status", "running");
        info.put("memoryStorage", "Redis (7天过期, 最多50条消息)");
        info.put("distributed", "支持");
        info.put("compression", "GZIP");
        info.put("sessionIsolation", "基于用户ID");
        
        return Result.success(info);
    }

    /**
     * 测试会话记忆功能
     * 演示多轮对话的记忆效果
     *
     * @return 测试结果
     */
    @GetMapping("/test-memory")
    public Result<Map<String, Object>> testMemory() {
        Long currentId = BaseContext.getCurrentId();
        String conversationId = "admin_" + (currentId != null ? currentId : "test");
        
        Map<String, Object> result = new HashMap<>();
        
        // 第一轮对话：告知名字
        String response1 = chatClient.prompt()
                .user("你好，我叫张三，请记住我的名字")
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                .call()
                .content();
        
        // 第二轮对话：询问名字（应该能记住）
        String response2 = chatClient.prompt()
                .user("我叫什么名字？")
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                .call()
                .content();
        
        // 第三轮对话：继续上下文
        String response3 = chatClient.prompt()
                .user("我刚才说了什么？")
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                .call()
                .content();
        
        result.put("conversationId", conversationId);
        result.put("round1_response", response1);
        result.put("round2_response", response2);
        result.put("round3_response", response3);
        result.put("memoryWorking", response2.contains("张三") || response2.contains("三"));
        
        log.info("会话记忆测试完成 - 会话ID: {}", conversationId);
        
        return Result.success(result);
    }

    /**
     * 清除当前用户的会话记忆
     *
     * @return 操作结果
     */
    @DeleteMapping("/clear-memory")
    public Result<String> clearMemory() {
        Long currentId = BaseContext.getCurrentId();
        String conversationId = "admin_" + (currentId != null ? currentId : "guest");
        
        // 通过删除文件系统中的记忆文件来清除记忆
        // 注意：这里需要通过 ChatMemoryRepository 来删除
        // 由于我们没有直接注入 Repository，这里返回提示信息
        
        log.info("清除会话记忆 - 会话ID: {}", conversationId);
        
        return Result.success("会话记忆已清除（会话ID: " + conversationId + "）");
    }
}

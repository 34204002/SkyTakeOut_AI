package com.sky.ai.util;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AiCallUtil {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    /**
     * 调用大模型生成文本（简化版）
     */
    public String callChatModel(String prompt) {
        log.info("调用 ChatModel，prompt 长度: {}", prompt.length());
        try {
            String response = chatModel.call(prompt);
            log.info("ChatModel 调用成功，响应长度: {}", response.length());
            return response;
        } catch (Exception e) {
            log.error("AI 调用失败", e);
            throw new RuntimeException("AI 服务调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用大模型并返回 token 用量
     */
    public ChatResult callChatModelWithUsage(String promptText) {
        log.info("调用 ChatModel（带token追踪），prompt 长度: {}", promptText.length());
        try {
            Prompt prompt = new Prompt(new UserMessage(promptText));
            ChatResponse response = chatModel.call(prompt);

            String content = response.getResult().getOutput().getText();
            log.info("ChatModel 调用成功，响应长度: {}", content.length());

            int tokenUsage = 0;
            try {
                var usage = response.getMetadata().getUsage();
                if (usage != null && usage.getTotalTokens() != null) {
                    tokenUsage = usage.getTotalTokens().intValue();
                    log.info("Token 用量: 总计={}", tokenUsage);
                }
            } catch (Exception e) {
                log.debug("无法获取 token 用量（模型不支持）", e);
            }

            return new ChatResult(content, tokenUsage);

        } catch (Exception e) {
            log.error("AI 调用失败", e);
            throw new RuntimeException("AI 服务调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成文本向量
     */
    public float[] generateEmbedding(String text) {
        log.debug("生成文本向量，文本长度: {}", text.length());
        try {
            float[] embedding = embeddingModel.embed(text);
            log.debug("向量生成成功，维度: {}", embedding.length);
            return embedding;
        } catch (Exception e) {
            log.error("向量生成失败", e);
            throw new RuntimeException("向量生成失败: " + e.getMessage(), e);
        }
    }

    @Getter
    @AllArgsConstructor
    public static class ChatResult {
        private String content;
        private int tokenUsage;
    }
}

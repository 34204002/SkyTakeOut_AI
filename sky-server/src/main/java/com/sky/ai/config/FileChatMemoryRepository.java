package com.sky.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.ai.config.VectorStoreConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于文件系统的聊天记忆仓库
 * 用于替代Redis存储聊天历史，支持持久化
 */
@Slf4j
public class FileChatMemoryRepository implements ChatMemoryRepository {

    private static final String MEMORY_DIR = VectorStoreConfig.DATA_DIR + "/chat-memory";
    private static final int MAX_MESSAGES = 50; // 最多保留50条消息
    
    private final ObjectMapper objectMapper;
    private final Path memoryDirectory;

    public FileChatMemoryRepository() {
        this.objectMapper = new ObjectMapper();
        this.memoryDirectory = Paths.get(MEMORY_DIR);
        
        // 确保目录存在
        try {
            Files.createDirectories(this.memoryDirectory);
            log.info("聊天记忆目录已创建: {}", this.memoryDirectory.toAbsolutePath());
        } catch (IOException e) {
            log.error("创建聊天记忆目录失败", e);
            throw new RuntimeException("无法创建聊天记忆目录", e);
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        Path filePath = getFilePath(conversationId);
        try {
            // 限制消息数量
            List<Message> limitedMessages = messages.size() > MAX_MESSAGES
                    ? messages.subList(messages.size() - MAX_MESSAGES, messages.size())
                    : messages;
            
            List<Map<String, String>> serializedMessages = limitedMessages.stream()
                    .map(this::serializeMessage)
                    .collect(Collectors.toList());
            
            // 将消息序列化为JSON并保存到文件
            String json = objectMapper.writeValueAsString(serializedMessages);
            Files.writeString(filePath, json);
            
            log.debug("保存对话记忆到文件: conversationId={}, 消息数={}", conversationId, limitedMessages.size());
        } catch (Exception e) {
            log.error("保存对话记忆失败: conversationId={}", conversationId, e);
        }
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Path filePath = getFilePath(conversationId);
        
        // 如果文件不存在，返回空列表
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }
        
        try {
            // 从文件读取JSON
            String json = Files.readString(filePath);
            
            List<Map<String, String>> serializedMessages = 
                    objectMapper.readValue(json, List.class);
            
            List<Message> messages = serializedMessages.stream()
                    .map(this::deserializeMessage)
                    .collect(Collectors.toList());
            
            log.debug("从文件加载对话记忆: conversationId={}, 消息数={}", conversationId, messages.size());
            return messages;
        } catch (Exception e) {
            log.error("加载对话记忆失败: conversationId={}", conversationId, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        Path filePath = getFilePath(conversationId);
        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.debug("删除对话记忆文件: conversationId={}", conversationId);
            }
        } catch (IOException e) {
            log.error("删除对话记忆文件失败: conversationId={}", conversationId, e);
        }
    }

    @Override
    public List<String> findConversationIds() {
        try {
            return Files.list(memoryDirectory)
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString().replace(".json", ""))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("查找对话ID列表失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取对话记忆文件路径
     */
    private Path getFilePath(String conversationId) {
        // 确保conversationId是安全的文件名
        String safeFileName = conversationId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return memoryDirectory.resolve(safeFileName + ".json");
    }

    /**
     * 序列化消息
     */
    private Map<String, String> serializeMessage(Message message) {
        return Map.of(
                "type", message.getMessageType().name(),
                "content", message.getText()
        );
    }

    /**
     * 反序列化消息
     */
    private Message deserializeMessage(Map<String, String> data) {
        MessageType type = MessageType.valueOf(data.get("type"));
        String content = data.get("content");
        
        return switch (type) {
            case USER -> new UserMessage(content);
            case ASSISTANT -> new AssistantMessage(content);
            case SYSTEM -> new SystemMessage(content);
            default -> throw new IllegalArgumentException("Unsupported message type: " + type);
        };
    }
}

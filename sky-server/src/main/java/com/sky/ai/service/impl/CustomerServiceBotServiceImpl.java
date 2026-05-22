package com.sky.ai.service.impl;

import com.sky.entity.CustomerServiceKnowledge;
import com.sky.ai.config.VectorStoreConfig;
import com.sky.ai.mapper.CustomerServiceKnowledgeMapper;
import com.sky.ai.service.CustomerServiceBotService;
import com.sky.ai.util.AiCallUtil;
import com.sky.context.BaseContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CustomerServiceBotServiceImpl implements CustomerServiceBotService {

    @Autowired
    private AiCallUtil aiCallUtil;

    @Autowired
    @Qualifier("memoryChatClient")
    private ChatClient memoryChatClient;

    @Autowired
    private CustomerServiceKnowledgeMapper knowledgeMapper;

    @Autowired
    private EmbeddingModel embeddingModel;

    /** 自管理的向量存储实例，支持运行时重建 */
    private SimpleVectorStore vectorStore;

    private List<CustomerServiceKnowledge> knowledgeBase;

    private static final String STORE_FILE = VectorStoreConfig.CUSTOMER_SERVICE_VECTOR_STORE_FILE;

    @PostConstruct
    public void init() {
        new File(VectorStoreConfig.DATA_DIR).mkdirs();

        knowledgeBase = knowledgeMapper.listAll();

        this.vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        File storeFile = new File(STORE_FILE);

        if (storeFile.exists()) {
            try {
                vectorStore.load(storeFile);
                log.info("客服向量数据从文件加载成功: {}", storeFile.getAbsolutePath());

                boolean hasData = !vectorStore.similaritySearch(
                        SearchRequest.builder().query("测试").topK(1).build()
                ).isEmpty();

                if (hasData) {
                    log.info("向量库已有数据，跳过构建");
                } else {
                    log.info("向量库文件为空，从数据库重建");
                    rebuildAndSave(storeFile);
                }
            } catch (Exception e) {
                log.warn("加载客服向量数据失败，将重新构建", e);
                rebuildAndSave(storeFile);
            }
        } else {
            log.info("客服向量库文件不存在，从数据库构建");
            rebuildAndSave(storeFile);
        }

        log.info("客服知识库加载完成，共{}条知识", knowledgeBase.size());
    }

    private synchronized void rebuildAndSave(File storeFile) {
        buildVectorStore();
        try {
            vectorStore.save(storeFile);
            log.info("客服向量库已保存到文件: {}", storeFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("保存客服向量库到文件失败", e);
        }
    }

    private void buildVectorStore() {
        if (knowledgeBase == null || knowledgeBase.isEmpty()) {
            log.warn("知识库为空，跳过向量库构建");
            return;
        }
        this.vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        List<Document> documents = knowledgeBase.stream()
            .map(k -> {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("id", k.getId());
                metadata.put("category", k.getCategory());
                metadata.put("question", k.getQuestion());
                return new Document(k.getQuestion() + " " + k.getKeywords(), metadata);
            })
            .collect(Collectors.toList());
        vectorStore.add(documents);
        log.info("向量库构建完成，共{}个向量", documents.size());
    }

    @Override
    public synchronized String answer(String userQuestion) {
        if (userQuestion == null || userQuestion.trim().isEmpty()) {
            return "您好，请问有什么可以帮助您的？";
        }

        try {
            List<String> subQuestions = extractSubQuestions(userQuestion);
            log.info("原始问题: {}, 子问题: {}", userQuestion, subQuestions);

            Set<CustomerServiceKnowledge> allMatched = new LinkedHashSet<>();

            for (String subQuestion : subQuestions) {
                SearchRequest request = SearchRequest.builder()
                        .query(subQuestion)
                        .topK(5)
                        .similarityThreshold(0.5)
                        .build();
                List<Document> similarDocs = vectorStore.similaritySearch(request);

                if (similarDocs != null) {
                    for (Document doc : similarDocs) {
                        Object idObj = doc.getMetadata().get("id");
                        if (idObj instanceof Number) {
                            Long knowledgeId = ((Number) idObj).longValue();
                            CustomerServiceKnowledge k = knowledgeMapper.getById(knowledgeId);
                            if (k != null) {
                                allMatched.add(k);
                            }
                        }
                    }
                }
            }

            if (allMatched.isEmpty()) {
                return "抱歉，我暂时无法回答这个问题。您可以尝试换个问法，或联系人工客服。";
            }

            List<CustomerServiceKnowledge> matchedList = new ArrayList<>(allMatched);

            if (matchedList.size() == 1) {
                return matchedList.get(0).getAnswer();
            } else {
                return generateSummaryByAI(userQuestion, matchedList);
            }

        } catch (Exception e) {
            log.error("智能客服回答失败", e);
            return "抱歉，系统暂时无法回答您的问题。您可以尝试换个问法，或联系人工客服。";
        }
    }

    private List<String> extractSubQuestions(String userQuestion) {
        String prompt = String.format(
            "请分析用户问题，提取出所有独立的子问题或关键意图，每行一个。\n" +
            "要求：\n" +
            "1. 如果只有一个问题，直接返回原问题\n" +
            "2. 如果有多个问题，分别列出\n" +
            "3. 提取核心意图，去除无关信息\n" +
            "4. 最多提取 5 个子问题\n\n" +
            "用户：%s\n提取：", userQuestion
        );
        String extracted = aiCallUtil.callChatModel(prompt).trim();
        List<String> subQuestions = Arrays.stream(extracted.split("\n"))
            .map(String::trim)
            .filter(line -> !line.isEmpty() && line.length() > 2)
            .limit(5)
            .collect(Collectors.toList());
        return subQuestions.isEmpty() ? List.of(userQuestion) : subQuestions;
    }

    private String generateSummaryByAI(String userQuestion, List<CustomerServiceKnowledge> knowledges) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < knowledges.size(); i++) {
            CustomerServiceKnowledge k = knowledges.get(i);
            context.append(String.format("\n【相关知识%d】\n问题：%s\n答案：%s\n",
                i + 1, k.getQuestion(), k.getAnswer()));
        }

        String systemPrompt = "你是一个友好的智能客服助手，根据提供的知识回答用户问题。\n" +
            "要求：\n" +
            "1. 如果用户问了多个问题，请分别回答\n" +
            "2. 语言简洁友好，控制在 200 字以内\n" +
            "3. 不要编造知识中没有的信息\n\n" +
            "当前相关知识：\n" + context;

        Long userId = BaseContext.getCurrentId();
        String conversationId = userId != null ? "cs_" + userId : "cs_anonymous";

        return memoryChatClient.prompt()
                .system(systemPrompt)
                .user(userQuestion)
                .advisors(a -> a.param("chat_memory_conversation_id", conversationId))
                .call()
                .content();
    }

    @Override
    public synchronized void reloadKnowledge() {
        log.info("开始重新加载知识库...");
        knowledgeBase = knowledgeMapper.listAll();
        File storeFile = new File(STORE_FILE);
        rebuildAndSave(storeFile);
        log.info("客服知识库重新加载完成，共{}条知识", knowledgeBase.size());
    }
}

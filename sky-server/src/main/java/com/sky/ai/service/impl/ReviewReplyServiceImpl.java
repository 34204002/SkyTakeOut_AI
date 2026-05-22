package com.sky.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.entity.Review;
import com.sky.entity.ReviewReplyDraft;
import com.sky.entity.ReviewReplyKnowledge;
import com.sky.mapper.ReviewMapper;
import com.sky.ai.mapper.ReviewReplyDraftMapper;
import com.sky.ai.mapper.ReviewReplyKnowledgeMapper;
import com.sky.ai.service.ReviewReplyService;
import com.sky.ai.util.AiCallUtil;
import com.sky.ai.util.AiCallUtil.ChatResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReviewReplyServiceImpl implements ReviewReplyService {

    @Autowired
    private AiCallUtil aiCallUtil;

    @Autowired
    private ReviewMapper reviewMapper;

    @Autowired
    private ReviewReplyDraftMapper draftMapper;

    @Autowired
    private ReviewReplyKnowledgeMapper knowledgeMapper;

    @Autowired
    @Qualifier("reviewVectorStore")
    private SimpleVectorStore vectorStore;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<String, String> skillCache = new ConcurrentHashMap<>();

    @Override
    public void generateReplyDraft(Long reviewId, String reviewContent, Integer rating) {
        log.info("=== 开始生成评价回复草稿，reviewId: {}, rating: {} ===", reviewId, rating);

        int totalTokens = 0;

        try {
            // 第一步：AI 情感与意图分析
            ChatResult analysisResult = analyzeReview(reviewContent, rating);
            totalTokens += analysisResult.getTokenUsage();

            Map<String, String> analysisMap;
            try {
                String content = analysisResult.getContent()
                        .replaceAll("```json", "").replaceAll("```", "").trim();
                analysisMap = objectMapper.readValue(content, Map.class);
            } catch (Exception e) {
                log.warn("AI 分析结果格式异常，使用默认值", e);
                analysisMap = new HashMap<>();
                analysisMap.put("emotion", rating <= 2 ? "愤怒" : "中性");
                analysisMap.put("type", "其他");
                analysisMap.put("demand", "其他");
            }

            String emotion = analysisMap.get("emotion");
            String problemType = analysisMap.get("type");
            String demand = analysisMap.get("demand");

            log.info("AI 分析结果 - 情绪: {}, 类型: {}, 诉求: {}", emotion, problemType, demand);

            // 第二步：RAG 检索相似模板
            List<ReviewReplyKnowledge> templates = retrieveTemplates(problemType);

            // 第三步：AI 生成回复
            ChatResult generateResult = generateReply(reviewContent, emotion, problemType, demand, templates);
            totalTokens += generateResult.getTokenUsage();
            String generatedReply = generateResult.getContent();

            Review review = reviewMapper.getById(reviewId);
            if (review == null) {
                log.error("评价不存在，无法生成草稿，reviewId: {}", reviewId);
                return;
            }

            // 第四步：持久化草稿
            ReviewReplyDraft draft = new ReviewReplyDraft();
            draft.setOrderId(review.getOrderId());
            draft.setReviewId(reviewId);
            draft.setReviewContent(reviewContent);
            draft.setAiAnalysis(objectMapper.writeValueAsString(analysisMap));
            draft.setRetrievedTemplates(objectMapper.writeValueAsString(templates));
            draft.setGeneratedReply(generatedReply);
            draft.setStatus("pending_review");
            draft.setAiModel("deepseek-ai/DeepSeek-V4-Flash");
            draft.setTokenUsage(totalTokens);
            draft.setCreateTime(LocalDateTime.now());

            draftMapper.insert(draft);

            log.info("=== 评价回复草稿生成成功，draftId: {}, reviewId: {}, tokenUsage: {} ===",
                    draft.getId(), reviewId, totalTokens);

        } catch (Exception e) {
            log.error("=== 生成评价回复草稿失败，reviewId: {} ===", reviewId, e);
            try {
                generateFallbackDraft(reviewId, reviewContent, rating);
            } catch (Exception ex) {
                log.error("=== 降级策略也失败了，reviewId: {} ===", reviewId, ex);
            }
        }
    }

    @Override
    public void approveAndPublish(Long draftId) {
        ReviewReplyDraft draft = draftMapper.getById(draftId);
        if (draft == null) {
            throw new RuntimeException("草稿不存在");
        }

        reviewMapper.replyReview(draft.getReviewId(), draft.getGeneratedReply(), LocalDateTime.now());

        draft.setStatus("approved");
        draft.setPublishTime(LocalDateTime.now());
        draftMapper.update(draft);

        addToRagLibrary(draft.getReviewId(), draft.getReviewContent(), draft.getGeneratedReply());

        log.info("评价回复已发布并同步至 RAG 库，draftId: {}", draftId);
    }

    @Override
    public void rejectAndRegenerate(Long draftId, String reason) {
        ReviewReplyDraft draft = draftMapper.getById(draftId);
        if (draft == null) {
            throw new RuntimeException("草稿不存在");
        }

        draft.setStatus("rejected");
        draftMapper.update(draft);

        Review review = reviewMapper.getById(draft.getReviewId());
        if (review == null) {
            log.error("评价不存在，无法重新生成，reviewId: {}", draft.getReviewId());
            return;
        }

        generateReplyDraft(draft.getReviewId(), draft.getReviewContent(), review.getRating());

        log.info("草稿已拒绝并重新生成，draftId: {}, 原因: {}", draftId, reason);
    }

    @Override
    public List<ReviewReplyDraft> listPendingDrafts() {
        return draftMapper.listPendingReview();
    }

    private ChatResult analyzeReview(String content, Integer rating) {
        String skillContent = loadSkillContent("review-reply-assistant.md");

        String prompt = String.format(
            "%s\n\n【任务】请分析以下用户评价：\n" +
            "【评价内容】%s\n" +
            "【评分】%d星\n\n" +
            "输出严格的 JSON 格式：{\"emotion\": \"\", \"type\": \"\", \"demand\": \"\"}\n" +
            "JSON输出：",
            skillContent, content, rating
        );

        return aiCallUtil.callChatModelWithUsage(prompt);
    }

    private List<ReviewReplyKnowledge> retrieveTemplates(String problemType) {
        try {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(problemType)
                            .topK(3)
                            .similarityThreshold(0.5)
                            .build()
            );

            return results.stream().map(doc -> {
                ReviewReplyKnowledge k = new ReviewReplyKnowledge();
                k.setReplyTemplate(doc.getText());
                k.setCategory("historical_reply");
                return k;
            }).collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("RAG 向量检索失败，降级为 SQL 检索", e);
            return fallbackSqlRetrieve(problemType);
        }
    }

    private List<ReviewReplyKnowledge> fallbackSqlRetrieve(String problemType) {
        List<ReviewReplyKnowledge> templates = knowledgeMapper.listByCategory(problemType);
        if (templates == null || templates.isEmpty()) {
            templates = knowledgeMapper.listAll();
        }
        return templates.stream().limit(3).toList();
    }

    private ChatResult generateReply(String reviewContent, String emotion, String problemType,
                                     String demand, List<ReviewReplyKnowledge> templates) {
        String skillContent = loadSkillContent("review-reply-assistant.md");

        String templateStr = templates.isEmpty() ? "无参考模板" :
                templates.stream().map(ReviewReplyKnowledge::getReplyTemplate)
                        .reduce((a, b) -> a + "\n" + b).get();

        String prompt = String.format(
                "%s\n\n【任务】根据以下信息生成回复：\n" +
                        "【用户评价】%s\n" +
                        "【情绪分析】%s\n" +
                        "【问题类型】%s\n" +
                        "【用户诉求】%s\n\n" +
                        "【参考模板】\n%s\n\n" +
                        "回复内容：",
                skillContent, reviewContent, emotion, problemType, demand, templateStr
        );

        return aiCallUtil.callChatModelWithUsage(prompt);
    }

    private void generateFallbackDraft(Long reviewId, String content, Integer rating) {
        String fallbackReply;

        if (rating <= 2) {
            fallbackReply = "非常抱歉给您带来不好的体验，我们会认真反思并改进。如有任何问题，欢迎联系客服处理。";
        } else if (rating == 3) {
            fallbackReply = "感谢您的反馈，我们会继续努力提升服务质量，期待您的再次光临！";
        } else {
            fallbackReply = "感谢您的好评！我们会继续保持，期待再次为您服务～";
        }

        Review review = reviewMapper.getById(reviewId);
        if (review == null) {
            log.error("评价不存在，无法生成降级草稿，reviewId: {}", reviewId);
            return;
        }

        ReviewReplyDraft draft = new ReviewReplyDraft();
        draft.setOrderId(review.getOrderId());
        draft.setReviewId(reviewId);
        draft.setReviewContent(content);
        draft.setGeneratedReply(fallbackReply);
        draft.setStatus("pending_review");
        draft.setAiModel("rule-based");
        draft.setCreateTime(LocalDateTime.now());

        draftMapper.insert(draft);
        log.info("使用降级策略生成草稿成功，reviewId: {}", reviewId);
    }

    private void addToRagLibrary(Long reviewId, String reviewContent, String replyContent) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("reviewId", reviewId);
            metadata.put("type", "merchant_reply");
            metadata.put("timestamp", LocalDateTime.now().toString());

            Document document = new Document("reply_" + reviewId,
                    reviewContent + " [回复] " + replyContent, metadata);
            vectorStore.add(List.of(document));
            log.info("回复已存入 RAG 库，reviewId: {}", reviewId);
        } catch (Exception e) {
            log.error("存入 RAG 库失败，reviewId: {}", reviewId, e);
        }
    }

    private String loadSkillContent(String fileName) {
        return skillCache.computeIfAbsent(fileName, key -> {
            try {
                ClassPathResource resource = new ClassPathResource("ai/skills/" + key);
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("加载 Skill 文件失败: {}", key, e);
                return "你是客服回复专家，生成专业、得体的差评回复。";
            }
        });
    }
}

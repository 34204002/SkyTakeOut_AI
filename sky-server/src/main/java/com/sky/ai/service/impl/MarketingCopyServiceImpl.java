package com.sky.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.context.BaseContext;
import com.sky.dto.MarketingGenerateDTO;
import com.sky.entity.Dish;
import com.sky.entity.MarketingCaseLibrary;
import com.sky.mapper.DishMapper;
import com.sky.ai.mapper.MarketingCaseLibraryMapper;
import com.sky.ai.service.MarketingCopyService;
import com.sky.ai.util.AiCallUtil;
import com.sky.result.PageResult;
import com.sky.vo.MarketingCaseVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 营销文案生成服务实现类
 */
@Service
@Slf4j
public class MarketingCopyServiceImpl implements MarketingCopyService {

    @Autowired
    private AiCallUtil aiCallUtil;

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private com.sky.mapper.CategoryMapper categoryMapper;

    @Autowired
    private MarketingCaseLibraryMapper caseLibraryMapper;

    /**
     * 营销文案专用的向量存储
     */
    @Autowired
    @Qualifier("marketingVectorStore")
    private SimpleVectorStore vectorStore;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Skill 内容缓存，避免重复读取文件
    private final ConcurrentHashMap<String, String> skillCache = new ConcurrentHashMap<>();

    // 渠道字数限制
    private static final Map<String, Integer> CHANNEL_LIMITS = new HashMap<>();
    static {
        CHANNEL_LIMITS.put("短信", 70);
        CHANNEL_LIMITS.put("微信朋友圈", 200);
        CHANNEL_LIMITS.put("微信小程序", 300);
        CHANNEL_LIMITS.put("抖音", 500);
    }

    // 敏感词列表（示例）
    private static final List<String> SENSITIVE_WORDS = Arrays.asList(
            "最", "第一", "绝对", "100%", " guaranteed"
    );

    @Override
    public List<MarketingCaseVO> generateCopies(MarketingGenerateDTO dto) {
        log.info("开始生成营销文案，dishId: {}, activityType: {}, channel: {}, style: {}",
                dto.getDishId(), dto.getActivityType(), dto.getChannel(), dto.getStyle());

        // 1. 参数校验
        validateParams(dto);

        // 2. 查询菜品信息
        Dish dish = dishMapper.getById(dto.getDishId());
        if (dish == null) {
            throw new RuntimeException("菜品不存在");
        }

        // 3. RAG 检索相似案例
        List<MarketingCaseLibrary> similarCases = retrieveSimilarCases(dish, dto);

        // 4. AI 生成 3 条不同角度的文案
        List<String> copies = generateCopiesByAI(dish, dto, similarCases);

        // 5. 后处理：字数校验和敏感词过滤
        List<MarketingCaseVO> result = postProcessCopies(copies, dto.getChannel());

        log.info("营销文案生成成功，共 {} 条", result.size());
        return result;
    }

    @Override
    public PageResult queryCases(Integer page, Integer pageSize, String dishCategory,
                                 String activityType, String channel) {
        log.info("查询营销案例库，page: {}, pageSize: {}", page, pageSize);

        // 查询所有符合条件的案例
        List<MarketingCaseLibrary> cases;
        
        // 判断是否有有效的筛选条件（非null且非空字符串）
        boolean hasDishCategory = dishCategory != null && !dishCategory.trim().isEmpty();
        boolean hasActivityType = activityType != null && !activityType.trim().isEmpty();
        boolean hasChannel = channel != null && !channel.trim().isEmpty();
        
        if (hasDishCategory && hasActivityType && hasChannel) {
            log.info("使用三条件查询: dishCategory={}, activityType={}, channel={}", dishCategory, activityType, channel);
            cases = caseLibraryMapper.listByCondition(dishCategory, activityType, channel);
        } else if (hasDishCategory) {
            log.info("使用单条件查询: dishCategory={}", dishCategory);
            cases = caseLibraryMapper.listByDishCategory(dishCategory);
        } else {
            // 如果没有筛选条件，返回所有启用的案例
            log.info("无筛选条件，查询所有启用案例");
            cases = caseLibraryMapper.listAll();
        }
        
        log.info("查询到 {} 条案例数据", cases != null ? cases.size() : 0);

        // 转换为 VO
        List<MarketingCaseVO> voList = cases.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());

        // 总数
        int total = voList.size();

        // 分页处理
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, total);
        
        List<MarketingCaseVO> pagedList;
        if (start >= total) {
            pagedList = Collections.emptyList();
        } else {
            pagedList = voList.subList(start, end);
        }

        return new PageResult(total, pagedList);
    }

    @Override
    public void feedbackScore(Long id, BigDecimal score) {
        log.info("反馈效果评分，id: {}, score: {}", id, score);

        MarketingCaseLibrary caseLibrary = caseLibraryMapper.getById(id);
        if (caseLibrary == null) {
            throw new RuntimeException("案例不存在");
        }

        // 更新评分和使用次数
        caseLibrary.setPerformanceScore(score);
        caseLibrary.setUsageCount(caseLibrary.getUsageCount() + 1);
        caseLibrary.setUpdateTime(LocalDateTime.now());
        caseLibrary.setUpdateUser(BaseContext.getCurrentId());

        caseLibraryMapper.update(caseLibrary);
        
        log.info("效果评分更新成功");
    }

    @Override
    public Long publishCopy(MarketingGenerateDTO dto, String selectedCopy, String caseTitle) {
        log.info("发布营销文案，dishId: {}, title: {}", dto.getDishId(), caseTitle);

        // 1. 参数校验
        if (selectedCopy == null || selectedCopy.trim().isEmpty()) {
            throw new RuntimeException("文案内容不能为空");
        }

        // 2. 查询菜品信息
        Dish dish = dishMapper.getById(dto.getDishId());
        if (dish == null) {
            throw new RuntimeException("菜品不存在");
        }

        // 3. 获取菜品分类名称
        String dishCategory = getDishCategoryName(dish.getCategoryId());

        // 4. 构造案例对象
        MarketingCaseLibrary caseLibrary = new MarketingCaseLibrary();
        caseLibrary.setDishCategory(dishCategory);
        caseLibrary.setActivityType(dto.getActivityType());
        caseLibrary.setChannel(dto.getChannel());
        caseLibrary.setStyle(dto.getStyle());
        caseLibrary.setCaseTitle(caseTitle != null ? caseTitle : "自定义文案");
        caseLibrary.setCaseContent(selectedCopy);
        caseLibrary.setCharacterCount(selectedCopy.length());
        caseLibrary.setHasEmoji(containsEmoji(selectedCopy) ? 1 : 0);
        caseLibrary.setPerformanceScore(new BigDecimal("3.0")); // 默认评分
        caseLibrary.setUsageCount(0);
        caseLibrary.setConversionRate(BigDecimal.ZERO);
        caseLibrary.setComplianceNotes("AI生成，待验证");
        caseLibrary.setStatus(1); // 启用
        caseLibrary.setCreateUser(BaseContext.getCurrentId());
        caseLibrary.setCreateTime(LocalDateTime.now());

        // 5. 保存到数据库
        caseLibraryMapper.insert(caseLibrary);
        Long caseId = caseLibrary.getId();
        
        log.info("营销文案已保存到数据库，caseId: {}", caseId);

        // 6. 写入向量库
        try {
            addToVectorStore(caseId, dishCategory, dto.getActivityType(), dto.getChannel(), selectedCopy);
            log.info("文案已写入向量库，caseId: {}", caseId);
        } catch (Exception e) {
            log.error("写入向量库失败，caseId: {}", caseId, e);
            // 向量库写入失败不影响主流程
        }

        return caseId;
    }

    /**
     * 检测是否包含 emoji
     */
    private boolean containsEmoji(String text) {
        // 简单检测常见 emoji
        return text.contains("🔥") || text.contains("✨") || text.contains("⏰") || 
               text.contains("🎉") || text.contains("❤️") || text.contains("⭐");
    }

    /**
     * 将文案写入向量库
     */
    private void addToVectorStore(Long caseId, String dishCategory, String activityType, 
                                  String channel, String content) {
        try {
            // 1. 构造元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("caseId", caseId);
            metadata.put("dishCategory", dishCategory);
            metadata.put("activityType", activityType);
            metadata.put("channel", channel);
            metadata.put("timestamp", LocalDateTime.now().toString());

            // 2. 构造检索文本（分类 + 活动类型 + 渠道 + 内容）
            String textToEmbed = String.format("%s %s %s %s", 
                    dishCategory, activityType, channel, content);

            // 3. 创建 Document 并存入向量库
            Document document = new Document(
                    "marketing_" + caseId,
                    textToEmbed,
                    metadata
            );

            vectorStore.add(List.of(document));
            log.debug("文案已存入向量库，caseId: {}", caseId);
        } catch (Exception e) {
            log.error("存入向量库失败，caseId: {}", caseId, e);
            throw new RuntimeException("向量库写入失败", e);
        }
    }

    /**
     * 参数校验
     */
    private void validateParams(MarketingGenerateDTO dto) {
        if (dto.getDishId() == null) {
            throw new RuntimeException("菜品ID不能为空");
        }
        if (dto.getActivityType() == null || dto.getActivityType().trim().isEmpty()) {
            throw new RuntimeException("活动类型不能为空");
        }
        if (dto.getChannel() == null || dto.getChannel().trim().isEmpty()) {
            throw new RuntimeException("投放渠道不能为空");
        }
    }

    /**
     * RAG 检索相似案例
     */
    private List<MarketingCaseLibrary> retrieveSimilarCases(Dish dish, MarketingGenerateDTO dto) {
        try {
            // 构造查询文本：菜品分类 + 活动类型 + 渠道
            String queryText = String.format("%s %s %s",
                    dish.getCategoryId(),
                    dto.getActivityType(),
                    dto.getChannel());

            // 执行向量相似度搜索（带相似度阈值）
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(queryText)
                            .topK(5)
                            .similarityThreshold(0.5)
                            .build()
            );

            // 将 Document 转换为 MarketingCaseLibrary
            return results.stream().map(doc -> {
                MarketingCaseLibrary library = new MarketingCaseLibrary();
                library.setCaseContent(doc.getText());
                library.setDishCategory("retrieved_from_vector");
                return library;
            }).collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("RAG 向量检索失败，降级为 SQL 检索", e);
            return fallbackSqlRetrieve(dish, dto);
        }
    }

    /**
     * 降级策略：SQL 检索
     */
    private List<MarketingCaseLibrary> fallbackSqlRetrieve(Dish dish, MarketingGenerateDTO dto) {
        // 根据菜品分类、活动类型、渠道查询
        List<MarketingCaseLibrary> cases = caseLibraryMapper.listByCondition(
                getDishCategoryName(dish.getCategoryId()),
                dto.getActivityType(),
                dto.getChannel()
        );

        if (cases == null || cases.isEmpty()) {
            // 降级：只按菜品分类查询
            cases = caseLibraryMapper.listByDishCategory(
                    getDishCategoryName(dish.getCategoryId())
            );
        }

        return cases.stream().limit(5).collect(Collectors.toList());
    }

    /**
     * 根据分类ID获取分类名称
     */
    private String getDishCategoryName(Long categoryId) {
        if (categoryId == null) {
            return "未知分类";
        }
        
        try {
            com.sky.entity.Category category = categoryMapper.getById(categoryId);
            return category != null ? category.getName() : "未知分类";
        } catch (Exception e) {
            log.warn("查询菜品分类失败，categoryId: {}", categoryId, e);
            return "未知分类";
        }
    }

    /**
     * AI 生成 3 条不同角度的文案（优化版：一次调用生成3条）
     */
    private List<String> generateCopiesByAI(Dish dish, MarketingGenerateDTO dto,
                                            List<MarketingCaseLibrary> similarCases) {
        // 构造参考案例字符串
        String casesStr = similarCases.isEmpty() ? "无参考案例" :
                similarCases.stream()
                        .map(MarketingCaseLibrary::getCaseContent)
                        .reduce((a, b) -> a + "\n---\n" + b)
                        .orElse("无参考案例");

        // 获取渠道字数限制
        int charLimit = CHANNEL_LIMITS.getOrDefault(dto.getChannel(), 200);

        // 构造 Prompt，要求 AI 一次性生成 3 条不同角度文案
        String prompt = buildBatchPrompt(dish, dto, casesStr, charLimit);
        
        try {
            log.info("开始批量生成 3 条文案...");
            String response = aiCallUtil.callChatModel(prompt);
            
            // 清理 Markdown 格式
            response = response.replaceAll("```", "").trim();
            
            // 解析并分割成 3 条文案
            List<String> copies = parseBatchResponse(response);
            
            log.info("批量生成成功，共 {} 条文案", copies.size());
            return copies;
            
        } catch (Exception e) {
            log.error("AI 批量生成文案失败", e);
            // 降级：使用模板生成 3 条
            return generateFallbackCopies(dish, dto);
        }
    }

    /**
     * 构造 Prompt
     */
    private String buildPrompt(Dish dish, MarketingGenerateDTO dto, String casesStr,
                               int charLimit, String angle) {
        return String.format(
                "你是一个专业的营销文案创作助手。请根据以下信息生成一条吸引人的营销文案。\n\n" +
                "【菜品信息】\n" +
                "名称：%s\n" +
                "价格：%.2f元\n" +
                "描述：%s\n\n" +
                "【活动信息】\n" +
                "活动类型：%s\n" +
                "投放渠道：%s\n" +
                "文案风格：%s\n" +
                "文案角度：%s\n\n" +
                "【参考案例】\n%s\n\n" +
                "【要求】\n" +
                "1. 字数控制在 %d 字以内\n" +
                "2. 语气符合'%s'风格\n" +
                "3. 突出'%s'角度\n" +
                "4. 适当使用emoji增强吸引力\n" +
                "5. 不要使用'最'、'第一'、'绝对'等违禁词\n" +
                "6. 直接输出文案内容，不要加引号或其他说明\n\n" +
                "文案：",
                dish.getName(),
                dish.getPrice(),
                dish.getDescription() != null ? dish.getDescription() : "无",
                dto.getActivityType(),
                dto.getChannel(),
                dto.getStyle(),
                angle,
                casesStr,
                charLimit,
                dto.getStyle(),
                angle
        );
    }

    /**
     * 构造批量生成 Prompt（一次生成3条）
     */
    private String buildBatchPrompt(Dish dish, MarketingGenerateDTO dto, String casesStr, int charLimit) {
        // 加载 Skill 文件
        String skillContent = loadSkillContent("marketing-copywriter.md");
        
        return String.format(
                "%s\n\n【任务】根据以下信息生成 3 条不同角度的营销文案：\n" +
                "【菜品信息】\n" +
                "名称：%s\n" +
                "价格：%.2f元\n" +
                "描述：%s\n\n" +
                "【活动信息】\n" +
                "活动类型：%s\n" +
                "投放渠道：%s\n" +
                "文案风格：%s\n\n" +
                "【参考案例】\n%s\n\n" +
                "【要求】\n" +
                "1. 生成 3 条文案，分别从以下角度：\n" +
                "   - 文案1：优惠导向（突出价格优势、折扣力度）\n" +
                "   - 文案2：特色导向（突出食材品质、制作工艺）\n" +
                "   - 文案3：紧迫感导向（突出限时限量、先到先得）\n" +
                "2. 每条文案字数控制在 %d 字以内\n" +
                "3. **严格按以下格式输出**，用 '|||' 分隔3条文案：\n" +
                "   文案1内容|||文案2内容|||文案3内容\n" +
                "4. 直接输出文案内容，不要加序号、引号或其他说明\n\n" +
                "输出：",
                skillContent,
                dish.getName(),
                dish.getPrice(),
                dish.getDescription() != null ? dish.getDescription() : "无",
                dto.getActivityType(),
                dto.getChannel(),
                dto.getStyle(),
                casesStr,
                charLimit
        );
    }

    /**
     * 解析批量生成的响应，分割成3条文案
     */
    private List<String> parseBatchResponse(String response) {
        List<String> copies = new ArrayList<>();
        
        // 按 '|||' 分割
        String[] parts = response.split("\\|\\|\\|");
        
        if (parts.length >= 3) {
            // 取前3条
            for (int i = 0; i < 3; i++) {
                String copy = parts[i].trim();
                if (!copy.isEmpty()) {
                    copies.add(copy);
                }
            }
        } else if (parts.length > 0) {
            // 如果分割失败，尝试按换行符分割
            String[] lines = response.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("文案") && copies.size() < 3) {
                    copies.add(line);
                }
            }
        }
        
        // 如果还是不足3条，用降级方案补齐
        while (copies.size() < 3) {
            copies.add("AI生成文案" + (copies.size() + 1));
        }
        
        return copies.subList(0, 3); // 确保只返回3条
    }

    /**
     * 降级策略：模板生成单条
     */
    private String generateFallbackCopy(Dish dish, MarketingGenerateDTO dto, String angle) {
        String template;
        
        switch (angle) {
            case "优惠导向":
                template = "🔥限时优惠！%s 仅需 %.2f元！%s活动进行中，快来品尝吧～";
                break;
            case "特色导向":
                template = "✨精选好食材，匠心制作！%s，%.2f元即可享受美味，不容错过！";
                break;
            case "紧迫感导向":
                template = "⏰最后机会！%s 特价 %.2f元！数量有限，先到先得！";
                break;
            default:
                template = "🎉推荐美食：%s，价格 %.2f元，欢迎品尝！";
        }

        return String.format(template, dish.getName(), dish.getPrice());
    }

    /**
     * 降级策略：模板生成3条
     */
    private List<String> generateFallbackCopies(Dish dish, MarketingGenerateDTO dto) {
        List<String> copies = new ArrayList<>();
        copies.add(generateFallbackCopy(dish, dto, "优惠导向"));
        copies.add(generateFallbackCopy(dish, dto, "特色导向"));
        copies.add(generateFallbackCopy(dish, dto, "紧迫感导向"));
        return copies;
    }

    /**
     * 后处理：字数校验和敏感词过滤
     */
    private List<MarketingCaseVO> postProcessCopies(List<String> copies, String channel) {
        int charLimit = CHANNEL_LIMITS.getOrDefault(channel, 200);
        List<MarketingCaseVO> result = new ArrayList<>();

        for (int i = 0; i < copies.size(); i++) {
            String copy = copies.get(i);

            // 敏感词过滤
            copy = filterSensitiveWords(copy);

            // 字数截断（如果超出限制）
            if (copy.length() > charLimit) {
                copy = copy.substring(0, charLimit - 3) + "...";
            }

            // 构造 VO
            MarketingCaseVO vo = MarketingCaseVO.builder()
                    .caseTitle("文案" + (i + 1))
                    .caseContent(copy)
                    .characterCount(copy.length())
                    .hasEmoji(copy.contains("🔥") || copy.contains("✨") || copy.contains("⏰") ? 1 : 0)
                    .channel(channel)
                    .build();

            result.add(vo);
        }

        return result;
    }

    /**
     * 敏感词过滤
     */
    private String filterSensitiveWords(String text) {
        String filtered = text;
        for (String word : SENSITIVE_WORDS) {
            filtered = filtered.replaceAll(word, "");
        }
        return filtered;
    }

    /**
     * 转换为 VO
     */
    private MarketingCaseVO convertToVO(MarketingCaseLibrary library) {
        MarketingCaseVO vo = new MarketingCaseVO();
        BeanUtils.copyProperties(library, vo);
        return vo;
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
                return "你是营销文案专家，为餐饮菜品生成吸引人的推广文案。生成3条不同角度：优惠导向、特色导向、紧迫感导向。遵循渠道规范和广告法要求。";
            }
        });
    }
}

package com.sky.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * SimpleVectorStore 配置类
 * 提供基于文件的持久化向量存储
 * 为不同业务场景创建独立的向量存储实例
 */
@Configuration
@Slf4j
public class VectorStoreConfig {

    public static final String DATA_DIR = "data";

    public static final String REVIEW_VECTOR_STORE_FILE = DATA_DIR + "/vector-store-review.json";
    public static final String MARKETING_VECTOR_STORE_FILE = DATA_DIR + "/vector-store-marketing.json";
    public static final String CUSTOMER_SERVICE_VECTOR_STORE_FILE = DATA_DIR + "/vector-store-customer-service.json";

    @Autowired
    private EmbeddingModel embeddingModel;

    @Bean("reviewVectorStore")
    public SimpleVectorStore reviewVectorStore() {
        return createVectorStore(REVIEW_VECTOR_STORE_FILE, "差评回复");
    }

    @Bean("marketingVectorStore")
    public SimpleVectorStore marketingVectorStore() {
        return createVectorStore(MARKETING_VECTOR_STORE_FILE, "营销文案");
    }

    @Bean("customerServiceVectorStore")
    public SimpleVectorStore customerServiceVectorStore() {
        return createVectorStore(CUSTOMER_SERVICE_VECTOR_STORE_FILE, "客服知识库");
    }

    private SimpleVectorStore createVectorStore(String fileName, String logPrefix) {
        log.info("初始化{}向量存储: {}", logPrefix, fileName);

        ensureDataDirExists();

        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        File storeFile = new File(fileName);

        if (storeFile.exists()) {
            try {
                vectorStore.load(storeFile);
                log.info("{}向量数据加载成功: {}", logPrefix, storeFile.getAbsolutePath());
            } catch (Exception e) {
                log.error("加载{}向量数据失败", logPrefix, e);
            }
        } else {
            log.info("{}向量存储文件不存在，将创建新的向量库", logPrefix);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                vectorStore.save(storeFile);
                log.info("{}向量数据已保存到: {}", logPrefix, storeFile.getAbsolutePath());
            } catch (Exception e) {
                log.error("保存{}向量数据失败", logPrefix, e);
            }
        }));

        return vectorStore;
    }

    private void ensureDataDirExists() {
        File dir = new File(DATA_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
            log.info("数据目录已创建: {}", dir.getAbsolutePath());
        }
    }
}

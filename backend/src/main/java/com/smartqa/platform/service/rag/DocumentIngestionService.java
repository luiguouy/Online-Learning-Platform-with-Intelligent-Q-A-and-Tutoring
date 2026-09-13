package com.smartqa.platform.service.rag;

import com.smartqa.platform.config.RagConfigProperties;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

/**
 * 课件文档解析与向量入库服务（A1.4）
 *
 * 职责：
 *   1. 接收上传的课件文件字节流（PDF / DOCX / TXT / MD 等）
 *   2. 使用 Apache Tika 提取纯文本
 *   3. 按配置的 chunk.size / chunk.overlap 递归分块
 *   4. 为每个片段注入元数据（courseId / docId / fileName / chunkIndex，camelCase 键名）
 *   5. 调用本地 BGE-Small-ZH 量化向量模型计算嵌入，写入 Chroma（或 InMemory）向量存储
 *
 * 注意：此服务为同步阻塞调用，建议在 Controller 层通过 AsyncThreadPoolConfig
 *       的异步线程池包裹，避免阻塞 Tomcat 工作线程。
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RagConfigProperties ragProps;

    public DocumentIngestionService(EmbeddingModel embeddingModel,
                                    EmbeddingStore<TextSegment> embeddingStore,
                                    RagConfigProperties ragProps) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.ragProps = ragProps;
    }

    /**
     * 解析并向量化入库课件文件
     *
     * @param inputStream  课件文件字节流
     * @param fileName     原始文件名（写入 Metadata，检索溯源用）
     * @param courseId     课程 ID（写入 Metadata，检索时租户级过滤依赖此键）
     * @param docId        课件记录 ID（写入 Metadata，级联删除依赖此键；由成员 B 的 course_document 主键提供）
     * @param uploadedBy   上传用户 ID（审计用途）
     * @return 实际入库的文本块数量
     */
    public int ingest(InputStream inputStream, String fileName, Long courseId, Long docId, Long uploadedBy) {
        log.info("[RAG-Ingest] 开始解析课件文件: {}, courseId={}, docId={}, uploadedBy={}",
                fileName, courseId, docId, uploadedBy);

        // ── 第一步：Apache Tika 提取纯文本 ──────────────────────────────────
        DocumentParser parser = new ApacheTikaDocumentParser();
        Document document;
        try {
            document = parser.parse(inputStream);
        } catch (Exception e) {
            log.error("[RAG-Ingest] 文件解析失败: {}", fileName, e);
            throw new RuntimeException("课件文件解析失败，请检查文件格式: " + fileName, e);
        }

        String text = document.text();
        if (text == null || text.isBlank()) {
            log.warn("[RAG-Ingest] 文件内容为空或无法提取文本，跳过入库: {}", fileName);
            return 0;
        }
        log.info("[RAG-Ingest] Tika 解析完成，文本长度: {} 字符", text.length());

        // ── 第二步：切块 → 元数据注入 → 向量化 → 写入 ────────────────────────
        return splitEmbedAndStore(text, fileName, courseId, docId);
    }

    /**
     * 直接从纯文本字符串入库（用于 Markdown / 纯文本直接提交场景）
     *
     * @param text      文本内容
     * @param fileName  来源文件名（检索溯源展示用）
     * @param courseId  课程 ID
     * @param docId     课件记录 ID（无数据库主键时可传业务唯一标识）
     * @return 实际入库的文本块数量
     */
    public int ingestText(String text, String fileName, Long courseId, Long docId) {
        if (text == null || text.isBlank()) {
            log.warn("[RAG-Ingest] 文本为空，跳过入库，docId={}", docId);
            return 0;
        }

        log.info("[RAG-Ingest] 开始文本直接入库，docId={}, courseId={}, 长度={}", docId, courseId, text.length());
        return splitEmbedAndStore(text, fileName, courseId, docId);
    }

    /**
     * 核心入库管线：递归切块 → 逐块注入元数据（camelCase 键名）→ BGE 向量化 → 批量写入向量存储。
     *
     * ⚠️ 元数据键名必须为 courseId / docId / fileName / chunkIndex（camelCase），
     *    与检索层 IsEqualTo("courseId", ...) 严格一致，写错将导致租户过滤静默失效。
     *
     * 说明：此处不用 EmbeddingStoreIngestor，因为它无法在切块后、写入前逐块注入
     *      chunkIndex 等差异化元数据；手动 split → embedAll → addAll 是等价正式 API。
     */
    private int splitEmbedAndStore(String text, String fileName, Long courseId, Long docId) {
        Document document = Document.from(text);

        RagConfigProperties.Chunk chunkCfg = ragProps.getChunk();
        DocumentSplitter splitter = DocumentSplitters.recursive(
                chunkCfg.getSize(),
                chunkCfg.getOverlap()
        );
        List<TextSegment> segments = splitter.split(document);

        // 元数据增强：每个片段携带 课程/文档/文件名/序号 四要素
        for (int i = 0; i < segments.size(); i++) {
            Metadata metadata = segments.get(i).metadata();
            metadata.put("courseId", String.valueOf(courseId));
            metadata.put("docId", String.valueOf(docId));
            metadata.put("fileName", fileName);
            metadata.put("chunkIndex", i);
        }

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);

        log.info("[RAG-Ingest] 向量入库完成，fileName={}, docId={}, 共 {} 个片段", fileName, docId, segments.size());
        return segments.size();
    }

    /**
     * 级联清理：删除/重建课件时必须同步删除向量库中该课件的全部切块，防止"幽灵参考资料"。
     * 成员 B 的课件删除接口与 reindex 接口必须先调用本方法。
     *
     * @param docId 课件记录 ID（course_document 主键）
     */
    public void removeDocumentVectors(Long docId) {
        embeddingStore.removeAll(new IsEqualTo("docId", String.valueOf(docId)));
        log.info("[RAG-Ingest] 课件向量切块已级联清理, docId={}", docId);
    }
}

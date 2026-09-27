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
import jakarta.annotation.PreDestroy;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xml.sax.ContentHandler;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

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
 *
 * 解析安全上限（M2 加固，上限可由 rag.ingest.* 配置）：
 *   1. 输入字节上限：解析流读取超过 max-file-bytes 立即终止（防解压炸弹）
 *   2. 提取文本上限：Tika 写出超过 max-text-chars 抛 WriteLimitReachedException
 *   3. 解析时长上限：超 max-parse-timeout-seconds 强制取消（防畸形文档卡死切块线程）
 *   超限均抛 RuntimeException，由 Controller 层 markFailed 写入明确错误信息。
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RagConfigProperties ragProps;

    /** 带解析上限的 Tika 解析器（每次 parse 时 supplier 新建组件，线程安全可复用） */
    private final DocumentParser parser;

    /** 仅用于执行带超时的 parse 调用；独立于业务池，避免与 ingestExecutor 互相占线程死锁。daemon 线程不阻塞 JVM 退出 */
    private final ExecutorService parseTimeoutExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tika-parse-guard");
        t.setDaemon(true);
        return t;
    });

    public DocumentIngestionService(EmbeddingModel embeddingModel,
                                    EmbeddingStore<TextSegment> embeddingStore,
                                    RagConfigProperties ragProps) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.ragProps = ragProps;

        RagConfigProperties.Ingest limits = ragProps.getIngest();
        // BodyContentHandler(writeLimit)：Tika 写出文本超过上限即抛 WriteLimitReachedException，
        // 不会把整个文档内容先吃进内存再判断；流包装 SizeLimitInputStream 限制输入读取字节。
        Supplier<Parser> tikaParser = AutoDetectParser::new;
        Supplier<ContentHandler> limitedHandler = () -> new BodyContentHandler(limits.getMaxTextChars());
        this.parser = new ApacheTikaDocumentParser(tikaParser, limitedHandler, null, null);
    }

    @PreDestroy
    void shutdownParseGuard() {
        parseTimeoutExecutor.shutdownNow();
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

        // ── 第一步：Apache Tika 提取纯文本（带三层安全上限） ────────────────
        Document document = parseWithLimits(inputStream, fileName);

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
     * 带三层安全上限的 Tika 解析：
     *   1. SizeLimitInputStream 限制输入读取字节（防解压炸弹）；
     *   2. BodyContentHandler(writeLimit) 限制提取文本字符数；
     *   3. parseTimeoutExecutor 限时等待，超时强制取消。
     *
     * <p>超时后 cancel(true) 中断解析线程；Tika 对中断响应有限，但该线程在 daemon
     * 池中（空闲自动回收），不会阻塞 JVM 退出，也不占用业务线程池。</p>
     *
     * @throws RuntimeException 超限时携带面向用户的明确错误信息（由 Controller markFailed 回写）
     */
    private Document parseWithLimits(InputStream inputStream, String fileName) {
        RagConfigProperties.Ingest limits = ragProps.getIngest();
        Future<Document> future = parseTimeoutExecutor.submit(
                () -> parser.parse(new SizeLimitInputStream(inputStream, limits.getMaxFileBytes())));
        try {
            return future.get(limits.getParseTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("[RAG-Ingest] 课件解析超时（>{}s）: {}", limits.getParseTimeoutSeconds(), fileName);
            throw new RuntimeException("课件解析超时（超过 " + limits.getParseTimeoutSeconds()
                    + " 秒），请拆分或简化文档后重新上传: " + fileName);
        } catch (ExecutionException e) {
            throw translateParseError(e.getCause(), fileName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new RuntimeException("课件解析被中断: " + fileName, e);
        }
    }

    /**
     * 将 Tika 解析异常翻译为面向用户的明确错误信息。
     * langchain4j 会把底层异常包在 RuntimeException 里，因此需要沿 cause 链识别具体超限类型。
     */
    private RuntimeException translateParseError(Throwable cause, String fileName) {
        if (findInChain(cause, WriteLimitReachedException.class) != null) {
            return new RuntimeException("课件文本内容超过解析上限（"
                    + ragProps.getIngest().getMaxTextChars() + " 字符），请拆分后重新上传: " + fileName);
        }
        if (findInChain(cause, FileSizeLimitExceededException.class) != null) {
            return new RuntimeException("课件文件超过解析大小上限（"
                    + (ragProps.getIngest().getMaxFileBytes() / 1024 / 1024) + " MB），请拆分后重新上传: " + fileName);
        }
        log.error("[RAG-Ingest] 文件解析失败: {}", fileName, cause);
        return new RuntimeException("课件文件解析失败，请检查文件格式: " + fileName, cause);
    }

    /** 沿异常 cause 链查找指定类型（含自引用环防护） */
    private static <T extends Throwable> T findInChain(Throwable t, Class<T> type) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (type.isInstance(cur)) {
                return type.cast(cur);
            }
            if (cur.getCause() == cur) {
                break;
            }
        }
        return null;
    }

    /** 输入流读取超出上限时抛出的内部标记异常（仅作为类型判据，不直接暴露给用户） */
    static final class FileSizeLimitExceededException extends IOException {
        FileSizeLimitExceededException(String message) {
            super(message);
        }
    }

    /**
     * 限制读取字节数的输入流：累计读取超过 maxBytes 即抛 {@link FileSizeLimitExceededException}。
     *
     * <p>禁用 mark/reset：允许 reset 会导致重复计数、漏判大文件（安全优先）；
     * Tika 对不可 mark 的流会自行缓冲，不影响解析正确性。</p>
     */
    static final class SizeLimitInputStream extends FilterInputStream {

        private long remaining;

        SizeLimitInputStream(InputStream in, long maxBytes) {
            super(in);
            this.remaining = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1 && --remaining < 0) {
                throw new FileSizeLimitExceededException("input exceeds limit");
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0 && (remaining -= n) < 0) {
                throw new FileSizeLimitExceededException("input exceeds limit");
            }
            return n;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public synchronized void mark(int readlimit) {
            // 不支持 mark，空实现
        }

        @Override
        public synchronized void reset() throws IOException {
            throw new IOException("mark/reset not supported");
        }
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

        if (text.length() > ragProps.getIngest().getMaxTextChars()) {
            throw new RuntimeException("课件文本内容超过解析上限（"
                    + ragProps.getIngest().getMaxTextChars() + " 字符），请拆分后重新上传: " + fileName);
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

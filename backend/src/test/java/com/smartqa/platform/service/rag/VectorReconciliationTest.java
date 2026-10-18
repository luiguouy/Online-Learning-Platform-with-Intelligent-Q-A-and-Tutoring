package com.smartqa.platform.service.rag;

import com.smartqa.platform.service.CourseDocumentService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #62 逻辑删除课件切片对账兜底单元测试。
 */
@SpringBootTest
@ActiveProfiles("test")
class VectorReconciliationTest {

    private static final long TEST_DOC_ID_OLD = 99901L;
    private static final long TEST_DOC_ID_NEW = 99902L;

    @Autowired
    private CourseDocumentService docService;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanDb();
    }

    @AfterEach
    void tearDown() {
        cleanDb();
    }

    private void cleanDb() {
        jdbcTemplate.update("DELETE FROM course_document WHERE id IN (?, ?)", TEST_DOC_ID_OLD, TEST_DOC_ID_NEW);
        embeddingStore.removeAll(new IsEqualTo("docId", String.valueOf(TEST_DOC_ID_OLD)));
        embeddingStore.removeAll(new IsEqualTo("docId", String.valueOf(TEST_DOC_ID_NEW)));
    }

    @Test
    @DisplayName("#62 对账机制自动扫描已逻辑删除且超静默期的孤儿课件并清理向量切片")
    void testReconcileCleansDeletedDocVectors() {
        // 1. 模拟插入一条 20 分钟前逻辑删除的课件记录 (is_deleted = 1)
        LocalDateTime twentyMinutesAgo = LocalDateTime.now().minusMinutes(20);
        jdbcTemplate.update("""
            INSERT INTO course_document (id, course_id, file_name, file_path, file_size, file_type, chunk_count, parse_status, is_deleted, created_at, updated_at)
            VALUES (?, 1, 'orphan.pdf', '/tmp/orphan.pdf', 1024, 'pdf', 1, 'CHUNKED', 1, ?, ?)
            """, TEST_DOC_ID_OLD, twentyMinutesAgo, twentyMinutesAgo);

        // 2. 模拟切片写入向量库
        TextSegment segment = TextSegment.from("孤儿课件切块内容", dev.langchain4j.data.document.Metadata.from("docId", String.valueOf(TEST_DOC_ID_OLD)));
        embeddingStore.add(dev.langchain4j.data.embedding.Embedding.from(new float[]{1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f}), segment);

        // 3. 执行对账（静默期 10 分钟）
        int cleaned = docService.reconcileDeletedDocumentVectors(10);
        assertTrue(cleaned >= 1, "应至少扫描并清理 1 条孤儿切片课件");
    }

    @Test
    @DisplayName("#62 对账机制保护在途删除记录：静默期内的删除记录不被提前清理")
    void testReconcileProtectsInFlightDeletions() {
        // 模拟插入一条 1 分钟前逻辑删除的课件记录（处于在途安全保护窗口）
        LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
        jdbcTemplate.update("""
            INSERT INTO course_document (id, course_id, file_name, file_path, file_size, file_type, chunk_count, parse_status, is_deleted, created_at, updated_at)
            VALUES (?, 1, 'inflight.pdf', '/tmp/inflight.pdf', 1024, 'pdf', 1, 'CHUNKED', 1, ?, ?)
            """, TEST_DOC_ID_NEW, oneMinuteAgo, oneMinuteAgo);

        // 静默期 10 分钟，1 分钟前的记录应被过滤掉不处理
        int cleaned = docService.reconcileDeletedDocumentVectors(10);
        assertEquals(0, cleaned, "静默期内的在途记录不得被清理，避免与并发切块产生竞态");
    }
}

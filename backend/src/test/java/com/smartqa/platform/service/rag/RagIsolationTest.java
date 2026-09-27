package com.smartqa.platform.service.rag;

import com.smartqa.platform.config.RagConfigProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhq.BgeSmallZhQuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 课程级隔离验证测试（A1.4/A1.5 验收项）
 *
 * 纯本地测试：BGE 向量模型 + InMemory 向量库，不依赖 DeepSeek Key / Chroma / MySQL。
 * 验证两个关键点：
 *   1. 入库元数据注入（courseId / docId / chunkIndex）确实写入每个切块
 *   2. 检索时 IsEqualTo("courseId", ...) 过滤生效——课程 1 的问题绝不召回课程 2 的切块
 */
class RagIsolationTest {

    private static EmbeddingModel embeddingModel;
    private InMemoryEmbeddingStore<TextSegment> store;
    private DocumentIngestionService ingestionService;
    private RagConfigProperties ragProps;

    /** 《操作系统》课件片段 */
    private static final String OS_DOC = """
            虚拟内存是计算机系统内存管理的一种技术。它使得应用程序认为它拥有连续的可用内存，\
            而实际上，它通常是被分隔成多个物理内存碎片，还有部分暂时存储在外部磁盘存储器上，\
            在需要时进行数据交换。页面置换算法包括FIFO、LRU和OPT等。""";

    /** 《高等数学》课件片段 */
    private static final String MATH_DOC = """
            极限的严格定义：设函数f(x)在x0的某一去心邻域内有定义。对于任意给定的正数epsilon，\
            总存在正数delta，使得当x满足0小于x减x0的绝对值且小于delta时，对应的函数值f(x)\
            满足f(x)减A的绝对值小于epsilon，则称A为函数f(x)当x趋于x0时的极限。""";

    @BeforeAll
    static void initModel() {
        // BGE 模型加载约 45MB，整个测试类共享一次
        embeddingModel = new BgeSmallZhQuantizedEmbeddingModel();
    }

    private void setup(String osCourseId, String mathCourseId) {
        store = new InMemoryEmbeddingStore<>();
        ragProps = new RagConfigProperties();
        ragProps.getChunk().setSize(400);
        ragProps.getChunk().setOverlap(50);
        ragProps.getChunk().setTopK(4);
        ragProps.getChunk().setSimilarityThreshold(0.70);
        ingestionService = new DocumentIngestionService(embeddingModel, store, ragProps);

        int osChunks = ingestionService.ingestText(OS_DOC, "操作系统-内存管理.md",
                Long.valueOf(osCourseId), 101L);
        int mathChunks = ingestionService.ingestText(MATH_DOC, "高等数学-极限.md",
                Long.valueOf(mathCourseId), 202L);
        assertTrue(osChunks >= 1, "OS 课件应至少切出 1 块");
        assertTrue(mathChunks >= 1, "数学课件应至少切出 1 块");
    }

    private List<EmbeddingMatch<TextSegment>> searchWithCourseFilter(String question, String courseId) {
        Embedding queryEmbedding = embeddingModel.embed(question).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .filter(new dev.langchain4j.store.embedding.filter.comparison.IsEqualTo("courseId", courseId))
                .maxResults(ragProps.getChunk().getTopK())
                .minScore(ragProps.getChunk().getSimilarityThreshold())
                .build();
        EmbeddingSearchResult<TextSegment> result = store.search(request);
        return result.matches();
    }

    @Test
    @DisplayName("元数据注入：每个切块携带 courseId / docId / fileName / chunkIndex")
    void metadataFullyInjected() {
        setup("1", "2");
        // 无条件搜一遍（用一个高相似度问法拿回 OS 切块检查元数据）
        List<EmbeddingMatch<TextSegment>> matches = searchWithCourseFilter(
                "虚拟内存是什么 页面置换算法", "1");
        assertFalse(matches.isEmpty(), "课程1内检索应有召回");
        for (EmbeddingMatch<TextSegment> m : matches) {
            var md = m.embedded().metadata();
            assertTrue("1".equals(md.getString("courseId")), "courseId 键缺失或错误");
            assertTrue("101".equals(md.getString("docId")), "docId 键缺失或错误");
            assertTrue(md.getString("fileName") != null && md.getString("fileName").contains("操作系统"),
                    "fileName 键缺失或错误");
            assertTrue(md.getInteger("chunkIndex") != null, "chunkIndex 键缺失或错误");
        }
    }

    @Test
    @DisplayName("跨课程隔离：在课程1问数学题，绝不召回课程2的切块")
    void crossCourseIsolation() {
        setup("1", "2");
        // 用与数学课件高度相似的问题，在课程1（只有OS内容）里检索
        List<EmbeddingMatch<TextSegment>> inCourse1 = searchWithCourseFilter(
                "极限的epsilon-delta定义是什么", "1");
        for (EmbeddingMatch<TextSegment> m : inCourse1) {
            assertTrue("1".equals(m.embedded().metadata().getString("courseId")),
                    "课程1检索结果混入了其他课程内容！");
        }

        // 对照：同样的问题在课程2里检索，必须能召回数学切块（证明过滤不是"一律搜不到"）
        List<EmbeddingMatch<TextSegment>> inCourse2 = searchWithCourseFilter(
                "极限的epsilon-delta定义是什么", "2");
        assertFalse(inCourse2.isEmpty(), "课程2内检索应召回数学切块");
        assertTrue(inCourse2.get(0).embedded().text().contains("极限"),
                "课程2召回内容应为数学课件");
    }

    @Test
    @DisplayName("级联删除：removeDocumentVectors 后该课件切块不再被召回")
    void cascadeRemovalWorks() {
        setup("1", "2");
        ingestionService.removeDocumentVectors(101L);
        List<EmbeddingMatch<TextSegment>> afterDelete = searchWithCourseFilter(
                "虚拟内存是什么", "1");
        assertTrue(afterDelete.isEmpty(), "删除 docId=101 后课程1不应再召回任何切块");

        // 课程2不受影响
        List<EmbeddingMatch<TextSegment>> mathStill = searchWithCourseFilter(
                "极限的定义", "2");
        assertFalse(mathStill.isEmpty(), "级联删除不应波及其他课件");
    }
}

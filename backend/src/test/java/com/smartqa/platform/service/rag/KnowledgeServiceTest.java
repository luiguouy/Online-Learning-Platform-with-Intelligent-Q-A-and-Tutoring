package com.smartqa.platform.service.rag;

import com.smartqa.platform.config.RagConfigProperties;
import com.smartqa.platform.dto.KnowledgeGenerateDTO;
import com.smartqa.platform.vo.KnowledgeGenerateVO;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhq.BgeSmallZhQuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识点解析服务（A2.5）单元测试。
 *
 * <p>纯本地：BGE 向量模型 + InMemory 向量库 + Mockito 打桩阻塞式大模型，
 * 不依赖 MySQL / Chroma / DeepSeek Key。覆盖验收标准：</p>
 * <ol>
 *   <li>返回结构化精解：VO 回填 courseId / knowledgePoint / content；</li>
 *   <li>Prompt 纪律：注入本课课件上下文（courseId 隔离）、要求两章结构、禁止自测题；</li>
 *   <li>课件未命中时降级为无参考资料仍能生成（不抛异常）。</li>
 * </ol>
 */
class KnowledgeServiceTest {

    private static final String MOCK_MARKDOWN =
            "## 一、核心概念定义与原理\n虚拟内存是内存管理技术。\n\n## 二、核心难点深度辨析与常见陷阱\n易混淆点辨析。";

    private static final String OS_DOC = """
            虚拟内存是计算机系统内存管理的一种技术。它使得应用程序认为它拥有连续的可用内存，\
            而实际上，它通常是被分隔成多个物理内存碎片，还有部分暂时存储在外部磁盘存储器上，\
            在需要时进行数据交换。页面置换算法包括FIFO、LRU和OPT等。""";

    private static EmbeddingModel embeddingModel;

    private RagConfigProperties ragProps;
    private InMemoryEmbeddingStore<TextSegment> store;
    private ChatLanguageModel chatModel;
    private KnowledgeService knowledgeService;

    @BeforeAll
    static void initModel() {
        embeddingModel = new BgeSmallZhQuantizedEmbeddingModel();
    }

    @BeforeEach
    void setUp() {
        ragProps = new RagConfigProperties();
        ragProps.getChunk().setSize(400);
        ragProps.getChunk().setOverlap(50);
        ragProps.getChunk().setTopK(4);
        ragProps.getChunk().setSimilarityThreshold(0.70);

        store = new InMemoryEmbeddingStore<>();
        // 课程 1 灌入 OS 课件；课程 2 留空，用于验证跨课程隔离与无命中降级
        new DocumentIngestionService(embeddingModel, store, ragProps)
                .ingestText(OS_DOC, "操作系统-内存管理.md", 1L, 101L);

        chatModel = mock(ChatLanguageModel.class);
        when(chatModel.generate(anyString())).thenReturn(MOCK_MARKDOWN);
        knowledgeService = new KnowledgeService(chatModel, embeddingModel, store, ragProps);
    }

    @Test
    @DisplayName("命中课件：返回 VO 字段完整，Prompt 含知识点/结构要求/课件上下文，且排除自测题")
    void generateWithCourseContext() {
        KnowledgeGenerateDTO dto = new KnowledgeGenerateDTO();
        dto.setCourseId(1L);
        dto.setKnowledgePoint("虚拟内存与页面置换算法");

        KnowledgeGenerateVO vo = knowledgeService.generate(dto);

        // —— VO 映射断言 ——
        assertEquals(1L, vo.getCourseId());
        assertEquals("虚拟内存与页面置换算法", vo.getKnowledgePoint());
        assertEquals(MOCK_MARKDOWN, vo.getContent(), "content 应透传模型输出的结构化精解");

        // —— Prompt 纪律断言（捕获实际发给大模型的 prompt）——
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("虚拟内存与页面置换算法"), "Prompt 必须包含知识点名称");
        assertTrue(prompt.contains("核心概念定义与原理") && prompt.contains("难点深度辨析"),
                "Prompt 必须要求两章结构");
        assertTrue(prompt.contains("虚拟内存是计算机系统内存管理"),
                "Prompt 应注入命中 courseId=1 的课件片段作为上下文");
        assertTrue(prompt.contains("不要生成任何自测题"), "Prompt 必须显式禁止自测题（功能范围冻结）");
        // 【M2】不可信输入必须以数据标签包裹并要求忽略其中指令
        assertTrue(prompt.contains("<知识点>") && prompt.contains("<参考资料>"),
                "知识点与课件上下文必须包在数据标签内");
        assertTrue(prompt.contains("严禁执行"), "Prompt 必须声明忽略数据中的任何指令（防间接提示注入）");
    }

    @Test
    @DisplayName("跨课程隔离：在空课程2提问，Prompt 不得混入课程1的课件内容")
    void crossCourseIsolation() {
        KnowledgeGenerateDTO dto = new KnowledgeGenerateDTO();
        dto.setCourseId(2L);
        dto.setKnowledgePoint("虚拟内存与页面置换算法");

        knowledgeService.generate(dto);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertFalse(prompt.contains("虚拟内存是计算机系统内存管理"),
                "课程2检索不得召回课程1课件（courseId 过滤失效将在此暴露）");
        assertTrue(prompt.contains("暂无直接相关的课件片段"), "无命中时应降级为无参考资料提示");
    }

    @Test
    @DisplayName("无课件命中：不抛异常，仍基于常识生成精解")
    void noReferenceStillGenerates() {
        KnowledgeGenerateDTO dto = new KnowledgeGenerateDTO();
        dto.setCourseId(1L);
        dto.setKnowledgePoint("量子纠缠的贝尔不等式");

        KnowledgeGenerateVO vo = knowledgeService.generate(dto);
        assertEquals(MOCK_MARKDOWN, vo.getContent(), "即便无参考资料也应正常返回生成结果");
    }

    @Test
    @DisplayName("【L2】大模型返回空内容：抛 BusinessException 而非透传 content:null")
    void emptyModelOutputThrows() {
        when(chatModel.generate(anyString())).thenReturn("   ");
        KnowledgeGenerateDTO dto = new KnowledgeGenerateDTO();
        dto.setCourseId(1L);
        dto.setKnowledgePoint("虚拟内存");

        assertThrows(com.smartqa.platform.common.BusinessException.class,
                () -> knowledgeService.generate(dto));
    }
}

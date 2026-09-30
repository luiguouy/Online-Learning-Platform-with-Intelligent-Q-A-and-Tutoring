package com.smartqa.platform.service.rag;

import com.smartqa.platform.config.RagConfigProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * 课件解析三层安全上限（M2 加固）回归测试（A3.x 补测试盲区）。
 *
 * <p>此前 {@link DocumentIngestionService} 的三层上限——输入字节 / 文本字符 / 解析超时——
 * 无任何自动化测试保护（{@code RagIsolationTest} 只覆盖检索侧隔离），一旦有人「好心」调整
 * {@code rag.ingest.*} 或改动 {@code parseWithLimits} 逻辑，超限链路会静默退化。本类锁定三层上限的
 * <b>可验证行为与面向用户的错误文案</b>。</p>
 *
 * <p>纯本地、零外部依赖：三层上限的触发点都在向量化 {@code embedAll} 之前，故用 mock 的
 * {@link EmbeddingModel} 即可（永不被真正调用），不加载 45MB BGE 模型、不碰 MySQL / Chroma，
 * CI 裸环境可直接跑。</p>
 */
class DocumentIngestionGuardTest {

    private DocumentIngestionService newService(RagConfigProperties props) {
        return new DocumentIngestionService(
                mock(EmbeddingModel.class), new InMemoryEmbeddingStore<TextSegment>(), props);
    }

    // ── 第 1 层：输入字节上限（SizeLimitInputStream） ───────────────────────────
    @Test
    @DisplayName("第1层 输入字节上限：累计读取超 maxFileBytes 立即抛 FileSizeLimitExceededException")
    void inputByteLimitEnforced() throws Exception {
        byte[] data = new byte[1024]; // 提供 1KB，但上限设为 100 字节
        DocumentIngestionService.SizeLimitInputStream limited =
                new DocumentIngestionService.SizeLimitInputStream(
                        new ByteArrayInputStream(data), 100L);
        byte[] buf = new byte[256];

        assertThrows(DocumentIngestionService.FileSizeLimitExceededException.class,
                () -> limited.read(buf, 0, buf.length),
                "读取超过 100 字节上限应抛 FileSizeLimitExceededException");
    }

    // ── 第 2 层：提取文本字符上限（ingestText 快速路径） ─────────────────────────
    @Test
    @DisplayName("第2层 文本字符上限：ingestText 超 maxTextChars 抛可读异常且不进入向量化")
    void textCharLimitEnforced() {
        RagConfigProperties props = new RagConfigProperties();
        props.getIngest().setMaxTextChars(50);
        DocumentIngestionService svc = newService(props);

        String oversize = "页面置换算法".repeat(30); // 6 字 × 30 = 180 字 > 50
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> svc.ingestText(oversize, "超大课件.md", 1L, 101L));

        assertTrue(ex.getMessage().contains("解析上限"),
                "应命中『超过解析上限』文案，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("50"),
                "文案应回显配置的上限字符数，实际：" + ex.getMessage());
    }

    // ── 第 3 层：解析超时（parseWithLimits 限时等待） ────────────────────────────
    @Test
    @DisplayName("第3层 解析超时：解析阻塞超 parse-timeout 抛可读超时异常并取消守护任务")
    void parseTimeoutEnforced() {
        RagConfigProperties props = new RagConfigProperties();
        props.getIngest().setParseTimeoutSeconds(1);
        props.getIngest().setMaxFileBytes(10_000_000L); // 抬高输入上限，确保命中的是超时层而非字节层
        props.getIngest().setMaxTextChars(500_000);
        DocumentIngestionService svc = newService(props);

        // 一个「读第一个字节就阻塞 3 秒」的流：远超 1 秒解析超时预算，必然触发 future.get 超时
        InputStream slowStream = new InputStream() {
            @Override
            public int available() {
                return 1;
            }

            @Override
            public int read() throws IOException {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("parse interrupted by timeout cancel", e);
                }
                return ' ';
            }
        };

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> svc.ingest(slowStream, "卡死文档.pdf", 1L, 101L, 1L));
        assertTrue(ex.getMessage().contains("超时"),
                "应命中『解析超时』文案，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("1"),
                "文案应回显超时秒数，实际：" + ex.getMessage());
    }

    // ── Issue #55：失败文案必须区分「无文本层」与「文件损坏/无法识别」 ────────────────
    @Test
    @DisplayName("#55 无文本层：文案应指向「上传含文字层的文件」，不得再提「检查文件格式」")
    void blankDocumentMessageIsSpecific() {
        DocumentIngestionService svc = newService(new RagConfigProperties());
        // 提取到的文本为空白 —— 即扫描件 / 纯图片 PDF 的等价情形。
        // langchain4j 的 ApacheTikaDocumentParser 会抛 BlankDocumentException（实测复现）。
        InputStream blank = new ByteArrayInputStream("   \n\t  \n   ".getBytes(StandardCharsets.UTF_8));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> svc.ingest(blank, "扫描件_空白3页.pdf", 1L, 101L, 1L));

        assertTrue(ex.getMessage().contains("文本内容"),
                "应命中『未提取到文本内容』文案，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("文字层"),
                "文案应给出可执行下一步（上传含文字层的文件），实际：" + ex.getMessage());
        assertFalse(ex.getMessage().contains("格式"),
                "Issue #55：不得再出现『格式』字样把用户引向换格式（格式本身没问题），实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("#55 解析失败兜底文案：不得再把原因归到「格式」上（格式在上传入口已校验）")
    void fallbackMessageDoesNotBlameFormat() {
        DocumentIngestionService svc = newService(new RagConfigProperties());
        // 提醒：Tika 相当宽容 —— 内容乱码的 .docx/.zip 实测**不抛异常、只是提取到空文本**，
        // 会落到上面的"无文本"分支（已用 10 字节 ZIP 魔数垃圾验证过）。
        // 兜底分支要的是"读取/解析过程本身抛错"，故下面给出候选输入，取真正命中兜底的那个。
        List<InputStream> candidates = List.of(
                // 候选 1：读取即失败（与具体格式无关，Tika 无法凭空产出文本）
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("simulated read failure");
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        throw new IOException("simulated read failure");
                    }
                },
                // 候选 2：PDF 魔数 + 乱码正文（PDFBox 无法解析）
                new ByteArrayInputStream("%PDF-1.4\n!!!! this is not a valid pdf body !!!!"
                        .getBytes(StandardCharsets.UTF_8)));

        for (InputStream candidate : candidates) {
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> svc.ingest(candidate, "损坏的课件.pdf", 1L, 101L, 1L));
            assertFalse(ex.getMessage().contains("请检查文件格式"),
                    "Issue #55：兜底文案不得再引导用户去检查/更换格式，实际：" + ex.getMessage());
            if (ex.getMessage().contains("无法识别")) {
                return; // 确认命中兜底分支，且文案已改
            }
        }
        fail("未能构造出命中兜底分支的输入 —— 说明兜底文案没有被任何用例覆盖");
    }
}

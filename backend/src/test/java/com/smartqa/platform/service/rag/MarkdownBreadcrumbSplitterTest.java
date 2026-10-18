package com.smartqa.platform.service.rag;

import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markdown 结构感知与层级面包屑切块器单元测试
 */
class MarkdownBreadcrumbSplitterTest {

    private MarkdownBreadcrumbSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new MarkdownBreadcrumbSplitter();
    }

    @Test
    @DisplayName("空文本或无标题文档安全返回空列表（供调用层回退默认切块）")
    void blankOrNoHeadingMarkdownReturnsEmptyList() {
        assertTrue(splitter.split(null, 400, 50).isEmpty());
        assertTrue(splitter.split("", 400, 50).isEmpty());
        assertTrue(splitter.split("   ", 400, 50).isEmpty());

        String plainText = "这是一篇没有Markdown标题的文档。\n包含几段普通的描述文字。";
        List<TextSegment> segments = splitter.split(plainText, 400, 50);
        assertTrue(segments.isEmpty(), "无有效Markdown标题时应返回空列表，触发通用分块回退");
    }

    @Test
    @DisplayName("多层级标题层层递进并正确拼接面包屑")
    void nestedHeadingsBuildBreadcrumbCorrectly() {
        String md = """
                # 第一章 计算机网络体系结构
                本章介绍网络核心概念。
                ## 1.1 协议与体系
                协议是通信双方遵循的规则。
                ### 1.1.1 物理层
                物理层传输比特流。
                """;

        List<TextSegment> segments = splitter.split(md, 400, 50);
        assertEquals(3, segments.size(), "应切出3个结构化片段");

        // 片段1：第一章 正文
        TextSegment s1 = segments.get(0);
        assertEquals("第一章 计算机网络体系结构", s1.metadata().getString("breadcrumb"));
        assertTrue(s1.text().startsWith("[章节导航: 第一章 计算机网络体系结构]"));
        assertTrue(s1.text().contains("本章介绍网络核心概念。"));

        // 片段2：1.1 协议与体系
        TextSegment s2 = segments.get(1);
        assertEquals("第一章 计算机网络体系结构 > 1.1 协议与体系", s2.metadata().getString("breadcrumb"));
        assertTrue(s2.text().startsWith("[章节导航: 第一章 计算机网络体系结构 > 1.1 协议与体系]"));
        assertTrue(s2.text().contains("协议是通信双方遵循的规则。"));

        // 片段3：1.1.1 物理层
        TextSegment s3 = segments.get(2);
        assertEquals("第一章 计算机网络体系结构 > 1.1 协议与体系 > 1.1.1 物理层", s3.metadata().getString("breadcrumb"));
        assertTrue(s3.text().startsWith("[章节导航: 第一章 计算机网络体系结构 > 1.1 协议与体系 > 1.1.1 物理层]"));
        assertTrue(s3.text().contains("物理层传输比特流。"));
    }

    @Test
    @DisplayName("遇到同级或更高级标题时层级栈正确出栈")
    void headingStackPopsCorrectlyOnLevelChange() {
        String md = """
                # 操作系统原理
                ## 进程管理
                ### 进程调度算法
                包括先来先服务与短作业优先。
                ## 内存管理
                虚拟内存分页机制。
                """;

        List<TextSegment> segments = splitter.split(md, 400, 50);
        assertEquals(2, segments.size(), "应切出2个有效正文小节");

        // 第一个有效小节属于进程调度算法
        TextSegment s1 = segments.get(0);
        assertEquals("操作系统原理 > 进程管理 > 进程调度算法", s1.metadata().getString("breadcrumb"));
        assertTrue(s1.text().contains("先来先服务"));

        // 第二个小节进入内存管理，上一级的“进程调度算法”和“进程管理”应已出栈
        TextSegment s2 = segments.get(1);
        assertEquals("操作系统原理 > 内存管理", s2.metadata().getString("breadcrumb"));
        assertTrue(s2.text().contains("虚拟内存分页机制。"));
    }

    @Test
    @DisplayName("单小节正文超过最大字符数时平滑二次切块并共享面包屑")
    void oversizedSectionSplitsGracefullySharingBreadcrumb() {
        // 构造超长正文（500字），配置 maxChunkSize=150
        String longText = "死锁是指两个或两个以上的进程在执行过程中，因争夺资源而造成的一种互相等待的现象。".repeat(15);
        String md = "# 死锁管理\n## 银行家算法\n" + longText;

        List<TextSegment> segments = splitter.split(md, 150, 20);
        assertTrue(segments.size() > 1, "超长正文应切分成多个片段");

        for (TextSegment seg : segments) {
            assertEquals("死锁管理 > 银行家算法", seg.metadata().getString("breadcrumb"),
                    "每个子片段必须保留所属的面包屑");
            assertTrue(seg.text().startsWith("[章节导航: 死锁管理 > 银行家算法]"));
            assertNotNull(seg.text());
        }
    }
}

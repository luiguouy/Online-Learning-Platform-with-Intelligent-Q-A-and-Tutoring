package com.smartqa.platform.service.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文动态压缩与多余噪音消除单元测试 (Context Compressor & Noise Reducer)
 */
class ContextCompressorServiceTest {

    private ContextCompressorService compressorService;

    @BeforeEach
    void setUp() {
        compressorService = new ContextCompressorService();
    }

    @Test
    @DisplayName("课堂口头废话与翻页作业指示过滤")
    void testRemoveClassroomNoise() {
        String noisyText = """
                好，下面我们来看：
                请同学们翻到第25页。
                上节课我们已经讲过了进程的状态转换。
                临界区是指进程中访问临界资源的那段代码。为了实现互斥，进程在进入临界区前必须请求许可。
                课后作业：请思考读者-写者问题如何解决。
                """;

        String cleaned = compressorService.removeClassroomNoise(noisyText);

        assertFalse(cleaned.contains("翻到第25页"), "应消除翻页指令");
        assertFalse(cleaned.contains("上节课我们已经讲过了"), "应消除课堂回顾过渡句");
        assertFalse(cleaned.contains("课后作业："), "应消除课后作业说明");
        assertTrue(cleaned.contains("临界区是指进程中访问临界资源的那段代码"), "核心知识定义必须保留");
        assertTrue(cleaned.contains("为了实现互斥"), "关键机制说明必须保留");
    }

    @Test
    @DisplayName("保护章节导航面包屑前缀不被移除")
    void testBreadcrumbPrefixProtection() {
        String input = """
                [章节导航: 第二章 进程管理 > 2.3 进程同步]
                请大家参考教材第120页。
                信号量机制是一种功能较强的互斥与同步工具。P操作执行减1，V操作执行加1。
                """;

        String compressed = compressorService.compress(input, "信号量P操作");

        assertTrue(compressed.startsWith("[章节导航: 第二章 进程管理 > 2.3 进程同步]"),
                "章节导航面包屑必须位于首行得到完整保护");
        assertFalse(compressed.contains("参考教材第120页"), "噪音仍需被过滤");
        assertTrue(compressed.contains("信号量机制是一种功能较强的互斥与同步工具"), "核心内容保持");
    }

    @Test
    @DisplayName("短文本（<= 250字符）保底透传，避免过度裁剪")
    void testShortTextProtection() {
        String shortText = "死锁是指两个或两个以上的进程在执行过程中，因争夺资源而造成的一种互相等待的现象。";
        String compressed = compressorService.compress(shortText, "死锁的定义");

        assertEquals(shortText, compressed, "短文本应完整保留，不做激进句子裁剪");
    }

    @Test
    @DisplayName("长文本按Query关键词及定义句做上下文精炼")
    void testLongTextSentenceFiltering() {
        // 构建一段充分超过 250 字的长文本，前段讲概念，中段讲较长的无关硬件历史，后段讲管道机制
        String longText = """
                [章节导航: 第二章 进程管理 > 2.5 进程通信]
                现代操作系统的通信架构十分丰富，涵盖低级与高级多种机制。
                在非常早期的单任务系统时代，计算机完全没有并发多进程的概念。
                当时的计算机硬件极其原始，体积庞大且造价高昂。
                早期计算机经历了电子管和晶体管时代，主要用于国防与大规模数值计算。
                程序员必须使用打孔纸带进行繁琐的批处理作业输入与调试。
                这种运行环境下完全不存在多个应用程序协同合作的需求。
                后来分时操作系统的诞生才推动了现代并发调度机制的发展。
                管道通信是UNIX系统中最早引入的IPC机制之一。
                管道是半双工的，数据只能单向流动。
                若需要双向全双工通信，通常需要建立两个独立的管道。
                """;

        String compressed = compressorService.compress(longText, "管道通信的特点");

        // 面包屑和管道相关句子必须被保留
        assertTrue(compressed.contains("[章节导航: 第二章 进程管理 > 2.5 进程通信]"));
        assertTrue(compressed.contains("管道通信"));
        assertTrue(compressed.contains("半双工"));
        // 中间早期历史与电子管硬件背景属于远端无关段落，应被有效剔除
        assertFalse(compressed.contains("电子管和晶体管时代"), "无关背景句群应被有效裁剪");
    }

    @Test
    @DisplayName("空值与空白字符边界安全处理")
    void testEmptyAndBlankHandling() {
        assertNull(compressorService.compress(null, "任意query"));
        assertEquals("", compressorService.compress("", "任意query"));
        assertEquals("   ", compressorService.compress("   ", "任意query"));
    }
}

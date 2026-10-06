package com.smartqa.platform.service.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Issue #82：引用元数据数值解析回归测试（纯单元，**不依赖 Chroma / Spring / MySQL，CI 可直接跑**）。
 *
 * <p><b>被钉住的缺陷</b>：写入侧 {@code metadata.put("chunkIndex", i)} 存 int，经 Chroma 的
 * JSON 序列化 + Gson 反序列化往返后，取回来的是 {@code java.lang.String}、内容为 {@code "3.0"}
 * （运行时实测，非推断）。旧实现 {@code Integer.parseInt(String.valueOf(v))} 对此必抛
 * NumberFormatException 并<b>静默返回 null</b> → SSE references 的 chunkIndex 为 null →
 * 教师端引用详情显示「第 段」。</p>
 *
 * <p><b>为什么必须有这一层单元测试</b>：该缺陷在 {@code InMemoryEmbeddingStore} 下不复现
 * （进程内不做 JSON 往返，metadata 原样保留），所以既有测试全绿也照不出来——它与 #58 那条
 * 「InMemory 白送语义」是同一族问题。真实 Chroma 下的端到端表现由 #82 发现者复测承担，
 * <b>本类负责把解析规则本身钉死，使其在 CI 里长期受保护</b>。</p>
 *
 * <p>被测方法是包级可见（非 public），本类与其同包，故可直接调用；这是刻意为之——
 * 为了可测而放宽到包级，不对外暴露。</p>
 */
class ChunkIndexMetadataParsingTest {

    // ── #82 核心：Chroma 往返后的「浮点形式字符串」必须解析成功 ──────────────────

    @Test
    @DisplayName("#82 核心：字符串 \"3.0\"（Chroma 往返产物）必须解析为 3，不得返回 null")
    void decimalStringFromChromaRoundTripParses() {
        assertEquals(3, SseStreamService.parseIntOrNull("3.0"),
                "Issue #82 复现点：Chroma 回读的 chunkIndex 是字符串 \"3.0\"，旧实现在此返回 null");
        assertEquals(0, SseStreamService.parseIntOrNull("0.0"), "第 0 段同样要能解析（否则显示「第 段」）");
        assertEquals(17, SseStreamService.parseIntOrNull("17.0"));
    }

    @Test
    @DisplayName("#82 核心：Long 型字段（docId / courseId）同样要能吃浮点形式字符串")
    void decimalStringParsesForLong() {
        assertEquals(999001L, SseStreamService.parseLongOrNull("999001.0"));
        assertEquals(18L, SseStreamService.parseLongOrNull("18.0"),
                "docId 若以数值写入也会同样劣化，读取侧必须兼容");
    }

    // ── 正常路径不回归 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("正常整数字面量与 Number 实例照常解析（不回归）")
    void plainIntegerFormsStillWork() {
        assertEquals(3, SseStreamService.parseIntOrNull("3"));
        assertEquals(3, SseStreamService.parseIntOrNull(3));
        assertEquals(3, SseStreamService.parseIntOrNull(3L));
        assertEquals(-1, SseStreamService.parseIntOrNull("-1"), "负号字面量应保留");
        assertEquals(999001L, SseStreamService.parseLongOrNull("999001"));
        assertEquals(999001L, SseStreamService.parseLongOrNull(999001L));
    }

    // ── #82 验收要求：只接受合法整数，小数 / 非法 / 越界一律 null，绝不静默截断 ──────

    @Test
    @DisplayName("#82 验收：小数值必须返回 null（不得截断成 3 而把「第 3 段」显示成「第 0/3 段」）")
    void fractionalValuesAreRejectedNotTruncated() {
        assertNull(SseStreamService.parseIntOrNull("3.5"), "小数不得被截断为 3");
        assertNull(SseStreamService.parseIntOrNull(3.5), "Double 小数同样不得截断");
        assertNull(SseStreamService.parseIntOrNull("-0.5"));
        assertNull(SseStreamService.parseLongOrNull("18.7"));
    }

    @Test
    @DisplayName("#82 验收：非数值 / 空值 / NaN / 越界一律 null")
    void nonNumericAndOutOfRangeValuesYieldNull() {
        assertNull(SseStreamService.parseIntOrNull(null));
        assertNull(SseStreamService.parseIntOrNull(""));
        assertNull(SseStreamService.parseIntOrNull("   "));
        assertNull(SseStreamService.parseIntOrNull("abc"));
        assertNull(SseStreamService.parseIntOrNull("NaN"));
        assertNull(SseStreamService.parseIntOrNull("Infinity"));
        assertNull(SseStreamService.parseIntOrNull("3.0.0"), "多小数点不是合法数值");
        // 超出 Integer 范围：不得溢出成别的数，也不得截断
        assertNull(SseStreamService.parseIntOrNull("99999999999999999999.0"));
        assertNull(SseStreamService.parseIntOrNull("2147483648"), "Integer.MAX_VALUE + 1 应被拒");
        assertEquals(2147483647, SseStreamService.parseIntOrNull("2147483647"), "边界值本身应通过");
    }
}

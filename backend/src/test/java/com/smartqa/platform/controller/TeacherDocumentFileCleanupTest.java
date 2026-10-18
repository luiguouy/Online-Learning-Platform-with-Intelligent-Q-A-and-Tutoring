package com.smartqa.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 课件删除的<strong>磁盘原件清理</strong>验收（端到端人工验收时发现的问题回归）。
 *
 * <p>背景：{@code DELETE /api/teacher/docs/{id}} 原先只级联清理了向量库与语义缓存，
 * <b>从不删除磁盘上的课件原件</b>（只把记录置 {@code is_deleted=1}）。教师每删一次课件，
 * 服务器磁盘就永久留一份原件，属于确定性资源泄漏。之所以一直没被发现：
 * 既有的删除类测试在 {@code @AfterEach} 里<strong>自己</strong>删掉了上传文件，
 * 恰好替生产代码补了这一步。</p>
 *
 * <p>两条断言分别钉住两个方向，缺一不可：</p>
 * <ol>
 *   <li><b>必须删掉</b>：上传目录子树内的原件，删除课件后文件应消失；</li>
 *   <li><b>绝不能删</b>：{@code file_path} 来自数据库，若不校验边界，这个接口就是
 *       一个<strong>任意文件删除原语</strong>（一行被污染的数据即可指向 jar、配置或系统文件）。
 *       因此目录外的文件必须原样保留。</li>
 * </ol>
 *
 * <p>刻意不经"上传+解析"链路构造数据：直接写文件 + 插 {@code course_document} 行，
 * 使断言只依赖删除逻辑本身，不受向量链路与模型可用性影响。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeacherDocumentFileCleanupTest {

    /** 与 seed 的 teacher01(id=1) 所拥有的课程一致，保证通过 assertTeacherOwnsCourse */
    private static final long COURSE_ID = 1L;
    private static final long DOC_IN_TREE = 999001L;
    private static final long DOC_OUTSIDE = 999002L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${file.upload-dir}")
    private String uploadDir;

    private String teacherToken;
    private File insideFile;
    private File outsideFile;

    private static final String[] SEED_IDS = {String.valueOf(DOC_IN_TREE), String.valueOf(DOC_OUTSIDE)};

    @BeforeEach
    void setUp() throws Exception {
        JsonNode login = bodyOf(mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json;charset=UTF-8")
                        .content("{\"username\":\"teacher01\",\"password\":\"123456\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(200, login.path("code").asInt(), "测试前提：teacher01 登录成功");
        teacherToken = login.path("data").path("token").asText();

        // ① 上传目录子树内的"原件"
        File courseDir = new File(uploadDir, String.valueOf(COURSE_ID));
        courseDir.mkdirs();
        insideFile = new File(courseDir, "1799000000000_filecleanup-sample.md");
        Files.writeString(insideFile.toPath(), "# 磁盘清理测试片段\n", StandardCharsets.UTF_8);

        // ② 上传目录<strong>之外</strong>的文件：用来证明护栏不会把删除接口变成任意文件删除
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir"));
        outsideFile = Files.createTempFile(tmpRoot, "smartqa-must-survive-", ".md").toFile();
        Files.writeString(outsideFile.toPath(), "这个文件位于上传目录之外，删除接口不得碰它\n", StandardCharsets.UTF_8);

        insertDoc(DOC_IN_TREE, insideFile.getAbsolutePath());
        insertDoc(DOC_OUTSIDE, outsideFile.getAbsolutePath());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM course_document WHERE id IN (?, ?)", SEED_IDS);
        if (insideFile != null) {
            //noinspection ResultOfMethodCallIgnored
            insideFile.delete();   // 用例 2 之外的残留由这里收口
        }
        if (outsideFile != null) {
            //noinspection ResultOfMethodCallIgnored
            outsideFile.delete();
        }
    }

    private void insertDoc(long id, String filePath) {
        jdbcTemplate.update("""
                        INSERT INTO course_document (id, course_id, file_name, file_path, file_size,
                            file_type, chunk_count, parse_status, is_deleted)
                        VALUES (?, ?, ?, ?, 40, 'md', 0, 'CHUNKED', 0)
                        """,
                id, COURSE_ID, new File(filePath).getName(), filePath);
    }

    private JsonNode bodyOf(String json) throws IOException {
        return objectMapper.readTree(json);
    }

    @Test
    @DisplayName("删除课件必须同时清掉上传目录内的磁盘原件（此前只删库与向量，磁盘永久泄漏）")
    void deleteRemovesStoredFileInsideUploadDir() throws Exception {
        assertTrue(insideFile.isFile(), "测试前提：目录内原件已落盘");

        var result = mockMvc.perform(delete("/api/teacher/docs/" + DOC_IN_TREE)
                        .header("Authorization", "Bearer " + teacherToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(200, bodyOf(result).path("code").asInt(), "删除接口应成功：" + result);

        assertFalse(insideFile.exists(),
                "课件记录删除后磁盘原件必须一并清掉，否则每删一次就永久泄漏一份文件：" + insideFile.getAbsolutePath());
    }

    @Test
    @DisplayName("护栏：file_path 指向上传目录之外时拒绝删除文件（该接口不得成为任意文件删除原语）")
    void deleteRefusesFileOutsideUploadDir() throws Exception {
        assertTrue(outsideFile.isFile(), "测试前提：目录外文件存在");

        var result = mockMvc.perform(delete("/api/teacher/docs/" + DOC_OUTSIDE)
                        .header("Authorization", "Bearer " + teacherToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 业务事实（记录删除）仍成立：接口成功返回，但不越界删文件
        assertEquals(200, bodyOf(result).path("code").asInt(), "删除接口应成功：" + result);
        assertTrue(outsideFile.isFile(),
                "上传目录之外的文件必须原样保留，否则数据库里一行被污染的 file_path 就能删任意文件："
                        + outsideFile.getAbsolutePath());
    }
}

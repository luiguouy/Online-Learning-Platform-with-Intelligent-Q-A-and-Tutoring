package com.smartqa.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 注册 / 登出端点的服务端验收测试（Issue #91）。
 *
 * <p><b>职责边界</b>（与现有测试类不重叠）：本类只管 {@code /api/auth} 下的
 * <b>写路径与令牌生命周期</b>——注册落库、角色不可提权、重复名与参数校验、
 * 注册即登录所返回 token 的可用性与注销。问答链路契约归
 * {@code SseContractGuardTest}，协议栈边界归 {@code SseProtocolBoundaryTest}。</p>
 *
 * <p>需要真实 MySQL（test profile 关 Chroma、假模型不涉及，注册不涉及 LLM，
 * 因此不需要 {@code @MockBean} 模型替身）。用户名前缀 {@code t91auth}，
 * {@code @BeforeEach}/{@code @AfterEach} 物理删除（MyBatis-Plus 的逻辑删除只置
 * {@code is_deleted}，而 {@code sys_user.username} 上有 UNIQUE 约束，
 * 软删的行仍会挡住同名插入，测试必须硬删才能可重复运行）。</p>
 *
 * <p>断言口径遵循全项目契约：<b>HTTP 状态码恒 200</b>，业务码在 body 的 {@code code} 字段。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    /** 本类专属用户名前缀；长度受 @Pattern 约束（4-20 位字母数字下划线） */
    private static final String U1 = "t91auth01";
    private static final String U2 = "t91auth02";

    /** 满足"字母+数字"组合的口令：即便后续加入口令强度策略，本用例也不用改 */
    private static final String GOOD_PASSWORD = "Passw0rd91";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanUpTestUsers() {
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 't91auth%'");
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult register(String json) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    /** 传输层恒 200 是全项目契约；任何用例先看 body 的 code，不看状态码 */
    private void assertHttp200(MvcResult result) {
        assertEquals(200, result.getResponse().getStatus(),
                "传输层状态码必须恒 200（业务码只放 body），前端按 body 解包");
    }

    @Test
    @DisplayName("注册成功：返回体字段与登录一致，且密码以 BCrypt 落库、响应不回显口令")
    void registerReturnsLoginVoAndStoresBcryptHash() throws Exception {
        MvcResult result = register("""
                {"username":"t91auth01","password":"Passw0rd91","nickname":"测试学生一"}""");
        assertHttp200(result);

        JsonNode body = bodyOf(result);
        assertEquals(200, body.path("code").asInt(), "注册应成功：" + body);

        JsonNode data = body.path("data");
        assertNotNull(data.path("token").asText(null), "返回体应含 token（注册即登录）");
        assertFalse(data.path("token").asText().isBlank(), "token 不应为空串");
        assertEquals(U1, data.path("username").asText());
        assertEquals("测试学生一", data.path("nickname").asText());
        assertEquals("STUDENT", data.path("role").asText());
        assertTrue(data.has("userId"), "应含 userId");
        assertTrue(data.has("avatarUrl"), "应与 LoginVO 字段集一致，含 avatarUrl");
        assertFalse(body.toString().contains(GOOD_PASSWORD), "响应体严禁回显明文口令");

        String stored = jdbcTemplate.queryForObject(
                "SELECT password FROM sys_user WHERE username = ?", String.class, U1);
        assertTrue(stored.startsWith("$2"), "落库密码必须是 BCrypt 哈希，实际为：" + stored);
        assertFalse(stored.contains(GOOD_PASSWORD), "哈希里不应含明文口令");
    }

    @Test
    @DisplayName("防越权：客户端塞 role=TEACHER 也无效，服务端一律落 STUDENT")
    void roleCannotBeElevatedByClient() throws Exception {
        // RegisterDTO 没有 role 字段，Spring Boot 默认忽略未知属性；
        // 这条用例钉的是"以后有人给 DTO 加 role 入参"时立刻失败，而不是静默开放提权。
        MvcResult result = register("""
                {"username":"t91auth01","password":"Passw0rd91","nickname":"想当老师的人","role":"TEACHER"}""");
        assertHttp200(result);

        JsonNode body = bodyOf(result);
        assertEquals(200, body.path("code").asInt(), "带多余字段的注册仍应成功：" + body);
        assertEquals("STUDENT", body.path("data").path("role").asText(), "响应 role 必须仍是 STUDENT");

        String dbRole = jdbcTemplate.queryForObject(
                "SELECT role FROM sys_user WHERE username = ?", String.class, U1);
        assertEquals("STUDENT", dbRole, "库内 role 必须仍是 STUDENT，否则注册即为提权入口");

        // 提权失败的第二重证据：该账号访问教师后台应被角色校验拒绝（403 业务码）
        String token = body.path("data").path("token").asText();
        MvcResult teacherCall = mockMvc.perform(get("/api/teacher/docs/list")
                .param("courseId", "1")
                .header("Authorization", "Bearer " + token)).andReturn();
        assertEquals(403, bodyOf(teacherCall).path("code").asInt(),
                "注册产生的账号不得进入 /api/teacher/**");
    }

    @Test
    @DisplayName("重复用户名：第二次注册回 400 用户名已存在，且不产生第二条记录")
    void duplicateUsernameRejected() throws Exception {
        JsonNode first = bodyOf(register("""
                {"username":"t91auth02","password":"Passw0rd91","nickname":"首个占用者"}"""));
        assertEquals(200, first.path("code").asInt(), "测试前提：首次注册成功");

        MvcResult second = register("""
                {"username":"t91auth02","password":"Passw0rd91","nickname":"同名后来者"}""");
        assertHttp200(second);

        JsonNode dup = bodyOf(second);
        assertEquals(400, dup.path("code").asInt(), "重名注册应回业务码 400");
        assertEquals("用户名已存在", dup.path("message").asText());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = ?", Integer.class, U2);
        assertEquals(1, count, "重名请求不得插入第二条记录");
    }

    @Test
    @DisplayName("参数校验：口令过短、账号字符非法、姓名为空均回 400 且不落库")
    void invalidPayloadsRejected() throws Exception {
        String[] cases = {
                // 口令不足 8 位
                "{\"username\":\"t91auth01\",\"password\":\"12345\",\"nickname\":\"甲\"}",
                // 账号含非法字符（@）
                "{\"username\":\"t91auth01@mail\",\"password\":\"Passw0rd91\",\"nickname\":\"乙\"}",
                // 姓名空白
                "{\"username\":\"t91auth01\",\"password\":\"Passw0rd91\",\"nickname\":\"   \"}",
        };
        // 第三条只断言“名”字：@NotBlank 的「姓名不能为空」与 ValidNickname 的「名称须为…」
        // 都会命中，取第一条的顺序不应该被当成契约依赖
        String[] expectedHints = {"密码", "账号", "名"};

        for (int i = 0; i < cases.length; i++) {
            MvcResult result = register(cases[i]);
            assertHttp200(result);
            JsonNode body = bodyOf(result);
            assertEquals(400, body.path("code").asInt(),
                    "第 " + (i + 1) + " 条非法入参应回 400，实际：" + body);
            assertTrue(body.path("message").asText().contains(expectedHints[i]),
                    "第 " + (i + 1) + " 条提示应指向对应字段，实际：" + body.path("message").asText());
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username LIKE 't91auth%'", Integer.class);
        assertEquals(0, count, "参数校验失败的请求不应写入任何用户");
    }

    @Test
    @DisplayName("注册即登录：返回的 token 可直接访问需鉴权接口")
    void registeredTokenIsImmediatelyUsable() throws Exception {
        JsonNode body = bodyOf(register("""
                {"username":"t91auth01","password":"Passw0rd91","nickname":"免二次登录"}"""));
        assertEquals(200, body.path("code").asInt());
        String token = body.path("data").path("token").asText();

        MvcResult courses = mockMvc.perform(get("/api/course/list")
                .header("Authorization", "Bearer " + token)).andReturn();

        assertEquals(200, bodyOf(courses).path("code").asInt(),
                "注册返回的 token 应立即可用，学生端据此免二次登录");
    }

    @Test
    @DisplayName("登出吊销：注销后同一 token 再访问受保护接口回 401（不是只清本地）")
    void logoutRevokesTokenOnServer() throws Exception {
        JsonNode body = bodyOf(register("""
                {"username":"t91auth01","password":"Passw0rd91","nickname":"会被登出的人"}"""));
        String token = body.path("data").path("token").asText();

        // 登出前应当可用
        assertEquals(200, bodyOf(mockMvc.perform(get("/api/course/list")
                .header("Authorization", "Bearer " + token)).andReturn()).path("code").asInt(),
                "测试前提：登出前 token 可用");

        MvcResult logout = mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + token)).andReturn();
        assertHttp200(logout);
        assertEquals(200, bodyOf(logout).path("code").asInt(), "登出应成功：" + bodyOf(logout));

        // 关键断言：sa-token timeout 是 7 天，若登出只在前端清 localStorage，这里仍会返回 200
        MvcResult afterLogout = mockMvc.perform(get("/api/course/list")
                .header("Authorization", "Bearer " + token)).andReturn();
        assertEquals(401, bodyOf(afterLogout).path("code").asInt(),
                "登出后服务端会话必须立即失效，否则令牌泄露无法吊销");
    }

    @Test
    @DisplayName("口令强度：短、纯字母、等步长序列、黑名单弱口令均回 400 且不落库（Issue #94）")
    void passwordPolicyRejectsWeakForms() throws Exception {
        // 每条只违反一个维度，这样失败时能定位到具体规则而不是“反正都被拒”
        String[] weakPasswords = {
                "Ab1!xyz",        // 7 位，不足 8
                "abcdefghij",     // 纯字母，无数字
                "1234567890",     // 纯数字 + 等步长序列 + 黑名单
                "passw0rd",       // 结构合法（字母+数字、非序列）但命中黑名单：专测黑名单这一支
                "aaaaaaaa",       // 整串同字符
        };
        for (String pwd : weakPasswords) {
            JsonNode body = bodyOf(register(String.format(
                    "{\"username\":\"t91auth01\",\"password\":\"%s\",\"nickname\":\"测试\"}", pwd)));
            assertEquals(400, body.path("code").asInt(), "弱口令应被拒：" + pwd + " -> " + body);
            assertEquals(com.smartqa.platform.validation.StrongPassword.MESSAGE,
                    body.path("message").asText(),
                    "拒绝文案必须与后端约束文案逐字一致（前端已复用同一句）：" + pwd);
        }
    
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username LIKE 't91auth%'", Integer.class),
                "被强度规则拒绝的注册不得写入用户");
    
        // 反向断言：结构合格但含符号的口令应当通过（避免规则过严把正常用户挡在门外）
        JsonNode ok = bodyOf(register("""
                {"username":"t91auth01","password":"Tr0ub4dor&3","nickname":"合法口令用户"}"""));
        assertEquals(200, ok.path("code").asInt(), "合规口令应注册成功，实际：" + ok);
    }
    
    @Test
    @DisplayName("昵称卫生：零宽字符、尖括号、整串灌水、40 字长名均回 400（Issue #94）")
    void nicknamePolicyRejectsDirtyForms() throws Exception {
        String[] badNicknames = {
                "张\u200B小华",                 // 零宽空格：会让同一个显示名“看起来相同比实不同”
                "<script>x</script>",        // 尖括号：不进日志与教师端导出
                "aaaaaaaa",                   // 整串同字符灌水
                "李小华 ",                     // 尾部空白：拒绝而不是静默 trim
                "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十超过三十",  // 34 字，旧注册上限 50 能过、新约束必须拒
        };
        for (String nick : badNicknames) {
            JsonNode body = bodyOf(register(String.format(
                    "{\"username\":\"t91auth01\",\"password\":\"Passw0rd91\",\"nickname\":\"%s\"}", nick)));
            assertEquals(400, body.path("code").asInt(),
                    "应被拒的昵称：[" + nick + "] -> " + body);
        }
    
        // 正向：中文真名与常见英文昵称应当通过（规则不能把真实用户挡住）
        JsonNode ok = bodyOf(register("""
                {"username":"t91auth01","password":"Passw0rd91","nickname":"王小雨-2024级"}"""));
        assertEquals(200, ok.path("code").asInt(), "合规姓名应注册成功，实际：" + ok);
    }
    
    @Test
    @DisplayName("未登录不得调用登出接口（/api/auth/logout 不在匿名白名单里）")
    void logoutRequiresLogin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/logout")).andReturn();
        assertHttp200(result);
        assertEquals(401, bodyOf(result).path("code").asInt(),
                "logout 必须要求已登录；若被加进 excludePathPatterns 本用例会失败");
    }
}

# 成员 B 详细开发文档：后端业务与数据架构师

> **角色**：成员 B（后端业务与数据架构师 · 后端工程师）  
> **职责模块**：MySQL 数据库设计、MyBatis-Plus 持久层、Sa-Token 身份认证与 RBAC 权限、课程与课件业务 CRUD、问答持久化与反馈、学情统计 API、Knife4j 接口文档  
> **适用技术栈**：Spring Boot 3.x + MySQL 8.0 + MyBatis-Plus + Sa-Token + Knife4j (OpenAPI 3)

---

## 一、 模块定位与工程职责边界

成员 B 是整个系统的**“业务骨架与数据基石”**，负责保证业务逻辑的严密性、数据存储的稳固性与系统鉴权安全性：

1. **数据库物理建模与维护**：设计并维护 6 张核心表结构，编写规范的初始建表 SQL（`schema.sql`）与演示模拟数据（`data.sql`）。
2. **安全鉴权与角色权限（RBAC）**：基于 Sa-Token 实现轻量级无状态 Token 机制，划分“学生（STUDENT）”与“教师（TEACHER）”双重身份体系，实现路由白名单与鉴权拦截。
3. **课程与课件元数据管理**：课程的新增/修改/删除/查询；课件文件的本地/OSS 存储落盘，维护课件元数据与解析状态机（`PENDING` -> `PARSING` -> `CHUNKED` -> `FAILED`）。
4. **问答持久化与评价闭环**：配合成员 A 的流式输出，异步记录每次问答的提问、回答、耗时及切块溯源；提供会话列表查询与学生点赞/点踩反馈接口。
5. **学情统计与教师纠偏**：为成员 D 教师后台提供热点疑问聚合、分类统计接口；提供教师对 AI 错漏回答的人工纠偏保存接口。
6. **接口契约先行**：集成 Knife4j，第一时间向成员 C 和成员 D 提供可在线调试的 OpenAPI 接口文档。

---

## 二、 数据库物理表结构设计 (DDL)

```sql
-- 1. 用户信息表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '登录学号/工号',
    password VARCHAR(100) NOT NULL COMMENT 'BCrypt加密密码',
    nickname VARCHAR(50) NOT NULL COMMENT '用户真实姓名',
    role VARCHAR(20) NOT NULL DEFAULT 'STUDENT' COMMENT '角色: STUDENT-学生, TEACHER-教师',
    avatar_url VARCHAR(255) DEFAULT '' COMMENT '头像地址',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- 2. 课程表
CREATE TABLE IF NOT EXISTS course (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '课程ID',
    course_name VARCHAR(100) NOT NULL COMMENT '课程名称 (如: 操作系统原理)',
    course_code VARCHAR(50) NOT NULL UNIQUE COMMENT '课程编号 (如: CS202401)',
    teacher_id BIGINT NOT NULL COMMENT '任课教师ID',
    description VARCHAR(500) DEFAULT '' COMMENT '课程简介',
    cover_image VARCHAR(255) DEFAULT '' COMMENT '课程封面图',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-正常, 1-删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_teacher (teacher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程主表';

-- 3. 课程课件资料表
CREATE TABLE IF NOT EXISTS course_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '课件ID',
    course_id BIGINT NOT NULL COMMENT '关联课程ID',
    file_name VARCHAR(200) NOT NULL COMMENT '课件文件名 (如: 第3章_虚拟内存管理.pdf)',
    file_path VARCHAR(500) NOT NULL COMMENT '磁盘存储相对路径',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件字节大小',
    file_type VARCHAR(20) NOT NULL COMMENT '文件格式 (pdf, docx, md, txt)',
    chunk_count INT NOT NULL DEFAULT 0 COMMENT '切块片段总数',
    parse_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING-排队中, PARSING-切片中, CHUNKED-已就绪, FAILED-失败',
    error_msg VARCHAR(500) DEFAULT '' COMMENT '解析失败原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_course_doc (course_id, parse_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程课件资料表';

-- 4. 智能问答会话表
CREATE TABLE IF NOT EXISTS qa_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '会话ID',
    user_id BIGINT NOT NULL COMMENT '提问学生ID',
    course_id BIGINT NOT NULL COMMENT '关联课程ID',
    session_title VARCHAR(100) NOT NULL DEFAULT '新建答疑会话' COMMENT '会话标题',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_course_session (user_id, course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能问答会话表';

-- 5. 智能问答明细记录表
CREATE TABLE IF NOT EXISTS qa_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    session_id BIGINT NOT NULL COMMENT '所属会话ID',
    user_id BIGINT NOT NULL COMMENT '提问学生ID',
    course_id BIGINT NOT NULL COMMENT '所属课程ID',
    question TEXT NOT NULL COMMENT '学生提问内容',
    answer LONGTEXT NOT NULL COMMENT 'AI生成的Markdown回答',
    grounding_references JSON NULL COMMENT '命中的课件出处快照 (JSON数组)',
    is_corrected TINYINT NOT NULL DEFAULT 0 COMMENT '是否被教师纠偏: 0-否, 1-是',
    corrected_answer LONGTEXT NULL COMMENT '教师人工修正后的标准答案',
    teacher_comment VARCHAR(255) DEFAULT '' COMMENT '教师评语',
    feedback_rating TINYINT DEFAULT 0 COMMENT '学生打分: 1-点赞, -1-点踩, 0-未评',
    latency_ms INT DEFAULT 0 COMMENT '模型生成耗时(毫秒)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_record (session_id),
    INDEX idx_course_record (course_id, is_corrected)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问答明细记录表';

-- 6. 课程核心知识点与考点库表
CREATE TABLE IF NOT EXISTS course_knowledge_point (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '知识点ID',
    course_id BIGINT NOT NULL COMMENT '关联课程ID',
    chapter_name VARCHAR(100) NOT NULL COMMENT '所属章节',
    title VARCHAR(150) NOT NULL COMMENT '知识点标题 (如: 页面置换算法LRU与FIFO对比)',
    summary TEXT NOT NULL COMMENT '核心精解摘要',
    quiz_json JSON NULL COMMENT '配套自测选择题与解析(JSON)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_course_point (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程知识点库表';
```

---

## 三、 Maven 依赖与核心配置

### 3.1 `pom.xml` 依赖
```xml
<!-- MyBatis-Plus 增强 -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.7</version>
</dependency>

<!-- MySQL 驱动 -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Sa-Token 权限认证 (Spring Boot 3 版) -->
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-spring-boot3-starter</artifactId>
    <version>1.38.0</version>
</dependency>

<!-- BCrypt 密码散列加密 -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>

<!-- Knife4j API 文档支持 -->
<dependency>
    <groupId>com.github.xiaoymin</groupId>
    <artifactId>knife4j-openapi3-jakarta-spring-boot-starter</artifactId>
    <version>4.5.0</version>
</dependency>
```

### 3.2 Sa-Token 鉴权拦截器配置 (`SaTokenConfigure.java`)
```java
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 Sa-Token 路由拦截器
        registry.addInterceptor(new SaInterceptor(handle -> {
            // 教师后台接口必须具备 TEACHER 角色
            SaRouter.match("/api/teacher/**", r -> StpUtil.checkRole("TEACHER"));
            // 学生问答接口必须已登录
            SaRouter.match("/api/qa/**", r -> StpUtil.checkLogin());
        })).addPathPatterns("/api/**")
           .excludePathPatterns("/api/auth/login", "/api/auth/register", "/doc.html", "/v3/api-docs/**");
    }
}
```

---

## 四、 核心业务控制器与接口实现

### 4.1 统一响应封装与全局异常处理
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {
    private Integer code;    // 200: 成功, 400: 业务错误, 401: 未登录, 403: 无权, 500: 系统错误
    private String message;
    private T data;
    private Long timestamp;

    public static <T> Result<T> success(T data) {
        return Result.<T>builder().code(200).message("操作成功").data(data).timestamp(System.currentTimeMillis()).build();
    }
    public static <T> Result<T> fail(Integer code, String message) {
        return Result.<T>builder().code(code).message(message).data(null).timestamp(System.currentTimeMillis()).build();
    }
}
```

### 4.2 用户登录接口 (`AuthController.java`)
```java
@Tag(name = "身份认证模块")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserService userService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @PostMapping("/login")
    @Operation(summary = "用户登录 (学生/教师统一入口)")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        SysUser user = userService.getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, dto.getUsername()));
        
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // Sa-Token 登录注标
        StpUtil.login(user.getId());
        StpUtil.getSession().set("role", user.getRole());

        LoginVO vo = LoginVO.builder()
                .token(StpUtil.getTokenValue())
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .role(user.getRole())
                .avatarUrl(user.getAvatarUrl())
                .build();

        return Result.success(vo);
    }
}
```

### 4.3 课件上传与元数据维护 (`TeacherDocumentController.java`)
```java
@Tag(name = "教师端-课件管理")
@RestController
@RequestMapping("/api/teacher/docs")
@RequiredArgsConstructor
public class TeacherDocumentController {

    private final CourseDocumentService docService;
    private final DocumentIngestionService ingestionService; // 成员 A 提供的 RAG 切块服务

    @PostMapping("/upload")
    @Operation(summary = "课件文件上传并触发切块")
    public Result<Long> uploadDoc(
            @RequestParam("courseId") Long courseId,
            @RequestParam("file") MultipartFile file) throws IOException {

        if (file.isEmpty()) {
            throw new BusinessException("上传文件不可为空");
        }

        // 1. 本地存储落盘
        String originalName = file.getOriginalFilename();
        String fileType = originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase();
        String savedPath = "uploads/" + courseId + "/" + System.currentTimeMillis() + "_" + originalName;
        File dest = new File(savedPath);
        dest.getParentFile().mkdirs();
        file.transferTo(dest);

        // 2. 写入数据库，初始状态为 PENDING
        CourseDocument doc = CourseDocument.builder()
                .courseId(courseId)
                .fileName(originalName)
                .filePath(savedPath)
                .fileSize(file.getSize())
                .fileType(fileType)
                .parseStatus("PARSING")
                .build();
        docService.save(doc);

        // 3. 异步触发成员 A 的切块向量化
        CompletableFuture.runAsync(() -> {
            try (InputStream in = new FileInputStream(dest)) {
                int chunks = ingestionService.processAndEmbedDocument(in, courseId, doc.getId(), originalName);
                doc.setChunkCount(chunks);
                doc.setParseStatus("CHUNKED");
                docService.updateById(doc);
            } catch (Exception e) {
                doc.setParseStatus("FAILED");
                doc.setErrorMsg(e.getMessage());
                docService.updateById(doc);
            }
        });

        return Result.success(doc.getId());
    }
}
```

### 4.4 教师人工纠偏接口 (`TeacherQaController.java`)
```java
@Tag(name = "教师端-问答审计与人工纠偏")
@RestController
@RequestMapping("/api/teacher/qa")
@RequiredArgsConstructor
public class TeacherQaController {

    private final QaRecordService qaRecordService;

    @PostMapping("/correct")
    @Operation(summary = "人工纠偏 AI 回答")
    public Result<Boolean> correctQaRecord(@Valid @RequestBody QaCorrectionDTO dto) {
        QaRecord record = qaRecordService.getById(dto.getRecordId());
        if (record == null) {
            throw new BusinessException("问答记录不存在");
        }

        record.setIsCorrected(1);
        record.setCorrectedAnswer(dto.getCorrectedAnswer());
        record.setTeacherComment(dto.getTeacherComment());
        boolean success = qaRecordService.updateById(record);

        return Result.success(success);
    }
}
```

---

## 五、 协同契约与交付物清单

### 5.1 对接配合要求
1. **向成员 A 提供**：在 `qa_record` 表建立后，向成员 A 提供持久化方法 `qaRecordService.saveStreamingRecord(...)`，在流式传输完毕后保存提问与完整回复。
2. **向成员 C（学生端）提供**：`/api/course/list`、`/api/qa/sessions`、`/api/qa/records/{id}/feedback`。
3. **向成员 D（教师端）提供**：`/api/teacher/docs/list`、`/api/teacher/stats/overview`、`/api/teacher/qa/correct`。

### 5.2 成员 B 验收与交付物自测表
- [ ] MySQL 脚本在本地顺利导入无报错，外键与索引创建完毕。
- [ ] 启动项目访问 `http://localhost:8080/doc.html` 能看到清晰的 Knife4j 接口文档。
- [ ] 使用学生与教师两种账号测试登录，测试未授权访问 `/api/teacher/**` 被拦截（403 异常）。
- [ ] 文件上传成功在本地 `uploads/` 目录落盘，数据库状态流转正确。

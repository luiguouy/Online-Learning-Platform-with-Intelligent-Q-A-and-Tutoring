# 成员 B 详细开发文档：后端业务与数据架构师

> **角色**：成员 B（后端业务与数据架构师 · 后端工程师）  
> **职责模块**：MySQL 数据库设计、MyBatis-Plus 持久层、Sa-Token 身份认证与 RBAC 权限、课程与课件业务 CRUD、问答持久化与反馈、Knife4j 接口文档  
> **适用技术栈**：Spring Boot 3.x + MySQL 8.0 + MyBatis-Plus + Sa-Token + Knife4j (OpenAPI 3)

---

## 一、 模块定位与工程职责边界

成员 B 是整个系统的**“业务骨架与数据基石”**，负责保证业务逻辑的严密性、数据存储的稳固性与系统鉴权安全性：

1. **数据库物理建模与维护**：设计并维护 6 张核心表结构，编写规范的初始建表 SQL（`schema.sql`）与演示模拟数据（`data.sql`）。
2. **安全鉴权与角色权限（RBAC）**：基于 Sa-Token 实现轻量级无状态 Token 机制，划分“学生（STUDENT）”与“教师（TEACHER）”双重身份体系，实现路由白名单与鉴权拦截。
3. **课程与课件元数据管理**：课程**查询**（本期接口清单只有 `GET /api/course/list`，课程的新增/修改/删除不在范围内，不要实现）；课件文件的本地存储落盘，维护课件元数据与解析状态机（`PENDING` -> `PARSING` -> `CHUNKED` -> `FAILED`）。
4. **问答持久化与评价闭环**：配合成员 A 的流式输出，异步记录每次问答的提问、回答、耗时及切块溯源；提供会话列表查询与学生点赞/点踩反馈接口。
5. **问答记录查询**：为成员 D 教师后台提供学生提问明细的分页查询接口（支持按课程、时间、关键词筛选）。
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
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-正常, 1-删除',
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
    file_path VARCHAR(500) NOT NULL COMMENT '磁盘存储绝对路径（配置项 file.upload-dir 拼接后的全路径，重启不丢）',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件字节大小',
    file_type VARCHAR(20) NOT NULL COMMENT '文件格式 (pdf, docx, md, txt)',
    chunk_count INT NOT NULL DEFAULT 0 COMMENT '切块片段总数',
    parse_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING-排队中, PARSING-切片中, CHUNKED-已就绪, FAILED-失败',
    error_msg VARCHAR(500) DEFAULT '' COMMENT '解析失败原因',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-正常, 1-删除',
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
    feedback_rating TINYINT DEFAULT 0 COMMENT '学生打分: 1-点赞, -1-点踩, 0-未评',
    latency_ms INT DEFAULT 0 COMMENT '模型生成耗时(毫秒)',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-正常, 1-删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_record (session_id),
    INDEX idx_course_record (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问答明细记录表';

-- 6. 课程核心知识点与考点库表
CREATE TABLE IF NOT EXISTS course_knowledge_point (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '知识点ID',
    course_id BIGINT NOT NULL COMMENT '关联课程ID',
    chapter_name VARCHAR(100) NOT NULL COMMENT '所属章节',
    title VARCHAR(150) NOT NULL COMMENT '知识点标题 (如: 页面置换算法LRU与FIFO对比)',
    summary TEXT NOT NULL COMMENT '核心精解摘要',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-正常, 1-删除',
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
           // 只放行登录与接口文档；本期无注册接口，不要添加 /api/auth/register
           .excludePathPatterns("/api/auth/login", "/doc.html", "/v3/api-docs/**");
    }
}
```

### 3.3 Sa-Token 角色提供器 (`StpInterfaceImpl.java`) —— 必须实现，否则 `checkRole` 全部 403
Sa-Token 的 `StpUtil.checkRole()` 默认不知道任何用户的角色，**必须**实现 `StpInterface` 从数据库/Session 提供角色列表，否则教师端所有接口联调第一天即被 403 拦死：
```java
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final SysUserService userService;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList(); // 本项目仅做角色级控制，权限点留空
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        SysUser user = userService.getById(Long.valueOf(loginId.toString()));
        return user == null ? Collections.emptyList() : List.of(user.getRole()); // "TEACHER" / "STUDENT"
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

**业务异常类**（`BusinessException.java`）—— 必须有 `code` 字段与 `getCode()`，否则下面的全局异常处理器无法编译：

```java
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    /** 默认 400 业务错误 */
    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    /** 自定义错误码（如需 404、429 等） */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
```

**全局异常处理器**（`GlobalExceptionHandler.java`）：

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLogin(NotLoginException e) {
        return Result.fail(401, "登录已过期，请重新登录");
    }

    @ExceptionHandler(NotRoleException.class)
    public Result<Void> handleNotRole(NotRoleException e) {
        return Result.fail(403, "无权访问该资源");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        return Result.fail(400, msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("系统异常: ", e);   // 日志里保留完整堆栈，但绝不返回给前端
        return Result.fail(500, "系统繁忙，请稍后重试");
    }
}
```

> ⚠️ 注意：返回给前端的永远是 `Result` 结构，**绝不把异常堆栈或原始 `e.getMessage()`（可能含 SQL、路径等敏感信息）直接吐给前端**。

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
    private final QaRecordService qaRecordService;
    @Resource(name = "sseExecutor")
    private Executor asyncExecutor; // AsyncThreadPoolConfig 中定义的专用线程池（见 AGENT_INSTRUCTIONS 1.3），禁止用默认公共池

    @Value("${file.upload-dir}") // application.yml 配置绝对路径: ${user.home}/smartqa/uploads/
    private String uploadDir;

    @PostMapping("/upload")
    @Operation(summary = "课件文件上传并触发切块")
    public Result<Long> uploadDoc(
            @RequestParam("courseId") Long courseId,
            @RequestParam("file") MultipartFile file) throws IOException {

        if (file.isEmpty()) {
            throw new BusinessException("上传文件不可为空");
        }

        // 1. 本地存储落盘（必须用配置的绝对路径，严禁 "uploads/" 相对路径——jar 运行/重启会丢文件）
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.matches("(?i).+\\.(pdf|docx|md|txt)$")) {
            throw new BusinessException("仅支持 PDF / DOCX / MD / TXT 格式");
        }
        // 【安全】剥离路径，只保留纯文件名。
        // 上面的正则中「.」会匹配 / 与 \，不清洗的话 "../../evil.pdf" 能通过校验并被写到上传目录之外（路径穿越）。
        String safeName = Paths.get(originalName).getFileName().toString();
        String savedPath = uploadDir + courseId + "/" + System.currentTimeMillis() + "_" + safeName;
        File dest = new File(savedPath);
        dest.getParentFile().mkdirs();
        file.transferTo(dest);

        // 2. 写入数据库。状态说明：落库即进入 PARSING（异步切块已提交，见步骤 3）；
        //    PENDING 仅用于"已排队但尚未提交切块任务"的场景，本项目同步提交，故实际不会落库为 PENDING。
        CourseDocument doc = CourseDocument.builder()
                .courseId(courseId)
                .fileName(originalName)
                .filePath(savedPath)
                .fileSize(file.getSize())
                .fileType(originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase())
                .parseStatus("PARSING")
                .build();
        docService.save(doc);

        // 3. 异步触发成员 A 的切块向量化（显式指定专用线程池）
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
        }, asyncExecutor);

        return Result.success(doc.getId());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除课件（级联清理向量库，防止幽灵参考资料）")
    public Result<Boolean> deleteDoc(@PathVariable Long id) {
        CourseDocument doc = docService.getById(id);
        if (doc == null) {
            throw new BusinessException("课件不存在");
        }
        // 关键：MySQL 删除后必须同步删除 Chroma 中该 docId 的全部切块（成员 A 提供方法）
        ingestionService.removeDocumentVectors(id);
        docService.removeById(id);
        return Result.success(true);
    }
}
```

### 4.4 教师查看问答记录接口 (`TeacherQaController.java`)

> **功能范围说明**：教师后台**只做问答记录的查看**，不提供修改 AI 回答（人工纠偏）功能。
> 因此本 Controller 是**纯只读**的：只有 GET 查询，没有任何写接口。
> 相应地，`qa_record` 表也不再需要 `is_corrected` / `corrected_answer` / `teacher_comment` 字段（DDL 已同步删除）。
```java
@Tag(name = "教师端-问答记录查看")
@RestController
@RequestMapping("/api/teacher/qa")
@RequiredArgsConstructor
public class TeacherQaController {

    private final QaRecordService qaRecordService;

    @GetMapping("/records")
    @Operation(summary = "分页查询学生提问明细（只读）")
    public Result<IPage<QaRecord>> listRecords(
            @RequestParam Long courseId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String keyword) {
        // 按课程 + 关键词（匹配 question 或 answer）分页查询，按提问时间倒序
        return Result.success(qaRecordService.pageRecords(courseId, pageNum, pageSize, keyword));
    }
}
```

### 4.5 其余必须实现的接口（**缺失将直接导致前端 404**）

以下 7 个接口在 `TEAM_WORK_DIVISION.md` 接口矩阵中已声明由成员 B 提供，成员 C/D 的前端会真实调用。
**本节之前各章只给了 Auth / TeacherDocument / TeacherQa 三个 Controller 的代码，这些接口若不实现，前端调一个 404 一个。**

| 接口 | 方法 | 路径 | 说明 | Controller |
| :--- | :--- | :--- | :--- | :--- |
| 课程列表 | GET | `/api/course/list` | 返回当前用户可见课程（学生/教师视角不同） | `CourseController` |
| 会话历史 | GET | `/api/qa/sessions?courseId={id}` | 返回该课程的会话列表（含首条提问做标题） | `QaSessionController` |
| 会话明细 | GET | `/api/qa/records?sessionId={id}` | 返回该会话下的问答记录列表 | `QaSessionController` |
| 点赞/点踩 | POST | `/api/qa/records/{id}/feedback` | body: `{"status": 1 / -1}`，写入 `feedback_rating` | `QaSessionController` |
| 课件列表 | GET | `/api/teacher/docs/list?courseId={id}` | 教师端课件管理列表（**D 指南 4.1 直接调用此路径**） | `TeacherDocumentController` |
| 索引重构 | POST | `/api/teacher/docs/{id}/reindex` | **Controller 由 B 提供**，内部调用 A 的 `removeDocumentVectors(id)` 再重新切块，状态回到 `PARSING`→`CHUNKED`（**必须异步执行**） | `TeacherDocumentController` |
| 教师查看问答记录 | GET | `/api/teacher/qa/records?courseId=&pageNum=&pageSize=&keyword=` | 只读分页查询学生提问明细（实现见本章 4.4） | `TeacherQaController` |

**实现要点**：
- 全部返回 `Result<T>` 统一包装，路径与矩阵**逐字符一致**（前端已按此写死）。
- 课程/会话类接口必须带 **Sa-Token 登录校验**，并按角色过滤数据（学生只能看自己的会话）。
- `feedback` 接口必须校验 `status` 只能是 `1` 或 `-1`，其余值返回参数错误。
- `reindex` 属于重建索引，耗时较长，**必须异步执行**（用 `sseExecutor`，禁止默认线程池）。
- ✅ **逻辑删除列已全部齐备（v3.0 修复）**：`application.yml` 配了全局 `logic-delete-field: isDeleted`，因此**6 张表都必须有 `is_deleted` 列**——本章 DDL 已逐表补齐。后续改动 DDL 时严禁漏掉任一列：缺列的表调用 `removeById()` 会直接抛 `Unknown column 'is_deleted'`。

**重建索引骨架**（`TeacherDocumentController` 内新增，教师端"重建索引"按钮直接对接此接口）：

```java
@PostMapping("/{id}/reindex")
@Operation(summary = "重建课件索引：清旧向量 → 重新切块向量化")
public Result<Boolean> reindex(@PathVariable Long id) {
    CourseDocument doc = docService.getById(id);
    if (doc == null) {
        throw new BusinessException("课件不存在");
    }

    // 1. 先清掉旧向量，否则重建会产生重复切片（成员 A 提供的方法）
    ingestionService.removeDocumentVectors(id);

    // 2. 状态置回 PARSING，切块数清零
    doc.setParseStatus("PARSING");
    doc.setChunkCount(0);
    doc.setErrorMsg("");
    docService.updateById(doc);

    // 3. 异步重新切块（必须用专用线程池，禁止默认公共池）
    CompletableFuture.runAsync(() -> {
        try (InputStream in = new FileInputStream(doc.getFilePath())) {
            int chunks = ingestionService.processAndEmbedDocument(
                    in, doc.getCourseId(), doc.getId(), doc.getFileName());
            doc.setParseStatus("CHUNKED");
            doc.setChunkCount(chunks);
            docService.updateById(doc);
        } catch (Exception e) {
            doc.setParseStatus("FAILED");
            doc.setErrorMsg(e.getMessage());
            docService.updateById(doc);
        }
    }, asyncExecutor);

    return Result.success(true);
}
```

> 上表其余 4 个接口（`GET /api/course/list`、`GET /api/qa/sessions`、`GET /api/qa/records`、`POST /api/qa/records/{id}/feedback`）都是标准 MyBatis-Plus 分页与 CRUD，照本指南 4.2~4.4 的写法实现即可。
> **唯一硬要求：路径必须与上表逐字符一致**——前端已按这些路径写死，差一个字符就是 404。

---

### 4.6 答疑接口限流 (`QaRateLimitInterceptor.java`)

**为什么必须做**：答疑接口每次调用都消耗大模型 Token（含 Embedding + 生成），公开演示或恶意重复提问会在几分钟内烧光额度。答辩时评委常问"别人一直刷你的接口怎么办"，有实现就能直接回答。

**实现要求**：按用户维度做**固定窗口计数**，**零新增依赖**（只用 JDK 的 `ConcurrentHashMap` / `AtomicInteger`，不要引入 Guava）。

```java
@Component
public class QaRateLimitInterceptor implements HandlerInterceptor {

    /** 限流规则：每用户每 60 秒最多 20 次提问 */
    private static final int MAX_REQUESTS = 20;
    private static final long WINDOW_MILLIS = 60_000L;

    private final Map<Long, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final Map<Long, Long> windowStart = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response, Object handler) throws Exception {
        // 关键：未登录时绝不能调用 getLoginIdAsLong()，否则抛 NotLoginException 变成 500
        // 此处直写 JSON 是 Result<T> 红线的官方豁免（拦截器拿不到 Controller 返回值），字段名仍须与 Result 一致
        if (!StpUtil.isLogin()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"请先登录\"}");
            return false;
        }

        Long userId = StpUtil.getLoginIdAsLong();
        long now = System.currentTimeMillis();

        windowStart.compute(userId, (id, start) -> {
            if (start == null || now - start > WINDOW_MILLIS) {
                counters.put(id, new AtomicInteger(0));
                return now;
            }
            return start;
        });

        if (counters.get(userId).incrementAndGet() > MAX_REQUESTS) {
            // 字段名必须是 message，与统一响应 Result.message 保持一致
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":429,\"message\":\"提问过于频繁，请稍后再试\"}");
            return false;
        }
        return true;
    }
}
```

注册（**只拦截答疑与知识点接口**，不要全局拦截，否则会误伤登录与上传）。可直接合并进已有的 `SaTokenConfigure`：

```java
registry.addInterceptor(qaRateLimitInterceptor)
        .addPathPatterns("/api/qa/chat/stream", "/api/knowledge/generate");
```

**验收标准**：连续快速提问第 21 次返回 429；未登录访问返回 401（不是 500）。

> **关于计数器清理**：上面两个 Map 会随不同用户累积。本项目演示环境用户数极少（<10 人），**可以不做清理**；若追求严谨，可在启动类加 `@EnableScheduling`，再补一个定时任务清理超过 60 秒未活动的条目。

---

## 五、 协同契约与交付物清单

### 5.1 对接配合要求
1. **向成员 A 提供**：在 `qa_record` 表建立后，向成员 A 提供以下方法（`QaRecordService`）：
   - `Long saveStreamingRecord(Long courseId, Long sessionId, String question, String answer, List<SseReferenceVO> references, long latencyMs)`：流式传输完毕后保存提问与完整回复，**返回生成的 `recordId`**（成员 A 在 SSE `done` 包中回传）。
     ⚠️ **返回值不能为 null**：成员 A 会把它直接写进 `Map.of("recordId", recordId, ...)`，而 `Map.of` 不接受 null 值，会抛 `NullPointerException`。
   - `Long createSessionLazy(Long courseId, String question)`（`QaSessionService`）：`sessionId=0` 时懒创建会话（标题取问题前 15 字符），**返回新建的 `sessionId`**（成员 A 用它替换 `done` 包中的 `sessionId`）。
   - **状态回写由你自己的异步块完成**（见本章 4.3：`doc.setParseStatus("CHUNKED"); doc.setChunkCount(chunks); docService.updateById(doc);`），**不需要成员 A 调用任何方法**。注意状态值是**字符串**，取值仅限 `PENDING` / `PARSING` / `CHUNKED` / `FAILED`。
     ⚠️ 切块异常时务必把状态置为 `FAILED` 并写入 `error_msg`，否则前端会永远显示"切块向量化中"。
2. **向成员 C（学生端）提供**：`/api/course/list`、`/api/qa/sessions`、`/api/qa/records`、`/api/qa/records/{id}/feedback`。
3. **向成员 D（教师端）提供**：`/api/teacher/docs/list`、`/api/teacher/qa/records`（问答记录查看）、`/api/teacher/docs/{id}`（删除课件）。

### 5.2 成员 B 验收与交付物自测表
- [ ] MySQL 脚本在本地顺利导入无报错，外键与索引创建完毕。
- [ ] 启动项目访问 `http://localhost:8080/doc.html` 能看到清晰的 Knife4j 接口文档。
- [ ] 使用学生与教师两种账号测试登录，测试未授权访问 `/api/teacher/**` 被拦截（403 异常）。
- [ ] 文件上传成功在**配置的绝对路径**（`${user.home}/smartqa/uploads/`，非相对路径 `uploads/`）下落盘，数据库状态流转正确。

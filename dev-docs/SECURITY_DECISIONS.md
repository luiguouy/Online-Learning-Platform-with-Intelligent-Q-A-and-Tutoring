# 安全决策记录

> 本文档记录对抗式安全审查后做出的**产品级安全决策**，避免后续成员把有意设计当漏洞"修复"，或在不知情的情况下破坏既有约束。

## SEC-1：课件内容对全体登录用户公开（2026-09-19，owner 决策）

**结论**：RAG 检索与学生问答**不校验**学生对课程的访问权，任何登录用户可对任意课程提问并获得课件内容。这是**有意设计**，不是漏洞。

**背景**：
- 审查项 H1：`SseStreamService` 只用 `IsEqualTo("courseId", ...)` 做检索租户隔离，从不校验学生与课程的从属关系。
- 当前数据库**没有选课关系表**（学生-课程无关联实体），按课授权无从落地。

**适用范围**：
- 学生端 SSE 问答（`/api/qa/**`）
- 教师端课程/课件接口仅限教师本人课程（`assertTeacherOwnsCourse` 照常生效，不受本决策影响）

**约束**：
1. 不要在检索链路上加"课程访问校验"并返回 403——那会与产品决策冲突。
2. 若未来要改为按课授权，前置条件是：先引入选课关系表（需迁移脚本），再在 `SseStreamService` 检索前补访问校验，同时更新本文档。
3. 教师端权限边界（TEACHER 角色 + 课程归属校验）与本决策无关，始终强制。

**失效条件**：平台面向真实多租户/付费课程部署时，本决策自动失效，必须重审。

## SEC-2：上传目录的 HTTP 暴露边界（2026-10-17，组长 A 决策）

**结论**：`file.upload-dir` 根目录**禁止整体映射为静态资源**。允许经 HTTP 直访的只有两个低敏子目录（`avatars/`、`attachments/`）；课件原件（存在 `{uploadDir}/{courseId}/` 下）**不得出现在任何静态映射里**。

**背景**：SEC-1 说“课件内容对全体**登录**用户公开”，这句话的边界是**登录**，不是匿名。实现里一旦加上 `registry.addResourceHandler("/uploads/**").addResourceLocations("file:" + uploadDir)` 这种盖头映射，就会把同一目录树下的课件原件一并变成**无需登录即可下载**，而它们的落盘名是 `{courseId}/{时间戳}_{原始文件名}`（零随机熵，courseId 自增、原始文件名在学生端课程列表可见）—— 两者叠加就从“可猜”变成“可枚举”。

**约束**：
1. 新增静态映射只允许按子目录逐条加，**不得**回到 `"/uploads/**"` 这种盖头写法；
2. 公开子目录里的文件名必须带**完整 UUID**（不可截断）；课件类高价值原件若将来要做“下载原件”功能，必须走带鉴权的接口（TEACHER 角色 + `assertTeacherOwnsCourse`），不得靠静态映射兼带发出；
3. 改这条时必须同步 `TeacherDocumentController` 的落盘路径约定，避免新旧文件分在两个目录里没人知道。

**失效条件**：引入真实多租户/私有课件计费，或平台不再使用本地磁盘存储时重审。

## SEC-3：限流分两个拦截器，而不是一个全局闸（2026-10-17）

**结论**：全项目保留两套互补的限流，**不得合并成一个“全局限流拦截器”**。

| 拦截器 | 覆盖 | 计数键 | 未登录时 |
|---|---|---|---|
| `QaRateLimitInterceptor`（成员 B） | `/api/qa/chat/stream`、`/api/knowledge/generate` | 用户 id | 直接 401 |
| `AuthRateLimitInterceptor`（成员 A） | 登录/注册/写盘类接口 | 匿名接口用 IP，上传用用户 id | 放过（本来就是匿名接口） |

**为何必须分开**：`QaRateLimitInterceptor` 未登录即 401，而登录/注册恰恰是匿名接口 —— 合并等于把所有未登录用户挡在门外。这是“好心办坏事”的典型，已有注释锁定。

**为何上传按用户、登录按 IP**：全校学生常从同一 NAT 出口（机房一个 IP）出来，按 IP 限上传会让整个机房共用一份额度；而登录/注册在拿到身份前只能按 IP。两个维度不是实现细节，是威胁模型差异。

**硬约束**：
1. 限流响应**HTTP 状态码恒 200**，业务码 429 只放 body（全项目契约，前端按 body 解包）；
2. 零新增依赖（AGENT_INSTRUCTIONS 1.3），只用 JDK 并发容器；
3. IP 只取 `getRemoteAddr()`，**不得自行读 `X-Forwarded-For`**（客户端可伪造）；代理之后靠 prod 的 `forward-headers-strategy=native`；
4. 新增需限流的接口时，`AuthRateLimitInterceptor.RULES` 与 `SaTokenConfigure` 的 `addPathPatterns` **必须同时改**（只改一边等于死配置或不拦）。

**失效条件**：横向扩容为多实例时，内存计数失效，必须改 Redis（INCR+EXPIRE）集中计数。

## SEC-4：生产靠独立 profile 取消“开发便利默认值”（2026-10-17）

**结论**：不在 `application.yml` 里删默认值（会打断全组本地环境与 CI），而是新增 `application-prod.yml` 并在其中取消兜底：`MYSQL_PASSWORD` / `MYSQL_HOST` / `MYSQL_PORT` / `CORS_ALLOWED_ORIGINS` 均不给默认值，文档类开关全关。

**理由**：本地 `.env` 与 `.env.example` 里 `MYSQL_PASSWORD` 默认是注释掉的，开发链路本来就靠 `application.yml` 的 `123456` 跑起来。直接删默认值的净效果是“全组起不来”而不是“更安全”；prod profile 才能把约束加在真正需要它的部署环境上：**缺配置就启动失败，而不是带病上线**。

**约束**：部署文档/启动命令必须带 `--spring.profiles.active=prod`；不得为了“启动方便”在 prod 里重新加默认值。

## SEC-5：安全响应头只兜后端能兜的部分（2026-10-18，PR #96）

**结论**：`SecurityHeadersFilter` 对全部响应下发 `X-Content-Type-Options=nosniff`、`X-Frame-Options=DENY`、`Referrer-Policy=strict-origin-when-cross-origin`、`Permissions-Policy` 关设备能力；仅 `/uploads/**` 额外带 `default-src 'none'; img-src 'self'; sandbox` 的 CSP。HSTS 由 `app.security.hsts-enabled` 控制，只在 prod 打开。

**理由**：真正同源可访的用户可控字节在 `/uploads/**`（头像、学生附件）。把它们锁成「不可作为文档执行」，即使有人将 HTML/脚本改名为 `.png` 上传也无法在同一域下跑起来。而面向 SPA 的 CSP 属于部署层（Vite dev server / nginx）的配置，写在后端 JSON 响应上既无效也不合适。

**被否决的方案**：
1. 给 `/doc.html` 加严格 CSP —— Knife4j 文档台大量使用内联脚本，严格 CSP 会直接弄坏开发调试台；它已在 prod 整体关闭（SEC-4），风险窗口仅在开发环境。
2. “前端已有 DOMPurify，所以头也不需要什么” —— 净化与响应头是两个独立防线，前者防 XSS 注入后者防内容类误解释，不可互相替代。

**约束**：新增用户可控静态目录时，必须像 uploads 一样附加 sandbox 级 CSP；不得为图方便去掉 `sandbox`；不得在开发 profile 里打开 HSTS（本机是 http，一发 HSTS 浏览器会拒访本机）。

**失效条件**：改用对象存储 + 独立域名/CDN serve 上传件时，同源执行风险自然消失，本条可简化为存储桶策略。

## SEC-6：口令强度与昵称校验做到“结构层”即止（2026-10-18，PR #96）

**结论**：注册/改资料口令 = 8-32 位且同时含字母与数字，并拒绝常见弱口令黑名单、整串同字符与等步长序列（`StrongPassword`）；昵称/姓名 = 1-30 字且不含控制字符、零宽字符、尖括号，首尾不得带空白，不得整串灌水（`ValidNickname`，注册与改资料共用）。**明确不做**：图形/短信验证码、专业弱口令字典、中文敏感词库。

**理由**：三者都需新增依赖或外部服务，与“零新增重型依赖”红线冲突。而它们要治的风险已被现有手段覆盖：批量灌水注册由 IP 维限流（5 次/10 分钟/IP，SEC-3）压到不可行；弱口令由黑名单覆盖高频项；昵称字段的前端展示已有 XSS 三道防线（markdown-it `html:false` + DOMPurify 白名单 + Vue 插值转义），剩余风险只是「日志与教师端审计页被脏字符污染」，结构卫生拦得住。

**被否决的方案**：接 reCAPTCHA/极验（新增依赖 + 隐私合规 + 演示环境还要处理测试密钥）；接 HaveIBeenPwned 字典（外部服务，且课程项目将口令哈希外送并无必要）；引入中文敏感词库（重型依赖且需持续维护，判罚口径本属产品决策）。

**有意的非对称（切勿“修复”）**：**登录接口不校验口令强度**。否则 `data.sql` 里口令为 `123456` 的三个演示账号会当场登不进去，把安全改动做成演示事故。只有新注册/改密受限。

**约束**：阈值与提示文案**三处同改** —— 后端 `validation/StrongPassword.java`与`ValidNickname.java`、前端 `frontend-student/src/utils/validators.ts`、`backend/README` §3.1.1/§3.7。本项目吃过“文案说一个数、实际拦另一个数”的亏，两边提示必须逐字一致。

**失效条件**：平台面向真实公网开放注册、或引入等保/合规要求时，验证码与词库必须真做，本条自动失效。

# 课程设计全文档红蓝对抗性审查报告 (ADVERSARIAL_AUDIT_REPORT.md)
## AI 驱动的在线学习智能答疑辅导平台（RAG 知识库）

> **审计背景**：针对面向 AI Agent 自动化生成代码与 4 人本科生团队全栈交付方案的全面红蓝对抗性审查。  
> **审计基准**：4 人协同零摩擦、接口零猜测、RAG 核心闭环零死锁、答辩演示零翻车。

> **🟢 v4.0 核实（代码级可编译性专项）—— 最新一轮，请先读本节**
>
> **触发条件**：组员**完全没有编程经验**，文档里的代码示例若不自洽，AI 生成的代码会直接编译失败，而组员**没有能力发现**。因此本轮不看措辞，只验证"代码链条是否闭合"。
> **核查方法**：① 方法签名链逐个对齐（调用方 vs 提供方，比参数顺序/类型/返回类型）；② 包结构比对；③ 类定义完整性；④ Vue 模板变量与事件处理器自动化校验；⑤ 配置键 → getter → yml 三方映射核对。
> **结论**：新发现 8 项（P0 1 项、P1 3 项），**已全部修复**。
>
> | ID | 级别 | 问题（一句话） | 涉及文档 | 状态 |
> | :--- | :--- | :--- | :--- | :--- |
> | **C1** | **P0** | `updateParseStatus(docId, CHUNKED, chunkCount)` 中的 `CHUNKED` 是**裸标识符**，Java 会编译报 `cannot find symbol`（需要字符串字面量） | `MEMBER_A_DEV_GUIDE` 5.1 | 已修：改为字符串字面量并加显式警示 |
> | **C2** | **P1** | **包结构冲突**：A 指南用 `com.smartqa.platform.rag.*`（自建 controller / service / model 子包），而 `AGENT_INSTRUCTIONS` 1.1 规定 Controller 在顶层 `controller/`、RAG 服务在 `service/rag/`。A 与 B 是同一后端工程的两人，会写出**两套目录结构**，合并后 import 全乱 | `MEMBER_A_DEV_GUIDE` 三 / `AGENT_INSTRUCTIONS` 1.1 | 已修：以 1.1 为准统一，并加"禁止自建 rag.* 子包"警示 |
> | **C3** | **P1** | **`RagConfigProperties` 类定义完全缺失**：A 指南代码调用 `getChunk().getSize()` / `getTopK()` / `getSimilarityThreshold()`，但该类从未给出字段定义，Agent 只能猜——猜错即编译失败或静默取到 null | `MEMBER_A_DEV_GUIDE` 2 | 已修：补完整类定义（llm/chroma/chunk 三层静态内部类 + 逐字段标注对应 yml 键 + `topK`/`similarityThreshold` 易错提示） |
> | **C4** | **P1** | B 指南 5.1 的**方法签名不完整**：`saveStreamingRecord` / `createSessionLazy` / `updateParseStatus` 只给了方法名，缺返回类型与参数类型；且未说明 `recordId` 不可为 null——A 会把它放进 `Map.of(...)`，而 **`Map.of` 不接受 null 值**，会抛 NPE | `MEMBER_B_DEV_GUIDE` 5.1 | 已修：补全类型签名与"返回值不可为 null"的约束 |
> | **C5** | P2 | D 指南课件表格出现**两个 `chunkCount` 列**（原表已有"切块片段数"，v3.0 修复时又加了一列） | `MEMBER_D_DEV_GUIDE` 4.1 | 已修：删除重复列 |
> | **C6** | P2 | A 指南 5.1 承诺"提供查询该课件已切分片段的接口，用于教师后台切块预览"——**该接口不在 12 接口清单内**（范围外功能） | `MEMBER_A_DEV_GUIDE` 5.1 | 已修：改为只回写 `chunkCount`，并注明不提供片段明细 |
> | **C7** | P2 | A 指南自测用例写"提问不相关的**政治**或娱乐话题"作为拒答测试语料 | `MEMBER_A_DEV_GUIDE` 5.2 | 已修：改为"课件外的专业问题"（如用《操作系统》课件问《计算机网络》的题） |
> | **C8** | P2 | `AGENT_INSTRUCTIONS` 1.1 的项目结构**缺 `SseStreamService`**；2.2 节流代码里的 `currentAiMessage` / `scrollToBottom` 未说明来源 | `AGENT_INSTRUCTIONS` 1.1 / 2.2 | 已修：补 SseStreamService 并标注各 Service 承载的关键方法；2.2 加占位变量说明 |
>
> **本轮未发现问题的项（逐项验证过，一并列出）**：
> - **方法签名链一致**：`processAndEmbedDocument(InputStream, Long, Long, String)` → A 定义、B 调用 ✓；`removeDocumentVectors(Long)` → A 定义、B 调用 ✓；`saveStreamingRecord` 的 6 个参数顺序与类型在 A 的调用处与 B 的签名处一致，返回 `Long` ✓。
> - **Vue 组件模板变量**：对 4 个组件脚本化校验，模板引用的**事件处理器全部在 `<script setup>` 中定义**，无未定义引用（v3.0 修复的 `previewChunks` 类问题未复发）。
> - **前端字段 ↔ DDL**：教师端表格用的 `fileName` / `fileType` / `chunkCount` / `parseStatus` / `createdAt` 与 DDL 下划线列名驼峰对应 ✓，状态四态判断与规范一致 ✓。
> - **配置键 ↔ getter**：`getChunk().getSize()/getOverlap()/getTopK()/getSimilarityThreshold()` ↔ `rag.chunk.size/overlap/top-k/similarity-threshold` ✓；`@Value("${file.upload-dir}")` ↔ `file.upload-dir` ✓。
> - **结构完整**：12 个 md 代码围栏全部成对。
>
> **🔵 v3.0 复审（功能裁剪后的完整性检查）**
>
> **触发条件**：v2.0 功能裁剪（删除人工纠偏、ECharts 看板、知识点自测题、评测与演示兜底，整份删除 `EVALUATION_AND_DEMO.md`，净删 300+ 行）后，必须验证是否留下**断链引用、过时数字与未闭环的旧问题**。
> **审查方法**：双路交叉验证（人工逐条比对 + 独立审查 Agent 全量扫描）+ 全仓关键词检索 + 引用链与代码围栏自动化校验。
> **结论**：新发现 20 项（其中 P0 1 项、P1 4 项），**已全部修复**。
>
> | ID | 级别 | 问题（一句话） | 涉及文档 | 状态 |
> | :--- | :--- | :--- | :--- | :--- |
> | **M1** | **P0** | 上一轮 N9 **只加了文字提醒、DDL 实际没改**：全局 `logic-delete-field: isDeleted` 要求每张表都有 `is_deleted` 列，但 6 张表中 `sys_user`/`course_document`/`qa_record`/`course_knowledge_point` **4 张都缺列** → 调用 `removeById()` 抛 `Unknown column 'is_deleted'` | `MEMBER_B_DEV_GUIDE` 2 | 已修：4 张表逐表补齐该列，提醒文字同步改为"已齐备" |
> | **M2** | P1 | D 指南 4.1 用**裸 `axios`** 调 `/api/teacher/docs/list`——不经拦截器、不带双头，教师接口**必然 401**；且 `token` 变量取出后从未使用（死代码） | `MEMBER_D_DEV_GUIDE` 4.1 | 已修：改用 `@/utils/request` 封装，删除死代码 |
> | **M3** | P1 | `TEAM_WORK_DIVISION` 第三章仍写"**第 2 周末**为接口契约冻结日"，与三周计划的 Gate 1（Day 7）及 `COLLABORATION_WORKFLOW` 6.2 自相矛盾 | `TEAM_WORK_DIVISION` 3 | 已修：改为"第 1 周末（Day 7，Gate 1）" |
> | **M4** | P2 | N9 的提醒文字把表名**写反**（称 `course_document`/`qa_record` 有该列，实际是 `course`/`qa_session`） | `MEMBER_B_DEV_GUIDE` 4.5 | 已修：文字与 DDL 对齐 |
> | **M5** | P2 | 审查报告 N12 的"矩阵 9 → 13 接口"在裁剪后已过时（现为 12 个） | `ADVERSARIAL_AUDIT_REPORT` | 已修：加注"v2.0 裁剪后为 12 个" |
> | **M6** | P2 | C/D 指南代码写了 `import request from '@/utils/request'`，但两处目录结构里**没有 `request.ts` 的落点**，Agent 可能自建裸 axios 或找不到文件 | `MEMBER_C_DEV_GUIDE` 三 / `MEMBER_D_DEV_GUIDE` 三 | 已修：C 目录补 `utils/request.ts`；D 补"公共文件来源"说明 |
> | **M7** | P3 | 限流计数器 Map 无清理逻辑，文档未说明可否忽略 | `MEMBER_B_DEV_GUIDE` 4.6 | 已补说明：演示规模可不清理，需严谨则加 `@EnableScheduling` |
> | **M8** | P2 | `AGENT_INSTRUCTIONS` 项目结构里 `RagRetrievalService` 仍注释为"**双路召回**"——那是纠偏机制的产物（纠偏库 + 向量库），纠偏删除后只剩单路向量检索，该注释会误导 Agent 去实现两路 | `AGENT_INSTRUCTIONS` 1.1 | 已修：改为"向量召回与上下文组装（单路向量检索，无纠偏库）" |
> | **M9** | P2 | B 指南自测表写"文件落盘到 `uploads/` 目录"，与前文"严禁相对路径、必须用配置的绝对路径"自相矛盾 | `MEMBER_B_DEV_GUIDE` 5.2 | 已修：改为"配置的绝对路径 `${user.home}/smartqa/uploads/`" |
> | **M10** | **P1** | D 指南 4.1 的 `el-upload` 只发 `Authorization` 头、缺 `satoken` → **上传必 401**；裁剪时删掉 script 里的 `token` 变量后模板仍在引用（**未定义变量，vue-tsc 直接报错**）；模板还引用了 script 中从未定义的 `previewChunks` / `handleDelete` | `MEMBER_D_DEV_GUIDE` 4.1 | 已修：改用 `uploadHeaders` 计算属性（双头）；补齐 `handleDelete` 并接后端 DELETE；去掉无对应接口的"查看片段"改为展示切块数 |
> | **M11** | **P1** | A 指南 4.1 调用 `CourseDocumentService.updateParseStatus(docId, CHUNKED, chunkCount)`，但 **B 指南从未定义该方法**（B 侧只有 `updateById`）→ 切块完成后无法回写状态，课件永远停在 `PARSING` | `MEMBER_A_DEV_GUIDE` 4.1 / `MEMBER_B_DEV_GUIDE` 5.1 | 已修：在 B 指南 5.1"向成员 A 提供"清单中补上该方法签名 |
> | **M12** | **P1** | 审查报告第四节**答辩预案 Q1 的抗辩稿把"教师人工纠偏"当作第三道防线**——该功能已裁，评委若要求现场演示即当场翻车 | `ADVERSARIAL_AUDIT_REPORT` 四 | 已修：第三道防线改为"知识库可治理 + 出处可溯源"，并加注旧稿不可再照答 |
> | **M13** | P2 | `TEAM_WORK_DIVISION` 中 B 的 WBS 仍写"提供教师后台**数据统计 API**（问答量、高频提问）"——该功能已裁 | `TEAM_WORK_DIVISION` 2.2 | 已修：改为"提供教师后台问答记录查询接口（只读）" |
> | **M14** | P2 | 同文档中 C 的 WBS 仍写"集成 **KaTeX** 数学公式渲染"，而三周计划已把 KaTeX 列为 Won't | `TEAM_WORK_DIVISION` 2.3 | 已修：删除该条 |
> | **M15** | P2 | 同文档中 D 的 WBS 仍写"实现课程管理界面（**创建课程、修改状态**）"，但接口矩阵只有 `GET /api/course/list`，无课程增删改接口 | `TEAM_WORK_DIVISION` 2.4 | 已修：改为"课程列表展示与切换（只读）" |
> | **M16** | P2 | `COLLABORATION_WORKFLOW` 6.1 仍写"接口矩阵（**9 个接口**的提供方/调用方）"，裁剪后为 12 个 | `COLLABORATION_WORKFLOW` 6.1 | 已修：改为 12 个 |
> | **M17** | P2 | B 指南 4.5 导语写"以下 **5 个接口**"，但表格实际列了 7 条 | `MEMBER_B_DEV_GUIDE` 4.5 | 已修：改为 7 个 |
> | **M18** | P2 | `AGENT_INSTRUCTIONS` 1.1 的 Controller 清单（`QaRecordController` / `TeacherManageController`）与 B/A 指南实际类名（`QaSessionController` / `TeacherDocumentController` / `TeacherQaController` / `KnowledgeController`）**不一致**，Agent 可能建出两套类 | `AGENT_INSTRUCTIONS` 1.1 | 已修：清单按实际类名重写，A 指南的 `KnowledgeExtractController` 同步统一为 `KnowledgeController` |
> | **M19** | P2 | `GLOSSARY` 报错速查表的 401 条目只让检查 `Authorization` 头，未提 `satoken`——与双头铁律冲突，按它排查会查不出问题 | `GLOSSARY` 三 | 已修：改为"必须同时带两个头" |
> | **M20** | P2 | 审查报告 v1.1/v1.2 的历史结论与新范围矛盾（v1.1 称"纠偏优先双路检索已并入 SseStreamService"；v1.2 的 3 周结论把"教师纠偏"列为 Must、把"知识点精解"列为可砍项） | `ADVERSARIAL_AUDIT_REPORT` | 已修：两处均加 v2.0 修订标注（纠偏已移除、知识点解析属 Must） |
>
> **裁剪专项核查（未发现问题的项，一并列出以证明覆盖面）**：
> - **引用链完整**：全仓 12 处"见 `XXX.md` 第 N 节"类交叉引用逐一核实，目标文件与章节均真实存在（唯一的过期表述即 M3，已修）。
> - **已删实体无指导性残留**：`findTopCorrected` / `is_corrected` / `corrected_answer` / `teacher_comment` / `quiz_json` / `QuizCard` / `CorrectionDialog` / `AnalyticsDashboard` / `echarts` / `stats/overview` / `recall` / `EVALUATION_AND_DEMO` 全仓检索，**仅出现在"已裁掉/禁止实现"的说明语境中**，无一处仍指导实现。
> - **接口三方一致**：`TEAM_WORK_DIVISION` 矩阵、`THREE_WEEK_PLAN` 1.1、`MEMBER_B_DEV_GUIDE` 4.5 三处均为 **12 个接口且路径一致**。
> - **结构完整**：12 个 md 文件的代码围栏全部成对，无未闭合代码块；`.github/` 与 `templates/` 无已裁功能残留。
> - **功能范围表述一致**：`README` 铁律第 1 条、`THREE_WEEK_PLAN` 1.1、`TEAM_WORK_DIVISION` 角色表与 WBS、四个成员指南的职责模块，五处描述无冲突。
>
> **🔴 v1.2 复审（2026-09-12 · 3 周工期版）**
>
> **复审触发条件**：① 工期由 5 周压缩为 **3 周**；② 新增协作治理、评测演示、术语三类文档；③ 新增三周冲刺计划。
> **复审方法**：逐文件交叉比对 + 关键词全仓检索 + 代码示例可编译性验证（Python/YAML 实跑校验）。
> **结论**：新发现 7 项问题，**均已在本轮同步修复**（下表状态列）。修复后的文档即唯一有效版本。
>
> | ID | 级别 | 问题（一句话） | 涉及文档 | 状态 |
> | :--- | :--- | :--- | :--- | :--- |
> | **N1** | P0 | 限流代码用了**项目 pom 里根本没声明**的 Guava `RateLimiter`，直接编译失败；且未登录时 `StpUtil.getLoginIdAsLong()` 抛 `NotLoginException` 导致 500 | `EVALUATION_AND_DEMO.md` 4.2（该文件已删除，限流代码迁至 `MEMBER_B_DEV_GUIDE.md` 4.6） | 已修：改为零依赖固定窗口计数 + `isLogin()` 前置判断 |
> | **N2** | P1 | 接口矩阵声明的 5 个接口（课程列表、会话历史、会话明细、点赞、索引重构）在 B 指南中**完全没有实现代码**，前端调一个 404 一个 | `TEAM_WORK_DIVISION.md` 3 / `MEMBER_B_DEV_GUIDE.md` | 已修：B 指南新增 4.5 节，补全 5 个接口的路径与实现要点 |
> | **N3** | P1 | 工期压缩为 3 周后，**4 处时间线表述仍是 5 周版本**（契约冻结日、每日集成起始周、评测填数周次、5 周路线图本身），会让组员排错期 | `COLLABORATION_WORKFLOW` 6.2/7.1、`EVALUATION_AND_DEMO` 2.3（已删除）、`TEAM_WORK_DIVISION` 4 | 已修：统一改为 3 周口径，5 周路线图正文已移除、仅留作废提示 |
> | **N4** | P1 | `@types/echarts@4.9` 与 `echarts@5.5` 类型冲突，`vue-tsc --noEmit` 报红、CI 卡死 | `MEMBER_D_DEV_GUIDE.md` 2 | 已修：移除该依赖并加警示说明 |
> | **N5** | P2 | 接口矩阵**缺课件删除接口**；`reindex` 提供方标注为"成员 A"，但 A 指南根本没有 Controller，实际只能由 B 出接口 | `TEAM_WORK_DIVISION.md` 3 | 已修：矩阵 9 → 11 个接口（**v2.0 裁剪后为 12 个**），提供方改为"B（接口）+ A（服务）" |
> | **N6** | P2 | 分工文档承诺集成 KaTeX 数学公式，但 C 指南未实现、依赖未声明，是"纸面需求" | `TEAM_WORK_DIVISION.md` 2.3 | 已修：三周计划中明确列为 **Won't 不做** |
> | **N7** | P1 | 原 5 周工作量压进 3 周，**没有裁剪清单**，第 3 周必然崩在"到处是半成品" | 全局 | 已修：`THREE_WEEK_PLAN.md` 1.2 给出 MoSCoW 优先级与倒序裁剪清单 |
> | **N8** | **P0** | **鉴权头自相矛盾**：`token-name: satoken` 意味着 Sa-Token 只读 `satoken` 头，但文档写"以 Authorization 为准、不再单独发 satoken"，而 C 指南 SSE 客户端**只发了 Authorization** → **SSE 直接 401，主功能不可用** | `AGENT_INSTRUCTIONS` 1.2/2.1、`DEV_SPECIFICATION` 4.2、`MEMBER_C` 4.1 | 已修：锁定"**双头同发**"铁律，SSE 客户端补齐 `satoken` 头 |
> | **N9** | P0 | 全局 `logic-delete-field: isDeleted`，但 DDL 只有部分表有 `is_deleted` 列 → 缺列的表调用 `removeById()` 抛 `Unknown column 'is_deleted'` | `AGENT_INSTRUCTIONS` 1.2 / `MEMBER_B` 2 | 已修：B 指南 4.5 加"6 张表全部补齐"提醒 |
> | **N10** | P1 | 上传**路径穿越**：文件名未清洗，且正则中 `.` 会匹配 `/`，`../../evil.pdf` 可通过校验并写到上传目录之外 | `MEMBER_B` 4.3 | 已修：加 `Paths.get(name).getFileName()` 剥离路径 |
> | **N11** | P1 | `/api/knowledge/generate` 单次消耗大量 Token 却**无鉴权、无限流**，可被刷爆额度 | `MEMBER_A` 1 | 已修：明确要求 `StpUtil.checkLogin()` + 按用户限流 |
> | **N12** | P1 | 接口矩阵缺 `/api/teacher/docs/list`、`/api/teacher/stats/overview`，但 D 指南代码已在调用这两个路径 | `TEAM_WORK_DIVISION` 3 / `MEMBER_D` | 已修：矩阵与 B 指南 4.5 同步补齐（当时 9 → 13 接口；**v2.0 裁剪后为 12 个**，见 v2.0 说明） |
> | **N13** | P2 | 限流响应体字段名 `msg` 与统一响应 `Result.message` 不一致，前端弹窗显示 `undefined` | `EVALUATION_AND_DEMO` 4.2（已迁至 `MEMBER_B_DEV_GUIDE` 4.6） | 已修：统一为 `message` |
> | **N14** | P2 | A 指南**文字与代码打架**：文字写 `DocumentByParagraphSplitter` 与 snake_case 元数据键，代码却用 `DocumentSplitters.recursive` + camelCase → Agent 照文字写会**编译失败**或**检索静默失效** | `MEMBER_A` 4.1 | 已修：描述改为与代码一致并加警示 |
> | **N15** | P3 | 上传注释写"初始状态 PENDING"但代码写 `PARSING`；CI 未考虑学生端/教师端双前端工程布局 | `MEMBER_B` 4.3 / `.github/workflows/ci.yml` | 已修：注释对齐 + CI 加多前端说明 |
>
> **复审方法说明**：本轮采用**双路交叉验证**——一路人工逐条比对，一路独立审查 Agent 全量扫描，双方独立取证后合并去重，最终确认 15 项。两条路径各自都曾出现误判（例如把已在依赖中的 `markdown-it` 当成缺失、把实际已创建的 `THREE_WEEK_PLAN.md` 当成不存在），**均经回读原文证伪后剔除**——审查结论只采信有 `文件:行号` 证据支撑的条目。
>
> **⚙️ v2.0 功能范围裁剪（同日执行，优先级高于本报告全部内容）**
>
> 经需求方确认，本期**只实现 5 项核心功能**：①课程资料构建 RAG 知识库、②学生提问智能答疑、③知识点解析、④问答记录、⑤教师后台管理（课件管理 + 问答记录查看）。**原则：不写多余的代码，不实现多余的功能。**
>
> 以下功能已从全部文档中移除：**人工纠偏、ECharts 学情看板、学情统计接口（`/api/teacher/stats/overview`）、知识点自测题、金标集与 recall 评测脚本、演示缓存回放与录屏预案**。
> 以下两项**予以保留**（成本极低，且分别支撑"问答记录"与"防刷"诉求）：**点赞/点踩、接口限流**。
>
> **对本报告的影响**：
> - **缺陷 3（教师纠偏与 RAG 链路割裂）已不适用**——纠偏功能不再实现。相关的 `qa_record.is_corrected` / `corrected_answer` / `teacher_comment` 字段、`findTopCorrected` 方法、A 指南中的"纠偏优先双路检索"分支均已删除。**不要再按缺陷 3 的补丁实现任何代码。**
> - **缺陷 2（`done` 缺 `recordId`）仍然有效**——点斈/点踩与问答记录查询依旧依赖 `recordId`，`done` 包必须携带。
> - 缺陷 1、4、5 与漏洞 1~4 的修复仍然有效（不涉及被裁剪功能）。
> - 原 `EVALUATION_AND_DEMO.md` 已整份删除；其中**限流实现**已迁至 `MEMBER_B_DEV_GUIDE.md` 4.6，其余内容（金标集、评测脚本、演示兜底）不再保留。
>
> **3 周可行性结论（已被 v2.0 功能裁剪修订，以 v2.0 说明为准）**：**能做完。** v2.0 已将范围固定为 5 项核心功能（RAG 知识库构建、智能答疑、**知识点解析**、问答记录、教师后台管理）+ 点赞与限流；其中知识点解析属于 **Must**，而教师纠偏、学情看板、知识点自测题、评测脚本**已直接裁掉**（不再是"可砍项"，详见上方 v2.0 说明）。最大单点风险仍是**成员 A 的 Chroma 检索**（全项目唯一技术深水区），必须在 Day 2 完成 Spike 验证，失败当天启动内置向量存储退路。唯一硬死锁是**成员 B 必须在 Day 3 前交付 2 个方法签名给 A**（`saveStreamingRecord` / `createSessionLazy`），违反一次整体延期 3 天。

> **✅ 补丁落地状态（v1.1 复审）**：本报告中缺陷 1~5、漏洞 1~4 的修复方案**已全部回写进各源头文档并冻结为唯一契约**：SSE `done` 包已含 `recordId`（成员 A 指南代码）、纠偏优先双路检索已并入 `SseStreamService`（缺陷 3）——**注意：该机制已于 v2.0 随纠偏功能一并移除，不要再实现**、级联删除向量已提供 `removeDocumentVectors`（A/B 指南，缺陷 4）、会话懒创建规则已写入 `DEV_SPECIFICATION` 4.2（缺陷 5）、Sa-Token `Bearer` 前缀与 `satoken` 存储键已全团队统一（缺陷 1）、专用 `sseExecutor` 线程池强制注入（漏洞 1）、渲染节流见 AGENT_INSTRUCTIONS 2.2（漏洞 2）、`file.upload-dir` 绝对路径（漏洞 3）、`JacksonTypeHandler` + `autoResultMap`（漏洞 4）。**执行各成员任务时以回写后的文档为准，本报告仅作背景参考，不要再按"未修复前的旧代码"生成。**

---

## 目录
1. [跨成员接口与协同死锁（5 大致命隐患与补丁）](#一跨成员接口与协同死锁5-大致命隐患与补丁)
2. [系统工程与生产可靠性漏洞（4 大暴雷点与对策）](#二系统工程与生产可靠性漏洞4-大暴雷点与对策)
3. [AI Agent 开发特异性断层（4 个必须补齐的输入）](#三ai-agent-开发特异性断层4-个必须补齐的输入)
4. [答辩现场评委刁钻连环追问与抗辩预案（防挂科指南）](#四答辩现场评委刁钻连环追问与抗辩预案防挂科指南)

---

## 一、跨成员接口与协同死锁（5 大致命隐患与补丁）

### 🚨 缺陷 1：Sa-Token 鉴权 Header 与 SSE 请求规范冲突（联调直接 401 瘫痪）
* **涉及成员**：成员 B（鉴权） vs 成员 C（前端流式客户端）
* **冲突事实**：
  - 在 `MEMBER_B_DEV_GUIDE.md` 中，Sa-Token 默认配置从请求头 `satoken: <token>` 中提取认证信息，拦截器配置：`SaRouter.match("/api/qa/**", r -> StpUtil.checkLogin())`。
  - 在 `MEMBER_C_DEV_GUIDE.md` 中，前端 `fetchEventSource` 发送的是标准 JWT 请求头：`headers: { 'Authorization': 'Bearer ' + token }`。
* **致命后果**：
  - 成员 C 调用 `/api/qa/chat/stream` 时，后端 Sa-Token 根本拿不到 token，**所有智能问答流式请求 100% 返回 401 Unauthorized**。如果联调时排查不出，前后端会互相指责对方接口写错。
* **修复补丁**：
  - 成员 B 必须在 `application.yml` 中配置兼容 Header，或配置 token 前缀支持 `Bearer`：
  ```yaml
  sa-token:
    token-name: satoken
    is-read-header: true
    is-read-cookie: false
    token-prefix: Bearer # 允许自动剥离 Bearer 前缀并兼容 Authorization 标头
  ```

---

### 🚨 缺陷 2：SSE `done` 结束包未返回 `recordId`（问答评价与记录查询无法串联）
* **涉及成员**：成员 A（推流） vs 成员 B（存储） vs 成员 C（前台评价）
* **冲突事实**：
  - `MEMBER_A_DEV_GUIDE.md` 中，流式结束时推送：
    ```java
    emitter.send(SseEmitter.event().name("done").data(Map.of(
        "sessionId", sessionId, "finishReason", "stop"
    )));
    ```
  - 但在 `MEMBER_B` 和 `MEMBER_C` 的规范中，学生点赞/点踩接口是：`POST /api/qa/records/{id}/feedback`，需要明确的 `recordId`。
* **致命后果**：
  - 前端收到 `done` 后，只有 `sessionId`，**根本不知道刚刚生成的问答记录在数据库里的 `recordId` 是多少**。学生点击“点赞”或“点踩”时无法向后端传参，前端评价功能报废。
* **修复补丁**：
  - 成员 A 必须在 `done` 触发前，调用成员 B 暴露的持久化逻辑并拿到自增/雪花 `recordId`，在 `done` 包中下发：
    ```java
    Long recordId = qaRecordService.saveStreamResult(courseId, sessionId, question, fullAnswer.toString(), matches);
    emitter.send(SseEmitter.event().name("done").data(Map.of(
        "recordId", recordId,
        "sessionId", sessionId,
        "finishReason", "stop"
    )));
    ```

---

### 🚨 缺陷 3：教师人工纠偏与 RAG 检索链路割裂（“假闭环”：改了白改）
* **涉及成员**：成员 D（纠偏前台） vs 成员 B（纠偏落库） vs 成员 A（RAG 检索）
* **冲突事实**：
  - 成员 D 界面提供了教师修改答案的弹窗，成员 B 提供了 `/api/teacher/qa/correct` 将修改结果写入 `qa_record.corrected_answer` 并标记 `is_corrected = 1`。
  - **但是，成员 A 的 `RagRetrievalService` 依然只去检索 Chroma 向量数据库中的课件切块**！根本没有去查 `qa_record` 表！
* **致命后果**：
  - 教师在后台纠偏了“进程与线程的区别”，下一个学生在前端再问同样的问题时，**AI 依然基于原课件输出之前的错误回答**。所谓的“人工纠偏与监督微调”成为假功能，答辩演示时必露馅！
* **修复补丁（双路检索策略）**：
  - 成员 A 在检索向量库前，增加一层**纠偏库精准/语义快搜**：
    ```java
    // 优先匹配教师已纠偏的问题 (支持关键词/余弦距离最高且 is_corrected=1)
    Optional<QaRecord> corrected = qaRecordMapper.findTopCorrected(courseId, question);
    if (corrected.isPresent()) {
        // 直接下发纠偏标准答案，标记出处为“任课教师权威修正”
        emitCorrectedDirectly(emitter, corrected.get());
        return;
    }
    // 未命中纠偏记录，再走普通 RAG 课件检索
    ```

---

### 🚨 缺陷 4：课件删除后，Chroma 向量库未级联删除（“幽灵切块”脏数据）
* **涉及成员**：成员 B（删除课件） vs 成员 A（向量库）
* **冲突事实**：
  - 成员 D/B 实现了课件在 MySQL 中的删除：`DELETE FROM course_document WHERE id = ?` 或标记软删除。
  - 但 Chroma 向量数据库中的向量片段并没有被删除！
* **致命后果**：
  - 老师如果删除了包含旧版教学大纲或错误考点的课件，学生提问时，**系统依然会从 Chroma 召回已被删除的废弃课件切片**，并在前端出处抽屉里展示该已删课件，造成“幽灵参考资料”。
* **修复补丁**：
  - 成员 A 在 `DocumentIngestionService` 中补充物理级联清理方法：
    ```java
    public void removeDocumentVectors(Long docId) {
        // 基于元数据 docId 过滤并清理 Chroma 向量集合
        embeddingStore.removeAll(new IsEqualTo("docId", String.valueOf(docId)));
    }
    ```
  - 成员 B 在执行文档删除业务时，级联调用该方法。

---

### 🚨 缺陷 5：会话 `sessionId` 创生时机混乱，外键约束或历史关联断裂
* **涉及成员**：成员 C（发起新会话） vs 成员 A（流式答疑）
* **冲突事实**：
  - 前端刚进入页面时，学生尚未创建会话。此时如果直接在输入框提问，前端传给 `/api/qa/chat/stream` 的 `sessionId` 是 `null`、`0` 还是事先发请求创建？
* **修复补丁**：
  - **统一规范**：采用“接口懒加载创生”模式。若前端传 `sessionId=0` 或为空，后端自动在 `qa_session` 插入一条记录，标题截取提问前 15 个字符，并在首包或 `done` 中返回新 `sessionId`。

---

## 二、系统工程与生产可靠性漏洞（4 大暴雷点与对策）

### ⚡ 漏洞 1：`CompletableFuture.runAsync` 默认公共线程池耗尽
* **隐患**：成员 A 的代码使用了 `CompletableFuture.runAsync(() -> { ... })`。在没有传入自定义线程池时，默认使用的是 JVM 的 `ForkJoinPool.commonPool()`，其核心线程数等于 `CPU核数 - 1`。
* **暴雷场景**：答辩现场若有多位同学同时发问，由于大模型流式生成需要持续占用线程 20~60 秒，**后续提问将直接阻塞排队，导致整个后端响应冻结**！
* **对策**：必须在 `RagConfig` 中显式定义专门的业务虚拟线程池或异步线程池：
  ```java
  @Bean(name = "sseExecutor")
  public Executor sseExecutor() {
      ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
      executor.setCorePoolSize(10);
      executor.setMaxPoolSize(30);
      executor.setQueueCapacity(50);
      executor.setThreadNamePrefix("sse-worker-");
      executor.initialize();
      return executor;
  }
  ```

---

### ⚡ 漏洞 2：流式 Markdown 逐字全量重绘导致页面严重掉帧
* **隐患**：成员 C 的 `MarkdownViewer.vue` 中，每次大模型吐一个 token，就会触发 `sanitizeHtml` 和 `md.render()`，重新解析全量 HTML 字符串并重新挂载 DOM。
* **暴雷场景**：当回答到达 800 字以上时，随着 token 密集到达，浏览器主线程被高频全量 DOMPurify 和 Highlight.js 占满，**打字机产生肉眼可见的卡顿与打字停滞，甚至浏览器标签页假死崩溃**。
* **对策**：在前端增加**渲染节流（Throttle）**机制（50ms ~ 80ms 刷新一次渲染）。

---

### ⚡ 漏洞 3：大课件 Tika 解析内存溢出（OOM）与相对路径持久化丢失
* **隐患**：`MEMBER_B` 将上传文件保存至 `uploads/` 相对路径。在 Spring Boot 打包成 jar 包运行或重启容器时，相对路径下的文件将全部丢失。
* **对策**：必须在 `application.yml` 中配置绝对路径，如 `file.upload-dir: ${user.home}/smartqa/uploads/`。

---

### ⚡ 漏洞 4：MySQL 8.0 `JSON` 字段在 MyBatis-Plus 中序列化异常
* **隐患**：`qa_record` 表中的 `grounding_references` 定义为 `JSON` 类型。如果实体类中声明为 `List<SseReferenceVO>`，MyBatis-Plus 默认无法直接持久化，查询时抛出 `DataTruncation` 或 `TypeException`。
* **对策**：实体类字段必须加上注解：
  ```java
  @TableField(value = "grounding_references", typeHandler = JacksonTypeHandler.class)
  private List<SseReferenceVO> groundingReferences;
  ```
  并在类头上加上 `@TableName(autoResultMap = true)`。

---

## 三、AI Agent 开发特异性断层（4 个必须补齐的输入）

由于你们主要依靠 **AI Agent（如 Cursor, Windsurf, Claude Code 等）** 进行自动化代码编写，Agent 并不具备人类开发者的“经验隐式推断”能力。必须提供以下 4 类物料以防 Agent 乱写：
1. **系统级统一指令库 (`AGENT_INSTRUCTIONS.md`)**：明确禁止 Agent 引入微服务全家桶、严格限制类库版本。
2. **完整配置模板 (`application.yml`)**：提供开箱即用的配置，包含 Sa-Token、数据源、LLM API 与线程池参数。
3. **初始 SQL 数据脚本 (`data.sql`)**：预置 1 位老师、2 位学生测试账号与 2 门示范课程，方便 Agent 跑通冒烟自测。
4. **Agent 准入验证清单 (Gatekeeper Checklist)**：涵盖编译、类型检查、SSE 推流验证等 7 大检查项。

---

## 四、答辩现场评委刁钻连环追问与抗辩预案（防挂科指南）

| 评委刁钻提问 | 脆弱回答（易被扣分） | 优秀标准抗辩（高分示范） |
| :--- | :--- | :--- |
| **Q1：大模型有严重幻觉，课件里没有的内容它胡说八道怎么办？** | “我们用的模型很聪明，应该不会乱说。”（扣分） | “我们在系统层设计了三道防线：第一道是**元数据隔离检索**（Cosine 相似度严格限定 `>= 0.70`，低分片段直接丢弃）；第二道是**强约束系统提示词**，硬性规定未检索到时首句必须回答‘课件未提及’；第三道是**知识库可治理 + 出处可溯源**——每条回答都附带命中的课件片段与相关度，可当场点开核对，且教师删除或替换课件后，同一问题的答案会随之改变，这证明答案是‘从课件里找出来的’而不是模型编的。”（**注：原第三道防线"教师人工纠偏"已随 v2.0 裁剪取消，不要再照旧稿回答，否则评委会要求现场演示**） |
| **Q2：如果两个老师上传了同名课件，切块怎么隔离？学生提问怎么防止串课？** | “我们按照文件名区分的。”（扣分） | “我们在 Chroma 存储切块时，强制注入了租户级复合元数据：`courseId + docId + chunkIndex`。检索时在底层执行了强制的 Filter 条件过滤，彻底杜绝了不同课程间的跨域串流。” |
| **Q3：学生中途关闭网页或切换了题目，后端大模型还在消耗 Token 怎么处理？** | “没注意，关了就关了吧。”（扣分） | “我们在前端封装了基于 `AbortController` 的断流机制，一旦页面销毁或切换立即 `abort()`；后端 `SseEmitter` 监听了 `onCompletion` 和 `onTimeout` 回调，检测到客户端断开连接立即中断调用，防止算力与 Token 浪费。” |

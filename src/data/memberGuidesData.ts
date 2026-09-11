export interface MemberGuide {
  id: 'A' | 'B' | 'C' | 'D';
  name: string;
  roleTitle: string;
  badge: string;
  tagline: string;
  fileName: string;
  downloadUrl: string;
  summaryHighlights: string[];
  techStack: string[];
  markdownContent: string;
}

export const memberGuides: Record<'A' | 'B' | 'C' | 'D', MemberGuide> = {
  A: {
    id: 'A',
    name: '成员 A',
    roleTitle: 'AI / RAG 核心算法工程师 · 后端组长',
    badge: '算法与智能底座',
    tagline: '负责从课件非结构化解析到严谨流式输出的核心 RAG 链路与 Prompt 防幻觉调优。',
    fileName: 'MEMBER_A_DEV_GUIDE.md',
    downloadUrl: '/MEMBER_A_DEV_GUIDE.md',
    summaryHighlights: [
      'Spring Boot 3.x 工程底座初始化与 LangChain4j 0.35+ 整合',
      '课件切块（RecursiveSplitter 400字/50重叠）与 Chroma / 本地向量库落盘',
      '租户级隔离检索（courseId 过滤 + Cosine >= 0.70 阈值过滤）',
      'SSE 打字机流式输出接口（/api/qa/chat/stream 4 阶段事件流）',
      '知识点结构化精解与 3 道自测选择题生成 Prompt 调优',
    ],
    techStack: ['Spring Boot 3.x', 'LangChain4j', 'Chroma DB', 'Apache Tika', 'SSE (Server-Sent Events)'],
    markdownContent: `# 成员 A 详细开发文档：AI 与 RAG 核心算法工程师

> **角色**：成员 A（AI / RAG 核心算法工程师 · 后端组长）  
> **职责模块**：Spring Boot 工程底座、LangChain4j 接入、课件解析与向量化存储、RAG 检索增强、SSE 流式智能答疑接口、知识点结构化生成  
> **适用技术栈**：Spring Boot 3.x + LangChain4j 0.35+ + Chroma DB / 本地向量库 + 阿里云百炼/DeepSeek API + SSE (Server-Sent Events)

---

## 一、 模块定位与工程职责边界

成员 A 是整个系统的**“智能大脑枢纽”**，主要负责打通从**课件非结构化数据输入**到**高质量精准流式输出**的核心链路：

1. **工程骨架与基础脚手架**：初始化 Spring Boot 3.x 统一工程，配置 Maven 依赖管理。
2. **文档解析与切块（Document Ingestion）**：支持 PDF、Markdown、TXT 格式课件解析，执行递归字符切片（Chunk Size = 400，Overlap = 50）。
3. **向量化与存储（Embedding & Vector Store）**：使用通用文本向量模型（如 text-embedding-v3 或本地 BGE-small），元数据（courseId、docId、fileName）关联注入，存入 Chroma 或内存向量库。
4. **多阶段检索与防幻觉调优**：按 courseId 租户级过滤，Cosine 相似度 >= 0.70 过滤，召回 Top-K（3~4），组装严谨防幻觉 System Prompt。
5. **SSE 流式智能答疑接口**：对外提供 GET /api/qa/chat/stream，按照 4 阶段协议（references -> message -> done -> error）向前端打字机推流。
6. **知识点自动精解与自测题生成**：提供 POST /api/knowledge/generate 接口。

---

## 二、 核心依赖与配置

\`\`\`xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-spring-boot-starter</artifactId>
        <version>0.35.0</version>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
        <version>0.35.0</version>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-chroma</artifactId>
        <version>0.35.0</version>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-document-parser-apache-tika</artifactId>
        <version>0.35.0</version>
    </dependency>
</dependencies>
\`\`\`

---

## 三、 核心代码实现

### 3.1 课件切片与向量入库
\`\`\`java
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public int processAndEmbedDocument(InputStream inputStream, Long courseId, Long docId, String fileName) {
        Document document = new ApacheTikaDocumentParser().parse(inputStream);
        DocumentSplitter splitter = DocumentSplitters.recursive(400, 50);
        List<TextSegment> segments = splitter.split(document);

        for (int i = 0; i < segments.size(); i++) {
            Metadata metadata = segments.get(i).metadata();
            metadata.put("courseId", String.valueOf(courseId));
            metadata.put("docId", String.valueOf(docId));
            metadata.put("fileName", fileName);
            metadata.put("chunkIndex", i);
        }

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);
        return segments.size();
    }
}
\`\`\`

### 3.2 SSE 流式接口 (4 阶段事件推送)
- \`event: references\` -> 出处快照首包
- \`event: message\` -> 逐字流式推送
- \`event: done\` -> 结束信号与元数据
- \`event: error\` -> 异常处理

*(完整 200+ 行实现与防幻觉 System Prompt 请下载查看完整文档)*
`,
  },
  B: {
    id: 'B',
    name: '成员 B',
    roleTitle: '后端业务与数据架构师 · 后端工程师',
    badge: '业务骨架与数据基石',
    tagline: '负责 6 张核心表设计与迁移、MyBatis-Plus 持久层、Sa-Token 鉴权与业务 API 交付。',
    fileName: 'MEMBER_B_DEV_GUIDE.md',
    downloadUrl: '/MEMBER_B_DEV_GUIDE.md',
    summaryHighlights: [
      '设计维护 6 张核心数据表 (sys_user, course, course_document, qa_session, qa_record, course_knowledge_point)',
      '基于 Sa-Token 实现学生/教师双角色 RBAC 路由拦截与 Token 认证',
      '课件本地/OSS 文件存储落盘与解析状态机流转 (PENDING -> PARSING -> CHUNKED -> FAILED)',
      '问答会话历史分页与学生点赞/点踩反馈 API',
      '学情统计概览与教师人工纠偏覆盖 API，Knife4j 在线调试文档输出',
    ],
    techStack: ['Spring Boot 3.x', 'MySQL 8.0', 'MyBatis-Plus', 'Sa-Token', 'Knife4j (OpenAPI 3)'],
    markdownContent: `# 成员 B 详细开发文档：后端业务与数据架构师

> **角色**：成员 B（后端业务与数据架构师 · 后端工程师）  
> **职责模块**：MySQL 数据库设计、MyBatis-Plus 持久层、Sa-Token 身份认证与 RBAC 权限、课程与课件业务 CRUD、问答持久化与反馈、学情统计 API、Knife4j 接口文档  
> **适用技术栈**：Spring Boot 3.x + MySQL 8.0 + MyBatis-Plus + Sa-Token + Knife4j (OpenAPI 3)

---

## 一、 模块定位与工程职责边界

成员 B 是整个系统的**“业务骨架与数据基石”**，负责保证业务逻辑的严密性、数据存储的稳固性与系统鉴权安全性：

1. **数据库物理建模与维护**：设计并维护 6 张核心表结构，编写规范的初始建表 SQL（schema.sql）与演示模拟数据（data.sql）。
2. **安全鉴权与角色权限（RBAC）**：基于 Sa-Token 实现轻量级无状态 Token 机制，划分“学生（STUDENT）”与“教师（TEACHER）”双重身份体系。
3. **课程与课件元数据管理**：课程的新增/修改/删除/查询；课件文件落盘与状态机管理。
4. **问答持久化与评价闭环**：配合成员 A 的流式输出异步保存问答记录；提供点赞/点踩反馈接口。
5. **学情统计与教师纠偏**：为成员 D 教师后台提供热点聚合统计接口；提供教师对 AI 错漏回答的人工纠偏保存接口。
6. **接口契约先行**：集成 Knife4j 统一提供 Swagger / OpenAPI 接口定义。

---

## 二、 6 张核心数据表物理设计 (DDL 概要)
- sys_user (系统用户表)
- course (课程主表)
- course_document (课件资料表)
- qa_session (问答会话表)
- qa_record (问答明细记录表，含出处快照与纠偏字段)
- course_knowledge_point (核心知识点与考题库)

*(完整 DDL 包含所有字段说明与索引，已写入完整文档)*
`,
  },
  C: {
    id: 'C',
    name: '成员 C',
    roleTitle: '学生端智能答疑前台工程师 · 前端工程师',
    badge: '学生交互与视觉体验',
    tagline: '负责打造丝滑响应的智能答疑工作台、SSE 打字机通信、Markdown 高亮与出处溯源抽屉。',
    fileName: 'MEMBER_C_DEV_GUIDE.md',
    downloadUrl: '/MEMBER_C_DEV_GUIDE.md',
    summaryHighlights: [
      'Vue 3 + Vite + Pinia 搭建学生端响应式问答工作台与课程切换',
      '封装 fetch-event-source SSE 流式客户端，带 AbortController 优雅断流防泄漏',
      'Markdown-it + Highlight.js + DOMPurify 安全代码高亮与公式排版',
      '课件出处溯源侧边抽屉 (Grounding Drawer)，支持展示课件原句与相似度',
      '知识点深度解析卡片与 3 道自测选择题即时判分互动',
    ],
    techStack: ['Vue 3 (Setup)', 'Vite', 'TypeScript', 'Pinia', 'fetch-event-source', 'Markdown-it + DOMPurify'],
    markdownContent: `# 成员 C 详细开发文档：学生端智能答疑前台工程师

> **角色**：成员 C（学生前台核心开发工程师 · 前端工程师）  
> **职责模块**：学生前台问答工作台、SSE 打字机流式通讯、Markdown 与代码高亮渲染、知识库溯源抽屉、知识点解析与自测题交互  
> **适用技术栈**：Vue 3 (Composition API) + Vite + TypeScript + Pinia + Element Plus + Tailwind CSS + Highlight.js + DOMPurify

---

## 一、 模块定位与工程职责边界

成员 C 是学生用户的**“第一视觉与交互体验守门人”**，负责将复杂的 RAG 检索和大模型推理以丝滑、可信、直观的界面呈现给学生：

1. **学生工作台框架搭建**：实现响应式布局，左侧为课程切换与历史会话导航列表，中央为主问答流，右侧为出处溯源抽屉。
2. **SSE 流式通讯接收器**：封装 fetch-event-source，精确监听解析 4 类后端事件（references -> message -> done -> error），实现打字机效果。
3. **Markdown 与代码高亮渲染**：渲染复杂排版，支持代码一键复制与防 XSS 净化。
4. **知识库出处溯源侧边抽屉 (Grounding Drawer)**：卡片形式展示命中课件文件名、相似度与原文片段。
5. **知识点深度解析与自测题**：支持一键触发知识点精解与 3 道选择题互动。
`,
  },
  D: {
    id: 'D',
    name: '成员 D',
    roleTitle: '教师管理后台与学情可视化工程师 · 前端工程师',
    badge: '管理中枢与交付统筹',
    tagline: '负责课件上传状态机监控、问答记录审计与人工纠偏、ECharts 学情看板与答辩材料统筹。',
    fileName: 'MEMBER_D_DEV_GUIDE.md',
    downloadUrl: '/MEMBER_D_DEV_GUIDE.md',
    summaryHighlights: [
      '搭建 Vue 3 + Element Plus 教师端管理工作台与响应式侧边导航',
      '课件资料拖拽上传与切块状态监控 (排队中 -> 切片中 -> 已就绪 -> 失败)',
      '问答记录多维审计表格，重点筛选学生“点踩”负反馈记录',
      '教师人工纠偏工作台 (覆盖 AI 答案，保存最新标准答案到知识库)',
      'ECharts 7日提问热度趋势折线图与统计大屏，统筹答辩 PPT 与测试材料',
    ],
    techStack: ['Vue 3', 'Element Plus', 'ECharts 5.x', 'Axios', 'TypeScript'],
    markdownContent: `# 成员 D 详细开发文档：教师管理后台与学情可视化工程师

> **角色**：成员 D（教师后台与可视化工程师 · 前端工程师 · 答辩统筹）  
> **职责模块**：教师端管理后台、课件拖拽上传与切块状态监控、问答明细审计与人工纠偏、ECharts 学情统计看板、答辩演练与工程文档统筹  
> **适用技术栈**：Vue 3 + Vite + TypeScript + Element Plus + ECharts 5.x + Axios + Pinia

---

## 一、 模块定位与工程职责边界

成员 D 是教师角色的**“管理控制中心与学情洞察专家”**，同时兼任小组的**“交付与答辩质量总监”**：

1. **教师后台管理框架**：经典左侧菜单 + 顶部面包屑 + 主工作台布局。
2. **课件文件管理与分块状态监控**：拖拽上传课件（<=50MB），实时展示分块状态机。
3. **问答记录审计与人工干预（纠偏）**：多维度问答审计表格，支持人工纠偏弹窗覆写 AI 不严谨回答。
4. **学情看板与数据可视化 (ECharts)**：近 7 天提问趋势、高频热点词云、课程掌握率。
5. **项目验收与答辩物料统筹**：牵头整合团队代码仓库、编写使用说明书、准备答辩 PPT。
`,
  }
};

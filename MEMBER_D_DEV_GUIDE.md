# 成员 D 详细开发文档：教师管理后台工程师

> **角色**：成员 D（教师后台工程师 · 前端工程师 · 答辩统筹）  
> **职责模块**：教师端管理后台、课件上传与切块状态监控、问答记录查看、答辩演练与工程文档统筹  
> **适用技术栈**：Vue 3 + Vite + TypeScript + Element Plus + Axios + Pinia

---

## 一、 模块定位与工程职责边界

成员 D 是教师角色的**“管理控制中心”**，同时兼任小组的**“交付与答辩质量总监”**：

1. **教师后台管理框架**：采用经典左侧菜单 + 顶部面包屑 + 主工作台布局，只有两个子系统：**“课件知识库管理”**与**“问答记录查看”**。
2. **课件文件管理与分块状态监控**：实现拖拽上传课件（PDF/MD/TXT），限制大小（<=50MB），轮询展示切块状态机（排队中 -> 切片中 -> 已就绪 -> 失败），支持在线查看切块数量。
3. **问答记录查看**：提供问答记录表格，支持按课程、时间、关键词筛选，可查看单条问答的完整内容（提问、AI 回答、参考出处）。**该模块只读，不提供任何修改 AI 回答的功能。**
4. **项目验收与答辩物料统筹**：牵头整合团队的最终代码仓库、编撰系统使用说明书、准备答辩 PPT 与现场演示脚本。

---

## 二、 前端依赖配置 (`package.json`)

```json
{
  "dependencies": {
    "vue": "^3.4.21",
    "vue-router": "^4.3.0",
    "element-plus": "^2.6.1",
    "@element-plus/icons-vue": "^2.3.1",
    "axios": "^1.6.8"
  },
  "devDependencies": {
    "typescript": "^5.2.2",
    "vite": "^5.1.6"
  }
}
```

> **本项目不使用 ECharts**：学情可视化看板已按需求裁剪（不属于核心功能），**不要安装任何图表库**，也不要引入 `echarts` / `echarts-wordcloud` / `@types/echarts`。

---

## 三、 模块目录结构

```text
src/views/teacher/
├── TeacherLayout.vue          // 教师端通用导航与面包屑外框
├── CourseDocManage.vue        // 课件上传、分块切片状态管理
├── QaRecordList.vue           // 问答记录查看（只读）
└── components/
    └── DocUploadModal.vue     // 课件拖拽上传弹窗
```

> **依赖的公共文件**：两个页面都使用 `src/utils/request.ts`（axios 统一封装，自动注入 `Authorization: Bearer <token>` 头并解包 `Result.data`）。
> 若教师端是**独立的前端工程**，需要从 `AGENT_INSTRUCTIONS.md` 2.1 节复制一份 `request.ts` 到本工程 `src/utils/` 下；
> 若与学生端**共用同一个前端工程**，则直接复用即可。**无论如何都不要在页面里直接用裸 axios。**

---

## 四、 核心功能代码实现指南

### 4.1 课件拖拽上传与切块状态监控 (`CourseDocManage.vue`)
```vue
<template>
  <div class="p-6 space-y-6">
    <div class="flex items-center justify-between">
      <div>
        <h2 class="text-lg font-bold text-slate-900">课程知识库课件管理</h2>
        <p class="text-xs text-slate-500 mt-1">上传 PDF/Markdown 课件，系统将自动调用 LangChain4j 进行智能解析、切块与向量嵌入</p>
      </div>
      <el-button type="primary" :icon="Upload" @click="uploadDialogVisible = true">
        上传新课件资料
      </el-button>
    </div>

    <!-- 课件列表表格 -->
    <el-table :data="docList" stripe border class="w-full">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="fileName" label="课件文件名" min-width="200" />
      <el-table-column prop="fileType" label="类型" width="90">
        <template #default="{ row }">
          <el-tag size="small">{{ row.fileType.toUpperCase() }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="chunkCount" label="切块片段数" width="120" />
      <el-table-column prop="parseStatus" label="知识库状态" width="140">
        <template #default="{ row }">
          <el-tag v-if="row.parseStatus === 'CHUNKED'" type="success">● 已就绪 (RAG可用)</el-tag>
          <el-tag v-else-if="row.parseStatus === 'PARSING'" type="warning">● 切块向量化中...</el-tag>
          <el-tag v-else-if="row.parseStatus === 'PENDING'" type="info">● 待处理</el-tag>
          <el-tag v-else type="danger">● 失败 (查看原因)</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="上传时间" width="180" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" size="small" @click="handleDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 拖拽上传弹窗 -->
    <el-dialog v-model="uploadDialogVisible" title="上传课程资料" width="500px">
      <el-upload
        drag
        action="/api/teacher/docs/upload"
        :data="{ courseId: currentCourseId }"
        :headers="uploadHeaders"
        :on-success="handleUploadSuccess"
        :before-upload="beforeUpload"
        accept=".pdf,.docx,.md,.txt"
      >
        <el-icon class="el-icon--upload"><upload-filled /></el-icon>
        <div class="el-upload__text">
          拖拽课件文件到此处，或 <em>点击上传</em>
        </div>
        <template #tip>
          <div class="el-upload__tip text-xs text-slate-400">
            支持 PDF / Markdown / TXT，单文件大小不超过 50MB
          </div>
        </template>
      </el-upload>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { Upload, UploadFilled } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
// 必须用统一封装实例：它自动注入 Authorization: Bearer <token> 头，并自动解包 Result.data。
// 千万不要用裸 axios —— 不带头会让 /api/teacher/** 请求被判未登录（401）。
import request from '@/utils/request';

const docList = ref<any[]>([]);
const uploadDialogVisible = ref(false);
const currentCourseId = ref(1);

// el-upload 使用自己的上传通道，**不经过** axios 拦截器，因此必须手动补鉴权头
const uploadHeaders = computed(() => {
  const token = localStorage.getItem('satoken') || '';
  return { Authorization: `Bearer ${token}` };   // 头值必须带 Bearer 前缀
});

const fetchDocs = async () => {
  try {
    // request 的 baseURL 已是 /api，此处不要再写 /api 前缀
    docList.value = (await request.get('/teacher/docs/list', {
      params: { courseId: currentCourseId.value },
    })) as any;
  } catch {
    ElMessage.error('课件列表加载失败');
  }
};

const beforeUpload = (file: File) => {
  const isLt50M = file.size / 1024 / 1024 < 50;
  if (!isLt50M) {
    ElMessage.error('上传文件大小不能超过 50MB!');
  }
  return isLt50M;
};

const handleUploadSuccess = () => {
  ElMessage.success('上传成功，后台已启动向量切片索引！');
  uploadDialogVisible.value = false;
  fetchDocs();
};

/** 删除课件：后端会同步级联清除该课件在 Chroma 中的全部向量切片 */
const handleDelete = async (id: number) => {
  try {
    await ElMessageBox.confirm('删除后该课件的向量切片会被同步清除，确定删除？', '确认删除', {
      type: 'warning',
    });
  } catch {
    return; // 用户取消
  }
  try {
    await request.delete(`/teacher/docs/${id}`);
    ElMessage.success('课件已删除');
    fetchDocs();
  } catch {
    ElMessage.error('删除失败');
  }
};

onMounted(fetchDocs);
</script>
```

---

### 4.2 问答记录查看 (`QaRecordList.vue`)

**功能范围（重要）**：教师按课程查看学生提问明细，支持关键词筛选、分页，并可展开查看该条问答命中的课件出处。
**本页是只读的**——不提供修改 AI 回答的功能（人工纠偏不在本期需求范围内，数据库也无对应字段）。

```vue
<template>
  <div class="p-4">
    <!-- 筛选区 -->
    <div class="flex items-center gap-3 mb-4">
      <el-select v-model="courseId" placeholder="选择课程" style="width: 220px" @change="search">
        <el-option v-for="c in courses" :key="c.id" :label="c.courseName" :value="c.id" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="按提问或回答关键词搜索"
        style="width: 280px"
        clearable
        @keyup.enter="search"
      />
      <el-button type="primary" @click="search">查询</el-button>
    </div>

    <!-- 记录表格 -->
    <el-table :data="records" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="question" label="学生提问" min-width="200" show-overflow-tooltip />
      <el-table-column label="AI 回答" min-width="260">
        <template #default="{ row }">
          <span class="text-slate-600">{{ row.answer }}</span>
        </template>
      </el-table-column>
      <el-table-column label="参考出处" width="120">
        <template #default="{ row }">
          <el-button link type="primary" @click="showRefs(row)">
            查看 ({{ parseRefs(row.groundingReferences).length }})
          </el-button>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="提问时间" width="170" />
      <el-table-column label="学生反馈" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.feedbackRating === 1" type="success" size="small">点赞</el-tag>
          <el-tag v-else-if="row.feedbackRating === -1" type="danger" size="small">点踩</el-tag>
          <span v-else class="text-slate-400">—</span>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="mt-4 justify-end"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="pageSize"
      v-model:current-page="pageNum"
      @current-change="fetchRecords"
    />

    <!-- 参考出处抽屉（只读展示） -->
    <el-drawer v-model="refsVisible" title="参考出处" size="420px">
      <div v-for="(r, i) in currentRefs" :key="i" class="mb-3 p-3 bg-slate-50 rounded text-xs">
        <div class="font-bold text-slate-800">{{ r.fileName }} · 第 {{ r.chunkIndex }} 段</div>
        <div class="text-slate-500 mt-1">相关度 {{ r.score }}</div>
        <p class="text-slate-700 mt-2">{{ r.snippet }}</p>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
// 必须用统一封装实例，它会自动带上 Authorization: Bearer <token> 头；直接用裸 axios 会 401
import request from '@/utils/request';
import { ElMessage } from 'element-plus';

interface SseReference {
  docId: number;
  fileName: string;
  chunkIndex: number;
  score: number;
  snippet: string;
}

interface QaRecordRow {
  id: number;
  question: string;
  answer: string;
  groundingReferences?: SseReference[] | string;  // 可能是 JSON 字符串，统一解析
  feedbackRating?: number;
  createdAt: string;
}

const courses = ref<any[]>([]);
const courseId = ref<number>();
const keyword = ref('');
const records = ref<QaRecordRow[]>([]);
const total = ref(0);
const pageNum = ref(1);
const pageSize = ref(10);
const loading = ref(false);

const refsVisible = ref(false);
const currentRefs = ref<SseReference[]>([]);

/** groundingReferences 可能是 JSON 字符串（数据库 JSON 字段），统一解析为数组 */
const parseRefs = (raw: QaRecordRow['groundingReferences']): SseReference[] => {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw;
  try {
    return JSON.parse(raw);
  } catch {
    return [];
  }
};

const showRefs = (row: QaRecordRow) => {
  currentRefs.value = parseRefs(row.groundingReferences);
  refsVisible.value = true;
};

const fetchCourses = async () => {
  const res = await request.get('/course/list');
  courses.value = res as any;
  if (courses.value.length) {
    courseId.value = courses.value[0].id;
    fetchRecords();
  }
};

const fetchRecords = async () => {
  if (!courseId.value) return;
  loading.value = true;
  try {
    const res: any = await request.get('/teacher/qa/records', {
      params: {
        courseId: courseId.value,
        pageNum: pageNum.value,
        pageSize: pageSize.value,
        keyword: keyword.value,
      },
    });
    records.value = res.records;
    total.value = res.total;
  } catch {
    ElMessage.error('问答记录加载失败');
  } finally {
    loading.value = false;
  }
};

const search = () => {
  pageNum.value = 1;
  fetchRecords();
};

onMounted(fetchCourses);
</script>
```

**验收标准**：
- 切换课程后表格内容随之变化，不串课；
- 关键词能同时匹配到提问内容与回答内容；
- "参考出处"能展开看到课件名、分块序号与相关度；
- 学生点赞/点踩后，本页"学生反馈"列显示对应标签。

---

## 五、 协同契约与交付物清单

### 5.1 对接配合要求
1. **对接成员 B（后端业务）**：
   - 联调 `/api/teacher/docs/upload`（上传）、`/api/teacher/docs/list`（列表）、`/api/teacher/docs/{id}`（删除）、`/api/teacher/docs/{id}/reindex`（重建索引）。
   - 联调 `/api/teacher/qa/records` 问答记录查询接口（问答记录模块唯一的数据来源）。
2. **对接成员 A（AI 算法）**：
   - 课件上传后由 A 的切块服务异步处理，D 端只负责轮询状态机（`PENDING`→`PARSING`→`CHUNKED`→`FAILED`），**不需要切块内部细节**。
3. **牵头项目总结与答辩物料**：
   - 汇总 A、B、C、D 四人的核心成果，编写《课程设计总结报告》。
   - 制作包含架构图、RAG 核心流程图、前后端功能演练截图的答辩 PPT。

### 5.2 成员 D 验收与交付物自测表
- [ ] 拖拽上传 PDF 课件后，状态从"切片中"平滑过渡到"已就绪"，无需手动频繁刷新。
- [ ] 课件删除后列表不再显示该课件，且学生端检索不到它的内容。
- [ ] 问答记录页能按课程筛选、按关键词搜索、翻页，并能展开查看参考出处。
- [ ] 问答记录页**没有任何修改 AI 回答的入口**（本模块为只读，越权功能不得出现）。
- [ ] 全链路端到端测试完成，输出缺陷清单并推动修复闭环。
- [ ] 答辩材料齐全：PPT 框架清晰、4 人分工贡献一目了然。

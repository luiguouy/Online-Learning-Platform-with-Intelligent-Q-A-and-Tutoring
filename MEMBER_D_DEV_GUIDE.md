# 成员 D 详细开发文档：教师管理后台与学情可视化工程师

> **角色**：成员 D（教师后台与可视化工程师 · 前端工程师 · 答辩统筹）  
> **职责模块**：教师端管理后台、课件拖拽上传与切块状态监控、问答明细审计与人工纠偏、ECharts 学情统计看板、答辩演练与工程文档统筹  
> **适用技术栈**：Vue 3 + Vite + TypeScript + Element Plus + ECharts 5.x + Axios + Pinia

---

## 一、 模块定位与工程职责边界

成员 D 是教师角色的**“管理控制中心与学情洞察专家”**，同时兼任小组的**“交付与答辩质量总监”**：

1. **教师后台管理框架**：采用经典左侧菜单 + 顶部面包屑 + 主工作台布局，划分“课件知识库管理”、“问答记录与纠偏”、“学情数据大屏”三大子系统。
2. **课件文件管理与分块状态监控**：实现拖拽上传课件（PDF/MD/TXT），支持限制大小（<=50MB），实时轮询或展示切块状态机（排队中 -> 切片中 -> 已就绪 -> 失败），支持在线查看切块数量与片段明细。
3. **问答记录审计与人工干预（纠偏）**：提供多维度问答审计表格，支持筛选“被学生点踩（负反馈）”的回答；内置人工纠偏弹窗，教师可直接覆写 AI 的不严谨回答并录入修正答案。
4. **学情看板与数据可视化 (ECharts)**：开发直观的可视化看板，包含：近 7 天学生提问趋势折线图、高频提问热点词云、课程知识点掌握率雷达图。
5. **项目验收与答辩物料统筹**：牵头整合团队的最终代码仓库、编撰系统使用说明书、准备答辩 PPT 与现场演示脚本。

---

## 二、 前端依赖配置 (`package.json`)

```json
{
  "dependencies": {
    "vue": "^3.4.21",
    "vue-router": "^4.3.0",
    "element-plus": "^2.6.1",
    "@element-plus/icons-vue": "^2.3.1",
    "echarts": "^5.5.0",
    "echarts-wordcloud": "^2.1.0",
    "axios": "^1.6.8"
  },
  "devDependencies": {
    "typescript": "^5.2.2",
    "vite": "^5.1.6"
  }
}
```

> ⚠️ **不要安装 `@types/echarts`**：`echarts` 5.x 已自带完整 TypeScript 类型定义，再装 4.x 时代的 `@types/echarts` 会造成类型冲突（`echarts.init()` 等签名不匹配），`vue-tsc --noEmit` 直接报红、CI 卡死。
> 💡 `echarts-wordcloud` 仅在画词云时才用到；3 周工期下若只做折线图 + 饼图，可删掉此依赖减少安装体积。

---

## 三、 模块目录结构

```text
src/views/teacher/
├── TeacherLayout.vue          // 教师端通用导航与面包屑外框
├── CourseDocManage.vue        // 课件上传、分块切片状态管理
├── QaAuditCorrection.vue      // 问答历史审计与人工纠偏工作台
├── AnalyticsDashboard.vue     // ECharts 学情分析数据大屏
└── components/
    ├── DocUploadModal.vue     // 课件拖拽上传弹窗
    ├── CorrectionDialog.vue   // 教师人工纠偏弹窗
    └── TrendChart.vue         // 7日提问趋势折线图组件
```

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
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="previewChunks(row)">查看片段</el-button>
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
        :headers="{ Authorization: `Bearer ${token}` }"
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
import { ref, onMounted } from 'vue';
import { Upload, UploadFilled } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import axios from 'axios';

const docList = ref([]);
const uploadDialogVisible = ref(false);
const currentCourseId = ref(1);
const token = localStorage.getItem('satoken') || ''; // 键名统一为 satoken，全团队一致

const fetchDocs = async () => {
  const res = await axios.get(`/api/teacher/docs/list?courseId=${currentCourseId.value}`);
  if (res.data.code === 200) {
    docList.value = res.data.data;
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

onMounted(fetchDocs);
</script>
```

### 4.2 问答记录审计与人工纠偏弹窗 (`CorrectionDialog.vue`)
```vue
<template>
  <el-dialog
    v-model="visible"
    title="问答记录人工纠偏 (教师覆盖标准答案)"
    width="680px"
  >
    <div v-if="record" class="space-y-4 text-xs">
      <div class="bg-slate-50 p-3 rounded border border-slate-200">
        <span class="font-bold text-slate-700 block mb-1">学生提问：</span>
        <p class="text-slate-900">{{ record.question }}</p>
      </div>

      <div class="bg-amber-50/50 p-3 rounded border border-amber-200">
        <span class="font-bold text-amber-800 block mb-1">AI 原始回答 (待纠偏)：</span>
        <p class="text-slate-700 max-h-32 overflow-y-auto whitespace-pre-wrap">{{ record.answer }}</p>
      </div>

      <div class="space-y-2">
        <label class="font-bold text-slate-800 block">教师标准修正答案 (Markdown 格式)：</label>
        <el-input
          v-model="form.correctedAnswer"
          type="textarea"
          :rows="5"
          placeholder="请输入修正后的严谨答案，保存后学生端将优先展示此内容..."
        />
      </div>

      <div class="space-y-1">
        <label class="font-semibold text-slate-700 block">教师指导评语：</label>
        <el-input v-model="form.teacherComment" placeholder="如：已核实课件第4章第2节定义，原回答混淆了物理地址与虚拟地址" />
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submitCorrection">
        保存并更新知识库
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue';
import { ElMessage } from 'element-plus';
import axios from 'axios';

const visible = ref(false);
const submitting = ref(false);
const record = ref<any>(null);

const form = reactive({
  correctedAnswer: '',
  teacherComment: ''
});

const emit = defineEmits(['saved']);

const open = (row: any) => {
  record.value = row;
  form.correctedAnswer = row.correctedAnswer || row.answer;
  form.teacherComment = row.teacherComment || '';
  visible.value = true;
};

const submitCorrection = async () => {
  if (!form.correctedAnswer.trim()) {
    return ElMessage.warning('修正答案不能为空');
  }
  submitting.value = true;
  try {
    const res = await axios.post('/api/teacher/qa/correct', {
      recordId: record.value.id,
      correctedAnswer: form.correctedAnswer,
      teacherComment: form.teacherComment
    });
    if (res.data.code === 200) {
      ElMessage.success('人工纠偏成功！');
      visible.value = false;
      emit('saved');
    }
  } finally {
    submitting.value = false;
  }
};

defineExpose({ open });
</script>
```

### 4.3 ECharts 学情可视化看板 (`AnalyticsDashboard.vue`)
```vue
<template>
  <div class="p-6 space-y-6">
    <div class="grid grid-cols-1 md:grid-cols-3 gap-5">
      <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-2xs">
        <span class="text-xs text-slate-500 font-medium">累计学生提问总数</span>
        <div class="text-2xl font-bold text-slate-900 mt-2">1,248 <span class="text-xs text-emerald-600 font-normal">次</span></div>
      </div>
      <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-2xs">
        <span class="text-xs text-slate-500 font-medium">RAG 知识库课件切块数</span>
        <div class="text-2xl font-bold text-indigo-600 mt-2">342 <span class="text-xs text-slate-500 font-normal">个片段</span></div>
      </div>
      <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-2xs">
        <span class="text-xs text-slate-500 font-medium">教师人工纠偏率</span>
        <div class="text-2xl font-bold text-amber-600 mt-2">3.2% <span class="text-xs text-slate-400 font-normal">(40 条纠偏)</span></div>
      </div>
    </div>

    <!-- 趋势图 -->
    <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-2xs">
      <h3 class="text-sm font-bold text-slate-800 mb-4">近 7 天学生提问频次与活跃趋势</h3>
      <div ref="chartRef" class="w-full h-72"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import * as echarts from 'echarts';

const chartRef = ref<HTMLDivElement | null>(null);

onMounted(() => {
  if (chartRef.value) {
    const chart = echarts.init(chartRef.value);
    chart.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
      },
      yAxis: { type: 'value', name: '提问人次' },
      series: [
        {
          name: '智能答疑调用',
          type: 'line',
          smooth: true,
          data: [120, 182, 191, 234, 290, 330, 310],
          areaStyle: { color: 'rgba(79, 70, 229, 0.15)' },
          itemStyle: { color: '#4F46E5' }
        }
      ]
    });
  }
});
</script>
```

---

## 五、 协同契约与交付物清单

### 5.1 对接配合要求
1. **对接成员 B（后端业务）**：
   - 联调 `/api/teacher/docs/upload` 课件上传与列表分页接口。
   - 联调 `/api/teacher/qa/correct` 人工纠偏接口。
2. **对接成员 A（AI 算法）**：
   - 获取课件切块后的分块详情数据，在抽屉中展示切块字符长度与相似度分布。
3. **牵头项目总结与答辩物料**：
   - 汇总 A、B、C、D 四人的核心成果，编写《课程设计总结报告》。
   - 制作包含架构图、RAG 核心流程图、前后端功能演练截图的答辩 PPT。

### 5.2 成员 D 验收与交付物自测表
- [ ] 拖拽上传 PDF 课件后，状态从“切片中”平滑过渡到“已就绪”，无需手动频繁刷新。
- [ ] 人工纠偏提交成功后，列表立即反映纠偏状态，且重新查询该条问答已显示最新修正文本。
- [ ] ECharts 图表在窗口大小改变（resize）时能够自适应缩放，无布局错乱。
- [ ] 答辩材料齐全：PPT 框架清晰、演示录屏无死机卡顿、4人分工贡献一目了然。

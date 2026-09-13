/**
 * Mock 数据：课程列表 + 课件列表（Week 1 前端自测用）
 *
 * 背景：成员 B 的课件上传接口 B2.1 截止 Day 9，Week 1 前端无法真实联调，
 * 因此按 MEMBER_D_DEV_GUIDE.md 4.1 给的字段结构在本地模拟一份数据，
 * 用于验证 D1.3「课件管理页表格渲染」与 D1.4「上传进度交互」。
 * D2.1 对接真实接口后删除本文件。
 */
import type { Course, CourseDoc, ParseStatus } from '@/types';

/** 与 AGENT_INSTRUCTIONS.md 3.1 种子数据保持一致的两门示范课程 */
export const mockCourses: Course[] = [
  {
    id: 1,
    courseName: '计算机操作系统原理与实践',
    courseCode: 'CS202401',
    teacherId: 1,
    description: '涵盖进程调度、虚拟内存置换、死锁预防与文件系统核心机制。',
  },
  {
    id: 2,
    courseName: '计算机网络与高并发通信',
    courseCode: 'CS202402',
    teacherId: 1,
    description: '涵盖 TCP/IP 体系结构、拥塞控制算法、DNS 解析与网络安全协议。',
  },
];

/** 模拟切块耗时：6 秒后 PARSING → CHUNKED，用于观察 3 秒轮询的状态流转 */
const MOCK_PARSE_DURATION_MS = 6000;

const docStore: Record<number, CourseDoc[]> = {
  1: [
    {
      id: 1,
      fileName: '第3章 内存管理.pdf',
      fileType: 'pdf',
      chunkCount: 46,
      parseStatus: 'CHUNKED',
      createdAt: '2026-09-08 10:12:30',
    },
    {
      id: 2,
      fileName: '第4章 进程调度与死锁.md',
      fileType: 'md',
      chunkCount: 0,
      parseStatus: 'PARSING',
      createdAt: '2026-09-09 15:40:02',
    },
    {
      id: 3,
      fileName: '实验指导书-死锁检测.txt',
      fileType: 'txt',
      chunkCount: 0,
      parseStatus: 'FAILED',
      createdAt: '2026-09-07 09:02:11',
      errorMsg: '文档解析失败：未提取到有效文本，疑似扫描版文件',
    },
  ],
  2: [
    {
      id: 4,
      fileName: '第2章 应用层协议.pdf',
      fileType: 'pdf',
      chunkCount: 38,
      parseStatus: 'CHUNKED',
      createdAt: '2026-09-06 14:22:45',
    },
  ],
};

/** 记录每个 PARSING 课件的起始时间：首次查询时懒启动计时，之后自动流转为 CHUNKED */
const parseStartedAt = new Map<number, number>();

let idSeq = 100;

function pad(value: number): string {
  return value.toString().padStart(2, '0');
}

function nowText(): string {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}:${pad(d.getSeconds())}`;
}

/** 推进一次状态机：PARSING 超过模拟耗时后变为 CHUNKED */
function tick(): void {
  const now = Date.now();
  Object.values(docStore).forEach((list) => {
    list.forEach((doc) => {
      if (doc.parseStatus !== 'PARSING') return;
      const startedAt = parseStartedAt.get(doc.id);
      if (startedAt === undefined) {
        parseStartedAt.set(doc.id, now);
        return;
      }
      if (now - startedAt >= MOCK_PARSE_DURATION_MS) {
        doc.parseStatus = 'CHUNKED';
        doc.chunkCount = 20 + Math.floor(Math.random() * 30);
        parseStartedAt.delete(doc.id);
      }
    });
  });
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

/** 查询指定课程的课件列表 */
export function mockListDocs(courseId: number): CourseDoc[] {
  tick();
  return clone(docStore[courseId] ?? []);
}

/** 新增课件：落库后直接进入 PARSING（与 DEV_SPECIFICATION.md 4.2 的实际语义一致） */
export function mockCreateDoc(courseId: number, fileName: string): CourseDoc {
  const ext = fileName.includes('.') ? fileName.split('.').pop()!.toLowerCase() : '';
  const doc: CourseDoc = {
    id: ++idSeq,
    fileName,
    fileType: ext,
    chunkCount: 0,
    parseStatus: 'PARSING' as ParseStatus,
    createdAt: nowText(),
  };
  if (!docStore[courseId]) {
    docStore[courseId] = [];
  }
  docStore[courseId].unshift(doc);
  parseStartedAt.set(doc.id, Date.now());
  return clone(doc);
}

/** 删除课件：真实接口会级联清除该课件在 Chroma 中的全部向量切片 */
export function mockRemoveDoc(id: number): void {
  Object.keys(docStore).forEach((key) => {
    const courseId = Number(key);
    const index = docStore[courseId].findIndex((doc) => doc.id === id);
    if (index >= 0) {
      docStore[courseId].splice(index, 1);
    }
  });
  parseStartedAt.delete(id);
}

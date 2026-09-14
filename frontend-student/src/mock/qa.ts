/**
 * Mock 数据：会话与问答记录（Week 1 前端自测用，C1.5 主布局渲染历史会话栏）
 *
 * 字段名与真实接口一致；groundingReferences 覆盖三种情况以提前验证兼容性
 * （有值 / 空数组 / null —— B 回复确认单 Q10 指出历史数据可能为 null）。
 * 对接真实接口后删除本文件。
 */
import type { QaRecord, QaSession } from '@/types';

const SESSIONS: Record<number, QaSession[]> = {
  1: [
    { id: 7, title: '虚拟内存分页机制是怎么工作的', createdAt: '2026-09-12T20:31:05' },
    { id: 6, title: '进程调度算法有哪些区别', createdAt: '2026-09-11T19:02:41' },
    { id: 5, title: '死锁的四个必要条件', createdAt: '2026-09-10T21:15:18' },
  ],
  2: [{ id: 4, title: 'TCP 三次握手为什么不是两次', createdAt: '2026-09-09T10:22:33' }],
};

const RECORDS: Record<number, QaRecord[]> = {
  7: [
    {
      id: 1024,
      question: '虚拟内存分页机制是怎么工作的？',
      answer:
        '分页式存储管理把进程的虚拟地址空间切成固定大小的**页**，把物理内存切成同样大小的**页框**，' +
        '再通过页表记录「虚页号 → 物理页框号」的映射。\n\n' +
        '1. CPU 发出虚拟地址，拆成虚页号与页内偏移；\n' +
        '2. 查页表得到物理页框号，拼上页内偏移得到物理地址；\n' +
        '3. 页不在内存时触发缺页中断，由操作系统按置换算法调入。',
      groundingReferences: [
        {
          docId: 12,
          fileName: '第3章 内存管理.pdf',
          chunkIndex: 14,
          score: 0.88,
          snippet: '虚拟内存分页机制中，页表存储了虚页号与物理页框号的映射关系……',
        },
        {
          docId: 12,
          fileName: '第3章 内存管理.pdf',
          chunkIndex: 15,
          score: 0.81,
          snippet: '当访问的页不在内存时触发缺页中断，由页面置换算法决定淘汰哪一页……',
        },
      ],
      feedbackRating: 1,
      createdAt: '2026-09-12T20:31:05',
    },
    {
      id: 1023,
      question: '快表（TLB）解决了什么问题？',
      answer: '快表是页表项的高速缓存，把「两次访存」降为「一次访存」，缓解页表查询带来的性能损失。',
      // 空数组：课件未命中但成功返回
      groundingReferences: [],
      feedbackRating: null,
      createdAt: '2026-09-12T20:35:12',
    },
  ],
  6: [
    {
      id: 1018,
      question: '时间片轮转和优先级调度有什么区别？',
      answer: '时间片轮转保证公平性，优先级调度偏向紧急任务；实际系统常用多级反馈队列把两者结合。',
      // null：历史数据兼容路径
      groundingReferences: null,
      feedbackRating: -1,
      createdAt: '2026-09-11T19:02:41',
    },
  ],
  5: [],
  4: [
    {
      id: 1009,
      question: 'TCP 三次握手为什么不是两次？',
      answer: '两次握手无法确认客户端的接收能力，且历史连接请求会造成服务端资源浪费，三次才能双向确认收发能力。',
      groundingReferences: [
        {
          docId: 21,
          fileName: '第2章 应用层协议与拥塞控制.pdf',
          chunkIndex: 6,
          score: 0.92,
          snippet: '三次握手的本质是双方各自确认「自己能发、对方能收」……',
        },
      ],
      feedbackRating: null,
      createdAt: '2026-09-09T10:22:33',
    },
  ],
};

export function mockListSessions(courseId: number): QaSession[] {
  return (SESSIONS[courseId] ?? []).map((item) => ({ ...item }));
}

export function mockListRecords(sessionId: number): QaRecord[] {
  return (RECORDS[sessionId] ?? []).map((item) => ({ ...item }));
}

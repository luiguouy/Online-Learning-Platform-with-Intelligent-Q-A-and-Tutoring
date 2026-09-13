/**
 * Mock 数据：课程列表（Week 1 前端自测用）
 *
 * 数据取自 AGENT_INSTRUCTIONS.md 3.1 的种子课程，字段名与真实接口一致（courseName）。
 * 对接真实接口后删除本文件。
 */
import type { Course } from '@/types';

const COURSES: Course[] = [
  {
    id: 1,
    courseName: '计算机操作系统原理与实践',
    courseCode: 'CS202401',
    teacherId: 1,
    description: '涵盖进程调度、虚拟内存置换、死锁预防与文件系统核心机制。',
    coverImage: '',
  },
  {
    id: 2,
    courseName: '计算机网络与高并发通信',
    courseCode: 'CS202402',
    teacherId: 1,
    description: '涵盖 TCP/IP 体系结构、拥塞控制算法、DNS 解析与网络安全协议。',
    coverImage: '',
  },
];

export function mockListCourses(): Course[] {
  return COURSES.map((item) => ({ ...item }));
}

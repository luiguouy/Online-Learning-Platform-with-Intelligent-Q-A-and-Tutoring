/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState } from 'react';
import { 
  ShieldAlert, 
  ShieldCheck, 
  AlertOctagon, 
  Copy, 
  Download, 
  CheckCircle, 
  FileText,
  HelpCircle,
  Zap,
  ArrowRight,
  Flame,
  Bug
} from 'lucide-react';
import { adversarialAuditMarkdown } from '../data/auditContent';

interface AuditReportTabProps {
  onDownload: (content: string, filename: string) => void;
  onPreviewFull: () => void;
}

export const AuditReportTab: React.FC<AuditReportTabProps> = ({ onDownload, onPreviewFull }) => {
  const [copied, setCopied] = useState(false);

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <div className="space-y-6">
      {/* 顶部报告卡片 */}
      <div className="bg-gradient-to-r from-red-950 via-slate-900 to-amber-950 text-white rounded-xl p-6 shadow-md border border-red-900/30 flex flex-col md:flex-row items-start md:items-center justify-between gap-5">
        <div className="space-y-2 max-w-2xl">
          <div className="flex items-center space-x-2">
            <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-red-500/20 text-red-300 border border-red-500/30 flex items-center gap-1">
              <ShieldAlert className="w-3.5 h-3.5" />
              红蓝对抗性技术审查报告 (ADVERSARIAL_AUDIT_REPORT.md)
            </span>
            <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-500/20 text-emerald-300 border border-emerald-500/30">
              5 大死锁已出补丁
            </span>
          </div>
          <h2 className="text-xl font-bold tracking-tight text-white flex items-center gap-2">
            跨模块协同冲突对抗审查与答辩防翻车指南
          </h2>
          <p className="text-xs text-slate-300 leading-relaxed">
            模拟严苛测试与资深评审视角，全面排查 4 人协作中<b>“鉴权冲突导致 401”、“SSE 缺少 recordId 导致评价断裂”、“教师纠偏改了白改”、“Chroma 幽灵切块”</b>等 5 大死锁，并附带评委现场提问抗辩预案。
          </p>
        </div>

        <div className="flex items-center space-x-2 shrink-0">
          <button
            onClick={() => copyToClipboard(adversarialAuditMarkdown)}
            className="px-3.5 py-2 text-xs font-medium bg-white/10 hover:bg-white/20 text-white rounded-lg border border-white/20 flex items-center gap-1.5 transition-colors"
          >
            <Copy className="w-3.5 h-3.5" />
            {copied ? '已复制报告！' : '复制审查报告'}
          </button>
          <button
            onClick={() => onDownload(adversarialAuditMarkdown, 'ADVERSARIAL_AUDIT_REPORT.md')}
            className="px-4 py-2 text-xs font-semibold bg-red-600 hover:bg-red-700 text-white rounded-lg shadow-sm flex items-center gap-1.5 transition-colors"
          >
            <Download className="w-3.5 h-3.5" />
            下载 ADVERSARIAL_AUDIT_REPORT.md
          </button>
        </div>
      </div>

      {/* 5 大跨模块致命死锁卡片 */}
      <div className="space-y-3">
        <h3 className="text-sm font-bold text-slate-900 flex items-center gap-1.5">
          <Bug className="w-4 h-4 text-red-600" />
          5 大跨成员协同接口死锁与修复补丁
        </h3>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          <div className="bg-white p-4 rounded-xl border border-slate-200 hover:border-red-300 transition-colors shadow-2xs space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold text-red-600">隐患 1：鉴权 Header 冲突</span>
              <span className="text-[10px] bg-red-50 text-red-700 px-1.5 py-0.5 rounded font-medium">联调 401 瘫痪</span>
            </div>
            <p className="text-xs text-slate-600 leading-relaxed">
              <b>成员 B (Sa-Token)</b> 默认读 <code className="bg-slate-100 text-slate-800 px-1 font-mono text-[11px]">satoken: xxx</code>，而 <b>成员 C</b> 的 fetchEventSource 携带标准 <code className="bg-slate-100 text-slate-800 px-1 font-mono text-[11px]">Authorization: Bearer xxx</code>。
            </p>
            <div className="pt-2 border-t border-slate-100 text-xs text-emerald-700 font-medium">
              ✅ 修复：配置 <code className="bg-emerald-50 px-1 py-0.5 rounded font-mono text-[11px]">token-prefix: Bearer</code> 允许自动解析。
            </div>
          </div>

          <div className="bg-white p-4 rounded-xl border border-slate-200 hover:border-red-300 transition-colors shadow-2xs space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold text-red-600">隐患 2：SSE 漏传 recordId</span>
              <span className="text-[10px] bg-red-50 text-red-700 px-1.5 py-0.5 rounded font-medium">评价链路报废</span>
            </div>
            <p className="text-xs text-slate-600 leading-relaxed">
              <b>成员 A</b> 在 done 事件中只推了 sessionId，但 <b>成员 C</b> 点击“点赞/点踩”需要具体问答记录的 recordId。
            </p>
            <div className="pt-2 border-t border-slate-100 text-xs text-emerald-700 font-medium">
              ✅ 修复：done 包下发前调用持久化，下发 <code className="bg-emerald-50 px-1 py-0.5 rounded font-mono text-[11px]">recordId</code>。
            </div>
          </div>

          <div className="bg-white p-4 rounded-xl border border-slate-200 hover:border-red-300 transition-colors shadow-2xs space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold text-red-600">隐患 3：纠偏数据假闭环</span>
              <span className="text-[10px] bg-red-50 text-red-700 px-1.5 py-0.5 rounded font-medium">改了白改</span>
            </div>
            <p className="text-xs text-slate-600 leading-relaxed">
              <b>成员 D</b> 在教师后台修正了答案写入 MySQL，但 <b>成员 A</b> 的 RAG 服务只搜 Chroma 课件切块，学生再次提问依然答错。
            </p>
            <div className="pt-2 border-t border-slate-100 text-xs text-emerald-700 font-medium">
              ✅ 修复：RAG 双路检索，优先直搜教师纠偏标准库。
            </div>
          </div>

          <div className="bg-white p-4 rounded-xl border border-slate-200 hover:border-red-300 transition-colors shadow-2xs space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold text-red-600">隐患 4：文档删除幽灵切块</span>
              <span className="text-[10px] bg-red-50 text-red-700 px-1.5 py-0.5 rounded font-medium">废弃课件脏数据</span>
            </div>
            <p className="text-xs text-slate-600 leading-relaxed">
              MySQL 中删除了课件，但 Chroma 向量库未级联删除。提问时依然召回废弃切块并显示出处抽屉。
            </p>
            <div className="pt-2 border-t border-slate-100 text-xs text-emerald-700 font-medium">
              ✅ 修复：删除课件时按 <code className="bg-emerald-50 px-1 py-0.5 rounded font-mono text-[11px]">docId</code> 级联清理向量切块。
            </div>
          </div>

          <div className="bg-white p-4 rounded-xl border border-slate-200 hover:border-red-300 transition-colors shadow-2xs space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold text-red-600">隐患 5：线程池并发耗尽</span>
              <span className="text-[10px] bg-red-50 text-red-700 px-1.5 py-0.5 rounded font-medium">演示现场卡死</span>
            </div>
            <p className="text-xs text-slate-600 leading-relaxed">
              默认使用 <code className="bg-slate-100 text-slate-800 px-1 font-mono text-[11px]">commonPool</code>，多名同学同时发问时流式生成持续占有线程，后续请求阻塞冻结。
            </p>
            <div className="pt-2 border-t border-slate-100 text-xs text-emerald-700 font-medium">
              ✅ 修复：显式声明 <code className="bg-emerald-50 px-1 py-0.5 rounded font-mono text-[11px]">sseExecutor</code> 独立异步线程池。
            </div>
          </div>

          <div className="bg-indigo-50/50 p-4 rounded-xl border border-indigo-100 flex flex-col justify-between">
            <div>
              <span className="text-xs font-bold text-indigo-900 block mb-1">查阅完整对抗性审计分析</span>
              <p className="text-xs text-indigo-700 leading-relaxed">
                包含 MySQL JSON 实体类注解、文件相对路径上传丢失、大课件 Tika OOM 防范等系统级缺陷。
              </p>
            </div>
            <button
              onClick={onPreviewFull}
              className="mt-3 text-xs font-semibold text-indigo-600 hover:text-indigo-800 flex items-center gap-1"
            >
              查看审查报告完整全文 <ArrowRight className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      </div>

      {/* 答辩现场评委高频发难与抗辩话术 */}
      <div className="bg-white rounded-xl border border-slate-200 p-5 shadow-2xs">
        <div className="flex items-center space-x-2 mb-4">
          <HelpCircle className="w-5 h-5 text-indigo-600" />
          <h3 className="font-bold text-sm text-slate-900">
            答辩现场评委刁钻连环追问与抗辩预案（防挂科高分指南）
          </h3>
        </div>

        <div className="space-y-3">
          <div className="p-3.5 rounded-lg border border-slate-200 bg-slate-50/40">
            <div className="text-xs font-bold text-slate-900 mb-1 flex items-center gap-1.5">
              <span className="w-4 h-4 rounded-full bg-red-100 text-red-700 text-[10px] flex items-center justify-center font-bold">Q1</span>
              大模型有严重幻觉，课件里没有的内容它胡说八道怎么办？
            </div>
            <p className="text-xs text-slate-600 pl-5">
              <b>高分抗辩</b>：“我们在系统层设计了三道防线：第一道是<b>元数据隔离检索</b>（Cosine 相似度严格限定 &gt;= 0.70，低分片段直接丢弃）；第二道是<b>强约束系统提示词</b>，硬性规定未检索到时首句必须回答‘课件未提及’；第三道是<b>教师人工纠偏机制</b>，对负反馈问题进行覆盖纠偏。”
            </p>
          </div>

          <div className="p-3.5 rounded-lg border border-slate-200 bg-slate-50/40">
            <div className="text-xs font-bold text-slate-900 mb-1 flex items-center gap-1.5">
              <span className="w-4 h-4 rounded-full bg-red-100 text-red-700 text-[10px] flex items-center justify-center font-bold">Q2</span>
              如果两个老师上传了同名课件，切块怎么隔离？学生提问怎么防止串课？
            </div>
            <p className="text-xs text-slate-600 pl-5">
              <b>高分抗辩</b>：“我们在 Chroma 存储切块时，强制注入了租户级复合元数据：<code className="text-indigo-600 font-mono">courseId + docId + chunkIndex</code>。检索时在底层执行了强制的 Filter 条件过滤，彻底杜绝了不同课程间的跨域串流。”
            </p>
          </div>

          <div className="p-3.5 rounded-lg border border-slate-200 bg-slate-50/40">
            <div className="text-xs font-bold text-slate-900 mb-1 flex items-center gap-1.5">
              <span className="w-4 h-4 rounded-full bg-red-100 text-red-700 text-[10px] flex items-center justify-center font-bold">Q3</span>
              学生中途关闭网页或切换了题目，后端大模型还在消耗 Token 怎么处理？
            </div>
            <p className="text-xs text-slate-600 pl-5">
              <b>高分抗辩</b>：“我们在前端封装了基于 <code className="text-indigo-600 font-mono">AbortController</code> 的断流机制，一旦页面销毁或切换立即 abort()；后端 SseEmitter 监听了 onCompletion 和 onTimeout 回调，检测到客户端断开连接立即中断调用，防止算力与 Token 浪费。”
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState } from 'react';
import { 
  Bot, 
  Terminal, 
  AlertTriangle, 
  CheckCircle2, 
  Copy, 
  Download, 
  Code2, 
  Layers, 
  Cpu, 
  Database,
  Sparkles,
  Zap,
  ArrowRight
} from 'lucide-react';
import { agentInstructionsMarkdown } from '../data/auditContent';

interface AgentGuideTabProps {
  onDownload: (content: string, filename: string) => void;
  onPreviewFull: () => void;
}

export const AgentGuideTab: React.FC<AgentGuideTabProps> = ({ onDownload, onPreviewFull }) => {
  const [copied, setCopied] = useState(false);

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <div className="space-y-6">
      {/* 顶部行动卡片 */}
      <div className="bg-gradient-to-r from-indigo-900 via-slate-900 to-purple-950 text-white rounded-xl p-6 shadow-md border border-slate-800 flex flex-col md:flex-row items-start md:items-center justify-between gap-5">
        <div className="space-y-2 max-w-2xl">
          <div className="flex items-center space-x-2">
            <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-indigo-500/30 text-indigo-300 border border-indigo-400/30 flex items-center gap-1">
              <Bot className="w-3.5 h-3.5" />
              面向 Cursor / Windsurf / Claude Code / Qwen-Code
            </span>
            <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-500/30 text-emerald-300 border border-emerald-400/30">
              单体零废话约束
            </span>
          </div>
          <h2 className="text-xl font-bold tracking-tight text-white flex items-center gap-2">
            AI Agent 自动化编程执行指令手册 (AGENT_INSTRUCTIONS.md)
          </h2>
          <p className="text-xs text-slate-300 leading-relaxed">
            专为解决 AI Agent 编程时的<b>“过度设计、擅自分拆微服务、乱加依赖、返回格式不统一”</b>等痛点定制。将此文件置于项目根目录，Agent 会将其作为最高权限 System Prompt 自动执行。
          </p>
        </div>

        <div className="flex items-center space-x-2 shrink-0">
          <button
            onClick={() => copyToClipboard(agentInstructionsMarkdown)}
            className="px-3.5 py-2 text-xs font-medium bg-white/10 hover:bg-white/20 text-white rounded-lg border border-white/20 flex items-center gap-1.5 transition-colors"
          >
            <Copy className="w-3.5 h-3.5" />
            {copied ? '已复制手册！' : '复制 Agent 指令'}
          </button>
          <button
            onClick={() => onDownload(agentInstructionsMarkdown, 'AGENT_INSTRUCTIONS.md')}
            className="px-4 py-2 text-xs font-semibold bg-indigo-500 hover:bg-indigo-600 text-white rounded-lg shadow-sm flex items-center gap-1.5 transition-colors"
          >
            <Download className="w-3.5 h-3.5" />
            下载 AGENT_INSTRUCTIONS.md
          </button>
        </div>
      </div>

      {/* 核心禁令与设计红线 */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-white p-5 rounded-xl border border-red-100 shadow-2xs">
          <div className="flex items-center space-x-2 text-red-600 mb-2">
            <AlertTriangle className="w-4 h-4" />
            <h3 className="font-bold text-sm text-slate-900">禁令 1：严禁拆分微服务</h3>
          </div>
          <p className="text-xs text-slate-600 leading-relaxed">
            严禁引入 Spring Cloud、Nacos、Eureka、Feign。必须严格采用单体 Spring Boot 3.x，避免因分布式 RPC、注册中心配置导致本科生项目无法在本地一键运行。
          </p>
        </div>

        <div className="bg-white p-5 rounded-xl border border-amber-100 shadow-2xs">
          <div className="flex items-center space-x-2 text-amber-600 mb-2">
            <AlertTriangle className="w-4 h-4" />
            <h3 className="font-bold text-sm text-slate-900">禁令 2：严禁空桩代码</h3>
          </div>
          <p className="text-xs text-slate-600 leading-relaxed">
            严禁在 Service 中写 <code className="bg-slate-100 text-slate-800 px-1 py-0.5 rounded font-mono text-[11px]">return null;</code> 或 <code className="bg-slate-100 text-slate-800 px-1 py-0.5 rounded font-mono text-[11px]">// TODO</code>。所有持久化必须连接 MyBatis-Plus，所有 RAG 切块必须真实写入 Chroma。
          </p>
        </div>

        <div className="bg-white p-5 rounded-xl border border-indigo-100 shadow-2xs">
          <div className="flex items-center space-x-2 text-indigo-600 mb-2">
            <Zap className="w-4 h-4" />
            <h3 className="font-bold text-sm text-slate-900">禁令 3：接口协议红线</h3>
          </div>
          <p className="text-xs text-slate-600 leading-relaxed">
            普通 RESTful 接口必须返回统一 <code className="bg-slate-100 text-slate-800 px-1 py-0.5 rounded font-mono text-[11px]">Result&lt;T&gt;</code>；智能答疑必须输出标准 SSE 流，仅允许 <code className="bg-slate-100 text-slate-800 px-1 py-0.5 rounded font-mono text-[11px]">references / message / done / error</code> 四类事件。
          </p>
        </div>
      </div>

      {/* 两大实战卡片：后端开箱配置 + 前端防卡死节流 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        {/* 卡片 1: 后端完整 application.yml 约束模板 */}
        <div className="bg-white rounded-xl border border-slate-200 shadow-2xs overflow-hidden flex flex-col">
          <div className="px-5 py-3.5 bg-slate-50 border-b border-slate-200 flex items-center justify-between">
            <div className="flex items-center space-x-2">
              <Database className="w-4 h-4 text-indigo-600" />
              <span className="font-bold text-xs text-slate-800">
                后端 Agent 统一 application.yml 核心配置项
              </span>
            </div>
            <span className="text-[11px] font-mono px-2 py-0.5 bg-indigo-50 text-indigo-700 rounded">
              YAML 约束
            </span>
          </div>
          <div className="p-4 bg-slate-900 text-slate-200 text-xs font-mono overflow-x-auto flex-1">
            <pre className="leading-relaxed">
{`# 关键安全与连接配置
server:
  port: 8080

sa-token:
  token-name: satoken
  is-read-header: true
  token-prefix: Bearer # 关键修复：兼容 Authorization 请求头

rag:
  llm:
    model-name: qwen-plus
    temperature: 0.2
  chroma:
    base-url: http://localhost:8000
    collection-name: smart_qa_course_docs
  chunk:
    size: 400
    overlap: 50
    similarity-threshold: 0.70`}
            </pre>
          </div>
          <div className="p-3 bg-slate-50 border-t border-slate-100 text-xs text-slate-500">
            内置环境变量注入：<code className="text-indigo-600 font-semibold">{`\${AI_API_KEY}`}</code>、<code className="text-indigo-600 font-semibold">{`\${MYSQL_PASSWORD}`}</code>。
          </div>
        </div>

        {/* 卡片 2: 前端 Agent 渲染节流规范 */}
        <div className="bg-white rounded-xl border border-slate-200 shadow-2xs overflow-hidden flex flex-col">
          <div className="px-5 py-3.5 bg-slate-50 border-b border-slate-200 flex items-center justify-between">
            <div className="flex items-center space-x-2">
              <Code2 className="w-4 h-4 text-emerald-600" />
              <span className="font-bold text-xs text-slate-800">
                前端 Agent 流式渲染节流器 (防浏览器卡死)
              </span>
            </div>
            <span className="text-[11px] font-mono px-2 py-0.5 bg-emerald-50 text-emerald-700 rounded">
              Vue 3 + DOM
            </span>
          </div>
          <div className="p-4 bg-slate-900 text-slate-200 text-xs font-mono overflow-x-auto flex-1">
            <pre className="leading-relaxed">
{`// 避免大模型密集吐 token 触发高频 DOM 重绘卡死
let tokenBuffer = '';
let renderTimer: number | null = null;

const appendTokenWithThrottle = (token: string) => {
  tokenBuffer += token;
  if (!renderTimer) {
    renderTimer = window.setTimeout(() => {
      currentAiMessage.value.content += tokenBuffer;
      tokenBuffer = '';
      renderTimer = null;
      scrollToBottom();
    }, 60); // 60ms 周期批量更新
  }
};`}
            </pre>
          </div>
          <div className="p-3 bg-slate-50 border-t border-slate-100 text-xs text-slate-500">
            解决长文答复时，浏览器主线程被全量 markdown 解析与代码高亮占满的问题。
          </div>
        </div>
      </div>

      {/* Agent 准入自测检查清单 */}
      <div className="bg-white rounded-xl border border-slate-200 p-5 shadow-2xs">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center space-x-2">
            <CheckCircle2 className="w-5 h-5 text-emerald-600" />
            <h3 className="font-bold text-sm text-slate-900">
              AI Agent 任务交付准入验证清单 (Gatekeeper Checklist)
            </h3>
          </div>
          <button
            onClick={onPreviewFull}
            className="text-xs text-indigo-600 hover:text-indigo-800 font-medium flex items-center gap-1"
          >
            查看完整 AGENT_INSTRUCTIONS.md <ArrowRight className="w-3.5 h-3.5" />
          </button>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
          <div className="p-3 rounded-lg border border-slate-200 bg-slate-50/50">
            <span className="text-xs font-semibold text-slate-900 block mb-1">1. 编译构建测试</span>
            <p className="text-xs text-slate-500">
              <code className="text-indigo-600 font-mono">mvn clean package</code> 成功打包且无任何未解决的类加载或循环依赖。
            </p>
          </div>

          <div className="p-3 rounded-lg border border-slate-200 bg-slate-50/50">
            <span className="text-xs font-semibold text-slate-900 block mb-1">2. 前端类型测试</span>
            <p className="text-xs text-slate-500">
              <code className="text-indigo-600 font-mono">npm run build</code> 零 TypeScript 类型报错，组件 Prop 契约完好。
            </p>
          </div>

          <div className="p-3 rounded-lg border border-slate-200 bg-slate-50/50">
            <span className="text-xs font-semibold text-slate-900 block mb-1">3. SSE 推流测试</span>
            <p className="text-xs text-slate-500">
              浏览器或 Postman 访问 stream 接口，依次按序输出 <code className="text-indigo-600 font-mono">references -&gt; message -&gt; done</code>。
            </p>
          </div>

          <div className="p-3 rounded-lg border border-slate-200 bg-slate-50/50">
            <span className="text-xs font-semibold text-slate-900 block mb-1">4. 双路纠偏闭环测试</span>
            <p className="text-xs text-slate-500">
              教师后台纠偏某道题目后，学生再次提问，确认系统优先命中并下发教师修正的权威答案。
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

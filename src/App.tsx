/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useMemo } from 'react';
import { 
  Download, 
  Copy, 
  Check, 
  Users, 
  FileText, 
  Calendar, 
  Network, 
  Edit3, 
  ExternalLink,
  BookOpen,
  Sparkles,
  ShieldCheck,
  Code2,
  FileCheck2,
  GitBranch,
  Terminal,
  Cpu,
  Layers,
  Database,
  Lock,
  ArrowRight,
  FolderDown,
  ChevronRight,
  UserCheck,
  CheckCircle2,
  Bot,
  ShieldAlert
} from 'lucide-react';
import { defaultMembers, generateMarkdownDoc, MemberInfo } from './data/divisionContent';
import { specSections, devSpecificationMarkdown } from './data/specContent';
import { memberGuides, MemberGuide } from './data/memberGuidesData';
import { adversarialAuditMarkdown, agentInstructionsMarkdown } from './data/auditContent';
import { AgentGuideTab } from './components/AgentGuideTab';
import { AuditReportTab } from './components/AuditReportTab';

export default function App() {
  const [members, setMembers] = useState<MemberInfo[]>(defaultMembers);
  const [selectedMemberId, setSelectedMemberId] = useState<'A' | 'B' | 'C' | 'D'>('A');
  const [activeTab, setActiveTab] = useState<'member-guides' | 'spec-cards' | 'division-cards' | 'agent-guide' | 'audit-report' | 'timeline' | 'api' | 'preview'>('member-guides');
  const [previewDocType, setPreviewDocType] = useState<'member' | 'spec' | 'division' | 'agent' | 'audit'>('member');
  const [copied, setCopied] = useState(false);
  const [downloaded, setDownloaded] = useState<string | null>(null);
  const [isEditingNames, setIsEditingNames] = useState(false);

  // 动态生成的分工 Markdown 内容
  const divisionMarkdown = useMemo(() => {
    return generateMarkdownDoc(members);
  }, [members]);

  const currentMemberGuide = memberGuides[selectedMemberId];

  // 计算当前预览的 Markdown 文本
  const currentMarkdown = useMemo(() => {
    if (previewDocType === 'member') {
      return currentMemberGuide.markdownContent;
    } else if (previewDocType === 'spec') {
      return devSpecificationMarkdown;
    } else if (previewDocType === 'division') {
      return divisionMarkdown;
    } else if (previewDocType === 'agent') {
      return agentInstructionsMarkdown;
    } else {
      return adversarialAuditMarkdown;
    }
  }, [previewDocType, currentMemberGuide, divisionMarkdown]);

  const currentFileName = useMemo(() => {
    if (previewDocType === 'member') {
      return currentMemberGuide.fileName;
    } else if (previewDocType === 'spec') {
      return 'AI答疑辅导平台_团队全栈开发规范守则.md';
    } else if (previewDocType === 'division') {
      return 'AI答疑辅导平台_4人小组详细分工文档.md';
    } else if (previewDocType === 'agent') {
      return 'AGENT_INSTRUCTIONS.md';
    } else {
      return 'ADVERSARIAL_AUDIT_REPORT.md';
    }
  }, [previewDocType, currentMemberGuide]);

  // 修改组员名称
  const handleNameChange = (id: string, newName: string) => {
    setMembers((prev) =>
      prev.map((m) => (m.id === id ? { ...m, name: newName || `成员 ${id}` } : m))
    );
  };

  // 下载特定 Markdown 文件
  const downloadFile = (content: string, filename: string) => {
    const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);

    setDownloaded(filename);
    setTimeout(() => setDownloaded(null), 2500);
  };

  // 一键下载全部 8 份核心文档
  const downloadAllDocs = () => {
    const docs = [
      { name: 'AGENT_INSTRUCTIONS.md', content: agentInstructionsMarkdown },
      { name: 'ADVERSARIAL_AUDIT_REPORT.md', content: adversarialAuditMarkdown },
      { name: 'MEMBER_A_DEV_GUIDE.md', content: memberGuides.A.markdownContent },
      { name: 'MEMBER_B_DEV_GUIDE.md', content: memberGuides.B.markdownContent },
      { name: 'MEMBER_C_DEV_GUIDE.md', content: memberGuides.C.markdownContent },
      { name: 'MEMBER_D_DEV_GUIDE.md', content: memberGuides.D.markdownContent },
      { name: 'AI答疑辅导平台_团队全栈开发规范守则.md', content: devSpecificationMarkdown },
      { name: 'AI答疑辅导平台_4人小组详细分工文档.md', content: divisionMarkdown },
    ];

    docs.forEach((doc, idx) => {
      setTimeout(() => {
        downloadFile(doc.content, doc.name);
      }, idx * 250);
    });
  };

  // 复制 Markdown 源码
  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <div className="min-h-screen bg-slate-50 text-slate-800 flex flex-col font-sans selection:bg-indigo-100 selection:text-indigo-800">
      {/* 顶部通栏导航 */}
      <header className="sticky top-0 z-30 bg-white/95 backdrop-blur-md border-b border-slate-200 shadow-xs">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 rounded-lg bg-indigo-600 flex items-center justify-center text-white shadow-xs">
              <BookOpen className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center space-x-2">
                <h1 className="text-base font-semibold text-slate-900 leading-tight">
                  AI 驱动的在线学习智能答疑辅导平台
                </h1>
                <span className="hidden sm:inline-flex text-[11px] font-medium px-2 py-0.5 rounded bg-indigo-50 text-indigo-700 border border-indigo-100">
                  RAG 课程知识库
                </span>
              </div>
              <p className="text-xs text-slate-500">
                4人本科生团队 · 成员专属开发文档 · 全栈开发规范与交付中心
              </p>
            </div>
          </div>

          {/* 右侧核心下载与复制行动条 */}
          <div className="flex items-center space-x-2 sm:space-x-3">
            <button
              id="btn-copy-current"
              onClick={() => handleCopy(currentMarkdown)}
              className="inline-flex items-center px-3 py-2 text-xs font-medium rounded-lg text-slate-700 bg-white border border-slate-300 hover:bg-slate-50 transition-colors cursor-pointer"
              title="复制当前 Markdown"
            >
              {copied ? (
                <>
                  <Check className="w-4 h-4 text-emerald-600 mr-1.5" />
                  <span className="text-emerald-700">已复制</span>
                </>
              ) : (
                <>
                  <Copy className="w-4 h-4 text-slate-500 mr-1.5" />
                  <span>复制 Markdown</span>
                </>
              )}
            </button>

            <button
              id="btn-download-all"
              onClick={downloadAllDocs}
              className="inline-flex items-center px-3.5 py-2 text-xs font-medium rounded-lg text-white bg-indigo-600 hover:bg-indigo-700 shadow-sm transition-all cursor-pointer hover:shadow"
              title="一次性连续下载全部 8 份 Markdown 项目工程文档"
            >
              <FolderDown className="w-4 h-4 mr-1.5" />
              <span>下载全部 8 份文档 (.md)</span>
            </button>
          </div>
        </div>
      </header>

      {/* 主体容器 */}
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-6">
        
        {/* 顶部总览看板 */}
        <section id="section-project-overview" className="bg-white rounded-xl border border-slate-200 p-5 shadow-2xs">
          <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
            <div className="space-y-2 max-w-3xl">
              <div className="flex flex-wrap items-center gap-2">
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-indigo-50 text-indigo-700 border border-indigo-100">
                  4 位成员专属详细开发文档
                </span>
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-purple-50 text-purple-700 border border-purple-100">
                  AI Agent 自动化编程手册
                </span>
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-50 text-red-700 border border-red-100">
                  对抗性技术审查 (5大死锁补丁)
                </span>
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-emerald-50 text-emerald-700 border border-emerald-100">
                  统一全栈工程规范
                </span>
              </div>
              <h2 className="text-xl font-bold text-slate-900 tracking-tight">
                AI 驱动的在线学习智能答疑辅导平台 · 全套开发实战指南与交付系统
              </h2>
              <p className="text-sm text-slate-600 leading-relaxed">
                针对 4 人本科生团队与 <b>AI Agent 自动化编程</b> 进行全链路深度优化，提供<b>4 份成员详细指南、1 份全栈规范、1 份分工计划、1 份 Agent 执行手册以及 1 份红蓝对抗性技术审查报告</b>。杜绝接口歧义、消灭协同死锁，一键导出交付！
              </p>
            </div>

            {/* 右侧快速下载操作区 */}
            <div className="flex flex-col items-start lg:items-end gap-2 shrink-0">
              <div className="flex flex-wrap gap-1.5">
                {(['A', 'B', 'C', 'D'] as const).map((id) => (
                  <button
                    key={id}
                    onClick={() => downloadFile(memberGuides[id].markdownContent, memberGuides[id].fileName)}
                    className="text-xs text-indigo-700 hover:text-indigo-900 bg-indigo-50 hover:bg-indigo-100 px-2.5 py-1 rounded border border-indigo-200/80 flex items-center transition-colors"
                  >
                    <Download className="w-3 h-3 mr-1" />
                    成员 {id} 文档
                  </button>
                ))}
              </div>

              <div className="flex items-center space-x-3 text-xs text-slate-500 pt-1">
                <button
                  id="btn-toggle-edit-names"
                  onClick={() => setIsEditingNames(!isEditingNames)}
                  className="hover:text-indigo-600 flex items-center transition-colors"
                >
                  <Edit3 className="w-3 h-3 mr-1" />
                  {isEditingNames ? '收起姓名编辑' : '填写组员真实姓名'}
                </button>
                <span>·</span>
                <a
                  href="/DEV_SPECIFICATION.md"
                  download="AI答疑辅导平台_团队全栈开发规范守则.md"
                  className="hover:text-indigo-600 flex items-center transition-colors"
                >
                  <FileCheck2 className="w-3 h-3 mr-1" />
                  全栈开发规范
                </a>
              </div>
            </div>
          </div>

          {/* 组员姓名编辑抽屉 */}
          {isEditingNames && (
            <div className="mt-4 pt-4 border-t border-slate-100 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 bg-slate-50/70 p-3 rounded-lg">
              {members.map((m) => (
                <div key={m.id} className="space-y-1">
                  <label className="text-xs font-medium text-slate-600 block truncate">
                    {m.id} 角色 ({m.tag})
                  </label>
                  <input
                    type="text"
                    value={m.name}
                    onChange={(e) => handleNameChange(m.id, e.target.value)}
                    placeholder={`成员 ${m.id} 姓名`}
                    className="w-full text-xs px-2.5 py-1.5 bg-white border border-slate-300 rounded focus:outline-none focus:ring-1 focus:ring-indigo-500 focus:border-indigo-500 text-slate-800"
                  />
                </div>
              ))}
            </div>
          )}
        </section>

        {/* 标签栏导航 */}
        <div className="flex items-center justify-between border-b border-slate-200 overflow-x-auto">
          <nav className="flex space-x-1 sm:space-x-4 min-w-max">
            <button
              id="tab-member-guides"
              onClick={() => setActiveTab('member-guides')}
              className={`py-3 px-2.5 sm:px-3 text-xs sm:text-sm font-medium border-b-2 flex items-center space-x-1.5 transition-all cursor-pointer ${
                activeTab === 'member-guides'
                  ? 'border-indigo-600 text-indigo-600 font-semibold'
                  : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
              }`}
            >
              <UserCheck className="w-4 h-4" />
              <span>4 人专属详细开发文档</span>
            </button>

            <button
              id="tab-agent-guide"
              onClick={() => setActiveTab('agent-guide')}
              className={`py-3 px-2.5 sm:px-3 text-xs sm:text-sm font-medium border-b-2 flex items-center space-x-1.5 transition-all cursor-pointer ${
                activeTab === 'agent-guide'
                  ? 'border-indigo-600 text-indigo-600 font-semibold bg-indigo-50/40'
                  : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
              }`}
            >
              <Bot className="w-4 h-4 text-indigo-600" />
              <span className="flex items-center gap-1">
                <span>AI Agent 执行手册</span>
                <span className="px-1.5 py-0.2 rounded text-[10px] bg-indigo-100 text-indigo-700 font-bold">必看</span>
              </span>
            </button>

            <button
              id="tab-audit-report"
              onClick={() => setActiveTab('audit-report')}
              className={`py-3 px-2.5 sm:px-3 text-xs sm:text-sm font-medium border-b-2 flex items-center space-x-1.5 transition-all cursor-pointer ${
                activeTab === 'audit-report'
                  ? 'border-red-600 text-red-600 font-semibold bg-red-50/40'
                  : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
              }`}
            >
              <ShieldAlert className="w-4 h-4 text-red-600" />
              <span className="flex items-center gap-1">
                <span>对抗性审查与防翻车</span>
                <span className="px-1.5 py-0.2 rounded text-[10px] bg-red-100 text-red-700 font-bold">5大死锁</span>
              </span>
            </button>

            <button
              id="tab-spec-cards"
              onClick={() => setActiveTab('spec-cards')}
              className={`py-3 px-2.5 sm:px-3 text-xs sm:text-sm font-medium border-b-2 flex items-center space-x-1.5 transition-all cursor-pointer ${
                activeTab === 'spec-cards'
                  ? 'border-indigo-600 text-indigo-600 font-semibold'
                  : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
              }`}
            >
              <FileCheck2 className="w-4 h-4" />
              <span>开发规范 6 大准则</span>
            </button>

            <button
              id="tab-division-cards"
              onClick={() => setActiveTab('division-cards')}
              className={`py-3 px-2.5 sm:px-3 text-xs sm:text-sm font-medium border-b-2 flex items-center space-x-1.5 transition-all cursor-pointer ${
                activeTab === 'division-cards'
                  ? 'border-indigo-600 text-indigo-600 font-semibold'
                  : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
              }`}
            >
              <Users className="w-4 h-4" />
              <span>4 人分工矩阵</span>
            </button>

            <button
              id="tab-timeline"
              onClick={() => setActiveTab('timeline')}
              className={`py-3 px-2.5 sm:px-3 text-xs sm:text-sm font-medium border-b-2 flex items-center space-x-1.5 transition-all cursor-pointer ${
                activeTab === 'timeline'
                  ? 'border-indigo-600 text-indigo-600 font-semibold'
                  : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
              }`}
            >
              <Calendar className="w-4 h-4" />
              <span>5 周进度</span>
            </button>

            <button
              id="tab-api"
              onClick={() => setActiveTab('api')}
              className={`py-3 px-2.5 sm:px-3 text-xs sm:text-sm font-medium border-b-2 flex items-center space-x-1.5 transition-all cursor-pointer ${
                activeTab === 'api'
                  ? 'border-indigo-600 text-indigo-600 font-semibold'
                  : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
              }`}
            >
              <Network className="w-4 h-4" />
              <span>接口协同契约</span>
            </button>

            <button
              id="tab-preview"
              onClick={() => setActiveTab('preview')}
              className={`py-3 px-2.5 sm:px-3 text-xs sm:text-sm font-medium border-b-2 flex items-center space-x-1.5 transition-all cursor-pointer ${
                activeTab === 'preview'
                  ? 'border-indigo-600 text-indigo-600 font-semibold'
                  : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
              }`}
            >
              <FileText className="w-4 h-4" />
              <span>文档源码预览</span>
            </button>
          </nav>
        </div>

        {/* 核心视图 1：4 人专属详细开发文档 */}
        {activeTab === 'member-guides' && (
          <div className="space-y-6">
            {/* 4 位成员切换选卡 */}
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
              {(['A', 'B', 'C', 'D'] as const).map((id) => {
                const guide = memberGuides[id];
                const isSelected = selectedMemberId === id;
                return (
                  <button
                    key={id}
                    id={`btn-select-member-${id}`}
                    onClick={() => setSelectedMemberId(id)}
                    className={`p-4 rounded-xl border text-left transition-all cursor-pointer ${
                      isSelected
                        ? 'bg-indigo-50/70 border-indigo-600 shadow-xs ring-1 ring-indigo-600/30'
                        : 'bg-white border-slate-200 hover:border-slate-300 hover:bg-slate-50/50'
                    }`}
                  >
                    <div className="flex items-center justify-between mb-2">
                      <span className={`w-6 h-6 rounded-md font-bold text-xs flex items-center justify-center ${
                        isSelected ? 'bg-indigo-600 text-white' : 'bg-slate-100 text-slate-600'
                      }`}>
                        {id}
                      </span>
                      <span className="text-[11px] font-medium text-slate-500">
                        {guide.badge}
                      </span>
                    </div>
                    <div className="font-bold text-sm text-slate-900 truncate">
                      {guide.name}
                    </div>
                    <div className="text-xs text-indigo-600 font-medium truncate mt-0.5">
                      {guide.roleTitle}
                    </div>
                  </button>
                );
              })}
            </div>

            {/* 选中成员详细指南卡片 */}
            <div className="bg-white rounded-xl border border-slate-200 shadow-2xs overflow-hidden">
              <div className="p-5 border-b border-slate-100 bg-slate-50/50 flex flex-col md:flex-row md:items-center justify-between gap-4">
                <div>
                  <div className="flex items-center space-x-2">
                    <span className="w-7 h-7 rounded-lg bg-indigo-600 text-white font-bold text-sm flex items-center justify-center">
                      {currentMemberGuide.id}
                    </span>
                    <h3 className="text-base font-bold text-slate-900">
                      {currentMemberGuide.name} · {currentMemberGuide.roleTitle}
                    </h3>
                  </div>
                  <p className="text-xs text-slate-500 mt-1">
                    {currentMemberGuide.tagline}
                  </p>
                </div>

                <div className="flex items-center space-x-2 shrink-0">
                  <button
                    onClick={() => handleCopy(currentMemberGuide.markdownContent)}
                    className="inline-flex items-center px-3 py-1.5 text-xs font-medium rounded-lg text-slate-700 bg-white border border-slate-300 hover:bg-slate-50 transition-colors"
                  >
                    <Copy className="w-3.5 h-3.5 mr-1" />
                    {copied ? '已复制！' : '复制开发文档'}
                  </button>
                  <button
                    onClick={() => downloadFile(currentMemberGuide.markdownContent, currentMemberGuide.fileName)}
                    className="inline-flex items-center px-3.5 py-1.5 text-xs font-medium rounded-lg text-white bg-indigo-600 hover:bg-indigo-700 shadow-xs transition-colors"
                  >
                    <Download className="w-3.5 h-3.5 mr-1" />
                    下载此文档 ({currentMemberGuide.fileName})
                  </button>
                </div>
              </div>

              {/* 核心看点与技术栈标签 */}
              <div className="p-5 border-b border-slate-100 bg-white">
                <div className="flex flex-col md:flex-row md:items-center justify-between gap-3">
                  <div>
                    <h4 className="text-xs font-semibold text-slate-700 mb-2">
                      专属技术栈与环境：
                    </h4>
                    <div className="flex flex-wrap gap-1.5">
                      {currentMemberGuide.techStack.map((tech, idx) => (
                        <span key={idx} className="text-xs px-2.5 py-0.5 bg-slate-100 text-slate-700 rounded-md font-mono border border-slate-200/60">
                          {tech}
                        </span>
                      ))}
                    </div>
                  </div>

                  <a
                    href={currentMemberGuide.downloadUrl}
                    download={currentMemberGuide.fileName}
                    className="text-xs text-indigo-600 hover:text-indigo-800 flex items-center gap-1 shrink-0"
                  >
                    <ExternalLink className="w-3.5 h-3.5" />
                    备用下载链接 ({currentMemberGuide.fileName})
                  </a>
                </div>

                <div className="mt-4 pt-4 border-t border-slate-100">
                  <h4 className="text-xs font-semibold text-slate-800 mb-2 flex items-center gap-1.5">
                    <CheckCircle2 className="w-4 h-4 text-emerald-600" />
                    本成员核心攻坚要点概览：
                  </h4>
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                    {currentMemberGuide.summaryHighlights.map((highlight, idx) => (
                      <div key={idx} className="text-xs text-slate-600 flex items-start gap-1.5">
                        <span className="text-indigo-500 font-bold">•</span>
                        <span>{highlight}</span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>

              {/* 文档正文查看器 */}
              <div className="p-5 bg-slate-50/40">
                <div className="flex items-center justify-between mb-3">
                  <span className="text-xs font-semibold text-slate-700 flex items-center gap-1.5">
                    <Code2 className="w-4 h-4 text-indigo-600" />
                    开发文档全文预览（含代码实现规范与自测清单）：
                  </span>
                  <span className="text-[11px] text-slate-400">
                    可全选复制或直接下载
                  </span>
                </div>
                <pre className="text-xs font-mono text-slate-800 leading-relaxed whitespace-pre-wrap select-all bg-white p-4 rounded-lg border border-slate-200 max-h-[500px] overflow-y-auto">
                  {currentMemberGuide.markdownContent}
                </pre>
              </div>
            </div>
          </div>
        )}

        {/* 视图 2：开发规范 6 大准则卡片 */}
        {activeTab === 'spec-cards' && (
          <div className="space-y-4">
            <div className="flex items-center justify-between bg-indigo-50/50 border border-indigo-100 rounded-lg p-3">
              <div className="flex items-center space-x-2">
                <Sparkles className="w-4 h-4 text-indigo-600" />
                <span className="text-xs font-semibold text-indigo-900">
                  全栈统一工程标准：MVC 4层分层、Vue3组合式API、SSE流式打字机协议与RAG防幻觉调优
                </span>
              </div>
              <button
                onClick={() => downloadFile(devSpecificationMarkdown, 'AI答疑辅导平台_团队全栈开发规范守则.md')}
                className="text-xs font-medium text-white bg-indigo-600 hover:bg-indigo-700 px-3 py-1.5 rounded-md flex items-center gap-1 shadow-xs transition-colors"
              >
                <Download className="w-3.5 h-3.5" />
                下载完整规范 (.md)
              </button>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
              {specSections.map((sec, idx) => (
                <div
                  key={sec.id}
                  id={`card-spec-${sec.id}`}
                  className="bg-white rounded-xl border border-slate-200 p-5 flex flex-col justify-between hover:shadow-md transition-shadow"
                >
                  <div className="space-y-3">
                    <div className="flex items-center justify-between">
                      <span className="w-7 h-7 rounded-md bg-indigo-50 text-indigo-700 font-bold text-xs flex items-center justify-center border border-indigo-100">
                        0{idx + 1}
                      </span>
                      <span className="text-[11px] px-2 py-0.5 rounded-full bg-slate-100 text-slate-600 font-medium">
                        {sec.category}
                      </span>
                    </div>

                    <div>
                      <h3 className="text-base font-bold text-slate-900 flex items-center gap-1.5">
                        {sec.id === 'git' && <GitBranch className="w-4 h-4 text-indigo-600" />}
                        {sec.id === 'backend' && <Database className="w-4 h-4 text-indigo-600" />}
                        {sec.id === 'frontend' && <Layers className="w-4 h-4 text-indigo-600" />}
                        {sec.id === 'api' && <Network className="w-4 h-4 text-indigo-600" />}
                        {sec.id === 'rag' && <Cpu className="w-4 h-4 text-indigo-600" />}
                        {sec.id === 'env' && <Terminal className="w-4 h-4 text-indigo-600" />}
                        {sec.title}
                      </h3>
                      <p className="text-xs text-slate-500 mt-1 leading-relaxed">
                        {sec.summary}
                      </p>
                    </div>

                    <div className="border-t border-slate-100 pt-3 space-y-2">
                      <h4 className="text-xs font-semibold text-slate-700 flex items-center gap-1">
                        <Lock className="w-3.5 h-3.5 text-slate-400" />
                        核心执行守则：
                      </h4>
                      <ul className="space-y-1.5 text-xs text-slate-600">
                        {sec.keyPoints.map((point, pIdx) => (
                          <li key={pIdx} className="flex items-start gap-1.5">
                            <span className="text-indigo-400 font-bold mt-0.5">•</span>
                            <span className="leading-tight">{point}</span>
                          </li>
                        ))}
                      </ul>
                    </div>
                  </div>

                  <div className="mt-4 pt-3 border-t border-slate-100 flex items-center justify-end">
                    <button
                      onClick={() => {
                        setPreviewDocType('spec');
                        setActiveTab('preview');
                      }}
                      className="text-xs font-medium text-indigo-600 hover:text-indigo-800 flex items-center gap-1"
                    >
                      查看完整规范源码 <ArrowRight className="w-3 h-3" />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 视图 3：4人职责分工矩阵 */}
        {activeTab === 'division-cards' && (
          <div className="space-y-4">
            <div className="flex items-center justify-between bg-emerald-50/50 border border-emerald-100 rounded-lg p-3">
              <div className="flex items-center space-x-2">
                <Users className="w-4 h-4 text-emerald-600" />
                <span className="text-xs font-semibold text-emerald-900">
                  明确 2 后端 + 2 前端职责边界，任务精确到人，接口契约先行
                </span>
              </div>
              <button
                onClick={() => downloadFile(divisionMarkdown, 'AI答疑辅导平台_4人小组详细分工文档.md')}
                className="text-xs font-medium text-white bg-emerald-600 hover:bg-emerald-700 px-3 py-1.5 rounded-md flex items-center gap-1 shadow-xs transition-colors"
              >
                <Download className="w-3.5 h-3.5" />
                下载完整分工 (.md)
              </button>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-5">
              {members.map((member) => (
                <div
                  key={member.id}
                  id={`card-member-${member.id}`}
                  className="bg-white rounded-xl border border-slate-200 flex flex-col justify-between hover:shadow-md transition-shadow overflow-hidden"
                >
                  <div>
                    {/* 卡片头部 */}
                    <div className="p-4 border-b border-slate-100 bg-slate-50/50">
                      <div className="flex items-center justify-between mb-1.5">
                        <span className="w-7 h-7 rounded-md bg-indigo-100 text-indigo-700 font-bold text-xs flex items-center justify-center">
                          {member.id}
                        </span>
                        <span className="text-xs px-2 py-0.5 rounded-full bg-slate-200/70 text-slate-700 font-medium">
                          {member.tag}
                        </span>
                      </div>
                      <h3 className="text-sm font-bold text-slate-900 leading-snug">
                        {member.name}
                      </h3>
                      <p className="text-xs text-indigo-600 font-medium mt-0.5">
                        {member.role.replace(/成员 [A-D]（|）/g, '')}
                      </p>
                      <p className="text-xs text-slate-500 mt-2 line-clamp-2">
                        {member.focus}
                      </p>
                    </div>

                    {/* 具体任务清单 */}
                    <div className="p-4 space-y-3">
                      <div className="space-y-1.5">
                        <h4 className="text-xs font-semibold text-slate-700 flex items-center gap-1">
                          <Code2 className="w-3.5 h-3.5 text-indigo-500" />
                          核心开发任务
                        </h4>
                        <ul className="space-y-1.5 text-xs text-slate-600">
                          {member.tasks.map((task, idx) => (
                            <li key={idx} className="flex items-start gap-1.5">
                              <span className="text-indigo-400 font-mono mt-0.5">•</span>
                              <span className="leading-tight">{task}</span>
                            </li>
                          ))}
                        </ul>
                      </div>
                    </div>
                  </div>

                  {/* 底部关键交付物 */}
                  <div className="p-4 border-t border-slate-100 bg-slate-50/80 space-y-2">
                    <h4 className="text-xs font-semibold text-slate-800 mb-1.5 flex items-center gap-1">
                      <ShieldCheck className="w-3.5 h-3.5 text-emerald-600" />
                      期末关键交付物
                    </h4>
                    <ul className="space-y-1 text-xs text-slate-600">
                      {member.deliverables.map((item, idx) => (
                        <li key={idx} className="flex items-center gap-1.5 truncate">
                          <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 shrink-0"></span>
                          <span className="truncate" title={item}>{item}</span>
                        </li>
                      ))}
                    </ul>

                    <div className="pt-2 border-t border-slate-200/60">
                      <button
                        onClick={() => {
                          setSelectedMemberId(member.id as 'A' | 'B' | 'C' | 'D');
                          setActiveTab('member-guides');
                        }}
                        className="w-full py-1.5 text-xs font-medium text-indigo-700 bg-indigo-50 hover:bg-indigo-100 rounded-md transition-colors flex items-center justify-center gap-1"
                      >
                        查看详细开发文档 <ChevronRight className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 核心视图：AI Agent 执行指令清单与防坑实战手册 */}
        {activeTab === 'agent-guide' && (
          <AgentGuideTab
            onDownload={downloadFile}
            onPreviewFull={() => {
              setPreviewDocType('agent');
              setActiveTab('preview');
            }}
          />
        )}

        {/* 核心视图：红蓝对抗性审查报告 */}
        {activeTab === 'audit-report' && (
          <AuditReportTab
            onDownload={downloadFile}
            onPreviewFull={() => {
              setPreviewDocType('audit');
              setActiveTab('preview');
            }}
          />
        )}

        {/* 视图 4：5周进度排期 */}
        {activeTab === 'timeline' && (
          <div className="bg-white rounded-xl border border-slate-200 p-6 shadow-2xs space-y-6">
            <div className="border-b border-slate-100 pb-4 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
              <div>
                <h3 className="text-base font-bold text-slate-900">
                  项目 5 周敏捷迭代路线图 (Milestone Timeline)
                </h3>
                <p className="text-xs text-slate-500 mt-1">
                  以周为周期推进，严格执行前后端接口先冻结后实现的纪律，确保期末顺利交付。
                </p>
              </div>
              <span className="inline-flex items-center px-2.5 py-1 rounded text-xs font-medium bg-indigo-50 text-indigo-700">
                基准：第 3 周打通 RAG 最小闭环
              </span>
            </div>

            <div className="relative border-l-2 border-indigo-200 ml-4 space-y-8 pl-6">
              {/* 第 1 周 */}
              <div className="relative">
                <span className="absolute -left-[31px] top-0 w-4 h-4 rounded-full bg-indigo-600 border-4 border-white"></span>
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-bold px-2 py-0.5 rounded bg-indigo-100 text-indigo-700">
                      第 1 周
                    </span>
                    <h4 className="text-sm font-bold text-slate-900">
                      技术准备、架构定型与原型设计
                    </h4>
                  </div>
                  <p className="text-xs text-slate-600 leading-relaxed pt-1">
                    • <b>全体</b>：明确演示课程（推荐《操作系统》或《计算机网络》），搜集 3~5 篇典型 PDF 课件。<br />
                    • <b>成员 A</b>：搭建 Spring Boot 骨架，验证大模型 API 连通性与本地 Embedding 跑通。<br />
                    • <b>成员 B</b>：完成 MySQL 数据库表设计（用户、课程、课件元数据、会话、记录），编写建表 SQL。<br />
                    • <b>成员 C & D</b>：使用原型工具确定学生端流式对话布局和教师端后台界面线框图。
                  </p>
                </div>
              </div>

              {/* 第 2 周 */}
              <div className="relative">
                <span className="absolute -left-[31px] top-0 w-4 h-4 rounded-full bg-indigo-500 border-4 border-white"></span>
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-bold px-2 py-0.5 rounded bg-indigo-100 text-indigo-700">
                      第 2 周
                    </span>
                    <h4 className="text-sm font-bold text-slate-900">
                      骨架搭建与基础 CRUD 接口联调
                    </h4>
                  </div>
                  <p className="text-xs text-slate-600 leading-relaxed pt-1">
                    • <b>成员 A</b>：实现课件文本提取与分块切片算法（400字符分块 + 50字符重叠）。<br />
                    • <b>成员 B</b>：集成 Sa-Token 权限鉴权，实现用户登录与课程增删改查 REST 接口。<br />
                    • <b>成员 C</b>：搭建 Vue 3 + Element Plus 前台框架，实现课程切换与侧边会话导航。<br />
                    • <b>成员 D</b>：搭建后台管理框架，实现课程管理页面与表格组件。
                  </p>
                </div>
              </div>

              {/* 第 3 周 */}
              <div className="relative">
                <span className="absolute -left-[31px] top-0 w-4 h-4 rounded-full bg-amber-500 border-4 border-white"></span>
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-bold px-2 py-0.5 rounded bg-amber-100 text-amber-800">
                      第 3 周（核心攻坚周）
                    </span>
                    <h4 className="text-sm font-bold text-slate-900">
                      RAG 向量检索与智能答疑流式闭环
                    </h4>
                  </div>
                  <p className="text-xs text-slate-600 leading-relaxed pt-1">
                    • <b>成员 A</b>：完成向量库检索增强与 Prompt 上下文拼接，编写 SSE 流式接口 <code className="text-indigo-600 bg-slate-100 px-1">/api/qa/chat/stream</code>。<br />
                    • <b>成员 B</b>：实现文件异步上传状态机与问答明细持久化保存逻辑。<br />
                    • <b>成员 C</b>：对接 SSE 接口，实现打字机平滑流式吐字动画、Markdown 与代码高亮渲染。<br />
                    • <b>成员 D</b>：开发课件拖拽上传界面，实时展示分块进度条与索引状态。
                  </p>
                </div>
              </div>

              {/* 第 4 周 */}
              <div className="relative">
                <span className="absolute -left-[31px] top-0 w-4 h-4 rounded-full bg-indigo-500 border-4 border-white"></span>
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-bold px-2 py-0.5 rounded bg-indigo-100 text-indigo-700">
                      第 4 周
                    </span>
                    <h4 className="text-sm font-bold text-slate-900">
                      知识点解析、教师后台纠偏与交互完善
                    </h4>
                  </div>
                  <p className="text-xs text-slate-600 leading-relaxed pt-1">
                    • <b>成员 A</b>：编写考点精析与自测题 Prompt，实现知识点结构化生成接口。<br />
                    • <b>成员 B</b>：开发学情统计接口与教师纠偏保存接口。<br />
                    • <b>成员 C</b>：开发右侧参考出处溯源抽屉、知识点解析卡片与评价反馈功能。<br />
                    • <b>成员 D</b>：集成 ECharts 渲染学情看板，实现教师对不良回答的人工覆盖纠偏。
                  </p>
                </div>
              </div>

              {/* 第 5 周 */}
              <div className="relative">
                <span className="absolute -left-[31px] top-0 w-4 h-4 rounded-full bg-emerald-600 border-4 border-white"></span>
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-bold px-2 py-0.5 rounded bg-emerald-100 text-emerald-800">
                      第 5 周
                    </span>
                    <h4 className="text-sm font-bold text-slate-900">
                      端到端集成测试、文档封板与答辩演练
                    </h4>
                  </div>
                  <p className="text-xs text-slate-600 leading-relaxed pt-1">
                    • <b>全体</b>：进行全链路黑盒测试与边界压测（防大模型超时重试、防并发卡顿）。<br />
                    • <b>成员 C & D</b>：前端 UI 细节微调，添加骨架屏与空状态引导。<br />
                    • <b>成员 D 牵头</b>：整理最终软件工程设计说明书、使用手册及制作答辩演示 PPT。
                  </p>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* 视图 5：前后端接口协同矩阵 */}
        {activeTab === 'api' && (
          <div className="bg-white rounded-xl border border-slate-200 shadow-2xs overflow-hidden">
            <div className="p-4 border-b border-slate-100 bg-slate-50/70 flex flex-col sm:flex-row sm:items-center justify-between gap-2">
              <div>
                <h3 className="text-sm font-bold text-slate-900">
                  前后端与 AI 核心协同接口清单 (API Matrix)
                </h3>
                <p className="text-xs text-slate-500 mt-0.5">
                  严格采用 RESTful 语义化规范与 SSE 4 阶段事件流标准。
                </p>
              </div>
              <span className="text-xs font-medium text-slate-600 bg-white border border-slate-200 px-2 py-1 rounded">
                统一响应：<code className="text-indigo-600 font-mono">Result&lt;T&gt;</code>
              </span>
            </div>
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-slate-200 text-xs">
                <thead className="bg-slate-50">
                  <tr>
                    <th className="px-4 py-3 text-left font-semibold text-slate-600">接口功能</th>
                    <th className="px-4 py-3 text-left font-semibold text-slate-600">请求路径</th>
                    <th className="px-4 py-3 text-left font-semibold text-slate-600">方法</th>
                    <th className="px-4 py-3 text-left font-semibold text-slate-600">提供方 (后端)</th>
                    <th className="px-4 py-3 text-left font-semibold text-slate-600">调用方 (前端)</th>
                    <th className="px-4 py-3 text-left font-semibold text-slate-600">核心交互说明</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 bg-white">
                  <tr>
                    <td className="px-4 py-3 font-medium text-slate-900">用户登录鉴权</td>
                    <td className="px-4 py-3 font-mono text-slate-600">/api/auth/login</td>
                    <td className="px-4 py-3"><span className="px-1.5 py-0.5 rounded bg-blue-50 text-blue-700 font-semibold">POST</span></td>
                    <td className="px-4 py-3 text-slate-700">成员 B</td>
                    <td className="px-4 py-3 text-slate-700">成员 C & D</td>
                    <td className="px-4 py-3 text-slate-500">验证用户名密码，返回 Sa-Token 与角色身份</td>
                  </tr>
                  <tr>
                    <td className="px-4 py-3 font-medium text-slate-900">课程列表查询</td>
                    <td className="px-4 py-3 font-mono text-slate-600">/api/course/list</td>
                    <td className="px-4 py-3"><span className="px-1.5 py-0.5 rounded bg-emerald-50 text-emerald-700 font-semibold">GET</span></td>
                    <td className="px-4 py-3 text-slate-700">成员 B</td>
                    <td className="px-4 py-3 text-slate-700">成员 C & D</td>
                    <td className="px-4 py-3 text-slate-500">获取当前可选课程及课件知识库状态</td>
                  </tr>
                  <tr>
                    <td className="px-4 py-3 font-medium text-slate-900">课件上传切块</td>
                    <td className="px-4 py-3 font-mono text-slate-600">/api/teacher/docs/upload</td>
                    <td className="px-4 py-3"><span className="px-1.5 py-0.5 rounded bg-blue-50 text-blue-700 font-semibold">POST</span></td>
                    <td className="px-4 py-3 text-slate-700">成员 B & A</td>
                    <td className="px-4 py-3 text-slate-700">成员 D</td>
                    <td className="px-4 py-3 text-slate-500">上传课件文件，B存元数据，A触发异步切块分片</td>
                  </tr>
                  <tr>
                    <td className="px-4 py-3 font-medium text-slate-900">智能答疑 (核心SSE)</td>
                    <td className="px-4 py-3 font-mono text-indigo-600 font-semibold">/api/qa/chat/stream</td>
                    <td className="px-4 py-3"><span className="px-1.5 py-0.5 rounded bg-purple-50 text-purple-700 font-semibold">GET (SSE)</span></td>
                    <td className="px-4 py-3 text-slate-700 font-medium">成员 A</td>
                    <td className="px-4 py-3 text-slate-700 font-medium">成员 C</td>
                    <td className="px-4 py-3 text-slate-500">
                      4个Event：references(出处) -&gt; message(吐字) -&gt; done(完成) -&gt; error
                    </td>
                  </tr>
                  <tr>
                    <td className="px-4 py-3 font-medium text-slate-900">历史问答会话</td>
                    <td className="px-4 py-3 font-mono text-slate-600">/api/qa/sessions</td>
                    <td className="px-4 py-3"><span className="px-1.5 py-0.5 rounded bg-emerald-50 text-emerald-700 font-semibold">GET</span></td>
                    <td className="px-4 py-3 text-slate-700">成员 B</td>
                    <td className="px-4 py-3 text-slate-700">成员 C</td>
                    <td className="px-4 py-3 text-slate-500">分页加载历史提问列表与对话上下文</td>
                  </tr>
                  <tr>
                    <td className="px-4 py-3 font-medium text-slate-900">问答评价反馈</td>
                    <td className="px-4 py-3 font-mono text-slate-600">/api/qa/records/{'{id}'}/feedback</td>
                    <td className="px-4 py-3"><span className="px-1.5 py-0.5 rounded bg-blue-50 text-blue-700 font-semibold">POST</span></td>
                    <td className="px-4 py-3 text-slate-700">成员 B</td>
                    <td className="px-4 py-3 text-slate-700">成员 C</td>
                    <td className="px-4 py-3 text-slate-500">学生点赞或点踩，为模型回答打标</td>
                  </tr>
                  <tr>
                    <td className="px-4 py-3 font-medium text-slate-900">知识点深度精解</td>
                    <td className="px-4 py-3 font-mono text-slate-600">/api/knowledge/generate</td>
                    <td className="px-4 py-3"><span className="px-1.5 py-0.5 rounded bg-blue-50 text-blue-700 font-semibold">POST</span></td>
                    <td className="px-4 py-3 text-slate-700">成员 A</td>
                    <td className="px-4 py-3 text-slate-700">成员 C</td>
                    <td className="px-4 py-3 text-slate-500">传入知识点，AI 输出考点速记与 3 道自测题</td>
                  </tr>
                  <tr>
                    <td className="px-4 py-3 font-medium text-slate-900">教师人工纠偏</td>
                    <td className="px-4 py-3 font-mono text-slate-600">/api/teacher/qa/correct</td>
                    <td className="px-4 py-3"><span className="px-1.5 py-0.5 rounded bg-blue-50 text-blue-700 font-semibold">POST</span></td>
                    <td className="px-4 py-3 text-slate-700">成员 B</td>
                    <td className="px-4 py-3 text-slate-700">成员 D</td>
                    <td className="px-4 py-3 text-slate-500">教师修正 AI 错误答案，持久化为高优先级知识库</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* 视图 6：Markdown 文档源码预览 */}
        {activeTab === 'preview' && (
          <div className="bg-white rounded-xl border border-slate-200 shadow-2xs overflow-hidden">
            <div className="px-5 py-3 border-b border-slate-200 bg-slate-50 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
              <div className="flex items-center space-x-2">
                <FileText className="w-4 h-4 text-slate-500" />
                <span className="text-xs font-medium text-slate-700">
                  当前文档：{currentFileName}
                </span>
                <span className="text-[11px] px-2 py-0.5 rounded bg-indigo-50 text-indigo-700 font-medium">
                  {previewDocType === 'member'
                    ? `成员 ${selectedMemberId} 指南`
                    : previewDocType === 'spec'
                    ? '开发规范'
                    : previewDocType === 'division'
                    ? '4人分工'
                    : previewDocType === 'agent'
                    ? 'AI Agent 执行手册'
                    : '对抗性审查报告'}
                </span>
              </div>

              <div className="flex items-center space-x-2">
                <select
                  value={previewDocType}
                  onChange={(e) => setPreviewDocType(e.target.value as any)}
                  className="text-xs border border-slate-300 rounded px-2.5 py-1 bg-white text-slate-700"
                >
                  <option value="agent">🤖 AI Agent 执行指令手册 (AGENT_INSTRUCTIONS.md)</option>
                  <option value="audit">🛡️ 红蓝对抗性技术审查报告 (ADVERSARIAL_AUDIT_REPORT.md)</option>
                  <option value="member">当前成员专属指南 ({currentMemberGuide.name})</option>
                  <option value="spec">团队全栈开发规范守则 (DEV_SPECIFICATION.md)</option>
                  <option value="division">4人小组分工计划书 (TEAM_WORK_DIVISION.md)</option>
                </select>

                <button
                  onClick={() => handleCopy(currentMarkdown)}
                  className="px-2.5 py-1 text-xs font-medium text-slate-700 bg-white border border-slate-300 rounded hover:bg-slate-50 flex items-center gap-1"
                >
                  <Copy className="w-3.5 h-3.5" />
                  {copied ? '已复制' : '复制源码'}
                </button>
                <button
                  onClick={() => downloadFile(currentMarkdown, currentFileName)}
                  className="px-2.5 py-1 text-xs font-medium text-white bg-indigo-600 rounded hover:bg-indigo-700 flex items-center gap-1"
                >
                  <Download className="w-3.5 h-3.5" />
                  下载此文件
                </button>
              </div>
            </div>
            <div className="p-6 max-h-[680px] overflow-y-auto">
              <pre className="text-xs font-mono text-slate-800 leading-relaxed whitespace-pre-wrap select-all bg-slate-50/50 p-4 rounded-lg border border-slate-100">
                {currentMarkdown}
              </pre>
            </div>
          </div>
        )}

        {/* 底部全家桶下载操作条 */}
        <section className="bg-gradient-to-r from-indigo-50/60 via-slate-50 to-emerald-50/40 p-4 rounded-xl border border-indigo-100 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3">
          <div className="flex items-center space-x-2.5">
            <Sparkles className="w-4 h-4 text-indigo-600 shrink-0" />
            <p className="text-xs text-slate-700">
              <b>开发资源全量归档</b>：系统已内置 <b>4 份成员详细指南 + 1 份全栈规范 + 1 份分工计划 + 1 份 AI Agent 执行手册 + 1 份红蓝对抗性技术审查报告</b>，共计 <b>8 份</b> Markdown 文件。你可以直接一键批量下载，投喂给 AI Agent 驱动开发！
            </p>
          </div>
          <button
            id="btn-batch-download-all"
            onClick={downloadAllDocs}
            className="inline-flex items-center px-3.5 py-1.5 text-xs font-medium text-white bg-indigo-600 hover:bg-indigo-700 rounded-md transition-colors shadow-2xs shrink-0"
          >
            <FolderDown className="w-3.5 h-3.5 mr-1" />
            批量下载全部 8 份文档 (.md)
          </button>
        </section>

      </main>

      {/* 页脚 */}
      <footer className="border-t border-slate-200 py-4 bg-white text-center text-xs text-slate-500">
        AI 驱动的在线学习智能答疑辅导平台 · 4 人小组专属开发实战文档与交付管理平台
      </footer>
    </div>
  );
}

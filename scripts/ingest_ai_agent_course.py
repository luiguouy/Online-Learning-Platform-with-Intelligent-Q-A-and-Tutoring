#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
deepseek-harness 知识库 -> 结构化系统课件 -> 《AI Agent开发》(courseId=4) 切块向量化入库
"""

import os
import sys
import time
import requests
from pathlib import Path

SOURCE_DIR = Path(r"D:\Downloads\obsidian\storeManger\Anote_conserve\deepseek-harness")
OUTPUT_DIR = Path(r"D:\AI_Workspace\Online Learning Platform with Intelligent Q&A and Tutoring\knowledge_extracted\ai_agent")
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

COURSE_ID = 4 # AI Agent开发
API_BASE = "http://localhost:8080"

MODULE_MAPPING = [
    {
        "out_name": "00_AI_Agent_总览与学习路线图.md",
        "sub_dirs": ["00.总览与导航"],
        "title": "AI Agent 开发导论：总览与学习路线图"
    },
    {
        "out_name": "01_运行模式与四大能力体系.md",
        "sub_dirs": ["01.运行模式与四大能力"],
        "title": "AI Agent 四大核心能力：Goals体系、动态规划、Subagents协同与确定性流水线"
    },
    {
        "out_name": "02_上下文治理与Token经济学总纲.md",
        "sub_dirs": ["02.上下文治理与Token经济"],
        "title": "AI Agent 上下文治理与 Token 经济学：滑动压缩、持久化日志与寻址"
    },
    {
        "out_name": "03_会话记忆与Loop循环架构.md",
        "sub_dirs": ["03.会话与循环"],
        "title": "AI Agent 会话机制：记忆流、主循环设计与中断恢复"
    },
    {
        "out_name": "04_微内核与插件化架构.md",
        "sub_dirs": ["04.插件化架构"],
        "title": "AI Agent 软件工程：IoC微内核、一切皆插件与三元能力缝"
    },
    {
        "out_name": "05_Agent交互机制与安全策略.md",
        "sub_dirs": ["05.安全与交互"],
        "title": "AI Agent 交互安全与人在回路（HITL）机制"
    },
    {
        "out_name": "06_AI_Agent核心概念与架构术语全解.md",
        "sub_dirs": ["概念解释"],
        "title": "AI Agent 核心概念与架构术语精解词典"
    }
]

def login_teacher():
    url = f"{API_BASE}/api/auth/login"
    resp = requests.post(url, json={"username": "teacher01", "password": "123456"})
    if resp.status_code == 200 and resp.json().get("code") == 200:
        token = resp.json()["data"]["token"]
        print(f"[AUTH] 教师登录成功，Token: {token[:8]}...")
        return token
    raise RuntimeError(f"教师登录失败: {resp.text}")

def build_module_doc(mod_info):
    out_file = OUTPUT_DIR / mod_info["out_name"]
    lines = [f"# {mod_info['title']}\n"]
    
    file_count = 0
    for sub in mod_info["sub_dirs"]:
        sub_path = SOURCE_DIR / sub
        if not sub_path.exists():
            continue
        for md_path in sorted(sub_path.glob("*.md")):
            # 过滤临时归档或重复文件
            if "【旧版】" in md_path.name or "【重复】" in md_path.name:
                continue
            file_count += 1
            sec_title = md_path.stem.replace("_", " ")
            lines.append(f"\n## {sec_title}\n")
            try:
                content = md_path.read_text(encoding="utf-8")
                lines.append(content)
            except Exception:
                try:
                    content = md_path.read_text(encoding="gbk")
                    lines.append(content)
                except Exception as e:
                    print(f"   [WARN] 无法读取文件 {md_path.name}: {e}")
    
    full_text = "\n\n".join(lines)
    out_file.write_text(full_text, encoding="utf-8")
    print(f"📦 生成模块课件: {out_file.name} (聚合 {file_count} 篇原始笔记, 大小: {len(full_text) // 1024} KB)")
    return out_file

def upload_document(token, file_path):
    url = f"{API_BASE}/api/teacher/docs/upload"
    headers = {"Authorization": f"Bearer {token}"}
    with open(file_path, "rb") as f:
        files = {"file": (os.path.basename(file_path), f, "text/markdown")}
        data = {"courseId": COURSE_ID}
        resp = requests.post(url, headers=headers, files=files, data=data)
        if resp.status_code == 200 and resp.json().get("code") == 200:
            doc_id = resp.json().get("data")
            print(f"   [UPLOAD] 《{os.path.basename(file_path)}》上传成功！后端课件ID: {doc_id}，正在切块向量化...")
            return doc_id
        else:
            print(f"   [UPLOAD-ERROR] 上传失败: {resp.text}")
            return None

def main():
    print("=" * 60)
    print("🚀 启动《AI Agent开发》知识库构建与向量入库流水线")
    print(f"📖 原始目录: {SOURCE_DIR}")
    print(f"📂 聚合课件目录: {OUTPUT_DIR}")
    print(f"🎯 关联课程ID: {COURSE_ID} (AI Agent开发)")
    print("=" * 60)

    token = login_teacher()

    for idx, mod in enumerate(MODULE_MAPPING, 1):
        print(f"\n[{idx}/{len(MODULE_MAPPING)}] 正在处理: {mod['out_name']}")
        built_file = build_module_doc(mod)
        upload_document(token, built_file)

    print("\n" + "=" * 60)
    print(" 全部《AI Agent开发》课件上传完成！正在等待后台向量化...")
    print("=" * 60)

    # 等待 5 秒后查询状态
    time.sleep(5)
    url = f"{API_BASE}/api/teacher/docs/list?courseId={COURSE_ID}"
    headers = {"Authorization": f"Bearer {token}"}
    resp = requests.get(url, headers=headers).json()
    print("\n【当前知识库课件状态】")
    for d in resp.get("data", []):
        print(f" - ID: {d['id']} | {d['fileName']} | 状态: {d['parseStatus']} | 切块数: {d.get('chunkCount', 0)}")

if __name__ == "__main__":
    main()

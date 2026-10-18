#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
王道 2027 操作系统 PDF -> 3050 DirectML GPU 极速 OCR -> 结构化 Markdown -> 自动切块向量化入库
"""

import os
import sys
import time
import requests
import pymupdf
from pathlib import Path
from rapidocr_onnxruntime import RapidOCR

PDF_PATH = r"D:\Downloads\a408\王道2027操作系统-高清带书签.pdf"
OUTPUT_DIR = Path(r"D:\AI_Workspace\Online Learning Platform with Intelligent Q&A and Tutoring\knowledge_extracted\os")
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

COURSE_ID = 1 # 操作系统原理
API_BASE = "http://localhost:8080"

# 章节范围配置 (1-based page numbers from PDF bookmarks)
CHAPTERS = [
    {"name": "王道考研操作系统_第1章_计算机系统概述", "start": 13, "end": 48},
    {"name": "王道考研操作系统_第2章_进程与线程", "start": 49, "end": 187},
    {"name": "王道考研操作系统_第3章_内存管理", "start": 188, "end": 262},
    {"name": "王道考研操作系统_第4章_文件管理", "start": 263, "end": 316},
    {"name": "王道考研操作系统_第5章_输入输出管理", "start": 317, "end": 372},
]

def login_teacher():
    """登录教师端获取 Sa-Token 凭证"""
    url = f"{API_BASE}/api/auth/login"
    resp = requests.post(url, json={"username": "teacher01", "password": "123456"})
    if resp.status_code == 200 and resp.json().get("code") == 200:
        token = resp.json()["data"]["token"]
        print(f"[AUTH] 教师登录成功，Token: {token[:8]}...")
        return token
    raise RuntimeError(f"教师登录失败: {resp.text}")

def upload_document(token, file_path):
    """上传文件至课程知识库"""
    url = f"{API_BASE}/api/teacher/docs/upload"
    headers = {"Authorization": f"Bearer {token}"}
    with open(file_path, "rb") as f:
        files = {"file": (os.path.basename(file_path), f, "text/markdown")}
        data = {"courseId": COURSE_ID}
        resp = requests.post(url, headers=headers, files=files, data=data)
        if resp.status_code == 200 and resp.json().get("code") == 200:
            doc_id = resp.json().get("data")
            print(f"[UPLOAD] 《{os.path.basename(file_path)}》上传成功！后端课件ID: {doc_id}，正在异步切块向量化入库...")
            return doc_id
        else:
            print(f"[UPLOAD-ERROR] 上传失败: {resp.text}")
            return None

def main():
    print("=" * 60)
    print("🚀 启动 RTX 3050 DirectML GPU 极速 OCR 与知识库入库流水线")
    print(f"📖 输入 PDF: {PDF_PATH}")
    print(f"📂 输出目录: {OUTPUT_DIR}")
    print("=" * 60)

    token = login_teacher()

    print("[OCR] 初始化 RapidOCR (启用 DirectML 独显加速)...")
    engine = RapidOCR(det_use_dml=True, cls_use_dml=True, rec_use_dml=True)

    doc = pymupdf.open(PDF_PATH)
    toc = doc.get_toc() # [[level, title, page], ...]
    
    # 建立 page -> list of bookmark titles 映射
    page_bookmarks = {}
    for item in toc:
        lvl, title, p = item[0], item[1].strip(), item[2]
        if p not in page_bookmarks:
            page_bookmarks[p] = []
        page_bookmarks[p].append((lvl, title))

    for ch_idx, ch in enumerate(CHAPTERS, 1):
        md_file = OUTPUT_DIR / f"{ch['name']}.md"
        print(f"\n[{ch_idx}/{len(CHAPTERS)}] 开始处理: {ch['name']} (页码: {ch['start']} ~ {ch['end']}, 共 {ch['end'] - ch['start'] + 1} 页)")

        # 检查是否已有生成好的
        if md_file.exists() and md_file.stat().st_size > 1024:
            print(f"   [SKIP] 本地已存在 {md_file.name} ({md_file.stat().st_size // 1024} KB)，直接上传入库...")
            upload_document(token, md_file)
            continue

        ch_lines = [f"# {ch['name'].replace('_', ' ')}\n"]
        t_start = time.time()
        
        for p_no in range(ch["start"], ch["end"] + 1):
            p_idx = p_no - 1 # 0-based
            page = doc[p_idx]
            
            # 如果该页有书签，插入标题
            if p_no in page_bookmarks:
                for b_lvl, b_title in page_bookmarks[p_no]:
                    # 避免重复的主标题
                    if b_title not in ch['name']:
                        hashes = "#" * min(b_lvl + 1, 4)
                        ch_lines.append(f"\n{hashes} {b_title}\n")

            # 渲染页面并识别
            pix = page.get_pixmap(dpi=150)
            img_bytes = pix.tobytes("png")
            
            p_t0 = time.time()
            ocr_res, _ = engine(img_bytes)
            p_cost = time.time() - p_t0

            if ocr_res:
                # 过滤掉顶部水印与单独页码
                page_texts = []
                for box in ocr_res:
                    text = box[1].strip()
                    if not text:
                        continue
                    if "官方开源" in text or "最新配套视频" in text:
                        continue
                    if text.isdigit() and len(text) <= 3:
                        continue
                    page_texts.append(text)
                
                joined_text = "\n".join(page_texts)
                ch_lines.append(joined_text)
                print(f"   - Page {p_no}/{ch['end']} 识别完成 ({p_cost:.2f}s, {len(page_texts)} 行文本)")
            else:
                print(f"   - Page {p_no}/{ch['end']} 无文字或跳过 ({p_cost:.2f}s)")

        # 写入 Markdown
        md_content = "\n\n".join(ch_lines)
        with open(md_file, "w", encoding="utf-8") as f:
            f.write(md_content)
        
        cost_total = time.time() - t_start
        print(f"🎉 {ch['name']} 识别完成！耗时: {cost_total:.1f}s，生成文件大小: {len(md_content) // 1024} KB")

        # 自动上传入库
        upload_document(token, md_file)

    print("\n" + "=" * 60)
    print(" 全部章节 OCR 识别与知识库上传任务执行完毕！")
    print("=" * 60)

if __name__ == "__main__":
    main()

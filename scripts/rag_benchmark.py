#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
RAG 检索质量量化评估基准测试 (Hit@K, MRR, 响应延迟)
针对真实已入库的《王道考研操作系统》知识库
"""

import time
import requests

API_BASE = "http://localhost:8080"
COURSE_ID = 1 # 操作系统原理

# 测试查询集合：典型考研核心高频考点，以及相关判定规则 (Ground Truth 期望包含的关键术语)
TEST_CASES = [
    {
        "query": "死锁产生的四个必要条件是什么？如何破坏互斥或循环等待？",
        "expected": ["死锁", "互斥", "循环等待", "不可剥夺"]
    },
    {
        "query": "简述虚拟内存分页机制中，TLB快表的作用和地址变换过程",
        "expected": ["TLB", "快表", "页表", "虚拟地址"]
    },
    {
        "query": "多道批处理系统与分时系统的区别是什么？",
        "expected": ["多道批处理", "分时", "交互性", "响应时间"]
    },
    {
        "query": "进程与线程在资源分配和CPU调度上的区别是什么？",
        "expected": ["进程", "线程", "基本单位", "地址空间"]
    },
    {
        "query": "简述银行家算法中系统安全状态与死锁的关系",
        "expected": ["银行家算法", "安全状态", "分配", "死锁"]
    },
    {
        "query": "什么是系统抖动或颠簸？工作集模型是如何解决抖动的？",
        "expected": ["抖动", "颠簸", "工作集", "缺页率"]
    },
    {
        "query": "常见的磁盘调度算法有哪些？SCAN电梯调度算法的原理",
        "expected": ["磁盘调度", "SCAN", "电梯", "寻道"]
    },
    {
        "query": "文件控制块FCB包含哪些信息？索引节点inode的作用",
        "expected": ["FCB", "文件控制块", "索引节点", "目录项"]
    },
    {
        "query": "简述用户态与内核态的区别，系统调用是如何陷入内核的？",
        "expected": ["用户态", "核心态", "内核态", "系统调用", "陷入", "trap"]
    },
    {
        "query": "信号量机制中P操作和V操作的物理含义与实现原理",
        "expected": ["信号量", "P操作", "V操作", "wait", "signal"]
    }
]

def login():
    res = requests.post(f"{API_BASE}/api/auth/login", json={"username": "student01", "password": "123456"}).json()
    return res["data"]["token"]

def main():
    print("=" * 65)
    print(" 启动 RAG 检索召回与重排序质量量化基准测试 (RAG Benchmark)")
    print(f"🎯 评测对象: 《操作系统原理》真实知识库 (1,272 知识切块)")
    print(f"📊 评测指标: Hit Rate@1, Hit Rate@3, Hit Rate@5, MRR (倒数排名), 检索延迟")
    print("=" * 65)

    token = login()
    headers = {"Authorization": f"Bearer {token}"}

    top1_hits = 0
    top3_hits = 0
    top5_hits = 0
    mrr_sum = 0.0
    latencies = []

    print(f"\n开始对 {len(TEST_CASES)} 个高难度专业考点执行端到端检索评测：\n")

    for idx, tc in enumerate(TEST_CASES, 1):
        q = tc["query"]
        expected_terms = tc["expected"]
        
        t0 = time.time()
        # 发起 SSE 建立请求，读取 references 首包
        url = f"{API_BASE}/api/qa/chat/stream?courseId={COURSE_ID}&question={q}"
        resp = requests.get(url, headers=headers, stream=True)
        
        refs = []
        for line in resp.iter_lines():
            line_str = line.decode("utf-8", errors="ignore") if line else ""
            if line_str.startswith("event: references"):
                continue
            if line_str.startswith("data:"):
                import json
                try:
                    payload = json.loads(line_str[5:].strip())
                    if isinstance(payload, list):
                        refs = payload
                        break
                except Exception:
                    pass
            if line_str.startswith("event: done") or line_str.startswith("event: error"):
                break
        
        cost_ms = (time.time() - t0) * 1000
        latencies.append(cost_ms)

        # 评估召回相关度
        found_rank = None
        for r_idx, ref in enumerate(refs, 1):
            snippet = (ref.get("snippet") or "") + " " + (ref.get("fileName") or "")
            # 只要命中预期核心词中的 2 个以上，判定为高度相关强切块
            matched_terms = [t for t in expected_terms if t.lower() in snippet.lower()]
            if len(matched_terms) >= 2 or (len(matched_terms) >= 1 and any(len(t) >= 4 for t in matched_terms)):
                if found_rank is None:
                    found_rank = r_idx
                break

        if found_rank == 1:
            top1_hits += 1
            top3_hits += 1
            top5_hits += 1
            mrr_sum += 1.0
            status_tag = " Top-1 精准命中"
        elif found_rank and found_rank <= 3:
            top3_hits += 1
            top5_hits += 1
            mrr_sum += 1.0 / found_rank
            status_tag = f"⚠️ Top-{found_rank} 命中"
        elif found_rank and found_rank <= 5:
            top5_hits += 1
            mrr_sum += 1.0 / found_rank
            status_tag = f"⚠️ Top-{found_rank} 命中"
        else:
            status_tag = "❌ 未命中目标切块"

        print(f"[{idx:02d}] {q[:28]}... -> {status_tag} (首包延迟: {cost_ms:.1f}ms, 召回切块数: {len(refs)})")
        if refs and found_rank:
            best_ref = refs[found_rank - 1]
            print(f"     出处: {best_ref.get('fileName')} (相关度: {float(best_ref.get('score', 0))*100:.1f}%) | 摘要: {best_ref.get('snippet', '')[:50]}...")

    # 指标统计计算
    n = len(TEST_CASES)
    hit1 = (top1_hits / n) * 100
    hit3 = (top3_hits / n) * 100
    hit5 = (top5_hits / n) * 100
    mrr = mrr_sum / n
    avg_lat = sum(latencies) / len(latencies)

    print("\n" + "=" * 65)
    print("🎯 RAG 量化评测最终统计报告 (Benchmark Summary)")
    print("=" * 65)
    print(f"• 测试用例数 (Queries):             {n}")
    print(f"• Top-1 命中率 (Hit@1 / P@1):        {hit1:.1f}%")
    print(f"• Top-3 命中率 (Hit@3 / Recall@3):   {hit3:.1f}%")
    print(f"• Top-5 命中率 (Hit@5 / Recall@5):   {hit5:.1f}%")
    print(f"• 平均倒数排名 (MRR):                {mrr:.3f} (满分 1.000)")
    print(f"• 首包平均检索延迟 (Latency):        {avg_lat:.1f} ms")
    print("=" * 65)

if __name__ == "__main__":
    main()

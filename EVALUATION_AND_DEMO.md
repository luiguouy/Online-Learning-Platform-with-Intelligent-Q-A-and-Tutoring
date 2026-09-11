# 质量度量、演示保障与答辩冲刺（v1.0）

> **为什么需要这份文档**：审查报告里的"三道防线"目前全是口头承诺。要拉开与"套壳调 API"的差距，最便宜的方式是把口头承诺变成**一张有数字的表格**。
> **工作量**：金标集 + 评测脚本约 1~2 天（成员 A），演示兜底约半天（成员 D），限流约 2 小时（成员 B）。
> **产出价值**：答辩时"我们的 recall@3 是 86%，无资料时拒答率 92%"这句话，比十页架构图更有说服力。

---

## 一、 金标问答集（Golden Set）

### 1.1 什么是金标集

人工标注的"问题 → 应该命中的课件 → 必须答到的关键点"三元组。它是**唯一能证明 RAG 真的在工作**的东西：命中率高说明检索有效，而不是大模型在瞎编。

### 1.2 建设要求

| 项目 | 要求 |
| :--- | :--- |
| 规模 | **至少 20 条**（覆盖 2~3 门课程的课件） |
| 负责人 | 成员 D 出题（他本身就负责测试课件），成员 A 校验 |
| 完成时间 | **第 3 周末前**（RAG 闭环跑通后立刻建） |
| 存放位置 | 代码仓 `evaluation/golden_set.json`（**不含任何密钥**），本文档仓只放模板 |

### 1.3 数据格式

```json
[
  {
    "id": 1,
    "courseId": 1,
    "question": "什么是虚拟内存？",
    "expectedDocId": 12,
    "expectedDocName": "第3章 内存管理.pdf",
    "keyPoints": ["虚拟地址空间", "页表", "部分装入", "页面置换"],
    "shouldAnswer": true,
    "note": "基础概念题，考察能否命中内存管理章节"
  },
  {
    "id": 2,
    "courseId": 1,
    "question": "量子计算机的退相干时间是多少？",
    "expectedDocId": null,
    "keyPoints": [],
    "shouldAnswer": false,
    "note": "课件中不存在的内容，必须拒答，考察幻觉抑制"
  }
]
```

**字段说明**：
- `shouldAnswer: false` 的用例是**幻觉陷阱**（占总数 20%~30%），用于验证"无资料时是否老实说不知道"。
- `keyPoints` 用于人工/LLM 辅助判断答案是否答到点上（不做自动断言，只做人工核对辅助）。

### 1.4 出题分布建议（20 条）

| 类型 | 数量 | 目的 |
| :--- | :--- | :--- |
| 基础概念题（课件中有直接定义） | 6 | 考察检索命中率 |
| 跨章节综合题（需多个片段） | 4 | 考察 Top-K 是否够用 |
| 细节参数题（具体数值/步骤） | 4 | 考察切块粒度是否合理 |
| 近似表述题（用同义词提问） | 3 | 考察 Embedding 语义匹配能力 |
| **幻觉陷阱（课件外内容）** | 3 | 考察拒答能力 |

---

## 二、 评测脚本与指标

### 2.1 核心指标

| 指标 | 定义 | 目标值 | 不合格时怎么调 |
| :--- | :--- | :--- | :--- |
| **Recall@3** | 前 3 个检索片段中包含正确课件的比例 | ≥ 80% | 调小 chunk size、提高 overlap、换 Embedding 模型 |
| **命中率@1** | 首个片段即正确课件的比例 | ≥ 60% | 检查切块是否切断了关键句 |
| **拒答率** | 幻觉陷阱题中被正确拒答的比例 | ≥ 90% | 强化 Prompt 中的"无资料必须说明"约束 |
| **首字延迟** | 提问到收到第一个 token 的时间 | ≤ 3 秒 | 检查检索耗时、Embedding 调用是否同步阻塞 |
| **端到端延迟** | 提问到 `done` 事件 | ≤ 30 秒（200 字内答案） | 换更快的模型、减少 Top-K |

### 2.2 评测脚本（`evaluation/run_eval.py`）

放到代码仓，本地运行（**不需要 CI 跑，避免消耗 API 额度**）：

```python
#!/usr/bin/env python3
"""金标集评测：计算 Recall@K 与拒答率。
用法：python evaluation/run_eval.py --base http://localhost:8080 --token <satoken> --k 3
"""
import argparse, json, time, requests

def ask_once(base, token, course_id, question, timeout=60):
    """调用 SSE 接口，返回 (references 的 docId 列表, 完整答案, 首字延迟)"""
    url = f"{base}/api/qa/chat/stream"
    params = {"courseId": course_id, "sessionId": 0, "question": question}
    headers = {"Authorization": f"Bearer {token}", "Accept": "text/event-stream"}
    doc_ids, answer, first_token_at = [], "", None
    start = time.time()
    with requests.get(url, params=params, headers=headers,
                      stream=True, timeout=timeout) as r:
        r.raise_for_status()
        event = None
        for raw in r.iter_lines(decode_unicode=True):
            if raw is None or not raw.strip():
                event = None
                continue
            if raw.startswith("event:"):
                event = raw.split(":", 1)[1].strip()
                continue
            if not raw.startswith("data:"):
                continue
            data = raw.split(":", 1)[1].strip()
            if event == "references":
                try:
                    doc_ids = [x.get("docId") for x in json.loads(data)]
                except json.JSONDecodeError:
                    doc_ids = []
            elif event == "message":
                if first_token_at is None:
                    first_token_at = time.time() - start
                try:
                    answer += json.loads(data).get("delta", "")
                except json.JSONDecodeError:
                    answer += data
            elif event == "done":
                break
    return doc_ids, answer, (first_token_at or -1), time.time() - start


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--token", required=True, help="登录后 localStorage 里的 satoken")
    ap.add_argument("--k", type=int, default=3)
    ap.add_argument("--set", default="evaluation/golden_set.json")
    args = ap.parse_args()

    cases = json.load(open(args.set, encoding="utf-8"))
    hit_at_k = hit_at_1 = refused = 0
    answerable = traps = 0
    rows = []

    for c in cases:
        doc_ids, answer, first_delay, total = ask_once(
            args.base, args.token, c["courseId"], c["question"])
        if c["shouldAnswer"]:
            answerable += 1
            top_k = doc_ids[:args.k]
            if c["expectedDocId"] in top_k:
                hit_at_k += 1
            if doc_ids and doc_ids[0] == c["expectedDocId"]:
                hit_at_1 += 1
            ok = c["expectedDocId"] in top_k
        else:
            traps += 1
            # 拒答判据：答案中包含明确的"无资料"表述
            ok = any(kw in answer for kw in ("未找到", "没有找到", "课件中未", "无法回答", "暂无"))
            refused += 1 if ok else 0
        rows.append((c["id"], c["question"][:20], "PASS" if ok else "FAIL",
                     round(first_delay, 2), round(total, 2)))
        print(f"[{c['id']:>2}] {'PASS' if ok else 'FAIL'} | 首字 {first_delay:.2f}s | 总计 {total:.2f}s | {c['question'][:30]}")

    print("\n================ 评测结果 ================")
    if answerable:
        print(f"Recall@{args.k} : {hit_at_k}/{answerable} = {hit_at_k/answerable:.0%}")
        print(f"命中率@1      : {hit_at_1}/{answerable} = {hit_at_1/answerable:.0%}")
    if traps:
        print(f"拒答率        : {refused}/{traps} = {refused/traps:.0%}")
    print("==========================================")

if __name__ == "__main__":
    main()
```

**运行前置条件**：后端已启动、课件已上传并处于 `CHUNKED` 状态、已用 `teacher01` 登录拿到 token。

### 2.3 答辩用的结果表（模板，第 3 周填真实数字）

| 指标 | 无 RAG（直接问大模型） | 本项目（RAG） | 提升 |
| :--- | :--- | :--- | :--- |
| 事实性错误率 | — % | — % | ↓ — |
| 能给出出处比例 | 0% | — % | ↑ — |
| Recall@3 | — | — % | — |
| 幻觉陷阱拒答率 | — % | — % | — |

> **这张表是答辩的"杀手锏"**：左边那列只要现场演示一次"同样的问题，不开 RAG 时大模型会编造一个不存在的定义"，说服力极强。

---

## 三、 演示保障（防翻车）

### 3.1 三层兜底（从强到弱）

| 层级 | 措施 | 触发条件 | 负责人 |
| :--- | :--- | :--- | :--- |
| **L1 事前** | 演示前 30 分钟检查清单（3.2） | 每次演示前 | A |
| **L2 事中** | 答案缓存回放：LLM 调用失败时，从 `qa_record` 中检索历史相似问答直接回放 | 调用异常/超时 | A |
| **L3 兜底** | 完整演示录屏（3~5 分钟），任何情况下都能放 | 服务完全起不来 | D |

**L2 实现要点**（复用已有的"纠偏优先"思路，成本极低）：
在 `SseStreamService` 的异常处理分支中，先查 `qa_record` 是否有 `is_corrected=1` 或历史相似记录，有则回放并标注"（缓存答案）"，无则走 L3 录屏。不要为了兜底引入新的存储——**直接复用已有的 MySQL 历史表**。

### 3.2 演示前 30 分钟检查清单

| # | 检查项 | 不通过怎么办 |
| :--- | :--- | :--- |
| 1 | 后端启动无异常日志，Knife4j 能打开 | 重启；仍失败切 L3 录屏 |
| 2 | Chroma 容器 `docker ps` 在跑，collection 存在 | `docker restart chroma`，重新上传课件 |
| 3 | 大模型 Key 余额充足，手动调一次接口有返回 | 换备用 Key（组长保管） |
| 4 | 种子账号 `teacher01/123456` 与 `student01` 能登录 | 检查 BCrypt 种子数据是否入库 |
| 5 | 至少 1 门课件状态为 `CHUNKED` | 重新上传并等待切块完成 |
| 6 | 完整跑一遍主流程（登录→选课→提问→点赞→教师纠偏） | 任何一步卡住，立即修或改演示脚本 |
| 7 | 演示录屏文件已下载到本地（不依赖网络） | 提前一天录好 |
| 8 | 浏览器无痕窗口，清理缓存，投影分辨率已调 | 现场调 |

### 3.3 演示脚本（建议顺序，5 分钟）

1. **痛点开场**（30s）：老师备课答疑重复劳动量大 → 展示教师后台课件上传
2. **核心能力**（2min）：学生端提问 → 流式打字机 → **展开参考资料出处**（这是与套壳的最大区别）
3. **质量证明**（1min）：跑一个幻觉陷阱问题，展示"课件中没有，我不编造"
4. **纠偏闭环**（1min）：教师端修改一条错误答案 → 学生端再问同样问题 → **答案已变**
5. **数据收尾**（30s）：ECharts 学情看板 + 评测指标表

> 第 3、4 步是**不可省略**的：它们证明了这不是 ChatGPT 套壳，而是有知识库、可治理的系统。

---

## 四、 限流与成本护栏

### 4.1 为什么必须做

答辩现场或公开演示时，恶意/好奇的重复提问会在几分钟内烧光 API 额度（RAG 每次调用含 Embedding + 生成，成本远高于普通对话）。**评委常问"别人一直刷你的接口怎么办"，有实现就能直接答。**

### 4.2 实现方案（成员 B，约 2 小时）

基于 Sa-Token 登录态做**按用户固定窗口计数**。**零新增依赖**（只用 JDK 的 `ConcurrentHashMap` / `AtomicInteger`）——
原方案用 Guava `RateLimiter`，但项目 pom 未声明 Guava，直接写会**编译失败**；且 `StpUtil.getLoginIdAsLong()` 在未登录时会抛 `NotLoginException`，必须先判 `isLogin()`。

```java
@Component
public class QaRateLimitInterceptor implements HandlerInterceptor {

    /** 限流规则：每用户每 60 秒最多 20 次提问（演示场景足够，可按需要调整） */
    private static final int MAX_REQUESTS = 20;
    private static final long WINDOW_MILLIS = 60_000L;

    private final Map<Long, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final Map<Long, Long> windowStart = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response, Object handler) throws Exception {
        // 关键：未登录时绝不能调用 getLoginIdAsLong()，否则抛 NotLoginException 导致 500
        if (!StpUtil.isLogin()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"请先登录\"}");
            return false;
        }

        Long userId = StpUtil.getLoginIdAsLong();
        long now = System.currentTimeMillis();

        // 跨过时间窗口则重置计数
        windowStart.compute(userId, (id, start) -> {
            if (start == null || now - start > WINDOW_MILLIS) {
                counters.put(id, new AtomicInteger(0));
                return now;
            }
            return start;
        });

        if (counters.get(userId).incrementAndGet() > MAX_REQUESTS) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":429,\"message\":\"提问过于频繁，请稍后再试\"}");
            return false;
        }
        return true;
    }

    /** 定期清理过期计数，防止 Map 随用户数无限增长（需在启动类加 @EnableScheduling） */
    @Scheduled(fixedRate = 600_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        windowStart.entrySet().removeIf(e -> {
            if (now - e.getValue() > WINDOW_MILLIS) {
                counters.remove(e.getKey());
                return true;
            }
            return false;
        });
    }
}
```

注册（**只拦截答疑接口**，不要全局拦截，否则会误伤登录与上传）。
可以单独建配置类，也可以直接合并进 B 指南已有的 `SaTokenConfigure`：

```java
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final QaRateLimitInterceptor qaRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(qaRateLimitInterceptor)
                .addPathPatterns("/api/qa/chat/stream", "/api/knowledge/generate");
    }
}
```

> **依赖说明**：本实现零新增依赖。`@Scheduled` 清理需要启动类加 `@EnableScheduling`；若不想加，可删除 `cleanup()` 方法（课设规模下用户数极少，影响可忽略）。

### 4.3 成本控制（组长负责）

| 措施 | 说明 |
| :--- | :--- |
| 演示专用 Key | 与开发 Key 分离，设置平台侧每日消费上限 |
| 缓存高频问题 | 命中历史问答直接返回，不调用 LLM（复用 L2 回放逻辑） |
| 限制输入长度 | 提问超过 500 字直接拒绝，防止超长 Prompt 烧 Token |
| 每日额度告警 | 平台侧配置余额告警，低于阈值通知组长 |

---

## 五、 答辩前的自我审查（评委视角 10 问）

组长在答辩前组织一次模拟，用这 10 个问题自问，**答不上来的就是还要补的**：

1. 你的系统和"直接调 ChatGPT"的本质区别是什么？（答：课件检索 + 出处溯源 + 教师纠偏闭环 + 拒答机制）
2. 检索不准的时候怎么办？（答：看 Recall@3 数据 + 调切块策略 + 教师纠偏兜底）
3. 课件里没有的内容，系统会编吗？（答：不会，有拒答机制，演示给你看）
4. 两个人同时提问会卡死吗？（答：专用 `sseExecutor` 线程池，不用默认公共池）
5. 换一门课会不会串答案？（答：`courseId` 元数据过滤做租户隔离）
6. 删掉的课件还在影响答案吗？（答：级联删除向量，不留幽灵参考）
7. API Key 泄露了怎么办？（答：不入库 + CI 密钥扫描 + 立即轮换）
8. 你的数据存在哪？断电丢吗？（答：MySQL 持久化 + 课件文件本地绝对路径存储）
9. 教师纠偏后，学生再问会变吗？（答：会，纠偏优先双路检索）
10. 这套系统能支持多少人用？（答：单机验证规模，说明瓶颈在 LLM 并发与限流策略）

> 无法回答的问题，回到对应文档补齐或补实现——**答不上来通常意味着实现里真的缺了这一块**。

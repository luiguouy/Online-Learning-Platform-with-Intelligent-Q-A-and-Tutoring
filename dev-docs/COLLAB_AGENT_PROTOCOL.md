# Agent 协作流程执行指令（COLLAB_AGENT_PROTOCOL.md）

> **文档性质**：面向各组员的编程 Agent（Claude Code / Cursor / Windsurf / Qwen-Code 等），定义**开工、站会、提 PR、完成**四个时刻 Agent 必须执行的 GitHub 协作动作。
> **执行主体**：Agent 自己执行（组员只负责说一句触发词）。前提是组员本人已登录 `gh`（见第 0 节）——看板上每个人的操作必须用他本人的账号，组长才能按人过滤。
> **与《组员发指令速查卡》的关系**：那份管"任务怎么写代码"，本份管"任务状态怎么同步"。两者同时生效。

---

## 0. 一次性环境自检（每个组员的 Agent 开工前先跑）

```bash
gh auth status
```

- 显示本人账号已登录 → 继续。
- 未登录 → **停下来**，让组员自己在终端跑 `gh auth login`（选 GitHub.com → HTTPS → Login with a web browser），Agent 不要代填任何凭据。
- 登录身份必须与自己的 Issue assignee 一致（A=luiguouy，B=Cheng-king666，D=susir1，C 待加入）。

---

## 1. 任务唯一真源：GitHub Issue（不是聊天，不是文档表格）

本周所有任务已拆成 Issue 并指派到人。**Agent 开始任何编码工作前，必须先定位对应 Issue**：

```bash
# 列出指派给我的、未关闭的任务 Issue
gh issue list --repo luiguouy/Online-Learning-Platform-with-Intelligent-Q-A-and-Tutoring --assignee @me --state open
```

标题格式 `[A2.1] Top-K 向量检索…` 与 `THREE_WEEK_PLAN.md` 任务编号一一对应。Issue 正文含：任务目标、交付物、验收标准、截止日。**验收标准就是本任务的 DoD，优先于任何口头描述。**

组员说"我要做 XXX"但没给编号时，先从上面列表里找到最匹配的 Issue，把编号复述给组员确认后再动手。

---

## 2. 开工：第一件事是移卡片

确认任务后，在写第一行代码**之前**执行：

```bash
# 2.1 把看板卡片移到 In Progress（需先取 projectId 与 itemId，见附 A）
#     状态字段 Status 的选项 ID 固定为：
#       Todo            = 53f23f3c
#       In Progress     = e38981bc
#       Ready for review = a264fef7
#       Done            = 7ef1e567
#     （附 A 给出取 projectId/itemId 的通用命令，一次获取后可缓存进会话）

# 2.2 从 dev 拉工作分支（命名规范见 COLLABORATION_WORKFLOW.md 2.1）
git checkout dev && git pull origin dev
git checkout -b feature/<我的字母>-<任务短名>
```

## 3. 每日站会：发在该任务的 Issue 评论区

每个工作日开工时，Agent 替组员把三行站会**直接发到 Issue**：

```bash
gh issue comment <编号> --repo luiguouy/Online-Learning-Platform-with-Intelligent-Q-A-and-Tutoring --body "昨天：<上次进展>
今天：<本次要做>
阻塞：无 / 阻塞：<说清楚卡在谁/什么上，我打算先用什么顶着>"
```

站会内容从组员的口述 + 上次提交记录归纳，写完让组员过目一眼再发。

**阻塞上报**：同一问题连修 3 次失败、或等待他人产出超过半天时，Agent 必须主动提出打 blocked：

```bash
gh issue edit <编号> --repo <同仓库> --add-label blocked
```

并提醒组员在微信群 @ 组长一句话（链接 + 一句人话即可）。

## 4. 提 PR：卡片移到 Ready for review

任务完成后：

1. 对照 Issue 正文的验收标准**逐条自测**，把真实命令输出（`BUILD SUCCESS` / 接口返回 / 测试结果）整理进 PR 描述——遵循 `.github/PULL_REQUEST_TEMPLATE.md` 四段式。
2. 创建 PR 指向 `dev`，描述首行写 `Closes #<编号>`（合并时自动关 Issue）。

```bash
gh pr create --repo luiguouy/Online-Learning-Platform-with-Intelligent-Q-A-and-Tutoring --base dev --title "<type>(<scope>): <subject>" --body "..."
```

3. 把看板卡片移到 **Ready for review**（Status 选项 ID `a264fef7`）。
4. 提醒组员把 PR 链接发微信群一行（求 Review）。

## 5. 合并与完成

- CI 全绿 + 至少 1 人 Approve + 评论清完 → 组长（或有权限者）合并 → Issue 因 `Closes #` 自动关闭。
- 合并后 Agent 把卡片移到 **Done**（`7ef1e567`），删除已合并的远程分支。
- **禁止**：`--no-verify`、强推公共分支、注释掉失败用例凑 CI 绿、自己给自己 Approve 后秒合（A 除外，A 是终审人但改动 main 仍需走 PR）。

## 6. 契约变更红线（最高优先级拦截）

任务执行中若发现需要改动 `DEV_SPECIFICATION.md` 第四章接口字段 / 数据库表结构：

**立即停止写代码**，提醒组员：这要走 `COLLABORATION_WORKFLOW.md` 6.3 四步法——先提 `[契约变更]` Issue（打 `contract-change` 标签）→ 提供方+调用方在 Issue 回复同意 → 先改文档仓再改代码 → 群里通知。Agent 不得"顺手改"字段。

---

## 附 A：看板操作命令速查（Agent 用）

```bash
# 取 projectId（固定值，可直接用）：PVT_kwHOCORPac4BjVMF
# 取某 Issue 的看板 itemId（把 <编号> 换掉，用 issue 的 node_id 反查）：
gh api graphql -f query='{node(id:"<issue_node_id>"){... on Issue{projectCards(first:5){nodes{project{id}field(projectV2){id}}}}}}'
# 更直接：列项目全部卡片与对应 Issue 编号
gh api graphql -f query='{node(id:"PVT_kwHOCORPac4BjVMF"){... on ProjectV2{items(first:30){nodes{id content{... on Issue{number}}}}}}}'

# 移卡片（三个值替换后即可）：
gh api graphql --input - <<'EOF'
{"query":"mutation($i:UpdateProjectV2ItemFieldValueInput!){updateProjectV2ItemFieldValue(input:$i){projectV2Item{id}}}","variables":{"i":{"projectId":"PVT_kwHOCORPac4BjVMF","itemId":"<PVTI_…>","fieldId":"PVTSSF_lAHOCORPac4BjVMFzhiJ6BE","value":{"singleSelectOptionId":"e38981bc"}}}}
EOF
```

> GraphQL 偶发 TLS 超时：重试即可；嵌套 input 必须用 `--input`/stdin 传 JSON，不能用 `-f` 字符串拼接。

## 附 B：组员每天只需对 Agent 说的三句话

| 时机 | 说这句 | Agent 该做（对照本文） |
| :--- | :--- | :--- |
| 开工 | 「站会，开始今天的 <任务编号>」 | §3 发评论 + §2 移 In Progress、拉分支 |
| 做完 | 「提 PR」 | §4 自测 → PR → Ready for review → 提醒发群链接 |
| 卡住 | 「卡住了，帮我打 blocked」 | §3 打标签 + 提醒群里 @ 组长 |

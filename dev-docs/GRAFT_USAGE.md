# Graft 上下文图谱 使用速查卡

> **Graft 是什么**：`@nanonets/graft`（本机 v0.18.0，装在 WSL 全局）——把整个仓库静态解析成一张
> 「谁调用谁 / 谁引用谁 / 谁 import 谁」的上下文图谱，输出到仓库根 `graft/` 目录。
> **纯本地解析、零 API Key、零费用**，覆盖本项目的 `.java` + `.ts` + `.vue`。
>
> **解决什么痛点**：改了某个 Service/Mapper，前端哪些页面会受影响？这个符号被谁调用？
> 不用逐个文件翻，一条命令秒级返回精确到 `文件:行号`。

---

## 0. 前置条件（一次性）

- 本机已安装并可用 **WSL**，且 WSL 内已装 graft（`graft version` 能输出版本号）。
- 若换机器 / graft 缺失，在 WSL 内执行：`npm i -g @nanonets/graft`。

---

## 1. 建图（每次代码大改后跑一次）

**推荐用封装脚本**（自动换算 WSL 路径，免手动处理含空格和 `&` 的路径）：

```powershell
# 在项目根目录执行
pwsh -File scripts/graft-build.ps1          # 建图
pwsh -File scripts/graft-build.ps1 -Map     # 建图 + 打印仓库地图概览
pwsh -File scripts/graft-build.ps1 -Viz     # 建图 + 启动浏览器可视化(http://localhost:4400)
pwsh -File scripts/graft-build.ps1 -Check   # 只检查图谱是否过时(不重建)，exit 1 = 已过时
```

等价的原始命令（手动版，注意路径要换算成 `/mnt/d/...`）：

```bash
graft build "/mnt/d/AI_Workspace/Online Learning Platform with Intelligent Q&A and Tutoring" \
  -e .java -e .ts -e .vue
```

> 首次实测输出：`313 nodes · 1186 edges · 71 cards [java, typescript, vue]`，秒级完成。
> **2026-09-30 现状**：`512 nodes（225 method / 103 file / 86 function / 60 class / 32 interface / 6 type）· 1791 edges · 103 cards`，
> 其中 61 文件重新解析、42 文件命中缓存，仍是秒级。

---

## 2. 日常查询命令（都无需 Key）

以下命令中 `<repo>` 代表仓库 WSL 路径
`/mnt/d/AI_Workspace/Online Learning Platform with Intelligent Q&A and Tutoring`。

| 场景 | 命令 | 作用 |
|------|------|------|
| **谁调用了这个方法** | `graft callers removeDocumentVectors <repo>` | 反向调用链，精确到 `文件:行号` |
| **这个方法调用了谁** | `graft callers <symbol> <repo> --direction out` | 正向依赖 |
| **改这行会影响啥(爆炸半径)** | `graft blast <repo> --format markdown` | 基于 git diff 的传递影响，含 Mermaid 图，可直接贴 PR |
| **自然语言找代码** | `graft ask "课件入库流程" <repo>` | 语义检索，返回排序节点+行号 |
| **看单文件 API 骨架** | `graft skeleton CourseService.java <repo>` | 只看签名，最省 token |
| **仓库结构概览** | `graft map <repo>` | 目录聚类 + Hub + 热点符号 |
| **按符号分组的正则搜索** | `graft grep "courseId" <repo>` | 命中按所属符号归类、按耦合度排序 |

**示例（实测）**：`graft callers removeDocumentVectors <repo>` 返回——
它被 `TeacherDocumentController.delete` / `.reindex` 及两个测试调用，每处带行号与源码片段。

---

## 3. 接入 AI 编码助手（可选；⚠️ 本项目**暂缓**，原因见第 5 节）

让 Qoder / Claude Code 等 Agent 把图谱当成代码导航工具，回答架构类问题时不再逐文件烧 token：

```powershell
# 先预览会写哪些文件（强烈建议）
graft init <repo> --dry-run
# 确认后再正式接入
graft init <repo>
```

接入后 Agent 会话会多出 `graft_find_code` / `graft_trace_calls` / `graft_repo_map` 等 MCP 工具。
> ⚠️ `graft init` 可能改用户级配置（如 `~/.claude/`）。只想动仓库内文件就加 `--no-global`。

---

## 4. 与开发流程的接线点（谁在什么时候必须用）

图谱本身不产生价值，**被流程强制调用才产生价值**。此前本卡是唯一提到 graft 的文档（CI、Agent 协议、PR 模板、四份成员指南全未引用），等于「有说明书、没接线」。现已接入四处：

| 时机 | 谁 | 动作 | 写在哪里 |
|------|-----|------|----------|
| **开工前**（一次性） | 各成员的 Agent | `graft-build.ps1 -Check`，过时则重建 | `COLLAB_AGENT_PROTOCOL.md` §0 |
| **写第一行代码前** | 各成员的 Agent | 对要改的符号跑 `graft callers`，调用方列表贴进任务 Issue 评论区 | `COLLAB_AGENT_PROTOCOL.md` §2.3 |
| **提 PR 时** | PR 作者 | `graft blast --format markdown` 输出贴进 PR 模板 §4「影响面」折叠块 | `.github/PULL_REQUEST_TEMPLATE.md` §4 |
| **审 PR 时** | Reviewer | 对照 §4 的 blast 输出核实「无夹带无关修改」 | PR 模板 Reviewer 检查表 |

**只改文档 / 注释 / 测试文案时可跳过**——graft 只解析 `.java` / `.ts` / `.vue`。

`graft blast` 还有一个副作用价值：它能抓出 **IDE 自动格式化夹带的无关改动**。本项目已多次出现
「只编辑了一个 `.java` 文件，IDE 格式化器却把别处几个 Javadoc 块从 `<p>文本</p>` 重排成三行、
并把构造函数参数与三元表达式换行」的情况，肉眼读 diff 很容易放过
（判据：`git diff --numstat` 与 `git diff -w --numstat` 差距异常大）。

> **graft 治不了什么**：它只覆盖代码符号，**管不着文档 / 注释 / 配置与事实的漂移**。
> 而后者恰恰是本项目近期返工的主要来源（例：写进 `application.yml` 与 Javadoc 的实测数据曾是错的、
> `DEV_SPECIFICATION.md` 引用了仓库内不存在的文件、A3 文档写了不存在的能力）。
> **两者必须并行，别指望 graft 兜住文档。**

---

## 5. 团队约定与注意事项

- **`graft/` 已写入根 `.gitignore`**：它是本地缓存产物，**每人各自 build，不提交、不进仓库**。
- **不侵入构建**：不改 `pom.xml` / `package.json` / 任何源码，不加 Maven/npm 依赖。
- **代码更新后记得重建**：查询命令默认会做 freshness 检查自动提示过时；也可直接重跑 build。
- **跨盘性能**：项目在 `D:\` 经 WSL `/mnt/d/` 访问，文件多时会略慢；当前 **103 文件**仍无感（秒级）。若将来文件数破几百，可考虑在 WSL 原生 ext4 里 clone 一份专供建图。
- **⚠️ 不要把 `-Check` 加进 CI**（脚本旧注释曾误标「CI 用」，已更正）：`graft/` 不入库，CI 上没有既有图谱可比对——要么先 build 再 check（必然通过、纯浪费 runner 时间），要么直接报「无图谱」。`-Check` 的正确用法是**本地开工前 / 提交前**跑。真要在 CI 里用 graft，有价值的形态是另一种：在 PR 上跑 `graft blast` 并把影响面自动回帖（ubuntu runner 原生可跑、不需 WSL），但那属于第 4 节人工流程的自动化升级，等流程用顺了再评估。
- **⚠️ `graft init`（MCP 接入）暂缓，不要擅自跑**：`--dry-run` 实测它会新增**两个入库文件**（`.mcp.json`、`.github/copilot-instructions.md`，二者均**不在** `.gitignore` 内，提交后影响全组），外加**三处影响本机所有仓库**的全局写入（`~/.claude/settings.json` 的 SessionStart / UserPromptSubmit / PostToolUse / Stop hook、`~/.claude.json`、`~/.claude/helpers/`）。`.claude/` 本身已 gitignore（L51），那部分无害。若将来要接：先 `--dry-run` 复核 → 加 `--no-global` → 并决定那两个入库文件是提交还是加进 `.gitignore`，走 PR 讨论。
- **`--deep` 模式不推荐**：那才需要 LLM Key，本项目不需要——语义总结交给对话 Agent 读图谱直接做即可。因此 `graft check` 报的 `meaning tier 0% complete` 是**预期状态，不是缺陷**。
- **WSL 代理告警可忽略**：输出里可能出现乱码告警（localhost 代理在 WSL0NAT 模式下不支持）与 `latest: unreachable (offline?)`。纯静态解析不需网络，**不影响建图**，只是无法检查 graft 新版本。

---

## 6. 相关文件

| 文件 | 说明 |
|------|------|
| `scripts/graft-build.ps1` | 一键建图脚本（本卡第 1 节） |
| `.gitignore` | 含 `graft/` 忽略规则（L54-55）与 `.claude/`（L51） |
| `graft/INDEX.md` | build 自动生成的图谱入口 |
| `.github/PULL_REQUEST_TEMPLATE.md` | §4「影响面」= `graft blast` 输出的粘贴位（第 4 节） |
| `dev-docs/COLLAB_AGENT_PROTOCOL.md` | §0 / §2.3 / §4 三处强制调用点（第 4 节） |

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
pwsh -File scripts/graft-build.ps1 -Check   # 只检查图谱是否过时(CI 用，不重建)
```

等价的原始命令（手动版，注意路径要换算成 `/mnt/d/...`）：

```bash
graft build "/mnt/d/AI_Workspace/Online Learning Platform with Intelligent Q&A and Tutoring" \
  -e .java -e .ts -e .vue
```

> 首次实测输出：`313 nodes · 1186 edges · 71 cards [java, typescript, vue]`，秒级完成。

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

## 3. 接入 AI 编码助手（可选，价值最大）

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

## 4. 团队约定与注意事项

- **`graft/` 已写入根 `.gitignore`**：它是本地缓存产物，**每人各自 build，不提交、不进仓库**。
- **不侵入构建**：不改 `pom.xml` / `package.json` / 任何源码，不加 Maven/npm 依赖。
- **代码更新后记得重建**：查询命令默认会做 freshness 检查自动提示过时；也可直接重跑 build。
- **跨盘性能**：项目在 `D:\` 经 WSL `/mnt/d/` 访问，文件多时会略慢；当前 71 文件无感。若将来文件数破几百，可考虑在 WSL 原生 ext4 里 clone 一份专供建图。
- **`--deep` 模式不推荐**：那才需要 LLM Key，本项目不需要——语义总结交给对话 Agent 读图谱直接做即可。

---

## 5. 相关文件

| 文件 | 说明 |
|------|------|
| `scripts/graft-build.ps1` | 一键建图脚本（本卡第 1 节） |
| `.gitignore` | 含 `graft/` 忽略规则 |
| `graft/INDEX.md` | build 自动生成的图谱入口 |

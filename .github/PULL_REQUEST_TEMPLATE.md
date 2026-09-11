<!--
本模板需复制到【代码仓】根目录的 .github/ 下才会自动生效。
四段式描述缺任一段，Reviewer 有权直接打回。
-->

## 1. 做了什么

<!-- 一句话说清本次改动，例如：实现课件上传接口并触发异步切块 -->

## 2. 为什么

<!-- 关联的 Issue，例如：Closes #12 -->

## 3. 怎么验证

<!-- 贴出本地编译/运行的成功输出，不要只写"已测试" -->
```text
mvn -B compile
[INFO] BUILD SUCCESS
```

## 4. 影响面

- [ ] 未改动接口字段 / 表结构 / 依赖
- [ ] 改动了（请说明改动点，并 @ 受影响的成员）

---

### 提交者自查（Definition of Done）

- [ ] 代码编译通过，无 `TODO` 空桩，无 `System.out.println`
- [ ] 主流程跑通，且覆盖了 3 个边界（空输入 / 超长输入 / 无权限）
- [ ] 接口字段与 `DEV_SPECIFICATION.md` 4.2 契约一致，Knife4j 可调通
- [ ] commit 信息符合 Conventional Commits：`<type>(<scope>): <subject>`
- [ ] 无密钥、无 `application-local.yml`、无大文件混入
- [ ] 分支存活未超过 3 天，且已同步最新 `dev`

### Reviewer 检查（逐条过，不要只点 Approve）

- [ ] CI 全绿
- [ ] 改动范围与描述一致，无夹带无关修改
- [ ] 后端：Controller 无业务逻辑，接口统一 `Result<T>` 包装
- [ ] 前端：无未处理 Promise，Token 统一用 `satoken` 键
- [ ] SSE 相关：4 事件 JSON 载荷未被破坏，`done` 仍含 `recordId`

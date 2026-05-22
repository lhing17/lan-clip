继续推进当前任务，并严格遵守：

- doc/notes/TODO.md
- .claude/agent-policy.md

分支与集成策略：

- 不允许直接在 `master` 上提交代码。
- `master` 只作为稳定主干，由人工或 CI 合并 PR。
- Claude Code 的持续推进基线分支为 `ai/integration`。
- 每轮任务分支统一使用 `feature/` 前缀，例如：
  - `feature/todo-0001-clear-template-test`
  - `feature/todo-0002-config-module`
  - `feature/todo-0003-fingerprint-module`
- 每轮任务应在独立 git worktree 中完成，避免污染主工作区。
- 后续循环必须基于最新 `ai/integration`，不要因为 `ai/integration -> master` 的 PR 未合并而回退到 `master`。
- `doc/notes/TODO.md` 是循环状态文件，只能在基于最新 `ai/integration` 的任务分支中更新。

执行规则：

1. 每轮开始前重新分析：
   - 当前所在分支与 worktree
   - `master`、`ai/integration`、远端分支状态
   - 当前代码状态
   - 当前任务进度
   - 当前错误与日志
   - TODO 未完成项
2. 每轮开始前准备分支和 worktree：
   - 执行 `git fetch origin`
   - 如果远端存在 `origin/ai/integration`，则基于它更新本地 `ai/integration`
   - 如果远端不存在 `origin/ai/integration`，则从 `origin/master` 创建 `ai/integration`
   - 从最新 `ai/integration` 创建一个新的 `feature/...` 任务分支
   - 为该任务分支创建独立 worktree，并在该 worktree 内执行本轮修改
   - 如果主工作区或目标 worktree 有未归属的脏改动，必须先汇报并避免覆盖
3. 每轮只完成一个最小闭环：
   - 分析本轮要完成的待办项（按优先级和容易落地的顺序，不用严格按TODO.md中顺序）
   - 制定落地方案
   - 修改代码
   - 验证修改是否符合预期
   - 汇报本轮结果，包括修改内容、验证结果、是否符合预期
   - 更新PROJECT\_NOTES.md文档，极简记录完成的内容，标注完成的日期，如#2026-05-19 完成XX Feature
   - 更新TODO.md文档，标记已完成项
   - 在 `feature/...` 任务分支提交代码
4. 每轮完成后的集成规则：
   - 将 `feature/...` 任务分支合入 `ai/integration`，或创建 `feature/... -> ai/integration` 的任务 PR
   - 推送 `ai/integration`
   - 确保存在一个 `ai/integration -> master` 的总 PR，用于人工审核和最终合并
   - 如果总 PR 尚未合并，下一轮仍然继续基于最新 `ai/integration` 推进
   - 不允许为了等待 PR 合并而把后续任务改到 `master` 上执行
   - 如果`feature/...`任务分支已被合并，需要及时清理worktree
5. 严格遵守风险分级：
   - 🟢 绿灯：允许自动执行
   - 🟡 黄灯：允许执行，但必须汇报
   - 🔴 红灯：必须停止并等待人工确认
6. 如果出现失败：
   - 自动分析
   - 自动重试
   - 自动查阅文档
   - 自动提出修复方案
7. 连续失败超过 3 次：
   - 停止继续尝试
   - 输出阻塞原因
   - 请求人工介入
8. 严禁：
   - 编造配置
   - 编造接口
   - 编造业务逻辑
   - 使用伪造密钥
   - 执行危险生产操作
   - 直接向 `master` 提交
   - 从过期的 `master` 或过期的 `ai/integration` 开始新任务
   - 在多个并行任务分支中同时修改 `doc/notes/TODO.md`
9. 优先：
   - 小步推进
   - 持续验证
   - 可回滚修改
   - 保持系统稳定
   - 任务分支短生命周期
   - `ai/integration` 作为连续推进事实源

目标：

在安全边界内持续自主推进任务，直到：

- 任务完成
- 出现红灯决策
- 或需要人工介入


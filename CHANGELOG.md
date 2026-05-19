# Change Log

本文件记录 lan-clip 的所有重要变更，遵循 [keepachangelog.com](http://keepachangelog.com/) 的约定。

版本号遵循语义化版本（Semantic Versioning）。

## [Unreleased]

### Added

- 新增 `lan-clip.config` 命名空间，集中默认配置（`:port` / `:target-host` / `:target-port` / `:file-size` / `:interval`）、读取、合并与校验；附 5 个 `lein test` 用例覆盖默认值、缺失文件、自定义覆盖、非法端口与默认配置自洽。

### Changed

- 清理 `lein new` 模板留下的失败断言，让 `lein test` 重新可信。
- 更新 `project.clj` 的 `:description` / `:url` 为 lan-clip 真实定位与仓库地址。
- 补全 `.gitignore`，显式忽略 `.DS_Store` 与 Claude Code loop 流程产物（`.claude/scheduled_tasks.lock`、`.claude/worktrees/`）。
- 改写 `CHANGELOG.md` 与 `doc/intro.md`，移除 `lein new` 模板内容，文档入口指向 `doc/notes/`。

## [1.0]

工程初始版本：基于 Clojure + Netty 实现 LAN 内文本 / 图片 / 文件剪贴板同步。
详细架构与已知风险见 [`doc/notes/project-overview.md`](doc/notes/project-overview.md)；
桌面端落地计划见 [`doc/notes/tauri-desktop-landing-plan.md`](doc/notes/tauri-desktop-landing-plan.md)。

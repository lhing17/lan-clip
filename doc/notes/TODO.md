# lan-clip TODO

生成时间：2026-05-19

最后更新时间：2026-05-21

本文档根据 `doc/notes/project-overview.md` 和 `doc/notes/tauri-desktop-landing-plan.md` 整理，用作改造与 Tauri 桌面端落地的执行清单。本文档仅记录未完成的任务，已完成的任务已在PROJECT_NOTES.md中记录，在本文档中应当删除。

## 执行原则

- 先修核心，再包桌面端。
- 先让 `lein test` 可信，再做大规模重构。
- 先移除 Java 对象反序列化，再扩展多端能力。
- Tauri 第一版采用 Clojure sidecar，不立即重写 Rust 核心。
- 每个阶段结束都要有可运行、可验证的交付物。

## 状态标记

- `[ ]` 未开始
- `[~]` 进行中
- `[!]` 阻塞或需要决策

## Phase 0：工程清理与基线 已完成

## Phase 1：核心模块化 已完成

## Phase 2：协议安全改造 已完成

## Phase 3：回环控制与多节点基础 已完成

## Phase 4：文件传输改造 已完成

## Phase 5：sidecar 管理 API 已完成

## Phase 6：Tauri MVP 已完成

## Phase 7：打包与跨平台验收

目标：生成 macOS / Windows 可安装包，并完成核心场景验收。

- [ ] P0 在 macOS 验证文本互传。
- [ ] P0 在 macOS 验证图片互传。
- [ ] P0 在 macOS 验证小文件互传。
- [ ] P0 生成 Windows `.msi` 或 `.exe`。
- [ ] P0 在 Windows 验证文本互传。
- [ ] P0 在 Windows 验证图片互传。
- [ ] P0 在 Windows 验证小文件互传。
- [ ] P1 验证防火墙提示和端口监听行为。
- [ ] P1 验证停止同步后不再发送。
- [ ] P1 验证退出后无残留进程。
- [ ] P2 梳理应用图标、名称、版本号、签名策略。

完成标准：

- macOS 与 Windows 安装包可安装、可启动、可退出。
- 双端文本、图片、小文件互传通过。
- release checklist 可复用。

## Phase 8：发布体验增强

目标：在 MVP 稳定后补足产品体验。

- [ ] P1 增加开机自启动设置。
- [ ] P1 增加自动更新能力。
- [ ] P2 增加设备发现：mDNS/Bonjour 或局域网扫描。
- [ ] P2 增加配对流程，减少手动配置 IP 和共享密钥。
- [ ] P2 增加多 peer 同步策略。
- [ ] P2 增加传输历史。
- [ ] P2 增加失败重试。
- [ ] P3 评估是否把 Clojure sidecar 迁移到 Rust/Tauri 后端。

完成标准：

- 普通用户可以更少依赖手动配置完成两端连接。
- 发布、升级和长期运行体验更完整。

## 当前已知风险

- Java 对象反序列化在安全上必须尽早移除。
- AWT 剪贴板跨平台行为需要持续在 macOS 和 Windows 上实测。
- Tauri sidecar 打包 JVM 运行时可能带来体积问题，需要比较 uberjar + 内置 JRE、jlink、GraalVM native-image 三种方案。
- Windows 防火墙、macOS 权限和托盘行为需要单独验收。
- 文件传输涉及路径、覆盖、大小限制和用户感知，不能只靠日志处理失败。

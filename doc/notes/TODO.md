# lan-clip TODO

生成时间：2026-05-19

本文档根据 `doc/notes/project-overview.md` 和 `doc/notes/tauri-desktop-landing-plan.md` 整理，用作后续改造与 Tauri 桌面端落地的执行清单。

## 执行原则

- 先修核心，再包桌面端。
- 先让 `lein test` 可信，再做大规模重构。
- 先移除 Java 对象反序列化，再扩展多端能力。
- Tauri 第一版采用 Clojure sidecar，不立即重写 Rust 核心。
- 每个阶段结束都要有可运行、可验证的交付物。

## 状态标记

- `[ ]` 未开始
- `[~]` 进行中
- `[x]` 已完成
- `[!]` 阻塞或需要决策

## Phase 0：工程清理与基线

目标：清理模板残留，让当前工程具备可信的开发基线。

- [x] P0 清理模板失败测试：修改 `test/lan_clip/core_test.clj`，删除固定失败断言。
- [x] P0 运行 `lein test`，确保基础测试通过。
- [x] P0 更新 `project.clj` 中的 `:description` 和 `:url`。
- [x] P1 清理 `CHANGELOG.md` 模板内容，改为 lan-clip 真实变更骨架。
- [x] P1 改写 `doc/intro.md`，作为文档入口并链接到 `doc/notes`。
- [x] P1 梳理 `.gitignore`，确认忽略 `.DS_Store`、IDE、本地缓存、构建产物。
- [x] P1 确认 `resources/config.edn` 示例配置不包含个人局域网 IP 或敏感信息。
- [x] P2 补充 README 中的当前运行限制和安全提示。

完成标准：

- `lein test` 可以通过。
- 工程元信息不再是 Leiningen 模板默认值。
- 文档入口能指向现有 notes。

## Phase 1：核心模块化

目标：把当前混在 `core.clj` / `util.clj` / `socket` 中的逻辑拆成可测试、可停止、可复用的核心模块。

- [x] P0 新建 `src/lan_clip/config.clj`，集中默认配置、读取、合并、校验。
- [x] P0 为配置增加测试：默认值、缺失文件、自定义覆盖、非法端口。
- [x] P0 新建 `src/lan_clip/fingerprint.clj`，迁移 `ClipboardData` 与内容摘要判断。
- [x] P0 为 fingerprint 增加测试：文本、图片字节、文件列表、类型变化。
- [x] P0 新建 `src/lan_clip/clipboard.clj`，封装系统剪贴板读取与写入。
- [x] P1 为 clipboard 定义可替换抽象，测试中使用 fake clipboard。
- [x] P0 将 `set-interval` 改造成可停止 watcher。
- [x] P0 新建 `src/lan_clip/app.clj`，提供 `start!`、`stop!`、`status`。
- [x] P1 将 Netty server、clipboard watcher 纳入统一生命周期（`app.clj` `start!`/`stop!`）。
- [x] P1 退出时释放 server 端口、watcher future、Netty event loop。
- [ ] P2 删除或下沉 `server.clj` 中未使用的顶层 `config`。

完成标准：

- 核心服务可以在 REPL 或测试中启动、查询状态、停止。
- 停止后端口释放，轮询不再继续。
- 配置与 fingerprint 有单元测试覆盖。

## Phase 2：协议安全改造

目标：移除 Java 对象反序列化，换成显式协议和共享密钥认证。

- [x] P0 新建 `src/lan_clip/protocol.clj`。
- [x] P0 定义协议字段：magic、version、message-id、origin-node-id、sender-node-id、content-type、metadata-length、payload-length。
- [x] P0 选定 metadata 格式。使用 EDN（`pr-str`）作为第一版，避免新增依赖；后续如需 Rust/Tauri 互通可再迁移到 JSON。
- [x] P0 实现文本消息编码与解码。
- [x] P0 实现 `HMAC-SHA256` 签名与校验。
- [x] P0 为 magic/version/length/HMAC 错误增加拒绝逻辑。
- [x] P0 替换文本链路中的 Netty `ObjectEncoder` / `ObjectDecoder`。
  - [x] 新建 `lan-clip.socket.protocol-codec`，提供 `encode-frame` / `->protocol-encoder` / `->protocol-decoder`。
  - [x] 修改 `server.clj` / `client.clj` / `core.clj` 使用新 codec；`lein test` 全绿，集成测试验证 encoder → decoder 往返。localhost 双端运行需手动验收。
  - [x] 图片链路已迁移（PNG payload / HMAC / encoder-decoder 往返）。
  - [x] 文件链路已迁移（zip payload / 临时目录解压 / encoder-decoder 往返）。
- [x] P1 实现图片消息编码与解码，payload 使用 PNG 字节。
- [x] P1 实现文件消息编码与解码，第一版使用 zip payload。
- [x] P1 服务端限制最大 payload，避免超大消息耗尽内存。
- [x] P1 为协议增加单元测试：正常文本、HMAC 失败、版本不匹配、payload 超限。
- [x] P2 为图片与文件协议增加测试。

完成标准：

- 服务端不再依赖 Java 对象反序列化。
- HMAC 校验失败的消息不会写入剪贴板。
- localhost 文本同步集成测试通过。

## Phase 3：回环控制与多节点基础

目标：避免两端互相回传同一份剪贴板内容，为后续多 peer 做准备。

- [x] P0 在配置中生成并持久化 `node-id`。
- [x] P0 每条消息生成 `message-id`（`protocol.clj` `encode-message` 中已生成 UUID）。
- [x] P0 消息 header 已携带 `origin-node-id` 和 `sender-node-id`；metadata 当前仅含 `:content-type`，后续如需扩展可在 P1 补充。
- [x] P0 增加最近处理消息缓存，按数量 LRU 淘汰。
- [x] P0 远端写入剪贴板后记录 `last-remote-fingerprint`。
- [x] P1 watcher 识别远端刚写入的内容并抑制回发。
- [x] P1 日志区分 `local-change`、`remote-apply`、`loop-suppressed`。
- [x] P1 增加双端 localhost 手工或自动验收脚本。

完成标准：

- A 复制一次文本到 B 后，不会出现持续互相发送。
- 日志能看出消息来源和抑制原因。
- 同一端再次主动复制时行为可预期。

## Phase 4：文件传输改造

目标：让文件同步更安全、更可控，避免覆盖和临时目录混乱。

- [x] P0 新增配置项 `received-files-dir`。
- [x] P0 默认接收目录改为应用数据目录下 `received-files`。
- [x] P0 每次接收创建批次目录：`yyyyMMdd-HHmmss-message-id`。
- [x] P0 处理同名文件，避免覆盖。
- [x] P1 文件 metadata 记录文件名、相对路径、大小、hash。
- [x] P1 超过 `max-file-size-kb` 时拒绝发送并产生可展示事件。
- [ ] P1 接收完成后把真实本地文件列表写入系统剪贴板。
- [ ] P2 支持目录结构。
- [ ] P2 预留分块传输、进度事件和取消接口。

完成标准：

- 连续接收同名文件不会覆盖。
- 超限文件有明确日志或状态事件。
- 剪贴板里的文件列表指向真实存在的接收文件。

## Phase 5：sidecar 管理 API

目标：把 Clojure 核心变成可被 Tauri 管理的后台服务。

- [ ] P0 选型轻量 HTTP server 依赖。
- [ ] P0 新建 `src/lan_clip/api.clj`。
- [ ] P0 实现 `GET /status`。
- [ ] P0 实现 `GET /config`。
- [ ] P0 实现 `PUT /config`。
- [ ] P0 实现 `POST /sync/start`。
- [ ] P0 实现 `POST /sync/stop`。
- [ ] P1 实现 `GET /logs/recent`。
- [ ] P1 sidecar 启动成功后输出 ready 标记。
- [ ] P1 日志落盘，路径可通过状态或配置查询。
- [ ] P1 配置变更区分热更新和需重启。
- [ ] P2 增加健康检查和版本接口：`GET /version` 或并入 `/status`。

完成标准：

- 用 curl 可以完成启动、停止、查看状态、读取配置、保存配置。
- Tauri 不需要直接调用 Clojure 内部函数。
- sidecar 可以独立运行与调试。

## Phase 6：Tauri MVP

目标：构建第一版桌面应用壳，管理 sidecar 并提供基本 UI。

- [ ] P0 初始化 Tauri v2 工程：`src-tauri`。
- [ ] P0 初始化前端工程。建议 Vite + TypeScript。
- [ ] P0 决定前端框架：React 或 Svelte。
- [ ] P0 配置 Tauri sidecar，打包 `lan-clip-core`。
- [ ] P0 Rust 后端实现 sidecar 启动、停止、状态检测。
- [ ] P0 主窗口实现状态页：同步开关、节点名、监听端口、sidecar 状态。
- [ ] P0 主窗口实现配置页：设备名、端口、peers、共享密钥、轮询间隔、文件大小、接收目录。
- [ ] P1 主窗口实现日志页：最近事件、错误详情、打开日志目录。
- [ ] P1 实现系统托盘：打开窗口、启动/停止同步、打开接收目录、退出。
- [ ] P1 关闭主窗口时隐藏窗口，不退出应用。
- [ ] P1 退出应用时停止 sidecar，确保无残留进程。
- [ ] P1 配置 Tauri capabilities，只开放必要命令和目录权限。
- [ ] P2 实现系统通知：同步错误、文件超限、sidecar 启动失败。
- [ ] P2 实现关于页：版本、协议版本、检查更新入口。

完成标准：

- macOS 本地可以运行 Tauri dev app。
- UI 可以启动/停止同步并保存配置。
- 托盘菜单可用。
- 退出后 sidecar 不残留。

## Phase 7：打包与跨平台验收

目标：生成 macOS / Windows 可安装包，并完成核心场景验收。

- [ ] P0 生成 macOS `.app` 或 `.dmg`。
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
- [ ] P1 建立 `doc/notes/release-checklist.md`。
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

## 第一轮建议执行顺序

先从这 10 项开始：

1. [x] 清理模板测试，使 `lein test` 通过。
2. [x] 新建 `lan-clip.config`，集中默认配置、读取、校验。
3. [x] 新建 `lan-clip.fingerprint`，迁移 `ClipboardData` 和 MD5 判断。
4. [x] 抽象 `lan-clip.clipboard`，封装读取/写入文本、图片、文件列表。
5. [x] 给 watcher 增加可停止机制。
6. [x] 新建 `lan-clip.app`，提供 `start!`、`stop!`、`status`。
7. [x] 新建 `lan-clip.protocol`，先实现 text message 的新协议和 HMAC。
8. [x] 替换 Netty object codec 的文本链路，跑通 localhost 文本同步。
9. [x] 迁移 image 和 files 链路。（image 与 files 均已完成）
10. [ ] 加入 HTTP 管理 API，为 Tauri 做准备。

## 当前已知风险

- Java 对象反序列化在安全上必须尽早移除。
- AWT 剪贴板跨平台行为需要持续在 macOS 和 Windows 上实测。
- Tauri sidecar 打包 JVM 运行时可能带来体积问题，需要比较 uberjar + 内置 JRE、jlink、GraalVM native-image 三种方案。
- Windows 防火墙、macOS 权限和托盘行为需要单独验收。
- 文件传输涉及路径、覆盖、大小限制和用户感知，不能只靠日志处理失败。

# lan-clip 主要问题改造与 Tauri 桌面端落地方案

编写时间：2026-05-19

## 目标

把 lan-clip 从一个可运行的命令行/JVM 局域网剪贴板同步工具，改造成一个可长期使用、可配置、可打包分发的跨平台桌面应用。

落地目标分三层：

- 核心同步能力稳定：文本、图片、小文件在可信局域网内可靠同步。
- 主要工程问题收敛：协议安全、回环控制、配置管理、资源生命周期、测试覆盖和模板残留得到处理。
- Tauri 桌面壳可用：提供系统托盘、配置页、连接状态、日志/错误提示、启动/停止同步、安装包打包。

## 总体策略

推荐采用“两阶段架构”：

第一阶段：保留现有 Clojure/JVM 同步核心，把它改造成稳定的后台 sidecar 服务，Tauri 负责桌面 UI、托盘、配置管理、进程管理和打包。

第二阶段：当桌面版功能稳定后，再评估是否把同步核心迁移到 Rust/Tauri 后端。迁移 Rust 的收益是减少 JVM 体积、简化打包、贴近 Tauri 原生能力；代价是剪贴板、图片、文件列表、跨平台差异都要重新实现和验证。

选择先 sidecar 的原因：

- 现有核心已经验证过 macOS/Windows 的基本剪贴板行为。
- Tauri 官方支持嵌入外部二进制 sidecar，适合把已有核心作为子进程运行。
- 可以先把协议、安全和产品体验补齐，再决定是否重写核心。
- 风险拆分更清楚：核心服务与桌面 UI 可以分阶段验收。

## 目标架构

```text
┌──────────────────────────────────────────┐
│              Tauri Desktop App           │
│                                          │
│  ┌──────────────┐   ┌─────────────────┐ │
│  │ Frontend UI  │   │ Tauri Rust Host │ │
│  │              │   │                 │ │
│  │ 配置页        │<->│ Commands/Event  │ │
│  │ 状态页        │   │ Tray/Updater    │ │
│  │ 日志页        │   │ Sidecar Manager │ │
│  └──────────────┘   └────────┬────────┘ │
└──────────────────────────────┼──────────┘
                               │ stdin/stdout 或 localhost 管理 API
┌──────────────────────────────▼──────────┐
│           lan-clip-core sidecar          │
│                                          │
│  Clipboard Watcher                       │
│  Sync Engine                             │
│  Peer Transport                          │
│  Config/State/Logs                       │
└──────────────────────────────────────────┘
```

桌面端职责边界：

- Tauri 前端：配置编辑、状态展示、手动操作、日志查看。
- Tauri Rust 后端：托盘、窗口、权限、sidecar 生命周期、系统通知、自动更新。
- Clojure sidecar：剪贴板监听、内容编码、网络传输、接收写入、核心日志和状态事件。

## 主要问题与改造方案

### 1. 协议安全

现状问题：

- 当前使用 Netty `ObjectEncoder` / `ObjectDecoder` 做 Java 对象序列化。
- Java 反序列化天然风险较高，且当前没有认证、加密、来源校验。
- 任意可信网段内的进程只要能连上端口，就可能尝试写入剪贴板。

改造方案：

- 移除 Java 对象反序列化协议。
- 定义显式的应用层协议：固定 magic、version、message-id、source-id、type、metadata-length、payload-length、metadata、payload。
- metadata 使用 EDN 或 JSON。考虑到后续 Tauri/Rust 互通，建议优先 JSON。
- payload 按类型保存：
  - text：UTF-8 字节。
  - image：PNG 字节。
  - files：tar/zip 包或多段文件 payload。
- 每条消息加入 HMAC，例如 `HMAC-SHA256(shared-secret, header + metadata + payload)`。
- 第一阶段可不做传输加密，但必须做共享密钥认证；第二阶段再引入 TLS 或 Noise 协议。

验收标准：

- 服务端拒绝 magic/version 不匹配的消息。
- 服务端拒绝 HMAC 校验失败的消息。
- 服务端不再依赖 JVM 对象反序列化。
- 单元测试覆盖文本、图片、文件 metadata 编解码和 HMAC 校验。

### 2. 剪贴板回环控制

现状问题：

- A 写入 B 后，B 的轮询器可能把同一内容再发回 A。
- 当前只依赖本地 `ClipboardData` 摘要去重，无法完整表达“这条内容来自哪个节点、是否已经处理过”。

改造方案：

- 每个设备生成稳定 `node-id`，保存到用户配置目录。
- 每条同步消息生成 `message-id`。
- 维护最近处理消息缓存，例如最近 1000 条或最近 30 分钟。
- 本地写入远端内容后，记录 `suppress-until` 或 `last-remote-fingerprint`，下一轮轮询识别为远端写入时不再发送。
- 消息中携带 `origin-node-id` 和 `sender-node-id`，后续支持多节点时可以避免广播回源。

验收标准：

- 两端同时运行时，复制一次文本不会持续互相发送。
- 本机用户再次复制同样内容时，行为符合预期：可配置为不重复发送，或带手动重发入口。
- 日志能区分 local-change、remote-apply、loop-suppressed。

### 3. 配置管理

现状问题：

- 配置文件路径固定为 `~/.lan-clip/config.edn`。
- server 端口只在启动时生效，但监听逻辑每轮读取配置。
- 配置项没有 schema、默认值说明和校验。
- Tauri 包装后需要 GUI 编辑配置，并把配置变更反馈给后台进程。

改造方案：

- 定义统一配置 schema：
  - `node-id`
  - `device-name`
  - `listen-host`
  - `listen-port`
  - `peers`
  - `shared-secret`
  - `clipboard-poll-interval-ms`
  - `max-file-size-kb`
  - `received-files-dir`
  - `auto-start-sync`
  - `log-level`
- 配置迁移到平台应用数据目录。Clojure sidecar 可先支持旧路径兼容读取，再写入新路径。
- 配置变更分为两类：
  - 热更新：目标 peers、轮询间隔、文件大小限制、日志级别。
  - 需重启：监听端口、绑定地址、协议版本。
- 提供 sidecar 管理 API：
  - `GET /status`
  - `GET /config`
  - `PUT /config`
  - `POST /sync/start`
  - `POST /sync/stop`
  - `GET /logs/recent`

验收标准：

- 配置文件不存在时自动生成完整默认配置。
- 非法端口、非法路径、缺失共享密钥等能给出明确错误。
- Tauri UI 保存配置后，sidecar 能热更新或提示需要重启。

### 4. 文件传输

现状问题：

- 文件接收目录固定为当前工作目录下 `tmp`。
- 同名文件可能覆盖。
- 文件列表传输方式简单，缺少目录结构、权限、进度、错误状态。
- 大文件超过阈值后只打印日志。

改造方案：

- 接收目录改为配置项，默认使用应用数据目录下 `received-files`。
- 每次接收创建独立批次目录：`yyyyMMdd-HHmmss-message-id`。
- 保留文件相对路径，防止同名冲突。
- 文件 payload 第一阶段可使用 zip 包，metadata 中记录文件清单、大小、hash。
- 大文件默认拒绝并发出状态事件，UI 展示“文件超过大小限制”。
- 第二阶段支持分块传输、进度事件和取消。

验收标准：

- 同名文件连续接收不会覆盖。
- 文件接收后剪贴板里的文件列表指向真实存在的本地文件。
- 超限文件不会被部分写入，UI 能看到原因。

### 5. 资源生命周期

现状问题：

- 每次发送都创建新的 Netty `NioEventLoopGroup`。
- 定时器是无限 `future`，没有停止机制。
- server 启动后缺少可控生命周期。

改造方案：

- 引入 `SyncService` 状态机：
  - `starting`
  - `running`
  - `stopping`
  - `stopped`
  - `error`
- 将 clipboard watcher、server、client transport 都纳入统一 start/stop。
- 复用 client event loop 或连接池，避免每次发送创建完整资源。
- 所有后台线程命名，退出时可等待 shutdown。
- sidecar 收到 SIGTERM 或管理 API stop 时优雅退出。

验收标准：

- Tauri 点击“停止同步”后，端口释放，剪贴板轮询停止。
- 退出桌面应用时 sidecar 不残留。
- 频繁复制 100 次不会产生持续增长的线程或 event loop。

### 6. 测试与质量门禁

现状问题：

- 当前测试是 Leiningen 模板失败用例。
- 核心逻辑没有可自动验证的测试。

改造方案：

- 删除模板失败测试，建立真实测试集。
- 优先补单元测试：
  - 配置读取、默认值、校验。
  - 内容 fingerprint。
  - 协议 header/metadata/payload 编解码。
  - HMAC 验证。
  - 文件接收目录生成。
- 再补 localhost 集成测试：
  - 启动 server，发送 text message，验证 handler 行为。
  - 不直接依赖系统剪贴板的测试用 fake clipboard 抽象。
- CI 或本地脚本：
  - `lein test`
  - `lein uberjar`
  - 前端 `npm test` 或 `npm run check`
  - `cargo test`
  - `cargo tauri build` 在发布阶段运行。

验收标准：

- `lein test` 不再失败。
- 核心协议和配置有稳定测试。
- 发布前至少有一份手工验收清单。

## Tauri 桌面端设计

### 技术选型

- Tauri：使用 Tauri v2。
- 前端：建议 Vite + React + TypeScript，或 Vite + Svelte + TypeScript。若没有强偏好，React 生态更常见，Svelte 包体和心智负担更轻。
- Tauri 后端：Rust，只做桌面能力、sidecar 管理、命令桥接和安全权限配置。
- 核心同步：第一阶段继续使用 Clojure uberjar 或 jlink/jpackage 后的 JVM sidecar。

### UI 信息架构

第一版不做复杂页面，重点做“常驻小工具”：

- 状态页：
  - 同步开关。
  - 当前节点名称、监听端口。
  - sidecar 状态。
  - 最近一次发送/接收内容类型和时间。
  - 已连接或已配置 peer 列表。
- 配置页：
  - 设备名称。
  - 监听端口。
  - peer 地址列表。
  - 共享密钥设置/重置。
  - 轮询间隔。
  - 最大文件大小。
  - 文件接收目录。
- 日志页：
  - 最近事件列表。
  - 错误详情。
  - 打开日志目录。
- 关于页：
  - 版本。
  - 协议版本。
  - 检查更新。

### 系统托盘

Tauri v2 支持系统托盘能力，官方文档说明托盘 API 可在 JavaScript 和 Rust 两侧使用，并需要启用 `tray-icon` feature。

托盘菜单建议：

- 打开 lan-clip。
- 启动同步 / 停止同步。
- 最近状态：运行中、已停止、错误。
- 打开接收文件夹。
- 偏好设置。
- 退出。

托盘行为：

- 默认启动后进入托盘。
- 关闭主窗口不退出程序，只隐藏窗口。
- 退出菜单负责停止 sidecar 并退出应用。

### Sidecar 集成

Tauri 官方支持嵌入外部二进制 sidecar，适合把现有核心作为桌面应用的一部分发布。

推荐 sidecar 形态：

- `lan-clip-core` 可执行文件，而不是直接依赖用户系统安装 Leiningen。
- 第一阶段可用 uberjar + 内置 JRE 或平台脚本启动。
- 更理想是用 GraalVM native-image 或 jlink 打出最小运行时，但需要单独验证 AWT、Netty、图片和剪贴板兼容性。

Tauri 与 sidecar 通信方式：

- 管理 API 使用 localhost HTTP，便于调试和跨语言调用。
- 状态事件可以先用轮询 `GET /status`，后续改为 WebSocket 或 server-sent events。
- sidecar stdout/stderr 仍接入 Tauri 日志，用于启动失败诊断。

### 权限与安全

Tauri v2 有 capabilities 权限系统，默认会限制前端可调用的命令和插件能力。应遵循最小权限原则：

- 只暴露必要 commands：读取状态、保存配置、启动/停止同步、打开目录、检查更新。
- 文件系统权限限定到配置目录、日志目录和接收文件目录。
- shell/sidecar 权限只允许启动固定的 `lan-clip-core`。
- updater 权限只在发布渠道配置完成后启用。

### 自动更新

Tauri 提供 updater 插件，官方文档中 updater 权限需要在 capabilities 中显式启用。建议放在桌面版稳定后再接入：

- 内测阶段：手动下载安装包。
- Beta 阶段：启用检查更新，但默认不自动安装。
- 正式阶段：支持自动下载并提示安装。

## 分阶段落地计划

### Phase 0：工程清理与基线

目标：让当前工程从“模板项目”变成可维护项目。

任务：

- 更新 `project.clj` 的 `:description`、`:url`、依赖备注。
- 清理 `CHANGELOG.md` 模板内容。
- 把 `doc/intro.md` 改成真实文档入口，链接到 notes。
- 修复 `test/lan_clip/core_test.clj`，去掉模板失败用例。
- 梳理 `.gitignore`，确认不提交 `.DS_Store`、IDE、本地缓存和构建产物。
- 增加 `doc/notes/project-overview.md` 和本方案作为后续基线。

交付物：

- 工程元信息真实可读。
- `lein test` 至少能通过空测试或基础测试。

### Phase 1：核心逻辑模块化

目标：把当前 `core.clj` 中混在一起的监听、配置、发送和服务启动拆开。

建议模块：

```text
src/lan_clip/
├── app.clj              # 统一 start/stop 状态机
├── config.clj           # 配置 schema、读写、校验、迁移
├── clipboard.clj        # 剪贴板读取、写入、Transferable
├── fingerprint.clj      # 内容摘要与回环抑制
├── protocol.clj         # 新协议编解码
├── transport/
│   ├── client.clj
│   └── server.clj
├── files.clj            # 文件打包、接收目录、大小限制
└── api.clj              # sidecar 管理 HTTP API
```

任务：

- 把 clipboard 读写抽象成 protocol，方便测试 fake clipboard。
- 把配置读取从 `util.clj` 拆到 `config.clj`。
- 把 `set-interval` 改成可停止 watcher。
- 建立 `app/start!`、`app/stop!`、`app/status`。

交付物：

- Clojure 核心可以被代码启动/停止，而不仅是 `-main`。
- 单元测试覆盖配置和 fingerprint。

### Phase 2：新传输协议与安全

目标：去掉 Java 对象反序列化，加入共享密钥认证和消息元信息。

任务：

- 定义 `MessageHeader` 和 `MessageMetadata`。
- 实现 text/image/files 三种消息编码。
- 服务端按 header 解析 payload，不再接受任意 JVM 对象。
- 实现 HMAC 校验。
- 加入 `node-id`、`message-id`、`origin-node-id`。

交付物：

- 本机 localhost text/image/file 集成测试通过。
- 错误消息可观测，例如 HMAC failed、payload too large、unsupported version。

### Phase 3：sidecar 管理 API

目标：让 Tauri 不直接理解剪贴板同步细节，只管理 sidecar。

任务：

- 增加轻量 HTTP 管理 API。
- 支持读取状态、启动/停止同步、读取/保存配置、最近日志。
- sidecar 启动时输出 ready 标记，便于 Tauri 判断启动成功。
- 日志落盘，路径在配置中可查。

交付物：

- 用 curl 可以完成桌面 UI 将来要做的所有管理动作。
- sidecar 可以独立运行，便于调试。

### Phase 4：Tauri 桌面壳

目标：创建可用桌面应用。

任务：

- 初始化 `src-tauri` 和前端工程。
- 实现主窗口：状态页、配置页、日志页。
- 实现托盘：显示/隐藏窗口、启动/停止同步、打开接收目录、退出。
- Rust 后端实现 sidecar 启停、状态轮询、事件转发。
- 配置 Tauri capabilities，仅暴露必要权限。
- 本地开发支持一键启动：前端 dev server + Tauri + sidecar。

交付物：

- macOS 上可运行 Tauri dev app。
- 主窗口能编辑配置并控制同步。
- 退出应用后 sidecar 不残留。

### Phase 5：打包与跨平台验收

目标：生成可安装桌面包，并在 macOS/Windows 验收核心场景。

任务：

- macOS：生成 `.dmg` 或 `.app`，验证托盘、剪贴板权限、退出行为。
- Windows：生成 `.msi` 或 `.exe`，验证防火墙提示、托盘、文件剪贴板。
- 梳理应用图标、名称、版本号、签名策略。
- 建立手工验收清单：
  - 文本 A -> B。
  - 文本 B -> A。
  - 截图/图片 A -> B。
  - 小文件 A -> B。
  - 超限文件提示。
  - 停止同步后不发送。
  - 退出后无残留进程。

交付物：

- macOS 和 Windows 安装包。
- 一份 release checklist。

### Phase 6：发布体验增强

目标：从“可用”走向“好用”。

任务：

- 增加设备发现，例如 mDNS/Bonjour 或局域网扫描。
- 增加配对流程，自动交换节点信息或引导用户复制共享密钥。
- 增加自动更新。
- 增加开机自启动。
- 增加多 peer 同步策略。
- 增加传输历史与失败重试。

交付物：

- Beta 发布版本。
- README 更新为桌面应用使用指南。

## 推荐里程碑

### M1：核心可测试

预计工作量：2 到 4 天。

完成标志：

- 模板测试清理。
- 配置、fingerprint、图片转换有测试。
- `lein test` 通过。

### M2：协议安全改造

预计工作量：4 到 7 天。

完成标志：

- Java 对象反序列化移除。
- HMAC 认证完成。
- localhost 集成测试通过。

### M3：sidecar 服务化

预计工作量：3 到 5 天。

完成标志：

- 可启动/停止。
- 有 `/status`、`/config`、`/logs/recent`。
- Tauri 可通过 HTTP 管理它。

### M4：Tauri MVP

预计工作量：5 到 8 天。

完成标志：

- 桌面窗口、托盘、配置页、状态页可用。
- sidecar 随 app 启停。
- macOS 本地可打包。

### M5：Windows 验收与发布准备

预计工作量：3 到 6 天。

完成标志：

- Windows 包可安装。
- 双端互传通过手工验收。
- 文档和 release checklist 完成。

## 第一轮具体任务清单

建议从下面 10 个任务开始，不要先创建 Tauri 空壳：

1. 清理模板测试，使 `lein test` 通过。
2. 新建 `lan-clip.config`，集中默认配置、读取、校验。
3. 新建 `lan-clip.fingerprint`，把 `ClipboardData` 和 MD5 判断移出 `core.clj`。
4. 抽象 `lan-clip.clipboard`，封装读取/写入文本、图片、文件列表。
5. 给 watcher 增加可停止机制。
6. 新建 `lan-clip.app`，提供 `start!`、`stop!`、`status`。
7. 新建 `lan-clip.protocol`，先实现 text message 的新协议和 HMAC。
8. 替换 Netty object codec 的文本链路，跑通 localhost 文本同步。
9. 再迁移 image 和 files 链路。
10. 加入 HTTP 管理 API，为 Tauri 做准备。

做到第 10 步后，再初始化 Tauri，会更顺。

## 决策记录

- 第一版桌面端采用 Tauri + Clojure sidecar，不立即重写 Rust 核心。
- 第一版 peer 配置采用手动输入 IP/端口/共享密钥，不立即做自动发现。
- 第一版文件传输只支持大小限制内的一次性传输，不立即做大文件分块。
- 第一版更新机制延后，不阻塞 MVP。
- 第一版 UI 以托盘和设置页为核心，不做复杂剪贴板历史管理。

## 官方资料参考

- Tauri v2 System Tray：<https://v2.tauri.app/learn/system-tray/>
- Tauri v2 Sidecar / Embedding External Binaries：<https://tauri.app/develop/sidecar/>
- Tauri v2 Capabilities：<https://v2.tauri.app/security/capabilities/>
- Tauri v2 Updater：<https://v2.tauri.app/plugin/updater/>

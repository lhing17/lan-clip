# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

lan-clip 是一个局域网剪贴板同步工具，支持在多台设备间互传文本、图片、文件。

- **后端核心**：Clojure + JVM + Netty（TCP 通信）
- **桌面前端**：Tauri v2（Rust 后端）+ React + TypeScript（Vite 构建）
- **项目管理**：Leiningen（Clojure）、npm（前端）、Cargo（Rust）

## 常用命令

### Clojure 后端

```bash
# 启动开发
lein run

# 运行全部测试
lein test

# 运行单个测试命名空间
lein test lan-clip.config-test

# 打包 uberjar
lein uberjar

# 启动 REPL（默认命名空间 lan-clip.core）
lein repl
```

### 前端 / Tauri

前端代码位于 `ui/` 目录下：

```bash
cd ui

# 开发模式（仅前端，不启动 Tauri）
npm run dev

# 启动 Tauri 开发模式（前后端+Rust）
npm run tauri dev

# 前端测试（vitest）
npm run test

# 前端构建
npm run build

# Rust 测试
cargo test
```

### 组合验证

修改涉及多个层时，需依次验证：

```bash
lein test && cd ui && npm run test && cargo test
```

## 代码架构

### 后端核心（`src/lan_clip/`）

| 命名空间 | 职责 |
|---------|------|
| `app.clj` | 统一生命周期入口：`start!` / `stop!` / `status`。整合配置加载、watcher 轮询、Netty server。 |
| `config.clj` | 默认配置、读取 `~/.lan-clip/config.edn`、合并覆盖、校验（端口范围等）。热更新键与需重启键分开管理。 |
| `protocol.clj` | 二进制协议定义：magic + version + header（EDN metadata）+ payload。每条消息含 `message-id`（UUID）、`origin-node-id`、`sender-node-id`，并做 HMAC-SHA256 签名。 |
| `socket/protocol_codec.clj` | Netty 编解码器：4-byte length prefix + `->protocol-encoder` / `->protocol-decoder`，支持文本/图片/文件列表三种 content-type。 |
| `socket/server.clj` | Netty TCP 服务端。`start-server` 返回包含 `:stop!` 的 map。每条消息处理完后立即关闭连接（单消息短连接设计）。 |
| `socket/client.clj` | Netty TCP 客户端。`->Client` record + `run` 方法，负责将剪贴板内容编码后发送到目标主机。 |
| `core.clj` | 剪贴板监听与发送逻辑。`make-clipboard-handler` 生成供 `app/start!` 使用的回调函数。旧版 `lan-clip` 函数已废弃。 |
| `watcher.clj` | 可停止的剪贴板轮询器（`volatile!` + `future-cancel`）。 |
| `clipboard.clj` | 剪贴板抽象协议 `IClipboard`，实现 `SystemClipboard` 和 `FakeClipboard`（测试用）。 |
| `fingerprint.clj` | `ClipboardData` 记录与 `changed?`，基于 flavor + length + MD5 判断内容变化。 |
| `message_cache.clj` | LRU 消息缓存（atom + vector），用于回环控制与去重。 |
| `api.clj` | HTTP 管理 API（http-kit）。端点：`/status`、`/config`、`/sync/start`、`/sync/stop`、`/logs/recent`。供 Tauri 前端调用。 |
| `log.clj` | 日志系统（当前基于 `println`）。 |
| `util.clj` | 工具函数：MD5、图片字节转换、zip 压缩解压等。 |

### 协议栈

Netty pipeline 中的编解码器链路：

```
[LengthFieldBasedFrameDecoder] -> [->protocol-decoder] -> handler
handler -> [->protocol-encoder] -> [LengthFieldPrepender]
```

- `protocol-codec` 负责将 Clojure 数据结构与二进制帧互转。
- `protocol` 负责消息级语义：header（EDN）+ payload + HMAC 校验。

### 前端（`ui/src/`）

| 文件 | 职责 |
|------|------|
| `api.ts` | EDN 解析/序列化、sidecar HTTP 调用、Tauri `invoke` 封装。前后端数据交换格式为 EDN。 |
| `App.tsx` | 主窗口：状态页 / 配置页 / 关于页，tab 导航。 |
| `notifications.ts` | 系统通知封装（`sendNotification`）。 |

### Rust 后端（`ui/src-tauri/src/`）

- `lib.rs`：Tauri 主入口。注册 tray-icon、系统通知、opener 插件。
- Sidecar 管理函数：`sidecar_start`、`sidecar_stop`、`sidecar_status`（当前为占位实现，未实际 spawn Clojure 进程）。
- 自定义命令：`sidecar_port`、`received_files_dir`、`open_directory`。

### 关键设计决策

1. **单消息短连接**：`server.clj` 的 `channelRead` 处理完每条消息后调用 `(.close ctx)`。频繁建连，但简化了状态管理。
2. **回环抑制**：远端写入剪贴板后记录 `last-remote-fingerprint`（在 `app.clj` 中）；`core.clj` 的 `listen-clipboard` 检测到指纹匹配时抑制发送。
3. **图片统一转 PNG**：`protocol-codec` 的 encoder 将 `BufferedImage` 自动转 PNG 字节；decoder 将 PNG 字节还原为 `BufferedImage`。
4. **文件统一转 zip**：文件列表在发送端压缩为 zip payload；接收端解压到临时目录后再写入剪贴板。
5. **剪贴板 flavor 优先级**：文件列表 > 图像 > 字符串。用于抹平 macOS 和 Windows 的系统差异。

## 文档规范（`doc/notes/`）

`doc/notes/` 目录下的全大写字母命名文档是项目的关键管理文档，有明确的编写规范和用途：

### `TODO.md` — 执行清单

- **用途**：按 Phase 组织的任务执行清单，是开发工作的主要依据。
- **格式**：
  - 顶层按 Phase 分组（如 `Phase 1：核心模块化`）。
  - 每项任务前加状态标记：`[ ]` 未开始、`[~]` 进行中、`[x]` 已完成、`[!]` 阻塞。
  - 每个 Phase 末尾有"完成标准"小节。
- **维护**：完成新任务后更新对应条目标记；新增 Phase 或调整顺序时同步更新。

### `PROJECT_NOTES.md` — 极简完成记录

- **用途**：记录每轮 `.claude/loop.md` 闭环的实际交付成果，形成时间线。
- **格式**：每轮闭环结束时追加一行，严格使用格式：
  ```
  #YYYY-MM-DD 完成 XX
  ```
  其中 `XX` 是本轮完成的具体内容摘要。
- **维护**：只追加、不修改历史行；与 `TODO.md` 的 `[x]` 标记保持对应。

### `TECHNICAL_DEBT.md` — 技术债清单

- **用途**：记录代码审查或开发过程中发现的未解决问题，按优先级分级。
- **格式**：
  - 分 `P1`（近期修复，影响功能正确性/可维护性）和 `P2`（长期技术债，不影响当前功能）。
  - 每条技术债包含：位置（文件:行号）、问题描述、建议方案。
- **维护**：修复完成后删除对应条目或标记为已解决；定期审视 P2 项是否升级为 P1。

**编写规范**：
- 这三份文档使用中文编写。
- 保持简洁，只记录决策、状态和债务，不重复代码细节（代码即文档）。
- 更新任何一份时，考虑是否需要同步更新另外两份（如完成 TODO 任务后追加 PROJECT_NOTES 记录）。

## 测试策略

- **Clojure**：`lein test` 运行全部测试。单元测试覆盖 config、fingerprint、clipboard、watcher、app、protocol、codec。集成测试在 `socket/integration_test.clj` 中验证 encoder → decoder 往返。验收测试在 `acceptance_test.clj` 中验证 localhost 双端文本同步与回环抑制。
- **前端**：vitest。`npm run test` 在 `happy-dom` 环境中运行。测试覆盖 `api.ts` 的 EDN 解析/序列化、`App.tsx` 各页渲染、通知逻辑。
- **Rust**：`cargo test`。测试覆盖自定义命令和工具函数。
- 网络/异步测试中大量使用 `Thread/sleep` 做同步，这是已知技术债（见 `TECHNICAL_DEBT.md` #14）。

## 配置

运行时配置位于 `~/.lan-clip/config.edn`（EDN 格式）。关键字段：

| 字段 | 说明 |
|------|------|
| `:port` | 本机监听端口 |
| `:target-host` / `:target-port` | 对端地址 |
| `:secret-key` | HMAC 共享密钥 |
| `:interval` | 剪贴板轮询间隔（毫秒，默认 2000）|
| `:file-size` | 文件大小上限（KB，默认 2048）|
| `:max-frame-size` | 协议最大帧大小（字节，默认 10MB）|
| `:device-name` | 设备名（热更新）|
| `:received-files-dir` | 接收文件存放目录 |

`:node-id` 由系统自动生成并持久化到 `~/.lan-clip/node-id`，通常无需手动配置。

## 安全边界

- 通信使用明文 TCP + HMAC-SHA256 认证（无加密）。
- 服务端 `ObjectEncoder`/`ObjectDecoder` 已替换为自定义二进制协议。
- **切勿**在日志、测试输出、文档中输出真实剪贴板内容（文本/图片/文件名/路径）。调试时使用摘要信息（长度、类型、哈希）。
- 参考 `.claude/agent-policy.md` 的绿/黄/红灯决策分级：修改网络协议、依赖升级、配置结构变更等属于黄灯（执行后需汇报）；对外发布、降低安全基线等属于红灯（必须人工确认）。

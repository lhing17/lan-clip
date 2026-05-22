# lan-clip

跨平台局域网剪贴板同步工具，支持在 macOS 与 Windows 之间互传文本、图片和文件。

## 功能特性

- **剪贴板同步**：文本、图片、文件列表实时同步，剪贴板内容变化后自动推送到对端设备。
- **设备发现**：同一局域网内自动发现其他运行 lan-clip 的设备，无需手动输入 IP。
- **一键配对**：点击"配对"即可自动交换节点信息并生成共享密钥，降低配置门槛。
- **多设备同步**：支持同时向多个已配对设备广播发送剪贴板内容。
- **传输历史**：记录最近 100 次发送/接收事件的元数据（类型、大小、对端、时间），便于追溯。
- **发送重试**：网络抖动时自动重试（默认 3 次），提高传输可靠性。
- **系统托盘**：常驻托盘，支持快捷切换同步、打开接收目录、退出应用。
- **自动更新**：内置更新检测，有新版本时一键下载安装。
- **开机自启动**：可选在系统登录时自动启动。

## 安装

从 [GitHub Releases](https://github.com/lhing17/lan-clip/releases) 下载对应平台的安装包：

| 平台 | 安装包 |
|------|--------|
| macOS | `.dmg` |
| Windows | `.msi` 或 `.exe` |

> **系统要求**：macOS 或 Windows，且系统中已安装 Java 运行时（JRE 11+）。首次启动时如未安装 Java，应用会提示。

### macOS 首次运行

由于当前使用 adhoc 签名，首次运行 `.app` 时 macOS 会弹出"无法验证开发者"安全提示。请在"系统设置 > 隐私与安全性"中手动允许。

## 快速开始

1. **在两台设备上安装并启动 lan-clip**。
2. **设备发现**：打开应用，进入"配置"页，等待"已发现设备"列表刷新，应能看到对端设备。
3. **配对**：点击对端设备的"配对"按钮。配对成功后，目标主机、端口和共享密钥将自动填充。
4. **启动同步**：返回"状态"页，点击"启动同步"。此时任意一台设备复制内容，另一台即可粘贴。
5. **查看历史**：进入"历史"页，查看最近的传输记录。

## 配置

配置文件位于 `~/.lan-clip/config.edn`（EDN 格式），也可通过应用内的"配置"页编辑。

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `:device-name` | 设备名称（显示给对端） | `""` |
| `:port` | 本机监听端口 | `9615` |
| `:target-host` / `:target-port` | 对端地址（配对后自动填充） | — |
| `:secret-key` | HMAC 共享密钥（配对后自动生成） | — |
| `:interval` | 剪贴板轮询间隔（毫秒） | `2000` |
| `:file-size` | 文件大小上限（KB） | `2048` |
| `:max-frame-size` | 协议最大帧大小（字节） | `10485760`（10MB）|
| `:received-files-dir` | 接收文件存放目录 | `~/.lan-clip/received-files` |
| `:retry-count` | 发送失败重试次数 | `3` |
| `:retry-delay-ms` | 重试间隔（毫秒） | `1000` |

> `:node-id` 由系统自动生成并持久化到 `~/.lan-clip/node-id`，通常无需手动配置。

## 开发

### 技术栈

- **后端核心**：Clojure + JVM + Netty（TCP 通信）
- **桌面前端**：Tauri v2（Rust 后端）+ React + TypeScript（Vite 构建）
- **项目管理**：Leiningen（Clojure）、npm（前端）、Cargo（Rust）

### 开发环境要求

- JDK 11+
- Leiningen 2.9.3+
- Node.js 20+
- Rust + Cargo

### 启动开发

```bash
# 启动 Clojure 后端（sidecar）
lein run

# 前端开发（仅前端，不启动 Tauri）
cd ui && npm run dev

# Tauri 开发模式（前后端 + Rust）
cd ui && npm run tauri dev
```

### 测试

```bash
# Clojure 测试
lein test

# 前端测试
cd ui && npm run test

# Rust 测试
cd ui/src-tauri && cargo test

# 组合验证
lein test && cd ui && npm run test && cargo test
```

### 构建

```bash
# 打包 Clojure uberjar
lein uberjar

# 构建桌面安装包（macOS / Windows）
cd ui && npm run tauri build
```

CI 已配置 GitHub Actions 自动构建，推送代码后可在 Actions 页面下载产物。

## 架构

```text
┌─────────────────────────────────────────┐
│           Tauri Desktop App             │
│  ┌──────────────┐  ┌─────────────────┐  │
│  │  Frontend UI │  │ Tauri Rust Host │  │
│  │  (React/TS)  │  │  Tray/Updater   │  │
│  └──────┬───────┘  └────────┬────────┘  │
└─────────┼───────────────────┼───────────┘
          │  HTTP localhost   │
┌─────────┼───────────────────┼───────────┐
│         ▼                   ▼           │
│      lan-clip-core sidecar (Clojure)    │
│         Clipboard Watcher               │
│         Sync Engine                     │
│         Peer Transport (Netty)          │
└─────────────────────────────────────────┘
```

Tauri 前端通过 localhost HTTP 管理 API 与 Clojure sidecar 通信。剪贴板监听、内容编码、网络传输和接收写入由 sidecar 负责；桌面 UI、系统托盘、窗口管理和自动更新由 Tauri 负责。

## 协议与安全

- **传输协议**：自定义二进制协议（magic + version + EDN metadata + payload），基于 Netty TCP。
- **消息认证**：每条消息携带 HMAC-SHA256 签名，使用共享密钥 `:secret-key` 计算。服务端拒绝 HMAC 校验失败的消息。
- **回环抑制**：远端写入剪贴板后记录指纹，本地轮询识别到相同指纹时抑制发送，避免内容在设备间无限循环。
- **Payload 上限**：服务端限制最大帧大小（默认 10MB），超限消息立即拒绝。
- **消息去重**：基于 `message-id`（UUID）的 LRU 缓存，防止重复处理同一条消息。

> **注意**：当前通信仍为明文 TCP（无加密），请仅在可信局域网内使用。

## 安全提示

- **仅在可信局域网内运行**，不要让监听端口对公网或不可信网络开放。
- 不要使用本工具同步密码、Token、机密文档等高敏感剪贴板内容。
- 修改 `~/.lan-clip/config.edn` 时，不要把真实局域网 IP 或共享密钥提交到公共仓库。

## License

Copyright (C) 2022 Hao Liang

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.

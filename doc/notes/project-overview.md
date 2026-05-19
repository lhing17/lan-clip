# lan-clip 工程扫描总结

扫描时间：2026-05-19

## 项目定位

lan-clip 是一个跨平台局域网剪贴板同步工具。它在多台处于同一局域网的电脑上各启动一个 JVM 进程，通过轮询本机系统剪贴板发现变化，再把剪贴板内容经 TCP 发送到目标机器，由目标机器写入自己的系统剪贴板，从而实现类似“统一剪贴板”的体验。

当前实现重点覆盖三类常见剪贴板内容：

- 文本：直接传输字符串。
- 图片：把 `java.awt.Image` 转为 PNG 字节数组传输。
- 文件列表：读取文件内容，按文件名和字节数组传输到目标机器临时目录后设置到剪贴板。

README 中说明该项目已在 macOS 和 Windows 10 上验证过基础能力。

## 技术栈

- 语言与运行时：Clojure 1.11.1，JVM，入口命名空间为 `lan-clip.core`。
- 构建工具：Leiningen，配置文件为 `project.clj`。
- 网络通信：Netty 4.1.76.Final，基于 TCP 连接，使用 Netty `ObjectEncoder` / `ObjectDecoder` 序列化 JVM 对象。
- 剪贴板操作：JDK AWT `Toolkit`、`Clipboard`、`DataFlavor`、`Transferable`。
- 图片处理：AWT `Image` / `BufferedImage` 与 `javax.imageio.ImageIO`。
- 文件工具：`clojure.java.io` 与 Apache Commons IO。
- 摘要计算：Apache Commons Codec `DigestUtils`，用 MD5 判断剪贴板内容是否变化。
- GUI 相关依赖：`seesaw`、`flatlaf`、`cljfx` 已声明，但当前源码没有实际 UI 实现。
- REPL/调试：`nrepl` 依赖已声明，`project.clj` 中还保留了 Reveal profile。

## 目录结构

```text
.
├── README.md
├── CHANGELOG.md
├── LICENSE
├── project.clj
├── resources/
│   └── config.edn
├── src/
│   └── lan_clip/
│       ├── core.clj
│       ├── util.clj
│       └── socket/
│           ├── client.clj
│           ├── content.clj
│           └── server.clj
├── test/
│   └── lan_clip/
│       └── core_test.clj
└── doc/
    ├── intro.md
    └── notes/
        └── project-overview.md
```

代码规模较小，当前主要源码约 450 行，核心逻辑集中在 `core.clj`、`util.clj` 和 `socket` 包。

## 核心模块

### `lan-clip.core`

`core.clj` 是主流程入口，负责监听本地剪贴板并触发发送。

主要职责：

- 定义剪贴板内容优先级：文件列表 > 图片 > 文本。
- 通过 `handle-flavor` multimethod 按内容类型处理发送逻辑。
- 使用 `ClipboardData` 记录剪贴板内容的类型、长度和 MD5 摘要。
- 每隔固定时间读取剪贴板，判断是否发生变化。
- 启动本机 Netty server 接收对端数据。
- 读取 `~/.lan-clip/config.edn`，并与默认配置合并。

默认配置行为：

- 本机服务端口：`9002`。
- 目标主机：`localhost`。
- 目标端口：`9002`。
- 剪贴板轮询间隔：`2000` 毫秒。
- 文件大小限制默认在监听逻辑中为 `2048` KB，资源配置中示例为 `4096` KB。

### `lan-clip.util`

`util.clj` 是通用工具层，负责时间轮询、图片转换、MD5、剪贴板 Transferable 包装和配置读取。

主要能力：

- `set-interval`：用 `future` 和无限循环实现类似 JavaScript `setInterval` 的定时执行。
- `buffered-image`：把跨平台来源不同的 `java.awt.Image` 转为 `BufferedImage`。
- `image->bytes` / `bytes->image`：图片和 PNG 字节数组之间互转。
- `Digestable` protocol：为 byte array、字符串、输入流、图片、文件、序列等提供统一 MD5 计算。
- `ImageTransferable`：把图片写入系统剪贴板时使用。
- `FileListTransferable`：把文件列表写入系统剪贴板时使用。
- `read-edn`：从用户目录 `~/.lan-clip/config.edn` 读取配置；如果不存在，则从 `resources/config.edn` 复制默认配置。

### `lan-clip.socket.content`

`content.clj` 定义传输消息对象：

- `Content` 是一个可序列化类型，字段为 `type` 和 `content`。
- `type` 保存原始内容的 JVM 类型，例如 `String`、图片类型或文件列表类型。
- `content` 保存实际传输内容，例如字符串、图片字节数组或文件元组列表。

该类型会在客户端和服务端之间经 Netty 对象序列化传输，因此 AOT 编译任务里显式包含了 `lan-clip.socket.content`。

### `lan-clip.socket.client`

`client.clj` 负责发起到目标机器的 TCP 连接并发送剪贴板内容。

主要流程：

- `->msg` 把本地剪贴板内容包装成 `Content`：
  - 图片：转为 PNG 字节数组。
  - 文本：保持字符串。
  - 文件列表：每个文件转换为 `[文件名, 文件字节数组]`。
- `content-handler` 在 channel active 时写出消息。
- `Client` record 实现 `RunnableClient`，每次发送创建一个 Netty `NioEventLoopGroup`，连接成功后等待连接关闭，再关闭 event loop。

### `lan-clip.socket.server`

`server.clj` 负责启动 TCP server 并把收到的内容写入本机系统剪贴板。

主要流程：

- 使用 Netty `ServerBootstrap` 监听配置端口。
- 使用 `ObjectDecoder` 解码客户端发来的 `Content` 对象。
- `handle-msg` multimethod 根据 `Content.type` 写剪贴板：
  - `String`：通过 `StringSelection` 设置文本。
  - `Image`：从字节数组还原图片，再用 `ImageTransferable` 设置图片。
  - `List`：把文件写入当前工作目录下的 `tmp` 文件夹，再用 `FileListTransferable` 设置文件列表。
  - `ByteBuf`：打印字节内容并丢弃，像是调试或兼容分支。

## 数据流

本机复制内容后的发送链路：

```text
系统剪贴板
  -> 定时轮询读取
  -> 根据 DataFlavor 选择文件/图片/文本
  -> 生成 ClipboardData 摘要并与上次缓存比较
  -> 内容变化时构造 Client
  -> 包装成 Content
  -> Netty TCP 发送到目标机器
```

目标机器接收后的写入链路：

```text
Netty Server 接收 Content
  -> ObjectDecoder 反序列化
  -> 根据 Content.type 分派处理
  -> 文本/图片/文件列表转换为 Transferable
  -> 写入目标机器系统剪贴板
```

## 功能特性

- 跨平台：依赖 JVM 与 JDK AWT 剪贴板 API，目标是 macOS / Windows 等桌面系统。
- 局域网同步：通过配置目标 IP 和端口，在两台机器之间同步剪贴板。
- 双向能力：每台机器同时运行监听器和服务端，因此两端都可作为发送方和接收方。
- 内容去重：通过内容类型、长度和 MD5 摘要判断剪贴板是否变化，避免重复发送同一份内容。
- 内容优先级：为解决 macOS 和 Windows 对同一剪贴板内容映射差异，选择文件列表 > 图片 > 文本。
- 小文件传输：文件总大小小于限制时，会直接读取文件字节并发送给目标机器。
- 用户配置自举：首次运行会把 classpath 中的 `config.edn` 复制到 `~/.lan-clip/config.edn`。

## 配置说明

项目资源中的示例配置：

```clojure
{:target-host "192.168.1.181"
 :file-size 4096}
```

运行时读取位置：

```text
~/.lan-clip/config.edn
```

支持或代码中出现的配置项：

- `:port`：本机 Netty server 监听端口，默认 `9002`。
- `:target-host`：目标机器 IP 或主机名，默认 `localhost`。
- `:target-port`：目标机器监听端口，默认 `9002`。
- `:interval`：剪贴板轮询间隔，默认 `2000` 毫秒。
- `:file-size`：文件列表总大小上限，单位为 KB。超过限制时不会发送文件。

## 构建与运行

开发运行：

```shell
lein run
```

打包 jar：

```shell
lein uberjar
```

README 中还记录了使用 JDK `jpackage` 生成 macOS dmg 和 Windows msi 的示例命令。

## 当前工程状态

- `README.md` 对产品背景、原理、使用方式和开发方式描述较完整。
- `doc/intro.md` 仍是 Leiningen 模板生成的占位内容。
- `CHANGELOG.md` 仍是模板示例内容，尚未反映 lan-clip 的真实变更历史。
- `project.clj` 中 `:description` 和 `:url` 仍是 `FIXME` 模板值。
- `test/lan_clip/core_test.clj` 是模板失败用例，当前测试并不代表项目真实质量门禁。
- `seesaw`、`flatlaf`、`cljfx` 等 UI 依赖已存在，但当前未看到 UI 层源码。
- `server.clj` 顶层读取了一次配置到私有 var `config`，但实际 server 启动和消息处理并未使用该 var。
- 仓库中存在 `target`、`.idea`、`.lsp`、`tmp` 等本地或生成目录；`.gitignore` 已包含部分忽略规则。

## 风险与待完善点

- 安全性：当前网络层直接使用 Java 对象反序列化，且没有认证、加密或来源校验。只适合可信局域网环境。
- 连接模型：每次剪贴板变化都会创建新的 Netty client event loop，频繁复制时可能有额外资源开销。
- 剪贴板回环：A 发送给 B 后，B 写入剪贴板会触发 B 自己的监听并可能再发回 A。当前依赖内容摘要去重缓解，但跨端回环和竞态仍值得专门验证。
- 大文件处理：超过 `:file-size` 的文件仅打印提示，不提供降级方案、用户反馈 UI 或传输队列。
- 文件目录：接收文件统一写入当前工作目录下 `tmp`，可能受启动目录影响，也可能与用户期望的下载/临时目录不一致。
- 文件名冲突：同批或连续接收同名文件时会覆盖 `tmp` 中文件。
- 图片格式：图片统一转 PNG 字节传输，可保证通用性，但可能丢失部分原始格式信息。
- 配置变更：监听逻辑每轮会重新读取配置，但 server 监听端口只在启动时生效。
- 测试覆盖：目前没有有效自动化测试覆盖剪贴板摘要、消息包装、图片转换、文件传输或 Netty 收发。

## 建议后续方向

- 替换或约束 Java 对象反序列化协议，例如改为明确的二进制帧、JSON/EDN 元数据加 payload，或至少加入白名单解析。
- 增加节点身份、共享密钥或局域网配对机制，避免非预期机器写入剪贴板。
- 给传输消息增加来源 ID、消息 ID 和时间戳，更可靠地避免回环。
- 将文件接收目录改为可配置，并保留原始目录结构或处理文件名冲突。
- 补充核心单元测试：`best-fit-flavor`、`ClipboardData` 摘要、`->msg`、图片字节转换、配置读取。
- 补充集成测试或手工验收脚本：本机 localhost 双进程文本/图片/文件传输。
- 清理模板残留：更新 `project.clj` 元信息、`CHANGELOG.md` 和 `doc/intro.md`。
- 如果计划做桌面产品化，可基于现有 UI 依赖实现托盘图标、连接状态、配置面板和传输提示。

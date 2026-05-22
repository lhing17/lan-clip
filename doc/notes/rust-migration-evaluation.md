# Clojure Sidecar → Rust/Tauri 后端迁移评估

> 评估日期：2026-05-21
> 评估范围：当前基于 Clojure sidecar + Tauri 前端的双进程架构，是否应迁移为纯 Rust/Tauri 单进程架构。

---

## 一、当前架构概览

### 1.1 职责划分

| 层级 | 语言 | 职责 | 代码规模（估算） |
|------|------|------|----------------|
| Tauri 前端 | Rust + TS/React | GUI、系统托盘、系统通知、自动更新、开机自启 | ~300 行 Rust + ~750 行 TS |
| Clojure Sidecar | Clojure + JVM | 剪贴板监听、TCP 传输（Netty）、UDP 发现、HTTP API、配置管理、传输历史 | ~1200 行 Clojure |
| 通信桥 | HTTP (EDN) | 前端通过 localhost:9615 调用 sidecar REST API | — |

### 1.2 Clojure Sidecar 核心模块

```
src/lan_clip/
├── core.clj          # 剪贴板监听 + 发送调度（多 peer 广播）
├── app.clj           # 生命周期管理（start!/stop!）
├── config.clj        # EDN 配置读写、校验
├── protocol.clj      # 二进制协议（magic + HMAC-SHA256）
├── socket/
│   ├── server.clj    # Netty TCP 服务端
│   ├── client.clj    # Netty TCP 客户端 + 重试
│   └── protocol_codec.clj  # 编解码器（length-prefix + 自定义协议）
├── discovery.clj     # UDP 广播 beacon + 配对消息
├── fingerprint.clj   # 剪贴板指纹（MD5 + flavor + length）
├── history.clj       # 传输历史环形缓冲区
├── message_cache.clj # LRU 消息去重缓存
├── watcher.clj       # 剪贴板轮询器（volatile! + future）
├── api.clj           # HTTP API（http-kit）
├── clipboard.clj     # 剪贴板抽象协议
├── util.clj          # MD5、图片转字节、zip 压缩
└── log.clj           # 日志系统
```

### 1.3 关键外部依赖

- **Netty 4.1.76** — TCP 服务端/客户端、编解码器框架
- **http-kit** — HTTP API 服务器
- **AWT (java.awt.datatransfer)** — 剪贴板读写（跨平台差异由 flavor 优先级抹平）
- **commons-io / commons-codec** — IO 工具、Base64/Hex

### 1.4 当前包体积

| 产物 | 大小 | 说明 |
|------|------|------|
| uberjar | 61 MB | 含 Clojure + Netty + 所有依赖 |
| Tauri .app | ~70 MB | 含 Rust 二进制 + Web 资源 |
| .dmg | ~61-98 MB | 安装包 |

**关键问题**：当前 sidecar 通过 `java -jar` 启动，**要求用户系统已安装 JVM**。Tauri 应用包本身不携带 JRE。

---

## 二、迁移方案分析

### 2.1 方案 A：完全迁移（Rust 重写全部 sidecar 逻辑）

将 Clojure sidecar 的全部功能迁移到 Tauri 的 Rust 后端，前端直接通过 Tauri `invoke` 调用 Rust 命令。

#### 可行性矩阵

| 功能 | Clojure 实现 | Rust 替代方案 | 复杂度 | 风险 |
|------|-------------|--------------|--------|------|
| 剪贴板文本读写 | AWT `StringSelection` | `arboard` crate | 低 | 低 |
| 剪贴板图片读写 | AWT `ImageTransferable` | `arboard` + `image` crate | 中 | 中 |
| 剪贴板文件列表 | AWT `FileListTransferable` | 平台 API 直接调用（无成熟跨平台库） | **高** | **高** |
| TCP 服务端 | Netty `ServerBootstrap` | `tokio::net::TcpListener` + 自定义 codec | 中 | 中 |
| TCP 客户端 | Netty `Bootstrap` | `tokio::net::TcpStream` | 低 | 低 |
| 长度前缀编解码器 | Netty `LengthFieldBasedFrameDecoder` | 手动实现（tokio 无内置） | 中 | 中 |
| 二进制协议编码 | `ByteBuffer` 手动组装 | `bytes` crate | 低 | 低 |
| HMAC-SHA256 | `javax.crypto.Mac` | `hmac` + `sha2` (RustCrypto) | 低 | 低 |
| UDP 广播发现 | `DatagramSocket` | `tokio::net::UdpSocket` | 低 | 低 |
| HTTP API | http-kit | `axum` 或嵌入 Tauri 命令 | 低 | 低 |
| 配置持久化 | EDN 文件 | `toml` / `serde_json` + 标准文件 IO | 低 | 低 |
| 图片转 PNG 字节 | `javax.imageio.ImageIO` | `image` crate | 低 | 低 |
| zip 压缩/解压 | `java.util.zip` | `zip` crate | 低 | 低 |
| 剪贴板轮询 | `future` + `Thread/sleep` | `tokio::time::interval` | 低 | 低 |

#### 工作量估算

| 模块 | 预估 Rust 代码量 | 测试代码量 | 人天 |
|------|-----------------|-----------|------|
| 协议编解码器 | ~300 行 | ~200 行 | 3-4 |
| TCP 服务端 | ~200 行 | ~150 行 | 2-3 |
| TCP 客户端 + 重试 | ~150 行 | ~100 行 | 2 |
| UDP 发现 + 配对 | ~250 行 | ~150 行 | 2-3 |
| 剪贴板监听（文本/图片） | ~200 行 | ~150 行 | 3-4 |
| 剪贴板文件列表 | ~300 行 | ~100 行 | 5-7 |
| HTTP API → Tauri 命令 | ~150 行 | ~100 行 | 2 |
| 配置管理 | ~150 行 | ~100 行 | 2 |
| 历史/指纹/缓存 | ~200 行 | ~150 行 | 2-3 |
| 系统集成调试 | — | — | 5-7 |
| **合计** | **~1900 行** | **~1200 行** | **26-35 天** |

#### 优点

1. **单二进制分发**：用户无需安装 JVM，真正的"开箱即用"
2. **启动更快**：无 JVM 冷启动开销
3. **包体积可能更小**：去掉 61MB uberjar + JVM，预计最终 .app 可控制在 30-50MB
4. **统一技术栈**：Rust 前后端一致，减少心智负担
5. **更精细的内存控制**：无 JVM GC 停顿

#### 缺点

1. **重写成本极高**：26-35 人天的开发 + 测试 + 调试，期间无法并行开发新功能
2. **剪贴板文件列表风险最大**：AWT 的 `FileListTransferable` 在 macOS 和 Windows 上行为经过长期验证。Rust 的 `arboard` 截至 2024 年对文件列表的支持**非常有限**，可能需要直接调用平台 API（macOS `NSPasteboard` / Windows `IDataObject`），引入大量平台特定代码
3. **协议互操作性风险**：必须确保 Rust 编码器与现有 Clojure 解码器 100% 兼容，否则导致与老版本无法互通
4. **丢失 REPL**：Clojure 的交互式开发对调试剪贴板行为极其宝贵
5. **测试覆盖率归零**：现有 140 个 Clojure 测试需全部重写为 Rust 测试
6. **AWT 跨平台差异**：当前通过 flavor 优先级（文件 > 图像 > 字符串）抹平了 macOS 和 Windows 的差异。Rust 生态没有等价的 battle-tested 抽象

---

### 2.2 方案 B：渐进式迁移（只替换 JVM，保留 Clojure）

使用 **GraalVM native-image** 将 Clojure sidecar 编译为原生二进制，Tauri 直接 spawn 原生进程而非 `java -jar`。

#### 可行性

- GraalVM native-image 支持 Clojure（有成功案例）
- Netty 有 native-image 配置
- 但 AWT/Swing 反射 heavy，native-image 需要大量 `--initialize-at-build-time` 和反射配置
- 剪贴板操作涉及 JNI（Java Native Interface），native-image 可能无法正确处理

#### 结论

**不推荐**。AWT 剪贴板 + Netty 的组合在 native-image 下的配置复杂度极高，且每次 Netty 版本升级都可能破坏 native-image 配置。稳定性风险大于收益。

---

### 2.3 方案 C：jlink 精简 JRE（短期最优）

使用 `jlink` 生成最小化 JRE（仅包含运行 uberjar 所需的模块），将 JRE + uberjar 打包进 Tauri 应用资源。

#### 可行性

- Java 9+ 的 `jlink` 可以裁剪 JRE 到 ~30-50MB
- Clojure 运行时主要依赖 `java.base`、`java.desktop`（AWT）、`java.net`、`java.crypto`
- Tauri 的 `bundle.resources` 已经支持嵌入外部文件
- `sidecar_start` 改为调用 `<资源目录>/bin/java -jar` 即可

#### 工作量

| 任务 | 人天 |
|------|------|
| 编写 jlink 脚本（模块分析 + 裁剪） | 1 |
| 修改 Tauri 构建流程（CI 中集成 jlink） | 1-2 |
| 修改 `lib.rs` 优先使用 bundle 内的 JRE | 0.5 |
| 测试 macOS / Windows 启动 | 1 |
| **合计** | **3-4.5 天** |

#### 优点

1. **工作量最小**：几天即可解决"用户需自行安装 Java"的最大痛点
2. **零功能风险**：Clojure 代码完全不变
3. **可控的包体积**：精简 JRE + uberjar 预计 40-60MB，与当前持平或略小
4. **可复用 CI**：GitHub Actions 中增加 jlink 步骤即可

#### 缺点

1. 仍然不是单二进制（JRE 作为资源目录中的目录存在）
2. 启动速度仍有 JVM 开销
3. 需要为每个平台生成对应的 jlink JRE（macOS x64/ARM、Windows x64）

---

### 2.4 方案 D：维持现状 + 文档告知

不迁移，仅在 README 和安装说明中明确告知用户需安装 Java 17+。

#### 适用场景

- 目标用户群体为开发者或技术爱好者（愿意安装 JVM）
- 短期内无资源进行任何改造

#### 当前问题

- 普通用户遇到 `"java" 命令未找到` 时体验极差
- macOS Gatekeeper + 未签名二进制 + JVM 要求 = 三重安装障碍

---

## 三、关键风险对比

| 风险项 | 方案 A（全迁移） | 方案 C（jlink） | 方案 D（现状） |
|--------|----------------|----------------|---------------|
| 开发周期 | 1-2 个月 | 1 周 | 0 |
| 功能回退风险 | **高**（剪贴板文件列表） | 无 | 无 |
| 跨平台一致性风险 | **高** | 无 | 无 |
| 用户安装门槛 | 最低（单 .app/.exe） | 低（自包含） | 高（需装 JVM） |
| 包体积 | ~30-50MB（最优） | ~60-80MB | ~70MB + JVM 安装 |
| 长期维护成本 | 低（统一栈） | 中（JVM + Rust 双栈） | 中 |
| 协议兼容性 | 需严格验证 | 无变化 | 无变化 |

---

## 四、推荐决策

### 短期（1-2 周）：实施方案 C（jlink 精简 JRE）

**理由**：
- 以最小工作量解决当前最大的用户体验障碍（Java 依赖）
- 为后续可能的 Rust 迁移争取时间，同时保持功能稳定
- 包体积可控，安装体验接近原生应用

**实施路径**：
1. 分析 uberjar 运行时所需的最小 Java 模块集合
2. 编写 `scripts/build-jre.sh`（macOS/Windows/Linux 三平台）
3. 修改 `tauri.conf.json` 将 JRE 目录加入 `bundle.resources`
4. 修改 `lib.rs` `resolve_jar_path` → `resolve_java_path`，优先使用 bundle 内 JRE
5. 更新 CI workflow，在 `lein uberjar` 后执行 jlink
6. 验证 `.app` 在无系统 JVM 的干净环境中可启动

### 中期（1-3 个月）：Rust 剪贴板技术预研

**理由**：
- 方案 A 的最大障碍是 Rust 剪贴板生态不成熟
- 需要独立验证 `arboard` 在 macOS/Windows 上处理文本/图片/文件列表的行为
- 如果预研结果乐观，可启动正式迁移设计

**预研任务**：
1. 编写独立 Rust CLI 原型，验证以下场景：
   - macOS：复制文本/图片/文件（Finder）→ 程序正确读取
   - Windows：同上
   - flavor 优先级是否可复现（文件 > 图像 > 字符串）
2. 验证 `arboard` 的图片格式兼容性（PNG、JPEG、TIFF 等）
3. 验证 Rust `tokio` TCP 服务端与现有 Clojure 客户端的协议互通

### 长期（3-6 个月后评估）：决定是否启动方案 A

**启动条件**（需同时满足）：
1. Rust 剪贴板预研通过（文件列表可稳定支持）
2. 有充足的开发资源（2-3 周专注投入）
3. 当前版本无紧急功能需求

**不推荐立即启动方案 A 的理由**：
- 当前 Clojure 后端已稳定运行，140 个测试覆盖核心链路
- Phase 7（跨平台验收）尚未完成，此时进行大规模重写会导致验收基准动摇
- 剪贴板跨平台行为是隐性知识密集区，Rust 重写可能引入难以发现的回归

---

## 五、附录：Rust 生态调研

### 5.1 剪贴板

| Crate | 文本 | 图片 | 文件列表 | 成熟度 |
|-------|------|------|----------|--------|
| `arboard` | 支持 | 支持（跨平台） | **不支持** | 高 |
| `clipboard-rs` | 支持 | 部分支持 | 部分支持 | 中 |
| `copypasta` (alacritty) | 支持 | 不支持 | 不支持 | 中 |
| 平台 API 直接 | 支持 | 支持 | 支持 | 需自行封装 |

结论：文件列表必须走平台 API（macOS `NSPasteboard` / Windows `ole32` / Linux `x11`/`wayland`），无现成跨平台库。

### 5.2 异步网络

| Crate | 用途 | 备注 |
|-------|------|------|
| `tokio` | 运行时 + TCP/UDP | 事实标准 |
| `tokio-util::codec` | 长度前缀编解码器 | 需自行实现 `Decoder`/`Encoder` |
| `axum` | HTTP API | 可替代 http-kit |
| `serde` + `serde_json` / `toml` | 配置序列化 | 需 EDN → JSON/TOML 迁移 |

### 5.3 加密/哈希

| Crate | 用途 |
|-------|------|
| `sha2` + `hmac` | HMAC-SHA256（与现有兼容） |
| `md-5` | MD5（剪贴板指纹） |
| `uuid` | UUID 生成与解析 |

---

## 六、结论

| 维度 | 评估结果 |
|------|----------|
| 迁移必要性 | 中（JVM 依赖影响用户体验，但功能稳定） |
| 迁移可行性 | 中（技术可行，但剪贴板文件列表是硬骨头） |
| 推荐方案 | **短期 jlink（方案 C）+ 中期 Rust 预研** |
| 不建议立即全量迁移 | 成本过高、风险过大、收益不够紧迫 |

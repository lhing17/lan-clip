# Change Log

本文件记录 lan-clip 的所有重要变更，遵循 [keepachangelog.com](http://keepachangelog.com/) 的约定。

版本号遵循语义化版本（Semantic Versioning）。

## [Unreleased]

### Added

- 新增 `lan-clip.config` 命名空间，集中默认配置（`:port` / `:target-host` / `:target-port` / `:file-size` / `:interval`）、读取、合并与校验；附 6 个 `lein test` 用例覆盖默认值、默认 host、缺失文件、自定义覆盖、非法端口与默认配置自洽。
- 新增 `lan-clip.fingerprint` 命名空间，迁移 `ClipboardData` 记录与内容摘要判断（`fingerprint`、`changed?`）；附 6 个 `lein test` 用例覆盖文本、图片字节、文件列表、类型变化、内容变化与相同内容。
- 新增 `lan-clip.clipboard` 命名空间，定义 `IClipboard` 可替换协议，提供 `SystemClipboard`（真实系统剪贴板）与 `FakeClipboard`（测试替身）两种实现；附 6 个 `lein test` 用例覆盖空状态、文本/图片/文件列表读写、`available-flavors` 与协议实现检查。
- 新增 `lan-clip.watcher` 命名空间，提供可停止的 `start-watcher` / `stop-watcher`（基于 `volatile!` 运行标志与 `future-cancel` 中断），替代 `util.clj` 中不可停止的 `set-interval`；附 4 个 `lein test` 用例覆盖回调执行、周期性、可停止与幂等停止。
- 新增 `lan-clip.app` 命名空间，提供应用生命周期管理 `start!` / `stop!` / `status`，整合配置加载（`lan-clip.config`）与 watcher 启动/停止；附 3 个 `lein test` 用例覆盖启动停止循环、默认配置、状态反映。Netty server 整合留待 Phase 1 P1。
- 新增 `lan-clip.protocol` 命名空间，定义显式二进制协议（magic `0x4C434C50` / version / UUID 字段 / content-type / metadata-length / payload-length）与 HMAC-SHA256 认证，替代 Java 对象序列化；附 6 个 `lein test` 用例覆盖文本往返、HMAC 成功/失败、magic/version 错误、截断消息拒绝。metadata 第一版使用 EDN，避免新增外部依赖。
- 新增 `lan-clip.socket.protocol-codec` 命名空间，提供 Netty 编解码器 `encode-frame`（4-byte length prefix）、`->protocol-encoder`（String → protocol frame）、`->protocol-decoder`（length-prefixed frame → Message），为替换 `ObjectEncoder` / `ObjectDecoder` 做准备；附 6 个 `lein test` 用例覆盖 frame 长度前缀、encoder 输出有效性、decoder 完整/半包读取、HMAC 拒绝、编解码往返。

- `lan-clip.config` 默认配置增加 `:secret-key`（默认 `"lan-clip"`），为 HMAC 签名提供共享密钥。
- `lan-clip.socket.server` 移除 `ObjectDecoder`，接入 `->protocol-decoder`；`handle-msg` 按 `Message` 的 `:content-type` 分发；文本链路可直接运行，图片/文件链路打印未实现提示待后续迁移。
- `lan-clip.socket.client` 移除 `ObjectEncoder`/`ObjectDecoder` 与 `Content` 类型依赖，接入 `->protocol-encoder`；`->Client` 增加 `secret-key` 与 `node-id` 参数；仅文本内容通过新协议发送。
- `lan-clip.core` 使用 `config/load-config` 替代 `util/read-edn`；启动时生成 `node-id`（`UUID/randomUUID`）并透传至 server/client；图片/文件剪贴板内容暂不同步。
- 新增 `lan-clip.socket.integration-test`，验证 client encoder → server decoder 文本往返（`EmbeddedChannel`）。
- `lan-clip.protocol` 提取通用 `encode-message`，新增 `encode-image-message`；附 1 个 `lein test` 用例覆盖图片往返。
- `lan-clip.socket.protocol-codec` 的 `->protocol-encoder` 扩展支持 `java.awt.Image`（自动转为 PNG 字节）；附 1 个 `lein test` 用例覆盖 BufferedImage 编码。
- `lan-clip.socket.server` 的 `:image` handler 实现：将 payload PNG 字节解码为 `BufferedImage` 并写入系统剪贴板。
- `lan-clip.socket.client` 的 `content-handler` 扩展支持 `Image` 内容发送。
- `lan-clip.core` 重新启用图片剪贴板内容同步。
- `lan-clip.socket.integration-test` 新增图片往返验证（`BufferedImage` → encoder → decoder → Message `:image`）。

### Changed

- 清理 `lein new` 模板留下的失败断言，让 `lein test` 重新可信。
- 更新 `project.clj` 的 `:description` / `:url` 为 lan-clip 真实定位与仓库地址。
- 补全 `.gitignore`，显式忽略 `.DS_Store` 与 Claude Code loop 流程产物（`.claude/scheduled_tasks.lock`、`.claude/worktrees/`）。
- 改写 `CHANGELOG.md` 与 `doc/intro.md`，移除 `lein new` 模板内容，文档入口指向 `doc/notes/`。

## [1.0]

工程初始版本：基于 Clojure + Netty 实现 LAN 内文本 / 图片 / 文件剪贴板同步。
详细架构与已知风险见 [`doc/notes/project-overview.md`](doc/notes/project-overview.md)；
桌面端落地计划见 [`doc/notes/tauri-desktop-landing-plan.md`](doc/notes/tauri-desktop-landing-plan.md)。

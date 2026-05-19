# PROJECT NOTES — lan-clip 极简完成记录

每轮 `.claude/loop.md` 闭环结束时追加一行，格式：`#YYYY-MM-DD 完成XX`。

#2026-05-19 完成 Phase 0 工程清理基线：清理 `lein new` 模板失败断言、更新 `project.clj` 元信息、补全 `.gitignore`、改写 `CHANGELOG.md` 与 `doc/intro.md`、脱敏 `resources/config.edn`、README 增加运行限制与安全提示。
#2026-05-19 完成 Phase 1 P0 第一步：新增 `lan-clip.config` 命名空间（默认配置 / 读取 / 合并 / 校验）及 6 个 `lein test` 用例（默认值 / 默认 host / 缺失文件 / 自定义覆盖 / 非法端口 / 默认配置自洽）。
#2026-05-19 完成 Phase 1 P0 第二步：新增 `lan-clip.fingerprint` 命名空间（迁移 `ClipboardData` 记录、`fingerprint`、`changed?`）及 6 个 `lein test` 用例（文本 / 图片字节 / 文件列表 / flavor 变化 / 内容变化 / 相同内容）。
#2026-05-19 完成 Phase 1 P0 第三步：新增 `lan-clip.clipboard` 命名空间（`IClipboard` 协议、`SystemClipboard`、`FakeClipboard`）及 6 个 `lein test` 用例（空状态 / 文本读写 / 图片读写 / 文件列表读写 / available-flavors / 协议实现检查）。
#2026-05-19 完成 Phase 1 P0 第四步：新增 `lan-clip.watcher` 命名空间（可停止的 `start-watcher` / `stop-watcher`，基于 `volatile!` + `future-cancel`）及 4 个 `lein test` 用例（回调执行 / 周期性 / 可停止 / 幂等停止）。
#2026-05-19 完成 Phase 1 P0 第五步：新增 `lan-clip.app` 命名空间（`start!` / `stop!` / `status`，整合配置加载与 watcher 生命周期）及 3 个 `lein test` 用例（启动停止 / 默认配置 / 状态反映）。
#2026-05-19 完成 Phase 2 P0 第一步：新增 `lan-clip.protocol` 命名空间（二进制协议 / HMAC-SHA256 / magic+version 校验）及 6 个 `lein test` 用例（文本往返 / HMAC 成功 / HMAC 失败 / magic 错误 / version 错误 / 截断消息）。metadata 第一版使用 EDN，避免新增依赖。
#2026-05-19 完成 Phase 2 P0 第二步：新增 `lan-clip.socket.protocol-codec` 命名空间（Netty 编解码器 / 4-byte length prefix / `->protocol-encoder` / `->protocol-decoder`）及 6 个 `lein test` 用例（frame 长度前缀 / encoder 有效性 / decoder 完整读取 / decoder 半包累积 / HMAC 拒绝 / 往返）。尚未替换 `server.clj` / `client.clj` 中的 ObjectEncoder/ObjectDecoder。
#2026-05-19 完成 Phase 2 P0 第三步：将 `protocol-codec` 整合进 `server.clj` / `client.clj` / `core.clj`，替换 `ObjectEncoder` / `ObjectDecoder`；`config.clj` 增加 `:secret-key` 默认；`handle-msg` 按 `:content-type` 分发；新增 integration-test 验证 encoder → decoder 往返。图片/文件链路待迁移。
#2026-05-19 完成 Phase 2 P1 第一步：图片链路迁移至新协议。`protocol.clj` 提取通用 `encode-message`、新增 `encode-image-message`；`protocol-codec.clj` encoder 扩展支持 `BufferedImage`（自动转 PNG）；`server.clj` `:image` handler 实现 PNG → BufferedImage → 剪贴板；`client.clj` `content-handler` 支持 `Image`；`core.clj` 重新启用图片同步；新增 protocol / codec / integration 测试共 3 个。文件链路待迁移。
#2026-05-19 完成 Phase 2 P1 第二步：文件链路迁移至新协议。`protocol.clj` 新增 `encode-file-list-message`；`util.clj` 新增 `files->zip-bytes` / `zip-bytes->files`；`protocol-codec.clj` encoder 扩展支持 `List<File>`（自动转 zip）；`server.clj` `:file-list` handler 实现 zip → 临时目录 → 剪贴板；`client.clj` `content-handler` 支持文件列表；`core.clj` 重新启用文件同步；新增 protocol / codec / integration 测试共 3 个。Phase 2 P1 全部完成。
#2026-05-19 完成 Phase 2 P1 第三步：服务端限制最大 payload。`config.clj` 新增 `:max-frame-size` 默认 10MB；`protocol-codec.clj` 的 `->protocol-decoder` 增加可选 `max-frame-size` 参数，超限立即拒绝；`server.clj` `Server` record 增加 `max-frame-size` 字段；`core.clj` 启动时透传配置；新增 config / codec 测试共 2 个。Phase 2 全部完成。
#2026-05-19 完成 HMAC 安全修复：`protocol.clj` `decode-message` 中 HMAC 校验由 `(not= (seq ...) (seq ...))` 改为 `java.security.MessageDigest/isEqual`，消除定时攻击漏洞。`lein test` 48 测试 / 138 断言全绿。

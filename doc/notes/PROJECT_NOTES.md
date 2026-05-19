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

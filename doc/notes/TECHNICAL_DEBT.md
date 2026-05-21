# lan-clip 技术债清单

> 本文档记录 PR #9 代码审查中发现的 P1（近期修复）和 P2（技术债）级别问题。
> P0 级别问题（严重安全/稳定性问题）已在审查后当场修复，不记录于此。
> 最后更新：2026-05-21

---

## P1 — 近期修复（影响功能正确性或可维护性）

### 1. `core.clj` — 剪贴板快速变化时堆积未限制的 future

- **位置**：`src/lan_clip/core.clj:22-43`
- **问题**：`handle-flavor` 的每个方法内部都执行 `future (client/run clnt)`。如果剪贴板内容快速变化（如用户连续复制），会创建大量并发的 Netty 客户端 future，既消耗线程又可能引发连接风暴。
- **建议**：引入发送队列或节流机制（如 100ms debounce），限制并发客户端数量。

### 2. `server.clj` — 单消息即关闭连接的设计未文档化

- **位置**：`src/lan_clip/socket/server.clj:67-75`
- **问题**：`channelRead` 处理完每条消息后都调用 `(.close ctx)`，意味着每个 TCP 连接只处理一条消息。虽然简化了状态管理，但频繁建连在剪贴板同步高频场景下开销较大。
- **建议**：在文档或代码注释中明确说明此设计决策；若后续需要长连接，需重构 handler 生命周期。

### 3. `server.clj` — `start-server` stop 阻塞 10 秒

- **位置**：`src/lan_clip/socket/server.clj:111-114`
- **问题**：`:stop!` 使用 `(deref channel-promise 10000 nil)` 等待 channel。如果启动阶段失败（如端口被占用），`channel-promise` 永不被 deliver，`stop!` 会阻塞整整 10 秒。
- **建议**：将 `channel-promise` 的 deliver 逻辑与启动异常处理结合，失败时立即 deliver `nil` 或异常。

### 4. `api.clj` — `PUT /config` 缺少输入验证

- **位置**：`src/lan_clip/api.clj:60-73`
- **问题**：`PUT /config` 直接对请求体执行 `edn/read-string` 后 `merge` 到现有配置，**无任何输入校验**。可写入任意数据结构，导致后续代码在读取配置时行为异常。
- **建议**：
  - 对 `updates` 中每个 key 做类型校验（如 `:port` 必须是整数）。
  - 拒绝未知 key，防止配置污染。
  - 限制请求体大小。

### 7. `api.ts` — 脆弱的 EDN 字符串解析

- **位置**：`ui/src/api.ts:133-188`
- **问题**：`parseEdnLike` 和 `configToEdn` 是字符串级的简易解析器，无法正确处理：
  - 嵌套 map（如 `{:a {:b 1}}`）
  - 字符串中的空格或引号
  - `#uuid`、 `#inst` 等 tagged literal
- **建议**：引入真正的 EDN 解析库（如 `jsedn`），或改用 JSON 作为前后端数据交换格式。

### 9. `lib.rs` — Sidecar 是占位实现

- **位置**：`ui/src-tauri/src/lib.rs:42-61`
- **问题**：`sidecar_start` / `sidecar_stop` / `sidecar_status` 只修改内存布尔值，**不实际 spawn 或 kill Clojure 进程**。前端所有 sidecar 控制按钮都是无操作。
- **建议**：接入 `std::process::Command` 管理 Clojure sidecar 进程生命周期，记录 PID，在 Exit 事件中确保清理。

---

## P2 — 技术债（不影响当前功能，长期维护成本）

### 10. `core.clj` — 遗留的 `lan-clip` 函数与不可停止的 `set-interval`

- **位置**：`src/lan_clip/core.clj:111-120`、`src/lan_clip/util.clj:15-23`
- **问题**：`lan-clip` 函数仍使用旧版不可停止的 `util/set-interval`，与新的 `app/start!` 生命周期并存，容易造成混淆。`set-interval` 的 `while true` 循环一旦启动无法优雅停止。
- **建议**：删除废弃的 `lan-clip` 入口和 `set-interval` 函数，或添加 `@deprecated` 注释。

### 11. 多处 — 裸 `.printStackTrace` 散布

- **位置**：`server.clj:77`、`client.clj:28`、`watcher.clj:21` 等
- **问题**：异常处理使用裸 `(.printStackTrace e)`，而不是统一的日志系统。导致错误信息无法被 `log/recent-logs` 捕获，也难以在生产环境中排查。
- **建议**：统一替换为 `log/log! :error ...`。

### 13. `api.clj` — CORS 设置为 `*`

- **位置**：`src/lan_clip/api.clj:35-38`
- **问题**：`Access-Control-Allow-Origin: *` 允许任意来源。虽然 sidecar 仅绑定 localhost，风险可控，但不够严谨。
- **建议**：限制为 `http://localhost` 或 `tauri://localhost`。

### 14. 测试 — 大量 `Thread/sleep` 做同步

- **位置**：`acceptance_test.clj`、`server_test.clj`、`app_test.clj` 等
- **问题**：网络/异步测试中普遍使用 `Thread/sleep`（300ms、500ms 不等）等待 side effect。这导致：
  - 测试运行时间不稳定
  - 在慢机器上可能 flaky
  - 在快机器上浪费等待时间
- **建议**：引入 `CountDownLatch`、promise 或 Netty 的 `EmbeddedChannel` 同步机制实现确定性测试。

### 15. `protocol.clj` — 消息大小计算的隐式 long→int 转换

- **位置**：`src/lan_clip/protocol.clj:78`
- **问题**：`total-size` 由 Clojure `+` 计算为 `long`，传入 `ByteBuffer/allocate` 时隐式转 `int`。如果 payload 极大（接近 2GB），可能静默溢出。
- **建议**：由于 `max-frame-size` 已限制实际大小，当前风险低。可在 `encode-message` 中加入显式上限检查。

### 16. `lib.rs` — `TrayIconBuilder` 事件错误被忽略

- **位置**：`ui/src-tauri/src/lib.rs:165-171`
- **问题**：托盘菜单的 `emit`、`show`、`set_focus` 等操作使用 `let _ = ...` 忽略错误。若前端事件系统未就绪，操作会静默失败。
- **建议**：至少记录错误日志，或向用户展示降级提示。

# lan-clip 技术债清单

> 本文档记录 PR #9 代码审查中发现的 P1（近期修复）和 P2（技术债）级别问题。
> P0 级别问题（严重安全/稳定性问题）已在审查后当场修复，不记录于此。
> 最后更新：2026-05-21

---

## P1 — 近期修复（影响功能正确性或可维护性）

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

### 14. 测试 — 大量 `Thread/sleep` 做同步

- **位置**：`acceptance_test.clj`、`server_test.clj`、`app_test.clj` 等
- **问题**：网络/异步测试中普遍使用 `Thread/sleep`（300ms、500ms 不等）等待 side effect。这导致：
  - 测试运行时间不稳定
  - 在慢机器上可能 flaky
  - 在快机器上浪费等待时间
- **建议**：引入 `CountDownLatch`、promise 或 Netty 的 `EmbeddedChannel` 同步机制实现确定性测试。

### 16. `lib.rs` — `TrayIconBuilder` 事件错误被忽略

- **位置**：`ui/src-tauri/src/lib.rs:165-171`
- **问题**：托盘菜单的 `emit`、`show`、`set_focus` 等操作使用 `let _ = ...` 忽略错误。若前端事件系统未就绪，操作会静默失败。
- **建议**：至少记录错误日志，或向用户展示降级提示。

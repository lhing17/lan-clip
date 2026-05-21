# lan-clip 技术债清单

> 本文档记录 PR #9 代码审查中发现的 P1（近期修复）和 P2（技术债）级别问题。
> P0 级别问题（严重安全/稳定性问题）已在审查后当场修复，不记录于此。
> 最后更新：2026-05-21

---

## P1 — 近期修复（影响功能正确性或可维护性）

（当前无 P1 项）

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

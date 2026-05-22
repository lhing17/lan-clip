# lan-clip 技术债清单

> 本文档记录代码审查或开发过程中发现的未解决问题，按优先级分级。
> P0 级别问题（严重安全/稳定性问题）已在审查后当场修复，不记录于此。
> 最后更新：2026-05-22

---

## P1 — 近期修复（影响功能正确性或可维护性）

（当前无 P1 项）

---

## P2 — 技术债（不影响当前功能，长期维护成本）

### UDP beacon 可能超过固定缓冲区
- **位置**：`src/lan_clip/discovery.clj:53-63`
- **问题**：`beacon-max-bytes` 固定为 1024，`send-beacon!` 未检查 payload 实际大小。当 `device-name` 很长（如含多字节 UTF-8 字符）时，`pr-str` 后的 beacon 可能超过 1024 字节，导致接收端 `receive-udp!` 截断数据并 EDN 解析失败，peer 无法被发现。
- **建议**：在 `send-beacon!` 中增加 payload 大小检查，超过阈值时截断 `device-name` 或输出警告日志。

### 无效 peer 地址静默跳过
- **位置**：`src/lan_clip/core.clj:56-60`
- **问题**：`send-to-all-peers!` 中当 `host` 或 `port` 为 nil/空时通过 `(when (and (:host peer) (:port peer)))` 静默跳过，无日志记录。用户配置错误或配对失败时难以排查原因。
- **建议**：在跳过分支中增加 `log/log! :warn` 记录无效的 peer 地址和原因。

### 每次发送创建新 Netty EventLoopGroup
- **位置**：`src/lan_clip/socket/client.clj:27-58`
- **问题**：`client/run` 每次调用都创建新的 `NioEventLoopGroup`，多 peer 同步场景下频繁创建/销毁线程池。`in-flight-sends` 的 `future-cancel` 虽取消旧 future，但 `shutdownGracefully` 非瞬时，线程资源可能短暂堆积。
- **建议**：长期考虑复用 `NioEventLoopGroup`（如作为应用状态的一部分）或使用连接池，减少每次发送的线程创建开销。

---

## P3 — 技术债（优化点 Nice to Have）

### 前端缺少 Error Boundary
- **位置**：`ui/src/App.tsx`
- **问题**：无 React Error Boundary，任何组件（如配置页表单渲染、历史页列表渲染）抛出未捕获异常时，整个应用可能白屏崩溃，用户只能强制重启。
- **建议**：增加顶层 Error Boundary 组件，捕获错误后展示友好错误页和重启按钮。

### API 错误响应格式不一致
- **位置**：`src/lan_clip/api.clj:165-183`（`POST /pair`）
- **问题**：`POST /pair` 成功返回 `{:success? true :secret-key ...}`，失败返回 `{:success? false :reason ...}`；但其他端点（如 `PUT /config`）使用 `{:error :xxx :details ...}` 格式。前端处理错误时需要兼容多种结构。
- **建议**：统一所有 API 端点的错误响应结构，例如 `{:success? false :error :keyword :message "..."}`。

---

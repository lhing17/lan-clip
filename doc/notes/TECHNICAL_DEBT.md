# lan-clip 技术债清单

> 本文档记录代码审查或开发过程中发现的未解决问题，按优先级分级。
> P0 级别问题（严重安全/稳定性问题）已在审查后当场修复，不记录于此。
> 最后更新：2026-05-22

---

## P1 — 近期修复（影响功能正确性或可维护性）

（当前无 P1 项）

---

## P2 — 技术债（不影响当前功能，长期维护成本）

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

---

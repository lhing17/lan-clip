import { useState, useEffect, useCallback } from "react";
import { invoke } from "@tauri-apps/api/core";
import {
  fetchSidecarStatus,
  fetchSidecarConfig,
  startSync,
  stopSync,
} from "./api";
import "./App.css";

interface AppState {
  sidecarRunning: boolean;
  syncRunning: boolean;
  nodeId: string | null;
  listenPort: number | null;
  error: string | null;
}

function App() {
  const [state, setState] = useState<AppState>({
    sidecarRunning: false,
    syncRunning: false,
    nodeId: null,
    listenPort: null,
    error: null,
  });
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const sidecarRunning = await invoke<boolean>("sidecar_status");
      setState((prev) => ({ ...prev, sidecarRunning, error: null }));

      if (sidecarRunning) {
        const [status, config] = await Promise.all([
          fetchSidecarStatus().catch(() => null),
          fetchSidecarConfig().catch(() => null),
        ]);
        setState((prev) => ({
          ...prev,
          syncRunning: status?.running ?? false,
          nodeId: status?.nodeId ?? config?.nodeId ?? null,
          listenPort: config?.port ?? null,
        }));
      } else {
        setState((prev) => ({
          ...prev,
          syncRunning: false,
          nodeId: null,
          listenPort: null,
        }));
      }
    } catch (e) {
      setState((prev) => ({
        ...prev,
        error: e instanceof Error ? e.message : String(e),
      }));
    }
  }, []);

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, 3000);
    return () => clearInterval(id);
  }, [refresh]);

  async function toggleSidecar() {
    setLoading(true);
    try {
      if (state.sidecarRunning) {
        await invoke("sidecar_stop");
      } else {
        await invoke("sidecar_start");
      }
      await refresh();
    } catch (e) {
      setState((prev) => ({
        ...prev,
        error: e instanceof Error ? e.message : String(e),
      }));
    } finally {
      setLoading(false);
    }
  }

  async function toggleSync() {
    if (!state.sidecarRunning) return;
    setLoading(true);
    try {
      if (state.syncRunning) {
        await stopSync();
      } else {
        await startSync();
      }
      await refresh();
    } catch (e) {
      setState((prev) => ({
        ...prev,
        error: e instanceof Error ? e.message : String(e),
      }));
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="container">
      <h1>lan-clip</h1>
      <p className="subtitle">局域网剪贴板同步</p>

      {state.error && (
        <div className="error-banner">
          {state.error}
        </div>
      )}

      <section className="card">
        <h2>Sidecar 状态</h2>
        <div className="status-row">
          <span className="status-label">运行状态</span>
          <span className={state.sidecarRunning ? "status-on" : "status-off"}>
            {state.sidecarRunning ? "运行中" : "已停止"}
          </span>
        </div>
        <button
          className="toggle-btn"
          onClick={toggleSidecar}
          disabled={loading}
        >
          {state.sidecarRunning ? "停止 Sidecar" : "启动 Sidecar"}
        </button>
      </section>

      {state.sidecarRunning && (
        <section className="card">
          <h2>同步状态</h2>
          <div className="status-row">
            <span className="status-label">同步开关</span>
            <span className={state.syncRunning ? "status-on" : "status-off"}>
              {state.syncRunning ? "运行中" : "已停止"}
            </span>
          </div>
          <div className="status-row">
            <span className="status-label">节点名</span>
            <span className="status-value">
              {state.nodeId ?? "—"}
            </span>
          </div>
          <div className="status-row">
            <span className="status-label">监听端口</span>
            <span className="status-value">
              {state.listenPort ?? "—"}
            </span>
          </div>
          <button
            className="toggle-btn"
            onClick={toggleSync}
            disabled={loading}
          >
            {state.syncRunning ? "停止同步" : "开始同步"}
          </button>
        </section>
      )}
    </main>
  );
}

export default App;

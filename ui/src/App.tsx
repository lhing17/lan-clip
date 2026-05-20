import { useState, useEffect, useCallback } from "react";
import { invoke } from "@tauri-apps/api/core";
import {
  fetchSidecarStatus,
  fetchSidecarConfig,
  startSync,
  stopSync,
  saveConfig,
  type SidecarConfig,
} from "./api";
import "./App.css";

interface AppState {
  sidecarRunning: boolean;
  syncRunning: boolean;
  nodeId: string | null;
  listenPort: number | null;
  error: string | null;
}

type Tab = "status" | "config";

function App() {
  const [activeTab, setActiveTab] = useState<Tab>("status");
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

      <nav className="tabs">
        <button
          className={activeTab === "status" ? "tab-active" : "tab"}
          onClick={() => setActiveTab("status")}
        >
          状态
        </button>
        <button
          className={activeTab === "config" ? "tab-active" : "tab"}
          onClick={() => setActiveTab("config")}
        >
          配置
        </button>
      </nav>

      {activeTab === "status" ? (
        <>
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
        </>
      ) : (
        <ConfigPage />
      )}
    </main>
  );
}

function ConfigPage() {
  const [config, setConfig] = useState<SidecarConfig>({});
  const [saving, setSaving] = useState(false);
  const [saveMsg, setSaveMsg] = useState<string | null>(null);

  useEffect(() => {
    fetchSidecarConfig()
      .then((cfg) => setConfig(cfg))
      .catch(() => setConfig({}));
  }, []);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setSaveMsg(null);
    try {
      const result = await saveConfig({
        deviceName: config.deviceName,
        port: config.port,
        targetHost: config.targetHost,
        targetPort: config.targetPort,
        secretKey: config.secretKey,
        interval: config.interval,
        fileSize: config.fileSize,
        receivedFilesDir: config.receivedFilesDir,
      });
      if (result.restartRequired) {
        setSaveMsg("配置已保存，部分改动需要重启 sidecar 后生效。");
      } else {
        setSaveMsg("配置已保存。");
      }
    } catch (err) {
      setSaveMsg("保存失败：" + (err instanceof Error ? err.message : String(err)));
    } finally {
      setSaving(false);
    }
  }

  function update<K extends keyof SidecarConfig>(key: K, value: SidecarConfig[K]) {
    setConfig((prev) => ({ ...prev, [key]: value }));
  }

  return (
    <section className="card">
      <h2>应用配置</h2>
      {saveMsg && <div className="info-banner">{saveMsg}</div>}
      <form onSubmit={handleSubmit} className="config-form">
        <label>
          <span>设备名</span>
          <input
            type="text"
            value={config.deviceName ?? ""}
            onChange={(e) => update("deviceName", e.target.value)}
            placeholder="例如：My-MacBook"
          />
        </label>
        <label>
          <span>监听端口</span>
          <input
            type="number"
            value={config.port ?? ""}
            onChange={(e) => update("port", parseInt(e.target.value, 10) || undefined)}
            placeholder="9002"
          />
        </label>
        <label>
          <span>目标主机（Peers）</span>
          <input
            type="text"
            value={config.targetHost ?? ""}
            onChange={(e) => update("targetHost", e.target.value)}
            placeholder="localhost"
          />
        </label>
        <label>
          <span>目标端口</span>
          <input
            type="number"
            value={config.targetPort ?? ""}
            onChange={(e) => update("targetPort", parseInt(e.target.value, 10) || undefined)}
            placeholder="9002"
          />
        </label>
        <label>
          <span>共享密钥</span>
          <input
            type="password"
            value={config.secretKey ?? ""}
            onChange={(e) => update("secretKey", e.target.value)}
            placeholder="lan-clip"
          />
        </label>
        <label>
          <span>轮询间隔（毫秒）</span>
          <input
            type="number"
            value={config.interval ?? ""}
            onChange={(e) => update("interval", parseInt(e.target.value, 10) || undefined)}
            placeholder="2000"
          />
        </label>
        <label>
          <span>文件大小限制（KB）</span>
          <input
            type="number"
            value={config.fileSize ?? ""}
            onChange={(e) => update("fileSize", parseInt(e.target.value, 10) || undefined)}
            placeholder="2048"
          />
        </label>
        <label>
          <span>接收目录</span>
          <input
            type="text"
            value={config.receivedFilesDir ?? ""}
            onChange={(e) => update("receivedFilesDir", e.target.value)}
            placeholder="~/.lan-clip/received-files"
          />
        </label>
        <button type="submit" className="toggle-btn" disabled={saving}>
          {saving ? "保存中..." : "保存配置"}
        </button>
      </form>
    </section>
  );
}

export default App;

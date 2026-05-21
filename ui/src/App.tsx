import { useState, useEffect, useCallback, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { getVersion } from "@tauri-apps/api/app";
import { listen } from "@tauri-apps/api/event";
import {
  fetchSidecarStatus,
  fetchSidecarConfig,
  startSync,
  stopSync,
  saveConfig,
  fetchRecentLogs,
  enableAutostart,
  disableAutostart,
  getAutostartStatus,
  type SidecarConfig,
  type LogEntry,
} from "./api";
import { openPath } from "@tauri-apps/plugin-opener";
import { notifyError } from "./notifications";
import "./App.css";

interface AppState {
  sidecarRunning: boolean;
  syncRunning: boolean;
  nodeId: string | null;
  listenPort: number | null;
  error: string | null;
}

type Tab = "status" | "config" | "logs" | "about";

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

  const toggleSyncRef = useRef(toggleSync);
  toggleSyncRef.current = toggleSync;

  useEffect(() => {
    let unlisten: (() => void) | undefined;
    listen("tray-sync-toggle", () => {
      toggleSyncRef.current();
    }).then((fn) => {
      unlisten = fn;
    });
    return () => {
      if (unlisten) unlisten();
    };
  }, []);

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
      const msg = e instanceof Error ? e.message : String(e);
      setState((prev) => ({ ...prev, error: msg }));
      notifyError("Sidecar 启动失败", msg);
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
      const msg = e instanceof Error ? e.message : String(e);
      setState((prev) => ({ ...prev, error: msg }));
      notifyError("同步错误", msg);
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
        <button
          className={activeTab === "logs" ? "tab-active" : "tab"}
          onClick={() => setActiveTab("logs")}
        >
          日志
        </button>
        <button
          className={activeTab === "about" ? "tab-active" : "tab"}
          onClick={() => setActiveTab("about")}
        >
          关于
        </button>
      </nav>

      {activeTab === "status" && (
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
      )}
      {activeTab === "config" && <ConfigPage />}
      {activeTab === "logs" && <LogsPage />}
      {activeTab === "about" && <AboutPage />}
    </main>
  );
}

function ConfigPage() {
  const [config, setConfig] = useState<SidecarConfig>({});
  const [saving, setSaving] = useState(false);
  const [saveMsg, setSaveMsg] = useState<string | null>(null);
  const [autostart, setAutostart] = useState<boolean | null>(null);

  useEffect(() => {
    fetchSidecarConfig()
      .then((cfg) => setConfig(cfg))
      .catch(() => setConfig({}));
    getAutostartStatus()
      .then((enabled) => setAutostart(enabled))
      .catch(() => setAutostart(false));
  }, []);

  async function toggleAutostart() {
    try {
      if (autostart) {
        await disableAutostart();
        setAutostart(false);
      } else {
        await enableAutostart();
        setAutostart(true);
      }
    } catch (e) {
      setSaveMsg("自启动设置失败：" + (e instanceof Error ? e.message : String(e)));
    }
  }

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
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={autostart ?? false}
            onChange={toggleAutostart}
            disabled={autostart === null}
          />
          <span>开机自启动</span>
        </label>
        <button type="submit" className="toggle-btn" disabled={saving}>
          {saving ? "保存中..." : "保存配置"}
        </button>
      </form>
    </section>
  );
}

function AboutPage() {
  const [version, setVersion] = useState<string | null>(null);

  useEffect(() => {
    getVersion()
      .then((v) => setVersion(v))
      .catch(() => setVersion(null));
  }, []);

  return (
    <section className="card">
      <h2>关于 lan-clip</h2>
      <div className="status-row">
        <span className="status-label">应用版本</span>
        <span className="status-value">{version ?? "—"}</span>
      </div>
      <div className="status-row">
        <span className="status-label">协议版本</span>
        <span className="status-value">1</span>
      </div>
      <button
        className="toggle-btn"
        onClick={() => {
          alert("检查更新功能即将推出");
        }}
      >
        检查更新
      </button>
    </section>
  );
}

function LogsPage() {
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [logDir, setLogDir] = useState<string | null>(null);

  useEffect(() => {
    fetchSidecarConfig()
      .then((cfg) => {
        if (cfg.logFile) {
          const sep = cfg.logFile.includes("/") ? "/" : "\\";
          const idx = cfg.logFile.lastIndexOf(sep);
          setLogDir(idx > 0 ? cfg.logFile.slice(0, idx) : cfg.logFile);
        }
      })
      .catch(() => setLogDir(null));
  }, []);

  const refreshLogs = useCallback(async () => {
    setLoading(true);
    try {
      const entries = await fetchRecentLogs();
      setLogs(entries);
    } catch (e) {
      setLogs([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refreshLogs();
  }, [refreshLogs]);

  async function openLogDir() {
    if (!logDir) return;
    try {
      await openPath(logDir);
    } catch (e) {
      console.error("open log dir failed", e);
    }
  }

  const errorLogs = logs.filter((l) => l.level === "error");

  return (
    <section className="card">
      <h2>日志</h2>
      <div className="logs-toolbar">
        <button className="toggle-btn" onClick={refreshLogs} disabled={loading}>
          {loading ? "刷新中..." : "刷新"}
        </button>
        {logDir && (
          <button className="toggle-btn" onClick={openLogDir}>
            打开日志目录
          </button>
        )}
      </div>

      {errorLogs.length > 0 && (
        <div className="error-banner">
          <strong>错误详情</strong>
          <ul className="error-list">
            {errorLogs.map((l, i) => (
              <li key={`err-${i}`}>
                [{l.time}] {l.msg}
              </li>
            ))}
          </ul>
        </div>
      )}

      {logs.length === 0 ? (
        <p className="empty-logs">暂无日志</p>
      ) : (
        <ul className="log-list">
          {logs.map((l, i) => (
            <li key={`log-${i}`} className={`log-item log-${l.level}`}>
              <span className="log-time">{l.time}</span>
              <span className="log-level">{l.level}</span>
              <span className="log-msg">{l.msg}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export default App;

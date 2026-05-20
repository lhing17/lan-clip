import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import App from "./App";

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(),
}));

vi.mock("@tauri-apps/api/app", () => ({
  getVersion: vi.fn(),
}));

vi.mock("@tauri-apps/api/event", () => ({
  listen: vi.fn().mockResolvedValue(() => {}),
}));

vi.mock("./api", async () => {
  const actual = await vi.importActual<typeof import("./api")>("./api");
  return {
    ...actual,
    fetchRecentLogs: vi.fn(),
    fetchSidecarStatus: vi.fn(),
    fetchSidecarConfig: vi.fn(),
    startSync: vi.fn(),
    stopSync: vi.fn(),
  };
});

import { invoke } from "@tauri-apps/api/core";
import { getVersion } from "@tauri-apps/api/app";
import { listen } from "@tauri-apps/api/event";
import { fetchRecentLogs, fetchSidecarStatus, fetchSidecarConfig, startSync, stopSync } from "./api";

describe("App logs tab", () => {
  beforeEach(() => {
    vi.mocked(invoke).mockResolvedValue(true);
    vi.mocked(fetchSidecarStatus).mockResolvedValue({
      running: true,
      nodeId: "test-node",
    });
    vi.mocked(fetchSidecarConfig).mockResolvedValue({ port: 9002 });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("shows log entries after clicking 日志 tab", async () => {
    vi.mocked(fetchRecentLogs).mockResolvedValue([
      { time: "10:00:00", level: "info", msg: "sync started" },
      { time: "10:01:00", level: "error", msg: "connection failed" },
    ]);

    render(<App />);

    await waitFor(() =>
      expect(document.body.textContent).toContain("节点名")
    );

    fireEvent.click(screen.getByText("日志"));

    await waitFor(() => {
      expect(document.body.textContent).toContain("sync started");
    });

    expect(document.body.textContent).toContain("connection failed");
    expect(document.body.textContent).toContain("info");
    expect(document.body.textContent).toContain("error");
  });

  it("shows empty state when no logs", async () => {
    vi.mocked(fetchRecentLogs).mockResolvedValue([]);

    render(<App />);
    await waitFor(() =>
      expect(document.body.textContent).toContain("节点名")
    );

    fireEvent.click(screen.getByText("日志"));

    await waitFor(() => {
      expect(document.body.textContent).toContain("暂无日志");
    });
  });
});

describe("App about tab", () => {
  beforeEach(() => {
    vi.mocked(invoke).mockResolvedValue(true);
    vi.mocked(getVersion).mockResolvedValue("1.0.0");
    vi.mocked(fetchSidecarStatus).mockResolvedValue({
      running: true,
      nodeId: "test-node",
    });
    vi.mocked(fetchSidecarConfig).mockResolvedValue({ port: 9002 });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("shows about info after clicking 关于 tab", async () => {
    render(<App />);

    await waitFor(() =>
      expect(document.body.textContent).toContain("节点名")
    );

    fireEvent.click(screen.getByText("关于"));

    await waitFor(() => {
      expect(document.body.textContent).toContain("lan-clip");
    });

    expect(document.body.textContent).toContain("版本");
    expect(document.body.textContent).toContain("协议版本");
    expect(document.body.textContent).toContain("1.0.0");
    expect(document.body.textContent).toContain("检查更新");
  });
});

describe("App tray sync toggle", () => {
  beforeEach(() => {
    vi.mocked(invoke).mockResolvedValue(true);
    vi.mocked(fetchSidecarStatus).mockResolvedValue({
      running: true,
      nodeId: "test-node",
    });
    vi.mocked(fetchSidecarConfig).mockResolvedValue({ port: 9002 });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("stops sync when tray sync-toggle event fires while sync is running", async () => {
    let eventHandler: ((event: any) => void) | null = null;
    vi.mocked(listen).mockImplementation(async (event: string, handler: any) => {
      if (event === "tray-sync-toggle") {
        eventHandler = handler;
      }
      return () => {};
    });

    render(<App />);

    await waitFor(() =>
      expect(document.body.textContent).toContain("节点名")
    );

    expect(eventHandler).not.toBeNull();
    eventHandler!({} as any);

    await waitFor(() => {
      expect(stopSync).toHaveBeenCalled();
    });
  });

  it("starts sync when tray sync-toggle event fires while sync is stopped", async () => {
    let eventHandler: ((event: any) => void) | null = null;
    vi.mocked(listen).mockImplementation(async (event: string, handler: any) => {
      if (event === "tray-sync-toggle") {
        eventHandler = handler;
      }
      return () => {};
    });

    vi.mocked(fetchSidecarStatus).mockResolvedValue({
      running: false,
      nodeId: "test-node",
    });

    render(<App />);

    await waitFor(() =>
      expect(document.body.textContent).toContain("Sidecar 状态")
    );

    expect(eventHandler).not.toBeNull();
    eventHandler!({} as any);

    await waitFor(() => {
      expect(startSync).toHaveBeenCalled();
    });
  });
});

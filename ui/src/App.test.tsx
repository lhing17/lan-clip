import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import App from "./App";

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(),
}));

vi.mock("@tauri-apps/api/app", () => ({
  getVersion: vi.fn(),
}));

vi.mock("./api", async () => {
  const actual = await vi.importActual<typeof import("./api")>("./api");
  return {
    ...actual,
    fetchRecentLogs: vi.fn(),
    fetchSidecarStatus: vi.fn(),
    fetchSidecarConfig: vi.fn(),
  };
});

import { invoke } from "@tauri-apps/api/core";
import { getVersion } from "@tauri-apps/api/app";
import { fetchRecentLogs, fetchSidecarStatus, fetchSidecarConfig } from "./api";

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

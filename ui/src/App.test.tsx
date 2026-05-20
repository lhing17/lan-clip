import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import App from "./App";

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(),
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

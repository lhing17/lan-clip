import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  fetchSidecarStatus,
  fetchSidecarConfig,
  startSync,
  stopSync,
  saveConfig,
} from "./api";

describe("sidecar HTTP client", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("fetchSidecarStatus parses EDN response", async () => {
    const mockFetch = vi.mocked(fetch);
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: async () => '{:running? false :version "1.0" :protocol-version 1}',
    } as Response);

    const status = await fetchSidecarStatus();
    expect(status.running).toBe(false);
    expect(status.version).toBe("1.0");
    expect(status.protocolVersion).toBe(1);
    expect(mockFetch).toHaveBeenCalledWith("http://localhost:9615/status");
  });

  it("fetchSidecarConfig parses config response", async () => {
    const mockFetch = vi.mocked(fetch);
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: async () => '{:port 9002 :target-host "localhost" :interval 2000}',
    } as Response);

    const config = await fetchSidecarConfig();
    expect(config.port).toBe(9002);
    expect(config.targetHost).toBe("localhost");
    expect(config.interval).toBe(2000);
  });

  it("startSync sends POST to /sync/start", async () => {
    const mockFetch = vi.mocked(fetch);
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: async () => '{:running? true}',
    } as Response);

    const status = await startSync();
    expect(status.running).toBe(true);
    expect(mockFetch).toHaveBeenCalledWith("http://localhost:9615/sync/start", {
      method: "POST",
    });
  });

  it("stopSync sends POST to /sync/stop", async () => {
    const mockFetch = vi.mocked(fetch);
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: async () => '{:running? false}',
    } as Response);

    const status = await stopSync();
    expect(status.running).toBe(false);
    expect(mockFetch).toHaveBeenCalledWith("http://localhost:9615/sync/stop", {
      method: "POST",
    });
  });

  it("throws on non-ok response", async () => {
    const mockFetch = vi.mocked(fetch);
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 500,
      text: async () => "error",
    } as Response);

    await expect(fetchSidecarStatus()).rejects.toThrow("Status 500");
  });

  it("saveConfig sends PUT with EDN body", async () => {
    const mockFetch = vi.mocked(fetch);
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      text: async () => '{:success? true :restart-required? false}',
    } as Response);

    const result = await saveConfig({ deviceName: "My-MacBook", port: 9003 });
    expect(result.success).toBe(true);
    expect(result.restartRequired).toBe(false);
    expect(mockFetch).toHaveBeenCalledWith(
      "http://localhost:9615/config",
      expect.objectContaining({
        method: "PUT",
        headers: { "Content-Type": "application/edn" },
        body: '{:device-name "My-MacBook" :port 9003}',
      })
    );
  });

  it("saveConfig throws on non-ok response", async () => {
    const mockFetch = vi.mocked(fetch);
    mockFetch.mockResolvedValueOnce({
      ok: false,
      status: 400,
      text: async () => "bad request",
    } as Response);

    await expect(saveConfig({ port: 0 })).rejects.toThrow("Status 400");
  });
});

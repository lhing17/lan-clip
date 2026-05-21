import { invoke } from "@tauri-apps/api/core";
import { parseEDNString, toEDNStringFromSimpleObject } from "edn-data";

const SIDECAR_PORT = 9615;

function sidecarUrl(path: string): string {
  return `http://localhost:${SIDECAR_PORT}${path}`;
}

export interface SidecarStatus {
  running?: boolean;
  version?: string;
  protocolVersion?: number;
  nodeId?: string;
  config?: {
    port?: number;
    targetHost?: string;
    targetPort?: number;
    interval?: number;
  };
}

export interface SidecarConfig {
  port?: number;
  targetHost?: string;
  targetPort?: number;
  interval?: number;
  fileSize?: number;
  receivedFilesDir?: string;
  nodeId?: string;
  logFile?: string;
  deviceName?: string;
  secretKey?: string;
}

export interface SaveConfigResult {
  success?: boolean;
  restartRequired?: boolean;
}

export interface LogEntry {
  time?: string;
  level?: string;
  msg?: string;
}

export interface HistoryEntry {
  timestamp?: string;
  direction?: string;
  type?: string;
  size?: number;
  peer?: string;
}

function kebabToCamel(s: string): string {
  const base = s.replace(/\?$/, "");
  return base.replace(/-([a-z])/g, (_, ch) => ch.toUpperCase());
}

function camelToKebab(s: string): string {
  return s.replace(/[A-Z]/g, (ch) => "-" + ch.toLowerCase());
}

function isUuidObj(value: unknown): value is { tag: "uuid"; val: string } {
  return (
    typeof value === "object" &&
    value !== null &&
    "tag" in value &&
    (value as { tag: unknown }).tag === "uuid" &&
    "val" in value
  );
}

function convertKeys(obj: unknown): unknown {
  if (obj === null || obj === undefined) return obj;
  if (Array.isArray(obj)) return obj.map(convertKeys);
  if (typeof obj === "object") {
    const result: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(obj)) {
      let value = convertKeys(v);
      if (isUuidObj(value)) {
        value = value.val;
      }
      result[kebabToCamel(k)] = value;
    }
    return result;
  }
  return obj;
}

function parseEdnResponse(text: string): unknown {
  const parsed = parseEDNString(text, { mapAs: "object", keywordAs: "string" });
  return convertKeys(parsed);
}

function configToEdn(cfg: Partial<SidecarConfig>): string {
  const entries: Record<string, string | number | boolean> = {};
  for (const [k, v] of Object.entries(cfg)) {
    if (v !== undefined && v !== null) {
      entries[camelToKebab(k)] = v as string | number | boolean;
    }
  }
  return toEDNStringFromSimpleObject(entries);
}

export async function fetchSidecarStatus(): Promise<SidecarStatus> {
  const res = await fetch(sidecarUrl("/status"));
  if (!res.ok) throw new Error(`Status ${res.status}`);
  const text = await res.text();
  return parseEdnResponse(text) as SidecarStatus;
}

export async function fetchSidecarConfig(): Promise<SidecarConfig> {
  const res = await fetch(sidecarUrl("/config"));
  if (!res.ok) throw new Error(`Status ${res.status}`);
  const text = await res.text();
  return parseEdnResponse(text) as SidecarConfig;
}

export async function startSync(): Promise<SidecarStatus> {
  const res = await fetch(sidecarUrl("/sync/start"), { method: "POST" });
  if (!res.ok) throw new Error(`Status ${res.status}`);
  const text = await res.text();
  return parseEdnResponse(text) as SidecarStatus;
}

export async function stopSync(): Promise<SidecarStatus> {
  const res = await fetch(sidecarUrl("/sync/stop"), { method: "POST" });
  if (!res.ok) throw new Error(`Status ${res.status}`);
  const text = await res.text();
  return parseEdnResponse(text) as SidecarStatus;
}

export async function startSidecar(): Promise<boolean> {
  return invoke("sidecar_start");
}

export async function stopSidecar(): Promise<boolean> {
  return invoke("sidecar_stop");
}

export async function getSidecarStatus(): Promise<boolean> {
  return invoke("sidecar_status");
}

export async function getSidecarPort(): Promise<number> {
  return invoke("sidecar_port");
}

export async function saveConfig(
  cfg: Partial<SidecarConfig>
): Promise<SaveConfigResult> {
  const res = await fetch(sidecarUrl("/config"), {
    method: "PUT",
    headers: { "Content-Type": "application/edn" },
    body: configToEdn(cfg),
  });
  if (!res.ok) throw new Error(`Status ${res.status}`);
  const text = await res.text();
  return parseEdnResponse(text) as SaveConfigResult;
}

import { enable, disable, isEnabled } from "@tauri-apps/plugin-autostart";
import { check, type Update } from "@tauri-apps/plugin-updater";

export async function enableAutostart(): Promise<void> {
  await enable();
}

export async function disableAutostart(): Promise<void> {
  await disable();
}

export async function getAutostartStatus(): Promise<boolean> {
  return isEnabled();
}

export async function checkForUpdate(): Promise<Update | null> {
  return check();
}

export async function fetchRecentLogs(): Promise<LogEntry[]> {
  const res = await fetch(sidecarUrl("/logs/recent"));
  if (!res.ok) throw new Error(`Status ${res.status}`);
  const text = await res.text();
  const parsed = parseEdnResponse(text);
  if (!Array.isArray(parsed)) return [];
  return parsed.map((m) => ({
    time: String((m as Record<string, unknown>).time ?? ""),
    level: String((m as Record<string, unknown>).level ?? ""),
    msg: String((m as Record<string, unknown>).msg ?? ""),
  }));
}

export async function fetchHistory(limit = 20): Promise<HistoryEntry[]> {
  const res = await fetch(sidecarUrl(`/history/recent?limit=${limit}`));
  if (!res.ok) throw new Error(`Status ${res.status}`);
  const text = await res.text();
  const parsed = parseEdnResponse(text);
  if (!Array.isArray(parsed)) return [];
  return parsed.map((m) => ({
    timestamp: String((m as Record<string, unknown>).timestamp ?? ""),
    direction: String((m as Record<string, unknown>).direction ?? ""),
    type: String((m as Record<string, unknown>).type ?? ""),
    size: Number((m as Record<string, unknown>).size ?? 0),
    peer: String((m as Record<string, unknown>).peer ?? ""),
  }));
}

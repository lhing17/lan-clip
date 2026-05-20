import { invoke } from "@tauri-apps/api/core";

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

export async function fetchSidecarStatus(): Promise<SidecarStatus> {
  const res = await fetch(sidecarUrl("/status"));
  if (!res.ok) throw new Error(`Status ${res.status}`);
  const text = await res.text();
  return parseEdnLike(text);
}

export async function fetchSidecarConfig(): Promise<SidecarConfig> {
  const res = await fetch(sidecarUrl("/config"));
  if (!res.ok) throw new Error(`Status ${res.status}`);
  const text = await res.text();
  return parseEdnLike(text);
}

export async function startSync(): Promise<SidecarStatus> {
  const res = await fetch(sidecarUrl("/sync/start"), { method: "POST" });
  if (!res.ok) throw new Error(`Status ${res.status}`);
  const text = await res.text();
  return parseEdnLike(text);
}

export async function stopSync(): Promise<SidecarStatus> {
  const res = await fetch(sidecarUrl("/sync/stop"), { method: "POST" });
  if (!res.ok) throw new Error(`Status ${res.status}`);
  const text = await res.text();
  return parseEdnLike(text);
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

function camelToKebab(s: string): string {
  return s.replace(/[A-Z]/g, (ch) => "-" + ch.toLowerCase());
}

function toEdnValue(value: unknown): string {
  if (value === null || value === undefined) return "nil";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") return String(value);
  if (typeof value === "string") return JSON.stringify(value);
  return String(value);
}

function configToEdn(cfg: Partial<SidecarConfig>): string {
  const entries = Object.entries(cfg)
    .filter(([, v]) => v !== undefined)
    .map(([k, v]) => `:${camelToKebab(k)} ${toEdnValue(v)}`);
  return "{" + entries.join(" ") + "}";
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
  return parseEdnLike(text) as SaveConfigResult;
}

function parseEdnLike(text: string): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  const tokens = text.replace(/[{}]/g, "").split(/\s+/).filter(Boolean);
  for (let i = 0; i < tokens.length; i += 2) {
    const key = tokens[i]?.replace(/^:/, "");
    const raw = tokens[i + 1];
    if (key && raw !== undefined) {
      result[kebabToCamel(key)] = parseValue(raw);
    }
  }
  return result;
}

function kebabToCamel(s: string): string {
  const base = s.replace(/\?$/, "");
  return base.replace(/-([a-z])/g, (_, ch) => ch.toUpperCase());
}

function parseValue(raw: string): unknown {
  if (raw === "true") return true;
  if (raw === "false") return false;
  if (raw === "nil") return null;
  if (/^\d+$/.test(raw)) return parseInt(raw, 10);
  if (/^#[a-f0-9-]+$/i.test(raw)) return raw;
  return raw.replace(/^"/, "").replace(/"$/, "");
}

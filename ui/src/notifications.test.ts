import { describe, it, expect, vi } from "vitest";
import { notifyError } from "./notifications";

vi.mock("@tauri-apps/plugin-notification", () => ({
  sendNotification: vi.fn(),
}));

import { sendNotification } from "@tauri-apps/plugin-notification";

describe("notifyError", () => {
  it("sends a notification with title and body", async () => {
    await notifyError("同步错误", "连接失败");
    expect(sendNotification).toHaveBeenCalledWith({
      title: "同步错误",
      body: "连接失败",
    });
  });
});

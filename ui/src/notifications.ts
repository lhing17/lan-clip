import { sendNotification } from "@tauri-apps/plugin-notification";

export async function notifyError(title: string, body: string) {
  sendNotification({ title, body });
}

// Learn more about Tauri commands at https://tauri.app/develop/calling-rust/

use std::sync::Mutex;

/// Sidecar 进程句柄，用于管理 Clojure 后端生命周期。
/// 当前为占位实现，后续将接入真实的进程启动/停止逻辑。
pub struct SidecarState {
    running: bool,
}

impl Default for SidecarState {
    fn default() -> Self {
        Self { running: false }
    }
}

#[tauri::command]
fn greet(name: &str) -> String {
    format!("Hello, {}! You've been greeted from Rust!", name)
}

/// 启动 Clojure sidecar（占位实现）。
#[tauri::command]
fn sidecar_start(state: tauri::State<'_, Mutex<SidecarState>>) -> Result<bool, String> {
    let mut s = state.lock().map_err(|e| e.to_string())?;
    s.running = true;
    Ok(true)
}

/// 停止 Clojure sidecar（占位实现）。
#[tauri::command]
fn sidecar_stop(state: tauri::State<'_, Mutex<SidecarState>>) -> Result<bool, String> {
    let mut s = state.lock().map_err(|e| e.to_string())?;
    s.running = false;
    Ok(true)
}

/// 查询 sidecar 运行状态（占位实现）。
#[tauri::command]
fn sidecar_status(state: tauri::State<'_, Mutex<SidecarState>>) -> Result<bool, String> {
    let s = state.lock().map_err(|e| e.to_string())?;
    Ok(s.running)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sidecar_state_defaults_to_stopped() {
        let state = SidecarState::default();
        assert!(!state.running);
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(Mutex::new(SidecarState::default()))
        .invoke_handler(tauri::generate_handler![greet, sidecar_start, sidecar_stop, sidecar_status])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

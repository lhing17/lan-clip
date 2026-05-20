// Learn more about Tauri commands at https://tauri.app/develop/calling-rust/

use std::sync::Mutex;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{Emitter, Manager};

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

impl SidecarState {
    pub fn start(&mut self) {
        self.running = true;
    }

    pub fn stop(&mut self) {
        self.running = false;
    }

    pub fn is_running(&self) -> bool {
        self.running
    }
}

/// 返回 sidecar HTTP API 端口（当前固定为 9615）。
#[tauri::command]
fn sidecar_port() -> Result<u16, String> {
    Ok(9615)
}

/// 启动 Clojure sidecar（占位实现）。
#[tauri::command]
fn sidecar_start(state: tauri::State<'_, Mutex<SidecarState>>) -> Result<bool, String> {
    let mut s = state.lock().map_err(|e| e.to_string())?;
    s.start();
    Ok(true)
}

/// 停止 Clojure sidecar（占位实现）。
#[tauri::command]
fn sidecar_stop(state: tauri::State<'_, Mutex<SidecarState>>) -> Result<bool, String> {
    let mut s = state.lock().map_err(|e| e.to_string())?;
    s.stop();
    Ok(true)
}

/// 查询 sidecar 运行状态（占位实现）。
#[tauri::command]
fn sidecar_status(state: tauri::State<'_, Mutex<SidecarState>>) -> Result<bool, String> {
    let s = state.lock().map_err(|e| e.to_string())?;
    Ok(s.is_running())
}

/// 返回默认接收文件目录路径（~/.lan-clip/received-files）。
fn received_files_dir() -> Result<String, String> {
    let home = std::env::var("HOME").map_err(|_| "无法获取 HOME 目录".to_string())?;
    Ok(format!("{}/.lan-clip/received-files", home))
}

/// 使用系统默认程序打开指定目录。
fn open_directory(path: &str) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    let cmd = "open";
    #[cfg(target_os = "windows")]
    let cmd = "explorer";
    #[cfg(target_os = "linux")]
    let cmd = "xdg-open";

    std::process::Command::new(cmd)
        .arg(path)
        .spawn()
        .map_err(|e| format!("打开目录失败: {}", e))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sidecar_state_defaults_to_stopped() {
        let state = SidecarState::default();
        assert!(!state.is_running());
    }

    #[test]
    fn sidecar_start_sets_running_to_true() {
        let mut state = SidecarState::default();
        state.start();
        assert!(state.is_running());
    }

    #[test]
    fn sidecar_stop_sets_running_to_false() {
        let mut state = SidecarState::default();
        state.start();
        state.stop();
        assert!(!state.is_running());
    }

    #[test]
    fn sidecar_port_returns_9615() {
        assert_eq!(sidecar_port().unwrap(), 9615);
    }

    #[test]
    fn received_files_dir_returns_default_path() {
        let path = received_files_dir().unwrap();
        assert!(path.ends_with(".lan-clip/received-files"));
    }

    #[test]
    fn open_directory_returns_ok_for_valid_path() {
        let temp_dir = std::env::temp_dir();
        let result = open_directory(temp_dir.to_str().unwrap());
        assert!(result.is_ok());
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(Mutex::new(SidecarState::default()))
        .invoke_handler(tauri::generate_handler![sidecar_start, sidecar_stop, sidecar_status, sidecar_port])
        .setup(|app| {
            // 获取主窗口并设置关闭事件：隐藏窗口而不是退出应用
            if let Some(window) = app.get_webview_window("main") {
                let window_clone = window.clone();
                window.on_window_event(move |event| {
                    if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                        api.prevent_close();
                        let _ = window_clone.hide();
                    }
                });
            }

            // 创建托盘菜单
            let open_i = MenuItem::new(app, "打开窗口", true, None::<&str>)?;
            let toggle_sync_i = MenuItem::new(app, "切换同步", true, None::<&str>)?;
            let open_dir_i = MenuItem::new(app, "打开接收目录", true, None::<&str>)?;
            let quit_i = MenuItem::new(app, "退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open_i, &toggle_sync_i, &open_dir_i, &quit_i])?;

            TrayIconBuilder::new()
                .menu(&menu)
                .on_menu_event(move |app_handle, event| {
                    match event.id().as_ref() {
                        id if id == open_i.id().as_ref() => {
                            if let Some(window) = app_handle.get_webview_window("main") {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                        id if id == toggle_sync_i.id().as_ref() => {
                            let _ = app_handle.emit("tray-sync-toggle", ());
                        }
                        id if id == open_dir_i.id().as_ref() => {
                            if let Ok(dir) = received_files_dir() {
                                let _ = open_directory(&dir);
                            }
                        }
                        id if id == quit_i.id().as_ref() => {
                            app_handle.exit(0);
                        }
                        _ => {}
                    }
                })
                .build(app)?;

            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application");

    app.run(|app_handle, event| {
        if let tauri::RunEvent::Exit = event {
            // 应用退出时停止 sidecar
            if let Ok(state) = app_handle.state::<Mutex<SidecarState>>().lock() {
                if state.is_running() {
                    drop(state);
                    let _ = app_handle.state::<Mutex<SidecarState>>().lock().map(|mut s| s.stop());
                }
            }
        }
    });
}

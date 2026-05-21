// Learn more about Tauri commands at https://tauri.app/develop/calling-rust/

use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{Emitter, Manager};

/// Sidecar 进程句柄，用于管理 Clojure 后端生命周期。
/// 通过 std::process::Command 启动 java -jar 运行 Clojure uberjar。
pub struct SidecarState {
    child: Option<Child>,
}

impl Default for SidecarState {
    fn default() -> Self {
        Self { child: None }
    }
}

impl SidecarState {
    pub fn start(&mut self) -> Result<(), String> {
        if self.is_running() {
            return Ok(());
        }
        let jar_path = find_jar_path()?;
        let child = Command::new("java")
            .arg("-jar")
            .arg(&jar_path)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| format!("启动 sidecar 失败: {}", e))?;
        self.child = Some(child);
        Ok(())
    }

    pub fn stop(&mut self) -> Result<(), String> {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
        Ok(())
    }

    pub fn is_running(&mut self) -> bool {
        match &mut self.child {
            Some(child) => match child.try_wait() {
                Ok(None) => true,
                _ => {
                    self.child = None;
                    false
                }
            },
            None => false,
        }
    }
}

/// 查找 lan-clip uberjar 路径。
/// 开发模式：从 CARGO_MANIFEST_DIR（ui/src-tauri）推导至项目根目录 target/。
/// 生产模式：在应用可执行文件同级目录查找。
fn find_jar_path() -> Result<std::path::PathBuf, String> {
    if let Ok(manifest_dir) = std::env::var("CARGO_MANIFEST_DIR") {
        let path = std::path::PathBuf::from(manifest_dir)
            .join("../../target/lan-clip-1.0-standalone.jar");
        if path.exists() {
            return Ok(path);
        }
    }

    if let Ok(exe_path) = std::env::current_exe() {
        if let Some(exe_dir) = exe_path.parent() {
            let path = exe_dir.join("lan-clip-1.0-standalone.jar");
            if path.exists() {
                return Ok(path);
            }
        }
    }

    Err("未找到 lan-clip uberjar（lan-clip-1.0-standalone.jar）。请先运行 `lein uberjar` 打包。".to_string())
}

/// 返回 sidecar HTTP API 端口（当前固定为 9615）。
#[tauri::command]
fn sidecar_port() -> Result<u16, String> {
    Ok(9615)
}

/// 启动 Clojure sidecar。
#[tauri::command]
fn sidecar_start(state: tauri::State<'_, Mutex<SidecarState>>) -> Result<bool, String> {
    let mut s = state.lock().map_err(|e| e.to_string())?;
    s.start()?;
    Ok(true)
}

/// 停止 Clojure sidecar。
#[tauri::command]
fn sidecar_stop(state: tauri::State<'_, Mutex<SidecarState>>) -> Result<bool, String> {
    let mut s = state.lock().map_err(|e| e.to_string())?;
    s.stop()?;
    Ok(true)
}

/// 查询 sidecar 运行状态。
#[tauri::command]
fn sidecar_status(state: tauri::State<'_, Mutex<SidecarState>>) -> Result<bool, String> {
    let mut s = state.lock().map_err(|e| e.to_string())?;
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
        let mut state = SidecarState::default();
        assert!(!state.is_running());
    }

    #[test]
    fn sidecar_start_and_stop() {
        let mut state = SidecarState::default();
        // 未找到 uberjar 时应返回错误
        std::env::remove_var("CARGO_MANIFEST_DIR");
        let result = state.start();
        assert!(result.is_err());

        // stop 不应 panic
        assert!(state.stop().is_ok());
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

    #[test]
    fn find_jar_path_falls_back_to_exe_dir() {
        std::env::remove_var("CARGO_MANIFEST_DIR");
        let result = find_jar_path();
        // 当前目录下无 jar，应返回错误
        assert!(result.is_err());
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
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
            if let Ok(mut state) = app_handle.state::<Mutex<SidecarState>>().lock() {
                let _ = state.stop();
            }
        }
    });
}

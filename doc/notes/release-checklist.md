# lan-clip Release Checklist

## 构建前准备

- [ ] 确认 `lein test` 全绿
- [ ] 确认 `cargo test`（Tauri）全绿
- [ ] 确认前端 `npm run build` 通过
- [ ] 运行 `lein uberjar` 生成最新 `target/lan-clip-1.0-standalone.jar`
- [ ] macOS：`brew install create-dmg`（生成 `.dmg` 需要）

## macOS

- [ ] `cd ui && npm run tauri build`
- [ ] 产物检查：`ui/src-tauri/target/release/bundle/macos/lan-clip.app`
- [ ] 产物检查：`ui/src-tauri/target/release/bundle/dmg/lan-clip_1.0.0_aarch64.dmg`
- [ ] 验证 `.app/Contents/Resources/lan-clip-1.0-standalone.jar` 存在
- [ ] 双击 `.app` 启动，无崩溃
- [ ] 验证状态页：sidecar 可启动/停止，同步可启停
- [ ] 验证文本互传（localhost 双端或两台机器）
- [ ] 验证图片互传
- [ ] 验证小文件互传（< file-size 限制）
- [ ] 验证托盘菜单：打开窗口、切换同步、打开接收目录、退出
- [ ] 验证退出后无残留 sidecar 进程
- [ ] 验证防火墙提示（首次监听端口）

## Windows

- [ ] `cd ui && npm run tauri build`
- [ ] 产物检查：`ui/src-tauri/target/release/bundle/msi/*.msi`
- [ ] 安装并启动
- [ ] 重复 macOS 验证项中的功能验证

## 已知限制

- 当前打包依赖用户系统已安装 Java 运行时（`java` 在 PATH 中）。后续需评估捆绑 JRE（jlink / GraalVM native-image）方案。
- `.dmg` 生成需 `create-dmg` 工具；若缺失，Tauri 会报错但临时 `.dmg` 文件通常仍可用，手动重命名即可。

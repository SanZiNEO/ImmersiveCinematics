# client（客户端）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/client/`

功能树：

- **编辑器桥接**
  - ✅ `EditorBridgeImpl` 单例实现 `EditorBridge` 接口：setTime→`CameraManager.setTime()`（拖动播放头）、pushScript→`CameraManager.pushScript()`（保存后推送预览）、play/pause/stop→`CameraManager.resume()/pause()/stop()`（`EditorBridgeImpl`）
- **配置界面**
  - ⚠️ `ConfigScreen` 提供配置界面（4 个配置项 + 完成按钮），但无游戏内打开入口（见已知问题）（`ConfigScreen`）
  - ✅ `showSkipHud`：跳过提示 HUD 开关（点击切换 ON/OFF，带悬浮提示）（`ConfigScreen`、`Config`）
  - ✅ `skipHoldThresholdMs`：跳过键长按时长阈值，点击按 +500ms 步进循环（500ms→10000ms 后回绕到 500ms）（`ConfigScreen`、`Config`）
  - ✅ `debugLogging`：调试日志开关（`ConfigScreen`、`Config`）
  - ✅ `editorEnabled`：编辑器开关（关闭即“无编辑器版本”，需重启生效）（`ConfigScreen`、`Config`）
  - ✅ 返回父界面：关闭时恢复上一界面（`ConfigScreen`）

## 已知问题

- `ConfigScreen` 无打开入口：全工程没有任何位置调用 `setScreen(new ConfigScreen(...))`（唯一的 setScreen 调用是编辑器 F6 打开 `EditorScreen`），配置界面实现了但无法从游戏内打开（来源：`ConfigScreen`、`CinematicKeyBindings`）

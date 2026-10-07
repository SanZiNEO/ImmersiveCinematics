# client（客户端）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/client/`

功能树：

- **配置界面**
  - ⚠️ `ConfigScreen` 提供配置界面（3 个配置项 + 完成按钮）：Forge 经 `ConfigScreenHandler` 扩展点从 mods 列表的 Config 按钮打开；Fabric 侧无打开入口（见已知问题）（`ConfigScreen`）
  - ✅ `showSkipHud`：跳过提示 HUD 开关（点击切换 ON/OFF，带悬浮提示）（`ConfigScreen`、`Config`）
  - ✅ `skipHoldThresholdMs`：跳过键长按时长阈值，点击按 +500ms 步进循环（500ms→10000ms 后回绕到 500ms）（`ConfigScreen`、`Config`）
  - ✅ `debugLogging`：调试日志开关（`ConfigScreen`、`Config`）
  - ✅ 返回父界面：关闭时恢复上一界面（`ConfigScreen`）

## 已知问题

- `ConfigScreen` 在 Fabric 上无打开入口：Forge 已通过 `ConfigScreenHandler` 扩展点接入（mods 列表的 Config 按钮），Fabric 侧全工程没有调用 `new ConfigScreen(...)`（来源：`ConfigScreen`、`ForgeClientEvents`）

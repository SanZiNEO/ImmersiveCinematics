> **过期文档（OUTDATED）——不得作为当前设计或实施依据**
>
> 本文未随最新设计更新，仅保留作历史参考。AI 与自动化工具不得据此推断当前格式、功能状态、接口或实施要求，也不得直接照搬其中示例用于新实现。
>
> 当前规划请先查阅 [0.3.6 计划状态索引](../../plans/0.3.6/README.md) 与 [I 轮实施基准](../../plans/0.3.6/I-script-core/README.md)，再按状态查阅对应未完成轮次。
>
> 计划目标不代表已经实现；核实当前代码行为时须结合源码，不得将旧文或未实现计划当作现状。

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

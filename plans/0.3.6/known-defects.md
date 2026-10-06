# 已知缺陷（0.3.6）

> 来源：架构图绘制过程中的 10 份子系统代码复查（报告为临时文件，已删）。
> 均为**只读代码审查**发现，**未在游戏内复现**，按用户判定"都不重要"，记录备查、不阻塞当前工作。
> 记录日期：2026-10-06。

| # | 缺陷 | 位置 | 备注 |
|---|---|---|---|
| 1 | MODE=1 时 `beginFrame` 固定 `i<4` 循环，而 `CAMERAS` 长度只有 1 → `CAMERAS[1]` 数组越界（`camera(int)` 无边界检查） | `proto/QuadrantProto` + `camera/CinematicOcclusion` | javap 实证；仅影响设了 `ICINEMATICS_QUADRANT` 的调试场景；一行可修（循环上限改 `CAMERAS.length`） |
| 2 | 非飞行屏蔽期 `onMove` 不拦截 → vanilla 累积 `accumulatedDX/DY`，`turnPlayer` 被 cancel 不清零，`syncInputStateAfterExit` 也未清理 → 退出过场首帧视角跳变 | `mixin/MouseHandlerMixin` + `control/CinematicController.syncInputStateAfterExit` | 注释只覆盖飞行态 |
| 3 | `PipLayer` 算出的 `fillArgb` / `borderArgb` 从未使用（直接用了常量）→ `opacity` 对 pip 填充与边框完全不生效；且 pip 的 `x/y/width/height` 是原始像素而非屏幕百分比 | `overlay/PipLayer` | pip 目前是占位形态 |
| 4 | `emergencyStop()` 只清 `pendingScript`，**不清 `scriptQueue`**；`deactivateNow` 会从队列接播 → 世界退出时可能误启下一脚本 | `camera/CameraManager` | 潜在误触发 |
| 5 | `C2SPlaybackStarted` **无条件回报**：`handlePlayScript` 不检查 `playScript` 返回值（0 拒绝 / 2 排队也回报"已开始"）→ 并行播放后服务端账本会错位 | `trigger/client/ClientScriptReceiver` | 并行化前无影响 |
| 6 | **多人服**：推送前结构/方块替换只覆盖 `look_at_target_structure` + `position.relative_origin`；触发器路径（`StartPlaybackAction`）直接发 rawJson、零替换 → 触发器播放时结构/方块来源全部不可解析 | `command/CinematicCommand` + `trigger/server/action/StartPlaybackAction` | 单人服不受影响 |
| 7 | `@a` / `@r` / `@n` / `@p[team=…]` 等选择器既不支持本地解析、也不转服务端（warn + null） | `script/CameraTrackPlayer.requiresServerSelector` | 只认 `@e[...]` |
| 8 | `WebPreviewScreen.enterFlightMode` 忽略传入的 `x/y/z` | `webui/WebPreviewScreen` | WebUI 飞控入口 |
| 9 | `PathStrategies` 注册表实际只 `register("linear")`；`bezier` 由 `CameraTrackPlayer` 直接 `new`，动态查表入口对 `bezier` 会回落 `linear` | `script/PathStrategies` | 两条路径不一致 |

## 相关但不属于缺陷

- `CameraManager.activate()`、staged 体系（`stageTarget*` / `commitStagedState` / `isStagedReady` / `TransitionType`）、`StopPlaybackAction` / `PlaySoundAction` / `ExecuteCommandAction`、`MODE_PREWARM`、`TextureLoader.clearCache()`、`CinematicOverlay.OVERLAY_ID`、`CameraAnchorManager.hasAnchor()`、`PreloadRequester.isPreloadActive()` —— **死代码**，按 `camera-state-plan.md` §4 与各子系统计划逐步清理。

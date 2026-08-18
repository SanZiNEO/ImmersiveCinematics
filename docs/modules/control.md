# control（运行时控制）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/control/`

功能树：

- **运行时行为控制**
  - ✅ `CinematicController` 单例持有脚本运行时行为标志（与 `ScriptMeta.RuntimeBehavior` 的 20 个标志一一对应）：可跳过/可打断/结尾保持、屏蔽键盘/鼠标、隐藏 HUD/聊天/记分板/动作栏/标题/字幕/快捷栏/准星/Boss 条/跳过 HUD、隐藏手臂、抑制视角摆动、渲染玩家模型、屏蔽生物 AI、游戏暂停时暂停（`CinematicController`）
  - ✅ `apply()` 在脚本开始时套用行为标志，`revert()` 在播放结束时恢复默认值（三态字段恢复为 null = 未设置）（`CinematicController`）
  - ✅ 提供 `setBlockKeyboard/setBlockMouse` 供编辑器预览模式临时放行输入（`CinematicController`）
- **退出原因与完成原因**
  - ✅ `ExitReason` 枚举 5 种退出请求：FORCE_QUIT（Ctrl+P 强退）、SYSTEM_STOP（系统停止）、INTERRUPTED（被新脚本打断）、USER_SKIP（用户长按跳过）、NATURAL_END（自然播完）（`ExitReason`）
  - ✅ `CompletionReason` 枚举 5 种完成原因：FORCE_QUIT/STOPPED/INTERRUPTED/SKIPPED/FINISHED，随 `C2SScriptFinishedPacket` 回执服务端（`CompletionReason`）
- **跳过与投票**
  - ✅ `CinematicKeyBindings.SKIP_KEY`（默认 C 键）长按跳过：达到 `Config.skipHoldThresholdMs` 阈值后触发 `requestExit(USER_SKIP)`，跳过键不受键盘屏蔽影响（`CinematicKeyBindings`）
  - ✅ `CinematicKeyBindings.EDITOR_KEY`（默认 F6）打开/关闭编辑器，关闭后 500ms 防重开冷却（`CinematicKeyBindings`）
  - ✅ 强制退出：Ctrl+P 组合键直接 `FORCE_QUIT`（`CinematicKeyBindings`）
  - ✅ `SkipHudRenderer` 渲染跳过提示：跳过键图标 + 按键名 + 长按进度环（分段三角填充的圆弧），仅在脚本模式、可跳过且未隐藏时显示（`SkipHudRenderer`）
  - ✅ 多人服务器（非本地）时在屏幕底部居中显示跳过投票进度（投票数/观看者总数，数据来自 `ClientScriptReceiver` 缓存）（`SkipHudRenderer`、`ClientScriptReceiver`）
  - ✅ 跳过 HUD 受 `Config.showSkipHud` 总开关控制（`SkipHudRenderer`）
- **输入屏蔽与路由**
  - ✅ `InputRouter` 接口定义输入路由决策（键盘/鼠标按钮/滚轮/视角转动），两层设计：Mixin 在 HEAD 捕获原始事件，本接口决定目标（`InputRouter`）
  - ✅ `InputTarget` 枚举三种路由结果：GAME（放行）、SELF（拦截但更新自身按键状态，如跳过键）、BLOCK（完全拦截）（`InputTarget`）
  - ✅ 默认路由实现：非激活/无世界时放行；跳过键始终 SELF；block_keyboard 开启且游戏未暂停（或暂停不随游戏）时拦截键盘，Esc 放行；block_mouse 开启时拦截鼠标按钮/滚轮/视角转动（`InputRouter`）
  - ✅ `CinematicController.releaseAllKeys()` 在**播放开始**释放全部按键，清旧状态；**播放退出**改用 `syncInputStateAfterExit()` 优雅交接：`KeyMapping.setAll()` 按当前物理按键状态重同步键盘 + 鼠标按钮单独按 GLFW 状态同步 + 清空鼠标累积量——避免玩家持续按键时退出导致"按键失效直到松开重按"与视角跳变（`CinematicController`、`MouseHandlerMixin.resetAccumulated`）

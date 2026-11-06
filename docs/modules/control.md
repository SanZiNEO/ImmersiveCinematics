# control（运行时控制）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/control/`

## 运行时行为并集

| 能力 | 入口 | 语义 |
|---|---|---|
| 行为开关并集 | `CinematicController.INSTANCE`（`computeUnion` / `applyUnion` / `recomputeUnion`） | 全部活跃实例的 `ScriptMeta.RuntimeBehavior` 逐位取并集：任一实例要求隐藏 / 屏蔽即生效；实例增删后由 `CameraManager` 重算 |
| 三态字段 | `hide_*` / `suppress_*` | `null` = 未声明，`true` / `false` = 显式声明；`revert()` 恢复为 `null` |
| 生命周期门控 | `CameraManager.isAnyPauseWhenGamePaused()` / `isTopInstanceSkippable()` | 无全局副本：暂停联动取并集，跳过提示取**顶层实例**（后来者居上） |
| 编辑器放行 | `setBlockKeyboard` / `setBlockMouse` | 预览模式临时放行输入 |
| 画面扭曲 | `suppress_distortion` | 临时改写原版 `screenEffectScale`，退出时恢复 |

## 画面层入口（screen_layers / screen_layer_mode）

| 项 | 说明 |
|---|---|
| 唯一字段 | `meta.screen_layers`（层名 → 布尔，**`true` = 显示**）与 `meta.screen_layer_mode`（`normal` / `allowlist`） |
| 层名真源 | `script/ScreenLayer`：15 个规范层名 + 11 个旧固定别名 + 注册分类名 + 未注册 overlay 的完整资源 ID |
| 模式归一 | 显式 `screen_layer_mode` > 写了 `screen_layers` → `allowlist` > `normal` |
| 模式并集 | 任一活跃实例 `allowlist` → 整段 `allowlist`；无活跃实例复位 |
| 判定顺序 | `screen_layers[规范层名]` → `screen_layers[原始分类键]` → 该实例旧字段（`hide_*` / `suppress_*` / `render_player_model`）→ `hide_hud` |
| 查询面 | `isLayerHidden(layerName, categoryKey)` / `isLayerHidden(layer)` / `screenLayerMode()` / `isAllowlistMode()` / `anyScreenLayerHidden()` |
| 已移除 | `meta.hud_layers` / `meta.hard_hide_hud`：解析期硬错（「已移除」），不再归一、不再进入运行时判定 |

## 退出原因与完成原因

| `ExitReason`（`requestExit` 入参） | 场景 | 映射的 `CompletionReason` |
|---|---|---|
| `FORCE_QUIT` | Ctrl+P | `FORCE_QUIT`（跳过渐出） |
| `SYSTEM_STOP` | 命令 / 服务端 stop / 世界退出 | `STOPPED`（渐出退场） |
| `INTERRUPTED` | 被新脚本抢占（要求实例可打断） | `INTERRUPTED` |
| `NATURAL_END` | 播放到结尾（`hold_at_end` 时拒绝） | `FINISHED` |

- `ExitReason` **只有上述 4 个值**；`USER_SKIP` 已删除（长按不再退出播放，改为投票）。
- `CompletionReason` 5 值：`FORCE_QUIT` / `STOPPED` / `INTERRUPTED` / `SKIPPED` / `FINISHED`。
- `SKIPPED` **保留但本模组客户端不再产生**：仅供外部 API 与历史回执识别（网络枚举跨版本回执仍可能带它）；服务端收到该值只补记账与投票者标记，不做达标判定。

## 跳过投票（客户端侧）

| 环节 | 行为 |
|---|---|
| 触发 | `SKIP_KEY` 长按累计到 `Config.skipHoldThresholdMs` |
| 门控 | 顶层实例可跳过（`isSkippable()`）且本实例本人尚未投票（`ClientScriptReceiver.hasVoted`） |
| 动作 | 发 `C2SSkipVotePacket(scriptId, instanceId)` → **只计票**；本机继续播放，不再 `requestExit(...)` |
| 本地标记 | `ClientScriptReceiver.markVoted` 覆盖「投票 → 服务端回包」的往返窗口；`skipTriggered` 只防本轮重复发包，松键复位 |
| 已投票后 | 不再显示长按提示、不再响应长按（每人每实例一票） |
| 服务端 | 票数达 `max(1, ceil(在场观众 × 比例 / 100))` 时向该批全部在场者发 `S2CStopScriptPacket` |

## 跳过 HUD（`SkipHudRenderer`）

| 元素 | 内容 | 位置 |
|---|---|---|
| 长按块 | 环形进度 + 键位图标 + 文案 | 元素中心按 `skipHudX` / `skipHudY`（缺省 95 / 90，贴右下） |
| 票数行 | `已投票跳过: %s/%s`；多实例并存时每实例一行、带脚本名 | 顶层实例中心按 `voteHudX` / `voteHudY`（缺省 50 / 90）；其余按实例顺序向下堆叠，行距 `lineHeight + 2` |

- 两个元素各自按**元素中心**的屏幕百分比定位，互不影响，不做越界保护。
- 票数仅在「真多人」或「单人存档已 LAN 发布」时绘制；`Config.showSkipHud` 关闭时两者都不画。
- 票数数据来自 `ClientScriptReceiver` 的按实例快照（`scriptId + "\u0000" + instanceId`），停止 / 结束只清该实例。

## 输入屏蔽与路由

| 组件 | 职责 |
|---|---|
| `InputRouter` | 输入路由决策接口（键盘 / 鼠标按钮 / 滚轮 / 视角） |
| `InputTarget` | `GAME`（放行）/ `SELF`（拦截但更新自身按键状态，如跳过键）/ `BLOCK`（完全拦截）/ `FLIGHT`（交给飞行取景） |
| 默认路由 | 非激活 / 无世界放行；跳过键始终 `SELF`；`block_keyboard` 时拦截键盘（Esc 放行）；`block_mouse` 时拦截鼠标按钮 / 滚轮 / 视角；飞行取景路由到 `FLIGHT` |
| `FlightModeManager` | 飞控会话唯一入口：进入 / 退出 / 取消 / 每帧 tick / 事件转发 / 光学 reset（只恢复 FOV / Zoom / Roll） |
| `FlightController` | 读原版键位与鼠标增量，直接驱动相机 POJO；相对 / 绝对模式、FOV / 变焦 / 翻滚微调 |
| 输入交接 | 播放开始 `releaseAllKeys()`；播放退出 `syncInputStateAfterExit()`（先清原版鼠标累积量，再按物理按键状态重同步键盘 + 鼠标按钮） |

## 键位

| 键位 | 缺省键 | 作用 |
|---|---|---|
| `SKIP_KEY` | C | 长按投一张跳过票（不退出播放） |
| `EDITOR_WEBUI_OPEN` | F9 | 打开 WebUI 预览屏（关闭后 500ms 冷却） |
| `EDITOR_FLIGHT` | F7 | 飞控开关 |
| `EDITOR_FLIGHT_FOV_IN` / `OUT` | `=` / `-` | FOV 微调 |
| `EDITOR_FLIGHT_ZOOM_IN` / `OUT` | `]` / `[` | 变焦微调 |
| `EDITOR_FLIGHT_ROLL_LEFT` / `RIGHT` | Q / E | 翻滚微调 |
| `EDITOR_FLIGHT_MODE` | R | 相对 / 绝对模式切换 |
| `EDITOR_FLIGHT_RESET_OPTICS` | C | 只复位光学参数 |
| （硬编码） | Ctrl+P | `FORCE_QUIT` 强退 |

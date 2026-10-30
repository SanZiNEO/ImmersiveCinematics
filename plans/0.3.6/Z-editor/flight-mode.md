# 飞控

> 状态：绑定入口已落地（2026-10-07）；选中片段画面的全屏 lane 渲染未实现
> 范围：飞控绑定选中相机片段、进入时的姿态采样、退出写回；飞控会话本身由 `FlightModeManager` 承担
> 关联：[`server-protocol.md`](server-protocol.md)（消息）、[`../F-playback/multi-camera-rendering.md`](../F-playback/multi-camera-rendering.md)（lane 渲染）、[`../F-playback/parallel-playback.md`](../F-playback/parallel-playback.md)（预览隔离）

## 一、方向

飞控不再只是“接管全局唯一相机”，而是**绑定到选中的相机轨道片段**——n 条相机轨道中选中轨道 A 的某个片段进入飞控时，该片段的相机画面**自动全屏覆盖预览**（即一个 dest=全屏、opacity=1 的 lane，“单独开了一个镜头”），精确调控的正是这一个相机的状态；退出飞控把最终姿态写回该片段（改造前：`FlightModeManager.enter()` = `CameraManager.pause()` + `setPreviewDirectControl(true)`，`FlightController` 接管全局相机，`exit()` 返回 `FlightState` 供编辑器写回片段）。

**明确不做**：把选中片段的相机画面当 PIP 小窗叠在原画上、或通过某种模式退回原画再操作——预览里处理的就是这个镜头本身。

**依赖**：① [多相机渲染](../F-playback/multi-camera-rendering.md)的 lane 渲染（选中片段相机 → 离屏 FBO → 全屏上屏）；② [并行播放](../F-playback/parallel-playback.md)步骤 5（预览为独立实例，不挤掉游戏内播放）——飞控即“预览实例的单 lane 全屏”形态。多 lane 合成本身在编辑器预览中的表现见[画面合成](../F-playback/camera-composition.md) §5 待定项。

## 二、落地（2026-10-07）：绑定选中片段的相机参数

- **前端发送**：`store.ts` 的 `enterFlightMode()` 用新增的 `operations.sampleCameraPose(clip, state.time)` 采样**选中 CAMERA 片段在播放头处**的姿态，把 `x/y/z/yaw/pitch/roll/fov/zoom` 填进 `editor.enter_flight_mode`（无选中片段 / 片段无关键帧时只发 `absolute`，即改造前的语义）。采样口径与运行时一致：时间越界 clamp 到片段边界；循环片段按周期折算回单次动画（`loop_count` 用尽后停在末段）；段内线性插值，yaw / roll 走最短路径环绕（`blendAngle`），zoom 对数。
- **只发前端能忠实计算的字段**：相对位置的基准点（玩家/实体/结构）与基准朝向、`follow=entity` 的 x/y/z 偏移、贝塞尔路径（弧长参数化）、`look_at` 目标点、片段级 `orient=tangent`、`yaw_base/pitch_base ≠ world`、`cam_breath` 抖动都在游戏端求值——这些字段留空，由接收端逐字段回落当前相机状态（= 预览正在渲染的那一帧姿态）。因此相对位置片段只发送朝向/光学，不发送位置。
- **游戏端无需改动**：`WebEditorApi.handleEnterFlightMode` 已逐字段解析（缺省 = null），`WebPreviewScreen.enterFlightMode` 按提供值覆盖、未提供回落 `CameraManager` 当前状态，再交 `FlightModeManager.enter`（D5 链路）。
- **退出写回**：仍写回选中关键帧（属选中片段），未按“整段”重写。
- **仍缺**：选中片段画面的**全屏 lane 渲染**（“单独开了一个镜头”的画面表现）——依赖 ①②，未实现；本批次只解决“进入飞控时取哪个片段的哪个姿态”。

## 三、已知缺陷（2026-10-06 代码复查）

`WebPreviewScreen.enterFlightMode` 曾忽略传入的 `x/y/z`（只使用 `absolute`，其余 8 个参数丢弃，改用 `CameraManager` 当前姿态）。

**已修（2026-10-07，D5）**：接收端逐字段解析并按字段回落当前相机状态；前端自本批次起发送选中片段在播放头处的参数（见 §二）。

## 四、会话与状态

- 飞控状态归游戏端：编辑器只发 `editor.enter_flight_mode` / `exit_flight_mode` / `cancel_flight_mode`，状态由 `flight.state`（约 10Hz）回推，退出时 `flight.exit` 带最终相机参数与 RELATIVE 基准点。
- 飞控期间相机由飞控接管，编辑器只显示与写回；`FlightModeManager` 不依赖具体界面，预览屏与键盘中转共用。
- 编辑器只负责进入、退出与状态显示；飞控留在游戏原生环境。

## 五、剩余

选中片段画面的全屏 lane 渲染；飞控远程面板（远期）。

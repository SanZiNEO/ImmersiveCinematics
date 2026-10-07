# 0.3.6 过渡：六参数状态过渡（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 接口、字段、曲线细节、JSON、打断规则等执行时再定
>
> 相关文档：
> - [相机状态与覆盖链](./camera-state-plan.md)
> - [数学函数模型](./math-models.md)
> - [时间插值](./temporal-interpolation.md)
> - [迟滞](./hysteresis.md)

---

## 1. 定位（已确认）

**过渡（Transition）**：

> 相机从状态 A 到状态 B 的变化过程。

- 完全围绕 6 个底层参数：position、yaw、pitch、roll、fov、zoom
- 属于**应用层**
- 用于非瞬时切镜头
- 目的是丰富相机运动、让转场更自然

直接切镜头 = 一种特殊的过渡（duration = 0）。

---

## 2. 与时间插值的区别

见[时间插值](./temporal-interpolation.md) §2 的对比表。

---

## 3. 过渡来源（方向）

- 关键帧 / 片段之间
- look_at / follow 目标切换
- Base Provider 切换（脚本 / 编辑器 / 飞行 / 外部）
- 外部 Override 的 push / release
- 编辑器 seek
- 脚本打断 / 排队接播
- 未来其他相机模式切换

---

## 4. 过渡类型（方向）

- **Cut**：硬切，无中间过程
- **Blend**：6 个参数同时过渡
- **分参数过渡**：每个参数独立选择切 / 过渡 / 保持
- **曲线过渡**：用缓动 / 贝塞尔 / 自定义曲线控制节奏
- **分层 / 链式过渡**：A→中间→B，或 A→B→C
- **双状态混合**：A / B 按权重混合

> 具体类型清单和优先级执行时再定。

---

## 5. 六参数语义（方向）

- `position`：世界坐标或相对基准；可线性 / 贝塞尔 / 样条
- `yaw` / `pitch`：角度环绕、最短路径、多圈语义
- `roll`：可独立切 / 过渡 / 保持
- `fov`：注意安全范围
- `zoom`：建议对数 / 几何域
- 每个参数可以有独立的时长、曲线、模式

---

## 6. 与覆盖链的关系（方向）

过渡可能放在：

- Base Provider 层：Base 切换时做过渡
- Modifier 层：作为临时修改
- 独立过渡层：专门管理 A→B 状态变化

需要明确它与 Base / Modifier 的优先级、打断和叠加关系。

---

## 7. 可能的问题

- 过渡放在哪一层
- 打断规则（snap / queue / blend / inherit）
- 分参数过渡的默认行为
- 曲线与数值域规则
- 状态 A / B 的采样时机
- 与关键帧插值的边界
- 与 look_at 目标切换的边界
- 与外部 Override 的交互
- 过渡链 / 中断恢复
- 性能与确定性
- 编辑器如何配置与预览
- 外部 API 开放时机

---

## 8. 待定

- 过渡类型第一版范围
- 默认时长 / 默认曲线
- 分参数默认值
- 打断规则默认行为
- 过渡与 Base / Modifier 的最终关系
- JSON / 接口 / API

---

## 9. 落地顺序（方向）

1. 定义过渡的六参数语义
2. 支持 cut / blend
3. 支持分参数过渡
4. 接入曲线（数学函数模型）
5. 接入 Base / Modifier 链
6. 支持打断 / 链式
7. 编辑器 UI
8. 内部稳定后考虑外部 API

---

## 10. 相关文档

- [相机状态与覆盖链](./camera-state-plan.md)
- [数学函数模型](./math-models.md)
- [时间插值](./temporal-interpolation.md)
- [迟滞](./hysteresis.md)

---

## 事实核查（2026-10-07）

> 核查依据：本仓库源码。§1–§10 设计/方向内容未改动。

### ① 核实为真（§3 过渡来源 / §5 六参数语义的现状挂点）
- **morph 过渡已存在**：`transition` 枚举 `{cut, morph}`（`script/schema/TrackSchemas.java:30`、`script/ScriptValidator.java:166`）、`TransitionType.CUT/MORPH`（`script/TransitionType.java`）、`Clip.isMorph()` / `getTransitionDuration()`（默认 0.5，`script/Clip.java:71-77`）。实现：`CameraTrackPlayer.renderMorph(...)`（`script/CameraTrackPlayer.java:247`）——B 模型重叠区 `[prevEnd−t/2, prevEnd+t/2)`（:139-157），按 weight 对 **position + yaw/pitch/roll/fov/zoom 全 6 参数**交叉混合（角度用 `blendAngle`）。即 §4 的 "Cut + Blend + 双状态混合" 已有原型。
- **切线朝向已存在**：`orient` 枚举 `{manual, tangent}`（`ScriptValidator.java:167`）；`TangentOrientation.compute(...)`（`script/TangentOrientation.java`）由路径切线求 yaw/pitch，叠加 `yaw_offset`/`pitch_offset`；入口 `CameraTrackPlayer.isTangentOrientation`（:820）/ :791-800。
- **look_at / follow 目标切换**：`CameraTrackPlayer.selectorPolicy` + `smoothTargetPoint` + `TargetLock`（:1052-1054、:1197），角色 follow / look_at / look_at_target / facing_origin。
- **编辑器 seek = 硬定位（无过渡）**：`WebEditorApi.handleSeek`（`webui/WebEditorApi.java:211`）→ `CameraManager.pause()` + `setTime(t)`（`camera/CameraManager.java:229`）→ `scriptPlayer.alignTime(...)`，下一帧 `CameraTrackPlayer` 经 `setPositionDirect`/`setAllDirect` 直写（`CameraTrackPlayer.java:312-313`）。
- **六参数各自独立**：`CameraProperties` 五属性各持独立 `AnimValue`（`camera/CameraProperties.java:29-63`），`CameraPath` 单独管位置——与 §5 "每参数可独立时长/模式" 方向对应。

### ② 已修正
- 无（§3/§5 均为方向性表述，未发现与代码冲突的现状断言）。

### ③ 补全
- §3 所列 "Base Provider 切换（脚本 / 编辑器 / 飞行 / 外部）" 现状：脚本 = `CameraTrackPlayer`；编辑器预览 = `CameraManager` preview（`pushScript` / `setTime` / `previewSetCamera`，`CameraManager.java:207-…`）；飞行 = `control/FlightController`（直写 :294-297）；"外部" 暂无公开输入入口（脚本 JSON 为唯一外部输入）。
- §5 的 "分参数过渡" 已有原语：`CameraProperties.AnimValue.setTarget(v, dur)` 每参数独立时长；`CameraManager.stageTargetPosition/Yaw/...` + `commitStagedState()`，仅用于编辑器 staged 预览。
- 位置路径现状：`PathStrategies` 注册表静态仅注册 `"linear"`；`"bezier"`（`BezierPathStrategy`，含 ArcLengthLUT 匀速参数化）由 `CameraTrackPlayer` 直接持有实例（`CameraTrackPlayer.java:23`），未进注册表。样条（CatmullRom）仅 `script/PathStrategy.java` javadoc 列为未来项。

### ④ 未验证
- §3 的 "外部 Override 的 push / release"、"脚本打断 / 排队接播" 作为过渡来源列出，未在代码中找到对应的公开实现入口——**未验证**（可能为规划项）。

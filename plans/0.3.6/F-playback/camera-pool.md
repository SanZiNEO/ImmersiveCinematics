# 相机租约池（按片段）

> 状态：方向已确认，实现未做（2026-10-22）。
> 范围：客户端 lane 相机的生命周期与视角状态。服务端相机中心 / 刷怪 / 预加载见
> [`camera-center-entities.md`](camera-center-entities.md)；编辑器（`editor/`）不在范围内。
> 术语：**片段** = CAMERA 轨道上的一个 `Clip`；**一次相机调用 = 一个片段**。

---

## 一、已确认口径

1. **一次相机调用 = 一个片段。** 同一轨道上的多个片段是**不同的相机**，不是同一台相机的分段。
   代码事实：`CameraLane` 带 `clip`，`LaneSnapshotCollector` 对每个活跃 clip 单独求值；
   同一轨道在重叠窗口内会产出多条 lane。
2. **租约粒度 = 片段。** 不是轨道，也不是帧。
3. **池子是池子，相机只是借用 ID。** acquire 时槽位必须干净；release 时必须清状态，不能把
   上一片段的相机 / 视角 / 颜色历史带进下一片段。
4. **编辑器预览是普通游戏内播放实例。** `PreviewChannel` 通过
   `PlaybackLifecycle.startScriptInternal(..., preview=true)` 建的是普通 `PlaybackInstance`，
   走同一条 `ScriptPlayer` → `CameraTrackPlayer` → `LaneRenderer` 路径；只是时钟、列表位置、
   暂停联动 / 账本参与方式不同。因此预览与游戏实例使用**同一个池**，不做预览专用池或预留槽。
5. **FBO 不进池。** `LaneRenderer` 只有一份共享 `offscreenTarget` 和一份共享 `adjustTarget`，
   所有 lane 顺序渲染、复用同一张缓冲；不是每个相机一份，也不承载跨片段的相机状态。
6. **不改 `editor/`。**

---

## 二、代码事实（2026-10-22 核对）

| 事实 | 位置 |
|---|---|
| `CameraLane` 记录 `(state, clip, clipLocalTime)`，片段是相机快照的一部分 | `script/CameraLane.java` |
| `LaneSnapshotCollector` 对重叠窗口内每个活跃 clip 单独 `renderSingle`，逐条产出 lane | `camera/source/LaneSnapshotCollector.java` |
| `LaneFrame` 只有 `(lane, cameraTrackIndex, laneAdjust)`，没有片段身份 | `script/LaneFrame.java` |
| `ScriptLaneDriver` 用 `scriptId + "_cam" + cameraTrackIndex` 生成 `captureId` | `client/lane/ScriptLaneDriver.java` |
| `ViewScope.enter/exit` 用这个 `captureId` 作为视角名，驱动 `ViewStateCapture` / `ColorHistoryCapture` | `client/lane/ViewScope.java` |
| `ViewStateCapture.saved` / `ColorHistoryCapture.snapshots` 都以视角名为 key；前者靠连续 2 帧不活跃释放 | `client/lane/ViewStateCapture.java`、`ColorHistoryCapture.java` |
| `LaneRenderer.lane(index)` 按帧内序号取槽位；`clear()` 只停用，不销毁槽位与 `Camera` | `client/lane/LaneRenderer.java` |
| `LaneCullSlots` 已经是池：按需创建、停播归还、清空复用 | `client/lane/LaneCullSlots.java` |
| `LaneRenderer` 的 `offscreenTarget` / `adjustTarget` 是共享缓冲，按主画面尺寸创建 / 重建 | `client/lane/LaneRenderer.java` |
| `PreviewChannel` 的 `pushScript` / `setTime` / `resume` 建普通预览 `PlaybackInstance` | `camera/PreviewChannel.java` |
| `PlaybackInstance.stop` 清播放器、Modifier、规则槽与覆盖层 | `camera/PlaybackInstance.java` |

---

## 三、要解决的问题

### 3.1 片段共用视角 key

`LaneFrame` 只带轨道序号，`ScriptLaneDriver` 的 `captureId` 因此是**轨道级**：

```text
scriptId + "_cam" + cameraTrackIndex
```

同一轨道的两个片段、同一脚本的多次播放，都会命中同一个 `captureId`。`ViewScope` 用这个字符串
恢复 `ViewStateCapture` 的 tracker / previous 和 `ColorHistoryCapture` 的 colortex 历史，结果是：

- 片段 B 进入时，可能先恢复片段 A 保存的视角状态；
- 同一脚本重播时，可能先恢复上一次播放保存的视角状态；
- 当前帧写入只要有一处对不上，就会表现为相机停在旧片段 / 旧播放的某一帧，或出现历史视角。

这不是“相机对象太多”的内存问题，而是**视角状态的身份错了**：状态按轨道归属，实际应按片段租约归属。

### 3.2 槽位身份是帧序号

`LaneRenderer.Lane` 按帧内 lane 序号复用，`clear()` 只把 `state` 置空；`Camera` 实例和槽位跨帧保留。
帧序号是**绘制顺序**，不是相机身份。片段切换时，同一个槽位可能被另一台相机占用，而它的
`captureId`、视角状态、剔除槽、合成参数仍可能残留上一片段的值。

### 3.3 清理是启发式，不是所有权

- `ViewStateCapture.releaseStale` 连续 2 帧不在活跃列表才释放；
- `ColorHistoryCapture` 只能经 `ViewStateCapture.releaseView` 释放；
- `LaneCullSlots` 有池，但只覆盖剔除槽，不覆盖相机 / 视角状态；
- `LaneRenderer.clear()` 每帧停用再重注册，槽位不归还。

这些机制叠加后，没有任何一个对象对“一条相机从片段开始到片段结束”的完整生命周期负责。
最近一次光影修复（`4390941`）解决的是跨视角污染，不是租约生命周期；它新增的 per-view 状态表
正是本次要收进租约的部分。

---

## 四、租约契约

### 4.1 租约字段

一条 `CameraLease` 至少包含：

| 字段 | 作用 |
|---|---|
| `slotId` | 池槽编号，free-list 复用 |
| `generation` | 租约代际；释放后旧句柄失效，防止旧状态被新租约读到 |
| `owner` | 播放实例 + 片段身份（哪个实例的哪个 clip） |
| `camera` | 该片段使用的原版 `Camera` 实例 |
| `cullSlot` | 该片段的 `LaneCullSlot` |
| `viewKey` | 视角状态 key，至少包含 `slotId + generation` |
| `captureId` | 仅用于日志 / 调试文件名，不再承担状态身份 |

### 4.2 acquire（片段激活）

- 从 free-list 取最小空闲 `slotId`；`generation++`；
- 清空该槽位的 `Lane` 状态：`state` / `content` / `adjust` / `captureId`；
- 清空 / 复位 `Camera` 的旧状态，不沿用上一片段的位置 / 朝向；
- 取一个干净的 `LaneCullSlot`；
- 视角状态标记为“首帧”：`ColorHistoryCapture` 首帧清 live，不恢复旧快照；`ViewStateCapture`
  不读取该 `viewKey` 之外的任何旧值；
- 合成参数槽（opacity / source / dest / mask）复位，防止上一片段残留。

### 4.3 release（片段结束）

- 删除该租约的 `ViewStateCapture` 快照与 `ColorHistoryCapture` 纹理；
- 清空 `Camera` / `Lane` / 合成参数；
- 归还 `LaneCullSlot`；
- `slotId` 回 free-list；`generation` 递增，旧租约句柄永久失效；
- 重复 release 必须被代际检查挡住，不能释放新租约。

### 4.4 身份与状态 key

- 视角状态按 `slotId + generation` 归属，不按 `captureId`；
- `captureId` 改成片段级标签（例如 `scriptId_camN_clipM`），只用于日志 / 调试捕获文件名；
- 同一轨道的两个片段必须得到两个不同的租约与两个不同的 `viewKey`；
- 同一脚本重播必须得到新的 `generation`，不能恢复上一次播放的视角状态。

### 4.5 渲染顺序

`slotId` 是身份，不是层序。`LaneRenderer` 接收本帧**有序租约列表**；合成顺序 = 列表顺序，
保持现有“先注册在下、后注册在上”的语义。不能把 `slotId` 大小当合成顺序。

### 4.6 所有权与释放时机

- **acquire**：片段变为活跃时，由 `CameraTrackPlayer` / `LaneSnapshotCollector` 一侧发起；
- **release**：片段结束时；实例停止 / 强制停止 / 世界卸载时由 `PlaybackLifecycle` 兜底释放全部；
- **异常**：release 必须在 `finally` 路径执行，不能依赖帧末 `clear()`；
- **预览**：与游戏实例同一套 acquire/release，不特殊处理；
- **条件 lane**：片段仍活跃但某一帧目标不可用时，不释放租约；片段结束才释放，避免 ID 抖动与历史重建。

---

## 五、迁移点

| 位置 | 改动 |
|---|---|
| `LaneFrame` | 增加片段身份 / 租约句柄，不能只带 `cameraTrackIndex` |
| `CameraTrackPlayer` / `LaneSnapshotCollector` | 片段激活 / 结束时 acquire / release；把租约挂到 lane 快照 |
| `ScriptLaneDriver` | 去掉每帧 `clear` + 按 index 重注册；改用有序租约列表；`captureId` 只做标签 |
| `LaneRenderer` | 去掉 `lane(index)` / `setLane(index)` 的位置身份；按租约渲染；合成顺序读有序列表 |
| `ViewScope` / `ViewStateCapture` / `ColorHistoryCapture` | key 改为租约；release 时删纹理 / 清矩阵；去掉按 `captureId` 的 2 帧 stale 释放 |
| `LaneCullSlots` | 由租约持有，release 时归还 |
| `LaneDebugCapture` / `LaneDiagnostics` | 读租约 id + `captureId` 标签，不持有生命周期 |
| `PlaybackLifecycle` | `deactivateNow` / `emergencyStop` / 世界卸载释放该实例全部租约 |
| `editor/` | 不改 |

---

## 六、验收

实现后至少验证：

1. **同轨多片段**：同一轨道两个相邻片段得到两个不同 `viewKey`；片段 B 不恢复片段 A 的
   tracker / previous / colortex 历史。
2. **重播**：同一脚本连续播放 100 次，每次停止后活跃租约数回到 0，free-list 回到初始容量；
   `ViewStateCapture` 除 `main` 外无残留，`ColorHistoryCapture.snapshots` 为空。
3. **画面**：重播后首帧与第一次播放首帧一致，不出现“停在上一次某一帧 / 历史视角”。
4. **异常路径**：强停、断线、世界卸载、片段渲染异常都不留下租约或视角快照。
5. **预览并行**：预览实例与游戏实例同时播放时，两边各自 acquire / release，互不释放对方的租约。
6. **合成顺序**：租约 ID 变化不影响叠放顺序；顺序只由本帧有序列表决定。

---

## 七、边界

- 本文只定义**客户端相机租约**的身份、清洁契约与迁移点；池容量、耗尽策略不在本文范围，另行拍板。
- 服务端相机中心 / 锚点 / 刷怪 / 预加载的状态与释放见
  [`camera-center-entities.md`](camera-center-entities.md) §2.7；它们不进客户端相机池。
- FBO 不进池；共享 `offscreenTarget` / `adjustTarget` 维持现状。
- 编辑器（`editor/`）不参与本设计。

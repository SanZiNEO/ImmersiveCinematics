# 相机租约池（按片段）

> 状态：已实现（2026-10-26）
> 范围：客户端 lane 相机的槽位归属、视角状态与合成顺序。服务端相机中心 / 刷怪见 [`camera-center-entities.md`](camera-center-entities.md)；编辑器（`editor/`）不在范围内。
> 关联：`multi-camera-rendering.md`（每 lane 渲染）、`iris-oculus-compat.md`（每视角状态隔离）、`docs/modules/camera.md`。

## 一、摘要

多相机播放里，一个 CAMERA 片段就是一次相机调用：它需要一台相机、一份视角状态（光影的上一帧矩阵与颜色历史）、一个剔除槽。实现前这些资源按轨道编号与帧序号复用，片段切换与重播会把上一片段的状态带进来；现行实现改为让每条片段在激活时借到一个干净槽位、结束时归还并清空，槽位编号与代际成为视角状态的唯一身份，合成顺序仍由时间轴叠放决定。跨维度时视角身份带维度，避免两个维度的 view 共用历史 / 剔除状态。已在 `CameraLease` / `CameraLeasePool` 落地（2026-10-26，见 §六）。

## 二、为什么做

### 2.1 问题（实现前的状态；现行行为见 §六）

- 同一轨道相邻两个片段共用同一个视角身份，后一片段进入时会先恢复前一片段的视角状态。
- 同一脚本重播时，新一次播放会恢复上一次播放留下的视角状态，画面可能停在上一次的某一帧。
- 槽位按绘制顺序编号，片段切换时同一个槽位可能换了一台相机，而它的视角快照、剔除槽、合成参数还留着上一片段的值。
- 视角快照只在连续两帧不活跃后才释放，属于启发式回收；没有任何对象对「一条片段从开始到结束」负责。
- 实例停止、强停、世界卸载时，lane 侧资源没有确定的归还点。

### 2.2 目标

- 同一轨道的两个片段得到两个互不相同的视角身份，片段 B 不继承片段 A 的任何视角状态。
- 重播从干净状态开始：停止后活跃租约归零，空闲表回到初始容量，视角快照除主视角外清空。
- 片段结束即归还槽位并清除状态；重复归还不能误伤新租约。
- 异常路径（强停、断线、世界卸载、渲染异常）不留下租约或视角快照。
- 预览实例与游戏实例并行时各借各的，互不释放对方的租约。
- 合成顺序只由本帧的片段叠放顺序决定，与槽位编号无关。

### 2.3 非目标

- 不接管离屏缓冲：所有 lane 继续共用同一张离屏与调色缓冲。
- 不接管服务端的相机中心、锚点与刷怪状态。
- 不改编辑器：编辑器预览走普通播放实例，不另设池或预留槽。
- 不定义池容量与耗尽策略。

## 三、方案

对脚本作者与调试者来说，行为应当变成：

- 每个片段是一台独立的相机。同一轨道前后两个片段之间没有状态继承，前一片段的视角、颜色历史、剔除结果都不会出现在后一片段的首帧。
- 重播是干净重来。同一个脚本播第二遍，首帧与第一遍首帧一致，不会出现「停在上一次某一帧」或历史视角残影。
- 并行播放互不干扰。预览与游戏实例同时播放时，各自占用自己的槽位，一方停止不会清掉另一方的状态。
- 叠放顺序仍由时间轴决定：先激活的片段在下、后激活的在上；槽位编号只用于识别，不参与层序。
- 片段内部出现空档（目标实体暂时不可用、该帧不产画面）时，租约保持，不因单帧缺失而反复重建视角历史。

边界：

- 租约只管客户端 lane 的槽位与视角状态，不改变画面内容、不改变合成参数。
- 一个片段跨越多帧、跨多个渲染 pass，租约跟着片段走，不跟着帧走。
- 池容量与超并发时的降级行为另行定义。

## 四、技术细节

### 4.1 核心模型

- 相机租约（拟新增 `CameraLease`）：一次片段调用的句柄，含槽位编号、代际、维度、属主（播放实例 + 片段）、相机实例、剔除槽、视角键、调试标签。
- 槽位池：空闲表按最小空闲槽位优先分配；槽位持有相机实例与 lane 渲染状态，跨片段复用，但不跨租约复用状态。
- 视角状态表：按视角键保存光影的上一帧矩阵与位置跟踪值、每个颜色历史目标的一对快照纹理。键 = 维度 + 槽位编号 + 代际。
- lane 渲染器（`LaneRenderer`）：每帧接收一个有序租约列表，按列表顺序渲染与合成；槽位编号不参与排序。
- 剔除槽池（`LaneCullSlots`）：沿用现有按需创建、停播归还的池，改为由租约持有。

### 4.2 契约

- 获取：片段变为活跃时，由相机轨道求值一侧发起；从空闲表取最小槽位，代际自增，清空该槽位的渲染状态、相机姿态、合成参数，取一个干净剔除槽，视角状态按「首帧」处理（不恢复任何旧快照）。
- 维度：租约记下该片段的维度（CAMERA clip 的 `dimension` 缺省 / 空串 = 玩家当前维度）；维度变化 = 新的视角身份，不把旧维度的历史 / 剔除状态带进来。
- 归还：片段结束时释放该租约的视角快照与颜色历史纹理，清空相机与合成参数，归还剔除槽，槽位回空闲表，代际自增使旧句柄永久失效。
- 兜底：实例停止、强制停止、世界卸载由播放生命周期统一释放该实例的全部租约；释放走 finally，不依赖帧末清空。
- 幂等：重复归还要被代际检查挡住，不能释放已经分配给新租约的槽位。
- 空档：片段仍活跃但某帧不产画面时不归还租约，片段结束才归还。
- 状态归属：租约归创建它的播放实例；视角状态表与剔除槽池只被租约读写。

### 4.3 数据形态

- 视角键：维度 + 槽位编号 + 代际；代际在每次归还后自增。
- 调试标签：片段级标识（脚本、相机轨序号、片段序号、维度），只用于日志与调试捕获文件名，不承担状态身份。
- 空闲表：槽位编号的集合，取用按最小空闲优先。
- 每视角快照：若干 4×4 矩阵与三维向量，加上每个颜色历史目标的一对纹理（主 / 备用），尺寸与格式跟随 live。

### 4.4 边界

- 离屏缓冲与调色缓冲不进池，仍按主画面尺寸共享。
- 服务端相机中心、锚点、刷怪状态不进客户端池。
- 跨维度：不同 `DimensionView` 不共用视角状态；维度变化按新租约 / 新视角处理，归还时释放该维度的快照。
- 编辑器不参与：预览实例与游戏实例走同一套获取 / 归还。
- 池容量、耗尽时的等待或降级策略另行定义。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 保持轨道级视角键 | 继续用脚本 id + 相机轨序号作为视角身份 | 同轨多片段、重播都命中同一份状态 | 身份与片段不对应，正是当前问题 |
| 用帧序号做身份 | 沿用 lane 序号作为槽位身份 | 序号是绘制顺序，片段切换时槽位会换相机 | 身份不稳定 |
| 每片段新建相机与状态 | 不池化，片段结束即丢弃 | 每片段重建相机与光影历史，首帧成本高，分配压力大 | 池化的收益正是复用槽位 |
| 每 lane 一份离屏缓冲 | 每条 lane 独立 FBO | 显存随 lane 数线性增长 | 与共享缓冲的既有结论冲突 |
| 只给视角键加片段后缀 | 保留现有槽位与清理，仅改 key | 仍无所有权与确定性释放，泄漏靠启发式 | 只治串状态，不治生命周期 |
| 停播时全局清空视角状态 | 一次性清掉所有视角快照 | 并行实例（预览 + 游戏）会互相清状态 | 破坏并行播放 |

**本方案的缺点**：

- 多一层租约间接，调试要同时看槽位、代际与调试标签。
- 每个新片段都要重建光影历史，首帧可能比复用旧状态更慢。
- 空档期保持租约，归还时机比「每帧清」更难推理。
- 迁移面覆盖快照记录、驱动、渲染器、视角作用域四处，漏一处就退化成旧的串状态。
- 池容量与耗尽策略未定，超并发时的行为没有保证。

## 六、实现结果

状态：已实现（2026-10-26）

| 项 | 实现 |
|---|---|
| 租约模型 | 每个 CAMERA 片段一个 `CameraLease`（`owner` / `clip` / `dimension` / `slotId` / `generation` / `viewKey` / `cull`），由 `CameraLeasePool` 按 `owner + clip` 对象身份复用；片段首次出现时 acquire。槽位按从未分配计数（不拿数组容量当已分配数），释放后回空闲表最小优先复用 |
| 视角键 | `lease<slot>g<generation>`，维度非空时追加 `@<dimension>`；`ViewScope` / `ViewStateCapture` / `ColorHistoryCapture` 均按该键隔离，同轨相邻片段与重播不继承旧历史。新租约首帧没有自己的 `ViewStateCapture` 快照时回落到 `main` 基线（颜色历史首帧清空），避免继承上一条 lane 的 tracker / previous |
| 生命周期 | 片段结束 / 实例停止 / 替换脚本 / 世界卸载 / lane 收集异常时确定性归还；`reconcile` 按本帧活跃片段列表（含 morph 两侧与目标暂时不可用的片段）保留租约 |
| 剔除槽 | 租约独占 `LaneCullSlot`，归还时回 `LaneCullSlots` 池；无租约的链输出 lane 仍走惰性回退槽。`LaneRenderer.deactivate` 只断开租约槽引用、不归还（归还权在池），避免与 `release` 双重归还；回退槽仍由 lane 持有 |
| 日志 | `Config.debugLogging` 下输出 `[camera-lease] acquire / release / releaseOwner`（只在新租约 / 归还时，不按帧刷）与 `[lane] 活跃 lane: captureId=viewKey`（签名变化才打）；`releaseOwner` 无租约时静默 |
| 释放路径 | `PlaybackInstance.stop` / `replaceScript`、`ScriptLaneDriver` 的停播实例与异常分支、`ClientEventHandler` 世界退出兜底；释放后槽位回空闲表，代际在下次分配时自增。无租约的链输出 lane 用固定键 `chain`，在「本帧不再注册链输出」时释放一次（世界退出再兜底），不会每帧清历史 |
| 合成顺序 | 仍由 `ScriptLaneDriver` 本帧注册顺序（实例启动顺序 → 轨道 → 片段 → `z_index`）决定，`slotId` 不参与排序 |
| 预览 / 游戏隔离 | 同一池、按播放实例身份登记；`reconcile` / `releaseOwner` 只作用于本实例，预览与游戏实例互不释放 |
| 实现偏离 | 相机实例仍由 `LaneRenderer.Lane` 按帧序号持有（每帧 `camera.setup` 覆盖姿态），租约只承载视角身份 + 剔除槽。设计 §4.1 曾把 `Camera` 列进租约字段，本次未迁入：相机对象无跨片段状态，迁入只会扩大改动面 |
| 已知边界 | 离屏缓冲 / 调色缓冲（FBO）不进池；`release` 只在 lane pass 之外调用（`LaneCullSlots.depth() == 0`）；池容量 = 并发片段数，无上限 / 耗尽策略 |

## 附录：重写前原文

### 相机租约池（按片段）

> 状态：方向已确认，实现未做（2026-10-22）。
> 范围：客户端 lane 相机的生命周期与视角状态。服务端相机中心 / 刷怪 / 预加载见
> [`camera-center-entities.md`](camera-center-entities.md)；编辑器（`editor/`）不在范围内。
> 术语：**片段** = CAMERA 轨道上的一个 `Clip`；**一次相机调用 = 一个片段**。

---

#### 一、已确认口径

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

#### 二、代码事实（2026-10-22 核对）

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

#### 三、要解决的问题

##### 3.1 片段共用视角 key

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

##### 3.2 槽位身份是帧序号

`LaneRenderer.Lane` 按帧内 lane 序号复用，`clear()` 只把 `state` 置空；`Camera` 实例和槽位跨帧保留。
帧序号是**绘制顺序**，不是相机身份。片段切换时，同一个槽位可能被另一台相机占用，而它的
`captureId`、视角状态、剔除槽、合成参数仍可能残留上一片段的值。

##### 3.3 清理是启发式，不是所有权

- `ViewStateCapture.releaseStale` 连续 2 帧不在活跃列表才释放；
- `ColorHistoryCapture` 只能经 `ViewStateCapture.releaseView` 释放；
- `LaneCullSlots` 有池，但只覆盖剔除槽，不覆盖相机 / 视角状态；
- `LaneRenderer.clear()` 每帧停用再重注册，槽位不归还。

这些机制叠加后，没有任何一个对象对“一条相机从片段开始到片段结束”的完整生命周期负责。
最近一次光影修复（`4390941`）解决的是跨视角污染，不是租约生命周期；它新增的 per-view 状态表
正是本次要收进租约的部分。

---

#### 四、租约契约

##### 4.1 租约字段

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

##### 4.2 acquire（片段激活）

- 从 free-list 取最小空闲 `slotId`；`generation++`；
- 清空该槽位的 `Lane` 状态：`state` / `content` / `adjust` / `captureId`；
- 清空 / 复位 `Camera` 的旧状态，不沿用上一片段的位置 / 朝向；
- 取一个干净的 `LaneCullSlot`；
- 视角状态标记为“首帧”：`ColorHistoryCapture` 首帧清 live，不恢复旧快照；`ViewStateCapture`
  不读取该 `viewKey` 之外的任何旧值；
- 合成参数槽（opacity / source / dest / mask）复位，防止上一片段残留。

##### 4.3 release（片段结束）

- 删除该租约的 `ViewStateCapture` 快照与 `ColorHistoryCapture` 纹理；
- 清空 `Camera` / `Lane` / 合成参数；
- 归还 `LaneCullSlot`；
- `slotId` 回 free-list；`generation` 递增，旧租约句柄永久失效；
- 重复 release 必须被代际检查挡住，不能释放新租约。

##### 4.4 身份与状态 key

- 视角状态按 `slotId + generation` 归属，不按 `captureId`；
- `captureId` 改成片段级标签（例如 `scriptId_camN_clipM`），只用于日志 / 调试捕获文件名；
- 同一轨道的两个片段必须得到两个不同的租约与两个不同的 `viewKey`；
- 同一脚本重播必须得到新的 `generation`，不能恢复上一次播放的视角状态。

##### 4.5 渲染顺序

`slotId` 是身份，不是层序。`LaneRenderer` 接收本帧**有序租约列表**；合成顺序 = 列表顺序，
保持现有“先注册在下、后注册在上”的语义。不能把 `slotId` 大小当合成顺序。

##### 4.6 所有权与释放时机

- **acquire**：片段变为活跃时，由 `CameraTrackPlayer` / `LaneSnapshotCollector` 一侧发起；
- **release**：片段结束时；实例停止 / 强制停止 / 世界卸载时由 `PlaybackLifecycle` 兜底释放全部；
- **异常**：release 必须在 `finally` 路径执行，不能依赖帧末 `clear()`；
- **预览**：与游戏实例同一套 acquire/release，不特殊处理；
- **条件 lane**：片段仍活跃但某一帧目标不可用时，不释放租约；片段结束才释放，避免 ID 抖动与历史重建。

---

#### 五、迁移点

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

#### 六、验收

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

#### 七、边界

- 本文只定义**客户端相机租约**的身份、清洁契约与迁移点；池容量、耗尽策略不在本文范围，另行拍板。
- 服务端相机中心 / 锚点 / 刷怪 / 预加载的状态与释放见
  [`camera-center-entities.md`](camera-center-entities.md) §2.7；它们不进客户端相机池。
- FBO 不进池；共享 `offscreenTarget` / `adjustTarget` 维持现状。
- 编辑器（`editor/`）不参与本设计。

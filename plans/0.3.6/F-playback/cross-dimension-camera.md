# 跨维度相机（per-view 维度渲染）

> 状态：P0 + WP1–WP4 已实现并提交（2026-10-25，`8354731` / `c0b1ac1` / `50aa5fb`）；WP5（光影每视角隔离）部分；WP6 已跑一次三画面 fixture（2026-10-26，光影开启，双视图 active、无 route-fail / oversize），完整验收矩阵未完成。实现状态与已知限制见 §八。
> 范围：相机采样带维度；同一客户端内每个画面 view 用目标维度的渲染源；服务端按相机 view 供区块、实体与刷怪中心。玩家留在原维度，不切整个客户端、不传送玩家、不开第二客户端。
> 关联：[多相机渲染](multi-camera-rendering.md)、[画面合成](camera-composition.md)、[相机中心实体](camera-center-entities.md)、[相机租约池](camera-pool.md)、[光影兼容](iris-oculus-compat.md)、[并行播放](parallel-playback.md)。
> 调查依据：`example/ImmersivePortalsMod-1.20.1`、MC 1.20.1 反编译源码、Iris 1.7.6 / Oculus 1.8.0、本仓客户端与服务端代码（2026-10-25）。IP 只作参考实现，不作为运行时依赖。

## 一、摘要

相机采样 = 六参数 + 维度。六参数仍是位置、yaw / pitch / roll、fov、zoom；维度是“这次采样来自哪个 `Level`”。当前 `CameraState` 只有六参数，`LaneRenderer` 固定读 `mc.level` / `mc.levelRenderer` / 主 lightmap，所以相机坐标写到末地，画面仍然是玩家所在维度。

本方案把维度放进**采样 / view**，不放进整个客户端：

- 每条 lane 的相机快照带自己的维度；
- `LaneRenderer` 按 lane 的维度取一个 `DimensionView`（该维度的 `ClientLevel` + `LevelRenderer` + `LightTexture` + 剔除 / 光影 / 实体状态），用这个 view 渲染；
- 同一帧可以有多条 view，按 lane 顺序**顺序渲染**、逐条合成；不是两个世界并行提交；
- `LaneCompositor` 不读维度，只按 `source` / `dest` / `opacity` / 遮罩贴纹理；
- 服务端按 `(player, dimension, cameraId)` 维护相机中心，并把目标维度的区块、光照、方块更新、实体送到这个玩家的连接；客户端按包里的维度与 camera id 路由到对应 `DimensionView`。

这是单客户端的多视角维度渲染，不是第二客户端；也不是把整个客户端常规切到目标维度（A）。

**施工顺序不是能力边界。** 下面 P0 / WP1–WP6 是验证与落地顺序，不是“只支持单相机 / 只支持原版三维度 / 只做全屏”的范围声明。接口从第一步按 `(player, dimension, cameraId)` 设计；多相机、多玩家、多维度都在同一条链上扩。

## 二、设计口径

### 2.1 脚本与采样

- CAMERA clip 的 `dimension` 是可选字段；**没填 / 空串 = 玩家当前维度**。
- 缺省维度在解析 / 采样时取 `player.level().dimension()`，不缓存成固定值；玩家在播放中换维度，缺省 view 跟着换。
- 填了具体维度，该相机采样就在目标维度取景；玩家位置、维度、身体、碰撞、存档不变。
- 维度是每个相机采样自己的属性，不是全局状态；同一玩家、同一脚本、同一帧都可以有多条不同维度的 lane。
- 目标维度的地形、实体、方块实体、粒子、天空 / 天气按该 view 的 `LaneContent` 渲染；同维度路径零回归。
- 合成层不读维度；全屏 / 分屏 / 画中画只是 `dest` 不同。

### 2.2 服务端与数据

- 服务端按 `(player, dimension, cameraId)` 保存相机中心、ticket、已发区块 / 实体集合与刷怪锚点。
- 目标维度的区块、光照、方块更新、实体 spawn / update / remove 通过**带维度标签的相机数据流**送到真实玩家的连接；不把真实玩家塞进目标维度 `ChunkMap` 的玩家表，不创建假 `ServerPlayer`。
- 相机中心与刷怪中心跟随相机所在维度；目标维度没有真实玩家时，刷怪 / 消失 / 实体冻结都要按“相机在场”处理。
- 不替换原版同维度玩家差集；同维度仍走现有 `CameraCenterManager` + vanilla `ChunkMap` 路径。

### 2.3 不锁版本、不锁加载方式

- 不把 MC 版本、原版三维度、IP 或某个客户端加载方案写死。
- 客户端最低要求是“目标维度有一套 `ClientLevel` + `LevelRenderer` + `LightTexture`”；这套对象怎么来（原版连接同步、维度元数据同步、其他模组提供）不锁死。
- 自定义 / 数据包 / 模组维度是目标能力；非当前维度需要一条很小的维度元数据同步（维度 key → 维度类型 / level stem），不能只靠原版三维度命名约定。
- 旧路径“片段到点 → 服务端传送玩家到目标维度”必须删除，不能与新语义并存。

## 三、调查结论

| 方向 | 结论 | 对设计的约束 |
|---|---|---|
| IP 参考实现 | 客户端可为每个维度持有 `ClientLevel` + `LevelRenderer` + `LightTexture`，换全局状态后调一次 `renderLevel`；`GuiPortalRendering` 可把任意维度渲染进 FBO | 证明单客户端多维度渲染可行；但 per-lane FOV、调度、Iris、FBO、服务端依赖都有冲突，只借“状态换入换出 + 维度包路由”的思路 |
| MC 客户端 | `ClientLevel` / `LevelRenderer` / `LightTexture` 都可公开构造；`LevelRenderer.renderLevel` 已接收相机、lightmap、投影，可脱离 `GameRenderer.renderLevel` 直接调用 | 需要第二套 view；登录包没有非当前维度的 `level_stem` 映射，需要维度元数据同步；`Minecraft.levelRenderer` 是 `final`；`ClientChunkCache` 有固定范围；包处理器读 `ClientPacketListener.level`；light 更新是延迟队列 |
| MC 服务端 | 可不做 IP 那种全局接管，只加约 8 个 hook + 一个自定义 `TicketType` | `ChunkMap.updateChunkTracking` 有 `serverPlayer.level() == this.level` 硬门；所有区块 / 实体包不带维度；目标维度没有玩家时刷怪多道门都是 0；`ServerLevel` 空置 300 tick 后实体冻结 |
| Iris / Oculus | 1.7.6 / 1.8.0 本来就有每维度一套 pipeline；`renderLevel` 按当前维度选 pipeline，shadow map 也是每 pipeline 一套 | 全局只有一个“当前 pipeline”指针；`Iris.getCurrentDimension()` 读 `Minecraft.level`；现有 `ShaderCompat.refresh()` 会把维度切换误判成光影重载并清空全部 view 历史；shadow 每 lane 每帧重跑；FABULOUS 有静态 target 风险 |
| 本仓现状 | `CameraState` 六参数；`CameraLane` / `LaneFrame` 无维度；`LaneRenderer` 固定 `mc.level` / `mc.levelRenderer` / `gameRenderer.lightTexture()`；服务端状态按玩家单条；旧传送路径仍在 | 数据模型、渲染源、服务端状态、上报包、测试夹具都要改；旧 crossdim 测试验证的是传送，不是真跨维度渲染 |

### 3.1 客户端关键事实

- `Minecraft.levelRenderer` 是 `public final`，目标 view 切换需要 accessor；不能只改 `GameRenderer` 的相机。
- `LevelRenderer` 的 `ViewArea` / `ChunkRenderDispatcher` / 各 target 是 per-instance；一个 renderer 不能每帧 `setLevel` 切维度，`allChanged()` 会重建整套视图与调度。
- `ClientChunkCache` 只接受 `viewCenter ± (viewDistance + 3)` 范围内的区块；目标 view 要更新 view center / storage radius，或换自定义 chunk store。
- 区块包处理器都读 `ClientPacketListener.level`；客户端路由必须在目标 `ClientLevel` 还切着的时候处理，处理完再还原。
- `handleLevelChunkWithLight` 的 light lambda 是延迟执行的；必须在该 level 还切着时排干 `pollLightUpdates`，否则光照会写回主世界。
- `ParticleEngine` 是单例，`setLevel` 会清粒子；跨维度 lane 要么用每 view 的 particle engine，要么在该 lane 先关粒子。不能假设共享没问题。
- `Camera.entity` 目前硬套 `mc.player`；跨维度时实体渲染、流体、spectator 判定会读到错误维度，需要 view 级 entity / detached 语义。

### 3.2 服务端关键事实

- `ChunkMap.updateChunkTracking` 的 `serverPlayer.level() == this.level` 门必须绕过；不能直接把玩家塞进目标维度 `playerMap`，否则会写坏 `lastSectionPos`、发出未标记的中心包。
- 目标维度没有真实玩家时，刷怪至少受这些门影响：spawn chunk count、全局 category cap、`anyPlayerCloseEnoughForSpawning`、`getPlayersCloseForSpawning` / local cap、`getNearestPlayer`。只加一个 anchor 不够。
- `ServerLevel.tick` 的 `emptyTime` 超过 300 后跳过实体 tick；相机中心必须被视为活动，否则会出现“刷怪继续、实体不动”。
- `DistanceManager.removePlayer` 对不存在的 section 可能 NPE；添加 / 移除必须严格配对。
- `ChunkMap.TrackedEntity.seenBy` 是 `ServerPlayerConnection` 集合；可以复用原版增量发送，但配对 / 已发集合要按 view 维护，不能伪造真实玩家进世界。

### 3.3 光影关键事实

- Iris / Oculus 的 pipeline 按维度分套；切 `mc.level` 后，`renderLevel` 会选目标维度的 pipeline，这是跨维度渲染最省事的一层。
- 但 `ShaderCompat` 现在只缓存一个 pipeline；维度切换会让 `getPipelineNullable()` 返回不同对象，`refresh()` 误判为重载，进而 `ViewStateCapture.onPipelineChanged()` / `ColorHistoryCapture.reset()` 清空所有 view。跨维度会每帧清一次。
- 视角历史、上一帧矩阵、颜色快照、深度重绑都按字符串 key 存；key 不带维度时，两个 view 会共用状态。
- shadow map 每 pipeline 一套，但 lane 每帧重跑 shadow pass；成本要进性能矩阵。IP 的 GUI 路径也没有完整 shadow swap，不能直接照搬。
- FABULOUS 图形模式下 `RenderStateShard` 的静态 target 与 `transparency.json` 固定绑 `minecraft:main`；目标 renderer 的链要么建到 lane FBO，要么重定向静态 target，否则会画错或 NPE。

## 四、方案骨架

### 4.1 数据模型与旧路径

- `CameraState` 保持六参数，不直接把维度塞进公开 record；维度放在 `CameraLane` / `LaneFrame`，主相机快照另存一份旁路维度。若后续要公开“相机维度” API，再加 wrapper，不破坏现有构造。
- `CameraLane` / `LaneFrame` 增加 `dimension`；`ScriptLaneDriver` / `LaneSnapshotCollector` / `LaneRenderer.Lane` 全链传递。
- `ScriptValidator` 校验 `dimension` 是合法 `ResourceLocation`；缺省 / 空串不报错。
- 删除旧传送语义：`ScriptEventManager.applyDimensionSchedule` 的 `teleportTo` 路径、`ScriptPlayer.validateClipDimensions` 的“服务端切换”注释、`DimensionClip` 相关调度与旧测试夹具全部退出。新的 `dimension` 只表示采样维度，不再驱动玩家传送。
- `CameraCenterReporter` 从“每个玩家一条中心”改为“每条活跃相机一条上报”：`(cameraId, dimension, x, y, z, cameraMode)`；`sessionId` 按 view 生成，旧播放的迟到包不能误杀新播放。（实际实现未改同维度 reporter：跨维度由独立的 `CameraViewReporter` + `CameraViewManager` 上报 / 接收，见 §八。）
- 调试 id 用 `<scriptId>_cam<轨道序号>_clip<片段序号>`（预览实例加 `_preview`）；每条 lane 的捕获产物因此天然按片段分开，不需要再追加维度后缀。

### 4.2 客户端 `DimensionView`

```text
DimensionView {
    ResourceKey<Level> dimension;
    ClientLevel level;
    LevelRenderer renderer;
    LightTexture lightTexture;
    LaneCullBridge / cull state;   // 该 view 的候选集 / 可见集合 / 门闩
    shader / view state key;       // 按维度 + camera id
    entity storage;                // 由 ClientLevel 持有
    chunk cache / view center;     // 目标维度独立
}
```

- **主世界 view**：复用现有 `mc.level` / `mc.levelRenderer` / 主 lightmap；同维度 lane 零回归。
- **目标维度 view**：懒创建；用维度元数据同步得到的 `DimensionType` / level stem 构造 `ClientLevel`，再构造独立 `LevelRenderer` / `LightTexture`；`setLevel(target)` 后手动 `onResourceManagerReload`，并挂窗口 resize / 资源 reload。
- **维度元数据**：非当前维度需要 `dimension key -> DimensionType / level stem` 同步；原版三维度可按命名约定推导，但不能把这条当唯一路径。
- **视图中心**：每条 lane 的 view center 用该 lane 相机；`LevelRendererVanillaMixin` 不能再取全局 `topLane()`，否则目标 renderer 会被拉到另一个维度的坐标。
- **剔除**：cull bridge / 槽位按 view 归属；不能把主世界候选集套到目标维度。
- **渲染状态换入换出**：lane pass 内临时把 `mc.level` 切到目标 view，必要时切 `mc.levelRenderer`（accessor，字段是 final）、`EntityRenderDispatcher` / `BlockEntityRenderDispatcher` / `hitResult` / particle level；`Camera.setup(view.level(), viewEntity, ...)` 使用 view 级 entity，不硬套主世界 `mc.player`；`finally` 全部还原。
- **光照**：`renderLevel` 显式传 `view.lightTexture()`，不读 `gameRenderer.lightTexture()`；天空 / 雾 / 天气读目标 `ClientLevel`。
- **FBO / 合成**：`LaneCompositor` 不变；FBO 仍按 lane 顺序复用。FABULOUS 需要单独验证静态 target 重定向。
- **粒子**：先做决策——每 view 独立 `ParticleEngine`，或跨维度 lane 在该 view 内关闭粒子。不能共享主世界单例后假装正确。
- **卸载**：播放结束、强停、断线、维度卸载时按 lane → view 状态 → renderer / lightmap → level 的顺序释放；不跨 view 复用光影历史、剔除槽、相机实例、FBO 内容。

### 4.3 服务端数据流

**状态键**：`ViewKey = (player UUID, dimension, cameraId)`。

- `CameraVirtualCenterState` 从 `Map<UUID, Entry>` 改为按 `ViewKey` 存；查询接口带 `ServerLevel` 与 `cameraId`。
- `CameraCenterManager.PlayerState` 按 view 存；`applyVirtualCamera` 接收目标 `ServerLevel`，不再固定 `player.level()`。
- `CameraAnchorManager` 按 `(UUID, dimension, cameraId)` 存锚点；刷怪 / 消失 / 最近玩家查询取该维度最近的锚点。仍不创建实体、不进玩家列表。
- 上报包增加 `dimension` 与 `cameraId`；服务端校验维度存在、camera 属于该玩家当前播放。

**加载**：

- 目标 `ServerLevel` 按相机位置建自定义 `TicketType` / region ticket（覆盖 entity-ticking）；相机移动只在 section 变化时重挂，避免 ticket 泄漏。
- 同维度多相机：保留原版单中心路径给主 / 顶层相机，额外相机用 ticket + 独立 view 数据流；不要假设一个 `ChunkMap` 中心能同时服务多条相机。

**发送**：

- 目标维度 `ChunkMap` 不会给主世界玩家发包；用独立的相机维度流：
  - 区块 / 光照：从目标 `ChunkMap` 取区块与光照数据，包带维度 + cameraId；
  - 方块更新 / 方块实体：`ChunkHolder.broadcastChanges` 的 dirty 集合是私有的、发完就清；需要 mixin 在清空前把带维度标签的副本发给相机 viewer；
  - 实体：复用原版 `TrackedEntity` / `ServerEntity` 的增量发送，或等价的自建实体流；已发集合 / 配对按 view 维护，处理新加载、离开范围、区块 forget、停播。
- 客户端路由：按维度 + cameraId 找 `DimensionView`；临时切 `ClientPacketListener.level` 到目标 `ClientLevel`，处理包，排干 light 更新队列，再还原。目标 view 的 `ClientChunkCache` 要 `updateViewCenter` / 设置 storage radius，或换自定义 store。
- 不替换 vanilla 玩家差集；同维度仍走原版，相机维度流只处理“相机在另一个维度”的 view。
- 释放：播放结束、强停、断线、维度卸载时释放 ticket、已发集合、实体引用、缓存；`DistanceManager` 的添加 / 移除严格配对。

### 4.4 相机中心与刷怪

- 中心查询：`ChunkMap` / `TrackedEntity` mixin 传入目标 `ServerLevel` 与 cameraId；目标维度 `ChunkMap` 的相机中心只对目标 view 生效。
- 刷怪门：目标维度没有真实玩家时，至少补 `DistanceManager.addPlayer(cameraSection, player)`，让 `getPlayersCloseForSpawning` 返回相机玩家；`anyPlayerCloseEnoughForSpawning` / 最近玩家继续由 anchor 查询覆盖；`LocalMobCapCalculator` 的 local cap 要按相机区记账，不能直接按真实玩家列表返回 0。
- 实体冻结：`ServerLevel.tick` 的 `emptyTime` 要因相机中心重置 / 视为活动；否则相机区刷怪但实体不 tick。
- 消失：`Mob.checkDespawn` 的最近玩家 / 距离门读 anchor；相机离开后按原版规则保留。
- 多相机：同一维度多条相机时，主中心 + 额外 ticket；刷怪查询取最近 anchor；诊断按 `(player, dimension, cameraId)` 分开记录。
- 不创建假 `ServerPlayer`，不把 anchor 放进世界玩家列表、存档、统计或 AI 目标选择。

### 4.5 光影

- 每个 `DimensionView` 有自己的 `LevelRenderer` / `LightTexture` / pipeline；lane pass 内切 `mc.level` 后，Iris / Oculus 自己会选目标维度的 pipeline。
- `ShaderCompat` 从“单个 pipeline 缓存”改成“按 pipeline / 维度缓存”；用光影自己的重载版本计数（Iris 的 `versionCounterForSodiumShaderReload`）区分“光影包重载”和“维度切换”。维度切换只换当前指针，不清其他 view 的历史。
- `ViewStateCapture` / `ColorHistoryCapture` / `ViewScope` / 深度重绑的 key 加维度 + camera id；`CameraPositionTracker` / `MatrixUniforms.Previous` 按 pipeline 分组，不能切换一次就丢另一维度的实例。
- `ShadowColorClear` 在目标 pipeline 选好之后再清；`CenterDepthSampler` 目前没有隔离，跨维度 DoF / TAA 可能出鬼影，要单独处理。
- shadow map 按 view / pipeline 复制或切换 target；IP 的 GUI 路径没有完整 shadow swap，不能直接照搬。shadow 每 lane 每帧重跑的成本要进性能矩阵。
- FABULOUS：目标 renderer 的 chain 要绑定 lane FBO，或重定向 `RenderStateShard` 的静态 target；`transparency.json` 固定 `minecraft:main` 的情况要单独验收。
- Oculus / Forge 还要处理 Embeddium 的 world / context 切换；Fabric / Iris 与 Forge / Oculus 分别验收。

### 4.6 同维度与跨维度

| 情形 | 行为 |
|---|---|
| CAMERA clip 未填 `dimension` / 空串 | 默认玩家当前维度；走同维度路径；玩家换维度时 view 跟着换；零回归 |
| `camera.dimension == player.dimension` | 走现有 lane 路径；`DimensionView` 就是玩家当前维度的 view；零回归 |
| `camera.dimension != player.dimension` | 用目标维度 view；服务端相机维度流供数据；玩家不动 |
| 同一玩家多条相机、不同维度 | 每条 view 独立 `DimensionView` / 数据流 / 光影状态；按 lane 顺序渲染 |
| 目标维度未加载 / view 创建失败 | 该 lane 本帧跳过并限频告警；主画面与其他 lane 不动 |
| 播放结束 / 强停 / 断线 / 维度卸载 | 释放该 view 的 lane、chunk / entity 引用、ticket 与缓存 |

### 4.7 失败与边界

- view 创建失败 / 目标 level 不可用：该 lane 跳过，主画面与其他 lane 不动。
- 数据流断 / 包不完整：该 view 本帧可能空地形；按现有 lane 失败守门跳过或保留上一帧，实现时定；不能崩客户端。
- 目标维度实体 / 方块实体：由目标 `ClientLevel` 的存储提供；没有数据就没有实体，不在客户端伪造。
- 玩家维度 / 位置不变：不在渲染路径写 `mc.level` 的持久状态；只在 lane pass 内换 view，`finally` 还原。
- 旧 A 路径：`ScriptEventManager.applyDimensionSchedule` 的传送必须删除；实现前不能保留“相机维度”和“传送玩家”两条语义并存。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| A：片段切维度、传送玩家 | 片段边界把玩家送到目标维度 | 改动小，单 `ClientLevel` 可用 | 玩家离开原维度；不满足“玩家留原维度”；只是绕开数据缺口 |
| 第二客户端 | 另开游戏进程 / 连接渲染目标维度 | 不改主客户端 | 不是单客户端功能；用户明确不做；分屏 / 合成也不在同一窗口 |
| 整个客户端切 `mc.level` | 把主客户端常规切到目标维度 | 单 view 可用 | 玩家世界 / 输入 / 实体跟着变；不是“玩家留原维度”；多 view 无法同帧 |
| 依赖 IP | 客户端 / 服务端都装 IP，用它的多世界与 GUI 渲染 | 有现成实现 | Fabric-only；要求 IP 服务端；接管 vanilla chunk / entity tracking，与相机中心冲突；alpha 内部 API；不能作为通用主路径 |
| 服务端预渲染 / 视频流 | 服务端渲染目标维度，客户端贴图 | 客户端简单 | 实时性、实体交互、光影、带宽都不合适；不是现有渲染路径 |
| 替换全局 `ChunkMap` 跟踪 | 像 IP 一样接管所有玩家 / 维度的区块与实体同步 | 一套图解决所有维度 | 影响面大，和现有相机中心 / vanilla 差集冲突；本功能只需要相机 view 的流 |
| 只做同维度 | 保持现状 | 零风险 | 不满足跨维度需求 |

## 六、施工顺序与验收

### 6.1 P0 垂直验证

先验证最难的一条链，**这不是能力边界，只是施工顺序**：

1. 一条 clip 指定末地，玩家留主世界；
2. 客户端构造末地 `ClientLevel` + `LevelRenderer` + `LightTexture`；
3. 服务端按相机中心发带维度标签的区块；
4. 现有 lane FBO 画出末地一帧，主世界同帧还在。

P0 通过，说明“采样带维度 → 目标 view → 数据流 → 合成”主链成立；实体、光影、多相机都在这条链上扩，不换接口。P0 失败，就停下来重新评估，不往上堆。

### 6.2 工作包

| 包 | 内容 | 验收 |
|---|---|---|
| WP1 数据与旧路径 | `CameraLane` / `LaneFrame` 带维度；主相机旁路维度；validator；删除传送路径；reporter 按相机上报 | 同维度脚本零回归；旧 crossdim 测试不再依赖传送 |
| WP2 客户端 view | `DimensionView` 注册 / 生命周期；`ClientLevel` / `LevelRenderer` / `LightTexture`；accessor；`mc.level` / `mc.levelRenderer` 换入换出；cull / view center / particle / FABULOUS | 玩家主世界、相机末地全屏正确；停播释放；窗口 resize / 资源 reload 不崩 |
| WP3 服务端流 | `(player, dimension, cameraId)` 状态；ticket；维度标签信封；区块 / 光照 / 方块 / 实体；客户端路由 + light 队列；实体配对 reconciliation；`ServerLevel.tick` | 末地地形、方块、实体到达正确 `ClientLevel`；主世界不重复收包；断线 / 重连清理 |
| WP4 相机中心 / 刷怪 | 三张表改 view key；包加维度 / cameraId；目标 `ServerLevel`；`DistanceManager.addPlayer`；`getPlayersCloseForSpawning`；anchor per view | 目标维度相机附近刷怪、实体不冻结、despawn 正常；无假 `ServerPlayer` 进世界 |
| WP5 光影 | `ShaderCompat` per-pipeline；维度 key；`mc.level` 驱动 Iris 选 pipeline；shadow 每 view；FABULOUS；Oculus / Embeddium | Iris / Oculus 开启时两个维度各自画面、无历史 / 阴影串色；三套光影包 A/B |
| WP6 验收 | 新 fixtures：不传送的 crossdim、双 lane 双维度、多玩家、modded 维度元数据；同维度回归；性能矩阵；FABULOUS / Sodium / Embeddium | 验收矩阵全过；没有 OOM / 泄漏 / 重复包 |

### 6.3 验收矩阵

| 场景 | 期望 |
|---|---|
| 同维度现有脚本 | 画面 / 性能 / 光影与现在一致 |
| CAMERA clip 不填 `dimension` | 默认玩家当前维度；与现在一致；玩家换维度后缺省 view 跟随 |
| 玩家主世界，相机末地，全屏 | 画面是末地；玩家坐标 / 维度 / 身体不变；无第二客户端 |
| 玩家主世界，相机末地，分屏 | 左主世界、右末地；合成参数正确 |
| 同一玩家两条相机、两个维度 | 两条 view 同帧可见；互不串历史 / 深度 / 剔除状态 |
| 末地实体 | 目标 view 内可见；主世界实体不受影响 |
| 光影开启 | 两个 view 各自完整光影；无跨视角历史 / 阴影串色 |
| 停播 / 强停 / 断线 | 目标 view、ticket、实体引用、缓存全部释放 |
| 目标维度不可用 | 该 lane 跳过；主画面与其他 lane 正常；日志可定位 |

## 七、开放问题

- 维度元数据同步的包形态：维度 key → `DimensionType` / level stem；原版三维度可推导，modded 必须同步。
- 同一玩家同一维度多条相机时，原版单中心 + 额外 ticket 的具体分工；`CameraAnchorManager` 的最近锚点选择是否足够。
- `CameraState` 公开 API 是否最终加维度字段；当前建议 lane 旁路，不破坏外部构造。
- 客户端加载方式：最低要求是第二套 `ClientLevel` / `LevelRenderer` / `LightTexture`；数据来源不锁死，但具体由谁构造、谁负责资源 reload 要定。
- `ParticleEngine`：每 view 独立，还是跨维度 lane 关闭粒子；两者成本和语义不同。
- FABULOUS 的静态 target 重定向方案；不解决就不能宣称 FABULOUS 下跨维度可用。
- shadow map 按 view 复制 / 切换的成本；IP 的 GUI 路径也没有完整 shadow swap。
- `ClientChunkCache` 是更新 view center / storage radius，还是换自定义 chunk store；后者改动更大但边界更干净。
- `RenderBuffers` / 各 render target 是否按 view 独立；串行 pass 可以共享，但必须验证不跨 view 残留。
- 旧 crossdim 测试与 `DimensionClip` 调度的删除范围；不能留下传送路径作为“兼容”。

## 八、实现状态

状态：P0 + WP1–WP4 已实现并提交（2026-10-25，`8354731` / `c0b1ac1` / `50aa5fb`）；WP5（光影每视角隔离）部分；WP6（实机验收矩阵）未完成。以下按当前代码陈述，不是实机验收结论。

### 8.1 已落地

- **采样 / 数据**：`Clip.dimension` → `CameraLane` / `LaneFrame.dimension`；空串 = 玩家当前维度。`LaneRenderer` 逐 lane 解析 `DimensionView` 并在 lane pass 内换入 `mc.level` / `mc.levelRenderer` / 实体与方块实体分发器 / 光照贴图，`finally` 还原。
- **客户端 view**：`DimensionViews` 按维度懒创建 `DimensionView`（独立 `ClientLevel` + `LevelRenderer` + `LightTexture` + `RenderBuffers`）；`MAIN` 哨兵复用玩家当前维度；`FABULOUS`（`Minecraft.useShaderTransparency()`）、缺维度类型、非法 id 返回 `null`（该 lane 本帧跳过并限频告警）；窗口 resize / 资源重载 / 世界退出 `closeAll()`。
- **服务端流**：`CameraViewManager` 按 `(玩家 UUID, viewId)` 维护 `ViewState`（`ServerLevel` / 位置 / section / 已发区块 / 实体 / 虚拟玩家 / ticket）；`DistanceManager.addPlayer(相机 section, 真实玩家)`；区块 `ClientboundLevelChunkWithLightPacket` + 离开半径 `ClientboundForgetLevelChunkPacket`；实体 `ServerEntity.sendPairingData` 只发初始配对，离开 / 移除补 `ClientboundRemoveEntitiesPacket`；全部经 `S2CCameraViewPacket` 信封（维度 + vanilla 包 id + payload）。
- **客户端路由 / 上报**：`CameraViewReporter` 每 5 tick / 位移 ≥1 格 / 状态变化上报；`CameraViewPacketRouter` 按维度 `DimensionViews.acquire`，临时切 `ClientPacketListener.level` 处理 vanilla 包、排干 light 更新、`finally` 还原。
- **刷怪 / 消失**：`ChunkMapMixin` 增加视图门（`isAnyViewNear` / `playerForSpawn`）与 `getPlayersCloseForSpawning` 返回视图属主真实玩家；`NaturalSpawnerMixin` / `MobDespawnCameraMixin` 在真实玩家、F6 锚点虚拟玩家、视图虚拟玩家三者中取最近。
- **剔除 / 视角状态**：`LaneCullSlot` 带 `owner`，换渲染器时整箱作废、按新渲染器重新播种；`LaneCullSlots.mirrorCompiled` 只投给同渲染器的槽；`ViewScope` 在 lane 循环内跳过 `ShaderCompat.refresh()`（P0 折中，不是 per-pipeline 隔离）。
- **租约**：F7 后视角身份 = 租约（维度 + 槽位 + 代际），相邻片段 / 重播不继承光影历史；租约归还时确定性释放 `ViewStateCapture` / `ColorHistoryCapture`，见 [相机租约池](camera-pool.md)。
- **旧路径**：服务端“片段起点传送玩家”已删除（`8354731`）；`ScriptEventManager.applyDimensionSchedule` 不再参与跨维度。

### 8.2 与设计基线的偏离

| 设计 | 实际实现 | 原因 |
|---|---|---|
| `CameraVirtualCenterState` / `CameraAnchorManager` / `CameraCenterManager` 改造成 `(player, dimension, cameraId)` | 同维度链路保持原样；跨维度新增并行 `CameraViewManager` | 同维度零回归；三张表按 view 重做会牵动 F6 |
| 自定义 `TicketType` / region ticket 覆盖 entity-ticking | `DistanceManager.addPlayer(相机 section, 真实玩家)` + 每 tick `resetEmptyTime()` | 复用原版 ticket 语义；目标维度实体 tick 由 `emptyTime` 重置保住 |
| 区块 / 光照 / 方块 / 实体全量增量 | 区块首包带光照、实体只发初始配对；无方块 / 光照增量、无实体持续同步 | P0 先验证“可见”；增量发送要避开原版 `TrackedEntity` 的 dirty 数据 |
| 每 view 独立 `ParticleEngine` 或决策 | 跨维度 lane 内容档 `CROSS_DIM` 关粒子 | 粒子是主世界局部客户端状态，跨维度采样没有意义 |
| 光影 per-pipeline / 维度缓存，shadow 每 view | 未完成；`ViewScope` 在 lane 循环内跳过刷新 | WP5；FABULOUS 直接拒绝目标视图 |
| 客户端 `ClientChunkCache` 自定义 store | 复用 `ClientChunkCache` + `updateViewCenter` | 改动面更小；视距与 storage radius 已验证够用（P0） |

### 8.3 已知限制

- **实体是快照**：目标维度实体只发初始配对，不调用 `sendChanges`；目标 `ClientLevel` 不 tick，实体不移动 / 不播放动画。
- **目标维度只有区块 + 实体**：没有方块更新、光照更新、声音、天气、方块事件、粒子（跨维度 lane 内容档关粒子）。
- **FABULOUS 不支持**：`Minecraft.useShaderTransparency()` 为真时 `DimensionViews` 拒绝创建目标视图；光影 per-pipeline 隔离未做，`ViewScope` 跳过刷新是折中。
- **包上限**：自定义通道约 1 MiB；超大区块包丢弃（限频日志），目标视图可能缺一块。
- **多视图同维度**：同一玩家同一维度多个 view 会重复发区块（forget / remove 有去重）；`DistanceManager` 同 section 多视图可能重复 ticket，未实机验证。
- **F6 与 F8 并行**：F6 单锚点 `CameraCenterManager` 保持原样；跨维度视图是第二条链，不合并成单 key。
- **实机验收进度**：三画面 fixture `test_camera_center_crossdim_view` 于 2026-10-26 跑过一次（光影开启）：玩家留在主世界，末地 / 下界两条视图均 `active=true`，无 `route-fail` / `oversize`；停播释放与完整 WP6 矩阵仍待验。

### 8.4 下一步

按 §6.3 做 WP6 实机验收（玩家主世界 + 末地 / 下界双 lane、停播释放、光影 / FABULOUS 边界）；补目标维度实体增量、方块 / 光照更新与光影 per-pipeline 隔离后再宣称完整跨维度。

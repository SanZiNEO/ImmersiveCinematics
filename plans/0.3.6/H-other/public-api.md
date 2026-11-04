# 对外接口（Public API / SPI）

> 状态：设计稿（未实现）
> 范围：第三方可调用的播放链路、相机与核心参数只读面、可注册扩展点（触发器 / 轨道 / 插值模型 / 路径曲线 / 前置条件 / HUD 层）、注册时机、失败隔离、id 冲突
> 关联：[`../I-script-core/object-model.md`](../I-script-core/object-model.md)（对象与采样属性）、[`../I-script-core/schema-and-types.md`](../I-script-core/schema-and-types.md)（schema 声明）、[`screen-layers.md`](screen-layers.md)（画面层）、[`hud-layer-api.md`](hud-layer-api.md)（H11 HUD 层注册）、[`state-tracking.md`](state-tracking.md)（H7 事实层）
> 不在本文范围：跨 MC 版本的适配策略、编辑器消费契约（归 Z 轮）

## 一、摘要

第三方有两类诉求，分两条完全不同的路径：

| 诉求 | 路径 | 谁负责效果 |
|---|---|---|
| 播我们已有的脚本 | 调播放 API | 我们（脚本作者定义） |
| 读相机 / 核心参数 | 调只读 API | 不涉及 |
| 注册"自己的东西"进时间轴 | 注册 SPI（轨道 / 触发器 / 模型 / 曲线 / 前置条件） | **第三方自己** |

第三条是关键边界：**我们只负责调度**——时间推进、生命周期、槽位采样、加载期校验；**效果由第三方自己把控**。

具体到最常见的三类第三方轨道：

| 第三方要做的事 | 走哪条路 |
|---|---|
| 演员动画（驱动自己的模型 / 骨骼 / 状态机） | 注册轨道，从 `TrackContext` 按对象路径读值，自己接到模型上 |
| 出画面（自绘图层） | 注册轨道 + 接入 **overlay 系统**，由我们的合成序列承载 |
| 音频 / 事件 / 数据副作用 | 注册轨道，自己处理 |

我们**不提供渲染目标、不提供模型挂载、不保证第三方效果**；只保证调度正确、时间正确、对象值正确。

## 二、代码现状（2026-11-04，H-4 落地后核对）

### 2.1 已经存在的注册入口

| 注册点 | 位置 | 形态 |
|---|---|---|
| 相机 Base 层 | `CameraCoverChain.register(BaseCameraProvider)` | 接口 + 优先级域（`PRIORITY_EXTERNAL=400`） |
| 相机 Modifier | `CameraCoverChain.register(CameraModifier)` | 接口 + 优先级排序 |
| 触发器 | `TriggerRegistry.register(TriggerType)` | 普通 class（id + strategy + pollInterval + evaluator） |
| 前置条件 | `PrerequisiteRegistry.register(String, TriggerPrerequisite)` | 注册表 |
| 插值模型 | `MathModelRegistry.register(String, Map<String,FieldDef>, ...)` | 注册表 |
| 路径曲线 | `PathCurveRegistry.register(String, Set<PathCapability>, ...)` | 注册表 |
| 维度渲染映射 | `DimensionViews.registerDimensionType(...)` | 静态注册表 |
| 轨道类型 | `TrackRegistry.register(TrackTypeId, TrackSchema, TrackPlayerFactory)` | 注册表（内置 7 轨 + 第三方） |
| HUD 层 | `HudLayerRegistry.register(ResourceLocation, String)` | 唯一注册表（H11） |
| 模板 | `TemplateRegistry.register(ClipTemplate)` | ⚠️ 不纳入对外（I 轮已定随 Z 轮删除） |

> 以上全部经 `api/ImmersiveCinematicsApi` 汇总为一个门面；上表是门面背后的真实落点。

### 2.2 缺口（H-4 已关闭，保留原始记录）

| 缺口 | 原证据 | H-4 处置 |
|---|---|---|
| **没有统一门面** | 8 个注册表分散在 `camera.cover` / `trigger.server` / `script.schema` / `client.dimension` / `script.template` | ✅ `api/ImmersiveCinematicsApi` 12 个静态方法；冲突检查在门面层，内部注册表语义不变 |
| **轨道类型不可注册** | `TrackType` 是 `enum`（7 值），43 个文件引用 | ✅ enum 已删除，改 `spi/TrackTypeId` + `script/track/TrackRegistry`；43 个引用点全部迁移 |
| **schema 是静态表** | `SchemaRegistry.TRACKS` 为 `static final`，类加载期校验 | ✅ `SchemaRegistry` 只转发 `TrackRegistry.schema(id)`；注册时逐条校验，`freeze()` 后不再接受注册 |
| **槽位布局无失效机制** | `ObjectSlotLayout.CACHE` 静态 `ConcurrentHashMap` | ✅ 键改 `TrackTypeId`；路径→槽位在注册期编译（`TrackRegistry.pathSlots`），注册冻结后不会出现新 id |
| **播放没有稳定入口** | 只有 `CameraManager.playCinematic` / `stopScriptById` | ✅ `api/PlaybackApi`（play/stop/stopAll/isPlaying/scripts/addListener/removeListener）+ `api/ServerPlaybackApi` |
| **相机只读面很薄** | `CameraApi` 只有 3 个方法 | ✅ 扩到 5 个（+`getLaneComposite` / `getActiveBaseProvider`） |
| **`TriggerType` 直接暴露 MC 类型** | `BiPredicate<ServerPlayer, JsonObject>` | 保留原样（设计 §3 允许触发器 SPI 出现 `ServerPlayer`）；门面只负责转发 + id 冲突 |

**switch 数量的实测更正（重要）**：任务书与本文早期版本写的「10 处 `switch (trackType)`」不成立。逐文件核对结果：
- 真正以轨道类型为表达式的 `switch` = **2 处**：`script/track/TrackDriver`（原 `TrackPlayer.create`，现改为 `TrackRegistry.createDriver` 查表）、`script/object/ObjectValidator.Check.run`（现改为 `Map<TrackTypeId, Consumer<Check>>` + 兜底）。
- 其余按轨道类型分派的点 = **13 处** `if` / 三元 / `filter`：`Clip`（6）、`ObjectValidator.isVisualLoopTrack`/`rejectPingpong`（2）、`ScriptParser`（12 处中的类型判定合并计 1 组）、`PlaybackInstance.collectModifiers`、`PlayerMoveController`、`ScriptPlayer.buildTrackPlayers`、`ScriptEventManager.extractEventClips`、`EventClipRuntime`、`Timeline`（2）、`ColorAdjustSampler`、`SchemaExporter`、`ScriptTemplate`。
- 早期版本把 `ScriptValidator`（切 `String` 条件/动作 type）、`RuleActions`（同）、`ClientConditions`（同）、`ObjectCodec` / `ObjectSchemaValidator`（切 `PropertyType`）、`BreathDisturbance`（切 `int`）误计入。

**引用文件数**：43 = 40 个直接含 `TrackType` 标识符 + 2 个经 `Clip#getTrackType()` 间接（`RuleEngine`、`ClipSampler`）+ 1 个仅类型名（`TrackTypeSchema`）。`fabric/` 与 `forge/` 零命中。

## 三、分层

| 包 | 面向 | 内容（H-4 实际落地） | 稳定性 |
|---|---|---|---|
| `api` | 只读查询 + 播放控制 + 注册门面 | `CameraApi`、`CameraState`、`BaseCameraProvider`、`HudLayerRegistry`、`PlaybackApi`、`ServerPlaybackApi`、`PlaybackListener`、`ScriptInfo`、`OverlayApi`、`ServerFactsApi`、`ImmersiveCinematicsApi` | **稳定**：只增不删 |
| `spi` | 第三方实现与注册（轨道） | `TrackTypeId`、`TrackSchema`、`TrackPlayer`、`TrackContext`，以及声明 schema 所必需的 `PropertyType` / `Interp` / `PropertyMode` / `ObjectDef` | 随模组版本演进；破坏性变更给迁移说明 |
| 内部 | 不暴露 | `ClipSampler`、`ObjectSlotLayout`、`SampledProperty`、`SampledClip`、`ScriptPlayer`、`PlaybackInstance`、`script/track/{TrackRegistry,TrackDriver,BuiltinTracks,BuiltinTrackDrivers,TrackContextImpl,ExternalTrackDriver}`、各 `TrackDriver` 实现 | 随时改 |

> **`spi` 的当前边界（H-4 口径）**：本轮只把**轨道注册**所需的类型放进 `spi`。触发器（`TriggerType` / `ListenStrategy` / `TriggerEvalContext` / `TriggerPrerequisite`）、插值模型（`MathModel` / `ModelParams` / `FieldDef`）、路径曲线（`PathSpec` / `PathParams` / `PathParamDef` / `PathCapability`）仍留在原包（`trigger.server` / `math` / `script.schema`），通过 `ImmersiveCinematicsApi` 门面调用。它们**不在** §3 的「内部」清单里，因此不违反本设计的分层承诺；若要收成「单一 import 面」，需另开一轮机械迁移（见 `../I-BACKLOG.md` H4-R1）。


**为什么不暴露采样设施**：`TrackContext` 已经按路径给出本帧值，第三方不需要、也不应该碰槽位数组——否则第三方代码会依赖槽位编号，而槽位编号会随后注册的对象重排。

**SPI 里出现 MC 类型的边界**：

| SPI | 是否允许 MC 类型 | 理由 |
|---|---|---|
| 触发器 | **允许**（`ServerPlayer`） | 求值器本来就要读世界/玩家状态，包一层抽象会把能力削平 |
| 轨道 | **不允许** | 轨道只消费 `SampledClip` 的对象值，不需要任何 MC 渲染/实体类型 |
| 相机 Base/Modifier | **不允许** | 只收发 `CameraState` |

## 四、播放链路

### 4.1 客户端（本地播放）

```java
public interface PlaybackApi {
    /** 按脚本 id 播；instanceId 为空 = 匿名实例。返回实例号，<0 = 失败。 */
    int play(String scriptId, String instanceId);

    /** 停止某个实例；instanceId 为空 = 停止该脚本全部实例。 */
    boolean stop(String scriptId, String instanceId);

    /** 停掉全部脚本实例。 */
    void stopAll();

    /** 是否有活跃实例。 */
    boolean isPlaying();
}
```

现有实现：`CameraManager.playCinematic(script, instanceId)` / `stopScriptById(scriptId, instanceId)`；`PlaybackLifecycle` 承接生命周期。

### 4.2 服务端（给某个玩家播）

```java
public interface ServerPlaybackApi {
    boolean playFor(ServerPlayer player, String scriptId, String instanceId);
    boolean stopFor(ServerPlayer player, String scriptId, String instanceId);
}
```

现有实现：`TriggerEngine.startPlayback(...)`（触发器动作也在用同一条链）。

### 4.3 生命周期事件

```java
public interface PlaybackListener {
    default void onStarted(String scriptId, String instanceId) {}
    default void onFinished(String scriptId, String instanceId, CompletionReason reason) {}
}
```

注册入口挂在 `PlaybackApi` 上（`PlaybackApi.addListener` / `removeListener`）；同一实例重复注册只保留一份，`null` 被忽略。

**触发点（H-4 实测，各恰好一次）**：

| 回调 | 触发点 | 说明 |
|---|---|---|
| `onStarted` | `PlaybackLifecycle.startScriptInternal(...)` 成功启动后、返回前 | 排队 / 被拒不进本方法；编辑器实例（`editorDriven = true`）不广播 |
| `onFinished` | `PlaybackLedger.reportScriptFinished(instance, reason)` | 与既有 `ClientScriptNotifier.notifyScriptFinished` 同一位置；编辑器实例同样跳过 |

`CompletionReason` 5 个值（`control/CompletionReason.java`）：`FORCE_QUIT` / `STOPPED` / `INTERRUPTED` / `SKIPPED` / `FINISHED`；与 `ExitReason` 的映射在 `PlaybackLifecycle.requestExit`（`SYSTEM_STOP→STOPPED`、`USER_SKIP→SKIPPED`、`NATURAL_END→FINISHED`、`INTERRUPTED→INTERRUPTED`、`FORCE_QUIT→FORCE_QUIT`）。

广播逐个 `try/catch`：单个监听器抛异常不影响其它监听器，也不影响播放；日志走 `ErrorLog.logRateLimited("Playback", "listener", 5000L, ...)`。

### 4.4 缺口（H-4 已关闭，保留原始记录）

- 没有"脚本 id → `CinematicScript`"的公共查找 → ✅ 客户端接上了既有但零调用的 `ClientScriptCache`（`ClientScriptReceiver.handlePlayScript` / `handleScriptReload` 写入，`PlaybackApi.scripts()` 读取快照）；服务端用 `ScriptManager.INSTANCE.getScript(id)`。
- 没有事件总线；`onFinished` 的触发点散在 `PlaybackLifecycle` 与 `ScriptEventManager` → ✅ 收敛到上表两个点（客户端侧）；服务端事实仍由 `FactLedger` 记录（`PLAYBACK_STARTED` / `PLAYBACK_FINISHED`）。
- 播放失败没有结构化原因（现在靠日志） → ✅ `PlaybackApi.play` 返回 `-1`（脚本未缓存 / 客户端不在世界被拒）、`1`（已开始）、`2`（已排队）；`onFinished` 带 `CompletionReason`。


## 五、相机与核心参数（只读）

### 5.1 已有

```java
public interface CameraApi {
    boolean isActive();
    boolean hasActiveCameraClip();
    CameraState getCameraState();      // 不可变 record：position/yaw/pitch/roll/fov/zoom
}
```

### 5.2 已补齐（H-4 实现）

| 新增 | 用途 | 实现要点 |
|---|---|---|
| `CameraLaneComposite getLaneComposite()` | 顶层实例本帧**最上层** CAMERA lane 的合成参数（opacity / dest / source / mask），只读 | `CameraManager.activeInstance()` → `ScriptPlayer.activeCameraLanes()`（按轨道顺序拼接）→ 取末位；`composite()` 为 `null`（旧格式片段）时回落 `CameraLaneComposite.DEFAULT`，与旧链路缺省同义 |
| `String getActiveBaseProvider()` | 覆盖链本帧胜出的 Base 层标识（诊断/调试用） | `CameraCoverChain.evaluate()` 记下 `selectWinner()` 结果，`activeProviderId()` 读出；`CameraManager` 侧再用 `isActive()` 门控（无活跃实例时 `refreshCameraState()` 不求值，避免报上一帧的旧胜出层）。标识来自新增的 `BaseCameraProvider.providerId()` 默认方法（默认 = 实现类全限定名） |

> `getLaneComposite()` **不重新求值**相机：它复用本帧 `getLaneSnapshots()` 的结果，因此与 `getCameraState()` 同帧一致。


### 5.3 明确不开放

- `CameraManager.setScriptState(...)`（脚本导演写入口）
- 覆盖链的注册/注销之外的一切写操作
- Modifier 链的内部求值

## 六、轨道扩展（核心）

### 6.1 契约

```java
public interface TrackPlayer {
    void start(TrackContext ctx);   // 片段开始执行
    void tick(TrackContext ctx);    // 每帧 / 每次执行推进
    void stop(TrackContext ctx);    // 片段结束、停播、脚本替换
}

public interface TrackContext {
    Clip clip();                    // 只读：startTime/duration/loop/...
    double executionLocalTime();    // 本次执行的片段局部时间
    int executionIndex();           // 第几次执行（0 起）

    // 按对象路径取本帧采样值（路径→槽位在注册期编译，运行期不查字符串）
    float f(String path);           // 未声明 = schema 缺省
    boolean present(String path);   // 本帧是否有值（nullable_float 用这个判"未锁"，不用 NaN 哨兵）
    int i(String path);
    boolean b(String path);
    String s(String path);
    Object struct(String path);     // STRUCT 原始值（Map/List/String/...）

    String scriptId();
    String instanceId();
}
```

**我们负责**：时间轴推进、执行次数与方向、`SampledClip` 按 schema 采样、加载期校验、异常隔离。
**第三方负责**：拿到对象值之后做什么（驱动模型、播放声音、自己接 overlay）。

### 6.2 第三方必须提供三样

| 项 | 内容 |
|---|---|
| `TrackTypeId` | 唯一字符串 id（如 `mymod:actor_anim`） |
| `TrackSchema` | `ObjectDef` 表：路径 / 类型 / 默认值 / 插值 / 形态（value / animated / event） |
| `TrackPlayerFactory` | 按片段创建播放器实例 |

**硬性要求**：schema 非空，且**至少声明一个数值对象**（`float` / `int`，`value` 或 `animated`）。理由：轨道的"效果"必须落在对象与关键帧上，否则时间轴上没有可编排的东西。注册期校验，不满足直接拒绝。

### 6.3 出画面怎么办

**不通过 `TrackPlayer` 提供渲染目标。** 要出画面就走 overlay 系统：

```
第三方轨道 tick() → 算出本帧要画的东西 → 调 OverlayApi（新增，见 §8）
                                      → 我们的合成序列承载图层（z 序 / 混合 / 释放）
```

这样：
- 图层顺序、混合、资源回收仍是我们的
- 第三方不需要碰 FBO 与合成上下文
- 他的效果是否"好看"由他自己负责

### 6.4 注册表化改造（A 方案：全量，H-4 已落地）

```
TrackType（enum，7 值）                    ← 已删除
  → TrackTypeId（record，String id）+ TrackRegistry（启动期注册）   ← 已落地
```

影响面（**实测更正**，2026-11-04）：

| 项 | 数量 | 说明 |
|---|---|---|
| 引用 `TrackType` 的文件 | **43** | 40 直接 + 2 经 `Clip#getTrackType()` 间接 + 1 仅类型名；`fabric/`、`forge/` 零命中 |
| 真正 `switch (轨道类型)` | **2 处** | `TrackDriver` 工厂（→ `TrackRegistry.createDriver` 查表）、`ObjectValidator.Check.run`（→ `Map<TrackTypeId, Consumer<Check>>` + 兜底） |
| 按轨道类型分派的 `if`/三元/`filter` | **13 处** | `Clip`(6)、`ObjectValidator`(2)、`ScriptParser`(1 组)、`PlaybackInstance`、`PlayerMoveController`、`ScriptPlayer`、`ScriptEventManager`、`EventClipRuntime`、`Timeline`(2)、`ColorAdjustSampler`、`SchemaExporter`、`ScriptTemplate` |
| 以轨道类型为键的静态表 | **3 张** | `SchemaRegistry.TRACKS`（改为转发）、`ObjectSlotLayout.CACHE`（键换 `TrackTypeId`）、`TrackSchemas.all()`（键换 `TrackTypeId`，内容零改动） |

> 早期版本写的「10 处 `switch`」不成立：其中 8 处实际切在 `String`（`ScriptValidator`×2 / `RuleActions`×2 / `ClientConditions`×2）、`PropertyType`（`ObjectCodec` / `ObjectSchemaValidator`）、`int`（`BreathDisturbance`）上，与轨道类型无关。详见 §2.2 末段。

改造结果：

1. `TrackTypeId` 是**值对象**（`record`，equals/hashCode 按 id，`toString()` = id），不再是 enum 常量；内置 7 个以静态常量提供（`TrackTypeId.CAMERA` …）。
2. 内置轨道在 `TrackRegistry.ensureBuiltins()` 首次调用时按固定顺序登记，id 受保护（第三方占用内置 id → 注册期 `IllegalArgumentException`，消息含「内置轨道 id 受保护」）。
3. 2 处 `switch` 改为「注册表查表 + 兜底分支」；13 处 `if`/三元保留为**按 id 相等判断**（内置特化语义不改成可注册点——见 §11 边界）。
4. `SchemaRegistry` 由静态表改为「内置 + 注册合并」：`get(id)` 转发 `TrackRegistry.schema(id)`，未登记返回 `TrackTypeSchema.EMPTY`；`TrackRegistry.freeze()` 由 `ScriptParser.parse(String)` 首行触发，之后注册抛 `IllegalStateException`（消息含「注册必须在模组初始化期完成」）。
5. 第三方 `TrackPlayer` 经 `script/track/ExternalTrackDriver` 适配：活跃片段变化时 `stop` → 新建 `TrackContext` → `start`，每帧 `tick`；三类回调各自 `try/catch`，异常走 `ErrorLog.logRateLimited("Track", "third-party-track:<id>", 5000L, ...)`，单轨失败不中断其它轨道。


### 6.5 示例（第三方最小轨道）

```java
// 1) schema：一个数值对象 + 一个开关
TrackSchema schema = TrackSchema.builder()
        .animatable("blend", PropertyType.FLOAT, Interp.LINEAR, 0f)
        .value("clip_name", PropertyType.STRING, "")
        .build();

// 2) 注册
ImmersiveCinematicsApi.registerTrack(
        new TrackTypeId("mymod:actor_anim"), schema, ActorAnimPlayer::new);

// 3) 播放器
final class ActorAnimPlayer implements TrackPlayer {
    public void tick(TrackContext ctx) {
        float blend = ctx.f("blend");      // 本帧采样值；无关键帧时回落 value/缺省
        // 自己驱动模型
    }
}
```

## 七、触发器扩展（服务端 only）

### 7.1 契约

```java
public final class TriggerType {
    public TriggerType(String id, ListenStrategy strategy, int pollInterval,
                       BiPredicate<ServerPlayer, JsonObject> evaluator);
}
```

现有实现已在 `TriggerRegistry.register(TriggerType)`；本轮只需：

1. 增加对外门面（`ImmersiveCinematicsApi.registerTrigger(...)`）
2. 明确 `conditions` JSON 的校验契约（求值器自己校验，非法条件按未命中处理）
3. 明确线程：**服务端主线程**，每 tick（或按 `pollInterval`）调用

### 7.2 客户端不判定

**触发器只在服务端。** 客户端如果要贡献条件信息，只能发网络包给服务端、由服务端判定；不存在"客户端触发器"这条路径。

原因：两套判定会产生双份状态与竞态，且模组在客户端无法保证世界状态一致。

### 7.3 缺口（H-4 已关闭）

- **求值器抛异常时的隔离** → ✅ 早已有 `TriggerEngine.evaluateSafely(...)` 的 `try/catch`（本 tick 视为未命中），H-4 补上**限频**：`ErrorLog.logRateLimited("Trigger", "eval:<typeId>:<scriptId>", 5000L, …)`，不再每 tick 刷屏。
- **同名 id 覆盖策略** → ✅ `ImmersiveCinematicsApi.registerTrigger(...)` 在**门面层**先查 `TriggerRegistry.get(id)`，已存在则抛 `IllegalArgumentException("触发器 id 已登记: " + id)`；内部注册表语义（静默覆盖）保持不变，内置注册不受影响。
- **第三方触发器的 `poll_interval` 下限** → ✅ 沿用 `POLL_INTERVAL_MIN=1`，由 `TriggerType.resolvePollInterval` 钳制（非 `POLLING` 策略恒返 0）。

> 门面只做「id 冲突 + 冻结 + 转发」，不复制判定逻辑；`TriggerType` 仍可直接 `new`（设计 §3 允许触发器 SPI 出现 `ServerPlayer`）。


## 八、其他注册点（H-4：全部经 `api/ImmersiveCinematicsApi`）

```java
public static TrackTypeId registerTrack(TrackTypeId id, TrackSchema schema, TrackRegistry.TrackPlayerFactory factory);
public static void registerTrigger(TriggerType type);
public static void registerMathModel(String id, Map<String, FieldDef> params, Function<ModelParams, MathModel> factory);
public static void registerPathCurve(String id, Set<PathCapability> capabilities, List<PathParamDef> params,
                                     Function<PathParams, PathSpec> factory);
public static void registerPrerequisite(String id, TriggerPrerequisite prerequisite);
public static void registerBaseCamera(BaseCameraProvider provider);
public static void unregisterBaseCamera(BaseCameraProvider provider);
public static void registerCameraModifier(CameraModifier modifier);
public static void unregisterCameraModifier(CameraModifier modifier);
public static void registerHudLayer(ResourceLocation overlayId, String category);
public static void registerHudLayer(ResourceLocation overlayId);
public static boolean isRegistrationFrozen();
```

| 项 | 门面方法 | 落点 / 备注 |
|---|---|---|
| 轨道 | `registerTrack` | `script/track/TrackRegistry.register`（内置保护 + 重复 + schema 校验 + 冻结全在注册表内） |
| 触发器 | `registerTrigger` | `TriggerRegistry.register`；重复 id 在门面拦（IAE） |
| 插值模型（缓动） | `registerMathModel` | `MathModelRegistry.register`；`types()` 已含该 id → IAE |
| 路径曲线 | `registerPathCurve` | `PathCurveRegistry.register`（其自身已对重复 / 空名抛 IAE） |
| 前置条件 | `registerPrerequisite` | `PrerequisiteRegistry.register`；`has(id)` → IAE |
| 相机 Base | `registerBaseCamera` / `unregisterBaseCamera` | `CameraManager` 新增的 4 个转发器 → `CameraCoverChain` |
| 相机 Modifier | `registerCameraModifier` / `unregisterCameraModifier` | 同上 |
| HUD 层 | `registerHudLayer(×2)` | 直接转调 `api/HudLayerRegistry`（**唯一注册表**，不另建；后注册覆盖是 H11 既定语义） |
| overlay 图层 | — | 独立门面 `api/OverlayApi`，见下 |
| 模板 | ❌ 不开放 | 随 Z 轮删除 |

**`api/OverlayApi`（第三方出画面的唯一出口）**：

```java
public interface LayerRenderer { void render(GuiGraphics graphics, int screenWidth, int screenHeight); }
public static Handle openLayer(Object owner, String key, int zIndex, LayerRenderer renderer);
public static boolean closeLayer(Object owner, String key);
public static int closeLayers(Object owner);
public static boolean isOpen(Object owner, String key);
// Handle: int zIndex() / void setZIndex(int) / boolean isOpen() / void close()
```

- `owner` **必须**传播放实例 token（第三方轨道取 `TrackContext.owner()`）——`PlaybackInstance.stop()` 的 `OverlayManager.removeLayersOf(this)` 据此连带释放。
- `owner` 按**对象身份**比较（播放实例没有可靠 `equals`），`key` 按字符串相等。
- z 序与内置 overlay 完全同义（数值越小越先绘制）；同 `(owner, key)` 重复 `openLayer` = 更新 z 序 + 换 renderer，**不重建层、不 close**。
- `closeLayers(owner)` 只关**本 API 开的层**：不转调 `removeLayersOf(owner)`，否则第三方误调会连带拆掉同实例的画幅 / 字幕 / 转场遮罩层。
- 渲染异常隔离与限频唯一收口在 `OverlayManager.render`（逐层 `catch (Exception)` + `FatalErrors.rethrowIfFatal` + 限频日志），`OverlayApi` 不重复兜底，也不吞致命错误。
- **不提供** FBO / 渲染目标 / 合成上下文。


## 九、契约

### 9.1 注册时机（硬约束，已实现）

`ObjectSlotLayout` 的槽位按 schema 声明顺序分配，且缓存无失效机制；后注册字段会**重排槽位**，已分配的 `SampledClip` 缓冲全部失配。

因此：

> **所有注册必须发生在「第一个脚本被加载之前」**，即模组初始化期。注册表在首次加载脚本时**冻结**；冻结后再注册抛 `IllegalStateException`，消息里说明"注册必须在模组初始化期完成"。

实现细节：

| 项 | 实际做法 |
|---|---|
| 冻结入口 | `ScriptParser.parse(String)` 首行 `TrackRegistry.freeze()`——它是全仓唯一公开的 JSON→脚本入口（服务端加载 / 客户端收包 / 编辑器 push / 命令 play 全走它） |
| 冻结范围 | 进程内一次性；`reload` / 跨存档 / 编辑器重推**不会**解冻（注册表不重建） |
| 抛错文案 | 轨道：`轨道注册已冻结：注册必须在模组初始化期完成（当前 id: …）`；门面其余：`注册已冻结：注册必须在模组初始化期完成（首个脚本解析前）` |
| 内置注册 | `TrackRegistry.ensureBuiltins()` 幂等，由 `ImmersiveCinematics.init` 显式调用，且每个注册表读入口再调一次（防类加载顺序早于 mod init，例如 `ColorAdjustSampler` 的 `static final` 槽位） |
| 校验时机 | 内置 7 轨在 `ensureBuiltins()` 内一次性 `validateOrThrow`；第三方在 `register(...)` 内逐条 `validateEntry`——坏 schema 永远进不了运行期 |


### 9.2 线程

| 调用 | 线程 |
|---|---|
| `PlaybackApi` / `CameraApi` | 客户端主线程 |
| `ServerPlaybackApi` / 触发器 | 服务端主线程 |
| 第三方轨道 `tick` | 按轨道声明的作用域（客户端轨道 = 渲染帧主线程；服务端轨道 = server tick） |

跨线程调用由调用方自行 marshal。

### 9.3 失败隔离（H-4 已实现）

| 场景 | 行为 | 实现位置 |
|---|---|---|
| 第三方 `tick` 抛异常 | 该轨道本帧跳过 + 限频日志；**不中断脚本** | `ExternalTrackDriver.tickQuietly()` → `ErrorLog.logRateLimited("Track", "third-party-track:<id>", 5000L, "third-party track tick failed: …", e)`；`start` / `stop` 同样各自兜底 |
| 第三方 `schema` 非法 | **注册期**拒绝，不进运行期 | `TrackSchema.build()`（非空 / 路径唯一 / 至少一个数值对象）+ `TrackRegistry.register` 的 `ObjectSchemaValidator.validateEntry` |
| 第三方触发器求值抛异常 | 该触发器本 tick 视为未命中 + 限频日志 | `TriggerEngine.evaluateSafely(...)` → `logRateLimited("Trigger", "eval:<type>:<script>", 5000L, …)` |
| 第三方 overlay 图层渲染失败 | 本帧跳过该层 + 限频日志；不影响其它层 | `OverlayManager.render` 的逐层 `catch (Exception)` + `FatalErrors.rethrowIfFatal`（**唯一**隔离点，`OverlayApi` 不重复兜底） |
| `PlaybackListener` 回调抛异常 | 单个监听器跳过，不影响其它监听器与播放 | `PlaybackListeners` 广播逐个 `try/catch` → `logRateLimited("Playback", "listener", 5000L, …)` |

> 一个已知取舍：`PlaybackListeners` 捕获 `Throwable`（含第三方类加载失败的 `NoClassDefFoundError`），因为监听器完全属于第三方代码域；其余隔离点捕 `Exception` 并让致命错误照常上抛。

### 9.4 id 冲突（H-4 已实现）

- 内置 id 受保护，第三方不得占用 → `TrackRegistry.register` 抛 `IllegalArgumentException`，消息含「内置轨道 id 受保护」
- 第三方之间冲突 → **后注册者失败并报错**，不静默覆盖（轨道、触发器、插值模型、前置条件均在门面/注册表层拦；HUD 层沿用 H11 的「后注册覆盖」语义，因为它是 `overlayId → 分类` 的映射而不是 id 注册）
- id 命名建议带命名空间（`modid:xxx`），**不强制**；解析时第三方 id 区分大小写，内置 id 额外接受大小写不敏感写法（兼容旧脚本里的 `camera` / `Camera`）


### 9.5 稳定性

| 包 | 承诺 |
|---|---|
| `api` | 方法/字段/枚举**只增不删**；语义不变 |
| `spi` | 随模组版本演进；破坏性变更提供迁移说明 |
| 内部 | 无承诺 |

## 十、验收（H-4 实测结果）

| # | 项 | 通过标准 | 结果 | 证据 |
|---|---|---|---|---|
| A1 | 最小第三方轨道 | 1 个 `float` 对象 + 关键帧 → 注册成功、能播、`tick` 读到的值与脚本一致 | 🟡 **注册 + 解析 + 采样值已验证；实机 `tick` 未验证** | `H4ThirdPartyProbe`：`probe:actor_anim` + `blend`(float/animated) + `clip_name`(string/value)，`ScriptParser` 解析出对应轨道，`ClipSampler` 在 t=0/1/2 采到 0.0/0.5/1.0。**“能播”缺一个真实调用方模组**——本仓不能自带测试轨道（会成为永久脚手架），见 §十一 |
| A2 | 空 schema 被拒 | 注册期报错，消息指出"至少一个数值对象" | ✅ | `H4ThirdPartyProbe`：空 schema / 仅 string 对象 / 路径重复 三种均抛 `IllegalArgumentException`，文案含「至少需要一个数值对象」 |
| A3 | `tick` 抛异常 | 脚本继续播完，日志限频，其它轨道不受影响 | 🟡 **代码路径已验证，未做抛异常注入** | `ExternalTrackDriver.tickQuietly()` 的 `try/catch` + 限频键 `third-party-track:<id>`；实机日志无 `third-party-track` 记录（因为没有第三方轨道参与） |
| A4 | 晚注册 | 首个脚本加载后注册 → `IllegalStateException` | ✅ | `H4RegistryProbe`：`freeze()` 后 `registerTrack` 与 `registerPrerequisite` 均抛 ISE，消息含「注册必须在模组初始化期完成」 |
| A5 | id 冲突 | 第三方覆盖内置 id / 互相覆盖 → 注册期报错 | ✅ | `H4RegistryProbe`：重复第三方 id → IAE「已登记」；占用 `CAMERA` → IAE「内置轨道 id 受保护」 |
| A6 | 第三方触发器 | 服务端命中/未命中正确；抛异常时本 tick 视为未命中 | 🟡 **未验证** | 需要真实第三方触发器；`evaluateSafely` + 限频已就位，但无注入测试 |
| A7 | 只读面 | `CameraApi.getCameraState()` 与顶层 lane 六参数同帧一致 | 🟡 **结构已就位，未做数值对比** | `getLaneComposite()` 直接复用本帧 `CameraTrackPlayer.getLaneSnapshots()`，不重新求值；实机四象限渲染正常 |
| A8 | 播放事件 | `onFinished` 的 `CompletionReason` 能区分自然结束 / 跳过 / 打断 | 🟡 **触发点已接，未做三原因对比** | `PlaybackLedger.reportScriptFinished` 带上既有 `CompletionReason`；实机自然播完可观察，跳过 / 打断未逐项跑 |
| A9 | overlay 出口 | 第三方轨道通过 `OverlayApi` 开的层，z 序与释放行为与内置 overlay 一致 | 🟡 **未验证** | 需要第三方调用方；`OverlayApi` 的 z 序复用 `OverlayManager.addLayer` 排序、释放走 `close()`/`removeLayersOf` |

**探针（headless，全部绿）**

| 探针 | 内容 | 结果 |
|---|---|---|
| `H4RegistryProbe` | 内置 7 轨顺序 / `resolve` 大小写 / schema 与槽位一致 / 第三方注册 / 冲突 / 冻结 / 导出含第三方 / 门面冻结视图 | **47 passed, 0 failed** |
| `H4ThirdPartyProbe` | schema 三条硬校验 / id 校验 / `ScriptParser` 解析第三方轨道 / `ClipSampler` 三点取值 / 值域字段 / `ScriptValidator` 不拒绝 / `ObjectValidator` 兜底 | **23 passed, 0 failed** |
| `H4RegressionProbe` | 全仓 249 个脚本：243 解析成功，**860 个片段 × 3 个时间点采样**，覆盖全部 7 类内置轨道；6 个已知坏脚本仍被拒（3 个 `tests/faults/` + 3 个陈旧夹具） | **12 passed, 0 failed** |

**实机（Fabric dev 客户端，flat 世界，两轮）**

第一轮（`fabric/run/immersive_cinematics/scripts/h4probe/` 4 个夹具 + 既有测试集）：

- 4 个代表夹具全部被 `ScriptManager` 加载（`h4probe/test_adjust_blend.json` 等）。
- 5 个脚本真正起播，`TrackDriver数` 分别为 1 / 3 / 4 / 5（含四相机轨与多轨合成），`OVERLAY switch` 正常。
- 截图 `E:/tmp/h4/real-shot.png`（2560×1369）：四象限分屏四相机 lane 同时出画 + 「长按 C 跳过动画」HUD。

第二轮（补 LETTERBOX / AUDIO；夹具去掉指向不存在脚本的坏 `requires`）：

- 7 个脚本起播：`h4_audio`、`h4_letterbox`、`quadrant_demo`、`test_adjust_blend`、`test_h1_dwell`、`test_objects_event`、`test_objects_multitrack`（全部 `result=1`）。
- `Letterbox` 相关日志 43 行、`Audio` 相关日志 38 行——**LETTERBOX 与 AUDIO 轨道的播放器确实被创建并驱动**。
- 合并两轮：**内置 7 类轨道（CAMERA / LETTERBOX / AUDIO / EVENT / MOD_EVENT / OVERLAY / ADJUST）全部在实机上跑过**。
- 两轮日志**零**轨道相关异常（无 `未知轨道类型` / `ClassCastException` / `NoSuchMethodError` / `NoClassDefFoundError`）——与探针的 860 片段采样结论一致。

> 复现注意事项（踩过的坑）：实机启动前必须清掉本项目残留 `java.exe`（命令行含 `ImmersiveCinematics`），否则 `fabric/run/.fabric/processedMods` 被占用 → `Failed to remap mods!`。gradle 守护进程是 fork 出去的，`taskkill /T` 杀 python 包装器**不会**杀掉它启动的游戏 JVM。


## 十一、实现结果（H-4，2026-11-04）

**已落地**（编译：`:common` / `:fabric` / `:forge` 三模块均 BUILD SUCCESSFUL）：

| 交付物 | 落点 |
|---|---|
| `TrackType` enum → 注册表 | 删除 `script/TrackType.java`；新增 `spi/TrackTypeId` + `script/track/TrackRegistry`；43 个引用文件全量迁移；`grep -rn "\bTrackType\b"` = 0 |
| schema 静态表 → 内置 + 注册合并 | `SchemaRegistry` 只转发；`ObjectSlotLayout.CACHE` 键换 `TrackTypeId`；`TrackSchemas.all()` 键换 `TrackTypeId`（内容零改动） |
| `spi` 四件套 | `TrackTypeId` / `TrackSchema` / `TrackPlayer` / `TrackContext`（+ 迁移的 `PropertyType` / `Interp` / `PropertyMode` / `ObjectDef`） |
| 第三方适配 | `script/track/{TrackDriver,ExternalTrackDriver,TrackContextImpl,BuiltinTracks,BuiltinTrackDrivers}` |
| `api` 播放链路 | `PlaybackApi` / `ServerPlaybackApi`(+Impl) / `PlaybackListener` / `ScriptInfo` / `PlaybackListeners` |
| 相机只读面 | `CameraApi.getLaneComposite()` / `getActiveBaseProvider()` + `BaseCameraProvider.providerId()` + `CameraCoverChain.activeProviderId()` + `ScriptPlayer.activeCameraLanes()` |
| 统一注册门面 | `api/ImmersiveCinematicsApi`（12 个方法）；冲突检查在门面层，内部注册表语义不变 |
| overlay 出口 | `api/OverlayApi`（owner 归属 + z 序 + 幂等关层，无 FBO） |
| 事实对外读面 | `api/ServerFactsApi`（11 个只读查询，复用 `FactLedger`） |
| 注册时机 | `ScriptParser.parse` 首行 `TrackRegistry.freeze()`；冻结后 ISE |
| 失败隔离 | 轨道 `tick`/`start`/`stop` 各自 try/catch + 限频；触发器求值限频；overlay 由 `OverlayManager` 统一隔离 |

**未做 / 移交**：见 `../I-BACKLOG.md` H-4 小节（H4-R1 `spi` 类型收口、H4-R2 第三方轨道的通用 loop/pingpong 诊断、H4-R3 `ExternalTrackDriver` 对工厂返回 null 的防御、H4-R4 第三方真实调用方模组用于 A1/A3/A6/A9 的端到端验证）。


## 十一、不做

- 不做客户端触发器（只服务端判定）
- 不开放渲染目标 / FBO / 合成上下文
- 不开放模板注册（随 Z 轮删除）
- 不开放 `ClipSampler` / `ObjectSlotLayout` / `SampledProperty` 等内部采样设施
- 不为第三方效果的正确性背书
- 不做运行时热注册（只能初始化期）

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

## 二、代码现状（2026-11-03 核对）

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
| 模板 | `TemplateRegistry.register(ClipTemplate)` | ⚠️ 不纳入对外（I 轮已定随 Z 轮删除） |

### 2.2 缺口

| 缺口 | 证据 |
|---|---|
| **没有统一门面**：上面 8 个注册表分散在 `camera.cover` / `trigger.server` / `script.schema` / `client.dimension` / `script.template`，第三方要逐一依赖内部类 | — |
| **轨道类型不可注册**：`TrackType` 是 `enum`（7 个固定值），被 **43 个文件**引用，含 **10 处 `switch`**（`TrackPlayer.create`、`ScriptValidator`、`ObjectValidator`、`ObjectCodec`、`ObjectSchemaValidator` 等） | `script/TrackType.java` |
| **schema 是静态表**：`SchemaRegistry.TRACKS` 为 `static final`，类加载期一次性 `ObjectSchemaValidator.validateOrThrow` | `script/schema/SchemaRegistry.java:13-18` |
| **槽位布局无失效机制**：`ObjectSlotLayout.CACHE` 是静态 `ConcurrentHashMap`，槽位按 schema 声明顺序分配 | `script/object/ObjectSlotLayout.java:26,59-62` |
| **播放没有稳定入口**：只有 `CameraManager.playCinematic(script, instanceId)` / `stopScriptById(...)`，没有按 id 播、没有完成事件、没有 `CompletionReason` 暴露 | `camera/CameraManager.java:88-101` |
| **相机只读面很薄**：`CameraApi` 只有 3 个方法，lane 合成参数、覆盖链当前层不可见 | `api/CameraApi.java` |
| **`TriggerType` 直接暴露 MC 类型**：`BiPredicate<ServerPlayer, JsonObject>` | `trigger/server/TriggerType.java:28-38` |

## 三、分层

| 包 | 面向 | 内容 | 稳定性 |
|---|---|---|---|
| `api` | 只读查询 + 播放控制 | `CameraApi`、`CameraState`、`PlaybackApi`、`ScriptInfo` | **稳定**：只增不删 |
| `spi` | 第三方实现与注册 | `TrackTypeId`、`TrackSchema`、`TrackPlayer`、`TrackContext`、`TriggerType`、`Prerequisite`、`MathModelDef`、`PathCurveDef`、`BaseCameraProvider`、`CameraModifier`，以及**声明 schema 所必需的 `PropertyType` / `Interp` / `PropertyMode`** | 随模组版本演进；破坏性变更给迁移说明 |
| 内部 | 不暴露 | `ClipSampler`、`ObjectSlotLayout`、`SampledProperty`、`SampledClip`、`ScriptPlayer`、各 `TrackPlayer` 实现、`PlaybackInstance` | 随时改 |

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

`CompletionReason` 已有（自然结束 / 跳过 / 打断 / 停止），当前只在内部使用，需要暴露。

### 4.4 缺口

- 没有"脚本 id → `CinematicScript`"的公共查找（`ScriptManager` 是内部）
- 没有事件总线；`onFinished` 的触发点散在 `PlaybackLifecycle` 与 `ScriptEventManager`
- 播放失败没有结构化原因（现在靠日志）

## 五、相机与核心参数（只读）

### 5.1 已有

```java
public interface CameraApi {
    boolean isActive();
    boolean hasActiveCameraClip();
    CameraState getCameraState();      // 不可变 record：position/yaw/pitch/roll/fov/zoom
}
```

### 5.2 建议补齐

| 新增 | 用途 |
|---|---|
| `CameraLaneComposite getLaneComposite()` | 当前顶层 lane 的合成参数（opacity / dest / source / mask），只读 |
| `String getActiveBaseProvider()` | 覆盖链当前胜出的 Base 层（诊断/调试用） |

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

### 6.4 注册表化改造（A 方案：全量）

```
TrackType（enum，7 值）
  → TrackTypeId（record，String id）+ TrackRegistry（启动期注册）
```

影响面：

| 项 | 数量 |
|---|---|
| 引用 `TrackType` 的文件 | 43 |
| `switch (trackType)` | 10 处（`TrackPlayer.create`、`ScriptValidator` ×2、`RuleActions`、`ClientConditions` ×2、`ObjectCodec`、`ObjectValidator`、`ObjectSchemaValidator`、`BreathDisturbance`） |
| 以 `TrackType` 为键的静态表 | `SchemaRegistry.TRACKS`、`ObjectSlotLayout.CACHE`、`TrackSchemas` 的各 `xxxObjects()` |

改造要求：

1. `TrackTypeId` 是**值对象**（equals/hashCode 按 id），不再是 enum 常量
2. 内置轨道在模组初始化期注册，id 受保护
3. 所有 `switch` 改成「注册表查表 + 兜底分支」
4. `SchemaRegistry` 由静态表改为「内置表 + 注册期合并」，注册完成后**冻结**

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

### 7.3 缺口

- 求值器抛异常时的隔离（现在会冒泡到 tick 循环）
- 同名 id 覆盖策略
- 第三方触发器的 `poll_interval` 下限（沿用 `POLL_INTERVAL_MIN=1`）

## 八、其他注册点

| 项 | 对外形态 | 备注 |
|---|---|---|
| 插值模型（缓动） | `registerMathModel(id, Map<String,FieldDef>, factory)` | 第三方自定义缓动曲线，供 `motion_*` / `transition_curve` 使用 |
| 路径曲线 | `registerPathCurve(id, capabilities, factory)` | 供脚本级路径条目的 `geometry.type` 使用 |
| 前置条件 | `registerPrerequisite(id, TriggerPrerequisite)` | 供 `meta.triggers[].requires` 使用 |
| HUD 层 | 见 [`hud-layer-api.md`](hud-layer-api.md)（H11） | 已有独立设计稿 |
| 相机 Base / Modifier | `registerBaseCamera(...)` / `registerCameraModifier(...)` | 优先级域已存在，补门面 |
| **overlay 图层** | 新增 `OverlayApi`（§6.3 的出口） | 只提供"开层/更新/关层"，不提供 FBO |
| 模板 | ❌ 不开放 | I 轮已定随 Z 轮删除 |

## 九、契约

### 9.1 注册时机（硬约束）

`ObjectSlotLayout` 的槽位按 schema 声明顺序分配，且缓存无失效机制；后注册字段会**重排槽位**，已分配的 `SampledClip` 缓冲全部失配。

因此：

> **所有注册必须发生在「第一个脚本被加载之前」**，即模组初始化期。注册表在首次加载脚本时**冻结**；冻结后再注册抛 `IllegalStateException`，消息里说明"注册必须在模组初始化期完成"。

### 9.2 线程

| 调用 | 线程 |
|---|---|
| `PlaybackApi` / `CameraApi` | 客户端主线程 |
| `ServerPlaybackApi` / 触发器 | 服务端主线程 |
| 第三方轨道 `tick` | 按轨道声明的作用域（客户端轨道 = 渲染帧主线程；服务端轨道 = server tick） |

跨线程调用由调用方自行 marshal。

### 9.3 失败隔离

| 场景 | 行为 |
|---|---|
| 第三方 `tick` 抛异常 | 该轨道本帧跳过 + 限频日志；**不中断脚本** |
| 第三方 `schema` 非法 | **注册期**拒绝，不进运行期 |
| 第三方触发器求值抛异常 | 该触发器本 tick 视为未命中 + 限频日志 |
| 第三方 overlay 图层资源失败 | 关层 + 限频日志；不影响其它层 |

### 9.4 id 冲突

- 内置 id 受保护，第三方不得占用 → 注册期报错
- 第三方之间冲突 → **后注册者失败并报错**，不静默覆盖
- id 命名建议带命名空间（`modid:xxx`），但不强制

### 9.5 稳定性

| 包 | 承诺 |
|---|---|
| `api` | 方法/字段/枚举**只增不删**；语义不变 |
| `spi` | 随模组版本演进；破坏性变更提供迁移说明 |
| 内部 | 无承诺 |

## 十、验收

| # | 项 | 通过标准 |
|---|---|---|
| A1 | 最小第三方轨道 | 1 个 `float` 对象 + 关键帧 → 注册成功、能播、`tick` 读到的值与脚本一致 |
| A2 | 空 schema 被拒 | 注册期报错，消息指出"至少一个数值对象" |
| A3 | `tick` 抛异常 | 脚本继续播完，日志限频，其它轨道不受影响 |
| A4 | 晚注册 | 首个脚本加载后注册 → `IllegalStateException` |
| A5 | id 冲突 | 第三方覆盖内置 id / 互相覆盖 → 注册期报错 |
| A6 | 第三方触发器 | 服务端命中/未命中正确；抛异常时本 tick 视为未命中 |
| A7 | 只读面 | `CameraApi.getCameraState()` 与顶层 lane 六参数同帧一致 |
| A8 | 播放事件 | `onFinished` 的 `CompletionReason` 能区分自然结束 / 跳过 / 打断 |
| A9 | overlay 出口 | 第三方轨道通过 `OverlayApi` 开的层，z 序与释放行为与内置 overlay 一致 |

## 十一、不做

- 不做客户端触发器（只服务端判定）
- 不开放渲染目标 / FBO / 合成上下文
- 不开放模板注册（随 Z 轮删除）
- 不开放 `ClipSampler` / `ObjectSlotLayout` / `SampledProperty` 等内部采样设施
- 不为第三方效果的正确性背书
- 不做运行时热注册（只能初始化期）

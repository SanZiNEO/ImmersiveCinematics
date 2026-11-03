# H-1 轮项目地图与共享契约（主代理）

> 建立时间：2026-11-03。依据：本轮任务书（H-1 触发器语义：H7 事实层 + H8 连续判定）+ `plans/0.3.6/H-other/state-tracking.md` + `plans/0.3.6/H-other/trigger-continuity.md` + 源码核对。
> 行号仅供定位，以类名 / 方法签名为准。`docs/` 已过时，只作线索；冲突以设计文档与源码为准。
> 前置轮次：I-4（已完成，见 `ROUND-I-4-MAP.md`）。本文件是 H-1 派单与验收的唯一索引。

---

## 〇、本轮实现口径（已按设计文档推导，非用户待确认项）

| # | 项 | 口径 |
|---|---|---|
| 0.1 | 事实层位置 | **服务端**，`trigger/server/fact/`。与 I-4 的播放端规则事实（`script/rule/runtime/RuleFactStore`、`client/rule/FactSource`）**不同层、不合并**（state-tracking.md §三 边界） |
| 0.2 | 事实维度 | `type` / `script` / `object` / `detail` / `instance` / `tick` / `result` / `scene`。主体 = 玩家（按玩家分片存储） |
| 0.3 | 触发器身份 | `TriggerDefinition` 新增可选 `id`。**现有 232 条触发器声明已全部带 `id`、格式合法、无重复**（2026-11-03 全量核对）——解析层此前忽略该字段。身份 = 显式 `id`；缺省生成 `type + "#" + 声明下标`（`#` 不在合法 id 字符集内，不会碰撞） |
| 0.4 | 持久性 | 按事实类型声明：`PLAYBACK_STARTED` / `PLAYBACK_FINISHED` / `TRIGGER_FIRED` 跨存档（SNBT v2）；其余会话内（退出即清）。旧 v1 文件做**一次性转换**（不是兼容读取层）：三份集合转事实，旧触发器键按 `type_scriptId` 反查唯一声明映射到新身份，歧义/无匹配丢弃并 debug 日志 |
| 0.5 | 查询语义 | `exists` / `count` / `last` 由**常驻索引**回答（累计口径，保留集淘汰后仍成立）；`anyWithin` / `lastWithin` 由**保留集**回答（受保留策略约束，窗口 ≤ 1200 tick，保留深度足够） |
| 0.6 | 时间基准 | 统一服务端 tick（`MinecraftServer#getTickCount`）；`dwell` / `window` 单位为 tick（不引入秒） |
| 0.7 | 五份记录 | ①持久布尔 → 事实；②九个 tracker → 八个改事实、`InventoryTracker` 保留为「采样基线」；③实例账本 → 历史（起止 / 投票）进事实，当前态（观看者 / 暂停 / 时间线基准 / 已执行关键帧游标）保留；④区域进入态 → 边沿状态（H8）+ 进入/离开事实；⑤客户端镜像 → 删除（无读面） |
| 0.8 | 连续语义 | 按触发器显式开启（`continuity.sweep`），默认关；未开启 = 现有求值路径逐位不变。扫掠 = 相邻两个**该触发器采样点**之间的线段/弧段近似 |
| 0.9 | 不连续 | 传送 / 维度切换 / 载具变化 / 玩家加入由 `PlayerMotion` 的「代次」标记；每 tick 位移 > 16 格视为跳变（阈值兜底）。跳变段不参与扫掠 |
| 0.10 | 观察（observation） | 本轮**不扫掠**（设计 §7 步骤 3：成本实测后再定）；记入移交 |
| 0.11 | 窗口 | `continuity.window > 0` 才允许事件类子条件进 `all_of` / `any`；`window = 0` 保持现有校验拒绝。顶层事件触发器不受窗口影响 |
| 0.12 | 客户端镜像 | `S2CTriggerStateSyncPacket` + `ClientTriggerStateCache` 删除（全仓零读面）。事实的展示面归后续轮次 |
| 0.13 | 探针位置 | 仓库外 `E:/tmp/h1/`；临时日志不入库；夹具放 `cinematics/0.3.6/tests/trigger/` |
| 0.14 | 不做 | 不改条件字段形态与组合器结构（归 C-trigger）；不做真·连续时间；不做客户端判定；不做通用事件总线；不改 EVENT 命令协议；不做编辑器 UI |

---

## 一、任务清单

| 编号 | 目标（一句话） | 验收 | 依赖 | 对应任务书切片 |
|---|---|---|---|---|
| **H1-1** | 事实模型 + 存储 + 查询 + 持久化（v2，含 v1 一次性转换） | `FactStoreProbe` PASS；持久事实重启后仍在；会话事实不落盘；窗口/计数/最近一次/存在性四类查询正确 | — | 切片 1 + 3（持久化） |
| **H1-2** | 触发器身份：`id` 解析 / 校验 / 注册 / 使用边 | `IdentityProbe` PASS；两同类型触发器各自独立；缺 id 生成 `type#index`；重复/非法 id 报错；旧 232 条解析校验 diff 0 | — | 切片 2（身份部分） |
| **H1-3** | 连续语义纯几何核（段-方体 / 段-球 / 朝向弧 / 子步走段） | `ContinuityProbe` PASS；子步数与耗时数据产出 | — | 切片 4（几何部分） |
| **H1-4** | 引擎生命周期事实 + 三前置条件切事实查询 + 删除布尔存储 | 编译通过；`PrereqFactsProbe` PASS；`TriggerStateStore`/`PlayerTriggerState` 全仓 0 引用 | H1-1, H1-2 | 切片 2 + 3 |
| **H1-5** | tracker 收编 + 实例账本收编 + 客户端镜像删除 + `ScriptPlayback.server` 缺陷 | `TrackerFactsProbe` PASS；`grep` 无 `skipVoters` / `ClientTriggerStateCache` / `S2CTriggerStateSyncPacket`；跳过票未达标不再 NPE | H1-1, H1-4 | 切片 2 + 3 |
| **H1-6** | 连续语义接入引擎：采样 / 边沿 / 扫掠 / `dwell` / 区域事实 | `EdgeProbe` PASS；穿越用例必触发；未开启连续语义路径不变 | H1-1, H1-2, H1-3, H1-4 | 切片 4 |
| **H1-7** | `window` + 事件类子条件按窗口解禁 | `WindowProbe` PASS；`window=0` 仍拒绝；`window>0` 接受并按窗口判定 | H1-1, H1-6 | 切片 5 |
| **H1-8** | 全量探针 + 旧基线回归 + 实机 + 文档回写 + 移交落盘 | 全部探针 PASS；旧 231 支 parse/validate diff 0；实机穿越与 dwell 两用例；`I-BACKLOG.md` 新增 H-1 小节 | 全部 | 切片 6 |

### 依赖图

```
H1-1 ─┬─→ H1-4 ─┬─→ H1-5
      │          └─→ H1-6 ─→ H1-7
H1-2 ─┴────────────→ H1-6
H1-3 ──────────────→ H1-6
H1-1,H1-2,H1-3,H1-4,H1-5,H1-6,H1-7 ─→ H1-8
```

### 派单波次（文件所有权隔离）

| 波次 | 并行任务 | 文件所有权 |
|---|---|---|
| 1 | H1-1、H1-2、H1-3 | H1-1：`trigger/server/fact/**` + `ServerEventHandler` 生命周期；H1-2：`TriggerDefinition` / `ScriptParser` / `ScriptValidator` / `ScriptManager` / `TriggerRegistration` / `SchemaExporter`；H1-3：`trigger/server/continuity/{MotionSample,SegmentSweep}`（仅新建） |
| 2 | H1-4、H1-5 | H1-4：`TriggerEngine` / `BuiltinPrerequisites` / 删除 `store/**`；H1-5：`Evaluators` / `ScriptEventManager` / `ServerEventHandler` 记录点 / 网络包删除 |
| 3 | H1-6、H1-7 串行 | H1-6：`TriggerEngine`（边沿）/ `Evaluators`（扫掠重载）/ `TriggerType` / `TriggerDefinition`+parser+validator（`continuity`）/ `continuity/**`；H1-7：`Evaluators`（窗口）/ `ScriptValidator`（解禁）/ `FactWindow` |
| 4 | H1-8 | 探针 / 夹具 / 文档 |

> H1-4 与 H1-5 都触及 `ServerEventHandler`：H1-1 已把生命周期调用换成 `FactLedger`；H1-4 只改 `TriggerEngine` 与 `BuiltinPrerequisites`；H1-5 改 `ServerEventHandler` 的记录/清理点。派单时写死。

---

## 二、现状地图（源码核对，2026-11-03）

### 2.1 触发流水线

| 入口 | 位置 | 现状 |
|---|---|---|
| 轮询 | `TriggerEngine#onServerTick`（`pollBuckets`，`tickCounter % interval`） | 每桶 × 每玩家 × 每注册：`prerequisitesMet` → `shouldSkip` → `evaluateSafely` →（`on_enter`）`checkEnterState` → `fireTrigger` |
| 事件 | `TriggerEngine#onGameEvent(eventTypeId, player)`（`eventIndex`） | 同上，`checkEnterState` 用 `inRegion=true` 调用 |
| 延迟 | `TriggerEngine#processDelayedFires` | `DelayedFire(reg, fireTick)`，引用判重 |
| 注册 | `ScriptManager#registerAllTriggers` | `triggerId = td.getType() + "_" + meta.getId()`（**已确认：232 条声明全带 `id`，该字段被忽略**） |

### 2.2 五份记录现状

| # | 记录 | 位置 | 生命周期 | 本轮处置 |
|---|---|---|---|---|
| ① | 持久布尔三集合 | `store/PlayerTriggerState` + `store/TriggerStateStore`（SNBT v1） | 跨存档 | 改事实，删类 |
| ② | 九个 tracker | `Evaluators`（Kill / Death / Advancement / Dimension / Interact / Craft / PickupDrop / UseItem / Inventory） | 会话 | 八个改事实；Inventory 保留为采样基线 |
| ③ | 实例账本 | `ScriptEventManager.ScriptPlayback`：`viewers` / `skipVoters` / `triggeredKeyframes` / `paused` / 时间线基准 | 实例 | 投票改事实 + 实例索引；`viewers`/`paused`/基准/关键帧游标保留并写理由 |
| ④ | 区域进入态 | `TriggerEngine.enterStates`（`Map<UUID, Map<"脚本:触发器", Boolean>>`） | 进程内 | 改 `TriggerEdge` 边沿状态 + 进入/离开事实 |
| ⑤ | 客户端镜像 | `S2CTriggerStateSyncPacket` + `ClientTriggerStateCache` | 客户端内存 | 删除（零读面） |

### 2.3 已知缺陷

| 缺陷 | 位置 | 本轮 |
|---|---|---|
| `ScriptPlayback.server` 声明未赋值，`broadcastSkipVote` 读它 → 跳过票未达标时空指针 | `ScriptEventManager` 字段 / 构造器 / `broadcastSkipVote` | **H1-5 修**：构造器接收 / 赋值 `MinecraftServer` |
| 同类型两条触发器共用去重键与进入状态键 | `registerAllTriggers` + `TriggerDefinition` 无 id | **H1-2 修** |
| 播放结束丢失 `instanceId` / `reason` | `TriggerEngine#onScriptFinished` 只传 scriptId | **H1-4 修** |
| tracker 单槽同 tick 覆盖 / 无时间戳 | `Evaluators` 各 `Map<UUID, 值>` | **H1-5 修** |
| `DimensionTracker.getLastFrom` 会过期 | `Evaluators.DimensionTracker` | 事实带 tick 后可查询；「最近一次」语义保持 |

### 2.4 关键现有签名

```java
// trigger/server/TriggerEngine.java
public void onGameEvent(String eventTypeId, ServerPlayer player)
public void onServerTick(MinecraftServer server)
public void onScriptFinished(ServerPlayer, String scriptId, String instanceId, CompletionReason reason)
public void onPlaybackStarted(ServerPlayer, String scriptId, String instanceId)
private boolean prerequisitesMet(ServerPlayer, TriggerRegistration)
private boolean shouldSkip(ServerPlayer, TriggerRegistration)
private void fireTrigger(ServerPlayer, TriggerRegistration)
private boolean checkEnterState(ServerPlayer, TriggerRegistration, boolean inOriginal)
private boolean evaluateSafely(TriggerRegistration, ServerPlayer)
private boolean evaluateSafely(TriggerRegistration, ServerPlayer, JsonObject conditions)

// trigger/server/TriggerRegistration.java（11 参全参构造）
TriggerRegistration(String scriptId, String triggerId, TriggerType type, JsonObject conditions,
    List<TriggerAction> actions, boolean repeatable, int delayMs, boolean onEnter, float exitBuffer,
    JsonObject exitConditions, List<TriggerRequirement> requires, int effectivePollInterval)

// trigger/server/TriggerType.java
TriggerType(String id, ListenStrategy strategy, int pollInterval, BiPredicate<ServerPlayer, JsonObject> evaluator)
public boolean evaluate(ServerPlayer player, JsonObject conditions)

// script/TriggerDefinition.java（8 参全参构造）
TriggerDefinition(String type, Map<String,Object> conditions, boolean repeatable, float delay,
    boolean onEnter, float exitBuffer, List<TriggerRequirement> requires, Integer pollInterval)

// ScriptParser.parseTriggerDefinition(JsonObject obj, String p)  —— 需加声明下标
// ScriptManager.registerAllTriggers() —— triggerId 拼接处
// ScriptValidator.validateTriggerConditions / validateTriggerRequires / validateCombinationConditions
// Evaluators.matchesScene(...) —— private，H1-5 提取到 GeometryConditions
```

### 2.5 平台接线

| 事件 | Fabric | Forge | 本轮新增 |
|---|---|---|---|
| 维度切换 | `ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD` → `ServerEventHandler.onChangeDimension` | `PlayerChangedDimensionEvent` | 重置采样代次 |
| 加入 / 退出 | `ServerPlayConnectionEvents.JOIN/DISCONNECT` | 对应事件 | 事实载入/落盘已接 |
| 载具变化 | 无事件 | 无事件 | `PlayerMotion` 每 tick 比对 `player.getVehicle()` |
| 传送 | 无跨平台事件 | 无 | 每 tick 位移阈值（16 格）兜底 |

---

## 三、共享契约（Java 形态，派单按此写）

### 3.1 事实层（H1-1 新建，`trigger/server/fact/`）

```java
public enum FactType {
    PLAYBACK_STARTED(Durability.PERSISTENT, 256),
    PLAYBACK_FINISHED(Durability.PERSISTENT, 256),
    TRIGGER_FIRED(Durability.PERSISTENT, 1024),
    REGION_ENTERED(Durability.SESSION, 512), REGION_LEFT(Durability.SESSION, 512),
    SKIP_VOTE(Durability.SESSION, 0),        // 0 = 不按条数淘汰，随实例结束清理
    LOGIN(SESSION, 16), ENTITY_KILL(SESSION, 256), DEATH(SESSION, 64),
    ADVANCEMENT(SESSION, 256), DIMENSION_CHANGE(SESSION, 64),
    INTERACT_BLOCK(SESSION, 256), INTERACT_ENTITY(SESSION, 256),
    ITEM_CRAFT(SESSION, 256), ITEM_USE(SESSION, 128), ITEM_CONSUME(SESSION, 128),
    ITEM_RELEASE(SESSION, 128), ITEM_USE_INTERRUPT(SESSION, 64), ITEM_INSTANT_USE(SESSION, 128),
    ITEM_PICKUP(SESSION, 512), ITEM_DROP(SESSION, 256);
    public enum Durability { SESSION, PERSISTENT }
    public Durability durability(); public int maxRetained(); public String id(); static FactType byId(String);
}

public enum FactResult { NONE, FINISHED, STOPPED, INTERRUPTED, SKIPPED, FORCE_QUIT, SUCCEEDED, FAILED;
    public static FactResult of(CompletionReason reason); public static FactResult byId(String); }

public record FactScene(String dimension, String biome, double x, double y, double z) {}

public record Fact(FactType type, String script, String object, String detail,
                   String instance, int tick, FactResult result, FactScene scene) {
    // 工厂（空串归一，scene 可 null）
    static Fact event(FactType type, String object, int tick);
    static Fact event(FactType type, String object, String detail, int tick);
    static Fact scene(FactType type, String object, FactScene scene, int tick);
    static Fact playbackStarted(String script, String instance, int tick);
    static Fact playbackFinished(String script, String instance, FactResult result, int tick);
    static Fact triggerFired(String script, String triggerId, int tick);
    static Fact region(FactType type, String script, String triggerId, int tick);
    static Fact vote(String script, String instance, int tick);
}

/** 每玩家存储：按类型分片 + 常驻索引（exists/count/last）+ 保留集（窗口查询）。 */
public final class PlayerFacts {
    public void append(Fact fact);
    public boolean exists(FactType type, String script, String object);
    public int count(FactType type, String script, String object);
    public Fact last(FactType type, String script, String object);
    public Fact last(FactType type, String script, String object, FactResult requiredResult);
    public boolean anyWithin(FactType type, String script, String object, int fromTick, int toTick);
    public Fact lastWithin(FactType type, String script, int fromTick, int toTick,
                           java.util.function.Predicate<Fact> filter);
    /** 全量索引上的「最近一次匹配」：扫每键的最后一条，取 tick 最大者（累计口径）。H1-5 新增。 */
    public Fact lastMatching(FactType type, String script, java.util.function.Predicate<Fact> filter);
    public boolean anyObject(FactType type, java.util.function.Predicate<String> objectMatcher);
    public int retainedCount(FactType type);
    public java.util.List<Fact> retained(FactType type);      // 只读
    public void removeInstance(String script, String instance);
    public boolean isDirty(); public void markClean();
    public java.util.List<Fact> persistentFacts();
}

/** 服务端单例：tick 无关（记录时显式传 tick）、按玩家分片、投票实例索引、SNBT v2。 */
public final class FactLedger {
    public static final FactLedger INSTANCE;
    public static final int VERSION = 2;
    public void initialize(MinecraftServer server);                 // 目录
    public PlayerFacts of(UUID player);                             // 不存在则建
    public PlayerFacts ofOrNull(UUID player);
    public void loadForPlayer(UUID player);                         // 含 v1 一次性转换
    public void unloadForPlayer(UUID player);                       // 先存后删
    public void saveIfChanged(UUID player); public void saveAll();
    public void record(UUID player, Fact fact);
    public void recordVote(UUID voter, String script, String instance, int tick); // 幂等（同实例重复投票不重复记）
    public boolean hasVoted(UUID voter, String script, String instance);
    public int voteCount(String script, String instance);
    public void clearInstance(String script, String instance);      // 实例结束：清票事实 + 索引
    public void clearAll();                                         // 服务端停止 / 换档
}

/** 纯查询助手（探针可直调，不依赖 ServerPlayer）。 */
public final class FactQueries {
    public static boolean scriptStarted(UUID, String script);
    public static boolean scriptFinished(UUID, String script);
    public static boolean scriptPlayed(UUID, String script);        // started && finished（任一结果）
    public static boolean triggerFired(UUID, String script, String triggerId);
    public static Fact lastPlaybackFinished(UUID, String script);
}

/** SNBT 编解码（v2；只编持久事实）。 */
public final class FactCodec {
    static CompoundTag encode(Fact fact); static Fact decode(CompoundTag tag);
}
```

**查询语义（写进 Javadoc）**：
- `exists` / `count` / `last`：常驻索引，**累计口径**（保留集淘汰后仍成立），键 = `script + '\0' + object`。
- `anyWithin` / `lastWithin` / `anyObject`：扫描保留集 / 索引键，受 `maxRetained` 约束。
- `SKIP_VOTE` 不按条数淘汰；实例结束由 `clearInstance` 清理。
- v1 转换：`triggered_scripts` 的旧键 = `type_scriptId`，按脚本内**唯一**匹配的声明映射到新身份；歧义丢弃。`started_scripts` / `completed_scripts` → `PLAYBACK_STARTED` / `PLAYBACK_FINISHED`（`result = NONE`，`tick = 0`，`instance = ""`）。

### 3.2 触发器身份（H1-2）

```java
// script/TriggerDefinition.java
public static final String ID_REGEX = "[A-Za-z0-9_]{1,64}";
// 构造器追加 (String id, int declarationIndex)；id 可 null
public String getId();                 // 显式 id，可 null
public int getDeclarationIndex();
public String getIdentity();           // id != null ? id : type + "#" + declarationIndex

// ScriptParser.parseTriggerDefinition(JsonObject obj, String p, int index)
//   id = optString(obj, "id", null)；非字符串 → ScriptParseException
// ScriptManager.registerAllTriggers:  triggerId = td.getIdentity()

// ScriptValidator：id 可选；存在时必须匹配 ID_REGEX；脚本内重复 → issue
// SchemaExporter：root.triggers.triggerFields.id = string(optional)（additive）
```

### 3.3 连续语义（H1-3 纯核 / H1-6 接入）

```java
// continuity/MotionSample.java
public record MotionSample(double x, double y, double z, float yaw, float pitch, int tick) {}

// continuity/SegmentSweep.java（纯函数，无 MC 依赖）
public static boolean hitsBox(MotionSample a, MotionSample b, JsonObject corner1, JsonObject corner2);
public static boolean hitsSphere(MotionSample a, MotionSample b, JsonObject position, double radius);
public static boolean hitsFacing(MotionSample a, MotionSample b,
                                 float yaw1, float pitch1, float yaw2, float pitch2);
public static int substeps(double distance, int max);           // 结构扫掠的子步数
public interface Visitor { boolean test(double x, double y, double z); }  // true = 命中，停止
public static boolean anyPoint(MotionSample a, MotionSample b, int steps, Visitor visitor);
// 依次访问 steps + 1 个等距点（含两端）；任一点 test 返回 true 即返回 true
// 常量：SUBSTEP_MAX = 16；SUBSTEP_BLOCKS = 1.0（每格一个子步，封顶 16）

// continuity/TriggerEvalContext.java
public record TriggerEvalContext(int tick, boolean sweep, MotionSample from, MotionSample to, int windowTicks) {
    public static TriggerEvalContext disabled(int tick);       // sweep=false, from=null, window=0
    public boolean canSweep();                                  // sweep && from != null && to != null
}

// continuity/TriggerEdge.java（纯状态机，探针可直调）
public record Decision(boolean fire, boolean entered, boolean left) {}
public final class TriggerEdge {
    public Decision observe(boolean inOriginal, boolean inExitRegion, boolean continuous,
                            int tick, int dwellTicks, boolean onEnter);
    public MotionSample lastSample(); public int generation();
    public boolean hasSample(); public void setSample(MotionSample sample, int generation);
    public void reset();
}

// continuity/PlayerMotion.java（MC 依赖）
public static final PlayerMotion INSTANCE;
public void tick(MinecraftServer server);       // 每 tick：位移 >16 格 / 载具变化 → generation++
public int generation(UUID player);
public MotionSample capture(ServerPlayer player, int tick);
public void reset(UUID player);                 // 维度切换 / 退出
public void clearAll();
public static final double MAX_TICK_DISPLACEMENT = 16.0;

// script/ContinuitySpec.java（H1-6 建；H1-7 加 window）
public record ContinuitySpec(boolean sweep, int dwellTicks, int windowTicks) {
    public static final ContinuitySpec DEFAULT = new ContinuitySpec(false, 0, 0);
    public static final int MAX_TICKS = 1200;
    public boolean isDefault();
}
```

**触发条件 JSON（触发器级，与 `poll_interval` 同层）**：

```json
"continuity": { "sweep": true, "dwell": 20, "window": 100 }
```

- 全部可省；缺省 = `DEFAULT`（零回归）。
- `sweep`：只对 `location` / `structure` / `facing` 生效，其余类型给了 → 校验 warning「无几何语义，忽略」。
- `dwell`：`0..1200` tick；只对轮询类生效，事件类给了 → warning。
- `window`：`0..1200` tick；只对 `all_of` / `any` 生效（事件类子条件的窗口），其余类型给了 → warning。
- 解析期非法（非整数 / 负数 / 超上限）→ `ScriptParseException`；校验期同一套范围再报。

**扫掠语义**：
- 位置：线段与 AABB / 球的交（含端点，闭区间）；`location` 的 `dimension` 与条件结构按现状（`corner1+corner2` 或 `position+radius`）。
- `structure`：沿段按 `substeps` 取样点查结构（每子步一次 `getAllStructuresAt`）。
- `facing`：yaw 最短弧与目标弧求交 + pitch 区间重叠（与 `GeometryConditions.facingInRange` 同语义；`span == 0` = 整圈）。
- 端点判定先做：点条件为真直接命中（与原路径一致）。
- 静止（段长 0 / 无转向）退化为点判定，零额外求值。

**边沿 / 停留**：
- `TriggerEdge.observe` 每次采样更新：`inExitRegion == false` → `left`（若之前在区域内）+ `armed = true` + `trueSince = -1`；`inOriginal == true` → `entered`（若之前不在）+ 若 `trueSince < 0 || !continuous` 则 `trueSince = tick`。
- `fire = inOriginal && (dwell == 0 || tick - trueSince >= dwell) && (!onEnter || armed)`；`fire` 后 `armed = false`。
- 非 `on_enter` 触发器 `armed` 不参与判定；区域事实只在 `on_enter` 时写。

### 3.4 `window` 事件子条件（H1-7）

```java
// fact/FactWindow.java（纯函数：吃 PlayerFacts + 条件 JSON）
public static boolean evaluate(PlayerFacts facts, String typeId, JsonObject conditions, int fromTick, int toTick);
```

映射（事件类型 → 事实类型）：

| 触发器类型 | 事实 | 匹配 |
|---|---|---|
| `advancement` | `ADVANCEMENT` | `object` matchesId |
| `entity_kill` | `ENTITY_KILL` | `object` + scene；`mode=and` = 每个目标在窗口内都有击杀 |
| `death` | `DEATH` | scene |
| `dimension_change` | `DIMENSION_CHANGE` | 当前维度匹配 +（可选）`from_dimension` 匹配 `object` |
| `block_interact` / `entity_interact` | `INTERACT_BLOCK` / `INTERACT_ENTITY` | `object` 目标 |
| `item_on_interact` | `INTERACT_*` | `object` 目标 + `detail` 物品 + 可选 `target_type` |
| `item_craft` / `item_use` / `item_consume` / `item_release` / `item_instant_use` / `item_use_interrupt` | 对应 `ITEM_*` | `object` |
| `item_pickup` / `item_drop` | `ITEM_PICKUP` / `ITEM_DROP` | `object`；数组 `and` = 窗口内每个物品都捡到过 |
| `login` | `LOGIN` | 窗口内存在即可 |

- 无映射 / 未知 → `false`（并由校验层拒绝，正常路径到不了）。
- 场景匹配复用 `GeometryConditions.matchesScene`（H1-5 从 `Evaluators` 提取）。

---

## 四、验收命令

| 项 | 命令 / 期望 |
|---|---|
| 编译 | `sh gradlew :common:compileJava --offline --no-daemon` → BUILD SUCCESSFUL |
| 平台编译（H1-5 删包后） | `sh gradlew :fabric:compileJava :forge:compileJava --offline --no-daemon` |
| 旧解析基线 | `sh E:/tmp/i1/run-parse.sh "<repo>/cinematics"` → 与 `E:/tmp/i1/baseline-parse.txt` 按文件路径 diff 0（新增夹具除外） |
| 旧校验基线 | `sh E:/tmp/icv/run.sh "<repo>/cinematics"` → 与 `E:/tmp/i1/baseline-icv.txt` 对比，旧 231 支 diff 0 |
| 本轮探针 | `E:/tmp/h1/run-fact.sh`、`run-identity.sh`、`run-continuity.sh`、`run-prereq.sh`、`run-tracker.sh`、`run-edge.sh`、`run-window.sh`、`run-perf.sh` |
| 实机 | `tools/devtools/.venv/Scripts/python.exe tools/devtools/devtools.py client "<世界>"` + `ic` 命令；日志 `ic tail` |

---

## 五、现状与缺口分类

### 本轮做

| 缺口 | 归属 |
|---|---|
| 事实类型 / 容器 / 四类查询 / 持久性分级 / 保留策略 | H1-1 |
| 触发器身份（`id` 已存在于 232 条声明，解析层忽略） | H1-2 |
| 段-方体 / 段-球 / 朝向弧 / 子步走段 | H1-3 |
| 播放起止事实（带实例与退出原因）、命中事实、三前置条件、删除布尔存储 | H1-4 |
| 八个 tracker → 事实；投票 → 事实 + 实例索引；客户端镜像删除；`ScriptPlayback.server` NPE | H1-5 |
| 采样快照 / 代次 / 边沿状态 / 扫掠接线 / `dwell` / 区域进入离开事实 | H1-6 |
| `window` + 事件类子条件解禁 | H1-7 |

### 移交后续轮次

| 事项 | 归属 | 关闭条件 |
|---|---|---|
| `observation` 扫掠 | H-2（H8 步骤 3 剩余；编号以任务书为准） | 成本实测（子步数 × 射线查询）完成并给出纳入/不纳入结论；纳入时实现弧带 + 子步重放 |
| `docs/TRIGGER_TYPES.md` / `docs/SCRIPT_FORMAT.md` 的 `continuity` / 触发器 `id` 口径回写 | I-5（I2-B10） | 文档与源码一致，`grep` 无旧口径残留 |
| 事实的展示面（为什么触发 / 触发过什么） | Z 轮 + H-12 | 编辑器 / 客户端有读取事实查询 API 的入口 |
| 前置条件按退出结果筛选（含不含跳过） | C-trigger | `requires` 数据字段形态定稿并落地 |
| 播放期间扫过是否补记（N9） | 用户裁决 → C-trigger / H 后续 | 裁决写入 `trigger-continuity.md` 并实现 |
| 编辑器连续语义开关 | Z 轮 | 编辑器触发器表单含 `continuity` 字段 |
| 传送的显式钩子（短距离 < 16 格） | H-2 | 需要跨平台服务端传送事件或 mixin；当前以阈值兜底 |

### 契约边界（写入专篇，不实现）

- 事实不跨实例；`PLAYBACK_*` 的 `instance` 为空串表示退化实例。
- 事实层的 `count` 是累计口径；窗口查询受保留深度约束（`dwell` / `window` ≤ 1200 tick，保留深度 ≥ 512 条同类型事实）。
- 扫掠是相邻采样点的直线 / 最短弧近似，不是真连续时间；曲线绕行会误报或漏检。
- 短距离传送（每 tick ≤ 16 格）按移动处理（保守近似）；≥ 16 格按跳变处理，不参与扫掠。
- `sweep` 只对 `location` / `structure` / `facing` 生效；其余类型静默忽略（校验 warning）。
- 顶层事件触发器不受 `window` 影响；`window` 只作用于组合器里的**事件类子条件**。
- `TriggerEdge` 的 `trueSince` 以采样点真值为准（不是段内每一刻），`dwell` 因此是保守近似。
- 客户端不再接收触发状态镜像；服务端事实不自动下发。

### 本轮明确不做

- 不改条件字段形态与组合器结构（C-trigger）。
- 不做真·连续时间；不做客户端判定；不做通用事件总线；不做全量审计日志。
- 不改 EVENT 命令协议、规则引擎、路径集合、片段身份（I-4 范围）。
- 不做编辑器 UI；不改 `docs/`。

---

## 六、验收记录（2026-11-03）

### 6.1 编译

| 命令 | 结果 |
|---|---|
| `sh gradlew :common:compileJava --offline --no-daemon` | BUILD SUCCESSFUL |
| `sh gradlew :fabric:compileJava :forge:compileJava --offline --no-daemon` | BUILD SUCCESSFUL（9 tasks） |

### 6.2 本轮探针（`E:/tmp/h1/`，仓库外）

| 命令 | 结果 | checks |
|---|---|---|
| `run-fact.sh` | PASS | 100 |
| `run-identity.sh` | PASS | 32 |
| `run-continuity.sh` | PASS | 59 |
| `run-prereq.sh` | PASS | 50 |
| `run-tracker.sh` | PASS | 120 |
| `run-edge.sh` | PASS | 71 |
| `run-window.sh` | PASS | 111 |

合计 543 checks。另有 `run-semantics.sh`（点判定一致性随机比对 1,580,000 次 mismatches=0）与 `run-facing.sh`（360,000 次 mismatches=0）。

### 6.3 旧基线

| 命令 | 结果 |
|---|---|
| `sh E:/tmp/i1/run-parse.sh "<repo>/cinematics"` | `parsed=238, failed=6, total=244`；与 `baseline-parse.txt` 按文件路径逐条对比：旧 225 条 **missing 0 / changed 0**；新增 13 条为本轮夹具 |
| `sh E:/tmp/icv/run.sh "<repo>/cinematics"` | `18 of 244 scripts have issues`；与改动前 `18 of 241` 相比仅多 3 个新增夹具 OK 行与计数行，问题脚本集合不变；`SELFTEST` 全过 |

### 6.4 实机（Fabric 1.20.1 dev 客户端，世界「新的世界」，`quickPlaySingleplayer`）

夹具：`cinematics/0.3.6/tests/trigger/test_h1_sweep_off.json` / `test_h1_sweep_on.json` / `test_h1_dwell.json`（同一 box `(100,-64,100)-(102,320,102)`；dwell box `(150..152)`）。

| 观测点 | 证据 |
|---|---|
| **穿越用例（对照）** | 先 `/tp @s 0 -60 0`，`icinematics reload`，再 `/tp @s 98.5 -60 101.5`（区域外）→ 3s → `/tp @s 103.5 -60 101.5`（一帧跨过区域，两端都在区域外）。日志只有 `Firing trigger 'trg_sweep_on_b'`，**没有** `trg_sweep_off_b` → 开启 `sweep` 必触发、未开启漏检 |
| **dwell 门槛** | `/tp` 进入 dwell box 后 1.2s 内无触发；约 3.4s 后 `Firing trigger 'trg_dwell_b'`（`dwell=40` tick = 2s，轮询 10 tick） |
| **持久化** | 退出后 `fabric/run/saves/新的世界/immersive_cinematics/trigger_state/<uuid>.snbt` 为 `{facts:[...],version:2}`，含 `playback_started` ×8 / `playback_finished` ×8（`result:"finished"`）/ `trigger_fired` ×8；会话事实（事件 / 区域 / 投票）未落盘 |

说明：首轮实机尝试在 `y=100` 传送，玩家自由落体导致第二次传送的 y 位移 > 16 格被判定为跳变（不扫掠）——这正好验证了不连续标记生效；改为地面高度 `y=-60` 后通过。

### 6.5 收编 / 缺陷核对

| 检查 | 结果 |
|---|---|
| `grep -rn "TriggerStateStore\|PlayerTriggerState" common fabric forge --include=*.java` | 0 |
| `grep -rn "enterStates\|checkEnterState" common/src/main/java` | 0 |
| `grep -rn "skipVoters\|ClientTriggerStateCache\|S2CTriggerStateSyncPacket\|TRIGGER_STATE_SYNC" ...` | 0 |
| `grep -rn "KillTracker\|DeathTracker\|AdvancementTracker\|DimensionTracker\|InteractTracker\|CraftTracker\|PickupDropTracker\|UseItemTracker" ...` | 0 |
| `ScriptPlayback.server` | 构造器参数 + `final` 字段赋值（`addViewer` 传 `player.server`）；`broadcastSkipVote` 读已赋值字段 |

### 6.6 未覆盖 / 边界

- 跳过票未达标的 NPE 路径**未实机复现**（单客户端无法构造「有剩余观看者且票数未达标」）；修复为代码级（构造器赋值）验证。
- `structure` 扫掠的每 tick 世界查询成本未实机测量（只测了走段本身；已标注）。
- `observation` 扫掠未做（H-2，H1-R1）。
- Forge 实机未跑；Forge 仅编译验证。
- 像素级回归未做（本轮改动不涉及渲染路径）。

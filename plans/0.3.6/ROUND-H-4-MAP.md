# H-4 轮项目地图与共享契约（主代理）

> 建立时间：2026-11-04。依据：本轮任务书（H-4 对外接口：H12 + TrackType 注册表化）+ `plans/0.3.6/H-other/public-api.md` + `hud-layer-api.md` §一/§六/§八 + `state-tracking.md` §4.1/§六 + 源码逐条核对（三份只读调查，行号以类名/方法签名为准）。
> 前置：H-1 / H-2 / H-3 已完成（`ROUND-H-1-MAP.md`、`ROUND-H-2-MAP.md`、`ROUND-H-3-MAP.md`），本轮不重开 `I2-B` 系列与 `I4-R1`。
> `docs/` 已过时，只作线索；冲突以设计文档与源码为准。

---

## 〇、本轮实现口径（开工前定，写清后不再改）

| # | 项 | 口径 | 依据 |
|---|---|---|---|
| 0.1 | `TrackType` enum 处置 | **整体删除** `script/TrackType.java`，全部替换为 `spi/TrackTypeId`（`record TrackTypeId(String id)`）。不保留兼容别名、不留 deprecated shim | 设计 §6.4「A 方案：全量」+ 任务书「内置轨道 id 沿用旧字符串」 |
| 0.2 | 内置 id | `CAMERA` / `LETTERBOX` / `AUDIO` / `EVENT` / `MOD_EVENT` / `OVERLAY` / `ADJUST`（严格等于旧枚举 `name()`），以 `TrackTypeId` 静态常量提供，受注册表保护 | 任务书「老脚本零回归」 |
| 0.3 | 脚本侧大小写 | 解析先精确匹配；未命中时把输入 `toUpperCase(Locale.ROOT)` 再匹配**内置 id**。第三方 id 只做精确匹配（区分大小写） | 现 `ScriptParser.parseTrackType` 用 `valueOf(value.toUpperCase())`；逐位兼容 |
| 0.4 | `spi` 位置 | `common/src/main/java/com/immersivecinematics/immersive_cinematics/spi/`；`api` 同层 | 设计 §3 分层表 |
| 0.5 | `PropertyType` / `Interp` / `PropertyMode` / `ObjectDef` | **连同 `ObjectDef` 一起迁到 `spi`**（删除 `script/object/{PropertyType,Interp,PropertyMode}.java` 与 `script/schema/ObjectDef.java`）。理由：`spi.TrackSchema` 要交 `ObjectDef` 表；不搞镜像 record | 任务书「`PropertyType`/`Interp`/`PropertyMode` 迁到 `spi`」+ 设计 §3「声明 schema 所必需」；`ObjectDef` 是 schema 声明的载体，镜像会产出第二套类型 |
| 0.6 | `TrackContext.clip()` | 按设计原文返回 `script.Clip`（只读用法），**不**改成 `TrackClip` 视图。`Clip` 因此进入对外可达面：文档标注「只保证片段级只读字段」，其采样成员不承诺 | 任务书明文；设计 §6.1 |
| 0.7 | 采样设施 | `ClipSampler` / `SampledClip` / `ObjectSlotLayout` 不出现在 `spi`/`api` 任何签名里。`TrackContext` 的路径→槽位由注册期编译（`TrackRegistry` 为每个 id 缓存 `Map<String,Integer>`） | 设计 §3「不暴露采样设施」+ §6.1 注释 |
| 0.8 | 内置轨道特化语义 | 注册表化只把「枚举封闭集合」打开：`Clip.getLut()`、`ObjectValidator` 的内置检查、`ScriptParser` 的 `OVERLAY`/`CAMERA` 归属规则等**保留为按 id 相等判断**（第三方 id 一律走兜底分支，不参与这些特化）。**不**把内置语义改造成可注册点（那是过度设计，且会改变内置 7 轨行为） | 设计 §6.4「查表 + 兜底分支」；本轮验收「内置 7 轨零回归」 |
| 0.9 | `switch` 覆盖范围 | 任务书写的「10 处 switch」与源码不符：**真正以 `TrackType` 为表达式的 switch 只有 2 处**（`TrackPlayer.create`、`ObjectValidator.Check.run`）；另 8 处切在 `String` / `PropertyType` / `PropertyMode` / `int` 上。本轮覆盖口径 = **全部按 `TrackTypeId` 分叉的点**（2 处 switch + 13 处 if/三元/filter，见 §二.6），并在 `public-api.md` 更正数字（见 §五） | 三份调查实测 |
| 0.10 | 注册时机 | 冻结入口 = `ScriptParser.parse(String)` 首行（所有 JSON→脚本路径的唯一入口）。冻结后 `TrackRegistry` / `ImmersiveCinematicsApi` 的一切注册调用抛 `IllegalStateException`，消息含「注册必须在模组初始化期完成」 | 设计 §9.1；`ScriptParser.parse` 为唯一公开入口（`ScriptParser.java:125`） |
| 0.11 | 内置注册时机 | `TrackRegistry.ensureBuiltins()` 幂等；由 `ImmersiveCinematics.init` 显式调用，且每个公开读方法入口也调用一次（防类加载顺序导致 `ColorAdjustSampler` 静态字段先于内置注册）。第三方注册前同样先 `ensureBuiltins()`，保证内置 id 永远排在前 | 现存 `ColorAdjustSampler:63-66` 等 `static final` 在类加载期取槽位 |
| 0.12 | id 冲突 | 内置 id 不可被第三方占用；第三方之间重复 → 后注册者失败并抛 `IllegalArgumentException`；**不静默覆盖** | 设计 §9.4 |
| 0.13 | 第三方 schema 校验 | 复用 `ObjectSchemaValidator`（单条 entry），并额外要求：非空 objects、至少一个 `FLOAT`/`INT` 且 `mode` ∈ {`VALUE`,`ANIMATED`} 的对象。不满足 → 注册期 `IllegalArgumentException`，消息指出「至少一个数值对象」 | 设计 §6.2 + A2 |
| 0.14 | 失败隔离 | 第三方 `tick` 抛异常 → 该轨道本帧跳过 + 下一帧继续尝试；日志走既有 `util/ErrorLog.logRateLimited(category,key,intervalMs,msg,t)`（已存在，勿新建）。触发器求值异常已有 try/catch 但**无限频**，本轮补限频 | 设计 §9.3；`ErrorLog.logRateLimited` 实测存在 |
| 0.15 | `PlaybackListener` | 新增注册入口（`PlaybackApi.addListener/removeListener`）。`onStarted` 触发点 = `PlaybackLifecycle.startScriptInternal` 成功启动后；`onFinished` 触发点 = `PlaybackLedger.reportScriptFinished`。**编辑器实例（`isEditorDriven()`）不触发**，与 `reportScriptFinished` 现有口径一致 | 设计 §4.3 只给接口未给注册入口；`PlaybackLedger` 现有 editor 跳过 |
| 0.16 | `ScriptInfo` 字段 | 设计只列类名未定义字段，本轮定为 `record ScriptInfo(String id, String name, int version, double duration, boolean infinite, List<TrackTypeId> trackTypes)`；`PlaybackApi.scripts()` 返回本机可见脚本。**回写 `public-api.md`** | `CinematicScript` 现有 `getId/getName/getVersion/getTotalDuration/isInfinite` |
| 0.17 | `CameraApi` 扩展 | `CameraLaneComposite getLaneComposite()`（返回既有 `script.CameraLaneComposite` 纯不可变 record，不再造第二套类型）+ `String getActiveBaseProvider()`。后者需给 `api/BaseCameraProvider` 加 `default String providerId()`，`CameraCoverChain` 加 `activeProviderId()` | 设计 §5.2；`CameraCoverChain` 现只有 `winnerPriority()`，无正主对象 getter |
| 0.18 | `OverlayApi` 归属 | `openLayer(Object owner, ...)`：`owner` 必须传播放实例 token（第三方轨道取 `TrackContext.owner()`），这样实例停止时 `OverlayManager.removeLayersOf(instance)` 能连带释放。**不**提供 FBO / 合成上下文 / 渲染目标 | 设计 §6.3、§8；`PlaybackInstance.stop` 已 `removeLayersOf(this)` |
| 0.19 | 事实对外读面 | `api/ServerFactsApi` **只读转发** `FactLedger.of(player)` 的 `PlayerFacts` 查询原语，不新建表、不缓存、不写事实。返回既有 `trigger.server.fact.Fact` 只读 record | 任务书「复用 `FactLedger`，不重建第二套记录」；`PlayerFacts` 已有 `exists/count/last/lastMatching/anyWithin/lastWithin/anyObject/retained` |
| 0.20 | HUD 层一档 | `ImmersiveCinematicsApi.registerHudLayer(...)` 直接转调既有 `api/HudLayerRegistry.register(...)`；**不另建第二个注册表** | 任务书承接项；`HudLayerRegistry` 已是唯一注册表 |
| 0.21 | 模板 | 不纳入对外门面；`ScriptTemplate.build()` 仍只遍历**内置 7 id**（骨架输出逐字不变） | 设计 §11「不开放模板注册」 |
| 0.22 | 探针位置 | 仓库外 `E:/tmp/h4/`；临时脚本 / 夹具 / 日志不入库，测完还原 | 本轮约定 |

---

## 一、任务清单

| 编号 | 目标（一句话） | 验收 | 依赖 | 切片 |
|---|---|---|---|---|
| **H4-1** | 值类型迁移：`PropertyType` / `Interp` / `PropertyMode` / `ObjectDef` → `spi` 包，删除原类，改全部引用 | `sh gradlew :common:compileJava --offline --no-daemon` 过；`grep -rn "script.object.PropertyType\|script.object.Interp\|script.object.PropertyMode\|script.schema.ObjectDef"` = 0；`grep -rln "\bPropertyType\b"` 的文件全部有正确 import | — | 切片 1 |
| **H4-2** | `TrackTypeId` + `TrackRegistry`（内置注册 / schema 合并 / 冻结 / id 保护）+ `spi/TrackSchema` + `spi/TrackPlayer` + `spi/TrackContext` + 第三方适配器 + 删除 `TrackType` enum + 43 文件替换 + 2 处 switch + 3 张静态表 | 编译过；`grep -rn "\bTrackType\b"` 只剩 `TrackTypeId`/`TrackTypeSchema` 子串；探针 `TrackRegistryProbe`、`BuiltinSchemaProbe` 全 PASS；内置 7 轨 schema 与 id 逐条一致 | H4-1 | 切片 2 |
| **H4-3** | `api/ImmersiveCinematicsApi` 统一注册门面（轨道 / 触发器 / 插值模型 / 路径曲线 / 前置条件 / Base / Modifier / HUD 层）+ 注册时机冻结接线 + 触发器求值限频 | 编译过；探针 `FacadeProbe` PASS（id 冲突 / 晚注册 / 未知类型各一例）；`ScriptParser.parse` 之后注册抛 `IllegalStateException` | H4-2 | 切片 3 |
| **H4-4** | `api/OverlayApi`（开层 / 更新 / 关层 / 归属释放，无 FBO） | 编译过；探针 `OverlayApiProbe` PASS（z 序、owner 释放、重复关层幂等） | — | 切片 3 |
| **H4-5a** | `api/PlaybackApi` / `ServerPlaybackApi` / `PlaybackListener` / `ScriptInfo` + 客户端脚本缓存接线 + 监听器两个触发点 | 编译过；探针 `PlaybackApiProbe` PASS；实机 `onFinished` 三分支可区分 | H4-2（仅 `TrackTypeId` 类型） | 切片 3 |
| **H4-5b** | `CameraApi` 扩展：`getLaneComposite()` + `getActiveBaseProvider()`（`BaseCameraProvider.providerId()` + `CameraCoverChain.activeProviderId()` + `ScriptPlayer` lane 访问器） | 编译过；探针 `CameraApiProbe` PASS（六参数同帧一致） | H4-2（要改 `ScriptPlayer`） | 切片 3b |
| **H4-6** | `api/ServerFactsApi`（事实对外读面，复用 `FactLedger`） | 编译过；探针 `FactsApiProbe` PASS（存在性 / 计数 / 最近一次 / 时间窗 / 结果过滤） | H4-2（`Fact` 未改） | 切片 3 |
| **H4-7** | 探针套件 + 第三方最小轨道实机 + 内置 7 轨零回归 | 全部探针 PASS；实机：第三方轨道 `tick` 读到脚本值、抛异常不中断、内置 7 轨脚本逐帧无差异 | H4-1..H4-6 | 切片 4 |
| **H4-8** | 文档回写（`public-api.md` §2/§6.4/§四/§五/§八/§十/§十一 + 数字更正）+ 移交落盘 + 提交 | 专篇状态改「已实现」；`I-BACKLOG.md` 新增 H-4 小节；`git commit`（不 push） | 全部 | 收尾 |

### 依赖图

```
H4-1 ──→ H4-2 ──┬──→ H4-3
                ├──→ H4-5
                ├──→ H4-6
                └──→ H4-7 ←── H4-4（无依赖，可与 H4-3/5/6 并行）
H4-3 / H4-4 / H4-5 / H4-6 ──→ H4-7 ──→ H4-8
```

### 派单波次（文件所有权隔离）

| 波次 | 任务 | 文件所有权 |
|---|---|---|
| 1 | H4-1 | `spi/{PropertyType,Interp,PropertyMode,ObjectDef}.java`（新建）、删除 `script/object/{PropertyType,Interp,PropertyMode}.java`、删除 `script/schema/ObjectDef.java`、全部引用点 import 修正（`script/object/` 内 9 个无 import 文件 + `script/{Clip,ScriptParser,ColorAdjustSampler}` + `script/schema/{ObjectSchemaValidator,TrackSchemas,MathModelRegistry?}` 等，以 `grep` 结果为准） |
| 2 | H4-2 | `spi/{TrackTypeId,TrackSchema,TrackPlayer,TrackContext}.java`（新建）、`script/track/{TrackRegistry,BuiltinTracks,TrackContextImpl,ExternalTrackDriver,BuiltinTrackDrivers}.java`（新建）、删除 `script/TrackType.java`、`script/{Clip,Timeline,TimelineTrack,TrackPlayer,ScriptPlayer,ScriptParser,ScriptValidator,SchemaLoader,ScriptTemplate,ColorAdjustSampler,AdjustTrackPlayer,AudioTrackPlayer,CameraTrackPlayer,LetterboxTrackPlayer,ModEventTrackPlayer,OverlayClipRuntime,OverlayTrackPlayer,PlayerMoveController,Keyframe}.java`、`script/{object,schema,event,template}/*`、`camera/{PlaybackInstance,rule/RuleActions,rule/RuleEngine,source/*}`、`trigger/server/ScriptEventManager.java` |
| 3 | H4-4、H4-6、H4-5a **并行**（H4-2 进行中，三者文件集与 H4-2 不重叠） | H4-4：新建 `api/OverlayApi.java`（已完成，`closeLayers` 只关本 API 的层、`Handle.close()` 额外摘管理器、render 异常隔离交给 `OverlayManager`）。<br>H4-6：新建 `api/ServerFactsApi.java`（static facade，11 个查询方法，已完成）。<br>H4-5a：新建 `api/{PlaybackApi,ServerPlaybackApi,ServerPlaybackApiImpl,PlaybackListener,ScriptInfo}.java`、`camera/PlaybackListeners.java`；改 `camera/{CameraManager,PlaybackLedger,PlaybackLifecycle}.java`、`trigger/client/{ClientScriptCache,ClientScriptReceiver}.java`。 |
| 4 | H4-7 | `E:/tmp/h4/**` 探针（不入库）+ `fabric/run/immersive_cinematics/scripts/` 临时夹具（测完删）<br>主代理统一跑编译 + 探针 |
| 5 | H4-8 | `plans/0.3.6/H-other/public-api.md`、`H-other/README.md`、`plans/0.3.6/README.md`、`plans/0.3.6/I-BACKLOG.md`、`docs/SCRIPT_FORMAT.md`（若需补第三方轨道段）、`CHANGELOG.md` |

> 波次 1–3 的子代理**不编译、不提交**（`H4-1` 与 `H4-2` 独占波次时也只做静态自检，由主代理统一跑模块级增量编译）。波次 3 四个任务并行，禁止任何人碰别人的文件。

---

## 二、现状与关键符号

路径前缀：`C/` = `common/src/main/java/com/immersivecinematics/immersive_cinematics/`，`F/` = `forge/src/main/java/com/immersivecinematics/immersive_cinematics/forge/`，`FB/` = `fabric/src/main/java/com/immersivecinematics/immersive_cinematics/fabric/`。

### 2.1 `TrackType` 引用面（本轮改造对象）

- `C/script/TrackType.java:8-36`：7 值 enum，无字段、无方法、无 `toString` 重写。
- **43 个文件**（40 个直接含 `TrackType` 标识符 + `camera/rule/RuleEngine.java`、`script/object/ClipSampler.java` 经 `Clip#getTrackType()` 间接 + `script/schema/TrackTypeSchema.java` 仅类型名）。
- `fabric/` 与 `forge/` **零命中** —— 注册表化不波及平台模块。
- 187 次出现 / 178 行；归类：`PARAM` 82、`COMPARE` 33、`ENUM_CONST` 32、`IMPORT` 20、`MAP_KEY` 10、`CONVERT` 1。

### 2.2 两处真正的 `switch`

| 位置 | 表达式 | 现行为 |
|---|---|---|
| `C/script/TrackPlayer.java:70-78` | 方法参数 `TrackType type` | 6 case 各 new 一个实现；`EVENT` 无 case → `default` 抛 `IllegalArgumentException` |
| `C/script/object/ObjectValidator.java:212-229` | 内部类 `Check.type` | `CAMERA`/`OVERLAY`/`AUDIO`/`ADJUST`/`EVENT`/`MOD_EVENT` 各有检查；`default` 吸收 `LETTERBOX` |

### 2.3 三张静态表

| 表 | 位置 | 结构 | 冻结要求 |
|---|---|---|---|
| `SchemaRegistry.TRACKS` | `C/script/schema/SchemaRegistry.java:13` | `static final Map<TrackType, TrackTypeSchema> = TrackSchemas.all()`；`static` 块跑 `ObjectSchemaValidator.validateOrThrow` | 改「内置 + 注册合并」，freeze 时统一校验；`get()` 未命中仍返回 `TrackTypeSchema.EMPTY`（调用点不判 null） |
| `ObjectSlotLayout.CACHE` | `C/script/object/ObjectSlotLayout.java:26,59` | `static final ConcurrentHashMap<TrackType, ObjectSlotLayout>`，懒填充、无失效 | 键换 `TrackTypeId`；freeze 后不再出现新 id |
| `TrackSchemas` 的 7 个 `xxxObjects()` | `C/script/schema/TrackSchemas.java:549/789/798/821/829/838/899` | `private static Map<String, ObjectDef>`，每次新建；`all()` 七次 `put` | 内置侧不变，只改 `Map` 键类型 |

### 2.4 字符串 ↔ id 落点（必须改）

| 位置 | 现状 | 改法 |
|---|---|---|
| `C/script/ScriptParser.java:1847-1853` `parseTrackType` | `TrackType.valueOf(value.toUpperCase())`；错误文案硬编码 7 个小写名 | `TrackRegistry.resolve(value)`；文案由注册表 id 列表生成 |
| `C/script/ScriptParser.java:410,689` | 两处调用 `parseTrackType` | 不变（只改内部实现） |
| `C/camera/rule/RuleActions.java:377-382` `trackType(String)` | `for (TrackType t : values()) if (name().equalsIgnoreCase)` | `TrackRegistry.resolveOrNull` |
| `C/script/ScriptValidator.java:2494-2499` `trackTypeOf` | 同上 | `TrackRegistry.resolveOrNull` |
| `C/script/ScriptValidator.java:59-60` `KNOWN_TYPES` + `:4363` `isKnownType` | 硬编码 7 字符串数组 | 改为 `TrackRegistry.isKnown(String)`（**否则第三方轨道会被校验器拒绝**） |
| `C/script/ScriptValidator.java:399` / `ScriptParser:1852` | 硬编码提示文案 | 由注册表 id 列表生成 |
| `C/script/schema/SchemaExporter.java:44,51,157,305` | `values()` + `type.name()` 作为 JSON 键 | `TrackRegistry.registered()` + `id()`；第三方轨道必须被导出（编辑器要能渲染） |
| `C/script/ScriptTemplate.java:50-53` | `values()` 生成 7 条空轨 | `TrackRegistry.builtinIds()`（模板骨架输出逐字不变） |
| `C/script/template/TemplateScriptAssembler.java:51,53` / `C/command/CinematicCommand.java:402,517` | `template.trackType().name()` | `.id()` |
| `C/webui/ScriptGraphService.java:194` | 字符串透传 | 不改 |

### 2.5 其它按 id 分叉的点（替代 `switch` 的 if/三元/filter）

`Clip:268,294,304,439,448,458`（`OVERLAY` / `AUDIO` / `ADJUST`）、`ObjectValidator:144-149`（视觉轨 / 非视觉轨）、`ScriptParser:440,441,452,471,474,519,724,726,734,842,861,1097,1815,1820,1827`、`PlaybackInstance:279`（`CAMERA`）、`PlayerMoveController:63`（`EVENT`）、`ScriptPlayer:233`（`EVENT` 跳过）、`ScriptEventManager:605`（`EVENT`）、`EventClipRuntime:63`（`MOD_EVENT`）、`Timeline:75,84,94,104,114`。

### 2.6 采样与执行（`TrackContext` 的支撑）

| 符号 | 位置 | 关键事实 |
|---|---|---|
| `ClipSampler` | `C/script/object/ClipSampler.java` | `ClipSampler(Clip)`(:41)、`newBuffer()`(:66)、`sample(double localTime, SampledClip)`(:79)、`slotOf(String)`(:126)、`isEmpty()`(:131)；**非线程安全、持有游标与 `lastTime`** |
| `SampledClip` | `C/script/object/SampledClip.java` | `f/i/b/s(int)`、`present(int)`(:74-95)、`clear()`(:62)、`layout()`(:69)；`executionIndex` 字段**从无生产写入** |
| `ClipRuntimeCache` | `C/script/object/ClipRuntimeCache.java` | `ClipRuntimeCache(TrackType)`(:33) → 键换 id；`sample(Clip,double)`(:54)、`values(Clip)`(:80)、`clear()`(:91)；同片段返回**同一缓冲** |
| `ClipValueView` | `C/script/object/ClipValueView.java` | `f/i/b/s/struct(String,...)`(:43-107) 只读 `VALUE` 模式；`STRUCT` 只能从这里取 |
| `ClipExecution` | `C/script/execution/ClipExecution.java:20-52,217-284` | `executionIndex()`(0 起)、`localTime()`、`executionId()`、`direction`；`applyHead`/`applyInfinite` 是唯一口径真源 |
| `ClipExecutionLedger` | `C/script/execution/ClipExecutionLedger.java:96,113,122` | `executionOf(Clip)`、`isActive(Clip,head)`、`localTime(Clip,head)`；`advance` 只前进、`reset` 供 seek/折返 |
| `ScriptPlayer` | `C/script/ScriptPlayer.java` | `clipsForTrack(int)`(:170)、`executions()`(:855 public)、`executionLocalTime(Clip,double)`(:875)、`owner()`(:92 → Object，值 = `PlaybackInstance`)、`isClipActive`(:870)、`onRenderFrame(double)`(:523) |
| `PlaybackInstance` | `C/camera/PlaybackInstance.java` | `scriptId()`(:166)、`instanceId()`(:171)、`isEditorDriven()`、`stop(CompletionReason)`(:225，含 `OverlayManager.removeLayersOf(this)`) |
| 内部 `TrackPlayer` | `C/script/TrackPlayer.java` | `isActiveAt/onRenderFrame/onStop/onSeek/onLoopRestart/onScriptReplaced` + `static create(...)`。**与新增的 `spi/TrackPlayer` 同名不同包** → 本轮把内部接口改名 `script/track/TrackDriver`（内部层无稳定性承诺，避免第三方读到两个 `TrackPlayer`） |

### 2.7 播放链路

| 符号 | 位置 | 签名 / 事实 |
|---|---|---|
| `CameraManager.INSTANCE` | `C/camera/CameraManager.java:29` | `implements CameraApi`；`playCinematic(CinematicScript,String) : int`(:88)、`stopScriptById(String,String) : boolean`(:101)、`isScriptMode()`(:63)、`instances()`(:58)、`activeInstance()`(:56)、`getCameraState()`(:161)、`setScriptState(...)`(:148，**不对第三方开放**) |
| `PlaybackLifecycle` | `C/camera/PlaybackLifecycle.java:36` | 包内可见；`playScript(CinematicScript,String) : int`(:90)、`startScriptInternal(CinematicScript,String)`(:358)、`startScriptInternal(...,boolean editorDriven)`(:369)、`deactivateNow(PlaybackInstance)`(:280)、`requestExit(instance, ExitReason)`(:183) |
| `CompletionReason` | `C/control/CompletionReason.java:7` | `FORCE_QUIT` / `STOPPED` / `INTERRUPTED` / `SKIPPED` / `FINISHED` |
| `PlaybackLedger.reportScriptFinished` | `C/camera/PlaybackLedger.java:88-97` | 仅非编辑器实例上报；`ClientScriptNotifier.notifyScriptFinished(scriptId,instanceId,reason)` — 现成的「结束」单点 |
| `ScriptManager` | `C/script/ScriptManager.java` | `INSTANCE`；`getScript(String)`(:164)、`getAllScripts()`(:168)、`isLoaded()`(:172)、`loadAll(server)`(:44)、`reload(server)`(:151) |
| 播放入口 | `C/trigger/client/ClientScriptReceiver.java:63-84` | 收包 → `Minecraft.execute` → `ScriptParser.parse` → `CameraManager.playCinematic`；**不写任何 id→脚本缓存** |
| `ClientScriptCache` | `C/trigger/client/ClientScriptCache.java:16-41` | `putScript/getScript/putAll/clear/size`；**全工程零调用方** — 本轮接上（`handlePlayScript` / `handleScriptReload` 写入） |
| `S2CPlayScriptPacket` | `C/trigger/network/S2CPlayScriptPacket.java` | `send(ServerPlayer,String json[,String refId])`；包体 gzip + 完整 JSON；`getInstanceId()` |
| `S2CStopScriptPacket` | `C/trigger/network/S2CStopScriptPacket.java:49` | `send(ServerPlayer,String scriptId,String instanceId,String refId)` |
| `StartPlaybackAction.execute` | `C/trigger/server/action/StartPlaybackAction.java:41-83` | 服务端播放的完整样板：`ScriptManager.getScript` → `ScriptStructureResolver.resolveTargets` → `primeCommandTable` → `createGrant` → `S2CPlayScriptPacket.send`。**`ServerPlaybackApi.playFor` 必须复用这条链** |

### 2.8 注册点现状（门面要转调的）

| 注册表 | 位置 | 现有签名 | 冲突策略 |
|---|---|---|---|
| 相机 Base / Modifier | `C/camera/cover/CameraCoverChain.java:43,53` | `register(BaseCameraProvider)` / `register(CameraModifier)`；优先级域在 `C/api/BaseCameraProvider.java:16-25`（`PRIORITY_EXTERNAL=400` 等）；**无正主对象 getter** | 无 id，不涉及 |
| 触发器 | `C/trigger/server/TriggerRegistry.java:13-31` | `register(TriggerType)`（`HashMap`，重复 id **静默覆盖**）、`get`、`getAll`、`clear` | 本轮加「重复 id 抛 `IllegalArgumentException`」——需确认内置无重名（`ImmersiveCinematics:21-58` 26 个内置类型） |
| 前置条件 | `C/trigger/server/prereq/PrerequisiteRegistry.java:17-41` | `register(String, TriggerPrerequisite)` → `ConcurrentHashMap.put`（静默覆盖） | 同上 |
| 插值模型 | `C/script/schema/MathModelRegistry.java:127` | `register(String, Map<String,FieldDef>, Function<ModelParams,MathModel>)` | `static` 块内置注册（`:104-120`） |
| 路径曲线 | `C/script/schema/PathCurveRegistry.java:229` | `register(String, Set<PathCapability>, List<PathParamDef>, Function<PathParams,PathSpec>)` | **已抛 `IllegalArgumentException`**（`:239`）；内置 11 条 |
| HUD 层 | `C/api/HudLayerRegistry.java:54,66,80` | `install(Platform)` / `register(ResourceLocation,String)` / `categoryOf` | 后注册覆盖；无异常路径 |
| 模板 | `C/script/template/TemplateRegistry.java` | `register(ClipTemplate)` | **不纳入对外** |

### 2.9 初始化入口

| 平台 | 入口 | 顺序 |
|---|---|---|
| common | `C/ImmersiveCinematics.java:13-19` `init(ConfigProvider)` | `Config.init` → `NetworkHandler.init` → `registerTriggerTypes()`(26 个) → `BuiltinPrerequisites.registerAll()` |
| Forge | `F/ImmersiveCinematicsForge.java:14-24` | `registerConfig` → `ForgeNetwork.init` → `ForgeEvents.registerServer` → `ImmersiveCinematics.init` |
| Forge 客户端 | `F/ForgeClientEvents.java:45-49` | `ClientEventHandler.onClientInit()` → `HudLayerRegistry.install(...)` |
| Fabric | `FB/ImmersiveCinematicsFabric.java:8-13` | `ImmersiveCinematics.init` → `FabricNetwork.init` → `FabricEvents.registerServer` |
| Fabric 客户端 | `FB/client/ImmersiveCinematicsFabricClient.java:8-10` → `FB/FabricEvents.java:86-91` | `ClientEventHandler.onClientInit()` → `HudLayerRegistry.install(...)` → 键位 → tick → HUD 回调 |

> `TrackRegistry.ensureBuiltins()` 的显式调用点 = `C/ImmersiveCinematics.init`（common 侧，Forge/Fabric 都覆盖）；客户端专属的第三方注册（HUD 层 / overlay）在 `HudLayerRegistry.install` 之后。

### 2.10 事实层（对外读面的数据源）

| 符号 | 位置 | 现状 |
|---|---|---|
| `FactLedger.INSTANCE` | `C/trigger/server/fact/FactLedger.java:48` | `initialize(server)`(:68)、`of(UUID) : PlayerFacts`(:87)、`ofOrNull`(:92)、`record(UUID,Fact)`(:198)、`voteCount`(:224) |
| `Fact` | `C/trigger/server/fact/Fact.java:11` | `record(FactType type, String script, String object, String detail, String instance, int tick, FactResult result, FactScene scene)` |
| `PlayerFacts` | `C/trigger/server/fact/PlayerFacts.java` | `exists`(:56)、`count`(:61)、`last`(:67)、`lastOf`(:79)、`lastMatching`(:104)、`last(...,requiredResult)`(:129)、`anyWithin`(:153)、`lastWithin`(:182)、`anyObject`(:206)、`retainedCount`(:227)、`retained`(:233) |
| `FactType` / `FactResult` | `C/trigger/server/fact/` | 含 `PLAYBACK_STARTED` / `PLAYBACK_FINISHED` / `TRIGGER_FIRED` / `REGION_ENTERED` / `REGION_LEFT` / `SKIP_VOTE` |

### 2.11 文档（本轮要动的）

| 文件 | 现状 | 本轮动作 |
|---|---|---|
| `H-other/public-api.md` | 状态「设计稿（未实现）」；§2.1 列 8 个注册点、§2.2 缺口、§6.4 写「43 文件 / 10 处 switch」、§四/§五/§八 给接口草案 | 状态改「已实现（H-4）」；§2.2 缺口逐条改现状；§6.4 switch 数字更正为「2 处 switch + 13 处 if/三元/filter」；补 `ScriptInfo` / `PlaybackListener` 注册入口 / `OverlayApi` / `CameraApi` 两个新方法 / `ServerFactsApi` 的实际签名；§十 验收逐条勾 |
| `H-other/README.md` | H12 行 `☐ 设计稿`（`:22`） | 改 `✅ 已实现（H-4，2026-11-04）` |
| `plans/0.3.6/README.md` | H 其它行（`:74`）没有 H12 状态 | 追加 H12 已实现 |
| `plans/0.3.6/I-BACKLOG.md` | `H1-R4`（`:84`）归「Z 轮 + H-4（H12）」 | 关闭或在 H-4 小节写「API 入口已给，展示面仍归 Z 轮」 |
| `docs/SCRIPT_FORMAT.md` | 无第三方轨道段 | 补一段「第三方轨道 id 与 schema 声明」；引用 `public-api.md` |
| `CHANGELOG.md` | — | 加 H-4 条目 |

---

## 三、缺口分类

### 3.1 本轮做

- `spi` 四件套 + `TrackTypeId`/`TrackRegistry` + `TrackType` 全量删除替换（H4-1、H4-2）。
- 统一注册门面 + id 冲突 + 注册时机冻结 + 失败隔离（H4-3）。
- `OverlayApi`（H4-4）。
- `PlaybackApi` / `ServerPlaybackApi` / `PlaybackListener` / `ScriptInfo` + `CameraApi` 两方法 + 客户端脚本缓存（H4-5）。
- `ServerFactsApi` 事实对外读面（H4-6）。
- 探针 + 实机 + 内置 7 轨零回归（H4-7）。

### 3.2 移交后续轮次

| 事项 | 归属 | 关闭条件 |
|---|---|---|
| 「触发过什么 / 为什么触发」的展示面（HUD / 编辑器 UI / 命令输出格式化） | Z 轮 + H-12（沿用 `I-BACKLOG.md:84` 的归属） | 编辑器 / 客户端有读取事实查询 API 的**展示**入口；本轮只给 `ServerFactsApi` 读面 |
| 第三方 overlay 图层的 GPU 资源托管（纹理解码 / 缓存策略） | 未排期 | 出现真实第三方需求（要画图片而不是几何/文本）时定 |
| 客户端触发器 | 不做（设计 §7.2 明令） | — |
| 运行时热注册 / 注册表反注册 | 不做（设计 §9.1/§十一） | — |
| 模板注册开放 | 不做（随 Z 轮删除） | — |
| `hard_hide_hud` 的 Fabric 等价能力 | 未排期（`screen-layers.md` §5.5 边界） | 沿用 H-2 遗留，不在本轮 |

### 3.3 契约边界（写进文档，不排期）

- 第三方 id 区分大小写；脚本里的内置 id 大小写不敏感（旧行为）。
- 第三方轨道**不参与**内置特化语义（`lut` / `blend_mode` / `mask_shape` / `curve` 归属 / 视觉轨循环规则 / 相机 modifier 收集 / EVENT 移动控制）。
- 第三方轨道在客户端与服务端都可以 tick；作用域由轨道自己保证，我们只按 `ScriptPlayer` 的渲染帧调度（客户端）。
- `Clip` 通过 `TrackContext.clip()` 可见，但只承诺片段级只读字段（`startTime` / `duration` / `loop` / `id` 等）；其采样成员与 `objects` 结构不承诺。
- `TrackSchema` 只承载 `objects`；第三方没有 clip 级字段与 `play_control`。
- 注册表在首个脚本解析后冻结；跨存档 / `reload` 不重置冻结状态（进程内一次性）。
- `OverlayApi` 只保证 z 序与释放语义，不保证第三方图层的绘制正确性。

---

## 四、验证与记录约定

| 类别 | 约定 |
|---|---|
| 编译 | 波次结束后主代理统一跑 `sh gradlew :common:compileJava --offline --no-daemon`；涉及平台侧签名时追加 `:fabric:compileJava` / `:forge:compileJava`。**禁止全量 `build`** |
| 探针 | `E:/tmp/h4/`，类路径模板复用 `E:/tmp/h1/run-fact.sh`（`common/build/classes/java/main` + gradle 缓存 jar + MC merged jar）；探针可声明与目标同包以访问包私有成员；`private` 用反射；自己打印 `PASS`/`FAIL` + 汇总，失败退出码非 0 |
| 实机 | `tools/devtools/.venv/Scripts/python.exe tools/devtools/devtools.py`：`client` / `cmd` / `shot`（游戏内 F2）/ `tail` / `trace`；启动前 `jps -l` 确认无残留 Minecraft；**`IC_RUN_DIR` 按平台设**（Fabric 默认 `fabric/run`，Forge 用 `forge/run`） |
| 夹具 | 临时脚本放 `fabric/run/immersive_cinematics/scripts/`（不入库，测完删）；正式夹具放 `cinematics/0.3.6/tests/` |
| 日志 | 过滤走 `tools/devtools` 的 `trace`；限频日志关键字固定为 `third-party track tick failed` / `trigger eval failed` |
| 提交 | `feat(0.3.6): H-4 对外接口与轨道注册表`；**不 push** |

---

## 五、设计文档与源码的已确认差异（必须回写，不得静默）

| # | 设计文档写法 | 源码事实 | 处理 |
|---|---|---|---|
| 1 | `public-api.md:217`「`switch (trackType)` 10 处」，并列出 `TrackPlayer.create`、`ScriptValidator` ×2、`RuleActions`、`ClientConditions` ×2、`ObjectCodec`、`ObjectValidator`、`ObjectSchemaValidator`、`BreathDisturbance` | 只有 **2 处**以 `TrackType` 为表达式：`TrackPlayer.create`、`ObjectValidator.Check.run`。`ScriptValidator` 两处切 `String`（条件/动作 type）、`RuleActions` 两处切 `String`、`ClientConditions` 两处切 `String`、`ObjectCodec` 与 `ObjectSchemaValidator` 切 `PropertyType`、`BreathDisturbance` 切 `int` | 回写为「2 处 switch + 13 处 if/三元/filter（逐条列点）」，数字与清单同时在 §6.4 与 §十 更新 |
| 2 | `public-api.md:50`「43 个文件」 | 成立（40 直接 + 2 间接 + 1 仅类型名）；`grep -rln` 只得 41 | 回写补「40 直接 / 2 间接 / 1 仅类型名」口径 |
| 3 | `public-api.md:113` `PlaybackListener` 只有接口 | 源码无 `onFinished` 符号；真实结束单点是 `PlaybackLedger.reportScriptFinished`（且跳过编辑器实例） | 回写触发点与「编辑器实例不触发」 |
| 4 | `public-api.md:122`「`ScriptManager` 是内部」 | `ScriptManager.INSTANCE` 已 `public`；客户端没有 id→脚本查找（`ClientScriptCache` 零调用方） | 回写：客户端播放 API 依赖本轮接线的 `ClientScriptCache`；服务端用 `ScriptManager` |
| 5 | `public-api.md` §4.2 `ServerPlaybackApi` 说「现有实现：`TriggerEngine.startPlayback(...)`」 | 该符号**不存在**；真实链是 `StartPlaybackAction` → `ScriptManager` → `ScriptStructureResolver` → `primeCommandTable`/`createGrant` → `S2CPlayScriptPacket.send` | 回写为真实链 |
| 6 | `public-api.md` §八「overlay 图层：只提供开层/更新/关层」 | 未定义 owner 语义，而 `OverlayManager` 的释放按 owner 过滤 | 回写 `openLayer(Object owner, ...)` 与「owner 必须传 `TrackContext.owner()`」 |
| 7 | `public-api.md:113` 只列 `ScriptInfo` 类名 | 无字段定义 | 补 §0.16 的 record 签名 |
| 8 | `public-api.md:113`/§4.3 未给 `PlaybackListener` 注册入口 | — | 补 `PlaybackApi.addListener/removeListener` |
| 9 | `state-tracking.md:495` 待定「事实层是否对外暴露」 | 本轮给只读 `ServerFactsApi` | 回写「已定：只读查询暴露，写入不暴露；自定义事实类型仍不开放」 |
| 10 | `H-other/README.md:22` H12 `☐` | — | 改 ✅ |
| 11 | `implementation-progress.md:142` 仍写 H11 `☐ 设计稿`，与 `H-other/README.md:21` 的 ✅ 冲突 | 属 H5 文档滞后范围，**不在本轮范围** | 记入移交（归属 I-5 文档回写轮），本轮不改 |

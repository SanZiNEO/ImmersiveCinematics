# 代码残留审计（K 轮）

> 状态：未开始
> 基线：I-5 收口（2026-11-06）。此文档记录**当时**的扫描结果；施工后逐项勾掉，并把「实际删除行数」补在行尾。
> 目标：0.3.6 要一个**干净且稳定**的版本——既不兼容旧格式，也不残留旧代码。本页只处理「旧代码残留」，不改任何脚本语义。
> 方法：静态扫描（剔除注释与字符串后做全仓标识符引用计数）+ 人工排除 Mixin / 平台入口 / 反射注册 / 探针专用。所有「0 引用」均在 2026-11-06 复核过。
>
> **本页只记录事实与选项；「删什么 / 怎么改 / 先做哪个」由用户裁决。** 文中的「候选方案」「待裁决」不是已定方案。

---

## 一、结论摘要

| 类 | 内容 | 规模 | 风险 |
|---|---|---|---|
| A | 旧字段读取口（`Clip` 访问器 / `PositionData` / `SchemaLoader`） | 约 450–500 行 | 低（全仓 0 引用） |
| B | 违反「运行时只读只执行」的旧行为：`ScriptStructureResolver` 推送前改写脚本 JSON | 149 行 + 连带 | **中**（动到推送路径） |
| C | 旧容器装新数据：`Clip.data` 只剩 `loop`/`loop_count`/`loop_mode` | 结构收尾 | 低 |
| D | 零散死方法（20+ 处，math 包为主） | 约 100–150 行 | 低；少数「探针专用」 |
| E | 未使用 import | 2 处 | 无 |

---

## 二、A 类：旧字段读取口

### A1. `script/Clip.java` — 13 个旧字段访问器（全仓 0 调用）

| 方法 | 旧语义 | 现状 |
|---|---|---|
| `getAttenuation()` | 旧 clip 级音频衰减 | 新格式读 `objects.attenuation`（`ClipValueView`） |
| `getAudioPitch()` / `getAudioPositionMode()` / `getVolume()` | 旧 clip 级音频字段 | 同上 |
| `getFadeIn()` / `getFadeOut()` | 旧 clip 级淡入淡出 | 同上 |
| `getBlendMode()` | 旧 clip 级混合模式 | 同上 |
| `getCommand()` / `getEventType()` | 旧 EVENT 关键帧/片段命令 | 新格式走 `objects.event` 记录 |
| `getLutInputGamma()` | 旧 clip 级 LUT 输入域 | 新格式读 `objects.lut_input_gamma` |
| `getPathSpec()` | 旧 clip 级单份曲线 | 已由脚本级 `paths[]` 取代 |
| `hasClipId()` / `isInstant()` / `isLoop()` | 旧片段判定 | 分别由 `scriptClipId()` / `loop` 三键 / 执行循环取代 |
| `getData()` | 暴露旧 `data` map | 全仓 0 调用（另有 2 处 `getData()` 属别的类） |

> 注：`getString/getFloat/getInt/getBool/getObject`（带 `objects` 回落）**不是残留**——仍被 `CameraKeyframeEvaluator`（`cam_breath_enabled`）、`LaneMotionStepper`（motion 模型）、`BreathDisturbance` 使用，是 value-only 字段的唯一读法。

### A2. `script/PositionData.java` — 361 行 / 47 个公开成员，实际只用 4 项

仍被引用（保留）：
- `ORIGIN_COORDINATE` / `ORIGIN_BLOCK` / `ORIGIN_SELECTOR` / `ORIGIN_STRUCTURE`（`PointSource.kindOf` 映射）
- `originKindOf(String)`（`PointSource`、`ObjectValidator`）
- `DEFAULT_BLOCK_RADIUS`（`WorldPointLocator`）
- `parseBlockOriginString(...)`（仅 `ScriptStructureResolver` 用；B 类删掉后它也随之死亡）

全仓 0 引用（可删）：
`relative()` / `relativeToCoordinate/Structure/Block/Entity` / `facingToPlayer/Entity/Coordinate/Structure/Block` / `getDx/getDy/getDz` / `getFwd/getUp/getRight` / `getOriginType/getOriginX/Y/Z/getOriginSelector/getOriginStructure/getOriginBlockId/getOriginBlockRadius` / `isRelative/isBaseSpace/isOriginCoordinate/isOriginBlock/isOriginSelector` / `getFacingTarget` / `withFacingTarget` …

### A3. `script/SchemaLoader.java` — 8 个方法里 6 个 0 调用

在用：`getMetaFields()`（`ScriptParser`、`ScriptTemplate`）。
可删：`getDefaultValue` / `isRequired` / `hasField` / `getClipFields` / `getKeyframeFields` / `getEnumValues`（`getKeyframeFields` 这名字还是旧模型命名）。

---

## 三、B 类：违反「运行时只读只执行」的旧行为

### B1. `util/ScriptStructureResolver.java`（149 行）

**现状**：服务端推送前**改写脚本 JSON**——把 `look_at_target_structure` 换成 `look_at_target_x/y/z`、`facing_origin`（结构 id / `block:id[:radius]`）换成 `"coordinate"` + `facing_origin_x/y_z`。
调用点：`trigger/server/action/StartPlaybackAction`、`api/ServerPlaybackApiImpl`。

**冲突**：2026-10-08 定的口径是「运行时只读只执行；世界相关引用在**执行时解析**，不改写脚本数据」。当时把该文件列入「越界处置 4 处」，要求改为执行时解析——**未落地**。

**待裁决（两种方向，均可实现，差别在改动面与风险）**：

| 选项 | 做法 | 代价 / 风险 |
|---|---|---|
| A. 改为执行时解析 | 删掉推送前改写；两条推送路径原样发送；结构 / 方块取点交给点源解析层（`PointSource.kindOf` / `blockFromString` / `WorldPointLocator`） | 符合既定口径；动到「服务端替客户端解析」这条链路，单人服（客户端本地解析）与专用服（服务端预解析）两种形态都要复验 |
| B. 保留现状，登记为契约边界 | 不改代码；在 `track-camera.md` / `object-model.md` 写明「推送前替换是运行时只读原则的已知例外」 | 零风险；但「运行时只读」口径出现例外，后续每轮都要重复解释 |

**连带影响（无论选哪个）**：`PositionData.parseBlockOriginString` 只在 A 方案下才会变死；选 B 则保留。

---

## 四、C 类：旧容器装新数据

### C1. `Clip.data`（`Map<String,Object>`）

新格式下 `parseObjectClip` 只往里放 `loop` / `loop_count` / `loop_mode` 三个键，其余读取全走 `objects` 回落。**容器是旧的，装的是新的三键**。

**待裁决（三种方向）**：

| 选项 | 做法 | 代价 |
|---|---|---|
| A. 改显式字段 | `Clip` 增 `loop` / `loopCount` / `loopMode` 三个字段，删 `data` map 与 `declare()` 的 `data.containsKey` 分支 | 结构最干净；改构造器签名与 `ScriptParser` 调用点 |
| B. 只读 objects | 三键改从 `objects` 的同名 value-only 属性读（与其它 value-only 字段一致） | 少一个容器；但 `loop` 是播放控制字段、不是采样属性，语义上略有混层 |
| C. 保留 `data` | 记作「通用兜底容器」，只在文档写明现在只装三键 | 零改动；「旧容器装新数据」的观感保留 |

---

## 五、D 类：零散死方法

### D1. 常规（0 引用，可删）

| 位置 | 方法 |
|---|---|
| `script/ScriptParser.java` | `parseVec3`（唯一 private 死方法） |
| `script/Timeline.java` | `getAudioTracks` / `getEventTrack` / `getLetterboxTrack` / `getModEventTracks` |
| `script/ScriptMeta.java` | `getAuthor` / `getDescription` / `getMacroLoopMode` |
| `script/ScriptPlayer.java` | `getRemainingTime` / `getOriginPos` / `getCurrentProperties` |
| `script/execution/ClipBranchController.java` | `currentTarget` / `isBranching` |
| `script/CubeLut.java` | `getFile` / `getLine` / `has1D` / `has3D` / `parseText` |
| `script/CinematicAudioInstance.java` | `decodePeaks` / `getCurrentTime` / `getGain` / `getOpenAlError` / `getSourceState` |
| `script/AudioTrackPlayer.java` | `oneShotCount` / `setSoundSink` |
| `script/CinematicScript.java` | `getUsageIndex` |
| `script/ModEventTrackPlayer.java` | `runtimeFor`（占位轨道的诊断口） |
| `script/TimelineTrack.java` | `getClipCount` |
| `script/TriggerDefinition.java` | `getDeclarationIndex` |
| `script/TriggerRequirement.java` | `scriptCompleted` |
| `script/event/EventRecord.java` | `hasCommand` / `hasPosition` |
| `camera/source/PointSource.java` | `withOffset` |
| `camera/CameraManager.java` | `getActiveScriptId` / `getCameraYaw` |
| `overlay/LetterboxLayer.java` | `getAspectRatio` |
| `client/dimension/DimensionView.java` | `isTickDisabled` |
| `client/lane/LaneRenderer.java` | `captureIds` |
| `Config.java` | `setRuleCommandPermissionLevel` |

### D2. math 包（0 引用；其中 4 个是**探针专用**，删前先确认探针是否还要留）

| 位置 | 方法 | 备注 |
|---|---|---|
| `math/path/ArcLength.java` | `normalized` | |
| `math/path/ArcLengthTable.java` | `sAt` | |
| `math/path/BezierPathSpec.java` | `quadratic` | 工厂便利方法 |
| `math/path/CompositePath.java` | `uFraction` / `uStart` | |
| `math/path/DynamicArcLengthTable.java` | `hasTable` / `isDegraded` | **探针专用**（`E:/tmp/gcurve`） |
| `math/path/FourierFit.java` | `fit` | |
| `math/path/FourierPathSpec.java` | `isPeriodic` | |
| `math/path/PathEvaluator.java` | `positionDynamic` / `tangentDynamic` | **探针专用** |
| `math/path/PathMath.java` | `bernstein` / `gaussLegendre5` | |

> 处理原则（**待裁决**）：仓库内 0 引用即删是默认；但 D2 里 4 个「探针专用」项要先由用户决定——(a) 一并删、探针预期同步改成「已删」；(b) 保留为诊断口并加 `@Internal` 之类标注；(c) 收进 K 轮之后再说。三者不许混成「代码死了但探针还在跑」的半状态。

---

## 六、E 类：未使用 import（2 处）

- `command/CinematicCommand.java` → `com.mojang.brigadier.arguments.BoolArgumentType`
- `forge/ForgeNetwork.java` → `net.minecraftforge.network.NetworkDirection`

---

## 七、设计保留（**不是**残留，别删）

| 项 | 为什么留 |
|---|---|
| `Clip.getString/getFloat/getInt/getBool/getObject` + `objects` 回落 | value-only 字段的唯一读法，仍被相机呼吸 / motion / BreathDisturbance 使用 |
| `ScreenLayer.legacyHidden` + `MetaSchemas.hide_*` / `suppress_*` | `screen_layers` 未声明时的逐层回落是 H-5 契约；`hide_*` 仍是合法脚本字段，不是旧格式 |
| `script/template/`（9 类）+ `ScriptTemplate` | E0 已裁决「随 Z 轮删」，现在仍被 `TemplateScriptAssembler`、`ScriptFileService.newScriptJson()` 使用 |
| `cinematics/0.3.6/tests/faults/bad_removed_*`（5 支） | 故意负例，断言「已移除」 |
| `docs/` 带 OUTDATED 横幅的文档 | 历史模块图，按「后面还有轮次」的口径保留横幅 |

---

## 八、误报清单（**不是**死码）

| 项 | 原因 |
|---|---|
| `ImmersiveCinematicsFabric` / `ImmersiveCinematicsFabricClient` | `fabric.mod.json` 入口点，文本扫描看不到 |
| `net/irisshaders/iris/{pipeline,shadows}/*` | 编译期桩，运行时由真 Iris 接管 |
| `ForgeConfig.Events` | `@Mod.EventBusSubscriber` 反射注册 |
| `mixin/` 包全部方法 | MC 反调（扫描已整包排除） |
| `@Override` 方法 | 接口实现，调用方是接口 |

---

## 九、其他不干净处（2026-11-06 补充扫描，**均待裁决**）

| # | 位置 | 事实 | 影响 | 可选处置 |
|---|---|---|---|---|
| 1 | **仓库根 `README.md`（167 行）** | 仍在描述旧格式：`The script format is v3, and meta.version must be 3`、`total_duration`、clip 级 `keyframes`、以及一段完整的旧格式 JSON 示例（8 处命中） | **对外门面文档与现行格式直接矛盾** | ① 整篇改写为新格式；② 保留结构与大部分内容，只把格式段换成「见 `docs/SCRIPT_FORMAT.md`」；③ 暂不动 |
| 2 | `docs/modules/overlay.md` | 2 条死链：`../../SCRIPT_FORMAT.md`（实际在 `docs/SCRIPT_FORMAT.md`）、`../../plans/0.3.6/camera-composition.md`（已迁走） | 点开 404 | 修相对路径 / 删链 |
| 3 | `plans/0.3.6/F-playback/README.md` | 2 条死链指向 `cinematics/0.3.6/tests/camera_pool/README.md`（该文件不存在） | 同上 | 补 README / 改指向 |
| 4 | 代码注释 **29 处「旧格式」** | 如 `CameraLane`、`CameraManager`、`CameraApi`、`AdjustTrackPlayer` 的 javadoc 仍写「旧格式片段走旧插值器」——旧插值器已删，现在只对应「空 `objects` 片段」 | 误导维护者（**行为正确**） | 批改成「空 objects 片段」口径 |
| 5 | `docs/` **13 份带 OUTDATED 横幅** | `modules/{camera,client,command,core,editor,handler,mixin,overlay,script,util,README}`、`LUT_WORKFLOW.md`、`old/*` | 已按「后面还有轮次」保留横幅；多数正文仍是旧结构 | 需要时逐份更新（K/Z），不批量动 |
| 6 | `plans/0.3.6/consolidation/pending-items.md` | 2026-10-12 快照，列了大量已关闭项（C/D/H 等） | `consolidation/README:22` 已声明失效，但仍易被当现状读 | ① 加「已失效」抬头；② 删除 |
| 7 | `example/`（48,406 个 JSON，含 106 个旧格式模组副本） | **未入库**（`.gitignore` 有 `/example/`），只是磁盘上的上游参考 | 0 影响仓库 | 无需处理（仅提示） |
| 8 | `editor/{dist,release,node_modules}`、`common/run`、`fabric/run`、`forge/run` | 均未入库（`run/` 与 editor 产物已在 `.gitignore`） | 0 影响仓库 | 无需处理（运行时目录的旧脚本已在 I-5 清过） |
| 9 | `CHANGELOG.md:46` 提到 `total_duration` | 历史条目，描述当时的改动 | 属变更史，不算残留 | 保持 |

> 其中 **#1（根 README）** 是这一批里唯一「对外可见且事实错误」的项；#2–#3 是死链；#4 是注释措辞；#5/#6 是历史快照口径。全部等你决定做不做。

---

## 十、候选施工顺序（**待裁决**，不是已定计划）

下面只是按「改动面 / 依赖」排的一个可行顺序，便于比较；先做哪个、做不做，由用户决定：

1. **B1 `ScriptStructureResolver`**（若选 A 方案，牵动面最大，宜先做）→ 连带 A2 `PositionData` 瘦身。
2. **A1 `Clip` 旧访问器** + **C1 `Clip.data`**（同一文件，可一次改完）。
3. **A3 `SchemaLoader`** 收敛（只留 `getMetaFields()` 或直接删门面、调用方改 `SchemaRegistry`）。
4. **D 类零散死方法**（math 包放最后，先按 D2 的裁决处理探针专用项）。
5. **E 类 import**。

**每步都适用的验收**（不含任何新语义）：
- `sh gradlew :common:compileJava --offline --no-daemon`、`:forge:compileJava` 通过；
- `E:/tmp/icv` 全量 258 支仍为 `8 of 258`（3 既有缺陷 + 5 负例），不得新增；
- 头部探针（I-5 的 `I5Probe`）24/24 PASS；
- 若动到 B1：补一次实机（单人服 + 专用服各跑一支带结构/方块目标的脚本，比较相机朝向一致）。

**完成判据（待裁决后生效）**：用户裁定的范围内各项勾掉，且对应验收全过；未选中的项保持原状并在本页标注「按裁决保留」。

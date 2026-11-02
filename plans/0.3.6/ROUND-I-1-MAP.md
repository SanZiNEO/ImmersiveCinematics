# I-1 轮项目地图（主代理）

> 建立时间：2026-11-02。依据：本轮任务书 + `plans/0.3.6/I-script-core/{object-model,schema-and-types,runtime-evaluation,track-camera,track-overlay-letterbox,track-audio-adjust,track-event}.md` + 源码核对。
> 本文件是本轮派单与验收的唯一索引；行号仅供定位，以类名 / 方法签名为准。
> `docs/` 过时，只作线索；冲突时以设计文档与源码为准。

## 〇、用户裁决（本轮口径，优先级最高）

1. **同片段只写新字段模型**：`objects` 片段不得再写 `keyframes`；`objects` 内部不接受旧字段名别名、不做 `keyframes` 回退合并。旧片段（只写 `keyframes`）按总任务书过渡策略继续可读可播到 I-5；I-5 才把旧字段切为错误。
2. **运行时只做通用层，不接轨道**：本轮交付对象模型 / schema / parser / validator / 通用采样器 / 点源与朝向求值原语；`CameraTrackPlayer`、`OverlayTrackPlayer` 等轨道装配一行不改。
3. **schema 七轨全部声明**：CAMERA / LETTERBOX / AUDIO / EVENT / MOD_EVENT / OVERLAY / ADJUST 的 `ObjectDef` 一次定稿；未接入轨道的声明本轮只供导出与校验。
4. **脚本级结构集合本轮不做**：`PathDef` / `RuleDef` / `RuleBinding` 不落地、不造空壳类型；只在地图与文档里保留归属标注。
5. **片段身份与引用（用户补充，已确认方向）**：clip 是持有对象组的内容实体，必须有稳定身份与引用关系；片段有两种存储形式：内联（轨道 `clips[]`）与独立（脚本级 `clips` 表，不属于任何轨道）；轨道下标/数组顺序/`start_time` 都不是身份；轨道只是编排视图；规则/路径/分支/入口通过片段引用定位，并留下「谁用了它」的使用边。I-1 只做通用层，不实现编排/分支；代码 `Clip` 暂不加 id，文档先定口径。详见 `I-script-core/clip-identity.md`。

## 一、轮次范围与基线

**本轮目标**：脚本数据从「全字段 Keyframe 快照」换成「对象 + 采样属性」，parser / validator / schema / 通用采样器共用同一份声明。

**不做**：循环执行轴、轨道专属装配、规则集合、格式清理 / 迁移、脚本级路径集合。

**工作树基线**：`main`，HEAD `ff7982e`，工作树干净；`:common:compileJava` 通过（11.3s）。

**基线数据（本轮改动前实测，2026-11-02）**：
- 解析：`E:/tmp/i1/baseline-parse.txt` → 231 脚本，225 OK / 6 ERR。
  - 6 个既有失败：`test_camera_facing_origin_coordinate.json`（id 超长）、`test_camera_center_crossdim_clip.json`（关键帧时间非递增）、`faults/bad_meta_{bool,int,string}.json`（回归夹具，应失败）、`showcase_11_faction_line.json`（version=4）。
- 校验：`E:/tmp/i1/baseline-icv.txt` → 231 脚本，223 OK / 18 FAIL（既有语料问题，共 82 条 issue），规则 self-test 全过。

## 二、任务清单（编号 + 目标 + 验收）

| 编号 | 目标（一句话） | 验收 |
|---|---|---|
| I1-1 | 对象模型原语：属性路径 / mode / 类型 / 插值 / `ObjectKeyframe` / `SampledProperty` / `ClipObjects` / JSON 值编解码 / 复合拆叶子表 | `E:/tmp/i1/run-object.sh`：路径规则、插值推导、采样边界（空/单帧/越界/step/angle/geometric/可空）、JSON 类型拒绝、复合表覆盖 |
| I1-2 | `ObjectDef` + `TrackTypeSchema.objects/playControl` + 七轨对象声明 + `SchemaExporter` 导出 | 声明自检（唯一路径/类型与默认值/interp 兼容/requires 无环/enum 非空/appliesTo 合法）+ 导出 JSON 含 `objects`、`playControl`、元数据字段；文档计数核对（ADJUST 59、CAMERA 57 调色等） |
| I1-3 | `Clip` 携带 `ClipObjects`；`ScriptParser` 识别 `objects` 新入口；旧 `keyframes` 分支逐行不变 | 新格式样例解析成功；非法样例报指定错误；`baseline-parse.txt` 逐行一致 |
| I1-4 | `ObjectValidator` 新格式规则；`ScriptValidator` 仅在新格式片段接入 | 新格式正例 0 issue；负例命中指定文案；`baseline-icv.txt` 逐行一致 |
| I1-5 | `ObjectSlotLayout` + `SampledClip` + `ClipSampler`：同一 `executionLocalTime`、槽位数组、游标/二分、零分配 | 探针：同 t 多对象一致；边界；1e6 次采样 0 字节分配（阈值内）；无字符串查表 |
| I1-6 | 点源/参考系/朝向求值（新字段名，纯通用层，resolver 注入）：`position_origin`/`facing_source`/`facing_entity`/`_freeze`/`look_at`/`yaw_base*`/`orient=tangent` | 桩上下文探针：六态点源表、auto 回落、冲突、冻结桶、look_at 覆盖、锁/偏移、切线偏移 |
| I1-7 | 探针与回归：新格式夹具、双基线对比、morph/lane 边界、实机人工清单、文档状态回写 | 全部探针 PASS；两份基线逐行 diff 为空；实机项如实标注执行人/结果 |

## 三、真实文件 / 类 / 方法地图

### 3.1 数据与容器（旧链路，本轮不改语义）

| 文件 | 现状（签名/行为） |
|---|---|
| `common/src/main/java/com/immersivecinematics/immersive_cinematics/script/Keyframe.java` | `class Keyframe`；`float getTime()`、`Map<String,Object> getData()`、`getFloat/getInt/getBool/getString/getObject`、`getPosition()`；一帧 = 全字段稀疏快照 |
| `.../script/Clip.java` | `Clip(float startTime, float duration, TrackType, Map<String,Object> data, List<Keyframe> keyframes)`；`getStartTime/getDuration/getData/getKeyframes`、`isInfinite()`、`getWindowEnd()`、`isEffectivelyInfinite()`、`getPathSpec()`、`getRules()`、`getModifier()` |
| `.../script/Timeline.java` | `Timeline(float totalDuration, List<TimelineTrack> tracks, float loopStart, float loopEnd)`；`getTracks()` |
| `.../script/TimelineTrack.java` | `type + clips[]` |
| `.../script/PositionData.java` | 旧 `position` 复合值（`dx/dy/dz` / `fwd/up/right` / `x/y/z`）+ 旧点源 `facing_origin`/`facing_target` + 旧时态 |
| `.../camera/source/PointSource.java` | `record PointSource(Kind, selector, structureId, blockId, blockRadius, coordinate, EntityPoint, Offset, Time)`；`Kind{WORLD,COORDINATE,SELECTOR,BLOCK,STRUCTURE}`、`Time{LIVE,SNAPSHOT}`、`Space{WORLD,BASE}`、`Offset.apply(Vec3, Frame)`、`Frame(origin,fwd,right,up)`、`EntityPoint{FOOT,CENTER}` |
| `.../camera/source/PointSourceSnapshots.java` | 旧冻结桶（clip + slot 键） |
| `.../camera/source/EntityTargetResolver.java` | `attachClip/attachFrame/attachSnapshots`、`resolveEntity/resolvePointSource/selectorPolicy/selectorAnchor/selectorPick/smoothTargetPoint` |
| `.../camera/source/WorldPointLocator.java` | 结构/方块世界点定位 |

### 3.2 Schema / 导出

| 文件 | 现状 |
|---|---|
| `.../script/schema/FieldDef.java` | `record FieldDef(String type, Object defaultValue, boolean required, List<String> enumValues, String section)` |
| `.../script/schema/TrackTypeSchema.java` | `record TrackTypeSchema(Map<String,FieldDef> clipFields, Map<String,FieldDef> kfFields)`；`EMPTY` |
| `.../script/schema/TrackSchemas.java`（513 行） | `static Map<TrackType,TrackTypeSchema> all()`；七个 builder；7 处 `new TrackTypeSchema(clips, kfs)` |
| `.../script/schema/SchemaRegistry.java` | `get/getClipFields/getKeyframeFields/getMetaFields/hasField/getField/getDefaultValue/isRequired` |
| `.../script/schema/SchemaExporter.java` | `exportAll()` → `{meta, tracks.<TYPE>.{clips,keyframes}, triggers, models, curveTypes}` |
| `.../script/SchemaLoader.java` | 只转发 `SchemaRegistry` |
| `.../webui/WebEditorApi.java` | `handleSchemaGet` → `SchemaExporter.exportAll()` |

### 3.3 Parser / Validator

| 文件 | 现状 |
|---|---|
| `.../script/ScriptParser.java`（1627 行） | `static CinematicScript parse(String)`；`parseClip(JsonObject, String p, TrackType) : Clip`（:297）；`parseKeyframe(...)`（:417）；`parseFieldBySchema(...)`（:659）；`parsePositionData`（:1172）；`parsePositionOrigin`（:1230）；`static class ScriptParseException extends Exception`（:81） |
| `.../script/ScriptValidator.java`（2998 行） | `static List<String> validate(String)`、`validate(String, Collection<String>)`（:174）；大量 `checkXxx`；返回中文 issue |
| 现状硬约束 | `parseClip`：CAMERA 片段 `keyframes` 为空即报错；`duration == 0` 报错；`loop_count == 0` 修正为 1；`objects` 目前会被当未知字段解析成 map |

### 3.4 运行时求值（旧）

| 文件 | 现状 |
|---|---|
| `.../script/KeyframeInterpolator.java` | `computeInterpolation(double clipTime, Clip)` → `InterpolationResult(from,to,s)`；**内部做 loop 时间折叠**；`interpolateChannel(List<Keyframe>, double, String, float)` 逐字段回落默认值 |
| `.../script/CameraTrackPlayer.java` | `onRenderFrame(double)`；`renderSingle(globalTime, clip, clipLocalTime, capture, laneRank)`、`renderMorph(prev,next,globalTime)`、`isClipUsable`、`findActiveClips` |
| `.../camera/source/CameraKeyframeEvaluator.java`（898 行） | 构造 `(EntityTargetResolver, WorldPointLocator, Vec3)`；`renderSingle/renderMorph/findActiveClips/isClipUsable/snapshotFrameState/restoreFrameState/resetPaths`；旧 `yaw_base`/`yaw_base_selector`/`yaw_base_from|to` 语义 |
| `.../script/OverlayTrackPlayer.java`（359 行） | `onRenderFrame/createLayer/updateLayer/interpolateFloat/interpolateSourceComponent/clipTime/findActiveClip` |
| `.../script/ScriptPlayer.java` | `clipsForTrack(int)`、`onRenderFrame(double)`、`start/stop`、`seekTo/onSeek` |

### 3.5 G 轮已实现边界（不要重复实现）

- `math/path/` 30 文件：`PathSpec`、`BezierPathSpec`、`ArcLengthTable`、`DynamicArcLengthTable`、`PathEvaluator.positionDynamic/tangentDynamic`、`AxisConstrainedSpec`、`CompositePath`、`Continuity`。
- `script/schema/PathCurveRegistry` 旧 `clip.curve` 类型注册表。
- 旧 `Clip.getPathSpec()` + `parsePathCurve` + `checkPathCurve` 已接入。
- `util/Clock`、`GameClock`、`PreviewClock`；运行侧 double、存储侧 float 已收口（G4）。
- 结论：路径数学现成；缺「脚本级路径集合 + 目标片段/相邻端点」结构层，归后续 I 分片。

## 四、依赖顺序

```
I1-1（对象原语）
  └─→ I1-2（schema 声明/导出）
        ├─→ I1-3（parser 新入口）──┐
        └─→ I1-4（validator 新规则）│（并行，不同文件）
                                    └─→ I1-5（采样器）
                                          └─→ I1-6（点源/参考系/朝向）
                                                └─→ I1-7（探针/回归/回写）
```

- 每单最后只跑 `sh gradlew :common:compileJava --offline --no-daemon`；主代理独立复跑编译 + 探针。

## 五、格式契约（父代理确定，子代理不得改口径）

### 5.1 新片段形态

```json
{
  "start_time": 0,
  "duration": 5,
  "objects": {
    "position.x": {"value": 0, "keyframes": [{"time": 0, "value": 0}, {"time": 5, "value": 10}]},
    "yaw":       {"keyframes": [{"time": 0, "value": 0}]},
    "position_mode": {"value": "relative"},
    "event":     {"keyframes": [{"time": 1, "value": {"command": "say hi"}}]}
  }
}
```

- 新格式片段只允许 clip 级键：`start_time` / `duration` / `objects` / `loop` / `loop_count` / `loop_mode`。其余键（含 `keyframes`、旧 `transition`、`curve`、`rules`）→ 错误「新格式片段只允许 …」。
- `objects` 是扁平点分路径 → 属性对象；**不接受**裸标量值、嵌套 map、数组下标。
- 属性对象只允许 `value` / `keyframes`；两者至少一个；`mode=value` 不许 `keyframes`；`mode=animated` 允许空/非空；`mode=event` 必须有 `keyframes` 且值为事件记录。
- keyframe 只允许 `time` / `value`；`time` 有限、`>= 0`、同属性严格递增（重复/递减报错）。
- 越界关键帧（有限片段 `> duration`）保留但忽略，不报错；无限执行（负 `duration`）只忽略 `time < 0`。
- `Clip` 增字段 `ClipObjects objects` + `hasObjects()` + `getObjects()`；旧构造器保留（委托空 objects）。
- 片段身份/引用不在 I-1 代码落地：`Clip` 仍只有 `start_time`/`duration`/`objects`；后续编排分片再实现稳定身份、引用与使用边（见 `clip-identity.md`）。

### 5.2 共用错误文案（parser 与 validator 一致）

- 未知对象路径：「未知对象路径: <path>」
- 形态：「<path>: mode=value 不允许 keyframes」「<path>: mode=event 必须有 keyframes」「<path>: 需要对象 {value?, keyframes?}」「<path>: 未知成员 <key>」
- 类型：「<path>.value: 需要有限数字」「<path>.value: 需要整数」「<path>.value: 需要布尔值」「<path>.value: 需要字符串」「<path>.value: 不在枚举内（允许: …）」「<path>.value: 需要 null 或有限数字」「<path>.keyframes[i].value: …」
- 时间：「<path>.keyframes[i].time: 必须为有限数字」「<path>.keyframes[i].time: 不能为负数」「<path>.keyframes[i].time: 关键帧时间必须单调递增，前一帧: x」
- 必填：「<path>: 必填对象没有值/关键帧/默认值」
- 前置条件：「<path>: 写了但前置条件不满足（requires=<expr>）」
- 点源冲突（CAMERA）：「position_mode=absolute 时 position_origin 必须为空」「position_mode=absolute 不允许 position_space=base」「facing_source=auto 时 facing_target 与 facing_entity 不能同时非空」「facing_source=entity 需要实体来源（facing_entity 或实体原点）」「facing_source=line 需要 facing_target」「facing_source=world 不使用 facing_target/facing_entity，已忽略」等。
- 切换对齐：「<path>: 参考系切换缺少 position.x/y/z 关键帧（time=T）」

### 5.3 新对象包（`script/object/`）

**I1-1 交付**
- `PropertyPath`（record；`parse(String)`；段规则 `[a-z][a-z0-9_]*`，`.` 分隔，无空段）。
- `PropertyMode` {VALUE, ANIMATED, EVENT}；`PropertyType` {FLOAT, INT, BOOL, STRING, ENUM, REF, NULLABLE_FLOAT, EVENT_RECORD}。
- `Interp` {NONE, LINEAR, ANGLE, GEOMETRIC, STEP}；`infer(type, path, unit)`：float+`yaw/roll/yaw_lock/pitch_lock`或`unit=degree`→ANGLE；float+`zoom`→GEOMETRIC；其他 float→LINEAR；int/bool/string/enum/ref→STEP；nullable_float→STEP；event_record/VALUE→NONE；显式覆盖由 `ObjectDef.interp` 决定。
- `ObjectKeyframe(float time, Object value)`。
- `SampledProperty`：不可变；`path/mode/type/interp/value/keyframes`；`compile(float duration, boolean infinite)` 过滤范围内帧并编译 typed arrays；采样 API：`sampleFloat(double t, float def)`、`sampleInt`、`sampleBool`、`sampleString`、`sampleNullableFloat(double t, boolean[] presentOut)`、`sampleEventAt`/`eventTimes`；规则严格按 `object-model.md` §3.4（单帧恒值、越界取端帧、step/angle/geometric）。
- `ClipObjects`（LinkedHashMap 只读视图；`get/contains/paths/isEmpty/EMPTY`）。
- `ObjectCodec`：`fromJson(PropertyType, JsonElement, String path)`（严格；抛 `ObjectFormatException`）；事件记录形状（`event`：`command`/`event_type`/`position{x,z}|{dx,dz}`/`id`；`mod_event`：`event_type`/`data`）。
- `CompositePaths`：旧复合 → 叶子表（`position`→x/y/z；`dest`/`source`→x/y/w/h；`color`→r/g/b；`look_at_part`→x/y/z；`look_at_offset`/`facing_target_offset`/`yaw_base_from_offset`/`yaw_base_to_offset`→x/y/z+space），供 schema 完整性探针与后续迁移，不参与运行时。

**I1-5 交付**
- `ObjectSlotLayout`：按 `TrackType` 从 `SchemaRegistry.getObjects(type)` 编译 `path → slot`；`slotOf(String)`、`typeOf(int)`、`defs()`、`size()`。
- `SampledClip`：`executionIndex`、`executionLocalTime`、`float[]/int[]/boolean[]/String[]` + `presence` bitset；`clear()`；只读访问器。
- `ClipSampler`：`ClipSampler(Clip)`（内部按 `clip.getTrackType()` 取 `ObjectSlotLayout`）；`newBuffer()`；`sample(double localTime, SampledClip out)`；每通道 `times[]` + typed values + cursor（顺序播放 O(1)，seek 二分）；**sample 内零分配**；不读 `loop`/`loopMode`。

**I1-6 交付**
- `PositionMode` {RELATIVE, ABSOLUTE}、`PositionSpace` {WORLD, BASE}、`FacingSource` {AUTO, WORLD, ENTITY, LINE}。
- `PointSourceSpec`（来源 + 载荷 + `Offset` + `freeze`；复用 `camera/source/PointSource.Kind/EntityPoint/Offset/Frame`）。
- `PositionSpec`（mode/space/origin/x/y/z/offset）；`FacingSpec`（source/target/coords/offset/freeze/entity）。
- `OrientationSpec`（yaw/pitch/roll、locks、yaw_base/pitch_base、yaw_base_selector、yaw_base_from/to + coords/offsets/freezes、look_at + 载荷、orient、yaw_offset/pitch_offset）。
- `WorldContext` 接口（playerActivation/entityFeet/entityBodyYaw/entityLookPitch/blockCenter/structureCenter；未解析返回 null）。
- `FreezeStore`：桶键 = clip 键 + 对象路径 + 来源签名 + freeze；`clear()`/`resolve(...)`。
- `PositionEvaluator`：`evaluate(PositionSpec, FacingSpec, WorldContext, FreezeStore, String clipKey)` → `PositionResult(Vec3 world, PointSource.Frame frame)`；auto 回落表、六态点源、冻结、absolute 校验前置。
- `OrientationEvaluator`：`evaluate(OrientationSpec, PositionResult, Vec3 tangentDir, WorldContext, FreezeStore, String clipKey)` → `OrientationResult(yaw,pitch,roll)`；look_at 覆盖、yaw/pitch base（world/entity/line）、lock、offset、tangent（`orient=tangent` 且 look_at=none 时用切线 + `yaw_offset/pitch_offset`）。
- `PathTangent` 接口 + `SampledPathTangent`（用 `ClipSampler` 对 `position.x/y/z` 做中心差分；无路径条目的本轮实现）。

## 六、现状与缺口

1. 已补（I1-1..I1-5）：对象模型原语、schema `objects`/`playControl` 与七轨声明、parser 新入口、validator 新规则、槽位采样器。
2. 已补：同一 `executionLocalTime` 的槽位采样与零分配（1e6 次 0 字节）；旧 `KeyframeInterpolator` 未改，旧链路继续走旧路径。
3. 仍缺：点源/朝向新字段求值（I1-6）；片段身份/引用/使用边（后续编排分片）。
4. 旧链路零回归：`baseline-parse.txt` / `baseline-icv.txt` 逐行一致（已复跑）。
5. 仍缺：轨道接入、循环执行轴、规则/路径集合（任务书明确不做/后续分片）。

## 七、验证工具

| 工具 | 命令 | 用途 |
|---|---|---|
| `E:/tmp/icv/run.sh` | `sh run.sh <dir...>` | 真实 `ScriptValidator` 全量校验 + 规则 self-test |
| `E:/tmp/i1/run-parse.sh` | `sh run-parse.sh <dir...>` | 真实 `ScriptParser` 全量解析 |
| `E:/tmp/i1/run-object.sh`（I1-1 建） | `sh run-object.sh` | 对象原语探针 |
| `E:/tmp/i1/run-schema.sh`（I1-2 建） | `sh run-schema.sh` | schema 声明/导出探针 |
| `E:/tmp/i1/run-parse-object.sh`（I1-3 建） | `sh run-parse-object.sh` | 新格式解析探针 |
| `E:/tmp/i1/run-validator.sh`（I1-4 建） | `sh run-validator.sh` | 新格式校验探针 |
| `E:/tmp/i1/run-sampler.sh`（I1-5 建） | `sh run-sampler.sh` | 采样/零分配探针 |
| `E:/tmp/i1/run-position.sh`（I1-6 建） | `sh run-position.sh` | 点源/朝向探针 |
| `E:/tmp/i1/run-boundary.sh`（I1-7 建） | `sh run-boundary.sh` | morph/lane 边界探针 |
| 编译口径 | `sh gradlew :common:compileJava --offline --no-daemon`；收口 `sh gradlew compileJava` | — |

## 八、实机验收（人工，主代理不可自动执行）

### 8.1 旧链路像素回归

- 候选脚本：`cinematics/0.3.6/tests/adjust/test_adjust_camera_clip.json`（3 条 CAMERA lane + OVERLAY subtitle + 逐 lane 调色，8s）。
- 对照方式：同一脚本、同一世界/视角、同一渲染设置；改动前（HEAD `ff7982e` 构建）与改动后各跑一次，在 `t=0/2/4/6/8` 截图。
- 判定：同帧像素 diff = 0；若驱动抖动，记录最大通道差与位置，不得有结构差异。
- 记录：模组版本、加载器（Fabric/Forge）、脚本路径、时间点、截图路径、执行人。
- 说明：I-1 未接轨道，新格式脚本不能实机播放；本项只证明旧 `keyframes` 链路未变。

### 8.2 新格式样例（仓库外，不污染旧基线）

- `E:/tmp/i1/fixtures/ok_camera.json`、`ok_overlay.json`：`ScriptParser.parse` + `ScriptValidator.validate` 0 issue；由 `run-parse-object.sh` / `run-validator.sh` 覆盖。
- 不放入 `cinematics/`：避免把 231 个旧脚本的基线变成 233 个，保持「旧脚本逐行一致」可判定。

### 8.3 状态

- **未执行，需人工**；执行后把结果补到 §十一。

## 九、提交

- 代码一笔：`feat(0.3.6): I-1 对象模型与通用采样器`
- 文档一笔：`docs(0.3.6): I-1 地图与状态回写`
- 不 push。

## 十、结构评审项（本轮不实现）

- **片段身份与引用**：稳定身份字段/语法、ClipRef、使用边种类/宿主、反向索引暴露面、重排/插入/删除后的显式重映射、旧轨道+索引引用的迁移。见 `I-script-core/clip-identity.md` §七。
- **片段存储形式**：内联（轨道 `clips[]`）与独立（脚本级 `clips` 表）的字段/语法、引用、运行态区分（多放置）；独立片段不属于任何轨道。
- **脚本级路径/规则集合**：引用形式、端点/时间门寻址、序列化；本轮明确不做。
- **执行入口**：`executionStart` 由默认放置/分支跳转/入口覆盖给出；本轮只定文档口径，不实现编排。

## 十一、I-1 验收记录（2026-11-02）

| 项 | 命令 | 结果 |
|---|---|---|
| 编译 | `sh gradlew :common:compileJava --offline --no-daemon` | exit 0 |
| 对象原语 | `sh E:/tmp/i1/run-object.sh` | PASS 147 |
| schema/导出 | `sh E:/tmp/i1/run-schema.sh` | PASS 521 |
| 新格式解析 | `sh E:/tmp/i1/run-parse-object.sh` | PASS 44 |
| 新格式校验 | `sh E:/tmp/i1/run-validator.sh` | PASS 35 |
| 采样/零分配 | `sh E:/tmp/i1/run-sampler.sh` | PASS 49；1e6 次 0 字节 |
| 点源/朝向 | `sh E:/tmp/i1/run-position.sh` | PASS 40 |
| morph/lane 边界 | `sh E:/tmp/i1/run-boundary.sh` | PASS 17 |
| 旧解析基线 | `run-parse.sh cinematics` vs `baseline-parse.txt` | diff 0 |
| 旧校验基线 | `icv/run.sh cinematics` vs `baseline-icv.txt` | diff 0 |
| 实机像素回归 | §8.1 | 未执行，需人工 |

## 十二、遗留项与风险

1. **实机像素回归未执行**：I-1 未接轨道，新格式脚本不能实机播放；§8.1 只覆盖旧链路。需人工按 Fabric/Forge 各跑一次。
2. **新格式夹具在仓库外**（`E:/tmp/i1/fixtures/`）：刻意不放入 `cinematics/`，避免改变 231 脚本基线；后续若要把新格式纳入常规回归，需单列 fixture 目录与基线口径。
3. **采样器游标仅作缓存**：`ClipSampler` 定位游标已维护，但 `Compiled.sampleX` 仍内部二分；功能正确、零分配，未把「游标 O(1) 取值」下沉到 `SampledProperty`。若要严格 O(1)，需改 `Compiled` 暴露段索引 API（本轮冻结该文件）。
4. **点源/朝向未接轨道**：`script/object/position/` 是纯通用层；`CameraKeyframeEvaluator` 仍走旧字段名与旧顺序，后续轨道装配分片再接。
5. **片段身份/引用未落代码**：`clip-identity.md` 只定口径；`Clip` 仍无 id，规则/路径/分支的稳定引用待后续分片。
6. **schema 声明体量大**：七轨 `ObjectDef` 已声明并自检，但 `section/group/labelKey` 部分沿用推断；编辑器接入时需按实际表单再校准。
7. **旧 `duration<0` 哨兵、`meta.version`/`timeline.total_duration` 未清理**：按任务书不做格式清理/迁移。

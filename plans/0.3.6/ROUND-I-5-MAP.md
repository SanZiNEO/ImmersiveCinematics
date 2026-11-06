# I-5 轮项目地图与共享契约（主代理）

> 建立：2026-11-06。依据：本轮任务书 + `plans/0.3.6/I-script-core/{format-cleanup,migration,acceptance,object-model,loop-execution,path-collection,rule-implementation}.md` + `plans/0.3.6/I-BACKLOG.md` + 四份只读测绘（格式核心 / 迁移对照面 / 夹具清单 / 承接遗留）。
> 行号仅供定位，以类名 / 方法签名为准。`docs/` 已过时，只作线索；冲突以设计文档与源码为准。
> 本文件是本轮派单与验收的唯一索引。

---

## 〇、口径裁决（本轮最高优先级）

### 0.1 任务书自检

目标 / 范围 / 验收 / 不做四项齐全；无互斥约束。`{{轮次}}` 未渲染，按内容判定本轮 = **I-5**（I-1~I-4、H-1~H-6 已关单，git log 可证）。**无用户待裁决项**：任务书点名的五处「二选一」均由设计文档给出方向，按「不把本该自己做的判断外包」直接定，见 0.2。

### 0.2 本轮裁决（写入对应专篇）

| 编号 | 事项 | 裁决 | 依据 |
|---|---|---|---|
| D1 | 旧格式（clip 级 `keyframes`） | **整体删除**：parser/validator 不再识别，出现即「已移除：keyframes」；`Clip.keyframes` / `Keyframe` / `KeyframeInterpolator` 与 10 处旧消费分支一并删除 | 任务书「删除旧字段与旧载体」+ 版本原则「不保留旧兼容」 |
| D2 | 派生范围 | `CinematicScript` 持有只读 `infinite` / `contentEnd` / `finiteDisplayEnd`，加载期派生；**不用** ∞/NaN/MAX 哨兵；`Clip.getWindowEnd()` 保持抛异常，调用方先判 `hasFiniteWindowEnd()` | `format-cleanup.md` §3.2 / §4.3 |
| D3 | `goto_clip` | 新引擎只接受 `{clip, entry_time}`；旧 `{track, at}` 在新引擎校验期报错（现有行为，保留）。范围检查改「目标片段存在 + 可定位 + `entry_time` 落在该片段有效范围（有限片段 `0..duration`，无限片段 `>=0`）」，不再用总时长做上界 | `format-cleanup.md` §3.2/§4.4、I-4 契约 7 |
| D4 | AUDIO `file_loop` | 旧 `loop`（文件循环）→ `objects.file_loop`；新格式 `loop` 只作执行循环；旧字段出现即错误 | `migration.md` §3.8、`track-audio-adjust.md` |
| D5 | `hud_layers` / `hard_hide_hud` | **删除解析与校验入口**，出现即「已移除」；`ScreenLayer` 表是唯一回落真源 | 任务书 H-5 收尾段 |
| D6 | `attenuation=inverse`（I3-C1） | **选 ②：删除枚举值 `inverse`**，`attenuation` 收敛为 `none`/`linear`；validator 告警删除；迁移把旧 `inverse` 写成 `linear` 并记差异报告。**理由**：`SoundInstance.Attenuation` 只有 `NONE`/`LINEAR`（平台支持边界），实现「真正的反距离衰减」只能自建逐帧手动增益模型——那是新增音频子系统，本轮无可验证的验收手段，且与「单一真源 / 不留死字段」冲突；`track-audio-adjust.md` §4.4 按「原方案 A / 实际选 B / 理由」改写。全仓 252 支脚本无一处使用 `inverse`（实测），零夹具行为变化 | 任务书 I3-C1 ② + 平台枚举实测 |
| D7 | `gradient.stops`（I3-C2） | **选 ①：保持 value-only 并定性关闭**；写入 `track-overlay-letterbox.md` §六 边界 | 同篇 §2.3/§六「本轮不做」 |
| D8 | OVERLAY 同轨多活跃层 / 运行中 `z_index` 重排（I3-C3） | **选 ②：明确不支持同轨重叠、z 仅加入时排序**，写入 `track-overlay-letterbox.md` §2.3 + §六 | 同篇 §2.3/§六 |
| D9 | 宏观循环 L6（I2-B11） | **选 ②：宏观语义不变、时间圈仅定位**；`loop_start`/`loop_end` 只是重复区间，不实现独立执行边界计数；改写 `acceptance.md` L6 表述 | `loop-execution.md` §2.3/§3.6 |
| D10 | `ExitReason.USER_SKIP` | **删除枚举值与 `PlaybackLifecycle` 分支**（无生产者）；`CompletionReason.SKIPPED` **保留**并注明「仅供外部 API / 历史回执识别」（网络枚举，跨版本回执仍可能出现） | 任务书 H-6 段 + 实测调用面 |
| D11 | 脚本级独立片段渲染（I4-R6） | 轨道播放器取片段集合改为「本轨内联片段 + 本轨独立片段（按需/被引用时）」，走 `track` 字段 | 任务书 I4-R6 |
| D12 | `spi` 机械迁移（H4-R1） | 11 类按 `public-api.md` §6.5 收进 `spi`，原包删除，全仓 import 改到 `spi.*` | 任务书未排期项 |
| D13 | 文档 | `docs/` 本轮允许改；按新格式重写，不保留 deprecated 段落 | 任务书交付物 |
| D14 | 迁移器去留 | `tools/script-migrate`（2190 行 / 7 文件）是**一次性工具**：本轮先独立 `chore(0.3.6)` 提交（可审计），迁移+验收通过后在收尾提交里**删除模块并移除 `settings.gradle` include**；HEAD 不留一次性工具，证据只留 `I-script-core/migration-report.md` 摘要（原始报告不入库） | 版本原则「不维护过时东西」+ `acceptance.md`「没有临时探针/一次性脚本留在仓库」 |

### 0.3 新格式契约（迁移器与 parser 的唯一口径）

```json
{
  "meta": { "id": "...", "name": "...", "author": "...", "triggers": [], "screen_layers": {}, "screen_layer_mode": "normal" },
  "timeline": { "loop_start": 0, "loop_end": -1, "tracks": [ { "type": "CAMERA", "clips": [ CLIP ] } ] },
  "clips": [ { "id": "...", "track": "CAMERA", "duration": 5.0, "objects": {}, "exit": {} } ],
  "rules": [ { "id": "...", "condition": {}, "actions": [], "policy": {} } ],
  "rule_bindings": [ { "id": "...", "rule": "...", "clip": "...", "gate_time": 0, "control": "observe", "schedule": {} } ],
  "paths": [ { "id": "...", "clip": "...", "from": 0.0, "to": 3.0, "axes": ["x","y","z"], "geometry": {}, "timing": {} } ]
}
```

- CLIP 白名单（`ObjectParser.CLIP_KEYS`）：`start_time` / `duration` / `objects` / `loop` / `loop_count` / `loop_mode` / `id` / `exit`。**其余一律拒绝**。
- `objects` 键 = `TrackSchemas` 声明的对象路径（唯一真源 `SchemaRegistry.getObjects(TrackTypeId)`）。值 = `{"value": …}` 或 `{"keyframes":[{time,value}…]}`。
- 对象路径展开：`position`→`position.x/y/z`；`dest`/`source`→`.x/.y/.w/.h`；关键帧 `color`→`color.r/g/b`；`look_at_part`→`.x/.y/.z`；`*_offset`→`.x/.y/.z` + `.space`。
- 值形态：value-only（`mode=VALUE`）字段**不允许** keyframes；animated 允许；EVENT/MOD_EVENT 用 `event`/`mod_event` 事件记录。
- 超界 keyframes（`time<0` 或有限片段 `time>duration`）保留、忽略、不报错。
- 旧字段拒绝表（parser 硬错，文案含「已移除」）：`meta.version`、`timeline.total_duration`、clip `keyframes`、clip `curve`、clip `rules`、clip `interpolation`、`follow`、`follow_selector`、`facing_origin(_x/_y/_z/_time)`、`selector_*_follow`、`selector_*_facing_origin`、clip 级 `orient`/`yaw_offset`/`pitch_offset`、AUDIO `loop`（文件循环语义）、`meta.hud_layers`、`meta.hard_hide_hud`。

---

## 一、任务清单

| ID | 目标（一句话） | 验收标准 |
|---|---|---|
| **T1 迁移器** | `tools/script-migrate` Java CLI：旧脚本 → 新格式，逐段路径展开、稀疏补齐、差异报告、幂等跳过、备份/回滚 | 对 `cinematics/**` 全量 252 支：0 未处理错误；输出通过当前 parser+validator；重复运行全部跳过；失败不覆盖；报告含每脚本 clip/关键帧/对象/修正帧/路径条目/规则绑定/最大误差/体积/复核项 |
| **T2 格式清理核心** | parser/validator/model 删除 `meta.version` / `timeline.total_duration` / 旧 `keyframes` 载体，旧字段出现即「已移除」；加载期派生 `infinite`/`contentEnd`/`finiteDisplayEnd` | 252 支迁移后脚本全绿；旧字段负例全部报「已移除」；派生探针（无片段/静态有限/含无限/有限+无限并存）全 PASS |
| **T3 删除旧运行时分支** | 删 `Clip.keyframes`/`Keyframe`/`KeyframeInterpolator` 与 10 处旧消费分支；删 `hud_layers`/`hard_hide_hud` 解析/校验 | 全仓 grep 无 `getKeyframes()`/`KeyframeInterpolator`/`hud_layers`/`hard_hide_hud`；编译通过；252 支全绿 |
| **T4 派生消费者** | `ScriptPlayer`/`FrameDriver`/`CinematicCommand`/`ScriptGraphService`/`ScriptInfo`/`RuleActions` 改用派生值 | grep 无 `getTotalDuration`/`total_duration`；命令打印「有限末端/无限」；图节点 `duration`/`infinite` 用派生 |
| **T5 独立片段渲染（I4-R6）** | `clips[]` 独立片段按 `track` 进入对应轨道播放器 | 探针：`clips[]` 里 CAMERA 片段作 `goto_clip` 目标后，求值路径能取到该片段并产出相机状态；实机：相机真的移动 |
| **T6 机械项** | `spi` 迁移（H4-R1）、第三方 `loop` 诊断（H4-R2）、`shouldHide()` 无参（H5-R10）、`BranchSnapshot.size()` 注释（I4-R1）、`attenuation=inverse`（I3-C1）、`ExitReason.USER_SKIP` 删除 | `grep` 示例只依赖 `api.*`/`spi.*`；第三方 `loop_mode=pingpong` 有提示；`shouldHide()` 无参；注释与实现一致；inverse 探针 PASS；`USER_SKIP` 零残留 |
| **T7 文档回写** | `docs/SCRIPT_FORMAT.md` 重写、`AI_SCRIPTING_GUIDE.md`、`TRIGGER_TYPES.md`、`modules/{control,network,trigger,script}.md`、`implementation-progress.md`、`I-script-core` 各专篇状态 | `grep` 无旧口径（`total_duration`、`meta.version`、`hud_layers`、`hard_hide_hud`、`requestExit(ExitReason.USER_SKIP)`、「含已跳过者」）；I README 状态表与实现一致 |
| **T8 验收** | 全量解析/校验矩阵 + 像素级同帧回归 + 迁移矩阵 + 性能 | 4 支循环夹具 + 新格式轨道脚本 Fabric/Forge 各一次；同帧像素差 ≤ 噪声；F5 矩阵不退化；acceptance 全绿 |

### 1.1 承接遗留的关闭对照

| 遗留 | 关闭点 |
|---|---|
| I2-B1 旧循环夹具像素回归 | T8（4 支 + 新格式，Fabric/Forge 各一次，像素级同帧） |
| I2-B10 `docs/SCRIPT_FORMAT.md` 重写 | T7 |
| I2-B11 宏观循环 L6 | D9 + T7（`acceptance.md` L6 改写） |
| I2-B12 AUDIO `file_loop` | T1（迁移）+ T2（旧字段拒绝） |
| I2-B13 迁移器 / 差异报告 / 旧字段拒绝 | T1 + T2 |
| I2-B14 全量实机/性能 | T8 |
| I3-C1 `attenuation=inverse` | T6 + `track-audio-adjust.md` §4.4 |
| I3-C2 `gradient.stops` | T7（`track-overlay-letterbox.md` §六 定性关闭） |
| I3-C3 同轨多层 / z 重排 | T7（同篇 §2.3 + §六） |
| I3 像素级同帧回归 | T8 |
| I3 新格式 9 支纳入迁移 | T1（幂等跳过 + 删两字段）+ T8 |
| I4-R1 `BranchSnapshot.size()` 注释 | T6 |
| I4-R6 独立片段渲染 | T5 |
| H1-R3 `triggers[].id` / `continuity` 文档 | T7 |
| H2-R10 clip 级 `interpolation` 现在时表述 | T7 |
| H2-R11 夹具 `meta.version` / `total_duration` | T1 |
| H5 画面层字段收尾（删旧入口 + 迁移映射） | T1 + T2 + T3 + T7 |
| H6 投票语义文档 / `USER_SKIP` 归属 / 协议兼容说明 | T7 + T6 |
| H4-R1 / R2 / H5-R10 | T6 |
| H4-R4 `implementation-progress.md` H11 状态 | T7 |

---

## 二、现状与关键签名（事实基线）

### 2.1 格式 / 解析

| 位置 | 事实 |
|---|---|
| `script/ScriptParser.java:263` | `int version = requireInt(metaObj, p, "version")`；`:275-277` 非 3 抛错 |
| `script/ScriptParser.java:396` | `float totalDuration = requireFloat(timelineObj, p, "total_duration")`；`:397-399` 为 0 抛错；`:412` `new Timeline(totalDuration, tracks, loopStart, loopEnd)` |
| `script/ScriptParser.java:432` | `parseClip(JsonObject, String, TrackTypeId)`；`:438` 分叉 `obj.has("objects")` |
| `script/ScriptParser.java:580` | `parseObjectClip(JsonObject, String, TrackTypeId, float)`；`:627` `new Clip(start, duration, type, data, List.of(), objects, true, clipId)` |
| `script/object/ObjectParser.java:31-32` | `CLIP_KEYS = {start_time,duration,objects,loop,loop_count,loop_mode,id,exit}`；`:49-56` 白名单拒绝 |
| `script/ScriptParser.java:669` | `parseStandaloneClips(JsonObject)`；`:682-712` `track` 必填、禁 `start_time` |
| `script/ScriptValidator.java:238-247` | version 必填 = 3 |
| `script/ScriptValidator.java:368-378` | total_duration 必填、非 0、有限 |
| `script/schema/MetaSchemas.java:20` | `version` FieldDef（默认 3，必填） |
| `script/ScriptMeta.java:16/57` | `version` 字段 + `getVersion()` |
| `script/Timeline.java:15/39/55` | `totalDuration` / `getTotalDuration()` / `isInfinite() = totalDuration < 0` |
| `script/CinematicScript.java:133/135/137` | 转发 `getVersion()` / `getTotalDuration()` / `isInfinite()` |
| `script/Clip.java:41/43/61` | `keyframes` + `objects` + `playControlFormat` 并存 |
| `script/Clip.java:352-359` | `getWindowEnd()` 无限抛 `IllegalStateException` |
| `script/Clip.java:338` | `isEffectivelyInfinite()` |
| `script/template/TemplateScriptAssembler.java:59` | 重写 `timeline.total_duration` |
| `script/ScriptTemplate.java:43-50` | 写 `version` + `total_duration`（模板归 Z 轮删除，本轮只停止依赖） |

### 2.2 派生范围现状

- **无静态脚本级派生**：`CinematicScript` 无 duration/infinite 字段；`Timeline.isInfinite()` 只读声明值。
- 运行时账本 `script/execution/ClipExecutionLedger.java:166-175` `contentEnd()` 遍历执行记录取 `max(windowEnd)`，含无限抛异常；`:153-159` `hasFiniteContentEnd()`；`:188` `remainingContent()`。
- `ClipExecution`：`infinite :34` / `finiteWindowEnd :39` / `windowEnd :41`；写入点 `resetDefault :95`、`markStandalone :121`、`startBranch :154`。
- 消费点：`ScriptPlayer.java:557`（读 total）、`:603-607`（超时告警）、`:365`（启动日志）、`:437/:447`（剩余时间用账本）、`:832-835`（宏观末端用账本）；`camera/rule/RuleActions.java:357-361`（旧 goto 上界）；`webui/ScriptGraphService.java:186-187`（节点 duration/infinite 读 JSON）；`command/CinematicCommand.java:252`（打印总时长）；`api/ScriptInfo.java:48`。

### 2.3 旧字段拒绝现状（已存在）

`position.up_axis`（`ScriptParser:1597-1599`）、`look_at_target`（`:845-849`）、`position.relative_origin*`（`:1622-1628`）、`meta.exit_on_duration_overrun`（`ScriptValidator:254-257`）、clip `interpolation`（`:459-462`）、clip `scope`/`lane`（`:3053-3060`）、`fit`（`:3067-3070`）。**新增拒绝项见 0.3。**

### 2.4 旧格式消费面（T3 删除清单）

`camera/source/CameraKeyframeEvaluator.java:180`、`client/lane/LaneMask.java:67`、`script/AudioTrackPlayer.java:495/538`、`script/ColorAdjustSampler.java:292`、`script/ColorMaskSpec.java:85`、`script/KeyframeInterpolator.java:28`、`script/LetterboxTrackPlayer.java:83`、`script/OverlayTrackPlayer.java:110`、`script/PlayerMoveController.java:80`、`script/event/EventClipRuntime.java:86`。

### 2.5 迁移输入面（实测，252 支）

- 目录：`0.3.6/tests` 210、`0.3.6/scripts` 13、`demo` 11、`mod_showcase/scripts` 11、`0.3.6/release` 7。
- 格式：旧 242、新 10（`tests/objects/` 9 + `tests/rules/test_rules_gate_suspend.json`）；无混合。
- `meta.version`：251 个 = 3，1 个 = 4（`mod_showcase/scripts/showcase_11_faction_line.json`，现被 parser 拒绝 → 迁移按「version 非 3」记报告后删除字段）。
- 轨道：OVERLAY 245、CAMERA 180、EVENT 93、ADJUST 29、AUDIO 28、LETTERBOX 12、MOD_EVENT 4、`event`（小写，1 处异常，需查）。
- clip 级旧字段命中文件数：`keyframes` 242、`z_index` 213、`loop` 164、`event_type` 89、`curve` 17、`orient` 10、`modifier` 7、`rules` 7、`mask_shape` 4、`suppress_modifiers` 2、`gradient` 1；`interpolation`/`follow`/`follow_selector`/`facing_origin*`/`transition_in`/`transition_out`/`data` 均为 0（出现在 keyframe 内）。
- 关键帧字段面：`time` 2519、`opacity` 1425、`x`/`y` 1032、`position` 834、`roll`/`fov`/`zoom` 834、`pitch` 804、`yaw` 802、`look_at` 441、`position_mode` 331、`font_scale` 304、`look_at_selector` 267、`dest` 260、`command` 240、`scale_x/scale_y` 240、`event_type` 236、`source` 226、`follow` 157、`follow_selector` 136、`look_at_target_x/y/z` 120、`look_at_offset` 105、`facing_origin` 65。
- `curve` 类型：`bezier`（含 `control_points`）、`bspline`、`catmull_rom`、`clothoid`、`fourier`、`hermite`、`nurbs`、`rational_arc`、`rational_bezier` —— 全部由 `PathCurveRegistry` 注册，迁移**原样复制 geometry JSON**。
- `total_duration` 与 `max(start+duration)` 不一致的 6 支：`test_camera_relative_axis`(8/12)、`test_exit_branch_extend`(8/14)、`test_exit_content`(12/10)、`test_exit_loop_count`(10/6)、`showcase_01_welcome`(31.5/31)、`showcase_06_nether`(12/11.5) —— 迁移报告单列。

### 2.6 路径 / 规则集合（T1 目标结构）

- `script/path/PathEntry.java:28` `record PathEntry(String id, Clip target, float from, float to, AxisConstraint axes, PathSpec geometry, PathTiming timing)`；`from < to`、`axes` 至少一轴。
- `PathCollectionParser.java:91` `parse(JsonObject, ClipResolver, UsageIndex.Builder, String)`；`:345` `positionNodeTimes(Clip)` = `position.x/y/z` 关键帧时间**并集**；`:390` `indexOfTime`（容差 `1e-4`）。端点必须在该并集内且相邻。
- `script/rule/RuleDefinition.java:65` 构造 `(id, condition, actions, once, cooldown, mode, dwell, timeout, onTimeout)`。
- `script/rule/RuleBinding.java:34` 构造 `(id, definition, target, gateTime, control, schedule, exitOverride)`；`gateTime` ∈ `[0, duration]`。
- `RuleCollectionParser.java:88` `parse(JsonObject, ClipResolver, UsageIndex.Builder, String)`。
- 旧 `ClipRule`（`script/ClipRule.java:92` `parse(JsonElement, String)`）字段：kind/condition/actions/once/cooldown/mode/dwell/timeout/onTimeout；**无 id、无 gateTime、无 control、无 schedule**。

### 2.7 承接遗留的真实位置

| 项 | 位置 |
|---|---|
| `spi` 11 类 | `trigger/server/TriggerType.java:14`、`trigger/server/ListenStrategy.java:6`、`trigger/server/continuity/TriggerEvalContext.java`、`trigger/prereq/TriggerPrerequisite.java:16`、`math/MathModel.java:10`、`math/ModelParams.java:12`、`script/schema/FieldDef.java:24`、`math/path/PathSpec.java:10`、`script/schema/PathParams.java:17`、`script/schema/PathParamDef.java:17`、`math/path/PathCapability.java:7` |
| `ObjectValidator` 兜底 | `script/object/ObjectValidator.java:216-219`（`CHECKERS` 只登记 6 型，`:226-241`） |
| `GuiMixin.shouldHide(Boolean)` | `mixin/GuiMixin.java:31-35`；调用点 `:110/:118/:132` 全传 `null` |
| `BranchSnapshot.size()` | `script/execution/BranchSnapshot.java:22` 注释「记录数（= 脚本扁平片段数）」 |
| 独立片段 | `ScriptParser.java:669` 解析；`CinematicScript.java:59/72` 持有；`ScriptParser.java:206-215` **只登记 timeline 片段的使用边**；5 个播放器全部经 `ScriptPlayer.clipsForTrack(int)`（`:179-184`）→ `TimelineTrack.getClips()`，**不含独立片段** |
| `USER_SKIP` | `control/ExitReason.java:14`；`camera/PlaybackLifecycle.java:204-210` 唯一引用；无生产者（长按已改投票） |
| 投票达标 | `trigger/server/ScriptEventManager.java:251-259` 达标 → `S2CStopScriptPacket`（SYSTEM_STOP），不产生 `SKIPPED` |
| `hud_layers` 入口 | `ScriptParser.java:297/326-329/2078-2185`；`ScriptValidator.java:335-338/2926-2999`；`MetaSchemas.java:59-61`；真源 `script/ScreenLayer.java:20-34/74-83` |
| `ScreenLayer` 唯一真源 | `script/ScreenLayer.java`（15 层 + `BY_ID` + `legacyHidden`） |

---

## 三、依赖顺序与派单波次

```text
Wave 1（并行，文件不相交）
  T1 迁移器（tools/script-migrate/**，全新目录；只读 JSON，不 import 运行时 parser）
  T6 机械项（spi/trigger/math/schema 包 + mixin + execution 注释 + audio + control）

Wave 2（串行，依赖 T1 产出与 T6 的包名）
  T2 格式清理核心（parser/validator/model/派生）
  → 迁移全部 fixture（主代理执行 T1 CLI）
  → 复跑校验

Wave 3（串行）
  T3 删除旧运行时分支
  T4 派生消费者
  T5 独立片段渲染

Wave 4（并行）
  T7 文档回写
  T8 验收（探针 + 实机 + 像素）
```

排序理由：
- T1 必须先于 T2：T2 一旦拒绝旧格式，242 支夹具立即不可加载；迁移完成前不能翻开关。
- T1 的旧格式读取**自带冻结副本**，不依赖 `ScriptParser`，故可与 T2 并行且 T2 之后仍可复用。
- T6 的 `spi` 迁移会改 `ScriptParser`/`ScriptValidator` 的 import，必须早于 T2 或晚于 T3；选「早于」，避免与 T2/T3 抢同一批文件。
- T3 删除旧分支必须在 T2 完成（`Clip.keyframes` 无解析来源）之后。
- T5 改 `ScriptPlayer` 与 5 个轨道播放器，与 T4 有文件重叠，串行。

---

## 四、新格式迁移映射表（T1 实现口径）

| 旧 | 新 | 备注 |
|---|---|---|
| `meta.version` | 删除 | 非 3 记报告 |
| `timeline.total_duration` | 删除 | 与 `max(start+duration)` 不一致时记报告 |
| clip `keyframes[]` | `objects.<path>` 的 keyframes | 逐通道展开 |
| clip `curve` | `paths[]` 条目（每旧全局帧段一条） | `geometry` 原样复制；`axes=["x","y","z"]`；`timing` 线性；端点 materialize |
| clip `rules[]` | `rules[]` 定义 + `rule_bindings[]` 绑定 | `gate_time=0`（旧规则随片段活跃窗生效，记报告）；无 id → 生成 `r_<clipId>_<n>` |
| AUDIO clip `loop` | `objects.file_loop.value` | 执行循环 `loop` 保留在 clip 外层 |
| clip 级 value-only 字段（`sound`/`source`/`pitch`/`fade_in`/`fade_out`/`attenuation`/`position_mode`/`category`/`layer_type`/`color`(#hex)/`gradient`/`mask_shape`/`blend_mode`/`path`/`text`/`z_index`/`event_type`/`data`/`transition*`/`motion_*`/`modifier`/`suppress_modifiers`/`cam_breath_*`/`orient`/`rgb_curve`/`r_curve`/`g_curve`/`b_curve`/`hv_*`/`lv_s`/`sv_*`/`lut`/`lut_input_gamma`/`dimension`/`mask_*`(CAMERA 新格式允许)/`selector_*`) | `objects.<同名>.value` | 名字不变；STRUCT 原样 |
| keyframe 标量字段 | `objects.<同名>` keyframes | 稀疏补齐见 §4.1 |
| keyframe `position` map | `position.x/y/z` + `position_mode` + `position_space` | 键集合决定 mode/space |
| keyframe `dest`/`source` | `.x/.y/.w/.h` | |
| keyframe `color` map | `color.r/g/b` | 0~1 |
| keyframe `look_at_part` | `.x/.y/.z` | |
| `*_offset` | `.x/.y/.z` + `.space` | |
| `facing_origin` | `position_origin` | 空串不写 |
| `facing_origin_x/y/z` | `position_origin_x/y/z` | |
| `facing_origin_time` | `position_origin_freeze`（`snapshot`→true，`live`/缺省→不写） | 静态来源丢弃并报告 |
| `*_time`（`facing_target`/`yaw_base_from`/`yaw_base_to`/`look_at`） | `*_freeze` | 同上 |
| `follow=entity` | `position_origin=<follow_selector>` + `position_origin_freeze=false` | `follow=none` 不生成 |
| `selector_*_follow` / `selector_*_facing_origin` | `selector_*_position_origin` | 两组并存 → 报告冲突，follow 优先 |
| EVENT keyframe | `objects.event` 的 EVENT_RECORD keyframes | `{event_type, command, position}` |
| MOD_EVENT keyframe | `objects.mod_event` | `{event_type, data}` |
| clip `interpolation` | 删除（已无夹具） | 出现即报「已移除」 |

### 4.1 稀疏保真算法（T1 核心）

旧语义（`script/KeyframeInterpolator.java:86` `interpolateChannel`）：每通道在**相邻两个全局关键帧**之间插值，缺失该字段的一端取**字段默认值**。因此：

1. 取该 clip 的全局关键帧时间集合 `T`（升序、范围内）。
2. 对每个对象路径 `p`：`S = {t ∈ T | 该帧写了 p}`；若 `S` 为空 → 不创建对象。
3. 若 `S` 非空 → 在**每个 `t ∈ T`** 写 `value(p, t) = 旧该帧值 ?? 字段默认`；这正是旧行为（缺失帧拉回默认）。
4. 无损压缩：仅对 `Interp.LINEAR` 且被删点严格落在相邻两点连线（`|v - lerp| <= 1e-6`）时删中间点；端点永不删；`ANGLE`/`GEOMETRIC`/`STEP`/`NULLABLE_FLOAT` 不压缩。
5. 位置特殊：`position` 走 §2.6 的世界位置语义；静态原点（无 `follow`、`position_origin` 为空或静态坐标）→ 逐轴展开 + 上述保真；动态原点（实体选择器且 `freeze=false`）→ 保语义（写 `position_origin` + `freeze=false`）并**报告人工复核**，不烘焙世界坐标。
6. 校验：旧值 `old(t)` 与新采样 `new(t)` 在 `T` + 中点网格上比较，容差：位置 1e-3 格、角度 0.01°、透明度/颜色/调色 1e-4、音量 1e-4、矩形/比例 1e-5。超差 → 插入修正帧；仍超差 → 报告。
7. 明确设计变更（不要求数值等价，单列报告）：`look_at` 动态目标、`yaw_base=entity/line`、morph 两侧效果、规则生命周期、`curve` 段归属。

---

## 五、缺口分类

### 5.1 本轮必须做

T1–T8 全部；§1.1 表内所有遗留。

### 5.2 移交后续轮次

| 项 | 归属 | 关闭条件 |
|---|---|---|
| 编辑器 UI（`screen_layers` 表单、对象/路径/规则编辑器、`demo.ts` 离线 schema） | Z 轮 | 编辑器源码回仓后按 `SchemaExporter` 同步 |
| 真实第三方模组复测（TACZ/SecurityCraft 类） | 未排期（实机批次） | 装上模组后在 `allowlist`/`normal` 两态确认 |
| Fabric 无整段 HUD 回调 → `allowlist` 降级 | 契约边界 | 已写入 `screen-layers.md` §5.5 |
| 未启动独立片段 `windowStart()=+∞` 的编辑器显示 | Z 轮 | 编辑器先判 `standalone()` |
| `H4-R3` 真实第三方 demo 模组 | 未排期 | 存在只依赖公开 API 的 demo 模组 |
| 票数行像素位置双端测量（H2-R9 已关闭，保留记录） | — | 已关闭 |

### 5.3 契约边界（写入对应专篇，不排期）

- `contentEnd` 只做显示/定位/统计，不作完成判据；完成看执行账本。
- 无限内容无自然完成点，不自动截断作者后续编排。
- `goto_clip` 只做分支启动，不重放 EVENT。
- OVERLAY 同轨单活跃层；`z_index` 仅加入时排序。
- `gradient.stops` value-only。
- 宏观循环区间只作定位，语义不变。
- 规则失败 fallback 只恢复执行事实，不回滚音频/覆盖层副作用。
- `ruleCommandPermissionLevel` 只由服务端配置决定。

---

## 六、验收命令与探针约定

- 增量编译：`sh gradlew :common:compileJava --offline --no-daemon`（每个波次结束由主代理统一跑一次）。
- 无头校验：`E:/tmp/icv`（现有 validator 驱动）→ 迁移后全量 252 支必须 0 issue。
- 迁移器自检：`tools/script-migrate` 自带 `--check`，输出 `PASS/FAIL` 行 + 汇总，失败退出码非 0。
- 派生/旧字段负例探针：`E:/tmp/i5-probe/`（不入库），`package` 与目标同包以访问包私有成员。
- 像素级同帧回归：`ic shot`（游戏内 F2）取帧；4 支循环夹具 + 新格式轨道脚本，Fabric/Forge 各一次；逐组 `ic stop` 清场后再 `ic play`。
- 运行目录：Forge 用 `IC_RUN_DIR=forge/run`；启动前 `jps -l` 确认无残留进程。

---

## 七、提交计划

1. `feat(0.3.6): I-5 脚本迁移器与全量夹具迁移`（T1 + 夹具）
2. `refactor(0.3.6): I-5 格式清理——删除 version/total_duration/旧关键帧载体`（T2 + T3 + T4）
3. `feat(0.3.6): I-5 独立片段渲染与派生范围消费者`（T5）
4. `refactor(0.3.6): I-5 spi 收敛与机械清理`（T6）
5. `docs(0.3.6): I-5 文档回写与验收记录`（T7 + T8）

---

## 八、实机验收记录（2026-11-06）

**装置**：`icdev` rig — Fabric dev 专用服 + 1 客户端，世界「新的世界」。
服务端脚本目录 = `E:/tmp/icdev/server/immersive_cinematics/scripts`（**不是** `fabric/run/...`；rig 的服端 run dir 在仓库外）。

| 项 | 结果 | 证据 |
|---|---|---|
| 独立片段分支（I4-R6） | **通过** | `tests/rules/test_standalone_camera_branch.json`：规则 `time@2s` + `goto_clip` 启动 `clips[]` 里的独立 CAMERA 片段。日志 `pass=test_standalone_camera_branch_cam0_clip0#0 cam=(0.000,200.000,0.000)` 证明**隐式轨道 cam0** 产出了该片段的相机 pass；判别帧（`position.y=200` / `pitch=-80`）画面变为夜空俯视 + 右下「长按 C 跳过动画」→ 相机**实际上屏** |
| 新格式脚本推送/自然结束 | **通过** | `已向 1 名玩家推送脚本: 独立片段相机分支`、`result=1`、`脚本播放停止 … (原因: FINISHED)` |
| 旧格式拒绝（服端残留脚本） | **通过** | `Failed to load script from h6_liveness_long.json: meta.version: 已移除…`（仓库外旧脚本，符合预期） |
| Forge 编译 | **通过** | `:forge:compileJava` BUILD SUCCESSFUL |

**未完成**：4 支循环夹具的像素级同帧新旧对比——旧格式运行时已删除，取旧基线需 checkout 历史 commit 另建 worktree 重构建；见 `I-BACKLOG.md` I5-R5。

**实机发现**：内联片段默认 `exit` 未被执行（`poll()` 只在 `branching` 时工作）→ `I5-R4`。

### 8.1 循环 / 多轨实机矩阵（2026-11-06，同一装置）

服务端副本剥掉 `triggers`（避免 5 支同时自动起播），逐支 RCON `icinematics play`：

| 用例 | 预期 | 实测 |
|---|---|---|
| `test_exit_loop_count`（有限 3 圈，跨度 6s） | 圈数耗尽自然结束 | `脚本播放停止 … (原因: FINISHED)`；相机租约 `lease0g1` 取/放各一次 |
| `test_exit_infinite`（无限循环） | 不自然结束 | 12s 内无 `FINISHED`，lane pass 持续；手动 `stop` 收尾 |
| `test_camera_loop_repeat`（无限循环，跨度 12s） | 跨过跨度仍继续，不结束 | 15s 内 80 个 `pass=..._cam0_clip0#0`；无 `FINISHED`；租约只取一次（同片段循环不换租约） |
| `test_camera_loop_pingpong`（无限循环） | 同上 | 15s 内 101 个 lane pass；无 `FINISHED`；0 异常 |
| `test_objects_multitrack`（6 轨：CAMERA/OVERLAY/LETTERBOX/ADJUST/AUDIO/EVENT） | 6s 自然结束，多租约代际 | `FINISHED`；租约到 `lease0g5`（片段切换 5 代）；相机 `(12.00, 64.0, -8.00)` |

全局异常扫描：本次启动仅 4 行 ERROR，全部为 dev 客户端 `Failed to verify authentication`（401，开发环境既有噪声），**无轨道/规则/相机相关异常**。

**边界**：矩阵验证的是「起播 / 循环持续 / 无限不结束 / 自然结束 / 多轨共存」的功能面；**像素级同帧新旧对比**未做（见 `I-BACKLOG.md` I5-R5）。

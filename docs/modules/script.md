> **过期文档（OUTDATED）——不得作为当前设计或实施依据**
>
> 本文未随最新设计更新，仅保留作历史参考。AI 与自动化工具不得据此推断当前格式、功能状态、接口或实施要求，也不得直接照搬其中示例用于新实现。
>
> 当前规划请先查阅 [0.3.6 计划状态索引](../../plans/0.3.6/README.md) 与 [I 轮实施基准](../../plans/0.3.6/I-script-core/README.md)，再按状态查阅对应未完成轮次。
>
> 计划目标不代表已经实现；核实当前代码行为时须结合源码，不得将旧文或未实现计划当作现状。
>
> 本次更新（2026-11-06，I-5）：按 `objects` 单格式重写本页事实；**横幅保留**——后续轮次（J 媒体、Z 编辑器）仍会改脚本栈，本页不作为权威。

# script（脚本系统）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/script/`

## 一、格式与解析

- ✅ `ScriptParser.parse(String)` → `CinematicScript`；Gson 树 API 手写解析，错误抛 `ScriptParser.ScriptParseException`（带 JSON 字段路径，**首错即抛**）。
- ✅ 顶层只有六类键：`meta` / `timeline` / 可选 `clips[]` / `rules[]` / `rule_bindings[]` / `paths[]`。
- ✅ `meta` 必填 `id`（`^[a-zA-Z0-9_]{1,32}$`）/ `name`（≤50）/ `author`（≤30）；**没有 `version`**。
- ✅ `timeline` 只承载 `tracks` 与宏观循环区间（`loop_start` / `loop_end`）；**没有 `total_duration`**。
- ✅ 片段白名单（`ObjectParser.CLIP_KEYS`）：`start_time` / `duration` / `objects` / `loop` / `loop_count` / `loop_mode` / `id` / `exit`；其余 clip 级键一律拒绝。
- ✅ 旧字段出现即报错：统一走未知字段白名单（`meta` / 片段 / `objects` 各一份），不再有专属"已移除"文案。被拒键包括 `meta.version`、`timeline.total_duration`、clip 级 `keyframes` / `curve` / `rules` / `interpolation`、`follow` / `follow_selector`、`facing_origin*`、`selector_*_follow` / `selector_*_facing_origin`、clip 级 `orient` / `yaw_offset` / `pitch_offset`、AUDIO 文件循环旧名 `loop`、`meta.hide_hud` / `hide_*` / `suppress_*` / `render_player_model` / `hud_layers` / `hard_hide_hud` / `screen_layer_mode`。
- ✅ 校验：`ScriptValidator.validate(json[, knownIds])` 收集多条 issue（parser 首错即抛，validator 汇总）。
- ✅ 触发器定义解析：`triggers[]`（含 `id`、`continuity`、`poll_interval`）。

## 二、数据模型

- ✅ `CinematicScript`：`meta` + `timeline` + `standaloneClips` + `ruleCollection` + `pathCollection` + 片段 id 索引；加载期派生 `isInfiniteContent()` / `hasFiniteContentEnd()` / `contentEnd()` / `hasFiniteDisplayEnd()` / `finiteDisplayEnd()`（只算 timeline 片段，独立片段只参与「是否无限」）。
- ✅ `ScriptMeta`：id/name/author/description/dimension/triggers/priority/`play_mode`/`skip_vote_ratio`/`base_resolution`/`screen_layers` + 运行时行为（`block_*`、`pause_when_game_paused`、`interruptible`、`skippable`、`hold_at_end`、`listener`、宏观循环）。画面层只有 `screen_layers` 一个入口，没有 `hide_*` / `suppress_*` 字段。
- ✅ `Timeline`：`tracks` + `loopStart` / `loopEnd`。
- ✅ `TimelineTrack`：`type` + `clips`。
- ✅ `Clip`：`startTime` / `duration` / `trackType` / `data` / `objects` / `clipId` / `exitPolicy`；`isEffectivelyInfinite()`（负时长或无限执行循环）、`getWindowEnd()`（无限时**抛异常**，调用方先判 `hasFiniteWindowEnd()`）。
- ✅ 对象层（`script/object/`）：`ClipObjects`（路径 → `SampledProperty`）、`SampledProperty`（`VALUE` / `ANIMATED` / `EVENT` 三形态，`compile(duration, infinite)`）、`ObjectKeyframe`、`ObjectCodec`、`ObjectParser`、`ObjectValidator`、`PropertyPath`。
- ✅ 采样层：`ClipSampler` / `SampledClip` / `ObjectSlotLayout`（路径 → 槽位）/ `ClipRuntimeCache`（按片段身份缓存）/ `ClipValueView`（只读 value-only）。
- ✅ 位置与朝向（`script/object/position/`）：`PositionSpec`（mode `absolute`/`relative` + space `world`/`base`）、`PointSourceSpec`、`FacingSpec`、`FreezeStore`、`PositionEvaluator`、`OrientationSpec` / `OrientationEvaluator` / `LookAtSpec` / `PathTangent`。
- ✅ 片段身份（`script/identity/`）：`ClipIdentity`、`ClipRef`、`ClipResolver`、`UsageIndex` / `UsageEdge` / `UsageKind`（放置 / 规则绑定 / 路径条目 / 分支目标 / 出口目标）。

## 三、Schema（唯一字段真源）

- ✅ `TrackSchemas`（各轨对象路径 / 类型 / 插值 / 默认值 / 枚举 / `requires` / `appliesTo`）→ `TrackTypeSchema` → `ObjectDef`；`MetaSchemas`、`TriggerSchemas` 同理。
- ✅ `SchemaRegistry` 提供查询；`SchemaExporter` 导出 JSON schema 给编辑器（Java 侧是唯一权威）。
- ✅ `TrackRegistry`：内置 7 轨 + 第三方注册（`TrackTypeId` / `TrackSchema` / `TrackPlayer`），首个脚本解析后冻结。

## 四、脚本级集合

- ✅ `clips[]`（独立片段）：`{id, track, duration, objects, exit?}`，无 `start_time`；由分支/出口启动后按 `track` 进入对应轨道（`ScriptPlayer` 的隐式轨道表）。
- ✅ `rules[]`：`{id, condition, actions, policy?}`；`rule_bindings[]`：`{id, rule, clip, gate:{at}, control?, schedule?, exit?}`（`gate.at` 必填）。
- ✅ 规则运行态（`script/rule/runtime/`）：`RuleRuntime`、`RuleActivation`、`RuleFactStore`、`SuspensionLedger`、`RuleActionRunner`、`PendingAction`。
- ✅ `paths[]`：`{id, clip, from, to, axes, geometry, timing}`；端点必须命中目标片段 `position.x/y/z` 节点时间并集且相邻（`PathCollectionParser`）；运行期 `PathBindingResolver`。
- ✅ 路径曲线：`PathCurveRegistry`（`line` + `bezier` / `bspline` / `catmull_rom` / `clothoid` / `fourier` / `hermite` / `nurbs` / `rational_*` 等注册类型）。

## 五、执行层

- ✅ `ClipExecutionLedger`：每实例一份；按执行身份维护 `localTime` / `windowStart` / `windowEnd` / `done` / `contentEnd` / `allDone()`；无限执行无有限末端。
- ✅ `ClipBranchController`：`launch(target, entryLocalTime, override)` / `poll()` / `fail(...)`；出口策略 `ClipExitResolver` + `ClipExitPolicyParser`（优先级：入口覆盖 > 片段默认 > `CONTINUE`）。
- ✅ 自然完成读执行账本（`allDone()`），不读任何声明时长；`contentEnd` 只作显示/定位/统计。

## 六、轨道播放器

| 轨道 | 类 | 数据来源 |
|---|---|---|
| CAMERA | `CameraTrackPlayer` + `ObjectCameraEvaluator` | `ClipSampler` + `PositionEvaluator` / `OrientationEvaluator`；lane 合成参数走 `CameraLaneComposite` |
| OVERLAY | `OverlayTrackPlayer` + `OverlayClipRuntime` | `SampledClip` + `ColorMaskSpec.forObjects`（渐变/遮罩结构 value-only） |
| LETTERBOX | `LetterboxTrackPlayer` | `SampledClip`（`aspect_ratio` / `opacity`） |
| AUDIO | `AudioTrackPlayer` + `CinematicAudioInstance` | `ClipValueView`（`sound` / `source` / `pitch` / `file_loop` / `fade_*` / `attenuation` / `position_mode` / `category`）+ `SampledClip`（`volume` / `x/y/z`）；每个活跃片段一个实例，实例自管解码 |
| ADJUST | `AdjustTrackPlayer` | `ColorAdjustSampler.sampleObjects`（59 通道） |
| EVENT | 服务端 `ScriptEventManager` + `EventClipRuntime` | `objects.event` 事件记录（客户端不建驱动） |
| MOD_EVENT | `ModEventTrackPlayer` | 格式与编译完整（`objects.mod_event`），**当前不消费**（占位，第三方以后可接） |

- ✅ 每渲染帧调度：`ScriptPlayer` 持有 `TrackDriver` 列表；`clipsForTrack(i)` 的索引空间 = timeline 轨道 + 隐式轨道（脚本级独立片段按 `track` 类型分组）。
- ✅ 画面 lane：`collectCameraLanes()` 按绘制顺序扁平列出各 CAMERA 轨本帧活跃 clip 的 `LaneFrame`；合成参数 `opacity` / `dest` / `source` / 遮罩由 `ScriptLaneDriver` 逐 lane 上屏。
- ✅ AUDIO 文件支持边界（`CinematicAudioInstance.decode`）：`source=minecraft` 读取资源包 OGG 后内存解码；文件源按扩展名分流——`.ogg` 走 stb_vorbis、`.wav` 走 `javax.sound`（仅 8/16 bit 位深），两者同步解码；`.mp3` / `.aac` / `.flac` / `.m4a` / `.opus` / `.wma` / `.oga` / `.ogx` 交给 `ExternalPcmDecode` → `Ffmpeg` 整段异步解码为 s16le / 48kHz / 立体声，未设素材时长阈值，但整段 PCM 驻留内存。其余扩展名、缺失文件或缺工具会构造失败，由 `AudioTrackPlayer.failedClips` 记一次并跳过该片段（不每帧重试；细节见 [media 模块](media.md)）。
- ✅ 异步解码期间实例 `isUsable()=false`：`play()` / seek 只记待办（`pendingPlay` / `pendingSeek`），PCM 就绪后由渲染线程下一帧 `ensurePlaying(localTime, volume)` 按当前片段局部时间定位起播（跳过加载耗时，不追帧）；解码失败置 `failed` 并限频写 `ErrorLog`，随后被 `AudioTrackPlayer` 回收。缺 `ffmpeg` 属配置错误，同样在构造期抛错并记为失败片段。
- ✅ 分类语义（`AudioTrackPlayer.startClipInstance`）：`category=music` 强制相对听者、无衰减、位置取玩家；`category=ambient` 走空间音源，`position_mode=relative` 时位置 = 当前听者位置（`AudioListenerController`，listener=camera 即镜头）+ `x/y/z` 偏移，`absolute` 时直接用偏移，衰减由 `attenuation` 决定；字段表见 [SCRIPT_FORMAT §4.3](../SCRIPT_FORMAT.md)。视频容器自带音轨不经过本轨，见 [overlay 模块](overlay.md)。

## 七、已知问题

- 内联 timeline 片段的**默认 `exit` 不会执行**：`ClipBranchController.poll()` 只在 `branching`（规则启动的分支）时工作，普通片段播完没人读出口策略 → `I-BACKLOG.md` I5-R4。
- 多人服务器下编辑器预览无法解析结构类目标（客户端无服务端访问）；服务端 `/icinematics play` 推送前替换为坐标，不受影响。
- `CinematicAudioInstance` 构造器仍对含非 ASCII 字符的文件名统一打警告。其中本地 `.ogg` 使用 `stb_vorbis_decode_filename` 的文件名入口，存在 Windows 路径编码风险；`source=minecraft` 走内存解码、`.wav` 走 `javax.sound`、扩展格式把路径作为 FFmpeg 进程参数，不经过该 C 文件名入口。不要将该警告泛化为所有格式都会因中文路径失败；本轮未做各格式的中文路径实机验收。
- `AUDIO` 的 `fade_in + fade_out > duration` 现在是**校验期错误**（`ObjectValidator`），不再静默跳过。

> 本章媒体/音频条目的事实基线：源码 HEAD `4b49c68`（J 轮媒体已合入）。本轮只做源码对照同步，未做游戏内实机验收；页面顶部的历史警告仍然有效。

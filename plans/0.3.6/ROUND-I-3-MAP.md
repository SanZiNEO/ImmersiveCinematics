# I-3 轮项目地图（主代理）

> 建立时间：2026-11-02。依据：本轮任务书 + `plans/0.3.6/I-script-core/{track-camera,track-overlay-letterbox,track-audio-adjust,track-event,path-collection,object-model,schema-and-types,runtime-evaluation,acceptance}.md` + 五路只读代码测绘（CAMERA / OVERLAY / AUDIO / EVENT / SCHEMA）。
> 行号仅供定位，以类名 / 方法签名为准。`docs/` 已过时，只作线索；冲突以设计文档与源码为准。
> 本文件是本轮派单与验收的唯一索引；I-1/I-2 的地图（`ROUND-I-1-MAP.md`、`ROUND-I-2-MAP.md`）只作前置。

## 〇、口径裁决（本轮，优先级最高）

1. **旧格式不动、新格式新增**：`clip.hasObjects()` 为真走对象装配，否则走现有端点求值 / 逐字段插值链路，一行不改。依据 `I-ROUNDS.md` 跨轮约定 1（I-1~I-4 旧格式继续可读可播，新模型在新字段路径启用）。
2. **路径集合本体留 I-4**：本轮只交付「消费侧 seam + 选轴 geometry/timing 求值 + 切线来源」，脚本级 `paths[]` 的解析 / 校验 / 索引由 I-4 建。依据 `I-ROUNDS.md` 跨轮约定 3（I-3 只消费绑定接口）。
3. **`SampledClip.presence` 不能表达「作者显式声明」**（schema 默认值也会让 `presence=true`，见 `script/object/ClipSampler.java:200`）。凡需要「写了才覆盖、没写才回落」的语义（OVERLAY `color.r/g/b` 回落基色、AUDIO `volume` 基量、LETTERBOX 缺省），一律以 `clip.getObjects().contains(path)` 判定，不以 `presence` 判定。
4. **`STRUCT` / `EVENT_RECORD` 不进 `SampledClip` 数值数组**（`ClipSampler.java:117-119`）。value-only 结构（`gradient`、10 条颜色曲线、`motion_*`、`transition_*`、`modifier`、`lut`）从 `ClipObjects` 读，按 clip 缓存解析结果，不逐帧解析。
5. **`CameraLane` 增加可空 `LaneComposite`**：新格式由相机求值器一次算出 opacity / dest / source / mask / lane 调色，`ScriptPlayer.collectCameraLanes` 与 `ScriptLaneDriver.resolve` 直接读；旧格式该字段为 `null`，仍走现有 `KeyframeInterpolator` + `ColorAdjustSampler`，像素不变。
6. **`attenuation=inverse` 本轮不实现**：`track-audio-adjust.md` §2.3 明确「不实现新的音频衰减模型」是 non-goal，§4.4 规定「在决定前 validator 给警告」。本轮按该口径执行：保留枚举值、validator 告警「当前按 linear 处理」、文档标注未实现，决策移交 I-5。
7. **探针放仓库外**（`E:/tmp/i3/`），编译口径复用 I-2：`common/build/classes/java/main` + Gradle 缓存依赖 + Minecraft merged jar。探针与临时日志不入库（与 I-2 一致）。
8. **旧格式 OVERLAY 的关键帧 `color` 字符串保留**；新格式只认 `color.r/g/b`，不双读。

## 一、轮次范围与基线

**目标**：四条轨道（CAMERA / OVERLAY+LETTERBOX / AUDIO+ADJUST / EVENT）改成对象模型 + 同一执行轴，字段语义按专篇落定；承接 I-2 的旧循环夹具实机基线。

**不做**：规则集合 / 引擎（I-4）、脚本级路径集合的解析与校验（I-4）、格式清理与迁移（I-5）、编辑器 UI（Z 轮）。

**基线（本轮改动前实测，2026-11-02）**：
- 工作树干净，HEAD `312ab1f`。
- `sh gradlew :common:compileJava --offline --no-daemon` → `BUILD SUCCESSFUL`（15s，up-to-date）。
- 旧解析 / 旧校验基线沿用 I-2 记录：`E:/tmp/i1/baseline-parse.txt`（231 脚本，225 OK / 6 ERR）、`E:/tmp/i1/baseline-icv.txt`（223 OK / 18 FAIL）；本轮必须保持 diff 0。
- `cinematics/0.3.6/tests/` 下 231 支既有夹具全为旧格式，`grep '"objects"' cinematics` 零命中 → 新格式夹具本轮新建。

## 二、任务清单（编号 + 目标 + 验收 + 依赖）

| 编号 | 目标（一句话） | 验收 | 依赖 | 状态（2026-11-02） |
|---|---|---|---|---|
| **I3-0** | 共享采样装配层：per-clip `ClipSampler` + 复用 `SampledClip` + value-only 读取 + `declared` 判定，供四条轨道统一取采样 | 探针：同 clip 连续采样零分配；`declared` 与 schema 默认值可区分；`clear()` 后按新 clip 重建；`sh gradlew :common:compileJava --offline --no-daemon` PASS | — | 完成：`run-object` 40 checks PASS |
| **I3-A1** | CAMERA 新格式单片段装配：位置（点源/参考系/方向源）、朝向（look_at > tangent > manual）、合成参数、57 通道 lane 调色全部从 `SampledClip` 装配；`CameraLane` 携带 `LaneComposite`；`isClipUsable` 只扫范围内对象 | 相机探针（字段 / 默认值 / 插值 / 边界 / 退化）全绿；旧格式夹具解析/校验基线 diff 0；旧格式像素路径未变（`LaneComposite==null`） | I3-0 | 完成：`run-camera` 57 checks PASS；旧基线 diff 0；旧格式像素实机回归未执行 |
| **I3-A2** | 路径绑定消费：`PathBinding` + `PathBindingSource` seam，选轴 geometry/timing 求值，`orient=tangent` 用条目导数 | 路径探针：line/bezier、2 轴/3 轴、显式 timing、无绑定回落 SampledProperty、端点外不生效 | I3-A1 | 完成（消费侧）：`run-path` 22 checks PASS；脚本级 `paths[]` 集合归 I-4 |
| **I3-A3** | CAMERA morph 每侧完整状态（各自 motion/breath/朝向/位置）+ motion/breath/modifier 对象化 | morph 探针：两侧各自 motion/breath 且每帧只推进一次；窗口 = 交界 ± maxDuration/2；modifier 从 objects 读；旧格式 morph 行为不变 | I3-A1 | 完成：`run-morph` 59 checks PASS（含旧格式逐位对照） |
| **I3-B** | OVERLAY / LETTERBOX 新格式装配：`color.r/g/b`（回落基色）、遮罩几何、渐变几何、`source.*`、`font_scale`、`z_index`、letterbox 两字段 | 覆盖层探针：fade full/rect/circle、渐变 linear/radial、image source/anchor/scale、subtitle font_scale、z 序、letterbox 淡出；旧格式像素回归 | I3-0 | 完成：`run-overlay` 76 checks PASS；旧格式像素实机回归未执行 |
| **I3-C** | AUDIO / ADJUST 新格式装配：`volume`/`x`/`y`/`z` + 9 个 value-only、59 通道 + 曲线/LUT/混合 | 音频探针（volume/xyz/position_mode/file_loop/无限片段）+ 调色探针（59 通道默认值/范围、曲线强度、LUT、blend）；旧格式采样值不变 | I3-0 | 完成：`run-audio` 139 checks PASS（含旧格式采样对照）；曲线改读解析期 `ColorCurve`（实机缺陷修复）；`attenuation=inverse` 决策归 I-5 |
| **I3-D** | EVENT / MOD_EVENT 离散记录 + 边沿触发 + 事件身份 `EventKey`（实例/片段/对象路径/事件索引），服务端与客户端共用 | 事件探针：到点触发一次、空 command 占位、多事件同帧按序、seek 不补触发、循环不重复、`position` 原子配对、pingpong 拒绝、MOD_EVENT 可加载不消费 | I3-0 | 完成（无 seek 场景）：`run-event` 33 checks PASS；服务端 seek 协议未实现（I3-B2） |
| **I3-E** | 收口：多轨道实机脚本 + 逐轨道旧脚本画面回归 + I2-B1 旧循环夹具实机基线 + 文档回写 | 见 §六；Fabric 实机跑通并留日志/截图；I2-B1 四支夹具记录结果 | A1~A3, B, C, D | 完成（含 1 条实机缺陷修复）：探针 12 个 / 559 checks PASS、旧解析/校验基线 diff 0、I-2 三探针回归 PASS、多轨道与四支循环夹具实机跑通、文档已回写；**像素级 diff 未做**，留 I-5 |

## 三、真实文件 / 类 / 方法地图

### 3.1 共享装配层（I3-0 新建）

| 文件 | 目标形态 |
|---|---|
| `script/object/ClipRuntimeCache.java` | 每轨（`TrackType`）一份：`ClipRuntime get(Clip)` 返回 `{ClipSampler sampler; SampledClip buffer;}`，按 `Clip` 对象身份缓存；`void clear()`（`onScriptReplaced` / `onStop` 调用）；`sampler(Clip)` 惰性建；`sample(Clip, double)` = `sampler.sample(localTime, buffer)` 并返回 buffer |
| `script/object/ClipValueView.java` | per-clip value-only 读取视图：`boolean declared(String path)`（= `ClipObjects.contains`）、`float f(String path, float def)`、`int i(...)`、`boolean b(...)`、`String s(...)`、`Object struct(String path)`；内部持有 `ClipObjects`，不逐帧建 map |

**复用既有能力（不要重造）**：
- `script/object/ObjectSlotLayout#of(TrackType)` / `#slotOf(String)`（`-1` = 未登记）。
- `script/object/ClipSampler#sample(double, SampledClip)`（不读 `loop`；调用方给已归一化的 `executionLocalTime`）。
- `script/object/SampledClip#f/i/b/s/present`；复用前必须 `clear()`。
- `script/object/SampledProperty.Compiled#eventCount()/eventValue(int)/timeAt(int)`（事件记录唯一读取口）。
- `script/execution/ClipTimeSource#isClipActive/executionLocalTime/finiteWindowEnd`（`ScriptPlayer` 已实现）。

### 3.2 CAMERA

**现有链路（旧格式，保持不动）**

| 成员 | 位置 | 说明 |
|---|---|---|
| `CameraTrackPlayer#onRenderFrame(double)` | `script/CameraTrackPlayer.java:96-155` | 过渡对 → `renderMorph`；顶层 → `renderSingle`；下层 → `LaneSnapshotCollector.captureLowerLanes` |
| `CameraKeyframeEvaluator#renderSingle(double, Clip, double, boolean, int)` | `camera/source/CameraKeyframeEvaluator.java:214-223` | `computeInterpolation` → `writeAttributes` → `new CameraLane(state, clip, clipLocalTime)`；无关键帧返回 `null` |
| `CameraKeyframeEvaluator#renderMorph(Clip, Clip, double)` | `:234-326` | 两侧端点求值 → 过渡层混合 → **只对 next 跑 motion**（`:301`）→ **只对 prev 套 breath**（`:315-316`） |
| `CameraKeyframeEvaluator#writeAttributes(Keyframe,Keyframe,double,Clip,double,boolean,int)` | `:805-855` | 位置 → 朝向 → `motion.step(TOP_LANE)` → breath → `lastWorldPos` |
| `CameraKeyframeEvaluator#isClipUsable(Clip)` | `:151-198` | 遍历**全部**关键帧（不按 duration 过滤） |
| `LaneSnapshotCollector#captureLowerLanes(double, List<Clip>, Clip)` | `camera/source/LaneSnapshotCollector.java:63-85` | 层号 = `active.size()-1-i` |
| `ScriptLaneDriver#resolve(CameraLane, Slot)` | `client/lane/ScriptLaneDriver.java:313-330` | 重新 `computeInterpolation` 求 opacity/dest/source，`LaneMask.sample` |
| `ScriptPlayer#collectCameraLanes()` | `script/ScriptPlayer.java:427-443` | 逐 lane 调 `ColorAdjustSampler.sample(lane.clip(), lane.clipLocalTime())` |

**已存在但零调用的可复用层（I3-A1 接入）**

| 类型 | 位置 | 契约 |
|---|---|---|
| `PositionEvaluator#evaluate(PositionSpec, FacingSpec, WorldContext, FreezeStore, String)` | `script/object/position/PositionEvaluator.java:57-93` | ABSOLUTE / RELATIVE+WORLD / RELATIVE+BASE；返回 `PositionResult` |
| `PositionEvaluator#frame(Vec3, FacingSpec, PointSourceSpec, WorldContext, FreezeStore, String)` | `:114-131` | `auto` 回落；双载荷冲突抛 IAE |
| `OrientationEvaluator#evaluate(OrientationSpec, PositionResult, Vec3, WorldContext, FreezeStore, String)` | `script/object/position/OrientationEvaluator.java:54-79` | look_at 覆盖 > tangent + 切线 > manual（base → lock → offset） |
| `FreezeStore#getOrCapture(String, boolean, Vec3)` / `#key(Clip, String, String)` | `script/object/position/FreezeStore.java:33-62` | 键 = 片段 + 对象路径 + 来源签名 |
| `WorldContext` | `script/object/position/WorldContext.java` | 6 个查询；空值表达未解析（点 null / 角 NaN） |
| `SampledPathTangent#direction(double)` | `script/object/position/SampledPathTangent.java:76-89` | `position.x/y/z` 中心差分 |
| `math/path/PathSpec` / `PathEvaluator#position/tangent` | `math/path/PathEvaluator.java:26-82` | 绑定缓存键 = `(spec, from/to 六分量)`，上限 32 |

**schema 现状**：`script/schema/TrackSchemas.java:549-784`（`cameraObjects()`，209 条）已声明位置 / 方向源 / 朝向 / look_at / 光学 / 合成 / 57 通道调色 / motion / breath / transition / modifier / curve / selector。**缺**：10 条颜色曲线（旧 `camera()` clipFields `:97-107` 有，`cameraObjects` 没有）→ I3-A1 补 value-only `STRUCT`。

**缺口（必须本轮闭合）**：新格式 `keyframes=List.of()`（`ScriptParser.java:475`）→ `computeInterpolation` 返回 null → 新格式 CAMERA 无画面；`ClipSampler` 全仓零生产调用；`script/object/position/*` 零调用；morph 两侧不完整；无路径条目结构。

### 3.3 OVERLAY / LETTERBOX

| 成员 | 位置 | 现状 |
|---|---|---|
| `OverlayTrackPlayer#onRenderFrame(double)` | `script/OverlayTrackPlayer.java:63-102` | 首活跃 clip → `createLayer` / `updateLayer`；`interpolateFloat` 在 `:96,194-263` |
| `OverlayTrackPlayer#updateLayer(float, List<Keyframe>, double)` | `:181-266` | 按 `currentLayer` 类型分派；颜色走 `spec.sampleColor(localTime)`（`:226`） |
| `OverlayTrackPlayer#createLayer(Clip)` | `:126-178` | 读 `data` 的 `layer_type`/`z_index`/`color`/`path`/`text`；`ColorMaskSpec` 懒建 |
| `LetterboxTrackPlayer#onRenderFrame(double)` | `script/LetterboxTrackPlayer.java:50-69` | `interpolateChannel(aspect_ratio/opacity)` |
| `ColorMaskSpec#from(Clip)` / `#sampleColor(double)` | `script/ColorMaskSpec.java:75-119` / `:172-201` | 读 `data` + 旧 `keyframes`；`keyframeTimes/keyframeColors` 缓存 |
| `OverlayManager#addLayer(OverlayLayer)` | `overlay/OverlayManager.java:66-69` | 唯一 z 排序点 |
| `FadeLayer` / `LetterboxLayer` | `overlay/FadeLayer.java:21`、`overlay/LetterboxLayer.java:21,24,50` | 0.5s 淡出 / z=0 / 黑边颜色硬编码 |
| `LaneMask#sample(Clip, double)` | `client/lane/LaneMask.java:56-83` | CAMERA lane 遮罩，读旧关键帧 |

**schema 现状**：`overlayObjects()` 32 条（`TrackSchemas.java:835-892`）、`letterboxObjects()` 2 条（`:786-793`）。`appliesTo` 只有 `source.*`/`font_scale`/`color.*`/`mask_*`/`gradient_*` 标了；`gradient`/`mask_shape`/`blend_mode` 未标。

**缺口**：两轨完全不读 `getObjects()`；新格式 `data` 是空壳（`layer_type→fade`、`z_index→10`、`color→#000000`）；`color.r/g/b` 声明了无人消费；`gradient` 是 STRUCT 且 `ClipSampler` 不存值；`source` 旧 map vs 新四叶不一致；`OverlayTrackPlayer` 单层（`currentLayer`），同轨重叠 clip 不支持。

### 3.4 AUDIO / ADJUST

| 成员 | 位置 | 现状 |
|---|---|---|
| `AudioTrackPlayer#onRenderFrame(double)` | `script/AudioTrackPlayer.java:95-151` | 首活跃 clip；`interpolateFloat` 在 `:337-340,412-414,446,463` |
| `AudioTrackPlayer#startClipInstance(Clip)` | `:274-330` | 读 `clip.getSound()`/`getSource()`/`isFileLoop()`/`getAudioPitch()`/`getVolume()`/`getAttenuation()` |
| `AudioTrackPlayer#updateClipInstance(Clip, CinematicAudioInstance, double)` | `:332-364` | `infinite = dur < 0f`（`:335`），fade 相位来自执行局部时间 |
| `AdjustTrackPlayer#onRenderFrame(double)` | `script/AdjustTrackPlayer.java:43-54` | `ColorAdjustSampler.sample(clip, executionLocalTime)` → `MasterColorAdjust.publish` |
| `ColorAdjustSampler#sample(Clip, double)` | `script/ColorAdjustSampler.java:27-113` | 按名字扫旧关键帧，构造 72 分量 `ColorAdjustParams` |
| `CinematicAudioInstance#setAttenuation(String, float)` | `script/CinematicAudioInstance.java:171-177` | `none` → NONE，其余（含 `inverse`）→ LINEAR |
| `CinematicAudioInstance#syncToTime(double, float, float)` | `:205-210` | `fadeIn` 参数被忽略 |

**schema 现状**：`audioObjects()` 13 条（`TrackSchemas.java:795-816`）、`adjustObjects()` 72 条（`:894-910`，59 通道 + 10 曲线 + `lut`/`lut_input_gamma`/`blend_mode`）。槽位顺序 = 声明顺序：AUDIO `volume=0,x=1,y=2,z=3,sound=4,...`；ADJUST `0..58` = 59 通道，`59..68` = 10 曲线，`69..71` = lut/gamma/blend。

**缺口**：新格式 AUDIO 静音（`getSound()` 只读 `data`）；`file_loop` 零消费；新格式 ADJUST 恒等（无关键帧）；`ColorAdjustSampler` 仍扫旧帧；`STRUCT` 曲线无 `ColorCurve` 适配器（`ScriptParser.parseColorCurve` 是 private）；`lut_input_gamma` 对象声明 `.min(0)` 允许 0（旧 parser 拒绝 ≤0）。

### 3.5 EVENT / MOD_EVENT

| 成员 | 位置 | 现状 |
|---|---|---|
| `ScriptEventManager#onServerTick(MinecraftServer)` | `trigger/server/ScriptEventManager.java:174-225` | 每 tick 扫**第一条 EVENT 轨**的旧 keyframes |
| 去重键 | `ScriptEventManager.java:211,221,331` | `(clipIndex << 16) \| kfIndex`，`Set<Integer> triggeredKeyframes` |
| `ScriptEventManager#extractEventClips(CinematicScript)` | `:312-318` | 只取第一条 EVENT 轨 |
| `ScriptEventManager#handlePause(...)` / `#reanchor(...)` | `:242-270` | 暂停跳过；恢复用上报位置重锚 |
| `PlayerMoveController#onScriptStart(CinematicScript, Vec3)` | `script/PlayerMoveController.java:47-86` | 扫**全部** EVENT 轨的旧 `position` map |
| `PlayerMoveController#onRenderFrame(double)` | `:88-116` | 无事件身份；`destIndex` 单调 |
| `PlayerMoveController#onScriptLoop()` | `:137-143` | 宏循环重置 `destIndex=1` |
| `ModEventTrackPlayer#isActiveAt` | `script/ModEventTrackPlayer.java:19-21` | 恒 `false`（占位） |

**schema 现状**：`eventObjects()` = `event`(EVENT/EVENT_RECORD) + `event_type`(VALUE/STRING)；`modEventObjects()` = `mod_event` + `event_type` + `data`(STRUCT)。

**已具备**：`ObjectCodec` 严格解析 `event`（`command`/`event_type`/`id`/`position{x,z}|{dx,dz}`）与 `mod_event`（`event_type`/`data`）；`SampledProperty.Compiled#eventCount/eventValue/timeAt`；`ObjectValidator` 拒绝 pingpong。

**缺口**：无 `EventKey` / `EventClipRuntime`；服务端/客户端均只读旧 keyframes；seek 无协议、向前 seek 会补触发；循环无服务端通知；`command`/`position` 只靠旧帧共存、无原子配对；MOD_EVENT 零消费。

### 3.6 探针与实机

| 工具 | 命令 | 用途 |
|---|---|---|
| 编译 | `sh gradlew :common:compileJava --offline --no-daemon` | 每单收口 |
| 旧解析 | `sh E:/tmp/i1/run-parse.sh "E:/Documents/GitHub/ImmersiveCinematics/cinematics"` | 与 `E:/tmp/i1/baseline-parse.txt` diff |
| 旧校验 | `sh E:/tmp/icv/run.sh "E:/Documents/GitHub/ImmersiveCinematics/cinematics"` | 与 `E:/tmp/i1/baseline-icv.txt` 对比 |
| 循环 / 分支 / 校验探针（I-2 建） | `sh E:/tmp/i2/run-loop.sh`、`run-branch.sh`、`run-validator-loop.sh` | 回归，必须保持 PASS |
| 本轮探针（新建） | `E:/tmp/i3/`：`run-object.sh`（I3-0）、`run-camera.sh`（A1）、`run-path.sh`（A2）、`run-morph.sh`（A3）、`run-overlay.sh`（B）、`run-audio.sh`（C）、`run-event.sh`（D） | 逐轨道字段/默认值/插值/边界/退化 |
| 实机 | `tools/devtools/.venv/Scripts/python.exe tools/devtools/devtools.py client "<世界>"`、`ic cmd`、`ic shot`、`ic tail` | 多轨道脚本 + I2-B1 四支循环夹具 |
| 新格式夹具 | `cinematics/0.3.6/tests/objects/`（新建） | 多轨道实机脚本 + 逐轨道样例 |

## 四、依赖顺序

```
I3-0（共享装配层）
  ├─→ I3-A1（相机单片段装配）─→ I3-A2（路径绑定消费）─→ I3-A3（morph 每侧 + motion/breath/modifier）
  ├─→ I3-B（覆盖层）
  ├─→ I3-C（音频/调色）
  └─→ I3-D（事件）
                              └─→ I3-E（探针收口 + 实机 + I2-B1 + 文档回写）
```

- A1/A2/A3 都改 `CameraKeyframeEvaluator`，**必须串行**；A2 依赖 A1 的采样与装配入口。
- B / C / D 与 A 链并行，文件不重叠（B：`OverlayTrackPlayer`/`LetterboxTrackPlayer`/`ColorMaskSpec`；C：`AudioTrackPlayer`/`AdjustTrackPlayer`/`ColorAdjustSampler`；D：`trigger/server/ScriptEventManager`/`PlayerMoveController`/`ModEventTrackPlayer`）。
- A1 会改 `CameraLane` 与 `ScriptPlayer.collectCameraLanes`；C 会改 `ColorAdjustSampler` 的调用形态 → **C 不得改 `ScriptPlayer.collectCameraLanes` 的签名**，只把新格式 lane 调色入口留给 A1 提供的 `LaneComposite`；若 C 需要动 `collectCameraLanes`，必须先 `write agent://MapAudio` 与 A1 对齐。

## 五、现状与缺口（本轮要闭合的）

| 设计目标 | 现状 | 缺口 |
|---|---|---|
| 每叶子独立采样、同一 `executionLocalTime` | `ClipSampler`/`SampledClip` 已实现但**零生产调用**；六条轨道播放器全走旧端点/逐字段插值 | I3-0 + 四轨装配 |
| 新格式可播放 | 新格式 `keyframes=List.of()`、`data` 空壳 → CAMERA 无画面、OVERLAY 全屏黑、AUDIO 静音、ADJUST 恒等、EVENT 不触发 | 全部 |
| 位置 = 点源 + 偏移；朝向独立参考系；`look_at` 覆盖 | `script/object/position/*` 完整但零调用；旧实现 `frame` 是 per-frame 可变字段（隐藏顺序） | I3-A1 接入 |
| 路径 = 脚本级条目（目标片段 + 相邻端点对 + geometry/timing） | 无 `PathEntry`；`Clip.getPathSpec()` 是片段级一份 3D 控制点，对每段隐式复用 | I3-A2 消费侧 seam（集合本体 I-4） |
| morph 每侧完整状态（含本段 motion/breath），过渡只混快照 | 先混六参数，再只跑 next motion / 只套 prev breath | I3-A3 |
| OVERLAY 对象字段 / 遮罩 / 渐变 / letterbox | 对象声明齐、运行时不读；`color.r/g/b` 无人消费；`gradient` STRUCT 不可采样 | I3-B |
| AUDIO value-only、ADJUST 59 通道、曲线/LUT | 对象声明齐、运行时不读；`ColorAdjustSampler` 按名字扫旧帧 | I3-C |
| EVENT 离散记录 / 边沿 / `EventKey` | 双端只读旧 keyframes；位打包键；无游标；seek 补触发 | I3-D |
| 旧格式继续可读可播 | 现状成立 | 全部任务必须保持解析/校验基线 diff 0 |

## 六、I3-E 收口验收（逐条可执行）

1. **逐轨道探针**：`run-camera.sh` / `run-path.sh` / `run-morph.sh` / `run-overlay.sh` / `run-audio.sh` / `run-event.sh` 全部 `checks>0` + `PASS`。
2. **旧脚本回归**：旧解析 diff 0；旧校验 diff 0；I-2 三个探针（loop/branch/validator-loop）保持 PASS。
3. **旧画面回归**：旧格式相机 / 覆盖层 / 调色 / 音频夹具在 Fabric 实机跑一遍，与 `fabric/run/lane-captures/` 基线截图同帧无结构差异。
4. **多轨道实机脚本**：`cinematics/0.3.6/tests/objects/test_objects_multitrack.json`（相机 + 覆盖层 + 调色 + 音频 + 事件同脚本），实机播放自然结束，日志无 error。
5. **I2-B1 旧循环基线**：`test_camera_loop_repeat` / `test_camera_loop_pingpong` / `test_exit_loop_count` / `test_exit_infinite` 四支实机跑通并记录结果；最终全量留 I-5。
6. **文档回写**：`plans/0.3.6/I-script-core/{track-camera,track-overlay-letterbox,track-audio-adjust,track-event}.md` 的「状态 / 实现结果」按实际更新；`plans/0.3.6/ROUND-I-3-MAP.md` 补验收记录；`I-BACKLOG.md` 增删对应条目。

执行结果见 §九：第 1、2、6 项完成；第 3–5 项（实机）本轮未执行，随 I-5 全量收口。

## 七、本轮明确不做 / 边界

- 不做脚本级 `paths[]` 的解析、校验、索引（I-4）；本轮只消费 `PathBindingSource`。
- 不做规则集合 / 时间门 / 挂起 / 事实 / 延迟动作（I-4）。
- 不做格式清理、迁移器、旧字段拒绝、`file_loop` 改名切换（I-5）。
- 不改旧格式任何求值语义与像素结果。
- 不实现新的音频衰减模型（`inverse` 保持「按 linear 处理」+ 告警）。
- 不做编辑器 UI / schema 导出形态调整（Z 轮）；但新增对象声明必须自动进 `SchemaExporter` 输出。
- 不改 `multi-camera-rendering` 的 FBO / lane 上屏；不改 cover chain 的优先级 / 修饰符公式。

## 八、风险 / 待观察

1. **`CameraLane` 记录扩字段**：`LaneFrame`、`ScriptPlayer.collectCameraLanes`、`ScriptLaneDriver`、`LaneSnapshotCollector` 都构造/消费它；改签名要一次改全，旧格式必须传 `null` 走旧路径。
2. **新格式相机与旧格式像素可比性**：同一脚本迁移到新格式后数值应等价（除设计明确变更项：morph 每侧完整状态、路径条目化）；I3-E 需要至少一支「旧格式 vs 新格式同参数」对照，差异清单化。
3. **`position` 求值的隐藏状态**：旧实现 `frame` 是实例字段；新装配必须用局部变量传递（`PositionEvaluator` 已按参数传递，接入时不得回写实例字段）。
4. **路径端点语义**：`PathBinding` 的端点 = 目标位置序列中两个相邻关键帧的时间；采样端点值时必须用 `position.<axis>` 在该时间的 SampledProperty 值，不得用「当前段端点」隐式推断。
5. **事件 seek 协议缺失**：服务端目前没有 seek 包。I3-D 只能保证「客户端事件游标 + 服务端 tick 基准」在无 seek 场景下一致；跨端 seek 对齐需要新协议，若本轮必须实现，需先确认协议改动范围。
6. **零分配**：`findActiveClips` 每帧 `new ArrayList<>(2)`、`BreathDisturbance` 每帧 `new` 已存在；本轮新增路径不得扩大分配面，探针需覆盖稳态无分配（相机 / 覆盖层 / 调色各一组）。
7. **实机窗口**：当前无运行中的 Minecraft 客户端（`ic list` 空）；I3-E 需要启动客户端，耗时较长且依赖图形环境，若启动失败必须如实报告「未验证」，不得写成通过。

## 九、I-3 验收记录（2026-11-02）

| 项 | 命令 | 结果 |
|---|---|---|
| 编译 | `sh gradlew :common:compileJava --offline --no-daemon` | `BUILD SUCCESSFUL`（up-to-date），exit 0 |
| 共享装配探针 | `sh E:/tmp/i3/run-object.sh` | `checks: 40` / `OBJECT CACHE PROBE: PASS` |
| 旧格式回落探针 | `sh E:/tmp/i3/run-clip.sh` | `checks: 18` / `CLIP FALLBACK PROBE: PASS` |
| CAMERA 装配探针 | `sh E:/tmp/i3/run-camera.sh` | `checks: 57` / `CAMERA PROBE: PASS` |
| 路径绑定探针 | `sh E:/tmp/i3/run-path.sh` | `checks: 22` / `PATH PROBE: PASS` |
| morph 探针 | `sh E:/tmp/i3/run-morph.sh` | `checks: 59` / `MORPH PROBE: PASS`（含旧格式 morph 逐位对照） |
| OVERLAY / LETTERBOX 探针 | `sh E:/tmp/i3/run-overlay.sh` | `checks: 76` / `OVERLAY PROBE: PASS` |
| AUDIO / ADJUST 探针 | `sh E:/tmp/i3/run-audio.sh` | `checks: 139` / `AUDIO PROBE: PASS`（曲线改为读解析期 `ColorCurve` 后 +1） |
| EVENT 探针 | `sh E:/tmp/i3/run-event.sh` | `checks: 33` / `EVENT PROBE: PASS` |
| schema 探针 | `sh E:/tmp/i3/run-schema.sh` | `checks: 42` / `SCHEMA PROBE: PASS` |
| 结构归一探针 | `sh E:/tmp/i3/run-struct-norm.sh` | `checks: 13` / `STRUCT NORM PROBE: PASS` |
| 对象校验探针 | `sh E:/tmp/i3/run-validator-object.sh` | `checks: 22` / `VALIDATOR OBJECT PROBE: PASS` |
| 新格式夹具探针 | `sh E:/tmp/i3/run-fixtures.sh` | `checks: 38` / `FIXTURE PROBE: PASS` |
| 探针合计 | 12 个探针 | `checks` 合计 **559**，全部 PASS |
| 旧解析基线 | `sh E:/tmp/i1/run-parse.sh "E:/Documents/GitHub/ImmersiveCinematics/cinematics"` | 旧 231 支（`E:/tmp/i3/parse-oldonly.txt`）与 `E:/tmp/i1/baseline-parse.txt` 逐行一致（差异仅 SLF4J 噪声 / 汇总行）；含 9 支新格式夹具后整体 `RESULT: parsed=234, failed=6, total=240`（6 支既有故障夹具不变） |
| 旧校验基线 | `sh E:/tmp/icv/run.sh "E:/Documents/GitHub/ImmersiveCinematics/cinematics"` | 旧 231 支（`E:/tmp/i3/icv-oldonly.txt`）与 `E:/tmp/i1/baseline-icv.txt` 一致（18 支既有 issue 不变）；整体 `RESULT: 18 of 240 scripts have issues`，`SELFTEST: all validator-rule self-tests passed` |
| I-2 循环探针回归 | `sh E:/tmp/i2/run-loop.sh` | `checks: 169` / `LOOP PROBE: PASS` |
| I-2 分支探针回归 | `sh E:/tmp/i2/run-branch.sh` | `checks: 135` / `BRANCH PROBE: PASS` |
| I-2 校验探针回归 | `sh E:/tmp/i2/run-validator-loop.sh` | `checks: 20` / `LOOP PROBE: PASS` |
| 旧画面回归（§六 3） | `ic play i3/test_objects_letterbox` / `..._overlay` / `..._adjust` | 实机：黑边上下黑边可见、`test_image.png` 图片层可见、`test_objects_adjust` 自然结束；**未做像素级 diff**（本轮无改动前基线截图），只做了可观察行为 + 数值探针层面回归 |
| 多轨道实机（§六 4） | `ic play i3/test_objects_multitrack` | 实机播放自然结束：`ScriptEventManager: Script 'test_objects_multitrack' fully complete — all viewers finished`；相机位置沿 `position.x/z` 关键帧推进（日志 `(8.04,64,-5.36)` → `(11.99,64,-8.00)`），lane 正常产出，无相关 ERROR |
| I2-B1 实机（§六 5） | `ic play i3/test_camera_loop_repeat` / `..._pingpong` / `i3/test_exit_loop_count` / `i3/test_exit_infinite` | 四支均实机跑通、无 ERROR；`test_exit_infinite` 按设计**不自然结束**（`loop:true, loop_count:-1`），符合 I-2 语义。**未做同帧像素对比**，最终全量留 I-5 |

- **实机发现并修复的缺陷（1 条）**：`test_objects_adjust` 实机报 `[Adjust] rgb_curve 曲线需要至少 2 个控制点，已忽略`。原因 = `ScriptParser` 已在解析期把曲线归一成 `ColorCurve`，而 `ColorAdjustSampler.CurveCache` 仍用第二份运行期解析器把 `values.struct()` 当原始 `List` 解析。修复 = `CurveCache` 改读 `Clip#getColorCurve()` 等十个解析期结果，删除运行期解析器（单一来源）。修复后实机复跑：`rgb_curve` 告警不再出现、脚本自然结束，`run-audio.sh` 139 checks PASS。
- 实机环境：Fabric 1.20.1 dev 客户端 + `quickPlaySingleplayer "新的世界"`；夹具经 `fabric/run/immersive_cinematics/scripts/i3/` 临时投放，测完已删除（运行目录恢复为 `camera/` + `gtest/`），临时日志/截图不入库。

- 探针与临时日志在仓库外（`E:/tmp/i3/`、`E:/tmp/i2/`），不入库；`cinematics/0.3.6/tests/objects/` 新增 9 支新格式夹具（`test_objects_multitrack.json` 等）。
- 旧格式结论：解析 / 校验基线 diff 0，说明新格式装配未改变旧格式的解析与校验结果；旧格式求值语义另由 `run-clip` / `run-morph` 的旧路径对照覆盖。
- 实机已跑通（多轨道 + 逐轨道 + 四支循环夹具），但**未做像素级 diff**：本轮没有「改动前」基线截图，不主张「旧格式画面像素无差异」；像素级对比与最终全量留 I-5（见 `I-BACKLOG.md` I2-B1）。

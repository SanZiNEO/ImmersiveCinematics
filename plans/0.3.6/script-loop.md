# 0.3.6 脚本循环：宏观循环与微观循环（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、公式、JSON、默认值等执行时再定
>
> 相关文档：
> - [过渡](./transition.md)
> - [时间插值](./temporal-interpolation.md)
> - [相机状态与覆盖链](./camera-state-plan.md)

---

## 1. 定位（已确认）

循环分**两层**，各自独立折叠时间，可以叠加：

| 层级 | 折叠对象 | 现状 |
|---|---|---|
| **宏观循环**（meta） | 整条脚本的时间轴 | 运行时**没有**，本文要立 |
| **微观循环**（clip） | 片段自身的局部时间 | 已有实现（`clip.loop` / `loop_count` / `loop_mode`），语义不改 |

- 宏观只做一件事：**整条时间轴走到末端后折回起点**。
- 宏观**不关心**片段内部发生了什么，它只看到“这个片段占了自己的展开长度”。
- 两层是 `f(g(t))` 的关系，互不干涉。

**举例（已确认）**：脚本 = `clip1`（自己循环 2 次）+ `clip2`（1 次），那么宏观单位就是

```text
[ c1 第1圈 | c1 第2圈 | c2 ]        ← 宏观单位
```

宏观 repeat 就是把这个整体重复：

```text
[ c1 c1 c2 ] [ c1 c1 c2 ] [ c1 c1 c2 ] ...
```

**动机场景**：1v1 阵营对抗的直播 / 录制——跟随蓝方看向红方 30s，再跟随红方看向蓝方 30s，整段无限交替。

---

## 2. 现状（已确认）

- **运行时只有 clip 级循环**：`Clip` 的 `loop` / `loop_count` / `loop_mode`；`ScriptPlayer` 的结束判定（`isFinished()`）以 `total_duration` 为基准，并叠加一条：一旦有已开始的永不结束片段（`hasActiveInfiniteLoopClip()`，判据 `clip.isEffectivelyInfinite() && elapsed >= clip.getStartTime()`），脚本即永不结束。
- **编辑器有 A-B 循环，但只在编辑器预览里生效**：
  - 快捷键 I / O 设置 in / out 点（`EditorScreen.setLoopPoint()`，键位 `CinematicKeyBindings.EDITOR_SET_LOOP_IN/OUT`，Shift+I/O 清除），写进 **timeline 对象**（`loop_start` / `loop_end`）。
  - 只有编辑器预览播放读它（`EditorScreen` 预览循环，播到 out 点把时间打回 in 点）；`ScriptPlayer` / `CameraManager` **完全不读**这两个字段。
  - 结果是一个**断层**：编辑器里能循环预览、字段也存进了脚本 JSON，游戏内实际播放却不循环。作者会误以为有这个功能。
- **`total_duration < 0` 是“无限时长”，不是循环**：脚本永不宣布结束，但不会回到开头（`Timeline.isInfinite()`；`ScriptPlayer.isFinished()` 对 `total_duration<0` 直接返回 false）。文档中“无限循环”的措辞要与之分开（`docs/AI_SCRIPTING_GUIDE.md` 中的表述需修正）。
- **无限循环片段 + 后续片段**：当前代码与文档是“特写覆盖”语义（后续片段在自己窗口内优先渲染，播完回落到循环视角）。本计划**改为“终点”**，见第 4 节。

---

## 3. 宏观末端（已确认·核心定义）

宏观关注的**不是“周期是多少”**，而是：

> **脚本里最后一个片段的最后一次，什么时候播完。**

- 定义：沿时间轴从前往后，每个片段按自己的语义展开（含循环），**最后一个会被播到的片段的最后一次播放结束时刻**，就是宏观末端。
- 实现口径（执行时再定）：即该片段的活跃窗口末端——有限循环 = `start + 局部周期 × loop_count`，非循环 = `start + duration`。
- **永不结束的片段 → 末端不存在**：时间轴走到这里就停住，那个片段自己一直循环。
  这是“末端不存在”的**自然结果**，不是一条特例规则。
- **不使用作者声明的总时长作为宏观末端**（已确认）：
  - 作者填的总时长是“各片段时长相加”，他填的时候脑子里没有循环这回事；
  - 而宏观看到的是**展开之后**的长度，两者差着所有 loop 的展开量；
  - 拿声明值当末端，只要有一个片段带循环，从第二圈起整体就会错位——不符合人的直觉。
- 作者改 `loop_count`，宏观末端自动跟着变，**不需要手改任何“总时长”字段**。

| 情况 | 宏观末端 | 宏观行为 |
|---|---|---|
| 所有片段都有限 | 最后一个片段最后一次播放结束的时刻 | 走到这里折回起点，再来一圈 |
| 中间 / 末尾有永不结束的片段 | 不存在 | 时间轴走不下去，停在那里，该片段自己一直循环 |

---

## 4. 时间重叠与不覆盖（已确认·2026-10-07 修订）

- **允许片段在时间上重叠**（lane 模型后，见[画面合成](./camera-composition.md)）：叠化 = 上一个 clip 的**末尾帧复制延长**（hold，等值关键帧、画面冻结）+ 下一个 clip 的**首帧复制延长**（同样 hold），两个冻结区时间交叉，交叉窗口内靠 opacity 关键帧做效果。作者不写延长区内容——延长区是自动补充的 hold 关键帧（复制延长 = 插等值关键帧，仍是可追踪数据）。不允许重叠的话，两 clip 之间出现空隙 → 没有 lane 覆盖 → 露出游戏原本画面。
- **重叠时的解析规则**：后面的 clip 覆盖在前面 clip 的上层；整体层级 = 先按**轨道层级**分层，再按**轨道内 clip 顺序**分层（详见画面合成 §1）。
- **历史原因**：以前不允许重叠是因为只有一个相机（同一时刻只能有一个相机状态）；lane 模型后每个活跃 clip 各自渲染自己的画面，重叠即重叠。
- 永不结束的片段 = **终点**，其后的内容不播放（不变）。
- **宏观末端不受重叠影响**：末端 = 最后一个片段的最后一次播放结束时刻 = 各片段展开结束时刻的最大值。
- 随之要改的四处（执行时）：
  - `CameraTrackPlayer.findActiveClip`：现在只返回**一个**活跃片段 → 改为返回重叠窗口内的**全部**活跃片段（每帧按时间查表，天然支持多个）；永不结束的片段仍按"终点"处理。
  - `ScriptValidator` 中“无限循环后仍有其他片段 → 特写覆盖播放”的告警 → **删除**（"后面 clip 覆盖前面"已是通用分层规则，不再是特例）。
  - `docs/SCRIPT_FORMAT.md` 中“后续片段作为特写覆盖播放” → 改为“永不结束的片段 = 时间轴终点，其后内容不播放；片段允许时间重叠，重叠区按轨道层级 → clip 顺序分层（后者在上）”。

---

## 5. 谁吃折叠（已确认）

| 分类 | 轨道 | 回卷时的行为 |
|---|---|---|
| **吃折叠**（可正可倒） | CAMERA、LETTERBOX、OVERLAY（`layer_type` = fade / image / subtitle / pip，即淡入淡出、图片、字幕、画中画） | 共用同一个脚本时间，天然同步——一起正放、一起倒放 |
| **单向** | AUDIO | 按新的时间重新求活跃片段，天然正向重播（自动重起实例） |
| **单向** | EVENT | 服务端播放，不参与折叠 |
| **单向** | MOD_EVENT | 占位未实现（`ModEventTrackPlayer` 为空实现） |

- “吃折叠”的集合就是**整个画面表现层**：相机以外的所有视觉元素（黑边、图片、文字、画中画、淡入淡出）。
- 它们全是“按时间取关键帧”的纯函数，所以**倒放时入场动画自然变成出场动画**，不需要额外处理。
- 因此“哪些轨道不支持倒放”只涉及音频和事件，与画面无关。

---

## 6. 实现方向

- **作用点唯一**：在 `ScriptPlayer` 对外分发“脚本时间”的那一处折叠，所有轨道自动跟随，不逐个改 TrackPlayer。
- 宏观末端在脚本开始时推导一次；推导不出（末端不存在）则不折叠。
- 开启循环时脚本不再自然结束（结束判定返回“未结束”）；`skippable` / `interruptible` 的退出路径不变。
- **折返瞬间需要重置的只有一处**：EVENT 轨驱动玩家走路的 `PlayerMoveController`（`destIndex` 单调推进，`onScriptStart()` 里从 1 起走，不重置则第二圈不再驱动）。
  其余状态（相机的活跃片段索引、音频实例表、OVERLAY 图层）都是每帧按时间重新查 / 重建，天然自愈。
- **相对基准不刷新**：保持脚本启动时刻的值，保证每一圈完全一致；需要实时跟随用 `follow: entity` / `look_at: entity`。
  （`plans/complete/0.3.4/pending-work.md` 中“每轮循环刷新基准”的待办，按此**不做**。）
- 循环区间：第一版为**整条**（起点 → 宏观末端）；编辑器已有的 `loop_start` / `loop_end` 将来可作为**子区间覆盖**。

---

## 7. 字段与校验（方向，命名执行时定）

- 宏观折叠开关（bool，默认关）：与 `hold_at_end` 之类的运行时行为并列，属脚本级 meta。
- 宏观循环模式（enum：repeat / pingpong，默认 repeat）：与 clip 级同名同语义，两级词汇统一。
  **第一版只实现 repeat**，pingpong 语义已定但延后（见第 8 节）。
- 宏观字段是 schema 驱动的（meta 元数据 → 属性面板），加字段后编辑器面板自动出控件。
- 建议的校验（方向）：
  - 宏观 `pingpong` + clip 级 `loop=true` 同时开启要告警（同一时间轴上有两个折叠：宏观倒放时片段局部时间仍在正着取模，语义不清晰）；
  - 宏观循环 + `hold_at_end` 同时开启要告警（互斥）。
- **明确不做**（框架不检查作者内容）：循环窗口超出时间轴、时间轴末尾空档等，全部由作者自负。

---

## 8. 微观循环（已确认·已有实现）

- `clip.loop` / `loop_count` / `loop_mode`：折叠**片段局部时间**；周期 = 末关键帧时间 − 首关键帧时间。
- 两种模式的语义：
  - `repeat`：`a→b→c→a→b→c`
  - `pingpong`：`a→b→c→b→a→b→c`（折返点各只走一次，整周期 = 2 × 局部周期）
- `loop_count = -1` = 永不结束 → 在宏观层表现为“末端不存在”。
- 宏观 pingpong 一旦实现，**沿用同一套公式**，只是作用在宏观时间上。

---

## 9. 两层级的语义边界（已确认）

- 宏观只做“折回起点”，不知道片段内部发生了什么。
- 片段自己怎么循环与宏观无关：宏观一圈内，它就按自己的语义播完自己的展开长度。
- “宏观倒放 + 片段正向循环”的组合语义不清晰 → 交给第 7 节的校验拦下，不做支持。

---

## 10. 服务端与音频边界（已确认）

- **服务端 EVENT / 触发器不重复**：客户端折叠服务端不知道。宏观循环只作用于客户端表现层。
- 音频实例按“当前活跃片段”复用：
  - 跨循环边界**仍然活跃**的音频不重启（典型是一条铺满全程的 BGM 跨圈连着播），只有关键帧驱动的音量 / 空间位置按新时间求值；
  - 回卷时**不再活跃**的，下一圈重新起实例（正常重播）。

---

## 11. 可能的问题

- 折返那一帧的首尾衔接：repeat 是硬切，作者自己负责首尾接得上（属作者内容层，暂不做提示）。
- 折返瞬间的动画 / 事件帧对齐。
- 长跑时的虚拟时钟精度：折叠后喂给轨道的值恒在区间内，顺带缓解；但仍需确认时间强转单精度那一处的长期表现。
- 编辑器预览如何表现宏观循环；A-B 循环与宏观循环的关系。
- 触发式启动的脚本与宏观循环的关系（何时开始算第 0 秒）。
- 脚本时间起点与相对基准的取值时机。

---

## 12. 待定

- `loop_start` / `loop_end` 子区间循环是否纳入、何时纳入
- 宏观 pingpong 的落地时机
- 编辑器 UI 的呈现方式（预览是否也折叠、与 A-B 循环如何共存）
- 服务端是否也要跟随折叠（当前结论：不跟随）

---

## 13. 落地顺序（方向）

1. 语义定稿 + 文档措辞修正（本文 + `SCRIPT_FORMAT.md` + 校验器文案）
2. 活跃片段查找改为“终点”语义，去掉覆盖逻辑
3. 脚本时间折叠（repeat）+ 宏观开关字段
4. 折返瞬间的玩家移动控制器重置
5. 校验器两条告警
6. 编辑器属性面板（schema 自动出控件）+ 预览表现
7. （延后）宏观 pingpong、子区间循环

---

## 14. 相关文档

- [过渡](./transition.md)
- [时间插值](./temporal-interpolation.md)
- [相机状态与覆盖链](./camera-state-plan.md)

---

## 事实核查（2026-10-07）

核查依据：本仓库 HEAD 源码（`common/src/main/java/com/immersivecinematics/immersive_cinematics/`）、`editor/src/`、`docs/`。冲突裁决用 `git log -1 --format=%cI -- <路径>`。

### ① 核实为真（附证据）

- **§2 clip 级循环字段存在且语义如述**：`Clip.getBool("loop",false)`（`Clip.isLoop()`）、`Clip.getInt("loop_count",-1)`（`Clip.getLoopCount()`，默认 **-1**）、`Clip.getString("loop_mode","repeat")`（`Clip.getLoopMode()`）。证据：`script/Clip.java:83-97`；schema 默认值 `script/schema/TrackSchemas.java:35-37`。
- **§2 `loop_count=-1` = 永不结束**：`Clip.isLoopInfinite() = isLoop() && getLoopCount()<0`（`Clip.java:109-111`），`Clip.isEffectivelyInfinite()` 汇总无限时长/无限循环（`Clip.java:118-120`）。`loop_count=0` 在解析期被改为 1（`ScriptParser.java:244-250`、`ScriptValidator.java:190-196`）。
- **§2 编辑器 A-B 循环写 timeline 对象、仅编辑器预览读**：写入 `EditorScreen.setLoopPoint()`（`EditorScreen.java:1097-1113`，键位注释 `I`/`O`、`EditorScreen.java:1489-1493` 用 `CinematicKeyBindings.EDITOR_SET_LOOP_IN/OUT`）；预览回卷读 `loop_start`/`loop_end` 把 playhead 打回（`EditorScreen.java:1274-1281`）；前端 store/types 同步（`editor/src/store.ts:52-53,653-654,884-887`、`types.ts:201-202`）。全仓库 grep `loop_start|loop_end` **仅命中 `editor/` 与 `EditorScreen.java`**，`ScriptPlayer` / `CameraManager` 无引用 → “完全不读”成立。
- **§2 `total_duration<0` = 无限时长、不是循环**：`Timeline.isInfinite()`（`Timeline.java:30`），`ScriptPlayer.isFinished()` 对 `total_duration<0` 直接 `return false`（`ScriptPlayer.java:285-286`），`ScriptParser` 禁 0 且注明“负数=无限时长”（`ScriptParser.java:167-170`）。
- **§2/§4 “特写覆盖”现状**：`CameraTrackPlayer.findActiveClip`（`CameraTrackPlayer.java:929-983`）对 `isEffectivelyInfinite()` 片段**只记 `result`/`resultIndex` 后 `continue`**，命中有限片段的活跃窗口则立即 `return`；末尾才回落到 `result` → 后续片段在窗口内优先、播完回落循环视角，确为“候选 + 继续扫”。
- **§4 `ScriptValidator` 告警原文**：`ScriptValidator.java:202` —— “无限循环（loop=true + loop_count=-1）后仍有其他片段：后续片段作为特写覆盖播放，播完回落该循环视角”，触发条件 `loop && infinite && ci < clips.size()-1`（`ScriptValidator.java:199-203`）。
- **§4 `docs/SCRIPT_FORMAT.md` 措辞原文**：第 215 行 “无限循环片段后接的其他片段会作为特写覆盖播放（其窗口内优先渲染），播完回落到循环视角。”
- **§6 `PlayerMoveController` 目标索引单调推进**：字段 `destIndex`，`onScriptStart()` 置 `destIndex=1`（`PlayerMoveController.java:45-79`），`onRenderFrame()` 内 `while` 只做 `destIndex++`，从不回退（`PlayerMoveController.java:82-110`）。
- **§6 EVENT 服务端播放、不参与折叠**：`ScriptEventManager` 位于 `trigger/server/`，`onServerTick(MinecraftServer)` 按 `server.getTickCount()` 推进 `elapsed` 并执行命令（`ScriptEventManager.java:139-200`），与客户端 `ScriptPlayer` 的虚拟时钟完全独立。
- **§6 AUDIO 实例按活跃片段重建**：`AudioTrackPlayer.onRenderFrame` 每帧 `findActiveClip(globalTime)`，非活跃实例 `cleanup()+remove`，活跃但无实例则 `startClipInstance(activeClip)`（`AudioTrackPlayer.java:57-100`、`findActiveClip` 307-320）→ 回卷后按新时间重求活跃片段、自动重起。
- **§5 OVERLAY `layer_type` 取值**：`OverlayTrackPlayer.createLayer` switch 分支 `"fade"/"image"/"subtitle"/"pip"`（`OverlayTrackPlayer.java:113-155`），与 `TrackType.java:43`、`docs/SCRIPT_FORMAT.md:446` 一致。
- **§5 MOD_EVENT 占位未实现**：`ModEventTrackPlayer` 三个方法均为空实现（`ModEventTrackPlayer.java` 全文，注释“Phase 1 不实现”）。
- **§8 clip 循环周期与两模式公式**：`Clip.getAnimPeriod() = 末关键帧时间 − 首关键帧时间`（`Clip.java:99-103`）；`KeyframeInterpolator` 对 `loop` 取模，`pingpong` 用 `% (2*animPeriod)` 镜像（`KeyframeInterpolator.java:47-62`），与 §8 “整周期 = 2 × 局部周期”一致。
- **§2 末句「`docs/AI_SCRIPTING_GUIDE.md` 中的表述需修正」所指位置**：`docs/AI_SCRIPTING_GUIDE.md:139` —— “`total_duration` | 总时长秒。正数 = 定长；**负数 = 无限循环**”（`docs/SCRIPT_FORMAT.md:134` 作“负数=无限”，用词中性；`SCRIPT_FORMAT.md:215` 才是“特写覆盖”那句）。指南把“负数=无限时长”写成“无限循环”，与 `Timeline.isInfinite()` 的“永不结束但不回到开头”语义不符 → 该“需修正”成立，待修正处即第 139 行。
- **§7 `hold_at_end` 存在**：`ScriptMeta.isHoldAtEnd()`（`ScriptMeta.java:65`），`CameraManager` 于 NATURAL_END 分支读它（`CameraManager.java:122-125,480-489`）。

### ② 已修正的断言

- **§2「`ScriptPlayer` 的结束判定只看 `total_duration`」→ 事实：`isFinished()` 还叠加无限循环片段判定。**
  现文：`isFinished()` 先看 `total_duration`，随后 `return !hasActiveInfiniteLoopClip(elapsed)`；`hasActiveInfiniteLoopClip` 判 `clip.isEffectivelyInfinite() && elapsed >= clip.getStartTime()`（`ScriptPlayer.java:284-291,424-431`）。`getRemainingTime()` 同判据（`ScriptPlayer.java:308-312`）。
  证据时间：`ScriptPlayer.java` 最后修改 `2026-08-30T22:56:16+08:00`，`hasActiveInfiniteLoopClip` 由提交 `ef1e539`（`2026-08-10T19:30:55+08:00`）引入；本文档 `plans/0.3.6/script-loop.md` 最后修改 `2026-09-15T14:14:43+08:00`。即本文写就时该代码早已存在，原断言属笔误而非代码更新导致，按代码事实修正（文档虽较晚，但与它所描述的、更早即已存在的代码行为不符）。

### ③ 补全的信息

- §2 结束判定补 `isFinished()` / `hasActiveInfiniteLoopClip()` / 判据表达式。
- §2 A-B 循环补 `EditorScreen.setLoopPoint()`、键位常量、Shift 清除、`editor/src` 落点。
- §2 无限时长补 `Timeline.isInfinite()` 与 `isFinished()` 的 `total_duration<0` 分支。
- §5 MOD_EVENT 补 `ModEventTrackPlayer` 空实现。
- §6 折返重置补 `PlayerMoveController.destIndex`、`onScriptStart()` 初值 1。

### ④ 无法核实的断言（未验证）

- §11「时间强转单精度那一处的长期表现」：属长期运行时表现，源码中未找到对应量化结论，**未验证**。
- §12 待定项、§13 落地顺序、§5「MOD_EVENT 占位」以外的设计口径（宏观折叠、子区间循环等）：均为方向/待办，非对现状的事实断言，**未验证/不适用**。

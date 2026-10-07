# 0.3.6 并行播放：多脚本实例与画面 lane（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [画面合成](./camera-composition.md)
> - [多相机渲染](./multi-camera-rendering.md)
> - [相机状态与覆盖链](./camera-state-plan.md)

---

## 1. 定位（已确认）

现在的播放模型是**单实例**：`CameraManager` 持有一个 `ScriptPlayer`、一个 `pendingScript` 槽，同一时刻只有一套虚拟相机状态。播脚本 A 时想同时用脚本 B 补音效、补 overlay，做不到。

本文确立的模型：

> **播放 = 实例。多个实例可以同时存在，不设数量上限。**

- 实例 = 一个正在播放的脚本：独立的时间轴、轨道状态、生命周期。
- 脚本 A（相机 a1、相机 a2、音频、overlay）和脚本 B（同样全套）可以并存。
- 单个脚本内也可以有多条 CAMERA 轨 / 多个相机——每个相机是一个**画面 lane**（产出一张相机画面的通道），lane 同样不设上限。

**原则（已确认）**：造好用的底层，而不是造限制多的工具。

- 框架不设实例数 / 相机数阈值；渲染压力由作者按场景自控，框架提供性能档位（见[多相机渲染](./multi-camera-rendering.md)）。**原型实测支持这条**：1 / 4 / 16 / 25 画面成本线性、无拐点（单画面 ≈3.4–3.6 ms，主画面稳态 2–3 ms，见 `quadrant-perf/summary.md`）。
- **lane 模型的红利（已确认·2026-10-07）**：以前不允许 clip 时间重叠，是因为只有一个相机（同一时刻只能有一个相机状态）；lane 之后每个活跃 clip 各自渲染自己的画面，于是**时间重叠、叠化、无空隙全屏覆盖（不露原版画面）、分层调色、多机位合成**等都自然成立——这些提升全部来自"多实例 × 多 lane"这一个底层能力。
- 优化模组与光影模组普遍在做二次渲染，说明多画面的渲染压力本身在可控范围内。

**“单线程”澄清**：这里说的是播放模型的单实例，不是 Java 线程；渲染仍在渲染线程，逻辑仍在 tick。

**不属于本文**：

- 画面 lane 怎么铺到屏幕 → [画面合成](./camera-composition.md)
- 多张相机画面怎么渲染出来 → [多相机渲染](./multi-camera-rendering.md)

---

## 2. 概念模型（已确认）

```text
播放实例（一个正在播放的脚本）
 ├─ 画面 lane × N（每条 CAMERA 轨 / 每个相机一个，N ≥ 0）
 └─ 非画面轨（AUDIO / EVENT / OVERLAY / LETTERBOX / MOD_EVENT）
```

- **画面 lane**：产出相机画面纹理，交给合成层上屏。没有 lane 的实例（纯音效 / 事件 / overlay 脚本）是合法的并行成员——这就是“播 A 时 B 辅助音效”的落点。
- **非画面轨**只属于实例自己，互不共享。

---

## 3. 语义规则

### 3.1 生命周期按实例独立（已确认）

`skippable` / `interruptible` / `hold_at_end` / `pause_when_game_paused` / 跳过投票，全部按实例独立计算。跳过实例 A 不影响实例 B；游戏暂停只暂停声明了联动的实例。

### 3.2 运行时控制取并集（已确认）

行为开关（`hide_hud`、键鼠屏蔽、`suppress_bob` 等）逐位取**并集**：任一实例要求隐藏 / 屏蔽即生效；该实例结束后，按剩余实例重新求并集。

### 3.3 主视角与听者（已确认·2026-10-07）

- 玩家最终看到的画面 = **合成层输出**（所有 lane 按合成参数铺屏的结果）；没有任何 lane 时 = 原版视角。
- **多脚本同时播放是功能，不是 bug**（用户原则）：**有什么就放什么，没有就不放**——活跃实例的 lane 全部渲染、全部上屏，不做互斥/裁剪。
- 音频听者唯一：**后来者居上**——由**启动最晚的活跃实例**的 `meta.listener` 决定（与叠加秩序的实例启动顺序同一规则）；无 lane 的实例不影响听者。

> **架构推论（2026-10-07，与[画面合成](./camera-composition.md) / [可变画面](./variable-frame.md) §2 的「主画面也是覆盖层之一」一致）**：多相机渲染落地后，**主画面不再走"单相机替换"路径**——它只是「dest=全屏、opacity=1」的全屏 lane 特例。相机画面与图片 / 字幕 / 黑边同类：lane 输出纹理进覆盖层体系，位置 / 大小 / 取材 / 不透明度 / 叠放顺序全部走关键帧（完全关键帧控制）。
>
> 随之而来：现有"把虚拟相机写进唯一主 `Camera`"的替换链路——`CameraMixin` 主相机分支、`GameRendererMixin` 的 getFov / roll 分支、`LevelRendererMixin` 视图中心改写、`CinematicOcclusion` 整帧遮挡决策——在理想态下是**过渡实现**，最终随 lane 化收敛（不再被调用 / 删除）。
> 两点保留：① 每个 lane 的相机仍复用"把模组相机状态写进一个 `Camera` 实例 + 独立投影"的机制（四象限原型已验证）；② 单 lane 全屏（dest=全屏、opacity=1）可作为优化走旧路径省一遍渲染（每 lane ≈3.4–3.6 ms），是否保留执行时定。

**✅ 落地（2026-10-07，主相机替换链退役）**：主画面不再走单相机替换路径，画面完全由 lane 合成层承担；零 lane 时 = 原版视角（回归 vanilla）。逐点处置：

| 项 | 处置 |
|---|---|
| `CameraMixin.onSetup` 主相机分支 | **删除**（lane 分支保留：lane 相机仍写自己的 `Camera` 实例 = 保留点 ①） |
| `CameraMixin.onGetEntity` / `onIsDetached` 主分支 | **删除**（lane 分支保留） |
| `GameRendererMixin.onGetFov` 主分支 | **删除**（lane 分支保留：lane 自己的 fov/zoom → 生效 FOV） |
| `GameRendererMixin` roll 主相机钩子（`onBeforePrepareCullFrustum`） | **删除**（lane 的 roll 本就在 `LaneRenderer.renderLane` 内施加到该 lane 的 PoseStack） |
| `LevelRendererMixin` 视图中心改写（`ModifyVariable` ×3） | **收窄为 lane 专用**：读**正在渲染的 lane 自己**的相机位置（`LaneRenderer.currentLane()`），非 lane pass 回落原版玩家坐标——直接删会让相机飞出玩家渲染距离后 lane 画面空洞 |
| `CinematicOcclusion` 整帧遮挡决策 | **保留但输入改为 lane 相机**：去掉主相机输入（原版 `player.isSpectator()` 判定对原版玩家相机本就正确）。可见区块集合仍是单份共享状态，故整帧统一必须保留（per-lane 独立可见性是长期方向） |
| 帧驱动 `CameraManager.onRenderFrame()` | **挂点迁移**：原唯一调用点 `CameraMixin.onSetup`（主相机）→ `LaneRendererMixin`（`GameRenderer.renderLevel` RETURN）开头，保证「先驱动（填 lane 快照）→ 再注册与渲染 lane」 |
| 听者相机（`SoundManagerMixin`） | **消费方迁移**：原版 `Minecraft.tick` 传的是主相机，退役后主相机 = 玩家相机 → `listener=camera` 改由 `AudioListenerController.cameraListener()` 显式提供镜头代理（`CameraAccessor` 写位置/朝向/initialized） |
| `getCameraState()` 快照 | **保留**（仍有消费方：听者位置/听者相机代理、区块预加载中心、预览 HUD）；值 = 顶层实例顶层活跃 clip 六参数，与最上层 lane 同源同值 |

> 未保留点 ②：单 lane 全屏优化未做——一律走全 lane 渲染（每 lane ≈3.4–3.6 ms）。
> 已知后果：渲染优化模组（Sodium / Embeddium）下 lane 渲染被禁用 → 退役后主画面不再被接管，过场看不到相机画面（退役前旧链在 Sodium 下仍生效）。

### 3.4 叠加秩序（已确认·2026-10-07）

**后来者居上（整体分层）**：实例**整体**按启动顺序叠放——后启动实例的**全部输出**（其所有 lane 与 OVERLAY/LETTERBOX）整体在上，前者的整体在下；**实例内部各自保持自己的顺序**（轨道层级 → 轨道内 clip 顺序（后面的在上）→ 层内 `z_index`）。**不做跨实例的逐 lane 混排**。多相机就是"相机有什么覆盖就放什么"。

### 3.5 触发器与重入（方向）

- 触发器命中 = **新建实例**，不再因“有脚本在播”被阻塞。✅ 已落地（2026-10-07）：`shouldSkip` 的“正在播放”门控是同脚本口径，跨脚本不阻塞。
- 同脚本重入：当前触发状态机以“脚本 + 玩家”为键，同脚本第二实例会撞键。
  - ✅ 已定稿（2026-10-07）：**同脚本同玩家默认单实例**（保持现状语义），跨脚本无限制；
  - 放开同脚本多实例需要先改状态机键（待定）。

### 3.6 与播放队列的关系（已确认·2026-10-07：不做互斥组）

默认**全并行**——有什么就放什么。互斥组不做（作者需要互斥时自行用触发器控制脚本起停）。现有“不可打断 → 排队”语义仅保留在同脚本单实例的内部（第二份请求排队），不扩展到跨脚本。

### 3.7 服务端（方向）

- 事件时间线、跳过投票按实例维护；✅ 已落地（2026-10-07）：`ScriptEventManager.scriptPlaybacks` 以 `(scriptId, instanceId)` 两级键，观看者 / 跳过投票 / 事件时间线按实例记账。
- 触发状态机按实例维护：**未采用**——状态机键保持（玩家 + 脚本 + 触发器），与“同脚本同玩家单实例”一致（§3.5）。
- 网络包带实例 id；✅ 已落地（2026-10-07）：`S2CPlayScriptPacket` / `C2SPlaybackStartedPacket` / `C2SScriptFinishedPacket` / `C2SScriptPausePacket` / `S2CStopScriptPacket` 均带实例 id。
- 跳过投票按实例记账。✅ 已落地（同上）。

---

## 4. 现状差距（方向）

- `CameraManager` 单实例 + 单 `pendingScript` 槽 → 改**实例列表**。
- 触发器的 `shouldSkip`（播放期间跳过）语义按实例重述 —— ✅ 已落地（2026-10-07，见 §7 步骤 6 落地记录）：同脚本同玩家跳过（单实例），跨脚本不阻塞。
- 编辑器预览通道与游戏播放通道要隔离：预览是一个特殊实例，不挤掉游戏内播放。

---

## 5. 可能的问题

- 同脚本重入与触发状态机键的冲突。
- 多实例 AUDIO 的空间锚点（听者唯一，见 3.3）。
- 跳过投票 UI：多实例同时可跳过时，玩家投的是哪一个。
- 实例与 Base Provider 的对应关系（见[相机状态与覆盖链](./camera-state-plan.md)）。
- 区块预加载 ticket：多 lane 的预加载取并集。
- 性能：实例数 × lane 数的开销曲线；档位建议放在渲染文档，不放本文。

---

## 6. 待定

- 互斥组 / 队列语义的最终形态。
- 同脚本重入是否放开（涉及状态机键改造）。
- 实例 id 的网络协议形态。
- 主 lane 判定规则（与合成文档一起定）。

---

## 7. 落地顺序（方向）

| # | 步骤 | 交付物（完成后我们要什么） |
|---|---|---|
| 1 | 实例模型：`CameraManager` 改实例列表，每实例独立 `ScriptPlayer` + 轨道状态 | 同一脚本可先后 / 同时起两个实例，日志可验证互不干扰 |
| 2 | 生命周期按实例：跳过 / 打断 / `hold_at_end` / 暂停联动按实例计算 | 实例 A 被跳过、实例 B 不受影响；游戏暂停只暂停声明联动的实例 |
| 3 | 运行时控制并集：行为开关逐位并集，实例结束重新求值 | A 隐藏 HUD、B 不隐藏 → 隐藏；A 结束后恢复按 B 判定 |
| 4 | lane 抽象与注册表：实例把 CAMERA 轨注册为画面 lane；此步仍只有主 lane 上屏 | lane 注册表 + 主 lane 选择规则；对外行为与现状一致（零回归） |
| 5 | 网络与编辑器隔离：网络包带实例 id；编辑器预览为独立实例 | 编辑器预览不再挤掉游戏内播放 |
| 6 | 触发器重入规则定稿与实施 | 跨脚本并行触发可用；同脚本语义与现状一致 |

> 步骤 1–4 是地基，行为与现状兼容；步骤 5–6 放开并行能力。多 lane 上屏属于[画面合成](./camera-composition.md)的落地范围。

### 步骤 1 落地记录（2026-10-07）

**范围**：先落**载体部分**——播放器与生命周期状态改由实例承载、`CameraManager` 实例列表化；**仍只允许一个活跃实例**（`pendingScript` / 播放队列语义不变，对外行为与改造前一致）。"同一脚本可先后 / 同时起两个实例"是步骤 1 的后半，随步骤 2+ 一起放开。步骤 2 的"生命周期判定按实例读"在同一次改造中顺带完成（判定点与实例载体是同一批代码，分不开）。

**实例形态**（`common/src/main/java/com/immersivecinematics/immersive_cinematics/camera/PlaybackInstance.java`）：

| 组成 | 说明 |
|---|---|
| `ScriptPlayer player` | 一个实例 = 一个播放器：时间轴、轨道状态、音频实例、`PlayerMoveController` 全随实例走，实例之间不共享可变状态 |
| `ScriptMeta.RuntimeBehavior behavior` | **启动时快照**（`start` 取自 `script.getMeta().getBehavior()`，编辑器增量替换时随新脚本刷新）；生命周期判定只读它，不读全局 `CinematicController` |
| `boolean stopping` | 退场渐出中（原 `CameraManager.stopping`） |
| `CompletionReason exitReason` | 退出原因（原 `CameraManager.pendingCompletionReason`）：`requestExit` 置入、`deactivateNow` 消费 |
| 判定方法 | `isSkippable()` / `isInterruptible()` / `isHoldAtEnd()` / `isPauseWhenGamePaused()`；无行为快照时取 `CinematicController.revert()` 的默认值 |
| 驱动方法（包内可见） | `start(script, preExecuteAt)` / `replaceScript(newScript)` / `stop(reason)` / `markStopping()` / `setExitReason(reason)` —— 实例只由 `CameraManager` 创建与驱动 |

**迁移点**（`camera/CameraManager.java`）：

- `private final ScriptPlayer scriptPlayer` + `active` / `stopping` / `pendingCompletionReason` → `private final List<PlaybackInstance> instances`（本版本至多 1 个）；`active` 改由实例列表推导（`isActive()` = 有活跃实例），`isScriptMode()` / `getActiveScriptId()` 同样从活跃实例读。
- 新增 `activeInstance()`（无播放时 `null`）作为实例入口；**删除** `getScriptPlayer()`、`getCurrentProperties()` 与无调用方的 `activate()`（死代码，全仓 grep 无调用点）。
- 生命周期判定全部改读活跃实例：`requestExit` 的 `skippable` / `interruptible` / `hold_at_end` 门控、`playScript` 的 `interruptible` 分支、`onRenderFrame` 的 hold 钳制与自然结束门控、暂停联动的 `pause_when_game_paused`。单实例游戏内播放与改造前**逐点等价**——改造前这些点读 `CinematicController`，而它的值正是 `startScriptInternal` 用同一份 `RuntimeBehavior` 应用进去的。
- 预览通道：`pushScript` / `setTime` / `resume` 改走活跃实例的播放器（`instance.player()`；`pushScript` 的增量替换走 `instance.replaceScript`，行为快照同步刷新）。

**消费方迁移**（`getScriptPlayer()` 的 4 个调用点）：`client/lane/ScriptLaneDriver`（lane 快照收集）、`mixin/LocalPlayerMixin`（假输入驱动）、`script/AudioListenerController`（listener 模式）、`trigger/client/PreloadRequester`（区块预加载）；统一改为 `activeInstance()` + 判空。

**已知语义差异（仅预览通道，1 处）**：预览实例的生命周期判定现在读**预览脚本自己的** `skippable` / `interruptible` / `pause_when_game_paused`，而改造前预览路径从不 `apply` 脚本行为，这三个开关在预览里恒为全局默认值（`true` / `true` / `true`）。`hold_at_end` 无差异（改造前该门控本就先读脚本自己的行为）。方向与 §3.1 一致（判定按实例独立）；若日后要恢复"预览不理会脚本这些声明"，应在预览实例上单独处理，而不是退回全局开关。

### 步骤 3 落地记录（2026-10-07）

**范围**：运行时控制取并集（§3.2）落地——`CinematicController.apply(RuntimeBehavior)` 拆成两条职责：`applyLifecycle(behavior)`（只写生命周期开关 skippable / interruptible / holdAtEnd / pauseWhenGamePaused）与 `recomputeUnion(List<RuntimeBehavior>)`（行为开关逐位并集）。消费方一行未改（仍读同一组全局 getter，值 = 并集）；实例增删时 `CameraManager` 重算（`startScriptInternal` 非预览分支、`deactivateNow` 出列后按剩余实例）。

**并集口径**：5 个非三态开关（blockKeyboard / blockMouse / blockMobAi / hideHud / renderPlayerModel）逐位 OR；11 个三态开关 + suppressDistortion 先按**各实例自己的 hide_hud** 解析有效值再 OR；`hud_layers` 键集 = 各实例声明键并集，每个键的值 = **全部实例**有效值 OR（未声明该键的实例按自身 hide_hud 参与投票）。空/全 null = 无实例要求 → 复位默认。

**验证**：单实例与改造前逐位一致；两实例并存逐位 OR + 顺序无关；实例结束后按剩余实例重算；10057 项冒烟（含 400 组随机多实例与独立参考实现比对）。注意：本版本 CameraManager 仍至多 1 个活跃实例，多实例并集只在离线性质测试验证，未在真实客户端跑过双实例。

### 审查修订（2026-10-07，多实例放开前执行）

| # | 修订 | 说明 |
|---|---|---|
| 1 | **实例 id 挂实例** | `PlaybackInstance` 增 `instanceId` 字段（start 时传入，预览/本地来源为空串）；删除 `CameraManager.instanceIdsByScriptId` 旁路 Map（当前"请求暂存/上报取走"关联不自然，且同脚本被拒请求残留 id 会误报） |
| 2 | **暂停联动 = 并集、跳过提示 = 顶层实例** | `InputRouter` 的暂停联动消费改为任一实例声明 `pause_when_game_paused` 即放行（并集）；`SkipHudRenderer` 的跳过提示只由顶层实例（后来者居上）的 `skippable` 决定；随之删除全局生命周期开关与实例快照的"两套真相" |
| 3 | **finish / stop / pause 包补 instanceId** | 服务端删除"观看者成员→唯一实例"启发式，账本按实例 id 精确解析（started 包已带 id） |

### 步骤 4/5/6 落地记录（2026-10-07）

- **跨脚本并行已放开**：`playScript` 并行决策树——同脚本冲突实例走原单实例语义（可打断替换该实例 / 不可打断排队或拒绝），跨脚本直接新建实例并行（不排队不打断不阻塞，§3.5/§3.6）；`activeInstance()` 改为顶层（后来者居上）；每帧按启动顺序驱动所有实例；任一实例结束只退该实例，全局复位只在最后一个实例退出时执行；`cameraState` 快照 = 顶层实例状态；暂停握手按实例各发一条（账本按实例）。
- 审查修订①②③ 全部落地（实例 id 挂实例 / 暂停联动并集+跳过提示顶层 / finish·stop·pause 包带 id）。
- 尚未落地（后续步骤）：听者后来者居上（AudioListenerController 已按顶层但 hasActiveCameraClip 为并集，口径待对齐）、编辑器预览独立实例、队列按脚本匹配接播（ScriptQueue 无匹配 API，待定）。跨实例 lane 收集已落地（ScriptLaneDriver 按启动顺序平铺所有实例 lane，后启动实例整体在上）。

### 步骤 6 落地记录（2026-10-07）

**范围**：服务端触发器 `shouldSkip` 的语义重述（§3.5 / §3.7）。

**核实结论（无需改逻辑，现状即目标口径）**：`TriggerEngine.shouldSkip(ServerPlayer, TriggerRegistration)` 的“正在播放”门控调用 `ScriptEventManager.isPlayerPlayingScript(player.getUUID(), reg.getScriptId())`——该方法只按 **scriptId** 取实例集合并判断玩家是否为其中任一实例的观看者，**已是同脚本口径**：

- **同脚本在播 → 跳过**（同脚本同玩家单实例，与实例化前逐点等价）；
- **跨脚本不阻塞**：播放脚本 A 时，指向脚本 B 的触发器 `isPlayerPlayingScript(uuid, "B") == false` → 照常命中并新建 B 实例（客户端多实例已就绪，步骤 4/5 已放开）。

全仓 grep `isPlayerPlayingScript` 仅 `TriggerEngine.shouldSkip` 一处调用；无“任意脚本在播即阻塞”的残留（`isScriptActive` / `isFullyComplete` / `getRemainingViewers` 在 Java 侧无调用点）。触发器状态机键（玩家 + 脚本 + 触发器，`TriggerStateStore` / `enterStates`）不变。

**账本按实例的消费点复核**（§3.7）：`scriptPlaybacks` 以 `scriptId → instanceId → ScriptPlayback` 两级键；开始 / 结束 / 暂停 / 跳过投票 / 事件时间线 / 超时重发全部经 `playback(scriptId, instanceId)` 精确解析实例，`onServerTick` 逐实例驱动，`broadcastSkipVote` 与强制停止均以单实例 `pb` 为单位——无单实例假设残留。

**改动**：仅为语义显式化——`TriggerEngine.shouldSkip` 与 `ScriptEventManager.isPlayerPlayingScript` 补 javadoc 说明同脚本口径；逻辑零变更。

---

## 8. 与 0.4.0 旧稿的关系

本文取代 0.4.0 的 **G1（相机实例队列）**：其实例列表、后台预热的思想并入本文，并扩展为“无上限 + lane + 完整并行语义”的模型。G1 原文已从 0.4.0 抹除。

---

## 已知缺陷（2026-10-06 代码复查）

> 只读代码审查发现，未在游戏内复现；不影响当前设计，记录备查。

- **`emergencyStop()` 不清队列**：只清 `pendingScript`，不清 `scriptQueue`（字面属实）；但 `deactivateNow` 会先调用 `reset()` 清空 `scriptQueue`，故“从队列接播”的分支实际不可达，世界退出不会误启下一脚本。（原文“会从队列接播”结论有误，见事实核查）
- **`C2SPlaybackStarted` 无条件回报**：`ClientScriptReceiver.handlePlayScript` 不检查 `playScript` 返回值（0 拒绝 / 2 排队也回报“已开始”）→ 并行化后服务端账本会错位（需按实例 id + 实际结果回报）。**✅ 已修复（2026-10-07）**：回执拆成两件事——传输层 ACK（无条件回，抑制超时重发）与播放账本（仅真正开始播放时由 `CameraManager.reportPlaybackStarted` 上报，3 个调用点：直接开始 / pendingScript 接播 / 队列接播）；`C2SPlaybackStartedPacket` 增加 `started` 字段。实例 id 仍留待并行化。

---

## 事实核查（2026-10-07）

> 以仓库代码为准逐条核对。路径均为仓库根相对路径。

### ① 核实为真的断言

1. **§1 “单实例：一个 `ScriptPlayer`、一个 `pendingScript` 槽、同一时刻一套虚拟相机状态”** —— 属实。`common/.../camera/CameraManager.java`：`private final ScriptPlayer scriptPlayer = new ScriptPlayer();`、`private CinematicScript pendingScript = null;`、单一 `private final CameraProperties activeProperties` / `activePath`。
2. **§4 “`CameraManager` 单实例 + 单 `pendingScript` 槽”** —— 属实（同上）；另持有 `private final ScriptQueue scriptQueue = new ScriptQueue();`（`camera/ScriptQueue.java`，容量 `CAPACITY = 8`，`PriorityQueue` 按 `meta.priority` 降序、同优先级 FIFO）。
3. **§4 “触发器的 `shouldSkip`（播放期间跳过）语义”** —— 属实。`trigger/server/TriggerEngine.java:196` `shouldSkip(...)`：若 `ScriptEventManager.INSTANCE.isPlayerPlayingScript(player.getUUID(), reg.getScriptId())` 返回 true 则跳过；两处轮询入口（`TriggerEngine.java:91`、`:113`）均调用。播放期间跳过且状态机不更新。
4. **§4 “编辑器预览与游戏播放共用 `CameraManager`”** —— 属实。`webui/WebPreviewScreen.java`（`enterFlightMode` 用 `CameraManager.INSTANCE.getPath()`）、`webui/WebEditorApi.java`（`pushPlaybackState` 读 `CameraManager.INSTANCE.getGameTimeSeconds()`）、`editor/EditorScreen.java:1722`（`CameraManager.INSTANCE.exitPreview()`）+ `PreviewCapture.capture(minecraft)`（`:1251`）。预览走 `CameraManager` 的 `previewMode` 分支，与游戏播放同一 `scriptPlayer`。
5. **已知缺陷 2 “`C2SPlaybackStarted` 无条件回报”** —— 属实。`trigger/client/ClientScriptReceiver.handlePlayScript`：`CameraManager.INSTANCE.playCinematic(script);` 后无条件 `NetworkHandler.sendToServer(new C2SPlaybackStartedPacket(script.getId(), packet.getRefId()))`，未检查返回值。`CameraManager.playScript` 的 javadoc 明确 `@return 0=被拒绝, 1=已开始播放, 2=已排队等待`（`CameraManager.java:145`）；`playCinematic` 直接丢弃返回值（`:177-179`）。
6. **§3.7 “网络包带实例 id（方向）/ 触发状态机按实例维护（方向）”** —— 现状确实无实例 id：`S2CPlayScriptPacket` 仅 `scriptJson` + `refId`；`C2SPlaybackStartedPacket` 仅 `scriptId` + `refId`；`ScriptEventManager` 的 `Map<String, ScriptPlayback> scriptPlaybacks` 以 **scriptId** 为键，`ScriptPlayback` 只含 `Set<UUID> viewers` / `Set<UUID> skipVoters`；`ScriptSyncState` 只做脚本文件指纹登记（`Map<String,String> fingerprints`），与实例无关。故“带实例 id”确为尚未实现的方向。
7. **§1 原型数据（引用 `quadrant-perf/summary.md`）** —— 绝对数值属实：结论表列 1/4/16/25 画面帧间隔 8/17/61/98 ms、单画面 3.4/3.5/3.6 ms、主画面各档 2–3 ms、线性。原「≈1.2–1.4 × 主画面」比值经 2026-10-07 跨文档核查与 CSV 明细不符，已在本文与引用处删除（裁决见 multi-camera-rendering.md 核查小节）。
8. **§8 “G1 原文已从 0.4.0 抹除”** —— 属实。`plans/0.4.0/README.md:12` 注明“G1/G2 已抹除”，`:19` 注“相机实例队列（G1）→ `plans/0.3.6/parallel-playback.md`（取代）”；`plans/0.4.0/camera-queue-pip-dimension.md` 开头声明 G1/G2 已被 0.3.6 三篇取代并抹除，仅保留 F 类跨维度运镜。全 0.4.0 目录 grep `G1` 仅剩这些“已抹除”说明。

### ② 已修正的断言

1. **已知缺陷 1（`emergencyStop()` 不清队列）** —— 旧说法：“只清 `pendingScript`，不清 `scriptQueue`；`deactivateNow` 会从队列接播 → 世界退出时可能误启下一脚本。”
   - 新事实：`emergencyStop()` 确实未直接调用 `scriptQueue.clear()`（只置 `pendingScript = null`），**但** `deactivateNow()` 在接播判断前调用了 `reset()`，而 `CameraManager.reset()` 内含 `scriptQueue.clear()`；随后 `else if (!scriptQueue.isEmpty()) startScriptInternal(scriptQueue.poll())` 恒为 false（队列已被清空）。故“从队列接播”分支不可达，世界退出**不会**误启下一脚本。
   - 证据：`camera/CameraManager.java` 的 `deactivateNow()`（`reset();` 位于 `pendingScript`/`scriptQueue` 判断之前）与 `reset()`（`scriptQueue.clear();`）。
   - **冲突裁决**：`git log -1 --format=%cI` —— 文档 `2026-10-06T21:16:28+08:00`；`camera/CameraManager.java` `2026-09-09T10:42:54+08:00`；`scriptQueue.clear()` 于 `2026-08-10T17:24:47+08:00`（b3c8724）已在 `reset()` 中（`git log -S`）。文档时间更晚，但其“会从队列接播”属对代码的观察性结论，且所涉代码在复查时点即已存在 → 按代码事实修正。（如需保留“未复现”存疑，可在游戏内断线场景再验一次。）

### ③ 补全的信息（原文只给方向、此处补类/方法名）

- §1/§4 单实例载体：`CameraManager`（`scriptPlayer`、`pendingScript`、`scriptQueue`、`activeProperties`/`activePath`）。
- §3.5 “同脚本重入：当前触发状态机以‘脚本 + 玩家’为键” —— 精确为：`TriggerStateStore` 以 `Map<UUID, PlayerTriggerState>`（玩家）为一级键，二级键为 `scriptId` + `triggerId`（`TriggerStateStore.isTriggered(UUID, String scriptId, String triggerId)`）；播放态去重另在 `ScriptEventManager.isPlayerPlayingScript(UUID, String scriptId)`（玩家 + 脚本）。即键实为“玩家 + 脚本 + 触发器”，非仅“脚本 + 玩家”。
- §4 触发器跳过语义所在方法：`TriggerEngine.shouldSkip(ServerPlayer, TriggerRegistration)`（`TriggerEngine.java:196`），调用点 `:91`、`:113`。
- §3.7 服务端现状承载类：`ScriptEventManager`（`scriptPlaybacks` 以 scriptId 为键、`ScriptPlayback` 含 `viewers`/`skipVoters`/`skipVoteRatio`/`triggeredKeyframes`）、`ScriptSyncState`（脚本文件指纹，非实例）、网络包 `S2CPlayScriptPacket`/`C2SPlaybackStartedPacket`（字段 `scriptJson`/`scriptId` + `refId`）。
- 已知缺陷 2 涉及方法：`ClientScriptReceiver.handlePlayScript`、`CameraManager.playCinematic` / `playScript`、`C2SPlaybackStartedPacket`。

### ④ 无法核实的断言（未验证）

- §1“优化模组与光影模组普遍在做二次渲染，说明多画面的渲染压力本身在可控范围内”—— 属论据性陈述，未指定具体模组/文件，未验证（`render-second-pass-cost.md` 另有“仓库内不存在第二遍世界渲染”的相反限定）。
- §3.7“事件时间线、触发状态机按实例维护”“跳过投票按实例记账”—— 为**方向**（当前均为按 scriptId 记账），非现状断言；作为方向保留，不判对错。
- §3.2 行为开关“逐位取并集”（`hide_hud`/键鼠屏蔽/`suppress_bob`）—— 为**目标**语义；当前 `CinematicController` 为单一全局开关，未按实例求并集，未逐字段核实（方向稿保留）。

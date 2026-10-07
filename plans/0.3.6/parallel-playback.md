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

### 3.3 主视角与听者（方向）

- 玩家最终看到的画面 = **合成层输出**（所有 lane 按合成参数铺屏的结果）；没有任何 lane 时 = 原版视角。
- 音频听者唯一：由**主 lane 所属实例**的 `meta.listener` 决定；无 lane 的实例不影响听者。主 lane 的判定规则与[画面合成](./camera-composition.md)一起定。

> **架构推论（2026-10-07，与[画面合成](./camera-composition.md) / [可变画面](./variable-frame.md) §2 的「主画面也是覆盖层之一」一致）**：多相机渲染落地后，**主画面不再走"单相机替换"路径**——它只是「dest=全屏、opacity=1」的全屏 lane 特例。相机画面与图片 / 字幕 / 黑边同类：lane 输出纹理进覆盖层体系，位置 / 大小 / 取材 / 不透明度 / 叠放顺序全部走关键帧（完全关键帧控制）。
>
> 随之而来：现有"把虚拟相机写进唯一主 `Camera`"的替换链路——`CameraMixin` 主相机分支、`GameRendererMixin` 的 getFov / roll 分支、`LevelRendererMixin` 视图中心改写、`CinematicOcclusion` 整帧遮挡决策——在理想态下是**过渡实现**，最终随 lane 化收敛（不再被调用 / 删除）。
> 两点保留：① 每个 lane 的相机仍复用"把模组相机状态写进一个 `Camera` 实例 + 独立投影"的机制（四象限原型已验证）；② 单 lane 全屏（dest=全屏、opacity=1）可作为优化走旧路径省一遍渲染（每 lane ≈3.4–3.6 ms），是否保留执行时定。

### 3.4 叠加秩序（方向）

多实例的 OVERLAY / LETTERBOX 叠放顺序：先按实例（启动顺序），再按**轨道层级 → 轨道内 clip 顺序（后面的在上）→ 层内 `z_index`**（轨道层级与 clip 顺序规则见[画面合成](./camera-composition.md) §1）。

### 3.5 触发器与重入（方向）

- 触发器命中 = **新建实例**，不再因“有脚本在播”被阻塞。
- 同脚本重入：当前触发状态机以“脚本 + 玩家”为键，同脚本第二实例会撞键。
  - 倾向：**同脚本同玩家默认单实例**（保持现状语义），跨脚本无限制；
  - 放开同脚本多实例需要先改状态机键（待定）。

### 3.6 与播放队列的关系（待定）

现有“不可打断 → 排队”语义在并行模型下的去留。候选：**互斥组**——作者声明哪些脚本互斥才排队，默认可并行。

### 3.7 服务端（方向）

- 事件时间线、触发状态机按实例维护；
- 网络包带实例 id；
- 跳过投票按实例记账。

---

## 4. 现状差距（方向）

- `CameraManager` 单实例 + 单 `pendingScript` 槽 → 改**实例列表**。
- 触发器的 `shouldSkip`（播放期间跳过）语义要按实例重述。
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

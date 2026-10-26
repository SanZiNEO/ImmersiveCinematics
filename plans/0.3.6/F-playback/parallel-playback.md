# 并行播放：多脚本实例与画面 lane

> 状态：实现中（实例模型与播放关系已落地；多实例音频锚点与跳过投票界面未做；跨维度是 lane 源属性，不改实例模型；跨维度已实现并随 F8 实机验证）
> 范围：客户端播放模型：一次播放 = 一个实例，可同时存在多个；生命周期、隔离、叠放与到达关系
> 关联：[画面合成](camera-composition.md)、[多相机渲染](multi-camera-rendering.md)、[相机状态与覆盖链](../../complete/0.3.6/B-camera/camera-state-plan.md)、[播放关系](../C-trigger/playback-relations.md)

## 一、摘要

播放以实例为单位：每次真正开始播放新建实例，实例自带时间轴、轨道状态、音频、玩家移动控制与覆盖层归属。多个实例可同时存在，不设上限；实例内每条相机轨产出一条画面 lane，所有 lane 进入合成层。本文定义隔离、收尾、叠放与到达关系。lane 的维度源是画面属性，不改变实例 / 队列 / 叠放模型；CAMERA clip 的 `dimension` 缺省 / 空串 = 玩家当前维度，见 [跨维度相机](cross-dimension-camera.md)；已实现并随 F8 实机验证。

## 二、为什么做

### 2.1 问题

- 一次只能播一条脚本：补音效或字幕只能塞进同一条脚本。
- 相机状态只有一份，片段不能重叠——叠化、分屏、无缝覆盖无处落脚。
- 跳过、暂停联动、HUD 屏蔽是全局开关，一次声明波及所有播放。
- 新请求只有「替换」和「排队」，辅助脚本被主脚本挡住。

### 2.2 目标

- 两条脚本同时播放，相机、音频、覆盖层互不干扰。
- 一条脚本内多相机同时出画，各自取材与摆放。
- 跳过、退出、结束只作用于目标实例。
- 控制按全部实例求并集，实例减少后收敛；队列不设上限。
- 编辑器预览独立于游戏播放。

### 2.3 非目标

- 不做跨实例逐 lane 混排。
- 不放开同脚本同玩家的第二个实例。
- 不做多实例跳过投票界面。
- 不设实例数 / 相机数阈值，不定义多实例音频锚点。

## 三、方案

一次播放就是一次独立的播放。同时触发两条脚本，两条都播，各跑自己的时间轴；谁也不会替谁让路，除非脚本自己声明了关系。可声明的关系有三种：并行（默认，来了就一起播）、独占（我播时别的脚本先排队）、可被顶掉（新脚本来了我就让位）。排队按优先级从高到低、同级先来先接，某条脚本结束后能播的接着播。

画面按实例整体叠放：后启动的实例整个盖在先启动的实例之上，实例内部再按轨道与片段顺序排。每个相机各出一幅画面，取哪一块、放到屏幕哪一块、多透明，都由该相机片段的关键帧决定；没有画面的实例（纯音效、纯字幕）也是合法的并行成员。

玩家看到的始终是合成结果，一条画面都没有时回到原版视角。跳过键结束最上层那次播放，直到最后一个结束才整体收尾；游戏暂停时只要有一个在播实例声明跟随暂停，整条时间线就冻结。

编辑器预览是独立通道：有自己的播放头，可反复定位与重放，不参与游戏侧的并集、暂停联动与网络上报。

## 四、技术细节

### 4.1 核心模型

- **播放实例**：一次播放的载体——自己的播放器、启动时快照的行为与播放模式、退场状态与退出原因、覆盖链修改器、规则引擎、预览标志与实例 id。
- **实例注册表**：活跃实例的有序列表（启动顺序 = 叠放顺序，顶层 = 最后一个），提供同脚本 / 按 id / 按播放模式查询与每帧两份相机片段缓存（并集、顶层自身）。
- **画面 lane**：相机快照 + 合成参数 + 维度源；维度不影响实例顺序、队列与叠放，只决定该 lane 用哪个 `DimensionView` 渲染（跨维度已实现并随 F8 实机验证）。
- **生命周期与队列**：到达裁决、统一退出、渐出退场、立即停用、接播与全局复位；待接播槽 + 按脚本 id 的优先队列（优先级降序、同级先入先出）。
- **预览通道与网络账本**：预览实例、播放头与直控标志；开始回执、暂停握手、结束通知与跳过投票复位（只记游戏实例）。
- **帧驱动与共享状态**：每帧推进时钟、逐实例驱动、写缓存、重建快照；门面只转发，状态持有者保存六参数缓冲与统一快照，覆盖链合成 Base 层与修改器。

### 4.2 契约

- 每次真正开始播放新建实例；游戏实例插在预览实例之前，预览恒为末位；本地来源的实例 id 为空串。
- 实例只由相机门面创建与驱动；线程契约 = 客户端主线程。
- 生命周期判定只读实例自己的行为快照，无快照取全局默认值。
- 退出走统一入口：按原因映射完成原因，再由实例开关决定是否拒绝；接受后渐出退场，结束由帧驱动判定后停用。
- 立即停用顺序固定：出列 → 通知账本 → 清理实例 → 接播 → 全局复位或重算并集；接播先于复位。
- 队列跨实例存活，全停才清空；覆盖层带实例归属，渐出与清理按归属过滤；账本只在真正开始播放时上报，暂停握手按实例各发一条。

### 4.3 数据形态

- 实例身份：播放实例 id（账本键，编辑器增量替换不变）、脚本 id、预览标志。
- 实例快照：行为（可跳过 / 可打断 / 末尾保持 / 跟随暂停 + 开关与 HUD 层声明）、播放模式（并行 / 独占 / 可被顶掉）、退场标志、退出原因。
- 顺序即语义：实例列表顺序 = 叠放顺序 = 帧驱动顺序 = lane 注册顺序。
- 队列项：脚本 + 实例 id + 入队序号，排序键 = 优先级与序号；帧缓存 = 两个布尔（全实例并集 / 顶层自身）。
- 服务端账本：脚本 id → 实例 id → 播放记录（观看者、跳过投票、事件时间线）。

### 4.4 边界

- 铺屏、排序与混合归画面合成；lane 渲染、光影与优化模组归多相机渲染。
- 相机状态覆盖链归相机状态文档；预览通道不参与游戏侧账本、并集与暂停联动；服务端不参与到达裁决。
- 维度源归多相机渲染 / 跨维度相机；并行播放只负责 lane 顺序，不定义 view 创建、数据流或维度缺省（缺省 = 玩家当前维度）。跨维度已实现并随 F8 实机验证。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 单实例 + 全局相机状态 | 一个播放器、一套相机状态 | 跨脚本协作做不到；片段不能重叠 | 表达力被模型卡死 |
| 实例列表但全局互斥 | 同一时刻只允许一个实例 | 实现简单、状态干净 | 辅助音效与覆盖层无法并行 |
| 每实例一个线程 | 时间轴与轨道各跑各的线程 | 同步与竞态成本极高 | 收益为零，渲染仍单线程 |
| 合成导演统一编排 | 独立导演脚本引用并排序所有 lane | 需要跨脚本 lane 寻址 | 先做自声明 |
| 框架限制实例数 / 相机数 | 超过阈值排队或拒绝 | 资源可控 | 场景差异太大，作者自控 |

**本方案的缺点**：

- 叠放是整体的：不能把 A 的一条 lane 插到 B 的两条之间。
- 听者唯一：多实例时只有顶层实例决定听者，其它实例的空间音频只能相对它摆放。
- 每实例各持一份播放器与覆盖层，成本随实例数增长，不做自动降级。
- 游戏实例共用一条虚拟时钟，暂停联动只能整体生效。

## 六、实现结果

状态：实现中

实例模型、跨脚本并行、每实例生命周期、控制并集、预览隔离、队列与三态播放关系已落地；多实例音频锚点、跳过投票界面与成本曲线未做。

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 不做互斥组、跨脚本全并行；队列容量 8 | 三态播放关系；队列不设上限 | 播放关系后独立成篇引入 |
| 暂停只影响声明联动的实例 | 并集：任一实例声明即全部冻结 | 共用一条时钟 |
| 听者口径待对齐 | 已对齐：顶层实例声明 + 其自身相机片段 | 并集误判顶层听者 |
| 实例 id 协议形态未定 | 复用播放请求的传输层标识，本地为空串 | 该标识本就唯一 |
| 顶层 = 最晚启动的实例 | 游戏实例按启动顺序，预览恒为末位 | 预览是独立通道 |
| 触发状态机按实例维护；同脚本多实例可放开 | 未采用：键不变、同脚本单实例 | 与既有语义一致 |

实测：队列接播无头探针 68/68，独占阻塞与按序接播已实机覆盖；控制并集由离线性质测试覆盖，多实例并集未实机验证。跨维度：lane 的维度源已实现并随 F8 实机验证；不改变实例 / 队列 / 叠放模型；缺省维度 = 玩家当前维度。

## 附录：重写前原文

### 0.3.6 并行播放：多脚本实例与画面 lane

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [画面合成](camera-composition.md)
> - [多相机渲染](multi-camera-rendering.md)
> - [相机状态与覆盖链](../../complete/0.3.6/B-camera/camera-state-plan.md)

> **状态（2026-10-18）**：步骤 1–6 已落地（§7）；F2 队列接播已完成并实机覆盖；剩余为 §5 / §6 的开放项（汇总见[本目录索引](README.md) §4）。

---

#### 1. 定位（已确认）

改造前（0.3.6 之前）的播放模型是**单实例**：`CameraManager` 持有一个 `ScriptPlayer`、一个 `pendingScript` 槽，同一时刻只有一套虚拟相机状态。播脚本 A 时想同时用脚本 B 补音效、补 overlay，做不到。**2026-10-07 起已改为实例列表**（§7 步骤 1–6 落地记录）。

本文确立的模型：

> **播放 = 实例。多个实例可以同时存在，不设数量上限。**

- 实例 = 一个正在播放的脚本：独立的时间轴、轨道状态、生命周期。
- 脚本 A（相机 a1、相机 a2、音频、overlay）和脚本 B（同样全套）可以并存。
- 单个脚本内也可以有多条 CAMERA 轨 / 多个相机——每个相机是一个**画面 lane**（产出一张相机画面的通道），lane 同样不设上限。

**原则（已确认）**：造好用的底层，而不是造限制多的工具。

- 框架不设实例数 / 相机数阈值；渲染压力由作者按场景自控，框架提供性能档位（见[多相机渲染](multi-camera-rendering.md)）。**原型实测支持这条**：1 / 4 / 16 / 25 画面成本线性、无拐点（单画面 ≈3.4–3.6 ms，主画面稳态 2–3 ms，见 `quadrant-perf/summary.md`）。
- **lane 模型的红利（已确认·2026-10-07）**：以前不允许 clip 时间重叠，是因为只有一个相机（同一时刻只能有一个相机状态）；lane 之后每个活跃 clip 各自渲染自己的画面，于是**时间重叠、叠化、无空隙全屏覆盖（不露原版画面）、分层调色、多机位合成**等都自然成立——这些提升全部来自"多实例 × 多 lane"这一个底层能力。
- 优化模组与光影模组普遍在做二次渲染，说明多画面的渲染压力本身在可控范围内。

**“单线程”澄清**：这里说的是播放模型的单实例，不是 Java 线程；渲染仍在渲染线程，逻辑仍在 tick。

**不属于本文**：

- 画面 lane 怎么铺到屏幕 → [画面合成](camera-composition.md)
- 多张相机画面怎么渲染出来 → [多相机渲染](multi-camera-rendering.md)

---

#### 2. 概念模型（已确认）

```text
播放实例（一个正在播放的脚本）
 ├─ 画面 lane × N（每条 CAMERA 轨 / 每个相机一个，N ≥ 0）
 └─ 非画面轨（AUDIO / EVENT / OVERLAY / LETTERBOX / MOD_EVENT）
```

- **画面 lane**：产出相机画面纹理，交给合成层上屏。没有 lane 的实例（纯音效 / 事件 / overlay 脚本）是合法的并行成员——这就是“播 A 时 B 辅助音效”的落点。
- **非画面轨**只属于实例自己，互不共享。

---

#### 3. 语义规则

##### 3.1 生命周期按实例独立（已确认）

`skippable` / `interruptible` / `hold_at_end` / `pause_when_game_paused` / 跳过投票，全部按实例独立计算。跳过实例 A 不影响实例 B；游戏暂停只暂停声明了联动的实例。

##### 3.2 运行时控制取并集（已确认）

行为开关（`hide_hud`、键鼠屏蔽、`suppress_bob` 等）逐位取**并集**：任一实例要求隐藏 / 屏蔽即生效；该实例结束后，按剩余实例重新求并集。

##### 3.3 主视角与听者（已确认·2026-10-07）

- 玩家最终看到的画面 = **合成层输出**（所有 lane 按合成参数铺屏的结果）；没有任何 lane 时 = 原版视角。
- **多脚本同时播放是功能，不是 bug**（用户原则）：**有什么就放什么，没有就不放**——活跃实例的 lane 全部渲染、全部上屏，不做互斥/裁剪。
- 音频听者唯一：**后来者居上**——由**启动最晚的活跃实例**的 `meta.listener` 决定（与叠加秩序的实例启动顺序同一规则）；无 lane 的实例不影响听者。
  - 实现状态（2026-10-18）：`AudioListenerController` 已按顶层实例取值，但 `hasActiveCameraClip` 仍按**并集**判定 → 多实例时「是否启用镜头听者」的口径未对齐。**开放项**，见 §5 / §6。

> **架构推论（2026-10-07，与[画面合成](camera-composition.md) / [可变画面](../D-transition/variable-frame.md) §2 的「整屏画面也是覆盖层之一」一致）**：多相机渲染落地后，**玩家看到的画面不再走"单相机替换"路径**——它只是「dest=全屏、opacity=1」的全屏 lane 特例，合成顺序不等于相机身份。相机画面与图片 / 字幕 / 黑边同类：lane 输出纹理进覆盖层体系，位置 / 大小 / 取材 / 不透明度 / 叠放顺序全部走关键帧（完全关键帧控制）。
>
> 随之而来：现有"把虚拟相机写进唯一主 `Camera`"的替换链路——`CameraMixin` 旧单相机替换分支、`GameRendererMixin` 的 getFov / roll 分支、`LevelRendererMixin` 视图中心改写、`CinematicOcclusion` 整帧遮挡决策——在理想态下是**过渡实现**，最终随 lane 化收敛（不再被调用 / 删除）。**〔→ `CinematicOcclusion` 已删（2026-10-11，B3-d，300daa3）：per-lane 可见集合（B3-b）落地后整帧遮挡决策不再需要；其余链项见下方 §3.3 落地表〕**
> 两点保留：① 每个 lane 的相机仍复用"把模组相机状态写进一个 `Camera` 实例 + 独立投影"的机制（四象限原型已验证）；② 单 lane 全屏（dest=全屏、opacity=1）可作为优化走旧路径省一遍渲染（每 lane ≈3.4–3.6 ms），是否保留执行时定。

**✅ 落地（2026-10-07，旧「单相机替换」链退役）**：玩家看到的画面不再走单相机替换路径，画面完全由 lane 合成层承担；零 lane 时 = 原版视角（回归 vanilla）。逐点处置：

| 项 | 处置 |
|---|---|
| `CameraMixin.onSetup` 旧单相机替换分支 | **删除**（lane 分支保留：lane 相机仍写自己的 `Camera` 实例 = 保留点 ①） |
| `CameraMixin.onGetEntity` / `onIsDetached` 旧替换分支 | **删除**（lane 分支保留） |
| `GameRendererMixin.onGetFov` 旧替换分支 | **删除**（lane 分支保留：lane 自己的 fov/zoom → 生效 FOV） |
| `GameRendererMixin` roll 旧替换钩子（`onBeforePrepareCullFrustum`） | **删除**（lane 的 roll 本就在 `LaneRenderer.renderLane` 内施加到该 lane 的 PoseStack） |
| `LevelRendererMixin` 视图中心改写（`ModifyVariable` ×3） | **改为 lane 驱动（非 lane pass 的共享网格整帧单一中心）**：中心 = 本帧合成顺序末位 lane 的相机位置（`LaneRenderer.topLane()`），该 lane 跨维度或无活跃 lane 时回落原版玩家坐标。**lane pass 仍各用各的中心**（`activeLaneState`）；只有共享的可见区块网格（`ViewArea` + `renderChunkStorage`）整帧一份，逐 pass 换中心会让每个 pass 把整张网格搬走并把区块全部置脏重建（`ViewArea.repositionCamera` → `RenderChunk.setOrigin` → `reset()` → `dirty`），一帧内非 lane pass + N lane 反复来回搬 = 持续重建、画面缺块。共享网格中心不是「主相机」，也不用于服务端当前维度中心上报（见 `CameraCenterReporter`） |
| `CinematicOcclusion` 整帧遮挡决策 | **已删除（B3-d）**：现行为 **per-lane 独立可见集合**（候选集 / 可见集合 / 门闩按 lane 槽字段级换入换出）+ **逐 pass `smartCull` 判定**（lane pass 的相机在实心方块内 → 本 pass `smartCull=false`；原版 `player.isSpectator()` 判定对原版玩家相机本就正确，本模组不改写）。见 `multi-camera-rendering.md` §12.9 |
| 帧驱动 `CameraManager.onRenderFrame()` | **挂点迁移**：原唯一调用点 `CameraMixin.onSetup`（旧单相机替换分支）→ `LaneRendererMixin`（`GameRenderer.renderLevel` RETURN）→ **2026-10-07 再前移到 `GameRendererMixin`（`GameRenderer.render` HEAD，世界渲染之前）**：视图中心（`setupRender` 改写）读 lane 表，原挂点会让它滞后一帧。顺序仍是「先驱动（填 lane 快照）→ 再注册 lane → 再渲染 lane」，调用条件与原挂点等价（`renderLevel && level != null`） |
| 听者相机（`SoundManagerMixin`） | **消费方迁移**：原版 `Minecraft.tick` 传的是玩家相机；旧替换链退役后不再有模组「主相机」→ `listener=camera` 改由 `AudioListenerController.cameraListener()` 显式提供镜头代理（`CameraAccessor` 写位置/朝向/initialized） |
| `getCameraState()` 快照 | **保留**（仍有消费方：听者位置/听者相机代理、预览 HUD、客户端条件查询）；值 = 顶层实例顶层活跃 clip 六参数，与合成顺序末位 lane 同源同值。它**不是**服务端 `CameraCenterManager` 的当前维度中心上报源——后者由 `CameraCenterReporter` 按当前维度第一条 lane 选择，目标维度预取景 lane 不参与 |

> 未保留点 ②：单 lane 全屏优化未做——一律走全 lane 渲染（每 lane ≈3.4–3.6 ms）。
> 已知后果：~~渲染优化模组（Sodium / Embeddium）下 lane 渲染被禁用 → 过场看不到相机画面~~。整条禁用已移除；检测改为跳过 `setupRender` 专属 mixin、走优化器路径；lane 始终渲染。**光影下的跨视角污染已于 2026-10-18 隔离**（Fabric / Iris 1.7.6 三包 A/B 干净；F3 验收通过，Forge / Oculus 1.8.0 同 jar 已部署，见[多相机渲染](multi-camera-rendering.md) §12.10）。

##### 3.4 叠加秩序（已确认·2026-10-07）

**后来者居上（整体分层）**：实例**整体**按启动顺序叠放——后启动实例的**全部输出**（其所有 lane 与 OVERLAY/LETTERBOX）整体在上，前者的整体在下；**实例内部各自保持自己的顺序**（轨道层级 → 轨道内 clip 顺序（后面的在上）→ 层内 `z_index`）。**不做跨实例的逐 lane 混排**。多相机就是"相机有什么覆盖就放什么"。

##### 3.5 触发器与重入（方向）

- 触发器命中 = **新建实例**，不再因“有脚本在播”被阻塞。✅ 已落地（2026-10-07）：`shouldSkip` 的“正在播放”门控是同脚本口径，跨脚本不阻塞。
- 同脚本重入：当前触发状态机以“脚本 + 玩家”为键，同脚本第二实例会撞键。
  - ✅ 已定稿（2026-10-07）：**同脚本同玩家默认单实例**（保持现状语义），跨脚本无限制；
  - 放开同脚本多实例需要先改状态机键（未排期）。

##### 3.6 与播放队列的关系（已确认·2026-10-07：不做互斥组）

默认**全并行**——有什么就放什么。互斥组不做（作者需要互斥时自行用触发器控制脚本起停）。现有“不可打断 → 排队”语义仅保留在同脚本单实例的内部（第二份请求排队），不扩展到跨脚本。

**队列语义（2026-10-17，F2 落地）**：队列不设容量上限；出队按 `priority` / FIFO **逐项判定可播性**（队首不可播时继续看后续项，不整队阻塞）；不再因某条 exclusive 请求一次排空队列；同脚本排队副本不被误启动；编辑器预览实例不参与队列接播。实现与证据见 §7「F2 落地记录」。

##### 3.7 服务端（方向）

- 事件时间线、跳过投票按实例维护；✅ 已落地（2026-10-07）：`ScriptEventManager.scriptPlaybacks` 以 `(scriptId, instanceId)` 两级键，观看者 / 跳过投票 / 事件时间线按实例记账。
- 触发状态机按实例维护：**未采用**——状态机键保持（玩家 + 脚本 + 触发器），与“同脚本同玩家单实例”一致（§3.5）。
- 网络包带实例 id；✅ 已落地（2026-10-07）：`S2CPlayScriptPacket` / `C2SPlaybackStartedPacket` / `C2SScriptFinishedPacket` / `C2SScriptPausePacket` / `S2CStopScriptPacket` 均带实例 id。**协议形态（id 生成 / 校验 / 跨端对齐）仍开放**，见 §5 / §6。
- 跳过投票按实例记账。✅ 已落地（同上）。

---

#### 4. 现状差距（状态·2026-10-18）

- ✅ 已落地（2026-10-07，§7 步骤 1 落地记录）：`CameraManager` 单实例 + 单 `pendingScript` 槽 → **实例列表**（`PlaybackInstance`）；跨脚本并行、每实例独立生命周期、运行时控制并集均已落地（步骤 2–4）。
- ✅ 已落地（2026-10-07，§7 步骤 6 落地记录）：触发器的 `shouldSkip`（播放期间跳过）按脚本口径——同脚本同玩家跳过（单实例），跨脚本不阻塞。
- ✅ 已落地（2026-10-07，§7 步骤 5）：编辑器预览通道与游戏播放通道隔离——预览是独立实例，不挤掉游戏内播放。
- ✅ 已落地（2026-10-17，F2，见 §7「F2 落地记录」）：队列按脚本匹配接播——出队按 `priority` / FIFO 逐项判定可播性、无 exclusive 一次排空、同脚本排队副本不被误启动、去掉容量 8；**无头行为探针 68/68，实机由 C 轮队列接播覆盖（2026-10-18）**。
- 🟡 仍开放：听者后来者居上的口径对齐、多实例 AUDIO 空间锚点、跳过投票 UI、实例与 Base Provider 的对应关系、实例 id 网络协议形态（§5 / §6）。

---

#### 5. 可能的问题（状态·2026-10-18）

- 🟡 **听者「后来者居上」口径未对齐**：`AudioListenerController` 已按顶层实例取值，但 `hasActiveCameraClip` 仍是并集——多实例时「是否启用镜头听者」可能按「任一实例有相机 clip」判定。
- 🟡 **多实例 AUDIO 的空间锚点**（听者唯一，见 §3.3）。
- 🟡 **跳过投票 UI**：多实例同时可跳过时，玩家投的是哪一个（服务端已按实例记账；`SkipHudRenderer` 只呈现顶层实例的跳过提示，未做多实例投票 UI）。
- 🟡 **实例与 Base Provider 的对应关系**（见[相机状态与覆盖链](../../complete/0.3.6/B-camera/camera-state-plan.md)）。
- ✅ **区块预加载 ticket**：已裁决不做（2026-10-22，用户拍板：当前版本不做预加载 / 预热，区块加载交原版；见 [camera-center-entities](camera-center-entities.md) §2.7）。
- 🟡 **实例 id 网络协议形态**：包已带 id（§3.7），但 id 的生成 / 校验 / 跨端对齐未定。
- ✅ **同脚本重入与触发状态机键的冲突**：已定（2026-10-07）——同脚本同玩家保持单实例，跨脚本无限制；放开同脚本多实例需先改状态机键（未排期）。
- 性能：实例数 × lane 数的开销曲线——成本模型见[多相机渲染](multi-camera-rendering.md) §6；lane 侧已由 F5 覆盖 1/4/16/25，实例数未测。

---

#### 6. 待定

- **实例 id 的网络协议形态**：id 生成 / 校验 / 跨端对齐（包字段已于 2026-10-07 落地，见 §3.7）。
- **主 lane 判定规则**（听者归属用；与[画面合成](camera-composition.md) §6 一起定）。

**已定（保留结论）**：

- **互斥组 / 队列语义**：不做互斥组；队列不设容量上限、按脚本匹配接播——F2 实现完成（2026-10-17，无头 68/68，实机由 C 轮队列接播覆盖），语义见 §3.6 与 §7「F2 落地记录」。
- **同脚本重入**：不放开——同脚本同玩家保持单实例（2026-10-07 用户裁决）。

---

#### 7. 落地顺序（方向）

| # | 步骤 | 交付物（完成后我们要什么） | 状态 |
|---|---|---|---|
| 1 | 实例模型：`CameraManager` 改实例列表，每实例独立 `ScriptPlayer` + 轨道状态 | 同一脚本可先后 / 同时起两个实例，日志可验证互不干扰 | ✅ 2026-10-07（下方落地记录） |
| 2 | 生命周期按实例：跳过 / 打断 / `hold_at_end` / 暂停联动按实例计算 | 实例 A 被跳过、实例 B 不受影响；游戏暂停只暂停声明联动的实例 | ✅ 2026-10-07 |
| 3 | 运行时控制并集：行为开关逐位并集，实例结束重新求值 | A 隐藏 HUD、B 不隐藏 → 隐藏；A 结束后恢复按 B 判定 | ✅ 2026-10-07 |
| 4 | lane 抽象与注册表：实例把 CAMERA 轨注册为画面 lane，全部 lane 上屏 | lane 注册表 + 主 lane 选择规则 | ✅ 2026-10-07（上屏见[画面合成](camera-composition.md) §7 步骤 3–5；**主 lane 选择规则仍未定**） |
| 5 | 网络与编辑器隔离：网络包带实例 id；编辑器预览为独立实例 | 编辑器预览不再挤掉游戏内播放 | ✅ 2026-10-07（协议形态仍开放） |
| 6 | 触发器重入规则定稿与实施 | 跨脚本并行触发可用；同脚本语义与现状一致 | ✅ 2026-10-07（同脚本保持单实例） |

> 步骤 1–6 均已落地（2026-10-07）。队列按脚本匹配接播（F2）不属于上表步骤，单独记在下方「F2 落地记录」。

##### 步骤 1 落地记录（2026-10-07）

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

##### 步骤 3 落地记录（2026-10-07）

**范围**：运行时控制取并集（§3.2）落地——`CinematicController.apply(RuntimeBehavior)` 拆成两条职责：`applyLifecycle(behavior)`（只写生命周期开关 skippable / interruptible / holdAtEnd / pauseWhenGamePaused）与 `recomputeUnion(List<RuntimeBehavior>)`（行为开关逐位并集）。消费方一行未改（仍读同一组全局 getter，值 = 并集）；实例增删时 `CameraManager` 重算（`startScriptInternal` 非预览分支、`deactivateNow` 出列后按剩余实例）。

**并集口径**：5 个非三态开关（blockKeyboard / blockMouse / blockMobAi / hideHud / renderPlayerModel）逐位 OR；11 个三态开关 + suppressDistortion 先按**各实例自己的 hide_hud** 解析有效值再 OR；`hud_layers` 键集 = 各实例声明键并集，每个键的值 = **全部实例**有效值 OR（未声明该键的实例按自身 hide_hud 参与投票）。空/全 null = 无实例要求 → 复位默认。

**验证**：单实例与改造前逐位一致；两实例并存逐位 OR + 顺序无关；实例结束后按剩余实例重算；10057 项冒烟（含 400 组随机多实例与独立参考实现比对）。注意：本版本 CameraManager 仍至多 1 个活跃实例，多实例并集只在离线性质测试验证，未在真实客户端跑过双实例。

##### 审查修订（2026-10-07，多实例放开前执行）

| # | 修订 | 说明 |
|---|---|---|
| 1 | **实例 id 挂实例** | `PlaybackInstance` 增 `instanceId` 字段（start 时传入，预览/本地来源为空串）；删除 `CameraManager.instanceIdsByScriptId` 旁路 Map（当前"请求暂存/上报取走"关联不自然，且同脚本被拒请求残留 id 会误报） |
| 2 | **暂停联动 = 并集、跳过提示 = 顶层实例** | `InputRouter` 的暂停联动消费改为任一实例声明 `pause_when_game_paused` 即放行（并集）；`SkipHudRenderer` 的跳过提示只由顶层实例（后来者居上）的 `skippable` 决定；随之删除全局生命周期开关与实例快照的"两套真相" |
| 3 | **finish / stop / pause 包补 instanceId** | 服务端删除"观看者成员→唯一实例"启发式，账本按实例 id 精确解析（started 包已带 id） |

##### 步骤 4/5/6 落地记录（2026-10-07）

- **跨脚本并行已放开**：`playScript` 并行决策树——同脚本冲突实例走原单实例语义（可打断替换该实例 / 不可打断排队或拒绝），跨脚本直接新建实例并行（不排队不打断不阻塞，§3.5/§3.6）；`activeInstance()` 改为顶层（后来者居上）；每帧按启动顺序驱动所有实例；任一实例结束只退该实例，全局复位只在最后一个实例退出时执行；`cameraState` 快照 = 顶层实例状态；暂停握手按实例各发一条（账本按实例）。
- 审查修订①②③ 全部落地（实例 id 挂实例 / 暂停联动并集+跳过提示顶层 / finish·stop·pause 包带 id）。
- 已落地：编辑器预览独立实例（`CameraManager.previewInstance`，§7 步骤 5）；跨实例 lane 收集（`ScriptLaneDriver` 按启动顺序平铺所有实例 lane，后启动实例整体在上）。
- **仍开放**：听者后来者居上——`AudioListenerController` 已按顶层取值，但 `hasActiveCameraClip` 仍为并集，口径待对齐（§5）。
- **队列按脚本匹配接播 = F2**：已于 2026-10-17 实现（无头 68/68，实机由 C 轮队列接播覆盖），见下方「F2 落地记录」。

##### 步骤 6 落地记录（2026-10-07）

**范围**：服务端触发器 `shouldSkip` 的语义重述（§3.5 / §3.7）。

**核实结论（无需改逻辑，现状即目标口径）**：`TriggerEngine.shouldSkip(ServerPlayer, TriggerRegistration)` 的“正在播放”门控调用 `ScriptEventManager.isPlayerPlayingScript(player.getUUID(), reg.getScriptId())`——该方法只按 **scriptId** 取实例集合并判断玩家是否为其中任一实例的观看者，**已是同脚本口径**：

- **同脚本在播 → 跳过**（同脚本同玩家单实例，与实例化前逐点等价）；
- **跨脚本不阻塞**：播放脚本 A 时，指向脚本 B 的触发器 `isPlayerPlayingScript(uuid, "B") == false` → 照常命中并新建 B 实例（客户端多实例已就绪，步骤 4/5 已放开）。

全仓 grep `isPlayerPlayingScript` 仅 `TriggerEngine.shouldSkip` 一处调用；无“任意脚本在播即阻塞”的残留（`isScriptActive` / `isFullyComplete` / `getRemainingViewers` 在 Java 侧无调用点）。触发器状态机键（玩家 + 脚本 + 触发器，`TriggerStateStore` / `enterStates`）不变。

**账本按实例的消费点复核**（§3.7）：`scriptPlaybacks` 以 `scriptId → instanceId → ScriptPlayback` 两级键；开始 / 结束 / 暂停 / 跳过投票 / 事件时间线 / 超时重发全部经 `playback(scriptId, instanceId)` 精确解析实例，`onServerTick` 逐实例驱动，`broadcastSkipVote` 与强制停止均以单实例 `pb` 为单位——无单实例假设残留。

**改动**：仅为语义显式化——`TriggerEngine.shouldSkip` 与 `ScriptEventManager.isPlayerPlayingScript` 补 javadoc 说明同脚本口径；逻辑零变更。

##### F2 落地记录（2026-10-17：队列按脚本匹配接播）

**范围**：`ScriptQueue` 匹配 API + `deactivateNow` 接播语义（§3.6 队列语义的落地）。

| 项 | 落地内容 |
|---|---|
| 出队判定 | 按 `priority` / FIFO **逐项判定可播性**：队首不可播时继续看后续项，不再整队阻塞 |
| exclusive | 不再因某条 exclusive 请求**一次排空**队列 |
| 同脚本副本 | 同脚本排队副本不被误启动（接播只取匹配的副本） |
| 预览隔离 | 编辑器预览实例不参与队列接播 |
| 容量 | 去掉容量 8——队列不设上限（用户裁决「队列不设上限、按脚本控制的排队语义」，`consolidation/decisions.md` ✅5） |

**证据**：无头行为探针 **68/68**；F 轮整体三模块 `compileJava` 通过、29 支 F 轮脚本 Parser / Validator 0 issue（见 `implementation-progress.md` F 轮行）。

**未完成**：真实客户端队列接播待用户实机复验；`camera-core-split` §三 P2 的接播（`pendingScript` / `ScriptQueue`）路径仍**无实机记录**。

---

#### 8. 与 0.4.0 旧稿的关系

本文取代 0.4.0 的 **G1（相机实例队列）**：其实例列表、后台预热的思想并入本文，并扩展为“无上限 + lane + 完整并行语义”的模型。G1 原文已从 0.4.0 抹除。

---

#### 已知缺陷（2026-10-06 代码复查）

> 只读代码审查发现，未在游戏内复现；不影响当前设计，记录备查。

- **`emergencyStop()` 不清队列**：只清 `pendingScript`，不清 `scriptQueue`（字面属实）；但 `deactivateNow` 会先调用 `reset()` 清空 `scriptQueue`，故“从队列接播”的分支实际不可达，世界退出不会误启下一脚本。（原文“会从队列接播”结论有误，见事实核查）
- **`C2SPlaybackStarted` 无条件回报**：`ClientScriptReceiver.handlePlayScript` 不检查 `playScript` 返回值（0 拒绝 / 2 排队也回报“已开始”）→ 并行化后服务端账本会错位（需按实例 id + 实际结果回报）。**✅ 已修复（2026-10-07）**：回执拆成两件事——传输层 ACK（无条件回，抑制超时重发）与播放账本（仅真正开始播放时由 `CameraManager.reportPlaybackStarted` 上报，3 个调用点：直接开始 / pendingScript 接播 / 队列接播）；`C2SPlaybackStartedPacket` 增加 `started` 字段。实例 id 已于 2026-10-07 落地（§3.7）；协议形态仍开放。

---

#### 事实核查（2026-10-07）

> 以仓库代码为准逐条核对。路径均为仓库根相对路径。
> **本小节是 2026-10-07 快照**：此后落地的实例列表、跨脚本并行、实例 id、多 lane 上屏与 F2 队列见 §3.3–§3.7、§4、§7 各落地记录；下文「现状无实例 id / 单实例」等描述不再代表当前代码。

##### ① 核实为真的断言

1. **§1 “单实例：一个 `ScriptPlayer`、一个 `pendingScript` 槽、同一时刻一套虚拟相机状态”** —— 属实。`common/.../camera/CameraManager.java`：`private final ScriptPlayer scriptPlayer = new ScriptPlayer();`、`private CinematicScript pendingScript = null;`、单一 `private final CameraProperties activeProperties` / `activePath`。
2. **§4 “`CameraManager` 单实例 + 单 `pendingScript` 槽”** —— 属实（同上）；另持有 `private final ScriptQueue scriptQueue = new ScriptQueue();`（`camera/ScriptQueue.java`，容量 `CAPACITY = 8`，`PriorityQueue` 按 `meta.priority` 降序、同优先级 FIFO）。
3. **§4 “触发器的 `shouldSkip`（播放期间跳过）语义”** —— 属实。`trigger/server/TriggerEngine.java:196` `shouldSkip(...)`：若 `ScriptEventManager.INSTANCE.isPlayerPlayingScript(player.getUUID(), reg.getScriptId())` 返回 true 则跳过；两处轮询入口（`TriggerEngine.java:91`、`:113`）均调用。播放期间跳过且状态机不更新。
4. **§4 “编辑器预览与游戏播放共用 `CameraManager`”** —— 属实。`webui/WebPreviewScreen.java`（`enterFlightMode` 用 `CameraManager.INSTANCE.getPath()`）、`webui/WebEditorApi.java`（`pushPlaybackState` 读 `CameraManager.INSTANCE.getGameTimeSeconds()`）、`editor/EditorScreen.java:1722`（`CameraManager.INSTANCE.exitPreview()`）+ `PreviewCapture.capture(minecraft)`（`:1251`）。预览走 `CameraManager` 的 `previewMode` 分支，与游戏播放同一 `scriptPlayer`。
5. **已知缺陷 2 “`C2SPlaybackStarted` 无条件回报”** —— 属实。`trigger/client/ClientScriptReceiver.handlePlayScript`：`CameraManager.INSTANCE.playCinematic(script);` 后无条件 `NetworkHandler.sendToServer(new C2SPlaybackStartedPacket(script.getId(), packet.getRefId()))`，未检查返回值。`CameraManager.playScript` 的 javadoc 明确 `@return 0=被拒绝, 1=已开始播放, 2=已排队等待`（`CameraManager.java:145`）；`playCinematic` 直接丢弃返回值（`:177-179`）。
6. **§3.7 “网络包带实例 id（方向）/ 触发状态机按实例维护（方向）”** —— 现状确实无实例 id：`S2CPlayScriptPacket` 仅 `scriptJson` + `refId`；`C2SPlaybackStartedPacket` 仅 `scriptId` + `refId`；`ScriptEventManager` 的 `Map<String, ScriptPlayback> scriptPlaybacks` 以 **scriptId** 为键，`ScriptPlayback` 只含 `Set<UUID> viewers` / `Set<UUID> skipVoters`；`ScriptSyncState` 只做脚本文件指纹登记（`Map<String,String> fingerprints`），与实例无关。故“带实例 id”确为尚未实现的方向。
7. **§1 原型数据（引用 `quadrant-perf/summary.md`）** —— 绝对数值属实：结论表列 1/4/16/25 画面帧间隔 8/17/61/98 ms、单画面 3.4/3.5/3.6 ms、主画面各档 2–3 ms、线性。原「≈1.2–1.4 × 主画面」比值经 2026-10-07 跨文档核查与 CSV 明细不符，已在本文与引用处删除（裁决见 multi-camera-rendering.md 核查小节）。
8. **§8 “G1 原文已从 0.4.0 抹除”** —— 属实。`plans/0.4.0/README.md:12` 注明“G1/G2 已抹除”，`:19` 注“相机实例队列（G1）→ `plans/0.3.6/parallel-playback.md`（取代）”；原 `plans/0.4.0/camera-queue-pip-dimension.md` 已删除，跨维度内容归 `plans/0.3.6/F-playback/cross-dimension-camera.md`；G1/G2 已被 0.3.6 三篇取代并抹除。全 0.4.0 目录 grep `G1` 仅剩这些“已抹除”说明。

##### ② 已修正的断言

1. **已知缺陷 1（`emergencyStop()` 不清队列）** —— 旧说法：“只清 `pendingScript`，不清 `scriptQueue`；`deactivateNow` 会从队列接播 → 世界退出时可能误启下一脚本。”
   - 新事实：`emergencyStop()` 确实未直接调用 `scriptQueue.clear()`（只置 `pendingScript = null`），**但** `deactivateNow()` 在接播判断前调用了 `reset()`，而 `CameraManager.reset()` 内含 `scriptQueue.clear()`；随后 `else if (!scriptQueue.isEmpty()) startScriptInternal(scriptQueue.poll())` 恒为 false（队列已被清空）。故“从队列接播”分支不可达，世界退出**不会**误启下一脚本。
   - 证据：`camera/CameraManager.java` 的 `deactivateNow()`（`reset();` 位于 `pendingScript`/`scriptQueue` 判断之前）与 `reset()`（`scriptQueue.clear();`）。
   - **冲突裁决**：`git log -1 --format=%cI` —— 文档 `2026-10-06T21:16:28+08:00`；`camera/CameraManager.java` `2026-09-09T10:42:54+08:00`；`scriptQueue.clear()` 于 `2026-08-10T17:24:47+08:00`（b3c8724）已在 `reset()` 中（`git log -S`）。文档时间更晚，但其“会从队列接播”属对代码的观察性结论，且所涉代码在复查时点即已存在 → 按代码事实修正。（如需保留“未复现”存疑，可在游戏内断线场景再验一次。）

##### ③ 补全的信息（原文只给方向、此处补类/方法名）

- §1/§4 单实例载体：`CameraManager`（`scriptPlayer`、`pendingScript`、`scriptQueue`、`activeProperties`/`activePath`）。
- §3.5 “同脚本重入：当前触发状态机以‘脚本 + 玩家’为键” —— 精确为：`TriggerStateStore` 以 `Map<UUID, PlayerTriggerState>`（玩家）为一级键，二级键为 `scriptId` + `triggerId`（`TriggerStateStore.isTriggered(UUID, String scriptId, String triggerId)`）；播放态去重另在 `ScriptEventManager.isPlayerPlayingScript(UUID, String scriptId)`（玩家 + 脚本）。即键实为“玩家 + 脚本 + 触发器”，非仅“脚本 + 玩家”。
- §4 触发器跳过语义所在方法：`TriggerEngine.shouldSkip(ServerPlayer, TriggerRegistration)`（`TriggerEngine.java:196`），调用点 `:91`、`:113`。
- §3.7 服务端现状承载类：`ScriptEventManager`（`scriptPlaybacks` 以 scriptId 为键、`ScriptPlayback` 含 `viewers`/`skipVoters`/`skipVoteRatio`/`triggeredKeyframes`）、`ScriptSyncState`（脚本文件指纹，非实例）、网络包 `S2CPlayScriptPacket`/`C2SPlaybackStartedPacket`（字段 `scriptJson`/`scriptId` + `refId`）。
- 已知缺陷 2 涉及方法：`ClientScriptReceiver.handlePlayScript`、`CameraManager.playCinematic` / `playScript`、`C2SPlaybackStartedPacket`。

##### ④ 无法核实的断言（未验证）

- §1“优化模组与光影模组普遍在做二次渲染，说明多画面的渲染压力本身在可控范围内”—— 属论据性陈述，未指定具体模组/文件，未验证（`render-second-pass-cost.md` 另有“仓库内不存在第二遍世界渲染”的相反限定）。
- §3.7“事件时间线、触发状态机按实例维护”“跳过投票按实例记账”—— 为**方向**（当前均为按 scriptId 记账），非现状断言；作为方向保留，不判对错。
- §3.2 行为开关“逐位取并集”（`hide_hud`/键鼠屏蔽/`suppress_bob`）—— 为**目标**语义；当前 `CinematicController` 为单一全局开关，未按实例求并集，未逐字段核实（方向稿保留）。

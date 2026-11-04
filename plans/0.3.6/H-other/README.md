# H 轮：HUD / 实机验证 / 触发器状态层 / 相机视图实体活性

> 本轮包含 HUD、实机验证、触发器状态层，以及相机视图实体活性（H10）。
> 区域同步（H1）已于 2026-11-04 由用户决定取消（暂不做），设计文档 `region-sync.md` 已删除，见 git 历史。
> 其中 H7 / H8 是 2026-10-18 新加入的两份既有计划（`state-tracking.md` = P1-impl、
> `trigger-continuity.md` = P3-impl，此前一直挂在 C-trigger 下）；H10 是 F8 后续的下一项设计稿。

## 1. 任务清单

| ID | 任务 | 文档 | 状态 |
|---|---|---|---|
| H1 | ~~region-sync 全部实现（平移 / 镜像旋转 / 多区域链 / 传送 / 防乒乓 / 朝向策略）~~ | ~~`region-sync.md`~~ | ⊘ **已取消（2026-11-04，用户决定暂不做区域同步传送；设计文档已删除，git 历史可查）** |
| H2 | wait-point-track | — | ⊘ 已被 C6 判定规则吸收（等待点 = `until→do` 阻塞形态） |
| H4 | feedback-04 #1 结案（命令归 EVENT 轨） | — | ✅ 2026-10-10（`476b442`） |
| H5 | 文档滞后清理（审计 §三 12 条） | [`doc-lag-2026-11-03.md`](doc-lag-2026-11-03.md) | ✅ 2026-11-03（H-2）：12 条逐条核对，1 条本轮改，2 条文件不在本仓 |
| H6 | 实机验证批次（审计 §四 7 条） | [`verification-2026-11-03.md`](verification-2026-11-03.md) | 🟡 2026-11-03（H-2）：4 条已跑、1 条部分跑、1 条未复现、1 条无环境 |
| **H7** | **P1-impl 事实层**：布尔点状事件判定 → 事实追踪 | [`state-tracking.md`](state-tracking.md) | ✅ **已实现（H-1，2026-11-03）**（见该文档 §六） |
| **H8** | **P3-impl 连续语义**：离散采样 → 连续判定 | [`trigger-continuity.md`](trigger-continuity.md) | ✅ **已实现（H-1，2026-11-03；`observation` 扫掠除外）**（见该文档 §六） |
| **H9** | **跳过投票 + 跳过 HUD**：判定对齐 MC `SleepStatus`、HUD 合成一行、位置可配 | [`skip-vote-and-hud.md`](skip-vote-and-hud.md) | ✅ **已实现（H-2，2026-11-03）**（见该文档 §六） |
| **H10** | **相机视图实体活性**：服务端原版实体跟踪镜像 + 客户端目标世界 tick；不做假玩家、不改 AI goal | [`entity-liveness.md`](entity-liveness.md) | ☐ 设计稿（未实现；F8 后续） |
| **H11** | **HUD 层注册 API**：面向第三方的正式注册入口 + `hud_layers` / 固定分类口径 + Fabric/Forge 边界 | [`hud-layer-api.md`](hud-layer-api.md) | ✅ **已实现（H-2，2026-11-03）**（见该文档 §八） |
| **H12** | **对外接口（API / SPI）**：播放链路、相机只读面、轨道 / 触发器 / 模型 / 曲线 / 前置条件注册门面、注册时机与失败隔离 | [`public-api.md`](public-api.md) | ☐ 设计稿（未实现；轨道注册依赖 `TrackType` 注册表化） |

H7 / H8 之间没有强绑定：H8 的**最小版本**（位置扫掠 / 朝向弧 / 进入边沿）不依赖 H7，可先落地；H8 的窗口与事件时间戳依赖 H7。

---

## 2. H7 / H8 的实现现状（2026-11-03 核对）

### 2.1 H7（P1-impl）：已实现

`state-tracking.md` §六 是完整回写。要点：

| 计划要点 | 现状 |
|---|---|
| §4.1 事实记录与存储 | `trigger/server/fact/`：`Fact`（八维）+ `PlayerFacts`（按类型分片 + 常驻索引 + 保留集）+ `FactLedger`（按玩家、SNBT v2） |
| §4.2 查询原语 | 存在性 / 计数 / 最近一次（含结果）/ 时间窗 / 通配 / 按结果过滤；前四类走累计索引，窗口受保留深度约束 |
| 触发器独立身份 | `TriggerDefinition.id`（可选）+ 缺省 `type#声明下标`；232 条既有声明本就带合法 id，此前被解析层忽略 |
| 播放生命周期事实 | `PLAYBACK_STARTED` / `PLAYBACK_FINISHED(script, instance, result)`；跳过 / 打断 / 自然结束可区分 |
| 五份记录 | ①持久布尔 → 事实（类已删）；②八个 tracker → 事实，`InventoryTracker` 留作采样基线；③账本只留当前态，起止与投票进事实；④区域进入态 → `TriggerEdge` + 区域事实；⑤客户端镜像删除 |
| 持久性 / 旧档 | 按事实类型声明；v1 一次性转换（旧触发器键仅在唯一匹配时映射，歧义丢弃） |
| 跳过票空指针 | `ScriptPlayback.server` 由构造器赋值，缺陷已修 |

### 2.2 H8（P3-impl）：已实现（`observation` 扫掠除外）

`trigger-continuity.md` §六 是完整回写。要点：

| 计划要点 | 现状 |
|---|---|
| 采样快照 / 轨迹补判 | `PlayerMotion`（每 tick 基线 + 代次）+ `TriggerEdge.lastSample`（按玩家 × 触发器） |
| 区间判定 | `SegmentSweep.hitsBox / hitsSphere / hitsFacing / anyPoint`；点判定优先，未命中才段判定 |
| 边沿 / 停留 | `TriggerEdge`（inside / armed / trueSince）；`dwell` 可配（0–1200 tick） |
| 窗口 / 事件子条件 | `ContinuitySpec.window` + `FactWindow`；`window = 0` 仍拒绝事件类子条件 |
| 不连续标记 | 维度切换 / 载具变化 / 每 tick 位移 > 16 格 → 代次自增，不参与扫掠 |
| 默认档 | 未声明 `continuity` 走旧快速路径，行为逐位不变 |

N1–N9 的现状：N1/N2/N3（位置 / 朝向 / 边沿）在开启 `sweep` 后消失；N4（事件单槽）已由事实层消除；N5/N6（事件子条件 / 窗口）在 `window > 0` 时按窗口解禁；N7（同 tick 顺序）与 N8（净变化）**未变**（分别归后续轮次与 `InventoryTracker` 契约）；N9（播放期间冻结）**保持现状**，待用户裁决。

## 3. H7 / H8 的执行顺序（历史计划，已执行完毕）

> 本节保留 2026-10-18 的排期口径；实际按「事实层 → 连续语义 → 窗口」推进，全部于 2026-11-03 落地。

计划文档自己给了依赖口径：**H8 的最小版本（位置 / 朝向 / 边沿）不依赖 H7**，可先落地；H8 的窗口与事件时间戳依赖 H7。

| 阶段 | 内容 | 依赖 | 交付物 |
|---|---|---|---|
| **1** | H8 步骤 1–3：采样点实测 → 位置扫掠 + 进入边沿 → 朝向弧 | 无 | 测试脚本 + 验收记录（扫过必触发 / 老脚本零回归 / 成本实测） |
| **2** | H7 步骤 1–7：事实模型 → 触发器身份 → 播放生命周期事实 → 账本收编 → tracker 收编 → 持久化定稿 → 消费面 | 无（可与阶段 1 并行） | 事实层可用 + 五份记录收编 + 三前置条件改走事实查询 |
| **3** | H8 步骤 4–5：`dwell` / `window` 配置面 → 事件侧时间戳与多事件 | 阶段 1 + 阶段 2 | 窗口语义可配 + 事件类子条件解禁（按窗口限定） |
| 4 | H8 步骤 6：编辑器连续语义开关 | 阶段 3 | 归 Z 轮，可延后 |

阶段 1 与阶段 2 不互相阻塞，可以并行开工；阶段 3 必须等两者。

---

## 4. 验收口径（历史计划，结果见各专篇 §六）

**阶段 1**
- 构造穿越用例：玩家在一帧内跨过 box / radius 边界 → 必触发（现状会漏）。
- 老脚本零回归：默认档保持现状（连续语义按触发器显式开启），未开启的脚本行为逐字节不变。
- 成本：扫掠子步数 vs 每 tick 耗时的实测数据。

**阶段 2**
- 五份记录（§2.1 表）全部收编或明确保留理由；同一信号不再双写。
- 三个内置前置条件（`hasPlayed` / `isScriptStarted` / `isScriptCompleted`）改走事实查询。
- 播放结束的 `instanceId` + `CompletionReason` 进入事实层，跳过 / 打断 / 自然结束可区分。
- `ScriptPlayback.server` 空指针缺陷修掉。
- 网络包与客户端缓存形态定稿（全量 / 增量 / 删除）。

**阶段 3**
- `dwell` / `window` 可配且校验完整，文档 `docs/TRIGGER_TYPES.md` 同步。
- 事件类子条件解禁后按时间窗限定，不再"很久以前发生过也算"。

---

## 5. 不在本轮

- 不改条件字段形态与组合器结构（归 `../C-trigger/trigger-conditions.md`）。
- 不做真·连续时间（服务端输入只有每 tick 一份，连续只能是相邻采样点的保守近似）。
- 不做客户端判定（判定仍在服务端）。
- 不做通用事件总线 / 跨模组事件 API。
- 编辑器 UI（阶段 4）归 Z 轮。

---

## 6. 文档关系

| 文档 | 关系 |
|---|---|
| [`state-tracking.md`](state-tracking.md) | 记账层计划（P1-impl）= H7 |
| [`trigger-continuity.md`](trigger-continuity.md) | 判定层计划（P3-impl）= H8 |
| ~~`region-sync.md`~~ | H1，**已取消**（2026-11-04 用户决定暂不做；文档已删除，git 历史可查） |
| [`skip-vote-and-hud.md`](skip-vote-and-hud.md) | H9，跳过投票判定 + HUD 布局/位置配置 |
| [`screen-layers.md`](screen-layers.md) | 画面层：HUD、手臂、摇晃、屏幕效果、玩家模型；普通/白名单模式与注册接口 |
| [`../C-trigger/trigger-conditions.md`](../C-trigger/trigger-conditions.md) | 条件怎么写、怎么组合；H7/H8 只提供它脚下的查询原语与时间语义，不改它的字段 |
| [`../C-trigger/condition-system.md`](../C-trigger/condition-system.md) | 判定规则（**脚本级规则定义/绑定**；I 轮迁移前的载体是片段级 `clip.rules`），与触发器判定是不同层 |
| [`../F-playback/parallel-playback.md`](../F-playback/parallel-playback.md) | 实例账本的上游；H7 阶段 2 与之对齐 |
| [`entity-liveness.md`](entity-liveness.md) | H10，F8 相机视图实体活性的下一项设计 |
| [`hud-layer-api.md`](hud-layer-api.md) | H11，HUD 层正式注册入口（`hud_layers` / 固定分类 / hard 白名单的平台边界） |
| [`public-api.md`](public-api.md) | H12，对外接口总纲：播放链路、相机只读面、轨道 / 触发器 / 模型 / 曲线 / 前置条件注册、注册时机与失败隔离 |

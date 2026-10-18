# H 轮：区域同步 / HUD / 实机验证 / 触发器状态层

> 本轮包含 5 项任务，其中 H7 / H8 是 2026-10-18 新加入的两份既有计划
> （`state-tracking.md` = P1-impl、`trigger-continuity.md` = P3-impl，此前一直挂在 C-trigger 下）。

## 1. 任务清单

| ID | 任务 | 文档 | 状态 |
|---|---|---|---|
| H1 | region-sync 全部实现（平移 / 镜像旋转 / 多区域链 / 传送 / 防乒乓 / 朝向策略） | [`region-sync.md`](region-sync.md) | ☐ 零实现（方向稿） |
| H2 | wait-point-track | — | ⊘ 已被 C6 判定规则吸收（等待点 = `until→do` 阻塞形态） |
| H4 | feedback-04 #1 结案（命令归 EVENT 轨） | — | ✅ 2026-10-10（`476b442`） |
| H5 | 文档滞后清理（审计 §三 12 条） | — | ☐ |
| H6 | 实机验证批次（审计 §四 7 条） | — | ☐ |
| **H7** | **P1-impl 事实层**：布尔点状事件判定 → 事实追踪 | [`state-tracking.md`](state-tracking.md) | ☐ **零实现**（核对见 §2） |
| **H8** | **P3-impl 连续语义**：离散采样 → 连续判定 | [`trigger-continuity.md`](trigger-continuity.md) | ☐ **零实现**（核对见 §2） |
| **H9** | **跳过投票 + 跳过 HUD**：判定对齐 MC `SleepStatus`、HUD 合成一行、位置可配 | [`skip-vote-and-hud.md`](skip-vote-and-hud.md) | 🟡 有实现但不完整（见该文档 §1.3） |

H7 / H8 之间没有强绑定：H8 的**最小版本**（位置扫掠 / 朝向弧 / 进入边沿）不依赖 H7，可先落地；H8 的窗口与事件时间戳依赖 H7。

---

## 2. H7 / H8 的代码现状（2026-10-18 逐条核对）

### 2.1 H7（P1-impl）：零实现

`state-tracking.md` §6.1 的七步全部没有代码，§6.2 的「最小版本」一行没落。

| 计划要点 | 现状 | 证据 |
|---|---|---|
| §4.1 事实记录（谁/何时/对象/结果） | 零实现：无事实类型、无容器、无 API | 全仓无 `Fact*`；`api/` 只有 `CameraApi` / `CameraState` / `BaseCameraProvider` |
| §4.2 存在性查询 | 退化形态（集合 `contains`，维度只有「脚本 / 触发器」） | `PlayerTriggerState.java:23-31`、`TriggerStateStore.java:46-66` |
| §4.2 计数查询 | 零实现 | trigger 包无 `count` 相关 |
| §4.2 最近一次（何时 + 结果） | 部分：只有 tracker 单槽"最近一次值"，**无时间戳、无结果** | `Evaluators.java:566,599,617,628,643,674,715` |
| §4.2 时间窗（N tick 内） | 零实现 | trigger 包无任何 tick 字段 |
| §4.2 分组/过滤（实例、触发器身份、结果） | 零实现：`TriggerDefinition` 无 id；退出原因在状态层被丢弃 | `TriggerDefinition.java:11-22`、`ScriptParser.java:1282-1286`、`TriggerEngine.java:164` |
| §5.1 七个记账口 | 全部零实现（播放开始仍双写布尔 + 账本；播放结束丢掉 `instanceId` / `reason`） | `TriggerEngine.java:161-177,222-228,265-289`；`ScriptEventManager.java:90-91` |
| §5.2 存储与生命周期 | 零实现：四种生命周期并存，无"每条事实声明持久性" | `TriggerStateStore.java:27-28`、`TriggerEngine.java:30`、`Evaluators.java`、`ScriptEventManager.java:39` |
| §5.4 迁移六项 | 全零（唯一已做的是"删死读法"，属清理不属事实层） | `hasAnyTriggered` / `getTriggeredIds` / `resetScript` / `resetAll` 全仓 0 命中 |
| §6.2 最小版本（事实 + 存在性 + 会话内存储 + 三前置条件切换） | 零实现：三个内置前置条件仍直连布尔存储 | `BuiltinPrerequisites.java:17-32` |

**五份记录（§2.9 说三套，实际是五份）**：

| # | 记录 | 位置 | 生命周期 |
|---|---|---|---|
| ① | 持久布尔三集合 | `PlayerTriggerState.java:10-13` + `TriggerStateStore.java:149-176` | 跨存档 SNBT |
| ② | 九个单槽 tracker | `Evaluators.java:562-760` | 会话内 |
| ③ | 实例账本 | `ScriptEventManager.java:39,325-356` | 实例内 |
| ④ | 区域进入态（裸布尔） | `TriggerEngine.java:30,265-289` | 进程内，不落盘 |
| ⑤ | 客户端镜像（只写不读） | `ClientTriggerStateCache.java:18-38` | 客户端内存 |

**已知缺陷仍在**：`ScriptPlayback.server` 声明未赋值（`ScriptEventManager.java:336` / 构造器 `:346-355`），但 `broadcastSkipVote` 读它（`:126`）。

### 2.2 H8（P3-impl）：零实现

`trigger-continuity.md` §3 的九条不连续问题（N1–N9）**全部仍在**，§7 的六步全部没有代码。

| 计划要点 | 现状 | 证据 |
|---|---|---|
| N1 位置扫过漏检 | 仍在：只判采样瞬间坐标 | `Evaluators.java:56-67`；`TriggerEngine.java:108` |
| N2 朝向扫过漏检 | 仍在（5 tick 采样） | `Evaluators.java:480-482,285-306`；`ImmersiveCinematics.java:44-47` |
| N3 边沿只在采样点 | 仍在（仅采样点布尔翻转） | `TriggerEngine.java:265-288` |
| N4 事件单槽覆盖 | 仍在（`put` 覆盖） | `Evaluators.java:617-623,644-659,715-725` |
| N5 事件类不可入组合 | 仍在（类型层禁用） | `ScriptValidator.java:2879-2882`；`Evaluators.java:557-559` |
| N6 组合器只有同一时刻 | 仍在（无窗口字段） | `Evaluators.java:515-524`；`TriggerDefinition.java:19-21` |
| N7 同 tick 顺序不可分辨 | 仍在（哈希序 / clip×关键帧序） | `TriggerEngine.java:106-108`；`ScriptEventManager.java:200-230` |
| N8 变化检测只见净额 | 仍在（快照覆盖） | `Evaluators.java:351-372` |
| N9 播放期间冻结 | 有意行为，未变 | `TriggerEngine.java:91-95,113-119` |
| §4.2 存在（any-instant） | 零实现：无线段/射线扫掠 | `GeometryConditions.java` 只有 `inBox` / `inRadius` / `facingInRange` 等点判定 |
| §4.2 持续（dwell） | 零实现（触发器层；`clip.rules` 的 dwell 是另一层） | 无 dwell 字段 |
| §4.2 边沿 | 部分：只有采样点布尔翻转 | `TriggerEngine.java:265-288` |
| §4.2 窗口（recent） | 零实现 | 无"最近 W tick"查询 |
| §5.1 每玩家采样快照 | 零实现 | trigger 包无 `Map<UUID, Vec3>` / 上一采样点 |
| §7 六步 | 全零；`cinematics/tests/trigger/` 无连续语义测试脚本 | 只有 `poll_interval` / `death` / `nested` / `play_mode` |

### 2.3 两处文档滞后（代码已做，计划没回写）

- C3 的轮询间隔三级生效链已落地：`TriggerType.java:42-47`（trigger ?? script ?? type）、`TriggerRegistration.java:25,90`、`ScriptParser.java:222,1293`。`trigger-continuity.md` §2.2 仍写"仅类型级、改配置要重启"。
- `state-tracking.md` §5.4 第一条的子项（删无调用者读法）已在 C4 完成。

---

## 3. H7 / H8 的执行顺序

计划文档自己给了依赖口径：**H8 的最小版本（位置 / 朝向 / 边沿）不依赖 H7**，可先落地；H8 的窗口与事件时间戳依赖 H7。

| 阶段 | 内容 | 依赖 | 交付物 |
|---|---|---|---|
| **1** | H8 步骤 1–3：采样点实测 → 位置扫掠 + 进入边沿 → 朝向弧 | 无 | 测试脚本 + 验收记录（扫过必触发 / 老脚本零回归 / 成本实测） |
| **2** | H7 步骤 1–7：事实模型 → 触发器身份 → 播放生命周期事实 → 账本收编 → tracker 收编 → 持久化定稿 → 消费面 | 无（可与阶段 1 并行） | 事实层可用 + 五份记录收编 + 三前置条件改走事实查询 |
| **3** | H8 步骤 4–5：`dwell` / `window` 配置面 → 事件侧时间戳与多事件 | 阶段 1 + 阶段 2 | 窗口语义可配 + 事件类子条件解禁（按窗口限定） |
| 4 | H8 步骤 6：编辑器连续语义开关 | 阶段 3 | 归编辑器阶段，可延后 |

阶段 1 与阶段 2 不互相阻塞，可以并行开工；阶段 3 必须等两者。

---

## 4. 验收口径

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
- 编辑器 UI（阶段 4）归编辑器阶段。

---

## 6. 文档关系

| 文档 | 关系 |
|---|---|
| [`state-tracking.md`](state-tracking.md) | 记账层计划（P1-impl）= H7 |
| [`trigger-continuity.md`](trigger-continuity.md) | 判定层计划（P3-impl）= H8 |
| [`region-sync.md`](region-sync.md) | H1，独立功能，与 H7/H8 无依赖 |
| [`skip-vote-and-hud.md`](skip-vote-and-hud.md) | H9，跳过投票判定 + HUD 布局/位置配置 |
| [`hud-hard-hide.md`](hud-hard-hide.md) | H4/H5/H6 相关 |
| [`../C-trigger/trigger-conditions.md`](../C-trigger/trigger-conditions.md) | 条件怎么写、怎么组合；H7/H8 只提供它脚下的查询原语与时间语义，不改它的字段 |
| [`../C-trigger/condition-system.md`](../C-trigger/condition-system.md) | 判定规则（`clip.rules`），与触发器判定是不同层 |
| [`../F-playback/parallel-playback.md`](../F-playback/parallel-playback.md) | 实例账本的上游；H7 阶段 2 与之对齐 |

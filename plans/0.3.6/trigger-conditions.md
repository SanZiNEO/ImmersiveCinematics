# 0.3.6 触发器体系一次升级：条件组合 + 朝向范围 + 检测频率（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、公式、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [触发器类型](../../docs/TRIGGER_TYPES.md)
> - [脚本格式](../../docs/SCRIPT_FORMAT.md)
> - [反馈：on_enter 状态机问题](./feedback-0.3.5/05-on-enter-not-repeatable.md)

---

## 1. 定位（已确认）

**这是触发器体系的“一次升级”**：现在够用但偏保守，升级一次到位；更大的（事件树、可视化条件编排）不进计划。

三个需求：

1. **多重条件触发**：一个触发器可以要求**多个条件组合满足**才触发——组合器是 `any` / `all_of`（**多元**，不设上限）；例如“到某个位置 **且** 看向某处”；原格式一个触发器只有一类条件，最小版本已用 `all_of` 补上一层组合。
2. **朝向范围条件**：检查玩家视线方向是否落在 `(yaw1, pitch1) → (yaw2, pitch2)` 范围内。按**玩家原版视线参数**判定：`yaw ∈ [-180, 180]`（0 = 南，正 = 顺时针）、`pitch ∈ [-90, 90]`（正 = 向下）；不掺模组相机侧的基准语义（`yaw_base` 等）。
3. **检测频率可配**：轮询间隔从“按类型全局”升级为可按触发器 / 按脚本单独设置——现状是所有 location 触发器共用 `triggerPollInterval_location`，少量需要高频检测的触发器被迫跟随全局（见 `feedback-0.3.5/04-trigger-latency.md` 待改进 #2）。朝向判定很便宜，高频轮询对它尤其有意义。

**节奏**：最小版本（`facing` / `all_of`）已落地；其余按效果分批放开。

**最小版本**：JSON 手写可用 + 服务端生效 + 校验通过；只做 `all_of`（全部满足）、只做玩家；编辑器 UI（游戏内 + WebUI）后置。

**条件模型**：触发条件包含——**单一条件**、**组合器（`any` / `all_of`，多元、不设上限）**、**前置条件（锁存）**。**组合器是统一的组合机制**：`any` = 任一满足，`all_of` = 全部满足；它**替代 AND / OR 这类二元的旧说法**，并且**统一用于所有层级**——既用于跨条件的条件列表，**也用于触发器内部的判定**（不是写死的 AND）。条件的元素可以是任何现有触发器类型的条件，也可以是前置条件——**前置条件归类为触发条件的一种**，参与组合（`requires` 现有字段写法保持兼容）。取反（not）未定，列为待定。

---

## 2. 现状与缺口

| 现状 | 说明 |
|---|---|
| 单个触发器 = 单一条件类型 | 25 种触发器各有一套条件字段；同一触发器内部的多字段判定目前写死为 AND（如 entity_kill 的场景条件）——方向：纳入统一组合器（`any` / `all_of`）；跨类型组合由 `all_of` 承担（一层） |
| 同一脚本多个触发器 = **任一** | 任意一个满足就触发该脚本（任一语义）；没有“都满足才触发” |
| `requires` = **全部满足**，但只支持脚本状态类前置 | 内置 `script_played` / `script_started` / `script_completed`；不能把两个触发器的条件组合起来 |
| `facing` / `all_of`（最小版本，已实现） | `facing`：视线角度区间（纯角度判定，不需目标）；`all_of`：一层组合（组合器），子条件限轮询类 |
| `observation` | 看向某个**具体目标**（实体 / 方块，带 reach）；与 `facing` 互补，两者都保留 |

**缺口（当前）**：① `all_of` 只支持一层、子条件只收轮询类；any / 嵌套 / 前置条件入组合未做；② 检测频率仍按类型全局，未按触发器 / 脚本可配；③ 编辑器 UI 未跟进（游戏内编辑器类型清单不含 `facing` / `all_of`）。

---

## 3. 方案方向

### 3.1 组合层：条件列表（`all_of` / `any` / 前置）

- 引入“**条件列表**”的写法，对应条件模型（见 §1）：
  - 单一条件 = 现有写法（不动）；
  - `all_of`（全部满足）= 列表内**全部满足**才触发（已实现为一层）；
  - `any`（任一满足）= 列表内**任一满足**即触发（未做）；
  - 前置条件（requires 类：script_played / script_started / script_completed）= 作为条件类型参与列表（未做）。
- **组合器统一**：同一套 `any` / `all_of` 也用于**触发器内部的判定**——不再另造一套 AND / OR。
- 列表元素**复用现有条件类型**（location / observation / entity_kill / …）+ 新增“朝向”条件 + 前置条件；**数量不设上限**。
- 语义区分：轮询条件是瞬时语义（现在是否满足）；前置条件是**锁存语义**（发生过即真）——组合求值时两类分清，避免“先 A 后 B”的误判。
- 现有“单条件触发器”写法**保持不动**（旧脚本零迁移）；新写法是可选的；
- 最小版本已做一层 all；any、嵌套、前置入组合为后续批次（见 §7）。

### 3.2 朝向条件：视线角度范围

- 条件参数 = 两对角：`(yaw1, pitch1)` 与 `(yaw2, pitch2)`；
- 判定对象 = **玩家视线**（原版参数），不需要目标、不需要射线命中——与 `observation`（看向具体目标）互补；
- 可以单独作为触发器使用（只判朝向），也可以放进条件列表与其他条件组合。

### 3.3 实现落点（方向）

- 复用现有触发器引擎：类型注册、求值器、轮询、触发状态机（`on_enter` / `exit_buffer` / `repeatable`）都不新造；
- 组合器 = 一个“依次求值：`all_of` 全真才真 / `any` 任一即真”的条件求值器；朝向条件 = 一个新的求值器；
- 轮询频率沿用现有机制；单列可配（按触发器 / 按脚本）见 §4。

### 3.4 动作面（方向）

触发器的动作收敛为两类：

- **控制脚本**：播放 / 停止 / 暂停某个脚本；
- **回报等待点**：给等待点（[等待点](./wait-point-track.md)）回报事件。

命令与声音**不属于动作面**——它们归脚本自己的轨道（命令 = EVENT 轨；声音 = AUDIO 轨）。现状代码里的 `ExecuteCommandAction` / `PlaySoundAction` 按此方向收敛。

---

## 4. 边界与细节

1. **yaw 环绕**：已实现“从 yaw1 顺时针扫到 yaw2”——跨越 ±180° 自然环绕；起止相同 = 整圈 / 不限制。
2. **边界包含性**：端点包含；`pitch` 端点由校验限制在 ±90。
3. **与 `on_enter` / `exit_buffer` 的关系**：组合条件下的“进入触发一次”同样依赖触发状态机——状态机已修复（fb-05），组合条件直接沿用。
4. **轮询频率**：已定单列（按触发器 / 按脚本可配）；当前实现仍为固定 5 tick，单列可配未做。
5. **求值顺序与短路**：已实现按列表顺序求值、任一不满足立即短路；按代价排序（先便宜后贵）未做。
6. **与 `observation` 的分工**：`observation` = “看向某个具体目标（实体 / 方块，带距离）”；朝向条件 = “视线落在某角度范围”；两者都保留，互不替代。
7. **校验与报错**：已实现结构校验（条件列表非空、子类型已知且为轮询类、朝向端点合法、`all_of` 不许嵌套）；旧脚本零回归。
8. **编辑器与文档**：`TRIGGER_TYPES.md` / `AI_SCRIPTING_GUIDE.md` 已同步；游戏内编辑器与 WebUI 的触发器编辑（组合条件增删、朝向范围编辑）未跟进。

---

## 5. 已确认 / 待定

**已确认**

- 三个需求（条件组合、朝向范围、检测频率）都做。
- 条件模型：单一条件 + 组合器（`any` / `all_of`，多元、不设上限）+ 前置条件（锁存）；**组合器统一用于跨条件与触发器内部判定**；前置条件归类为触发条件的一种。
- 动作面：只有两类——**控制脚本**（播放 / 停止 / 暂停）与**回报等待点**；命令 / 声音归脚本轨道。
- 朝向范围按**玩家原版视线参数**（yaw ±180、pitch ±90）。
- 最小版本：只做 `all_of`、只做玩家、JSON 手写 + 服务端生效 + 校验通过；编辑器后置。
- 现有触发器格式不动，旧脚本零迁移。
- 轮询间隔单列：按触发器 / 按脚本可配。

**待定**

- 条件列表的字段形态与嵌套方式（any 纳入体系、最小版本后放开；嵌套执行时定）。
- 前置条件 ↔ **脚本状态**：`script_played` / `script_started` / `script_completed` 的判定口径（"播完"是否含跳过 / 打断）、状态归属（谁记）、并行播放下的口径（按玩家 × 脚本 id，还是按实例）。
- 取反（not）是否纳入。
- 编辑器支持的排期。

---

## 6. 验收标准（草案）

- [ ] 条件列表：全部条件满足才触发；任一不满足不触发（含轮询下的连续判定）
- [ ] 朝向范围：区间判定正确（含跨 ±180° 区间、端点边界、pitch 上下限）
- [ ] 朝向条件可单独使用，也可与位置等条件组合
- [ ] 旧格式脚本行为无变化（零回归）
- [ ] `/icinematics validate` 能校验新写法并给出可读报错
- [ ] 最小版本全程 JSON 手写可用（不依赖编辑器）

---

## 7. 最小版本落地情况

| # | 改动 | 状态 |
|---|---|---|
| 1 | `Evaluators` 新增 `evaluateFacing` + `evaluateAllOf` | 已落地 |
| 2 | `ImmersiveCinematics.registerTriggerTypes()` 注册 `facing`、`all_of` | 已落地（间隔 5 tick） |
| 3 | `ScriptValidator` 加结构校验 | 已落地 |
| 4 | 解析层不改（`conditions` 已是通用嵌套结构） | 已落地 |
| 5 | 文档 `TRIGGER_TYPES.md` 加两节 | 已落地（`AI_SCRIPTING_GUIDE.md` 同批） |
| 6 | 测试脚本 `cinematics/tests/trigger/test_trigger_facing.json`、`test_trigger_all_of.json` | 已落地 |
| 7 | 编辑器 UI、schema 控件；any、嵌套、前置入组合 | 未做（后续批次） |

**实现约定（已落地）**

- 条件字段：`facing` = `yaw1 / pitch1 / yaw2 / pitch2`；`all_of` = `list`（数组，元素 = `{ type, conditions }`）；
- yaw 区间语义：**从 yaw1 顺时针扫到 yaw2**（跨越 ±180° 自然环绕；起止相同 = 整圈 / 不限制）；pitch 区间取 min / max；
- `all_of` 子条件**只允许轮询类**触发器类型（location / facing / observation / structure / biome / xp / inventory / gamestage / dimension）——事件类（advancement / entity_kill / item_* 等）会带来"很久以前发生过也算"的误判，最小版本拒绝；不允许 `all_of` 嵌套；
- 前置条件（requires 类）是**锁存语义**（发生过即真），纳入组合的写法属后续批次；`requires` 现有字段不动；
- schema：`facing` 已登记字段（yaw1 / pitch1 / yaw2 / pitch2）；`all_of` 只登记类型，无专用控件（手写 JSON）。

**后续批次**：any / 嵌套 / 前置条件入组合；新增类型 `death`（玩家死亡）；检测频率可配（按触发器 / 按脚本）；编辑器 UI（游戏内 + WebUI）。

**验收标准**：validate 通过；facing 可单独用；all_of 只在全真时触发（"先 A 后 B"不误判）；旧脚本零回归。

---

## 已知缺陷（2026-10-06 代码复查）

> 只读代码审查发现，未在游戏内复现；不影响当前设计，记录备查。

- **多人服下触发器播放的结构 / 方块来源不可解析**：推送前的结构 / 方块替换只覆盖 `look_at_target_structure` 与 `position.relative_origin`；触发器路径（`StartPlaybackAction`）直接发 rawJson、零替换 → 触发器播放时结构 / 方块来源全部不可解析（单人服不受影响）。**✅ 已修复（2026-10-07）**：替换逻辑提取为共享 `util/ScriptStructureResolver.resolveTargets(json, level, origin)`，`StartPlaybackAction.execute` 在发送前按触发者位置应用同一套替换（`/icinematics play` 路径同步切换到该 helper，行为不变）。

---

## 事实核查（2026-10-07）

> 依据：本仓库工作区源码（`common/src/main/java/...`、`forge/src/...`、`fabric/src/...`）、MC 1.20.1 源码（`build/mc-sources/net/minecraft/...` 与 loom `*-sources.jar`）。本次核查以工作区文件内容为准；未发现需按 `git log` 时间裁决的文档/代码冲突（文末另附相关文件最后提交时间备查）。

### ① 核实为真的断言

| 断言 | 证据 |
|---|---|
| §7#1 `Evaluators` 新增 `evaluateFacing` + `evaluateAllOf` | `trigger/server/evaluator/Evaluators.java:518`（`evaluateFacing`）、`:546`（`evaluateAllOf`） |
| §7#2 `registerTriggerTypes` 注册 `facing`、`all_of`，间隔 5 tick | `ImmersiveCinematics.java:50` `new TriggerType("facing", POLLING, 5, Evaluators::evaluateFacing)`、`:51` `("all_of", POLLING, 5, Evaluators::evaluateAllOf)` |
| §7#3 `ScriptValidator` 加结构校验 | `script/ScriptValidator.java:436` `validateTriggerConditions` → `:474` `validateFacingConditions`（yaw1/pitch1/yaw2/pitch2 必填为数字；pitch 端点限 -90~90）、`:491` `validateAllOfConditions`（list 必须非空数组、元素为 `{type,conditions}`、子类型必须已注册且 `ListenStrategy.POLLING`、禁止嵌套 `all_of`）；`:466` 对 facing/all_of 上无效的 `exit_buffer` 给出告警 |
| §7#4 解析层不改（conditions 已是通用嵌套结构） | `script/ScriptParser.parseTriggerDefinition`（:554 起）对 conditions 走通用 `parseDataMap`，无类型分支 |
| §7#5 `TRIGGER_TYPES.md` 加两节 + `AI_SCRIPTING_GUIDE.md` 同批 | `docs/TRIGGER_TYPES.md:604`（第 24 节 `facing`）、`:632`（第 25 节 `all_of`）；`docs/AI_SCRIPTING_GUIDE.md:268-269` 表格含 `facing` / `all_of` |
| §7#6 测试脚本存在 | `cinematics/tests/trigger/test_trigger_facing.json`（facing 单条件）、`cinematics/tests/trigger/test_trigger_all_of.json`（all_of[location,facing]）均存在 |
| §7 实现约定：facing 字段 `yaw1/pitch1/yaw2/pitch2` | `Evaluators.java:519-530` |
| §7 实现约定：all_of = `list`，元素 `{type, conditions}` | `Evaluators.java:547-559` |
| §7 实现约定：all_of 子条件限轮询类 | 校验器 `ScriptValidator.java:523` 判 `strategy == POLLING`；允许集恰为 location/facing/observation/structure/biome/xp/inventory/gamestage/dimension（与注册表 POLLING 类型一致） |
| §7 实现约定：yaw 从 yaw1 顺时针扫到 yaw2；起止相同 = 整圈 | `Evaluators.java:531-536`（`span = wrapDegrees(yaw2-yaw1)`；`span==0 → true`；`span>0` 取 `0 ≤ delta ≤ span`） |
| §4.2 端点包含 + pitch 限 ±90 | 端点包含：`Evaluators.java:526`（pitch 用 `< min` / `> max` 排除）、`:534`（`delta >= 0 && delta <= span`）；pitch 限值由 `ScriptValidator.java:483-486` 校验 |
| §4.4 当前实现仍为固定 5 tick，单列可配未做 | facing/all_of 在 `ImmersiveCinematics.java:50-51` 硬编码 5；`Config` 无 facing/all_of 专用间隔字段 |
| §2 "25 种触发器" | `registerTriggerTypes` 共注册 25 个类型（`ImmersiveCinematics.java:24-51`） |
| §2 `requires` = 全部满足，内置 script_played/script_started/script_completed | `TriggerEngine.prerequisitesMet`（:185-189）逐条 `PrerequisiteRegistry.evaluate`，任一 false 即 false；`prereq/BuiltinPrerequisites.java:17/23/29` 注册三者 |
| §2 同一触发器内部多字段判定目前写死 AND（如 entity_kill 场景条件） | `Evaluators.matchesScene`（:141）逐字段 `return false`；`evaluateXp`（:256-259）level/total 全判 |
| §2 on_enter / exit_buffer / repeatable 状态机现状 | `TriggerEngine.checkEnterState`（:253-274）+ `enterStates`（:30）；字段定义见 `ScriptParser.java:558-561`、`TriggerRegistration.java:71-75`；fb-05 状态标 ✅ 已修复（`feedback-0.3.5/05-on-enter-not-repeatable.md` 头部） |
| 已知缺陷：触发器路径零替换 | `StartPlaybackAction.sendTo`（:64）`S2CPlayScriptPacket.send(p, script.getRawJson())`；rawJson 由 `ScriptManager.java:75` `script.setRawJson(content)` 原样存入，无替换 |

### ② 已修正的断言

- 无。本次核查未发现与工作区源码不一致的事实性断言，未改动原文事实描述。

### ③ 补全的信息

- **`triggerPollInterval_location` 的来源**：它是 **Forge 配置文件键**（`forge/.../ForgeConfig.java:102` `.defineInRange("triggerPollInterval_location", 20, 1, 600)`）；Fabric 侧键为 camelCase `triggerPollIntervalLocation`（`fabric/.../FabricConfig.java:57`、`:120`）；Java 静态字段为 `Config.triggerPollIntervalLocation`（`Config.java:45`，默认 20）。且该间隔**不止 location 使用**：`xp`、`dimension` 两类型也复用 `Config.triggerPollIntervalLocation`（`ImmersiveCinematics.java:42`、`:44`）。其余：`triggerPollIntervalBiome`（默认 40）、`...Inventory`/`...Structure`/`...Gamestage`（默认 20）；**无** xp/dimension/observation/facing/all_of 的专用间隔字段。
- **facing 判定的角度获取方式**：直接读**玩家实体自身** `player.getXRot()` / `player.getYRot()`（`Evaluators.java:523`、`:528`），**不是**用 `getViewVector()` 反算；yaw 经 `Mth.wrapDegrees` 归一。
- **MC 原版朝向语义**（`build/mc-sources/net/minecraft/world/entity/Entity.java`）：`getYRot()`（:3321）/`getXRot()`（:3337）返回字段原值；`getViewVector`（:1483）→ `calculateViewVector(xRot,yRot)`（:1495）返回 `(sin(-yaw)·cos(pitch), -sin(pitch), cos(-yaw)·cos(pitch))` ⇒ yaw 0 指向 +Z（南），yaw 增大转向 -X（西），俯视顺时针；pitch 正指向 -Y（向下）。yaw 存值经 `% 360`（`setRot` :385、`absMoveTo` :1356），可能超出 [-180,180]；pitch 经 `Mth.clamp(..., -90, 90)`（`absMoveTo` :1357、`turn` :411），服务端移动包经 `ServerGamePacketListenerImpl` → `player.absMoveTo(...)`（:849），故服务端 `getXRot()` ∈ [-90,90]。`Mth.wrapDegrees`（`util/Mth.java:198`）把角度归一到 [-180,180)。⇒ §1/§4 的 "yaw ∈ [-180,180]（0=南，正=顺时针）、pitch ∈ [-90,90]（正=向下）" 与原版一致（yaw 区间为求值器 wrap 后的有效区间）。
- **schema 侧**：`script/schema/TriggerSchemas.java` 的 `typeList()`（:25-47）已含 `facing`/`all_of`；`facing()`（:239）登记 yaw1/pitch1/yaw2/pitch2 四个 float 字段；`allOf()`（:249）返回空 map（只登记类型、无专用控件）——与 §7 "schema" 约定一致。
- **单人服不受影响的机制**：客户端兜底 `CameraTrackPlayer.resolveStructurePos`（:555）/`resolveBlockPos`（:595）通过 `mc.getSingleplayerServer()` 定位；多人（返回 null）无法解析。`/icinematics play` 路径的服务端替换在 `CinematicCommand.resolveStructureTargets`（:165），覆盖 `look_at_target_structure`（:183）与 `position.relative_origin`（:189，含 `block:` 写法 :241）。

### ④ 无法核实的断言

- 无。本文所有事实性断言均已在工作区源码 / MC 源码中核实。

### 附：相关文件最后提交时间（冲突裁决用；本次无冲突）

| 文件 | `git log -1 --format=%cI` |
|---|---|
| `plans/0.3.6/trigger-conditions.md` | 2026-10-06T21:16:28+08:00 |
| `.../trigger/server/evaluator/Evaluators.java` | 2026-09-29T14:12:03+08:00（注：facing/all_of 相关改动在工作区中已存在） |
| `docs/TRIGGER_TYPES.md` | 2026-10-05T17:42:24+08:00 |

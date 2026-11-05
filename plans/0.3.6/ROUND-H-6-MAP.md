# H-6 轮次地图与共享契约（主代理）

> 建立时间：2026-11-04。依据：H-6 任务书 + `plans/0.3.6/H-other/*` + `plans/0.3.6/I-BACKLOG.md` F/G/I 表 + 源码核对。
> 行号仅供定位，**以类名 / 方法签名为准**。`docs/` 已过时，只作线索；冲突以设计文档与源码为准。
> 前置：H-1（事实层 / 连续语义）、H-2（跳过投票 / HUD / `api/HudLayerRegistry`）、H-3（H10 实体流）、H-5（`screen_layers` 15 层表，`fcde50a`）均已合入。

---

## 〇、任务书自检

| 项 | 结论 |
|---|---|
| 目标 | 明确：12 项各给终态（关闭 / 移交 / 契约边界），不留「未排期」 |
| 范围 | 明确：仅上述 12 项 + 文档回写；不重开 H-1/H-2/H-3 语义；实机脚手架不入库 |
| 验收 | 明确：BACKLOG F/G/I 表终态、实机记录带步骤与证据、多实例票数互不覆盖、H10 四条证据 |
| 不做 | 明确：Fabric hard 等价能力、编辑器 UI / `editor/src/demo.ts`（Z 轮） |
| 互斥约束 | **未发现**。两处「或」均为二选一，按「单一入口、无兼容层」原则自行裁决（见 §〇.1），不构成矛盾 |

### 〇.1 本轮自行裁决（按「干净、单一入口、无兼容层」）

| # | 二选一 | 裁决 | 理由 |
|---|---|---|---|
| 0.1 | 短传送：落地跨平台信号 / 定性契约边界 | **落地信号**（`ServerPlayer` 传送 mixin） | 有确定注入点（已反编译核实签名），比留一个已知漏判更干净；不引入第二套判定 |
| 0.2 | 票数缓存：按 `scriptId` / 按实例 id | **按实例 id（`scriptId` + `instanceId`）** | 服务端账本本来就是**按实例**（`ScriptPlayback` 一实例一条）；按 scriptId 仍会在同脚本多实例时串。包内加 `instanceId` 属内部协议，两端同版发布，无兼容层 |
| 0.3 | 多实例投票 UI：实现 / 降级单实例写文档 | **实现** | 任务书要求「能区分实例并分别呈现票数」；降级会让「多实例同时可跳过」继续是盲区 |
| 0.4 | H10 计量：临时脚手架 / 永久诊断 | **永久、`debugLogging` 门控的诊断计数** | 临时脚手架删掉后数据不可复现；现有 `[camera-view]` 已是同口径诊断日志，不新增测试框架 |

---

## 一、12 项 → 任务映射

| 任务书项 | 编号 | 终态目标 | 依赖 |
|---|---|---|---|
| 扫掠补遗（`observation` 射线扫掠） | **H6-1** | 成本实测 + 纳入实现（弧带 + 子步重放） | — |
| 短传送判据 | **H6-2** | 传送信号落地，短传送标为不连续 | — |
| 票数缓存分流 | **H6-3** | 缓存按实例分流；包内 `instanceId` 被真正消费 | — |
| 多实例投票 UI | **H6-4** | 多实例各自一行、带身份标签 | H6-3（契约固定，可并行） |
| 外部 overlay 复测 | **R1** | 真机记录（TACZ / SecurityCraft） | 实机环境 |
| 光影顺序与性能 | **R2** | A/B 帧顺序 + 诊断会话性能数据 | Iris + 光影包（已在 `fabric/run`） |
| lane 症状 B | **R3** | 复现采证或机制级证明 | Fabric 实机 |
| 双端联机票数行 | **R4** | 像素测量票数文本中心 = `voteHudX/Y` | 联机装置 |
| H10 断线 / 维度卸载 / 停服 | **R5** | 三路径无残留流 / 幽灵实体 | 联机装置 |
| H10 同维度多 lane | **R6** | 同一实体只配对一次、只更新一次 | 联机装置 |
| H10 多玩家同目标维度 | **R6** | 互不串包、互不影响 | 联机装置 |
| H10 包量 / tick 耗时 | **R7** | 2/3 lane × 32/64 格数据 + 批量信封结论 | H6-5 + 联机装置 |
| （配套）H10 计量诊断 | **H6-5** | 服务端包量/字节、客户端 `tickEntities` 耗时 | — |

### 依赖图

```
H6-1 ──┐
H6-2 ──┤
H6-3 ──┼──→ 编译波次 ──→ R1 / R2 / R3 / R4 / R5 / R6 / R7 ──→ 文档回写 + BACKLOG
H6-4 ──┤                        （联机装置先自检：client A + client B + server）
H6-5 ──┘
```

---

## 二、现状与关键符号（源码核对结果）

### 2.1 连续语义 / `observation`（H6-1）

| 符号 | 位置 | 现状 |
|---|---|---|
| `TriggerType.Evaluator` | `trigger/server/TriggerType.java:26` | 已有 3 参 `evaluate(player, conditions, ctx)`；2 参构造器包成忽略 ctx |
| `Evaluators.evaluateObservation(player, JsonObject)` | `trigger/server/evaluator/Evaluators.java:276` | **只有 2 参**；`level.clip` + `ProjectileUtil.getEntityHitResult`，无扫掠 |
| `Evaluators.evaluateLocation(player, c, ctx)` | 同文件 `:88` | 点判定未命中 → `SegmentSweep.hitsBox/hitsSphere` |
| `Evaluators.evaluateFacing(player, c, ctx)` | 同文件 `:512` | 点判定未命中 → `SegmentSweep.hitsFacing` |
| `Evaluators.evaluateStructure(player, c, ctx)` | 同文件 `:429` | `SegmentSweep.anyPoint` 子步（`SUBSTEP_MAX = 16`） |
| `SegmentSweep` | `trigger/server/continuity/SegmentSweep.java` | 纯函数；`SUBSTEP_MAX=16`、`SUBSTEP_BLOCKS=1.0`、`substeps()`、`anyPoint()` |
| `TriggerEvalContext` | `trigger/server/continuity/TriggerEvalContext.java` | `sweep`/`from`/`to`/`windowTicks`；`canSweep()` |
| 引擎构造 ctx | `trigger/server/TriggerEngine.java:319-325` | `continuous = edge.hasSample() && edge.generation() == generation` |
| 校验：可扫掠类型 | `script/ScriptValidator.java:4162` / `:4192` `isSweepableType` | 只允许 `location` / `structure` / `facing`；`observation` 目前被 warning 忽略 |
| 解析 | `script/ScriptParser.java:1708-1735` | `continuity {sweep, dwell, window}` |
| 注册 | `ImmersiveCinematics.java:50` | `new TriggerType("observation", POLLING, 5, Evaluators::evaluateObservation)`（2 参） |
| 夹具 | `cinematics/0.3.6/tests/trigger/test_trigger_observation.json` | 点判定夹具 |
| 设计 | `H-other/trigger-continuity.md` §六「`observation` 射线扫掠未实现」 | 待本轮 |

### 2.2 传送信号（H6-2）

| 事实 | 证据 |
|---|---|
| `ServerPlayer.teleportTo(double,double,double)` override | 反编译 `ServerPlayer.java:1268`（`this.connection.teleport(...)`） |
| `ServerPlayer.teleportTo(ServerLevel,double,double,double,Set<RelativeMovement>,float,float)` override | 同文件 `:1278`（同维度 → `connection.teleport`；跨维度 → 5 参重载） |
| `ServerPlayer.teleportTo(ServerLevel,double,double,double,float,float)`（Forge 扩展，无 `@Override`） | 同文件 `:1532`；跨维度分支 `:1536` |
| `/tp` 走 Set 重载 | `TeleportCommand` 传绝对坐标 + `RelativeMovement` 集合 |
| `PlayerMotion` 现状 | `MAX_TICK_DISPLACEMENT = 16.0`；维度切换 `reset`、载具变化、超阈值 → 代次自增；**短传送按连续处理**（契约边界） |
| 现有 mixin 目录 | `common/src/main/java/.../mixin/`，配置 `immersive_cinematics.mixins.json` |

### 2.3 跳过投票（H6-3 / H6-4）

| 符号 | 位置 | 现状 |
|---|---|---|
| `S2CSkipVoteUpdatePacket` | `trigger/network/S2CSkipVoteUpdatePacket.java` | 有 `scriptId`，**无 `instanceId`**；`handle()` → `ClientScriptReceiver.handleSkipVoteUpdate` |
| `ClientScriptReceiver` 缓存 | `trigger/client/ClientScriptReceiver.java:37-56` | 两个全局 `int` 全量覆盖；`getSkipVoterCount()` / `getSkipTotalViewers()` / `resetSkipVote()` |
| 清零调用点 | `camera/PlaybackLedger.java:98`、`handler/ClientEventHandler.java:149`、`ClientScriptReceiver.java:125` | 全部清空，不区分实例 |
| 服务端广播 | `trigger/server/ScriptEventManager.java:266-276` `broadcastSkipVote` | 逐 viewer 发 `(pb.scriptId, votes, total)` |
| 服务端阈值 | 同文件 `:248` `neededVotes(total, ratio)`；`ScriptPlayback.neededVotes(votes)` `:655` | 单一实现，已收口 |
| HUD | `control/SkipHudRenderer.java:20-108` | 门控 `isScriptMode` + `isTopInstanceSkippable` + `hide_skip_hud`；单行票数 `voteHudX/Y` |
| 实例枚举 | `camera/CameraManager.java:70` `instances()`；`PlaybackInstance.isSkippable()` `:422`、`scriptId()` `:166`、`instanceId()` `:171`、`isEditorDriven()` `:130`、`script()` `:161` |
| 语言键 | `assets/immersive_cinematics/lang/{zh_cn,en_us}.json:14,22` | `skip_hold` / `skip_vote`（2 参） |
| 设计 | `H-other/skip-vote-and-hud.md` §六 + `I-BACKLOG.md` H2-R1/R2 | 待本轮 |

### 2.4 H10 实体流（H6-5 / R5-R7）

| 符号 | 位置 | 现状 |
|---|---|---|
| `CameraEntityStream` | `trigger/server/CameraEntityStream.java` | `subscribers` + `interests`（`Interest{entity, views, paired}`）+ 引用计数 |
| `CameraViewManager.tick` | `:164-197` | `player-gone` / `level-unloaded` / `timeout` / `player-in-dimension` 四条释放路径 |
| `onPlayerQuit` / `clearAll` / `onLevelUnload` | `:201` / `:220` / `:230` | 断线 / 停服 / 维度卸载 |
| `sendEnvelope` | `:504`（private） | 单包信封，1 MiB 上限 |
| 客户端 tick | `client/dimension/DimensionViews.tick`（`ClientEventHandler.onClientTick:76` 调用） | `DimensionView.tickEntities()` → `ClientLevel.tickEntities()` |
| 现有日志 | `[camera-view]` 系列（`CameraViewManager:120/137/220/248/280/474/523/737/742/763`） | 事件式，无包量 / 耗时统计 |
| 设计 | `H-other/entity-liveness.md` §七 + `I-BACKLOG.md` H3-R1..R4 | 待本轮 |

### 2.5 实机装置

| 项 | 现状 |
|---|---|
| `ic`（devtools） | `tools/devtools/.venv/Scripts/python.exe tools/devtools/devtools.py`；子命令 `client/cmd/play/stop/shot/tail/trace/perf/sessions/doctor` |
| 运行目录 | Fabric `fabric/run`（5.3 GB）、Forge `forge/run`（291 MB）；`IC_RUN_DIR` 切换 |
| Iris | `fabric/run/mods/iris-1.7.6+mc1.20.1.jar`；`fabric/run/shaderpacks/` 有 4 包（BSL / Complementary ×2 / MakeUp） |
| 第三方 overlay | **未安装** TACZ / SecurityCraft；Modrinth API 可达（HTTP 200） |
| 联机 | **无现成装置**：Fabric `run` 同时被 client/server 占用；需临时 run 配置（见 §四） |
| 诊断会话 | `LaneDiagnostics`（manifest `immersive_cinematics/diagnostics/*.json`，`ic perf` / `ic sessions`） |

---

## 三、缺口分类

### 3.1 本轮做（代码）

- `observation` 扫掠：成本实测 + 实现（`Evaluators` / `SegmentSweep` / 校验 / 注册 / 文档）。
- 传送信号：`ServerPlayer` 传送 mixin → `PlayerMotion.reset`。
- 票数缓存按实例分流：包 + 服务端 + 客户端 + 清零粒度。
- 多实例投票 HUD：单行格式统一带身份标签、多行堆叠。
- H10 计量诊断：服务端包量 / 字节、客户端 `tickEntities` 耗时（`debugLogging` 门控）。

### 3.2 本轮做（实机）

- R1 外部 overlay（TACZ / SecurityCraft，Forge）。
- R2 Iris 顺序 + 性能（Fabric）。
- R3 lane 症状 B（Fabric，复跑 `tilt_repro` 配方 + 机制证据）。
- R4 双端票数行像素（联机）。
- R5 断线 / 维度卸载 / 停服（联机）。
- R6 同维度双 lane / 双玩家（联机）。
- R7 包量 / tick 耗时（联机）。

### 3.3 移交 / 契约边界（预判，最终以实机结果为准）

| 事项 | 分类 | 关闭条件 |
|---|---|---|
| Fabric `hard_hide_hud` 等价能力 | 契约边界（沿用 H2-R3） | Fabric 出现可取消整段 HUD 回调 |
| 编辑器 UI / `editor/src/demo.ts` | 移交 Z 轮（沿用 H2-R7/R8、H5-R2） | 编辑器源码回仓 |
| 模组私有实体包 / 方块光照声音不同步 / `FABULOUS` | 契约边界（沿用 H3-R6） | 不承诺 |
| 短传送之外的相对传送（`teleportRelative`） | 契约边界 | 相对位移是真实移动语义，按连续处理 |
| `observation` 扫掠的移动实体目标 | 契约边界 | 子步重放用「求值时刻」的实体位置，不做历史轨迹回溯 |

---

## 四、验证约定

| 类别 | 约定 |
|---|---|
| 编译 | `sh gradlew :common:compileJava --offline --no-daemon`；平台侧改动追加 `:fabric:compileJava` / `:forge:compileJava`。**每个波次结束后主代理统一跑一次**，子代理不跑 gradle |
| 无头探针 | 仓库外 `E:/tmp/h6-probe/`；类路径模板抄 `E:/tmp/h1/run-fact.sh` / `E:/tmp/h3/run-stream.sh`；探针自打印 `PASS`/`FAIL` + 汇总，失败退出码非 0 |
| 实机 | `ic` 驱动；截图一律 `ic shot`（游戏内 F2）；`IC_RUN_DIR` 显式指定；每组对照前 `ic stop` + 等 1–2 s；测完 `ic` 关闭客户端，不留后台 |
| 联机装置 | 临时在 `fabric/build.gradle` 的 `loom.runs` 增加 `client2`（`runDir("run2")`）与 `server2`（`runDir("run-server")`）；**测完立即回滚**并记录补丁原文到实机记录 |
| 证据落盘 | `plans/0.3.6/H-other/verification-h6.md`（新建）：逐项步骤、命令、日志片段、截图路径、结论 |
| 提交 | `feat(0.3.6): H-6 收口补遗与实机批次`；文档/工具单独 `docs(0.3.6)`；不 push |

---

## 五、共享契约（子代理必须按此实现，不得另立接口）

### 5.1 H6-3 票数缓存 API（H6-4 直接消费）

```java
// ClientScriptReceiver
public static final class SkipVote { public final int voters; public final int total; }  // 不可变
/** 按 (scriptId, instanceId) 取票数快照；无记录返回 null。 */
public static SkipVote getSkipVote(String scriptId, String instanceId);
/** 清空某实例的票数（脚本结束 / 停止时按实例清零）。 */
public static void resetSkipVote(String scriptId, String instanceId);
/** 清空全部（世界退出 / 服务端停止）。 */
public static void resetSkipVote();
```

- 键：`scriptId + "\u0000" + instanceId`；`instanceId` 为空串时按空串存（本地实例与旧服务端同键）。
- `S2CSkipVoteUpdatePacket` 增加 `instanceId` 字段（`write`/`read` 顺序：`scriptId`, `instanceId`, `voterCount`, `totalViewers`），`send(player, scriptId, instanceId, voters, total)`。
- 服务端 `broadcastSkipVote` 传 `pb.instanceId`。
- 删除两个全局 `int` 与 2 参 `getSkipVoterCount/getSkipTotalViewers`（无兼容垫片；唯一消费者是 `SkipHudRenderer`）。

### 5.2 H6-4 HUD 行格式（单一形态，不按实例数分支）

- 每个「可呈现」实例一行：`!isEditorDriven()` && `player().isPlaying()` && `isSkippable()` && 缓存 `total > 0`。
- 行文本统一：`hud.immersive_cinematics.skip_vote` = `"跳过投票: %s %s/%s"`（name, voters, total）；`name` 取 `instance.script().getMeta().getName()`，空白时回落 `scriptId`。
- 位置：第一行（顶层实例）中心 = `voteHudX/voteHudY`（**像素验收基准不变**）；其余行按实例顺序向下堆叠，行距 `font.lineHeight + 2`。
- 门控不变：`Config.showSkipHud` + `hide_skip_hud`（仍为总开关）；跳过提示块仍只对顶层实例绘制。

### 5.3 H6-1 扫掠契约

- `Evaluators.evaluateObservation(ServerPlayer, JsonObject, TriggerEvalContext)`：点判定先跑；未命中且 `ctx.canSweep()` 时，按 `SegmentSweep.arcSteps(from, to, ARC_STEP_DEG)` 子步重放「插值眼位 + 插值朝向」的射线，任一步命中即真。
- `ARC_STEP_DEG = 5.0`（度/子步），子步数 `1..SUBSTEP_MAX(16)`；眼位线性插值、yaw 走最短弧、pitch 线性。
- `ScriptValidator.isSweepableType` 增加 `observation`；文档写明「移动实体目标按求值时刻位置判定」。
- 成本：默认（未声明 `continuity.sweep`）零额外开销；开启后每轮最多 16 次 `clip` + 16 次实体查询。

### 5.4 H6-2 传送契约

- mixin `ServerPlayer`：`teleportTo(DDD)V` 与 `teleportTo(ServerLevel,DDD,Set,FF)Z` HEAD → 若目标点与当前位置距离平方 > `1e-6`，`PlayerMotion.INSTANCE.reset(uuid)`。
- 不碰 `connection.teleport`（那是发送侧，且被原版多处用于位置同步）。
- `teleportRelative` 不重置（相对位移是移动语义，写入契约边界）。

### 5.5 H6-5 计量契约

- 服务端：`CameraViewManager` 每 tick 统计 `envelopes` / `bytes` / 活跃流数；每 100 tick 在 `Config.debugLogging` 下打一行 `[camera-view] stats tick=… streams=… views=… envelopes=… bytes=… entities=…`。
- 客户端：`DimensionViews.tick` 逐视图累计 `tickEntities` 纳秒；每 100 tick 打 `[camera-view] client-tick dim=… samples=… p50us=… avgus=… entities=…`（与既有 `DimensionView` 日志同前缀）。
- 关闭 `debugLogging` 时零分配、零计时。

---

## 六、派单波次

| 波次 | 任务 | 说明 |
|---|---|---|
| 0 | 主代理：联机装置可行性自检（临时 run 配置 + 双客户端 + 服务端握手） | 失败则 R4/R5/R6/R7 转「移交」并记录尝试 |
| 1 | H6-1、H6-2、H6-3、H6-4、H6-5（并行；不跑 gradle、不提交） | 契约见 §五 |
| 2 | 主代理统一编译 + 修复 | `:common:compileJava`，必要时 `:fabric:compileJava` / `:forge:compileJava` |
| 3 | 无头探针（票数分流 / 扫掠几何 / 传送代次） | 仓库外 |
| 4 | R1 / R2 / R3（单客户端） | 主代理驱动 |
| 5 | R4 / R5 / R6 / R7（联机） | 主代理驱动 |
| 6 | 文档回写 + BACKLOG F/G/I 终态 + 本轮总结 | 子代理起草，主代理核 |

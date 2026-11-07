# J-1 遗留收口 · 轮次地图

> 建立：2026-11-07。来源：J-1 任务书（A–I 轮 8 项欠账）。
> 本文件是派单与验收的唯一引用面；任务书没写的口径以「本轮内完整最优」为准，不留双写入口 / 并行判定。

## 0. 任务书自检与裁决

| 项 | 结论 |
|---|---|
| 目标 / 范围 / 验收 / 不做 | 四项齐全，互不矛盾 |
| 第 8 项（数组条件） | **已 ask 裁决（2026-11-07）**：触发器体系只有 `any` / `all_of` 一套组合机制；触发器内部的多项判定也用它。**旧格式（数组 + `mode: or/and`）本轮删除，不做兼容**；两条夹具改写为新格式（组合器 + `continuity.window`） |
| 第 7 项（旧基线像素对比） | **已 ask 裁决（2026-11-07）**：采「等值映射 + 数值探针」。旧 worktree 只编译 `:common:`，用同一探针在旧/新 sampler 上按固定脚本时间采样，输出最大误差表；另单列旧格式 vs 新格式语义差异。不构建旧客户端、不做像素 diff |
| 数字口径修正 | `E:/tmp/icv` 基线实测 **8 of 258** = 2 支真实缺陷（`showcase_02` / `showcase_07`）+ **6 支 `cinematics/0.3.6/tests/faults/` 负例**（本应 FAIL）。修完第 8 项后应为 **6 of 258**（不是 7）。另：`SELFTEST: 6 ... FAILED` = `Validate.java` 内 6 个旧格式自检样本，归第 9 项 |

## 1. 任务清单

| ID | 一句话目标 | 验收（可判定） |
|---|---|---|
| T1 | 内联片段默认出口真正执行，且与分支 target 共用**同一处**完成派发 | 探针全绿：`goto` 启动目标 / `end_script` 结束脚本 / `continue` 主线继续 / 链式不丢 / seek 不合成完成事件；夹具 `test_standalone_camera_branch.json` 改由内联片段 `exit.goto` 启动独立 CAMERA 片段，实机登录后 2s 相机移动 |
| T2 | F 轮失败处理 P1/P2（37 条：15 P1 + 12 P2 + 10 P3）全部落地 | 坏 keyframe / 坏命令 / 坏图层三注入 → 进程存活、日志可定位（边界名 + scriptId/instanceId/层级/字段路径 + 限频）、其余实例继续；审计 §0.4 更新为「P1/P2 已落地」 |
| T3 | 数组条件按新格式收口：内部多项判定 = `any` / `all_of`，删除旧数组 + `mode` | 两条 showcase 夹具校验 0 issue；`conditions.mode` 出现即报「已移除」；`TriggerSchemas` / `Evaluators` / `FactWindow` 无数组分支；文档一致 |
| T4 | B18 ④ 过渡「打断」实机 | 逐帧六参数：打断帧无跳变、无双重混合、窗口按打断时刻重算、与 `snap` 有可见差异 |
| T5 | B18 ⑤ 过渡「链式」实机 | 三段衔接处逐帧六参数：衔接点无跳变、第二段起点 = B 当帧实际状态、窗口 `[now, now+maxDuration]` |
| T6 | B19 迟滞四象限人眼判定 | 同屏四象限（1 对照组 + `exponential_smooth` / `spring` / `inertia`），截图 + 逐型一句话结论 + 60/120/解锁三档记录 |
| T7 | C-pause 实机复验 | 含 EVENT 片段中途 pause → 3s → resume：①内容位置不变 ②EVENT 不重不漏 ③恢复后继续推进 ④`test_rules_actions` clip3 不卡死；四条各有日志/帧证据 |
| T8 | 迁移等值证据（旧基线数值探针） | 旧 worktree 编译 `:common:` + 同一探针双跑，输出逐项最大误差；差异报告单列「旧格式 vs 新格式」可观察差异（morph 每侧完整状态、路径条目化） |
| T9 | 工具侧收尾 | `E:/tmp/icv/Validate.java` 6 个旧格式自检样本 → 新格式，`SELFTEST` 全过；`test_h11_hard_hide.json` Forge 复测（`screen_layer_mode: allowlist` 下接管/重画） |
| T10 | 文档收口与提交 | 各专篇状态回写；`implementation-progress.md` 孤儿欠账表清零；未做项写入 `I-BACKLOG.md`（带归属轮次 + 关闭条件）；`type(0.3.6):` 提交，不 push |

## 2. 真实文件 / 类 / 签名

### T1 内联片段默认出口

| 位置 | 现状 | 缺口 |
|---|---|---|
| `common/src/main/java/com/immersivecinematics/immersive_cinematics/script/execution/ClipBranchController.java` | `poll()` 首行 `if (!branching) return;`，只观察分支 target | 主线片段完成无人读 `ClipExitResolver` |
| 同上 `launch(Clip, double, ClipExitPolicy)` / `fail(Clip, String)` | 分支启动 / 失败回滚 | 保持 |
| `common/.../script/execution/ClipExecutionLedger.java` | `advance(double)`、`executionOf(Clip)`、`at(int)`、`size()`、`endBranch(Clip)`、`snapshot()/restore()` | 需提供「遍历全部记录」的读面（已有 `at` / `size`） |
| `common/.../script/execution/ClipExecution.java` | `done()`、`executionId()`、`branchEntry()` | 完成事实唯一真源（不改） |
| `common/.../camera/FrameDriver.java:174-180` | 每帧 `instance.branch().poll()`（已 try/catch） | 调用点保持 |
| `common/.../camera/PlaybackInstance.java:310-333` | `syncExits` 已把内联 + 独立片段 `exit` 进表；`branch` 重建 | 确认无需改 |
| `common/.../script/rule/ClipExitPolicyParser.java` | 解析五种出口 | 保持 |
| `cinematics/0.3.6/tests/rules/test_standalone_camera_branch.json` | 现由 `rules[].goto_clip` 启动 | 改为内联 `intro.exit = {on_complete:"goto", target:"cam_standalone"}` |
| `plans/0.3.6/I-script-core/rule-exits.md` | §七 写「主线完成不走出口策略」 | 改写为新口径（单一完成派发） |

**实现口径（派单写死，避免两套判定）**

1. `poll()` 每帧扫描账本**全部**记录；每个 (clip, executionId) 只在 `done` 由 false→true 的那一帧派发一次。
2. `executionId` 变化（`reset` / `seek` / `startBranch` / `endBranch`）只记基线、不派发 —— seek 不得合成完成事件。
3. 派发 = 一个 `applyExit(clip, execution, policy)`：
   - `CONTINUE` / `RETURN` / `END_EXECUTION` → 若是当前分支 target，`ledger.endBranch(clip)`；主线执行无需结束。
   - `GOTO` → 若是分支 target 先 `endBranch`，再 `launch(policy.target(), policy.entryLocalTime(), null)`；`launch` 返回 false（已有分支在跑 / 目标非法）→ 限频日志，不静默。
   - `END_SCRIPT` → 若是分支 target 先 `endBranch`，再 `host.requestScriptEnd()`。
4. 优先级单点：`resolver.resolve(clip, isBranchTarget ? entryOverride : null)`；`isBranchTarget = branching && target == clip`（一个谓词，不是第二条判定链）。
5. 同帧多个完成按扁平索引顺序派发；无限执行永不 `done`，不派发。
6. 探针（仓库外 `E:/tmp/j1-exit/`）：普通片段 goto / end_script / continue、链式两段、入口覆盖优先、seek 不合成、`endBranch` 后不重复派发。

### T2 F 轮 P1/P2（37 条）

分片按「边界归属」切，文件不重叠：

- **片 A（渲染 / lane / 调试路径）**：`overlay/OverlayManager.java`（`update` 逐层 catch）、`mixin/GameRendererMixin.java:89-95`（帧尾调试捕获 catch + 失败关闭调试）、`mixin/LevelRendererVanillaMixin.java`（`smartCull` 自愈）、`mixin/LaneCullStateMixin.java`（`mirrorCompiled`）、`client/lane/LaneRenderer.java`（Iris 反射首次 INFO）、`client/lane/LaneDiagnostics.java`（静默解析）、`client/post/ColorAdjustPass.java` + `client/lane/LaneCompositor.java`（shader 加载 `RuntimeException`）、`client/lane/LaneDebugCapture.java`（由 A11 覆盖，确认）。
- **片 B（服务端 / 网络 / WebUI 消息）**：`trigger/server/ScriptEventManager.java`（`onServerTick` 按 playback 隔离）、`trigger/server/store/TriggerStateStore.java`（`saveAll`）、`trigger/client/ClientScriptReceiver.java`（play/reload 日志补上下文）、`trigger/client/ClientEntitySelectorCache.java`（空体 catch → 限频日志）、`trigger/server/ScriptSyncState.java`、`trigger/network/AckTracker.java`、`webui/WebEditorApi.java`（补栈）、`trigger/server/evaluator/Evaluators.java`（gamestage 首次 INFO）、`util/ErrorLog.java`（首次 stderr）、`script/AudioListenerController.java`。
- **片 C（客户端 tick / 输入 / 解析 / 配置）**：`mixin/KeyboardHandlerMixin.java`、`mixin/MouseHandlerMixin.java`、`mixin/LocalPlayerMixin.java`、`mixin/SoundManagerMixin.java`、`camera/PreviewChannel.java`（`pushScript` catch + 玩家 null）、`handler/ClientEventHandler.java`（建目录 WARN）、`webui/WebPreviewScreen.java` + `webui/WebFrameStreamer.java`、`fabric/.../FabricConfig.java`（+ Forge 对应配置类，若存在）。

**每条改动四要素**：边界名、上下文（scriptId / instanceId / 层级 / 字段路径 / 玩家）、限频 key（失败点 + 实例）、`Error` 直接上抛（`FatalErrors.rethrowIfFatal` 已有）。

### T3 数组条件 → 新格式

| 位置 | 现状 | 动作 |
|---|---|---|
| `script/schema/TriggerSchemas.java` | `entityKill()`：`entity` string + `mode` enum(or/and)；`inventory()`：`items` array + `mode` enum(and/or) | 删除两处 `mode`；`entity` / `item` 保持 string |
| `trigger/server/evaluator/Evaluators.java` | `evaluateEntityKill` / `evaluateItemPickup` 数组分支；`evaluateInventory` `mode=or` 分支 | 删除数组与 mode 分支；presence 恒为「全部拥有」 |
| `trigger/server/fact/FactWindow.java` | `entityKill` / `itemPickup` 数组分支 | 删除数组分支 |
| `script/ScriptValidator.java` | schema 驱动；未知键静默 | `conditions.mode`（entity_kill / inventory）出现即报「已移除：用 any / all_of 表达」 |
| `cinematics/mod_showcase/scripts/showcase_02_kill_sheep.json` | `entity:["minecraft:sheep"]` + `mode:"and"` | 单值 `entity:"minecraft:sheep"` |
| `cinematics/mod_showcase/scripts/showcase_07_barter.json` | `item:[15 项]` + `mode:"or"` | `any` 组合器 + 15 个单值 `item_pickup` 子条件 + `continuity.window` |
| `plans/0.3.6/C-trigger/trigger-conditions.md` | §六「内部判定统一未落地」 | 裁决 + 落地记录（唯一格式） |
| `docs/TRIGGER_TYPES.md` | §20 / §21 仍写 `mode` or/and | 改为新格式；旧写法不再支持 |

### T4–T9 材料

| 项 | 材料 |
|---|---|
| T4 / T5 | `cinematics/0.3.6/tests/camera/test_camera_transition_b18_a.json`、`_b.json`、`test_camera_transition_params.json`（已就绪，validator OK）；实现 `camera/transition/CameraTransitionLayer.java`（`interrupt` / `settle`） |
| T6 | 新建 `cinematics/0.3.6/tests/camera/test_camera_hysteresis_quadrant.json`：4 条 CAMERA 轨、同轨迹、`dest.x/y/w/h` 各占一象限；3 轨分别挂 `motion_*` = `exponential_smooth` / `spring` / `inertia`，1 轨不挂（对照）。通道 schema 见 `script/schema/TrackSchemas.java:696-699` |
| T7 | 新建 `cinematics/0.3.6/tests/rules/test_cpause_event.json`：CAMERA + EVENT 轨（EVENT 位于 pause 窗口之后，可判「不丢」；pause 前一条可判「不重」）+ 规则 `pause` → `until(timeout)` → `resume`；复跑 `test_rules_actions.json` clip3 |
| T8 | 旧 worktree `E:/tmp/j1-old`（`42c0ef4`，I-5 格式清理前）+ 探针 `E:/tmp/j1-equiv/`；新侧用当前 `common/build/classes/java/main` |
| T9 | `E:/tmp/icv/Validate.java`（6 个内联旧格式样本 + 期望串）；`cinematics/0.3.6/tests/hud/test_h11_hard_hide.json` |

## 3. 依赖顺序

```
Wave 1（并行，均不跑 gradle）
  T1 代码 + 夹具 + 探针
  T2-A / T2-B / T2-C
  T3 代码 + 夹具 + 文档
  T9-icv（Validate.java 迁移，独立于仓库）
Wave 1 收尾（主代理）
  :common:compileJava → icv 全量 → 探针（T1、T8 新侧）→ 修缺口重派
Wave 2（实机准备）
  T4/T5/T6/T7 夹具 + 临时 trace 补丁（env 门控，测完还原）
  T8 旧 worktree 编译 + 双跑探针
Wave 3（实机，主代理）
  Fabric 一轮：T1 夹具 → B18 A/B → B19 四象限 → C-pause → 三注入冒烟
  Forge：:forge:compileJava + h11 复测
Wave 4
  还原 trace、文档回写、提交（不 push）
```

## 4. 现状与缺口分类

| 缺口 | 分类 | 说明 |
|---|---|---|
| 主线片段 `exit` 不执行 | **本轮做（T1）** | 单一完成派发入口 |
| F P1/P2/P3（37 条） | **本轮做（T2）** | 只补边界捕获 + 日志 + 降级，不改业务流程 |
| `entity` / `item` 数组 + `mode` | **本轮做（T3）** | 旧格式删除；新格式 = 组合器 + `continuity.window` |
| B18 ④⑤ / B19 / C-pause 实机 | **本轮做（T4–T7）** | 夹具 + 实机 + 人眼 |
| 像素级旧基线 | **本轮做（T8，替代证据）** | 数值探针对拍 + 差异清单 |
| icv 自检样本 / h11 Forge | **本轮做（T9）** | 工具侧不入库 |
| 事件类子条件时间窗（H7/H8 剩余） | **契约边界** | `FactWindow` + `continuity.window` 已可用；事实层持久化 / 时间戳展示归后续 |
| `END_EXECUTION` 与 `CONTINUE` 等价 | **契约边界** | 无调用栈；`rule-exits.md` §二 已写明 |
| 分支失败不回滚副作用 | **契约边界** | I2-B8 已定 |
| 媒体能力（J-2 起） | **不做** | 任务书明确 |

## 5. 验证口径（主代理执行）

1. `sh gradlew :common:compileJava --offline --no-daemon` + `:forge:compileJava`。
2. `E:/tmp/icv` 全量 = 6 of 258（6 支为 `tests/faults/` 负例）+ `SELFTEST` 全过。
3. 探针：`I5Probe` 24/24 PASS + T1 出口探针 + T8 等值探针。
4. 实机：Fabric 一轮跑完 T1 夹具 / B18 A+B / B19 四象限 / C-pause / 三注入；Forge 编译 + h11。
5. 每项总结给「结论 + 证据路径」。

## 6. 执行结果（2026-11-07 收口）

| ID | 结果 | 证据 |
|---|---|---|
| T1 | ✅ 完成 | 单一完成派发（`applyExit`）；探针 28/28（`E:/tmp/j1-exit`）；夹具 `test_standalone_camera_branch.json` 实机 2s 启动、12.003s 自然结束 |
| T2 | ✅ 完成 | 37 条（P1 15 / P2 12 / P3 10）落地或核对为已存在；三注入（坏命令 / 坏图层 / 坏声音）均有可定位日志、进程存活、脚本自然结束 |
| T3 | ✅ 完成 | `entity` / `item` 数组与 `entity_kill` / `inventory` 的 `mode` 删除；两条 showcase 改写；`icv` 全量 `6 of 260`（6 支 `faults` 负例） |
| T4 / T5 | ✅ 完成 | snap 单帧硬切 / blend 每帧 ≤0.012px·0.09°、窗口 12s；链式 t=48 衔接 0.025px·0.155° |
| T6 | ✅ 完成 | 四象限逐帧 yaw 表（对照 / exp / spring / inertia）+ 逐型结论 + 60/120/解锁三档 |
| T7 | ✅ 完成 | 位置恒 0.000/6.004（666 帧）、EVENT 4/8/12 各一次、恢复推进到 20.002；**另修复 clip3 卡死**（pause 释放判据改「本执行内仍可触发的 resume」） |
| T8 | ✅ 完成 | 旧/新对拍 **21/21 PASS**：5 支仓库夹具全通道 maxAbsDiff=0；补测 yaw/roll 环绕（mod 360 差 0）、无限折叠 3 支、path 几何 5 支、多关键帧（≤9.54e-7）；非正 zoom 由校验期拒绝并断言 |
| T9 | ✅ 完成 | `E:/tmp/icv` SELFTEST 全过；Forge h11 接管 + 白名单重画（`hud.hotbar`）复测通过 |
| T10 | ✅ 完成 | 本文档 + `J-media/verification-2026-11-07.md` + 各专篇回写；**本轮新增遗留 R1~R5 已全部关闭，BACKLOG 无新增移交项** |

### 探针与命令（可复跑）

| 项 | 命令 | 结果 |
|---|---|---|
| 编译 | `sh gradlew :common:compileJava :fabric:compileJava :forge:compileJava --offline --no-daemon` | BUILD SUCCESSFUL |
| 全量校验 | `sh E:/tmp/icv/run.sh E:/Documents/GitHub/ImmersiveCinematics/cinematics` | `6 of 260` + `SELFTEST: all validator-rule self-tests passed` |
| I5Probe | `E:/tmp/i5/probe`（javac + java） | `SUMMARY pass=24 fail=0`（语料 260 支 = 252 正常 + 8 负例） |
| 出口探针 | `sh E:/tmp/j1-exit/run.sh` | `SUMMARY pass=28 fail=0` |
| 等值探针 | `sh E:/tmp/j1-equiv/run.sh` | `SUMMARY pass=21 fail=0` |

### 本轮新增 deprecated / 兼容路径

**无**。旧数组 + `mode` 直接删除（校验期报「已移除」），未留双读或垫片；`ClipBranchController` 旧的分支专用 poll 路径已并入单一派发。

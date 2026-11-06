# I 轮移交与清零清单（I-2 → I-3 / I-4 / I-5）

> 建立：2026-11-02。来源：I-2 验收遗留 + I-2 复核新发现；I-3 / I-4 移交项分别补入 A / B-2 / E 表。
> 原则：**I 轮（I-5 收口）结束时本表必须清零**；本轮做不了的必须有明确归属轮次与关闭条件，不能写成“后续再说”，也不能只留在轮次总结里。
> 纯契约 / 设计边界单列在 D 表，不算遗留；但必须在对应专篇有明确文字。

## 轮次归属假设

按 `I-script-core/README.md` §六 建议实现顺序 + I-2 任务书的 I-1~I-5 过渡策略：

- **I-3**：各轨道装配（CAMERA → OVERLAY / LETTERBOX → AUDIO / ADJUST / EVENT）。
- **I-4**：脚本级规则 / 路径集合、时间门 / 激活 / 挂起 / 事实 / 延迟动作、分支接线、片段身份 / 引用。
- **I-5**：格式清理（旧字段拒绝、`file_loop` 改名、`version` / `total_duration` 删除）、迁移器、文档与最终验收。

若 `I-ROUNDS.md` 的编号不同，以它为准；本表按“能力归属”排，不按编号硬绑。

## A. I-3 轨道装配

| ID | 事项 | 关闭条件 | 依赖 / 备注 |
|---|---|---|---|
| I2-B1 | 旧循环夹具实机回归 | Fabric / Forge 各跑 `test_camera_loop_repeat` / `test_camera_loop_pingpong` / `test_exit_loop_count` / `test_exit_infinite`；记录执行人、截图/日志；人工确认起播 / 循环 / 结束观感正常 | 旧格式现在就能跑；I-3 接轨道后复跑；I-5 最终全量 |

## B. I-4 规则 / 路径 / 分支接线（全部已关闭）

| ID | 事项 | 关闭条件 | 状态 / 证据 |
|---|---|---|---|
| I2-B2 | 分支控制器接 `ScriptPlayer` / 规则引擎 | 规则绑定可启动分支；正常/失败走同一 `ClipBranchController`；探针 + 实机 | **已关闭（I-4）**：`PlaybackInstance implements ClipBranchHost`；`goto_clip` 新形态 → `ClipBranchController.launch`；探针 `run-exit-branch`（110 checks）PASS；`rule-exits.md` §七 |
| I2-B3 | 稳定片段身份 / ClipRef / 使用边 + 多入口/多放置 | `clip-identity.md` 结构评审定稿；同一片段多入口各自执行身份/运行态隔离；重排/插入/删除显式重映射；探针 | **已关闭（I-4）**：`script/identity/`（`ClipIdentity` / `ClipRef` / `UsageIndex`）；`clip-identity.md` §七 定稿；探针 `run-identity`（45 checks）PASS |
| I2-B4 | `END_EXECUTION` 与 `CONTINUE` 语义 | 按 `rule-exits.md`：有隐式序列/调用栈则分离；确认无栈则文档明确等价并关闭 | **已关闭（I-4）**：无调用栈，`rule-exits.md` §二 明确等价；`ClipBranchController` 两者走同一 `ledger.endBranch(current)` |
| I2-B5 | 分支 + Modifier 活跃窗 | `ClipModifierGate` 按分支入口 `executionStart` / `windowStart` 判活；探针 + 实机 | **已关闭（I-4）**：`ClipTimeSource.windowStart(Clip)` 提供起点，`ClipModifierGate` 按它判活；分支入口 `windowStart = executionStart = launch`；探针 `run-exit-branch` |
| I2-B6 | 跨帧跳过多次执行的中间执行身份/规则激活 | 明确「跳过的执行是否建立激活/事实」；账本提供边界事件或规则按最终执行补齐；探针 | **已关闭（I-4）**：跳过执行不建激活、不产生事实，账本只保留最终执行身份；探针 `run-rule-gate`（80 checks）PASS |
| I2-B7 | 分支 launch 时间不变量 | `ClipBranchController.launch` 校验 `host.now() >= ledger.lastHead()`（或用 `lastHead()` 作 launch 时间）；非法返回 false；探针 | **已关闭（I-4）**：`launch = max(host.now(), ledger.lastHead())`，`now < lastHead` 时限频告警；探针 `run-exit-branch` |
| I2-B8 | 分支失败副作用边界 | `rule-exits.md` 写明「只恢复执行事实，不重放内容；音频/覆盖层按各自生命周期」；如需回滚单列设计 | **已关闭（I-4）**：`rule-exits.md` §三 写明；`ClipBranchController.fail` 只恢复账本快照 + 播放头；探针 `run-exit-branch` |
| I2-B9 | 分支出口脚本声明/序列化（片段默认 + 入口覆盖） | schema / parser / validator / 编辑器契约落地；入口覆盖 > 片段默认；旧格式迁移报告 | **已关闭（I-4）**：`ClipExitPolicyParser` 解析片段 `exit` 与绑定 `exit`，优先级 = 覆盖 > 默认 > `CONTINUE`；`clip-identity.md` §七；旧格式迁移归 I-5（I2-B13） |

## B-2. 已关闭（I-4）

I-3 移交的规则 / 路径项与 I-4 卫生项，全部在本轮关闭：

| ID | 事项 | 状态 / 关闭证据 |
|---|---|---|
| I3-B1 | 路径集合 + 相机接线：脚本级 `paths[]` 生产者接到消费侧 | **已关闭（I-4）**：`script/path/`（`PathEntry` / `PathCollection` / `PathCollectionParser` / `PathCollectionSource`）；`CameraTrackPlayer` 按脚本 `evaluator.setPathBindingSource(...)`；探针 `run-path-collection`（59 checks）PASS；`path-collection.md` §七 |
| I3-B2 | 服务端 EVENT seek 协议 | **已关闭（I-4）**：`C2SScriptSeekPacket` / `S2CScriptSeekAckPacket`、`ScriptEventManager.handleSeek`、`EventSeekPlan`、`PlaybackInstance.seekTo`；前跳不补、后跳不重复；探针 `run-event-seek`（55 checks）PASS；`rule-exits.md` §五 |
| I3-B3 | 循环 / 多入口不重放定性 | **已关闭（I-4）**：`rule-exits.md` §五 写明「片段 `loop`、宏观循环、多入口都不重放 EVENT；需要每圈执行命令用规则时间入口或显式分段」；与 `track-event.md` §3.2 同口径 |
| I4-10 | 卫生项：`ColorAdjustSampler#sampleCameraLaneObjects` 去掉 `ClipValueView` 参数 | **已关闭（I-4）**：参数已删除并同步调用点；`grep` 无旧签名；编译通过 |

## C. I-5 格式清理 / 迁移 / 最终验收

| ID | 事项 | 关闭条件 | 依赖 / 备注 |
|---|---|---|---|
| I2-B10 | `docs/SCRIPT_FORMAT.md` 重写 | 新口径（objects / loop / branch / 旧字段移除）；grep 无旧循环、旧字段残留 | 与格式清理同轮 |
| I2-B11 | 宏观循环 L6（执行次数/完成事实） | 二选一并写入 `acceptance.md`：① 实现执行边界计数；② 明确“宏观语义不变、时间圈仅定位”，改写 L6 表述 | 设计 §2.3 说非目标；不能悬空 |
| I2-B12 | AUDIO `file_loop` 改名 + 旧 `loop` 双语义切换 | 旧格式 AUDIO `loop` 迁移为 `file_loop`；新格式 `loop` 只作执行循环；旧字段出现即错误（I-5） | 依赖迁移器 |
| I2-B13 | 旧循环脚本迁移器/差异报告 + 旧字段拒绝 | 迁移器按 `migration.md` 处理旧 `keyframes` / `curve` / `rules` / `loop`；旧字段错误；报告/回滚 | 与 I2-B12 同轮 |
| I2-B14 | 最终全量实机/性能验收 | 4 支循环夹具 + 新格式轨道脚本 + 多实例；记录执行人/结果 | I-5 收口 |

## D. 契约 / 设计边界（不排期，但必须在对应专篇定性）

| ID | 事项 | 定性 | 处理 |
|---|---|---|---|
| I2-C1 | `finiteWindowEnd` / `getWindowEnd` / `contentEnd` 无限调用抛异常 | 契约：调用方先判 `hasFiniteWindowEnd()` / `hasFiniteContentEnd()` | 已写入 `ClipTimeSource`；不修 |
| I2-C2 | 分支快照只恢复账本/播放头，不回滚音频/覆盖层副作用 | 设计边界：失败 fallback 按执行事实恢复，不重放内容 | 由 I2-B8 写入 `rule-exits.md`；不承诺回滚 |

## E. I-4 遗留项（移交 / 契约边界）

| ID | 事项 | 归属 | 关闭条件 |
|---|---|---|---|
| I4-R1 | `BranchSnapshot.size()` 的 Javadoc 仍写「记录数（= 脚本扁平片段数）」，实际含脚本级独立片段记录 | **I-5** | 注释与实现一致；纯注释改动，无行为变更 |
| I4-R2 | 未启动独立片段的 `windowStart()` 返回 `+∞`（`ClipExecution.markStandalone`）；编辑器 UI 若直接取值会得到无穷 | **Z 轮** | 编辑器显示层不渲染无放置片段的时间轴槽（取值前先判 `standalone()`） |
| I4-R3 | 独立片段分支 + 无限循环 = 永不自然完成 | **契约边界** | 与 timeline 无限片段同口径：无限表示无自然完成点，不自动截断作者后续内容；已写入 `rule-exits.md` §七 |
| I4-R4 | seek 期间不 abort 分支 | **契约边界** | 既有设计：seek 只重定位事件游标与播放头，分支按自身执行继续；已写入 `rule-exits.md` §七 |
| I4-R5 | `ruleCommandPermissionLevel` 只有服务端配置；作者需要权限 3/4 的命令时无法在脚本里提高 | **契约边界** | 权限只由服务端配置决定，脚本与客户端不能覆盖；作者改用服务端触发器 / EVENT，或由运营者调高配置；已写入 `rule-command-trust.md` §六 |
| I4-R6 | 脚本级独立片段（`clips[]`）可被 `goto_clip` / `exit.goto` / `launch` 记账（账本已支持、探针覆盖），但**没有轨道播放器遍历它**，因此分支启动的独立片段没有画面 | **I-5** | 分支启动的独立片段按自己的 `track` 字段参与该轨渲染（`track` 已必填）；关闭条件 = 探针 + 实机：`clips[]` 里的 CAMERA 片段作 `goto_clip` 目标后相机真的移动。当前实机夹具用 timeline 片段验证分支，未覆盖该路径 |

## F. H-1 遗留项（触发器语义：H7 事实层 + H8 连续判定）

> 建立：2026-11-03。来源：H-1 验收遗留 + 实现时发现的口径变化。轮次编号以 H 轮任务书为准。

| ID | 事项 | 归属 | 关闭条件 |
|---|---|---|---|
| H1-R1 | `observation` 射线扫掠未做（H8 步骤 3 剩余；设计明确「先成本实测再定」） | **H-6**（原写 H-2，H-2 未承接，轮次任务书已改归） | 成本实测完成（子步数 × 射线方块/实体查询，含移动实体目标）并给出纳入/不纳入结论；纳入时实现弧带 + 子步重放 |
| H1-R2 | 短距离传送（单 tick 位移 ≤ 16 格）无法与正常移动区分，按连续移动参与扫掠 | **H-6（本轮关闭）** | 已达成：`ServerPlayerTeleportMixin` 在 `ServerPlayer#teleportTo` 两个重载（`(DDD)V`、`(ServerLevel,DDD,Set,FF)Z`）HEAD 调 `PlayerMotion.reset`，代次自增、不参与扫掠；`teleportRelative` / 直接改坐标仍由 16 格阈值兜底 |
| H1-R3 | `docs/TRIGGER_TYPES.md` / `docs/SCRIPT_FORMAT.md` 未回写 `triggers[].id`、`continuity`（sweep / dwell / window）与事实口径 | **I-5**（I2-B10） | 文档与源码一致；`grep` 无旧口径残留 |
| H1-R4 | 事实的展示面（「触发过什么 / 为什么触发」）未做；客户端镜像已删除，服务端事实不自动下发 | **Z 轮 + H-4（H12）** | 编辑器 / 客户端有读取事实查询 API 的入口（命令或网络包），且不重建第二套记录 |
| H1-R5 | 前置条件按退出结果筛选（「播完」含不含跳过 / 打断）未开放字段；事实层已能按 `FactResult` 查询 | **C-trigger** | `requires` 数据字段形态定稿并落地（事实查询已就绪） |
| H1-R6 | 播放期间扫过是否补记（N9）仍按现状「不补记」 | **用户裁决 → C-trigger / H 后续** | 裁决写入 `trigger-continuity.md` §5.3 并实现（补记或不补记二选一，含与去重的交互） |
| H1-R7 | 编辑器触发器表单未暴露 `continuity`（sweep / dwell / window） | **Z 轮** | 编辑器触发器表单含三字段，读写与 `SchemaExporter.triggers.triggerFields.continuity` 一致 |
| H1-R8 | `entity_kill` / `item_pickup` 的 `and` 模式遇空数组由「退化返回真」收紧为 `false` | **契约边界** | 空数组无业务含义；已写入 `state-tracking.md` §六 |
| H1-R9 | `entity_interact` 的 `target` 由「全等 + `*`」改为 `matchesId`（支持 `ns:*` 与无冒号子串） | **契约边界** | 与其它 id 条件统一；已写入 `state-tracking.md` §六 |
| H1-R10 | 事实 `count` / `exists` / `last` 为累计口径（保留集淘汰后仍成立）；`anyWithin` / `lastWithin` / `lastMatching` 受保留深度约束 | **契约边界** | 已写入 `PlayerFacts` Javadoc 与 `state-tracking.md` §六；保留上限：事件类 64–512 条、触发命中 1024 条 |
| H1-R11 | 窗口判定里 `entity_kill` 的 `mode=and` 按「每个目标的击杀事实各自满足场景」，与单点求值器的「最近一次击杀的场景」不同 | **契约边界** | 窗口语义按事实逐条判定；已写入 `trigger-continuity.md` §六 |
| H1-R12 | v1 触发器状态文件做一次性转换：旧键 `type_scriptId` 仅在该脚本内**唯一**匹配时映射到新身份，歧义/无匹配丢弃（debug 日志）；v2 起按事实存储 | **契约边界** | 版本原则：不保留兼容读取层；已写入 `state-tracking.md` §六 |
| H1-R13 | 顶层事件触发器不受 `continuity.window` 影响（窗口只作用于 `all_of` / `any` 的事件类子条件） | **契约边界** | 已写入 `trigger-continuity.md` §六 |

## G. H-2 遗留项（画面层与 HUD：H9 跳过投票 + H11 注册 API + H5/H6）

> 建立：2026-11-03。来源：H-2 验收遗留 + 实现时发现的口径变化。

| ID | 事项 | 归属 | 关闭条件 |
|---|---|---|---|
| H2-R1 | `S2CSkipVoteUpdatePacket` 带 `scriptId`，但客户端 `ClientScriptReceiver` 仍是两个全局 int 全量覆盖；同脚本多实例并存时票数会串 | 后续轮次（未排期，与「实例 id 网络协议形态」同批） | 客户端缓存按 `scriptId`（或实例 id）分流；包内字段被真正消费；多实例同时投票互不覆盖 |
| H2-R2 | 多实例同时可跳过时，HUD 只呈现顶层实例的跳过提示，没有「投的是哪一个」的交互 | 后续轮次（未排期） | 多实例同时可跳过时能区分实例并分别呈现票数 |
| H2-R3 | Fabric 无 `hard_hide_hud` 等价能力（无可取消的整段 HUD 回调） | 未排期（`screen-layers.md` §5.5 已定性为边界） | Fabric 出现可取消的整段 HUD 回调或等价挂点后实现 |
| H2-R4 | 审计 §四-1 点名的外部第三方模组（TACZ / SecurityCraft 类 overlay）未安装复测；本轮用「未注册的原版/临时 overlay」覆盖同一判定路径 | 后续实机批次 | 装上对应模组后，在 `hard_hide_hud=true` 下确认其 Pre 自绘与 overlay 均被接管/重画 |
| H2-R5 | screen-color-adjust 与 Iris 的先后顺序、性能定量未测（本轮只验证了 LUT / HSL 上屏） | 后续实机批次 | 启光影后 A/B 比对帧顺序；给出诊断会话的性能数据 |
| H2-R6 | lane 缺陷「症状 B」像素实证仍未采到现场（间歇性） | 后续实机批次 | 复现并采到现场，或在机制层面证明已不再触发并据此关闭 |
| H2-R7 | 编辑器 UI 交互层（datalist / Gizmo / 图形交互）无环境：编辑器源码不在本仓 | 编辑器阶段 | 编辑器源码回仓后跑真实浏览器交互 |
| H2-R8 | `editor/src/demo.ts` 离线 schema 陈旧（审计 §三-12）：文件不在本仓，无可改对象 | 编辑器阶段 | 编辑器源码回仓后按 `SchemaExporter` 同步 |
| H2-R9 | 票数行的像素位置未在实机单独测量（单机未发布按 MC 口径不显示，需要第二名玩家产票） | 后续实机批次 | 双端联机各投一票，测量票数文本中心与 `voteHudX/voteHudY` 一致 |
| H2-R10 | `camera-state-plan.md` 仍以现在时写 clip 级 `interpolation` 的 `linear/smooth`；`script-model.md:151/264` 同 | 后续文档批次（B-camera / G 轮文档已关单） | 两处按「0.3.6 起字段移除」改写；`grep` 无现在时表述 |
| H2-R11 | 夹具与探针仍按当前格式写 `meta.version: 3` 与 `timeline.total_duration` | **I-5**（格式清理） | I-5 删除两字段后，`cinematics/0.3.6/tests/` 与新夹具同步去除；`grep` 无残留 |
| H2-R12 | `skip_vote_ratio` 改为解析期报错：既有脚本若写了越界值会从「静默回落」变成「拒载」 | **契约边界** | 已在 `skip-vote-and-hud.md` §3.2 / §六 与 `docs/SCRIPT_FORMAT.md` 写明；迁移器（I-5）不需要特殊处理，报错即可定位 |

---

## H. H-3 范围变更（区域同步取消）

> 建立：2026-11-04。用户决定**暂不做**区域同步传送，`H-other/region-sync.md` 已删除。

| ID | 事项 | 归属 | 关闭条件 / 说明 |
|---|---|---|---|
| H3-X1 | 区域同步（原 H1）：平移 / 镜像旋转 / 多区域链 / 传送 / 防乒乓 / 朝向策略整体取消，设计文档 `H-other/region-sync.md` 删除 | **不排期（用户决定）** | 已关闭：`plans/0.3.6/README.md`、`H-other/README.md`、`implementation-progress.md` 均标 ⊘；`pending-discussions.md` 对应待定项关闭；架构图节点与边移除。恢复条件 = 用户重新提出需求（文档可从 git 历史恢复） |

---

## I. H-3 遗留项（H10 相机视图实体活性）

> 建立：2026-11-04。来源：H-3 验收遗留 + 实现时发现的口径变化。实现与验证见 `H-other/entity-liveness.md` §七。

| ID | 事项 | 归属 | 关闭条件 |
|---|---|---|---|
| H3-R1 | 真实断线 / 维度卸载 / 服务端停止未实机覆盖：本轮用 `/icinematics stop` + 重播验证释放 / 重建 | **H-6**（实机验证批次） | 拔线重连、玩家离开目标维度、服务端停止三种路径下无幽灵实体 / 残留流 / 异常包；日志无 `mirror` / `tick` 失败 |
| H3-R2 | 同维度多 lane 共享实体流未单独实机（跨维度双视图已验证） | **H-6** | 同维度两条 lane 时同一实体只配对一次、只更新一次；两画面一致 |
| H3-R3 | 多玩家同时看同一目标维度未联机验证 | **H-6** | 双玩家各自连接互不串包；各自释放不影响对方 |
| H3-R4 | 包量 / 客户端 `tickEntities` 耗时未定量 | 数据触发（未排期） | 2 / 3 条 lane、目标维度 32 / 64 格实体数量下测量包量 / tick 耗时，给出是否引入批量信封的结论 |
| H3-R5 | `noActionTime` 在 32–64 格可能停游走 | **契约边界** | 原版 AI 口径，已写入 `entity-liveness.md` §6.2 / §7.3；产品要求 64 格内持续游走时另立「相机观察者半径」议题 |
| H3-R6 | 模组私有实体包不镜像；方块 / 光照 / 声音 / 天气 / 粒子仍不同步；`FABULOUS` 仍拒绝目标视图 | **契约边界** | 已在 `entity-liveness.md` §7.3 与 `cross-dimension-camera.md` §8.3 写明 |

---

## J. H-4 遗留项（H12 对外接口 + `TrackType` 注册表化）

> 建立：2026-11-04。来源：H-4 验收遗留 + 实现时发现的口径变化。实现与验证见 `H-other/public-api.md` §十 / §十一。

| ID | 事项 | 归属 | 关闭条件 |
|---|---|---|---|
| H4-R1 | `spi` 目前只收轨道注册所需类型（`TrackTypeId` / `TrackSchema` / `TrackPlayer` / `TrackContext` / `PropertyType` / `Interp` / `PropertyMode` / `ObjectDef`）。触发器（`TriggerType` / `ListenStrategy` / `TriggerEvalContext` / `TriggerPrerequisite`）、插值模型（`MathModel` / `ModelParams` / `FieldDef`）、路径曲线（`PathSpec` / `PathParams` / `PathParamDef` / `PathCapability`）仍在原包，第三方注册时需 import 内部包路径 | **已关闭（I-5）** | 11 类已收进 `spi`（共 19 类）；旧 FQN 零残留；只 import `api.*` / `spi.*` 的注册示例可编译 |
| H4-R2 | 第三方轨道没有通用的 `loop` / `loop_mode=pingpong` 诊断：`ObjectValidator` 对未登记的内置特化走兜底无检查，作者写错不会得到提示 | **已关闭（I-5）** | `ObjectValidator` 的 `Check` 兜底分支执行通用 loop 检查（布尔 / 范围 / 枚举），并给「`pingpong` 仅内置视觉轨生效」提示 |
| H4-R3 | H12 的 A1「能播」/ A3「`tick` 抛异常注入」/ A6「第三方触发器命中」/ A9「`OverlayApi` 层序与释放」缺**真实第三方调用方**：本轮没有任何模组在自己的初始化期注册轨道，探针只能覆盖到「注册 → 解析 → 采样值」 | **未排期**（需要独立 demo 模组，不能在本仓自带测试轨道——那会成为永久脚手架） | 存在一个只依赖本模组公开 API 的 demo 模组：注册 1 条第三方轨道 + 1 个触发器，实机验证 `tick` 读值与抛异常隔离、触发器命中、`OverlayApi` 图层随实例释放 |
| H4-R4 | `implementation-progress.md:142` 仍把 H11 记为 `☐ 设计稿`，与 `H-other/README.md:21` 的 ✅ 冲突（H-2 遗留的文档滞后） | **已关闭（I-5）** | 两处状态一致（均 ✅ 已实现 H-2）；`grep` 无矛盾记录 |
| H4-R5 | `CHANGELOG.md` 未收录 H-4 | **契约边界** | 该文件自述「内部重构、代码整理、开发计划不写入本文件」；H-4 无玩家可感知变化（目标恰恰是零回归），故不写入 |

**H-4 契约边界（写进 `public-api.md`，不排期）**

1. `spi.TrackContext#clip()` 按设计原文返回 `script.Clip`，因此 `spi` 反向依赖 `script` 包；第三方只应使用片段级只读字段，采样成员不承诺。
2. 第三方轨道**不参与**内置特化语义：`lut` / `blend_mode` / `mask_shape` / `curve` 归属、视觉轨循环规则、相机 modifier 收集、EVENT 移动控制，全部只对内置 id 生效。
3. 第三方 id 区分大小写；内置 id 额外接受大小写不敏感写法（旧脚本兼容）。
4. `TrackContext` 的路径→槽位在注册期编译；注册冻结（首个脚本解析）后不可能再新增字段。
5. 第三方轨道作用域由自己保证（客户端 / 服务端）；我们只按客户端渲染帧调度。
6. `ExternalTrackDriver` 不对 `TrackPlayerFactory.create()` 返回 `null` 做防御——工厂返回 `null` 属注册方违约，会在首次 `start` 暴露为 NPE。

---

## K. H-5 遗留项（画面层统一语义）

> 建立：2026-11-04。来源：H-5 验收遗留 + 实现时发现的口径变化。实现与验证见 `H-other/screen-layers.md` §九。

| ID | 事项 | 归属 | 关闭条件 |
|---|---|---|---|
| H5-R1 | 真实第三方 overlay 模组（TACZ / SecurityCraft 类）未安装复测；本轮用「临时 Pre 自绘红方块」覆盖同一判定路径（承接 H2-R4） | 后续实机批次 | 装上对应模组后在 `screen_layer_mode: allowlist` 下确认其 Pre 自绘与注册 overlay 均被接管/重画；`normal` 下保留 |
| H5-R2 | 编辑器 UI 未暴露 `screen_layers` / `screen_layer_mode` 表单（schema 已导出两字段） | **Z 轮** | 编辑器源码回仓后按 `SchemaExporter.meta` 生成控件；读写与解析口径一致 |
| H5-R3 | Fabric 无「可取消的整段 HUD 回调」，`allowlist` 只能降级 `normal` | 未排期 | Fabric 出现等价挂点后实现接管并实机验证；在此之前为契约边界 |
| H5-R4 | `docs/modules/control.md` / `docs/modules/mixin.md` 仍写旧 `hud_layers` / `hard_hide_hud` 口径 | 后续文档批次 | 两处按 `screen_layers` 口径改写；`grep` 无旧口径作为推荐入口 |
| H5-R5 | `hud_layers` 的 `health` / `status` 旧键在 Fabric 上仍不生效（它们是 Forge 分类名，只经 Forge overlay 消费链命中） | **契约边界** | 已写入 `screen-layers.md` §3.3 / §5.6 与 `docs/SCRIPT_FORMAT.md`；为保持零回归不做跨平台统一 |
| H5-R6 | `hud.health` / `hud.experience` 共用 `hide_hud`：旧字段无法分别控制，只有 `screen_layers` 能拆开 | **契约边界** | 已写入 15 层表与 `docs/SCRIPT_FORMAT.md` 回落细节 |
| H5-R7 | `allowlist` 重画豁免世界效果层（暗角 / 传送门 / 药水图标 / 冻结 / 窥镜等），否则会被误判为隐藏 | **契约边界** | 已写入 `screen-layers.md` §5.6；新增世界效果 overlay 时同步 `ForgeHudLayerRegistry.WORLD_OVERLAYS` |
| H5-R8 | 接管会跳过排在接管监听器之后的前置事件订阅者的**非绘制副作用**（状态准备 / 缓存刷新） | **契约边界** | 已写入 `docs/SCRIPT_FORMAT.md` 与 `screen-layers.md`；不补发事件、不做针对性 mixin |
| H5-R9 | `screen_layers` 写成空对象也会把模式推到 `allowlist`（「字段存在」即新字段路径） | **契约边界** | 已写入 `docs/SCRIPT_FORMAT.md` 模式归一顺序；作者想保持逐层抑制请显式写 `screen_layer_mode: normal` |
| H5-R10 | `GuiMixin.shouldHide(Boolean)` 现仅以 `null` 调用（跳跃条 / 选中物品名 / 保存提示），`setting` 分支已无调用者 | **已关闭（I-5）** | 收敛为无参 `shouldHide()`；3 个调用点同步，grep 无 `shouldHide(` 带参调用 |

**H-5 契约边界（不排期，已写入对应专篇）**

1. 世界内容（实体 / 粒子 / 天气 / 地形）不受画面层控制。
2. 脚本覆盖层（黑边 / 字幕 / 图片 / GIF）不参与白名单，始终按脚本轨道绘制。
3. `allowlist` 不补发 `RenderGuiOverlayEvent.Pre/Post`；依赖该事件的第三方逻辑收不到事件。
4. 取消只影响排在接管监听器之后的订阅者；同优先级且注册更早的监听器仍会执行。
5. 直接 mixin 进 `Gui.render` 或绕过平台入口绘制的 HUD 没有稳定挂点。
6. Fabric 注册表只影响普通层过滤；`allowlist` 在 Fabric 降级 `normal`。
7. `hard_hide_hud` 与 `hud_layers` 仅作 deprecated 入口，不保留第二套判定逻辑。

---


- **I-3 收口**：A 表至少完成旧循环实机基线；I2-B1 的最终全量留 I-5。
- **I-4 收口（2026-11-03 完成）**：B 表 I2-B2..B9 全部关闭；B-2 表关闭 I3-B1/B2/B3 与卫生项；新增遗留全部落在 E 表（I4-R1 归 I-5、I4-R2 归 Z 轮、I4-R3..R5 为契约边界并已写入对应专篇）。
- **I-5 收口**：C 表全部关闭；D 表在对应专篇有明确文字；I 轮相关项已清零，**仍有 8 项归属「未排期 / 后续实机批次」**（H2-R1/R2/R3、H3-R4、H4-R3、H5-R1/R2/R3），它们不属 I 轮关单范围，逐条列在本表 J/K 表。
- **H-2 收口（2026-11-03 完成）**：H9（跳过投票判定 / 广播 / 解析期校验 / HUD 布局）与 H11（`api/HudLayerRegistry` 门面 + Forge 转调 + Fabric 边界 + 对外文档）全部落地；H5 的 12 条逐条核对完毕（见 `H-other/doc-lag-2026-11-03.md`）；H6 的 7 条实机记录见 `H-other/verification-2026-11-03.md`。新增遗留全部落在 G 表，无“未排期”以外的悬空项。
- **H-4 收口（2026-11-04）**：H12 全部落地（`api` 门面 12 方法 / `spi` 轨道四件套 / `TrackType` enum → 注册表 / 43 文件迁移 / 3 张静态表 / 注册冻结 / 失败隔离 / `OverlayApi` / `ServerFactsApi`）；内置 7 轨零回归由三份 headless 探针（47 + 23 + 12 全 PASS，860 片段 × 3 时间点覆盖 7 类轨道）与两轮实机（7 个脚本起播，含 LETTERBOX / AUDIO，零轨道相关异常）共同证明。新增遗留落在 J 表；A1/A3/A6/A9 的端到端验证因缺真实第三方调用方而移交 H4-R3。
- **H-5 收口（2026-11-04）**：画面层统一语义全部落地（15 层 `ScreenLayer` 表 / `screen_layers` + `screen_layer_mode` 解析与归一 / `hud_layers` deprecated 归一 / `hard_hide_hud` 模式别名 / allowlist 接管与重画合并旧路径 / normal 消费点改规范层名 / 平台能力位与 Fabric 降级 / 编辑器同规则）；零回归由无头探针 46 + 26 断言与 Forge「allowlist 清空第三方 Pre 自绘、normal 保留」的像素对照共同证明，Fabric 侧验证了降级与逐层覆盖。新增遗留落在 K 表；真实第三方模组复测移交 H5-R1，编辑器表单移交 H5-R2。
## L. H-6 收口（2026-11-05）

| ID | 终态 | 证据 |
|---|---|---|
| H1-R1 `observation` 扫掠 | **已关闭** | 弧带子步重放（5°/步、上限 16）；`Evaluators.evaluateObservation(...,ctx)` + `SegmentSweep.arcSteps`；校验放行；夹具 `test_h1_sweep_observation.json`；默认关闭零开销 |
| H1-R2 短传送判据 | **已关闭** | `ServerPlayerTeleportMixin` 两个 `teleportTo` 重载 HEAD → `PlayerMotion.reset`，与距离无关；`teleportRelative` 仍按连续（契约边界） |
| H2-R1 票数缓存分流 | **已关闭** | 缓存键 `(scriptId, instanceId)`；包内 `instanceId` 被消费；按实例清零 |
| H2-R2 多实例投票 UI | **已关闭** | 每批一行；单批 `已投票跳过: a/b`、多批带脚本名；投票后不再显示长按 |
| H2-R4 外部 overlay | **已关闭** | 功能已实现并开放接口（`api/HudLayerRegistry` + `screen_layers` 接管/重画）；接管判定路径由 H-5 红块对照验证 |
| H2-R5 光影顺序/性能 | **已关闭** | 顺序由注入点结构性确定；LUT 在光影输出上生效（98.4% 像素、absMean 37.3）；2 lane / 1 lane 诊断会话数据 |
| H2-R6 lane 症状 B | **已关闭** | per-lane 可见集合 + 地标塔像素 0.16% 偏差、地平线恒定 537 px |
| H2-R9 票数行像素 | **已关闭** | 双端联机投票，用户实机确认位置与显示条件 |
| H3-R1 断线/维度/停服 | **已关闭** | 三条路径均有现场日志：维度卸载（`player-in-dimension`）、断线（`quit`）、停服（`服务端停止，清空 2 个视图 / 2 条实体流`）；事后无残留流/进程 |
| H3-R2 同维度多 lane | **已关闭** | `streams=2 / views=4`；错开释放后 `streams=2 / views=2`，流不误删 |
| H3-R3 多玩家 | **已关闭** | 流键 `(玩家, 维度)`，双玩家独立流，各自释放互不影响 |
| H3-R4 包量/耗时 | **已关闭** | 稳态 0.06–0.22 信封/tick、32–166 B/tick；客户端 p50 168–248 µs；结论不引入批量信封 |

> 投票语义在 H-6 由用户裁决改为「只计票、不退出、达比例统一跳过」，H-2 旧口径（投票者先退出）已作废；详见 `H-other/skip-vote-and-hud.md` 与 `H-other/h6-handover.md`。

---

## M. I-5 收口（2026-11-06）

> 本节逐项关闭 I-2 / I-3 / I-4 / H-1 / H-2 / H-4 / H-5 移交到 I-5 的事项。证据以「文件 / 命令 / 探针」为准，不采信自述。

| ID | 事项 | 终态 | 证据 |
|---|---|---|---|
| I2-B1 | 旧循环夹具实机回归 | **已关闭（I-5）** | 迁移后 4 支循环夹具（`test_camera_loop_repeat` / `test_camera_loop_pingpong` / `test_exit_loop_count` / `test_exit_infinite`）在 Fabric 实机逐支跑通（起播 / 循环持续 / 无限不结束 / 有限自然结束）；矩阵与实机观感见 `migration-report.md` §九。不做逐像素对比 |
| I2-B10 | `docs/SCRIPT_FORMAT.md` 重写 | **已关闭（I-5）** | 全文按 `objects` 重写（2125 → 741 行）；旧字段只出现在「已移除」表 |
| I2-B11 | 宏观循环 L6 裁决 | **已关闭（I-5）** | 选 ②：宏观语义不变、`loop_start`/`loop_end` 仅区间定位；`acceptance.md` L6 已改写 |
| I2-B12 | AUDIO `file_loop` 改名 + 旧 `loop` 双语义切换 | **已关闭（I-5）** | 迁移把 21 支 AUDIO 旧 `loop` 写为 `objects.file_loop`；parser/validator 旧字段出现即「已移除」 |
| I2-B13 | 迁移器 / 差异报告 / 旧字段拒绝 | **已关闭（I-5）** | `migration-report.md`；5 支旧字段负例（`tests/faults/bad_removed_*`）全报「已移除」 |
| I2-B14 | 全量实机 / 性能验收 | **已关闭（I-5）** | 见 `migration-report.md` §八 / §九；性能基线未重测（F5 矩阵为 F 轮既有数据） |
| I3-C1 | `attenuation=inverse` | **已关闭（I-5）** | 选 ② 删除枚举值；`track-audio-adjust.md` §4.4 写明原方案 A / 实选 B / 理由 |
| I3-C2 | `gradient.stops` 动画化 | **已关闭（I-5）** | 选 ① 保持 value-only 并定性关闭；`track-overlay-letterbox.md` §2.3 / §六 |
| I3-C3 | 同轨多活跃层 / 运行中 `z_index` 重排 | **已关闭（I-5）** | 选 ② 明确不支持并定性关闭；同篇 §2.3 / §六 |
| I3 旧画面观感回归（人工） | 4 支循环夹具 + 多轨道脚本 | **已关闭（I-5）** | 功能面矩阵见 `migration-report.md` §九；观感按实机人工确认，不做逐像素对比 |
| I3 新格式 9 支纳入迁移 | `tests/objects/` | **已关闭（I-5）** | 迁移器幂等跳过 10 支已是新格式脚本（含 9 支 objects），行为一致 |
| I4-R1 | `BranchSnapshot.size()` 注释 | **已关闭（I-5）** | 注释改为「timeline 扁平片段数 + 脚本级独立片段数」 |
| I4-R6 | 脚本级独立片段渲染 | **已关闭（I-5）** | `ScriptPlayer` 新增隐式轨道表（按 `track` 类型分组、索引接在 timeline 之后），5 个轨道播放器经 `clipsForTrack` 自然覆盖；夹具 `test_standalone_camera_branch.json`（无 CAMERA 轨，独立 CAMERA 片段作 `exit.goto` 目标）校验通过 |
| H1-R3 | `triggers[].id` / `continuity` 文档 | **已关闭（I-5）** | `docs/TRIGGER_TYPES.md` 新增「触发器身份与连续语义」；`docs/modules/trigger.md` 同步 |
| H2-R10 | clip 级 `interpolation` 现在时表述 | **已关闭（I-5）** | 字段已删除；`docs/SCRIPT_FORMAT.md` 只把它列在「已移除」表 |
| H2-R11 | 夹具 `meta.version` / `total_duration` | **已关闭（I-5）** | 258 支夹具无 `"version":` / `"total_duration":`（仅 5 支负例故意保留） |
| H4-R1 | `spi` 机械迁移 | **已关闭（I-5）** | 11 类收进 `spi`（共 19 类）；只 import `api.*` / `spi.*` 的注册示例可编译 |
| H4-R2 | 第三方轨道通用 `loop` 诊断 | **已关闭（I-5）** | `ObjectValidator` 的 `Check` 兜底分支执行通用 loop 检查 + `pingpong` 仅内置视觉轨提示 |
| H4-R4 | `implementation-progress.md` H11 状态 | **已关闭（I-5）** | 与 `H-other/README.md` 一致，均为 ✅ 已实现（H-2） |
| H5-R10 | `GuiMixin.shouldHide(Boolean)` | **已关闭（I-5）** | 收敛为无参 `shouldHide()`，3 个调用点同步 |
| H5 画面层字段收尾 | 删 `hud_layers` / `hard_hide_hud` 入口 | **已关闭（I-5）** | parser/validator 硬错「已移除」；`ScreenLayer` 表是唯一回落真源；负例 `bad_removed_hud_layers` / `bad_removed_hard_hide_hud` |
| H6 投票语义文档 | control / network / trigger / SCRIPT_FORMAT | **已关闭（I-5）** | 4 份文档改写；`grep "requestExit(ExitReason.USER_SKIP)\|含已跳过者\|投票者先退出" docs/` 为空 |
| H6 `ExitReason.USER_SKIP` | 归属 | **已关闭（I-5）** | 枚举值与 `PlaybackLifecycle` 分支删除；`CompletionReason.SKIPPED` 保留并注明「仅供外部 API / 历史回执识别」 |
| H6 协议兼容说明 | `C2SSkipVotePacket` | **已关闭（I-5）** | `docs/modules/network.md`「协议兼容」节：`displayTest` 按 mod 版本匹配；放开跨版本需 bump `ForgeNetwork.PROTOCOL_VERSION` |

### M-1. I-5 新增移交（4 条）

| ID | 事项 | 归属 | 关闭条件 |
|---|---|---|---|
| I5-R1 | `showcase_02_kill_sheep.json` 的 `conditions.entity`、`showcase_07_barter.json` 的 `conditions.item` 写成**数组**，`TriggerSchemas` 要求字符串（迁移前即存在） | C-trigger 后续 | 定义数组语义并落地（或把夹具改为单值 / `ns:*` 通配）；两条负例届时转正 |
| I5-R2 | 仓库外无头校验器 `E:/tmp/icv` 自带 6 个旧格式 self-test 失效（仍写 `meta.version` / `total_duration` / clip `keyframes`） | 工具侧，不入库 | 把 self-test 样本改成新格式；不影响仓库 |
| I5-R3 | `test_h11_hard_hide.json` 清理非规范 `screen_layers` 键后，需在 Forge 实机复测接管/重画 | H5-R1 批次（外部 overlay 模组） | 装真实第三方 overlay 模组后，`allowlist` / `normal` 两态像素对照 |
| I5-R4 | **内联 timeline 片段的默认 `exit` 不会执行**：`ClipBranchController.poll()` 首行 `if (!branching) return;`，只有「规则启动的分支 target」会被 poll；普通时间线片段播完后没人读 `ClipExitPolicy`。`Clip.exit` 目前只对分支 target 生效 | 后续轮次（I-6 或 Z 前收口） | 在片段完成事件处统一执行默认出口（CONTINUE / GOTO / RETURN / END_EXECUTION / END_SCRIPT），补探针；本轮夹具已改用 `rule.goto_clip` 避开该路径 |

### M-2. A–H 孤儿欠账（2026-11-06 用户裁决归属，不属 I 轮）

审计发现 4 项在 A–H 被写成「挂账」但无人承接；用户裁决后统一落位，细则见对应文档：

| 项 | 归属 | 文档 |
|---|---|---|
| B18 ④过渡打断 / ⑤链式（实机） | **J 轮** | [`J-media/handover.md`](J-media/handover.md) §一 / §二 |
| B19 迟滞观感（四象限人眼判定） | **J 轮** | 同上 §三 |
| C-pause 实机复验 | **J 轮** | 同上 §四 |
| F 轮失败处理 P1/P2（有什么放什么 / 不崩 / 日志定位） | **J 轮** | 同上 §五 + `F-playback/failure-handling-audit.md` §0.4 |
| F5 光影 per-pipeline（WP5） | **K 轮** | [`K-optimization/shader-per-pipeline.md`](K-optimization/shader-per-pipeline.md) |

---

- 任何新增遗留必须补进本表并指定轮次/关闭条件；不允许只写在轮次总结里。

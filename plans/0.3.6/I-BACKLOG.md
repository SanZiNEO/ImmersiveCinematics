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
| I2-B1 | 旧循环夹具实机像素回归 | Fabric / Forge 各跑 `test_camera_loop_repeat` / `test_camera_loop_pingpong` / `test_exit_loop_count` / `test_exit_infinite`；记录执行人、截图/日志；同帧无结构差异 | 旧格式现在就能跑；I-3 接轨道后复跑；I-5 最终全量 |

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
| H1-R1 | `observation` 射线扫掠未做（H8 步骤 3 剩余；设计明确「先成本实测再定」） | **H-2**（H8 步骤 3 剩余） | 成本实测完成（子步数 × 射线方块/实体查询，含移动实体目标）并给出纳入/不纳入结论；纳入时实现弧带 + 子步重放 |
| H1-R2 | 短距离传送（单 tick 位移 ≤ 16 格）无法与正常移动区分，按连续移动参与扫掠 | **H-2**（如需显式钩子） | 跨平台服务端传送信号（事件或 mixin）落地后，短传送也标记为不连续；在此之前为契约边界 |
| H1-R3 | `docs/TRIGGER_TYPES.md` / `docs/SCRIPT_FORMAT.md` 未回写 `triggers[].id`、`continuity`（sweep / dwell / window）与事实口径 | **I-5**（I2-B10） | 文档与源码一致；`grep` 无旧口径残留 |
| H1-R4 | 事实的展示面（「触发过什么 / 为什么触发」）未做；客户端镜像已删除，服务端事实不自动下发 | **Z 轮 + H-12** | 编辑器 / 客户端有读取事实查询 API 的入口（命令或网络包），且不重建第二套记录 |
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

## 清零规则

- **I-3 收口**：A 表至少完成旧循环实机基线；I2-B1 的最终全量留 I-5。
- **I-4 收口（2026-11-03 完成）**：B 表 I2-B2..B9 全部关闭；B-2 表关闭 I3-B1/B2/B3 与卫生项；新增遗留全部落在 E 表（I4-R1 归 I-5、I4-R2 归 Z 轮、I4-R3..R5 为契约边界并已写入对应专篇）。
- **I-5 收口**：C 表全部关闭；D 表在对应专篇有明确文字；本表无“未排期”项。
- **H-2 收口（2026-11-03 完成）**：H9（跳过投票判定 / 广播 / 解析期校验 / HUD 布局）与 H11（`api/HudLayerRegistry` 门面 + Forge 转调 + Fabric 边界 + 对外文档）全部落地；H5 的 12 条逐条核对完毕（见 `H-other/doc-lag-2026-11-03.md`）；H6 的 7 条实机记录见 `H-other/verification-2026-11-03.md`。新增遗留全部落在 G 表，无“未排期”以外的悬空项。
- 任何新增遗留必须补进本表并指定轮次/关闭条件；不允许只写在轮次总结里。

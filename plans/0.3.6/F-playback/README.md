# F 轮（播放 / 渲染）文档索引

> 用途：一页看清本目录 14 篇文档各自负责什么、现在什么状态、还剩哪些开放项。
> 本页只做汇总，不新增结论；结论以各文档正文的落地记录为准。
> 图例：✅ 已完成　🟡 部分可用 / 有开放项　☐ 未开工　⊘ 不做　📄 记录性

---

## 1. 文档一览

| 文档 | 主题 | 状态 | 现行结论 / 落地记录 |
|---|---|---|---|
| [parallel-playback](parallel-playback.md) | 并行播放：实例 / lane 模型、叠加秩序、队列、播放关系 | ✅ 步骤 1–6 落地（2026-10-07）；F2 ✅（2026-10-17 实现 / 10-18 实机覆盖） | §7 步骤 1–6 落地记录、F2 落地记录 |
| [multi-camera-rendering](multi-camera-rendering.md) | 多相机渲染底层：每 lane 独立相机 → 共用离屏 FBO → 上屏；F3–F5 | ✅ 同维度实现完成（2026-10-18）；跨维度 per-view 维度源已实现并随 F8 实机验证（`c0b1ac1` / `50aa5fb`，2026-10-26）；WP5 per-pipeline 为技术收口项 | §12（实现）、§12.10（光影每视角隔离）、§6（成本矩阵）、§4.5（per-view 维度）、[cross-dimension-camera](cross-dimension-camera.md) §4.2 / §6 |
| [camera-composition](camera-composition.md) | 画面合成：取材 / 目标区域 / 不透明度 / 叠放顺序，全关键帧 | 🟡 主体落地（§7 步骤 3–5），5 项开放；维度源与合成解耦已实现（`c0b1ac1`，2026-10-25） | §7 步骤 3–5、§5 / §6、§4.5（维度源） |
| [iris-oculus-compat](iris-oculus-compat.md) | 光影（Iris / Oculus）兼容调研 + 每视角状态隔离 | ✅ 同维度调研完成 + F3 验收通过（2026-10-18）；跨维度 view 已实现并随 F8 实机验证（`c0b1ac1` / `50aa5fb`，2026-10-26）；WP5 per-pipeline 为技术收口项；2 项已知限制 | §7（结论与修复）、§7.5（Forge/Oculus）、§4.5（跨维度 view） |
| [f-round-issues](f-round-issues.md) | F 轮实测问题报告：绿边 / 扇形定位、性能矩阵、失败处理原则 | ✅ F 轮内已收口：问题 A / B 已修；矩阵完整；失败处理 P0 第 1 批已落地；P1/P2 属审计后续 | §1.1（矩阵复核）、§2 / §3（结论）、§5（下一步） |
| [failure-handling-audit](failure-handling-audit.md) | 运行期失败 → 崩溃路径审计（60 findings / 23 P0） | 🟡 第 1 批 P0 已落地（`4eb65b7`，2026-10-25）；P1/P2 未做（不属 F 轮关单）；§0.4 已回写 | §0.4（实施状态）、§9（三批与验收） |
| [f5-script-packet-limit](f5-script-packet-limit.md) | 大脚本传输上限（32,767 字符）→ gzip + 长度前缀 | ✅ 已修复（2026-10-18） | 全文（含 l25 复测数据） |
| [history-consolidation](history-consolidation.md) | 光影调查分支历史压缩记录 | 📄 记录性 | 全文 |
| [camera-center-entities](camera-center-entities.md) | 相机中心下的刷怪 / 实体可见性（C 方案） | ✅ 同维度 C0–C3 / C6 已实现并提交（`8354731`）；C4 / C5 接受项；跨维度视图流已实现（`50aa5fb`）并随 F8 实机验证；已知限制：相机附近生物无 AI | §六（实现结果）；§4.5（视图流与偏离） |
| [camera-pool](camera-pool.md) | 相机租约池（按 CAMERA 片段租借、归还清状态） | ✅ 已实现并提交（`38f3972`，2026-10-26）；实机重播日志已跑（2026-10-26：slot 0/1、停播 active=0、重播 gen=2） | §六（实现结果）、[camera_pool 测试说明](../../cinematics/0.3.6/tests/camera_pool/README.md) |
| [drop-preload](drop-preload.md) | 摘掉预加载 / 预热概念：模块改名相机中心同步，删死配置、预热空转与冗余协议 | ✅ 已实现（2026-10-25） | §六（实现结果） |
| [cross-dimension-camera](cross-dimension-camera.md) | 跨维度相机：per-view 维度渲染（相机采样带维度；每 lane 用自己的维度源；服务端按 `(player, viewId)` 供数据） | ✅ P0 + WP1–WP4 已实现并提交（2026-10-25）；F8 已实机验证（2026-10-26）；已知限制：相机附近生物无 AI；WP5 per-pipeline 为技术收口项 | §八（实现状态与已知限制）/ §六（施工与验收） |
| [cross-dimension-lightmap](cross-dimension-lightmap.md) | 跨维度 lane 的目标 `LightTexture` 绑定：为什么目标画面发暗、`GameRenderer.lightTexture` 字段换入方案 | ✅ 字段换入已落地（2026-10-26），随 F8 实机验证通过 | §一（结论）、§三（证据）、§五（修复）、§六（验收） |
| [script-path-resolution](script-path-resolution.md) | 命令脚本路径：`/` 为规范、colon 为兼容别名、字面优先旧解码兜底 | ✅ 已实现（2026-10-26）；实机命令验证待统一测试 | §三（解析顺序）、§五（验收）、§七（实现结果） |

---

## 2. 已完成（可核对）

| 项 | 结论 | 证据 |
|---|---|---|
| F2 队列接播 | 出队按 `priority` / FIFO 逐项判定可播性；无 exclusive 一次排空；同脚本排队副本不误启动；预览隔离；去容量 8 | 无头探针 68/68；实机由 C 轮队列接播覆盖（[parallel-playback](parallel-playback.md) F2 落地记录） |
| F3 光影适配 | 删除整条禁用，lane 始终渲染；检测仅用于跳过 `setupRender` 专属 mixin | Fabric/Iris 1.7.6 三视角 + 热切换实机；Forge/Oculus 1.8.0 同构移植、同 jar（[iris-oculus-compat](iris-oculus-compat.md) §7） |
| 光影跨视角污染 | 根因 = 每视角共享的历史 colortex / `shadowcolor` / 深度 / `Previous`，按视角保存恢复 + 自动探测历史目标 | MakeUp / Complementary / BSL 三包 A/B 干净（[f-round-issues](f-round-issues.md) §2 / §3） |
| F5 成本矩阵 | l0 / l1 / l4 / l16 / l25 × FULL / WORLD_ONLY 全部 600/600 | [f-round-issues](f-round-issues.md) §1.1、[f5-script-packet-limit](f5-script-packet-limit.md) |
| 大脚本传输 | gzip 字节数组 + `writeVarInt` 长度前缀；解压后 4 MiB / 压缩后 1 MiB 双向上限；命令入口预检 | 原始 47,061 字节脚本直接播放；负例 4,400,160 字节被拒（[f5-script-packet-limit](f5-script-packet-limit.md)） |
| F7 相机租约池 | 每片段一个 `CameraLease`，槽位 + 代际（+ 维度）作为视角键；片段结束 / 停播 / 替换脚本 / 世界卸载确定性归还；剔除槽随租约独占 | `38f3972`；fixture 与日志口径见 [camera_pool 测试说明](../../cinematics/0.3.6/tests/camera_pool/README.md) |
| F6 相机中心 / 实体 | 同维度 C0–C3 / C6 已实现；C4 / C5 接受项；跨维度视图流已实现并随 F8 验证 | `8354731` / `50aa5fb`；[camera-center-entities](camera-center-entities.md) §六 |
| F8 跨维度相机 | per-view 维度源、服务端视图流、三画面脚本已实现；2026-10-26 实机验证通过；已知限制：相机附近生物无 AI | `8354731` / `c0b1ac1` / `50aa5fb`；[cross-dimension-camera](cross-dimension-camera.md) §八 |

---

## 3. 关单条件与剩余验证

**代码关单条件 = ① [failure-handling-audit](failure-handling-audit.md) 的 P0 批次；② [camera-center-entities](camera-center-entities.md) 实现；③ [camera-pool](camera-pool.md) 实现；④ [cross-dimension-camera](cross-dimension-camera.md) 实现（per-view 维度渲染）。** 截至 2026-10-26，① P0 第 1 批已落地，②③④ 均已实现并提交；F8 已实机验证，F6 / F7 实现完成；F 轮内剩余只有已知限制收口（相机附近生物无 AI）与 WP5 per-pipeline 技术项，不改变代码关单口径。P1/P2 审计项不属 F 轮关单条件。已完成部分（性能数据、F2 / F3 / F5）见 §2。

> **F1 不是独立功能项**：它是 failure-handling 第 1 批 P0 的内部批次名，不在 F 轮功能表里单列；F 轮只按「P0 批次」关单，不按 F1 编号类推。

| 项 | 现状 |
|---|---|
| findings | 60 条，其中 23 条 P0（高频路径且当前无边界） |
| 已实施 | 第 1 批 P0 已落地（`4eb65b7`）；P1/P2 未做；正文 §0.4 待回写 |
| 空体 / 仅注释 catch | 第 1 批边界已加；剩余条目按 P1/P2 处理 |
| `PacketGuard`（统一包处理边界） | 第 1 批已落地（`4eb65b7`） |
| 断线清理（`CameraCenterReporter` 直发、`sendToPlayer` 无防护、断线不清 viewer） | 第 1 批已落地（`4eb65b7`） |
| camera-center-entities | ✅ C0–C3 / C6 已实现并提交（`8354731`）；C4 / C5 为接受项；跨维度视图流已实现（`50aa5fb`）并随 F8 实机验证。已知限制：相机附近生物无 AI |
| camera-pool | ✅ 已实现并提交（`38f3972`）；`CameraLease` / `CameraLeasePool`、租约键带维度、剔除槽随租约归还；实机重播日志已跑（slot 0/1、停播 active=0、重播 gen=2） |
| cross-dimension-camera | ✅ P0 + WP1–WP4 已实现并提交（`8354731` / `c0b1ac1` / `50aa5fb`）；F8 已实机验证（2026-10-26）；已知限制：相机附近生物无 AI；WP5 per-pipeline 为技术收口项。三画面 fixture 与观察点见 `cinematics/0.3.6/tests/camera_center/README.md` |

批次顺序与验收标准见该文档 §9；三条硬口径（只在边界捕获、捕获必须可定位、致命不吞）见 §0.3。审计范围不含「改设计」，只加边界与降级。

---

## 4. 开放项（按文档）

### parallel-playback

- 听者「后来者居上」口径未对齐：`AudioListenerController` 已按顶层实例取值，但 `hasActiveCameraClip` 仍是并集（§5 / §6）。
- 多实例 AUDIO 的空间锚点（听者唯一，见 §3.3）。
- 跳过投票 UI：多实例同时可跳过时投给谁；服务端已按实例记账，`SkipHudRenderer` 只呈现顶层实例（§5）。
- 实例与 Base Provider 的对应关系（§5）。
- 实例 id 的网络协议形态：id 生成 / 校验 / 跨端对齐（包字段已落地，§3.7 / §6）。
- 主 lane 判定规则（与 [camera-composition](camera-composition.md) §6 一起定）。
- 同脚本重入是否放开：需先改状态机键（§3.5）。
- 互斥组 / 队列语义的最终形态：互斥组已确认不做，队列语义仍待定（§6）。
- 实例数 × lane 数的开销曲线：lane 侧已由 F5 覆盖 1/4/16/25，实例数未测（§5）。

### camera-composition

- `dest` 矩形之外无 lane 覆盖时的兜底：现状 = 露原版玩家视角；是否改黑场未定（§5）。
- 编辑器预览的多 lane 合成表现（步骤 6，归编辑器阶段）。
- 与 HUD / letterbox 的叠放顺序：合成层写主 framebuffer、在原版 GUI 之前；letterbox 属 OVERLAY 层，层级 / z 序基线待定（§5）。
- 主 lane 判定规则（§6）。
- 跨脚本 lane 寻址（候选 B「合成导演」的前置，§6）。
- 维度源：已实现（`c0b1ac1`）；合成层不读维度，只读 lane 纹理；跨维度 lane 的 `dest` / `source` / `opacity` 与同维度一致。见 [cross-dimension-camera](cross-dimension-camera.md)。

### multi-camera-rendering

- 副画面是否需要支持 `look_at` / tangent 朝向（§9）。
- 是否允许叠化期间相机 lane 继续移动（§9；属编排 / 语义决定，合成层不限制）。
- Rubidium 兼容：`example/` 无源码，只按入口类名覆盖，未取 jar 逐一核对 —— **已知限制**（§9 / §11）。
- 每 lane 独立光照 / 光照更新：继承自 `_archive/render-second-pass-cost.md` §7；F3 后 lane 每遍走光影自己的注入链（[iris-oculus-compat](iris-oculus-compat.md) §7），该问题未单独复测。
- 跨维度 view：已实现（`c0b1ac1` / `50aa5fb`）；每个 lane 用自己的 `DimensionView`（level / renderer / lightmap / 剔除 / 光影状态），随 F8 实机验证。WP5 per-pipeline 仍是技术收口项，见 [cross-dimension-camera](cross-dimension-camera.md) §八。

### iris-oculus-compat

- 检测口径：按「光影模组存在」还是「光影包已启用」；主画面侧已定（`IrisApi.isShaderPackInUse()`），副画面侧未定（§5.2）。
- 是否给用户配置开关（「光影下强制启用副画面（自担风险）」）（§5.2）。
- 性能档位是否按光影另立（现有推荐值是无光影口径）（§5.2）。
- lane 画面的目标形态：2026-10-18 实现 = 光影画面（每 lane 走完整光影链）；成本未单独测，是否作为唯一形态待数据（§5.2）。
- Rubidium 结论来源（无源码，只能 jar 核对 + 实机）（§5.2）。
- 「装了 Iris 但未启用包」这一档未实测（§3）。
- **已知限制**：⑤ Rubidium 类名核对、非 `SRGB` 色彩空间 —— 不阻塞关单（§7.5）。

### camera-center-entities（已实现）

- C0–C3 / C6 已实现并提交：状态清理 / 超时、统一相机位置源、配对刷新节拍、刷怪局部上限、虚拟玩家 y、诊断分项计数。
- C4 / C5 是接受项：lane pass 各用各的相机中心；非 lane pass 的共享 `ViewArea` 网格整帧单中心是客户端实现约束，不是「主相机」；客户端可见半径维持原版。
- 跨维度数据流已实现：`CameraViewManager` 按 `(玩家, view id)` 维护 ticket / 区块 / 实体 / 虚拟玩家，不改造同维度 `CameraCenterManager`，见 [cross-dimension-camera](cross-dimension-camera.md)。
- **已知限制**：F8 实机观察「相机附近生物没有 AI」；目标维度实体只发初始配对、不 tick，是已知原因之一。作为已知限制记录，不阻塞 F6/F8 关单。

### camera-pool（已实现）

- 租约粒度 = 片段；`CameraLease` / `CameraLeasePool` 在片段激活时 acquire 干净槽位，结束 / 停播 / 替换脚本 / 世界卸载时 release 并清状态。
- 视角键 = 槽位 + 代际（+ 维度）；同轨道相邻片段、重播不再继承上一片段的光影历史 / 剔除槽。
- 剔除槽随租约独占；FBO 不进池，继续共享 `offscreenTarget` / `adjustTarget`；预览与游戏实例共用同一个池。
- 实机重播日志已跑：slot 0/1、停播 active=0、重播 gen=2；fixture 见 `cinematics/0.3.6/tests/camera_pool/test_lease_replay.json`。

### cross-dimension-camera（已实现，已实机验证）

- P0 + WP1–WP4 已实现并提交（`8354731` / `c0b1ac1` / `50aa5fb`）；F8 三画面已实机验证（2026-10-26）。
- 相机采样带维度；CAMERA clip 的 `dimension` 缺省 / 空串 = 玩家当前维度，填了才走跨维度。
- 每条 lane 用 `DimensionView`（`ClientLevel` + `LevelRenderer` + `LightTexture` + 剔除 / 光影 / 实体状态）；同一帧顺序渲染多个维度，合成层不变。
- 服务端按 `(玩家, view id)` 维护视图流；目标维度的区块 / 实体走独立流，不替换 vanilla 玩家差集；不传送玩家、不注册假玩家、不切整个客户端。
- 已知限制：相机附近生物无 AI；目标维度实体是快照，不移动 / 不播放动画；没有方块 / 光照 / 声音 / 天气 / 粒子增量；`FABULOUS` 拒绝目标视图。
- WP5 光影 per-pipeline 隔离仍是技术收口项，不是 F8 功能阻塞项。

---

## 5. 阅读提示（快照 vs 现行）

- [f-round-issues](f-round-issues.md) §1 的表是 2026-10-17 现场记录（多档缺测）；**现行状态看 §1.1 复核表**。
- [multi-camera-rendering](multi-camera-rendering.md) §12 末尾「事实核查」是 2026-10-07 快照，**不覆盖**后续合成 / F3 / F5 / 光影隔离；以 §7–§12 正文为准。
- [iris-oculus-compat](iris-oculus-compat.md) §3 是源码级推演，**结论在 §7**；§5.2 的待定项仍未定。
- [failure-handling-audit](failure-handling-audit.md) §2–§6 是**待实施清单**，不是已完成记录；状态只看 §0.4。
- [camera-composition](camera-composition.md) §5 的 🟡 项与 §6「待定」有重叠（主 lane 判定），本索引已合并列出。
- [cross-dimension-camera](cross-dimension-camera.md) 已于 2026-10-25 按 IP / MC / 光影 / 本仓代码调查重写为 per-view 维度渲染；旧 0.4.0 F 类与 A/B/C 选型不再作为现行口径，A（传送玩家）已停用。P0 是验证顺序，不是能力边界。

---

## 6. 分工

| 问题 | 看哪篇 |
|---|---|
| 谁在播、能同时播几个、排队怎么走 | [parallel-playback](parallel-playback.md) |
| 多张相机画面怎么渲染出来 | [multi-camera-rendering](multi-camera-rendering.md) |
| 画面怎么铺到屏幕（取材 / 位置 / 透明度 / 顺序） | [camera-composition](camera-composition.md) |
| 光影下语义对不对、状态怎么隔离 | [iris-oculus-compat](iris-oculus-compat.md) |
| 出错时该不该崩、在哪兜 | [failure-handling-audit](failure-handling-audit.md) |
| 相机播放时服务端中心 / 刷怪 / 实体可见性 | [camera-center-entities](camera-center-entities.md) |
| 相机生命周期与视角状态复用 | [camera-pool](camera-pool.md) |
| 预加载 / 预热这套命名怎么摘掉 | [drop-preload](drop-preload.md) |
| 相机能不能在另一个维度 | [cross-dimension-camera](cross-dimension-camera.md) |
| 实测记录与收尾过程 | [f-round-issues](f-round-issues.md)、[f5-script-packet-limit](f5-script-packet-limit.md)、[history-consolidation](history-consolidation.md) |

# 0.3.6 全量实现进度（todo / 追踪文档）

> 生成：2026-10-07 晚。**明天从这里接着做。**
> 目标：**0.3.6 计划全部实现**（不允许"差一点"）；**编辑器（游戏外 WebUI）放到最后**（需严密规划：参考 `example/editor/{olive,lossless-cut}`，核心 = 只产出游戏可解析的固定格式脚本）。
> 执行方式：一个子代理只做一个小任务（任务书写全需求）→ 主代理验收（编译 + 冒烟/读码）→ 中文 commit → 更新本表。**并行口径（2026-10-08 用户明确）**：**计划类任务可并行**（各写各的文档、互不冲突）；**执行类任务必须串行**（改代码的多子代理易出错——一个完成并验收提交后才开下一个）。
> 依据：`plans/0.3.6/implementation-audit-2026-10-07.md`（缺口总清单）；`plans/0.3.6/pending-discussions.md`（未定项清查：多数属执行时再定型、任务落地时定稿；少数需拍板）；工作笔记 `.planning/036-impl/state-brief-{arch,flow}.md`（现状/工具）。

## 通用原则（每个任务都必须遵守）

- **数据驱动**：先下定义（机制/模型）→ 唯一解释器读它/调控它 → 参数全部来自脚本数据；**演示/效果不写死进代码**（四象限 = 一个脚本四相机轨，不是代码演示）。
- **运行时只读只执行（2026-10-08 用户裁决）**：三层分工 = **定义**（运行时代码只写机制/模型定义）→ **调配**（编辑器做创作与运算——曲线手柄、模板展开、预设生成等效果在编辑器里算出来）→ **应用**（运行时解释器读脚本数据、执行调配结果）。**修改/生成/调配运算一律在编辑器侧**；曲线、手柄、模板这类创作性机制不进运行时代码，编辑器产出运行时可直接执行的纯数据。**运行时职责 = 固定的五项：解析、播放、触发、调度、网络交换**（2026-10-08 盘点定稿，职责面固定、不含「改」的东西）：
  - **解析**：脚本 / schema / 关键帧解析与校验，含世界相关引用的执行时解析（实体选择器、结构 / 方块 → 坐标）——代表：`ScriptParser`、`ScriptValidator`、`schema/`。
  - **播放**：时间推进、轨道 / 片段求值、lane 渲染合成、调色 pass 等画面执行——代表：`ScriptPlayer`、`*TrackPlayer`、`LaneRenderer` / `LaneCompositor`、`ColorAdjustPass`。
  - **触发**：触发源 / 条件 / 动作 / 前置 / 状态机——代表：`trigger/`。
  - **调度**：资源 / 图层 / 事件的编排（图片 / GIF、OVERLAY 层 z 排序、音频实例、EVENT 时间线、区块预加载、帧捕获推流）——**只安排「何时做什么」，不含修改**——代表：`OverlayManager`、`ScriptEventManager`、`ChunkPreloadManager`。
  - **网络交换**：C2S/S2C 包与回执、WebUI WebSocket——代表：`trigger/network`、`webui/`。
  - **实时动态例外（唯一允许进运行时的「调整」）**：必须逐帧响应的动态——呼吸扰动（`BreathDisturbance`）、look_at / follow 跟踪、选择器切换平滑；除此之外不把「调整 / 修改」逻辑写进游戏运行代码，除非功能必需。
  - **越界处置（2026-10-08 盘点出 4 处，按本口径收敛）**：`script/template/ClipTemplate`（片段模板展开）与 `script/ScriptTemplate`（脚本骨架生成）→ 归编辑器（随 E 批次任务书重审）；`util/ScriptStructureResolver`（推送前改写脚本数据）→ 改为**执行时解析、不改写数据**；`LetterboxTrackPlayer:71-72` 硬编码 smoothstep 缓动形状 → 复核数据化（形状应由关键帧 / 曲线数据驱动）；`control/FlightController`（飞行直控）= 编辑器链路的**游戏侧执行器**（输入/直控，非创作逻辑，保留并标注定位）。
- **调控顺序（画面完整性）**：画面处理（调色等）**只动 RGB、alpha 逐位直通**；透明度（alpha/opacity）一律在**画面合成完成之后**、在**合成层**调控；绝不把透明烤进画面、绝不先处理 alpha。
- **决策从文档推导（2026-10-08 用户指正）**：计划文档已有口径/倾向/落地步骤的，**直接照办一路顺下去**，不要重复问用户；只有文档确实未覆盖的（新需求、真二选一）才问。
- **任务书必须引用文档段落（2026-10-08 审计教训）**：每个任务书里的**设计决策**（数据放哪、层级语义、字段形态）必须先在计划文档里找到对应段落并引用；**找不到对应段落 → 先问用户，不自创**。现状简报（`.planning/036-impl/state-brief-*.md`）只是**代码现状**参考，**不能当设计依据**。反例：A8 自创 `scope=lane`（文档口径 = 每个 lane 各自持有、调色挂 CAMERA clip 关键帧）；A9 自创 `scope=layer` 语义（文档无机制描述）。
- **全模组调度口径（2026-10-09 用户裁决）**：模组按脚本解析「**能放什么、怎么放、能调度什么**」，并管住时机与调度；执行 = **有什么放什么、脚本定了怎么放就怎么放、没有就不放**。多个脚本同屏（放八九个都行）各自按自己的数据执行，模组不挑不猜、不做智能补偿；效果不符合预期，只要不是代码根本性问题，就是**作者侧要解决的**（如多 LUT 叠加的观感）。此口径适用于整个模组，不限调色。

---

## 批次 A：调色系统（screen-color-adjust）

| ID | 任务 | 依赖 | 状态 | commit |
|---|---|---|---|---|
| A1 | R/G/B 通道（master，-1~1 乘性、操作栈第 6 步） | — | ✅ | `d1ae22e` |
| A8 | lane 级调色 = **相机片段自带**（CAMERA clip / 关键帧携带调色，作用于该相机轨产出的 lane，渲染完、合成前） | — | ✅ | `8114a85` → `b529757`（原实现数据形态错误：自创 `scope=lane` + ADJUST 轨挂调色；0.3.6 修正为相机片段口径，ADJUST 轨回归 master） |
| A14 | 四象限交付物重写：单脚本四相机轨（4 机位 + dest 象限 + 每格 RGB 关键帧 + BR opacity 0.5 + BR 裁切动画） | A1,A8 | ✅ | `d0fc80b` |
| A2 | 完整 HSL 通道（hue 色相旋转 ±180° + lightness 亮度） | — | ✅ | `d03626a` |
| A3 | RGB 复合曲线（形态 b：曲线点集 + curve_strength 关键帧，CPU 采样 LUT） | A2 | ✅ | `6f91150` |
| A4 | 每通道曲线（R/G/B 三条 + 各自 strength） | A3 | ✅ | `45f03ed` |
| A13 | 多实例 master 合并 → 按层级叠加（见「用户裁决新增」A13'） | A8 | ✅ | `4aa03d8`（按 A13' 口径） |
| A5 | 六条 hue 曲线（HvH/HvS/HvL、LvS/SvS/SvL） | A3 | ✅ | `a1408fe` |
| A6 | RGB 通道混合器（3×3 矩阵 9 参数） | A1 | ✅ | `0a74539` |
| A7 | Lift/Gamma/Gain 色轮（或 LOG 色轮） | A2 | ✅ | `c4f0901` |
| A9 | 调整层 = **ADJUST 轨**（顶层、不参与排序、默认比其他层级高一个）作用于**整幅画面**；**无新机制**（2026-10-08 用户口径） | A8 | ✅（文档口径修正结案，无代码） | — |
| A10 | LUT（Color Lookup + 强度） | A9 | ✅ | `1157788`/`d1cbf7f` + `ed2a18e`（欠账清偿）/`76f0fe7`（输入域）/`43ca082`（文档） |
| A11 | PS 式六色带微调（Hue/Sat 分色带） | A5 | ✅ | `01cae9f` |
| A12 | 混合模式作用于调整层（正片叠底/屏幕/柔光/叠加） | A9 | ✅ | `ff1e5a5` |

## 批次 B：相机底层

| ID | 任务 | 依赖 | 状态 |
|---|---|---|---|
| B1 | Base/Modifier 覆盖链（优先级、通道掩码、REPLACE/ADD/MULTIPLY + 示例） | — | ☐ |
| B2 | 每 lane 独立 CameraPath/CameraProperties 核对（可能已被 lane 快照模型取代 → 结案回写文档） | — | ✅ 2026-10-10 结案（已被 lane 快照模型取代，回写 camera-state-plan §7 + camera-core-split §五） |
| B3 | 每 lane 独立可见集合/遮挡剔除（长期解，消除 smartCull=false 缓解的代价） | B1 | ☐（B3-a 设计定稿 ✅ 2026-10-10（multi-camera-rendering §12.9）；B3-b/c/d 未做） |
| B4 | 外部 API（common api 包）+ 相机核心拆分与死代码清理（方案已定稿：`camera-core-split.md`，2026-10-08 用户扩容） | B1,B3 | ☐（P0–P4 ✅ 2026-10-10（P2 Playback 四件 / P3 EntityTargetResolver / P4 Evaluator+Collector+Locator）；P5 未做） | |
| B5 | 统一点源清单（yaw_base_from/to、facing_target 接受完整点源） | — | ☐ |
| B6 | look_at 方块来源 + 部位百分比微调 | — | ☐ |
| B7 | 偏移下放到点源 / 通道相对化 | B5 | ☐ |
| B8 | 方向锁（yaw/pitch 分轴锁值） | B7 | ☐ |
| B9 | 垂直线边界修复 + 「视线/身体朝向」措辞 | — | ✅ 2026-10-10（`5497265`，Q3；表状态回写） |
| B10 | selector 调用点隔离补全（yaw_base_from/to + schema 声明） | — | ✅ 2026-10-10（调用点 6→8 + schema 32 字段 `<字段>_<调用点>` + 校验 + 文档） |
| B11 | selector 缓存键含调用点/锚点 | B10 | ✅ 2026-10-10（键 = Key(callpoint, anchorId, selector)；失效契约入 javadoc；实机双锁实证） |
| B12 | selector 锚点可配置 | B10 | ✅ 2026-10-10（selector_anchor 四值 camera/player/target/origin + 回落链；实机判别冒烟实证） |
| B13 | selector 多候选与择一策略 | B10 | ✅ 2026-10-10（selector_pick first/nearest/alive + 8 调用点变体；A/B 零回归 + 实机判别冒烟） |
| B14 | selector 解析缺陷（@a/@r/@n/@p[team]） | — | ✅ 2026-10-10（@a/@r/@p[…] 进服务端解析；@n=原版不存在按未知形式 warn 不崩；实机实证） |
| B15 | selector 通用化 | B10-B13 | ✅ 2026-10-10（selector/ 包通用服务 + SelectorSchema 定义单源 + 相机适配层；A/B 逐字节零回归 + 实机双冒烟复跑一致） |
| B16 | 数学函数模型库（spring/damper/… + 非线性变换 + 组合 + Registry） | — | ☐ |
| B17 | 时间插值 步骤 3-6（相机快照插值/历史缓冲/模型接入/API） | B16 | ☐ |
| B18 | 过渡 步骤 3-8（分参数过渡/曲线/覆盖链接入/打断链式/API） | B1,B16 | ☐ |
| B19 | 迟滞 6 步 | B16,B1 | ☐ |

## 批次 C：触发器

| ID | 任务 | 依赖 | 状态 |
|---|---|---|---|
| C1 | 组合器嵌套（多层 all_of/any） | — | ☐ |
| C2 | death 触发器类型 | — | ☐ |
| C3 | 检测频率可配（按触发器/按脚本） | — | ☐ |
| C4 | 动作面收敛（3 个死代码动作：注册或按"命令归 EVENT 轨"删除） | — | ☐ |
| C5 | 播放关系与队列（三态 play_mode@meta + 队列可达性修复 + 控制原语） | — | ☐（设计 ✅ 2026-10-10 playback-relations.md；用户拍板三态@meta） |
| C6 | 判定规则系统（clip.rules：if→do 持续 / until→do 阻塞；判定点/等待点收编） | — | ☐（设计 ✅ 2026-10-10 condition-system.md；用户拍板条件=片段元数据、不新开轨道、**分支失败回退主支** §5.1） |

## 批次 D：转场 / 遮罩 / 覆盖层

| ID | 任务 | 依赖 | 状态 |
|---|---|---|---|
| D4 | 渐变遮罩（线性/径向多色 stops） | — | ☐ |
| D5 | 混合模式（正片叠底/屏幕/柔光/叠加） | — | ☐ |
| D6 | 局部遮罩（矩形/圆形区域） | D4 | ☐ |
| D1 | 转场数据落点定稿（§4 A/B/C）+ 实现衔接字段 | D6 | ☐ |
| D2 | wipe | D1 | ☐ |
| D3 | z 序策略（转场遮罩压字幕） | D1 | ☐ |
| D7 | test_overlay_zindex 重做（高对比 + 说明文字层） | D4-D6 | ☐ |
| D9 | 文本层 source/fit（variable-frame 步骤 5） | — | ☐ |
| D10 | LETTERBOX opacity 接线 | — | ☐ |
| D8 | 画面层并入统一模型（variable-frame 步骤 3；不统一则结案回写） | — | ☐ |

## 批次 E：模板

| ID | 任务 | 依赖 | 状态 |
|---|---|---|---|
| E1 | 嵌套引用（片段级 ← 轨道级 id） | — | ⊘ 归编辑器阶段（2026-10-10 E0 重审，templates.md §0） |
| E2 | 轨道级模板 | E1 | ⊘ 归编辑器阶段（2026-10-10 E0 重审） |
| E3 | 脚本级模板（meta/触发器/requires 骨架） | E2 | ⊘ 归编辑器阶段（2026-10-10 E0 重审） |
| E4 | 纯数据/用户自定义模板 | E3 | ⊘ 归编辑器阶段（2026-10-10 E0 重审；B16 前置已解除） |

## 批次 F：播放/渲染主线

| ID | 任务 | 依赖 | 状态 |
|---|---|---|---|
| F2 | 队列按脚本匹配接播（ScriptQueue API + deactivateNow 语义） | — | ☐ |
| F3 | Iris/Oculus 适配（挂点先后调研 + 兼容或文档化限制） | — | ☐ |
| F4 | 视距档位 | — | ☐ |
| F5 | 帧耗时统计（新结构下重测成本曲线） | B3 | ☐ |

## 批次 G：script-model

| ID | 任务 | 依赖 | 状态 |
|---|---|---|---|
| G1 | 参数寻址到分量（"只让 X 动"） | — | ☐ |
| G2 | FieldDef keyframable 标记 | — | ☐ |
| G3 | meta 关键帧化（listener/hide_hud/可跳过等） | G1 | ☐ |
| G4 | 时间精度（float → 高精度）→ **时钟抽象**（任务书已定稿：`plans/0.3.6/clock-abstraction.md`，2026-10-09 用户裁决五步——① Clock 抽象 + 单调秒（GameClock/PreviewClock）② elapsed 链路 double 化（消 float 截断）③ elapsed 唯一分发语义 + 去死参数 ④ overlay delta 真实化 ⑤ float 存储边界契约；顺带修跨脚本并行 hasActiveCameraTrack 算错） | — | ☐ |
| G5 | 叠化 hold 自动补帧工具/模板 | — | ⊘ 归编辑器工具（2026-10-10 归置，templates.md §7） |
| G6 | 关键帧结构升级（自带插值/手柄）——按方案 E 结案（文档标注） | — | ✅ 2026-10-10 标注完成（script-model §4-1/§8 步骤 2） |

## 批次 H：其它

| ID | 任务 | 依赖 | 状态 |
|---|---|---|---|
| H1 | region-sync 全部实现（平移映射/镜像旋转/多区域链/传送/防乒乓/朝向策略） | — | ☐ |
| H2 | wait-point-track 全部实现（WAIT_POINT 轨 + 事件源 + pause_managed + player_death + 分支） | C2 + **P1-impl + P3-impl**（事件/事实层先行——等待点等的事件正是这一层；H2 需在二者之后） | ☐ |
| H4 | feedback-04 #1 结案（按"命令归 EVENT 轨"新方向标注） | — | ☐ |
| H5 | 文档滞后清理（审计 §三 12 条） | — | ☐ |
| H6 | 实机验证批次（审计 §四 7 条：调色/叠化/hard-hide/模板命令/release 脚本游戏内跑） | A,B,D | ☐ |

## 用户裁决新增（2026-10-08）

| ID | 任务 | 依赖 | 状态 |
|---|---|---|---|
| CLEANUP-2 | 删除运行时 pip 层（被 lane 取代；PipLayer 是占位实现从未接入真实画面）；WebUI 便捷操作留编辑器阶段 | — | ✅ 2026-10-08 代码完成（`5b3265d`）；2026-10-10 表状态回写 |
| P1 | **计划文档《脚本状态机与状态追踪》**：布尔值点状事件判定 → **事实追踪**（用户点名；后续优化基于它） | — | ✅ `6c1097f`（plans/0.3.6/state-tracking.md） |
| P1-impl | 按 P1 计划实现状态机/状态追踪优化 | P1 | ☐ |
| P2 | **计划文档《Iris/Oculus 兼容调研计划》**：挂点/可行性/风险/工作量（用户点名：先写计划，结论定策略） | — | ✅ `2ff77b6`（plans/0.3.6/iris-oculus-compat.md） |
| P3 | **计划文档《触发器系统的连续性》**：现状 = 全采样但不连续（离散轮询）；目标 = 做成连续的（与状态追踪同类思路；用户点名，独立成篇） | — | ✅ `8d6fc61`（plans/0.3.6/trigger-continuity.md） |
| P3-impl | 按 P3 计划实现触发器连续性 | P3 | ☐ |
| B3' | 每 lane 独立可见集合 → **做**（用户裁决；工程大，拆多个子任务：设计 → 实现 → 性能实测） | — | ☐ |
| A13' | 多实例 master 合并 → **按层级叠加**（lane 包内/包上调整 → 合成 → master 整体调整；多实例 master 按实例顺序叠加应用） | A8 | ✅ | `4aa03d8` |
| 任务2改判 | **曲线贝塞尔手柄归编辑器阶段**（2026-10-08 用户裁决，替代「手柄本轮补」）：手柄属编辑器侧运算（运行时只读只执行、**不含手柄逻辑**），产物数据形态随编辑器阶段定稿；已标注 screen-color-adjust.md「本版本明确不做」 | — | ✅ 标注完成 | |
| 创作机制口径 | **运行时只读只执行；修改/生成/调配运算归编辑器**（2026-10-08 用户裁决，已入通用原则）：曲线手柄、模板等创作性机制不进运行时代码，编辑器运算产出运行时直接执行的纯数据——**E 批次（模板）等任务书开工前按此口径重审**（模板展开归编辑器） | — | ✅ 口径记录 | |
| LUT 微调组合 | **输入域适配 + 外部工作流 + 编辑器登记**（2026-10-09 用户裁决）：运行时加 `lut_input_gamma` 小参数修输入域错位（现成 cube 偏色时作者试 0.45/2.2）；「对游戏画面调 cube」工作流文档 `docs/LUT_WORKFLOW.md`（外部达芬奇路线）；LUT 微调工具登记编辑器阶段清单——三层各归其位 | — | ✅ | `76f0fe7`/`f8a6265` |
| A11 形态 | **固定 PS 六带 × Hue/Sat = 12 通道**（2026-10-09 用户拍板）：不做每带 Lightness——加性运算会把 alpha=0 透明像素烤出非零 RGB，lane 合成前出缺失；hue/sat 对黑像素恒 0，透明像素不复活 | A5 | ✅ | `01cae9f` |
| A12 形态 | **blend_mode 枚举（normal/multiply/screen/soft_light/overlay）+ blend_amount 关键帧**（2026-10-09 用户拍板）：层级混合在全部操作栈步骤后整体施加，非栈步骤 | A9 | ✅ | `ff1e5a5` |
| 调度口径 | **全模组口径**（2026-10-09 用户裁决，已入通用原则）：模组按脚本解析「能放什么、怎么放、能调度什么」并管时机；执行 = 有什么放什么、脚本定了怎么放就怎么放、没有就不放；多脚本同屏各自按数据执行，模组不挑不猜；预期外效果非代码根本问题 = 作者侧解决（如多 LUT 叠加观感） | — | ✅ 口径记录 | — |
| 本轮范围 | 2026-10-09 用户裁决：本轮只做**批次 A 关单**（A10 收尾 + A11 + A12 + A13'），批次 B 起另起 | — | ✅ 口径记录 | — |
| CLEANUP-3 | **注释收敛**（规范已定稿：`plans/0.3.6/code-comment-style.md`，2026-10-09 用户口径「注释=定义/生效的公正说明，Why 归 plans，稳定后必须简化」）：全仓 20,569 代码行 / 6,844 注释行（比 0.33）收到 ≤25%；按包分片 S1–S6 纯注释 diff、逻辑零动、三套冒烟兜底；默认 B–H 落地后统一执行，本版不再动的包可提前 | — | ☐ | |
| E0 裁决 | **`/icinematics template` 命令删除**（2026-10-10 用户拍板）：与「运行时只读只执行」一致；E 批删除任务解锁（script/template/ 10 文件 + 命令子树 + ScriptTemplate/script.new 链路 + 文档回写，须与编辑器骨架本地化同批） | — | ✅ 口径记录 |
| B1 拍板 | **覆盖链四项（2026-10-10 用户拍板）**：① Modifier 缺省合成 = 偏移类 ADD（position/yaw/pitch/roll）、缩放类 MULTIPLY（fov/zoom）、REPLACE 显式 ② Base 优先级 飞行 300 > 直控 200（用户说明：飞控与编辑器互斥、编辑器只接收回传值，无实际语义差异）③ Modifier 只作用主相机状态 ④ 通道掩码 6 位 | — | ✅ 口径记录 |
| B16 拍板 | **seek 后模型实例状态 = reset**（2026-10-10 用户拍板）；数学模型库包名 = `math` 包（低影响，主代理定） | — | ✅ 口径记录 |
| 核心思路（重申） | **2026-10-10 用户重申（设计不得脱离）**：模组运行时只做三件事——定义（机制/模型/字段语义）→ 按定义解析（ScriptParser/Validator）→ 按规则播放（几何解算/播放求值属播放，k 系数计算允许在运行时）；**编辑器 = 生成固定格式脚本**，一切优化/生成/一键操作（起始/结束点写法、像素→scale 换算、四象限一键生成等）归编辑器，产物与手写脚本等价；运行时禁止生成/调配运算与改写脚本数据 | — | ✅ 口径记录 |
| 版本策略 | **不保留旧脚本兼容（2026-10-10 用户口径）**：每次更新不带旧兼容层、不加迁移垫片；格式以当前版本为准，保持干净。任务书/验收不再要求「旧脚本零回归」；等值类任务只对「未改路径行为」做对拍。 | — | ✅ 口径记录 |
| 几何口径 | **统一几何模型（2026-10-10 用户口径，替代参考画布方案）**：① 取材 `source` 与缩放 `scale_x/scale_y` 的参照 = **素材自身**（取材 = 素材左上角 (0,0) 归一化起始/结束区域；缩放 = 相对素材原始尺寸的倍数，例：1600×900 ×(2, 0.5) = 3200×450）；② 放置位置的参照 = **窗口**（百分比）；③ **画布 = 窗口**（废 16:9 参考画布 + Fit 映射，variable-frame §4.1 待改写）；④ 分辨率转义 = 编辑基准分辨率 → 播放窗口的整体缩放（例：2560×1440 编辑、1920×1080 播放 → 处理后整体 ×1920/2560）；⑤ 脚本保持标准字段写法（x/y/w/h），起始/结束点等便捷写法归编辑器「一键优化」 | — | ✅ 口径记录（2026-10-10 收口：落点 = `meta.base_resolution`（缺省 1920×1080）；溢出 = 单一 contain 系数 `k = min(W播/W基, H播/H基)`；定稿 = variable-frame.md §4.2） |
| 几何补充 | **（2026-10-10 几何定稿随带，主代理裁定）**：① `fit` 字段废除——新模型元素框由素材派生（`Cs × scale × k`），形状由 `scale_x:scale_y` 表达、等比 = 写 `scale_x = scale_y`，`FitMode`/`map()` 一并删（`fit` 为 0.3.6 未发布字段、无存量负担）② 画幅层补左右黑边（`aspect_ratio` 窄于窗口时 pillarbox，遮幅语义对称；现状只画上下）③ `base_resolution` 校验上限 16384。代码改动清单 = variable-frame §11（拆两件串行：任务一 11.1–11.5 几何与图层 / 任务二 11.6–11.9 字段·解析·校验·文档） | — | ✅ 口径记录 |

> 同脚本重入 = 保持单实例（用户裁决：脚本=整体；后来者居上针对不同脚本）——无代码改动，仅口径记录（已入 pending-discussions §〇）。

## 编辑器阶段（最后做，此轮不执行）

> 前置：先出编辑器规划文档（参考 `example/editor/{olive,lossless-cut}`，逐功能对照模组能力；核心 = 只产出游戏可解析的固定格式脚本）。
> 清单：调色编辑器 UI（含 `editor/src/operations.ts` 的 `continuousChannels('ADJUST')` 同步 15 通道、`demo.ts` 补 ADJUST 轨）、组合器编辑器 UI、转场面板、模板面板、PresetsPanel 残留处置、合成参数专用面板 + 多 lane 预览、Gizmo（variable-frame 步骤 6）、脚本架构图拖拽布局持久化 + 增量刷新、Bezier 控制点可视化、选中片段全屏 lane 渲染、demo.ts 离线 schema 同步、**LUT 微调工具**（2026-10-09 用户裁决：捕获帧 → 编辑器预览 → 调输入域/分区 → 导出修正 .cube；外部工作流见 `docs/LUT_WORKFLOW.md`）、**几何编辑器四件**（2026-10-10，variable-frame §5/§11.9：一键优化（像素输入→`scale`、起始/结束点写法→`source`）、`meta.base_resolution` 表单、预览按 `k` 渲染、Gizmo 按新几何口径——产物均为标准字段脚本，与手写等价）。

---

## 工作环境速查（明天直接用）

- 版本号：`gradle.properties` = 0.3.6；`WebEditorApi.hello_ack` = 0.3.6。
- 编译：`sh gradlew compileJava`（Git Bash，`sh` 前缀，不 && 连命令）。
- 跑客户端冒烟：`ICINEMATICS_CAPTURE=1 sh gradlew :fabric:runClient --args='--quickPlaySingleplayer QuadrantTest'`（脚本 `quadrant.json` 已在 `fabric/run/immersive_cinematics/scripts/`，登录自动播放；Forge 运行目录同款已放）。
- **测试世界已固定白天**：`QuadrantTest` + 3 个 Forge 世界（`DayTime=6000`、`doDaylightCycle=false`）。
- 捕获产出（`fabric/run/lane-captures/`）：`<scriptId>_cam<N>-raw[-rK][-alpha].png`（每相机原始 RGBA）、`<scriptId>_cam<N>-composited[-rK][-alpha].png`（该相机合成后、按 dest 裁剪、保真 alpha）、`frame[-rK].png`（窗口终帧）。开关 = `ICINEMATICS_CAPTURE`。
- 无头 validator：`E:/tmp/icv`（命令与 classpath 见 `.planning/036-impl/state-brief-flow.md`）。
- 像素分析：`uv run --with pillow --with numpy python <脚本>`（系统 python 是 uv 管理）。
- 提交约定：`type(0.3.6): 中文标题`；验收后由主代理统一提交。
- 交付物脚本：`cinematics/release/quadrant.json`（四象限，单脚本四相机轨）、`cinematics/release/multifeature/`（多功能并行展示）。

## 进度日志

| 日期 | 事件 |
|---|---|
| 2026-10-07 | 总表建立；版本号升 0.3.6（`04b9380`）；A1（`d1ae22e`）、A8（`8114a85`）、A14（`d0fc80b`）、CLEANUP-1 捕获工具通用化 + 删旧驱动（`384bd84`）完成；四象限交付物实机验证（每格 RGB 关键帧调色 + opacity 0.5 用户确认）；测试世界固定白天。**下一步 = A2（完整 HSL 通道）**。 |
| 2026-10-08 | A2 完整 HSL（hue/lightness）完成（`d03626a`，GL 冒烟 79/79）；「未定项」清查报告入库（`e3fb024`，plans/0.3.6/pending-discussions.md）。**下一步 = A3（RGB 复合曲线，形态 b）**。 |
| 2026-10-08（续） | A3 复合曲线（`6f91150`）、CLEANUP-2 pip 层删除（`5b3265d`）、P1 状态追踪计划（`6c1097f`）、P2 光影调研计划（`2ff77b6`）、P3 触发器连续性计划（`8d6fc61`）、A4 每通道曲线（`45f03ed`）完成。H2（wait-point）依赖修正为 C2+P1-impl+P3-impl（事件/事实层先行）。**下一步 = A5（六条 hue 曲线）**。 |
| 2026-10-08（续2） | **调色数据形态修正（《调色数据形态修正_PLAN.md》任务 1）完成**（`b529757`）：lane 级调色改为**相机片段自带**（删 scope/lane 机制、复合曲线改名 `rgb_curve`、ColorAdjustSampler 共用采样、ADJUST 轨回归 master、四象限交付物改写为相机关键帧调色、文档同步）。验收：compileJava + validator 120 脚本仅 3 个既有 FAIL + icv2 数据冒烟 527 断言 + icgl GL 回归 8 套件 + 实机四象限像素分析（三台调色相机目标通道系数同表压至 0.08~0.18、非目标通道恒 1.00，Q4 对照无调色、opacity 0.5/裁切动画保持）。**下一步 = 任务 2（曲线贝塞尔手柄）→ 任务 3（文档口径修正）**。 |
| 2026-10-08（续3） | **B4 扩容：相机核心拆分与外部 API 方案定稿**（`camera-core-split.md`，用户裁决「核心是核心、外围围绕核心转」）：目标形态按架构图 SRC→CORE→DEST 分层；CameraManager 拆 6 类 + 薄门面、CameraTrackPlayer 拆 4 类、死代码 7 处清理、api 包与可见性收紧；功能不变（camera-state-plan §10 覆盖目标逐项回归）。 |
| 2026-10-08（续4） | **A10 LUT 全链推进**：master 调色挂点后移「原版后处理后、GUI 前」（`af25747`，与 Iris 解耦）；.cube 解析器（`804d9c7`，兼容达芬奇导出、94 项冒烟）；**A10c 管线接线完成**（`1157788`：master 级整体画面烘焙、tetrahedral 插值、栈位 LGG 后 HSL 前、icv2 601 + icgl 1521 断言全绿、演示脚本前后对比版）；LUT 演示脚本（`cc7b062`）。**编辑器残留清理**：清单（`c4d5604`）+ 74 个零引用纹理删除（`a3a3dc6`）。**剩余 = A10d 文档 + A10c 欠账 + 色彩空间校准（用户已知、后续）**；交接文档 `LUT管线交接_PLAN.md`。 |
| 2026-10-08（续4） | **任务 3（文档口径修正）完成**（无代码）：批次 A 表 **A8 行改述**为「lane 级调色 = 相机片段自带（CAMERA clip / 关键帧携带调色，作用于该相机轨产出的 lane，渲染完、合成前）」（commit 列标注 `8114a85` → `b529757`，原实现数据形态错误：自创 `scope=lane` + ADJUST 轨挂调色）；**A9 行结案**为「调整层 = ADJUST 轨（顶层、不参与排序、默认比其他层级高一个）作用于整幅画面；无新机制（2026-10-08 用户口径）」（文档口径结案，无代码）；**调整层 / 两级分层口径改写**（`screen-color-adjust.md`：lane 级 = 相机片段字段 → 调整层/master = ADJUST 轨；「调整层」即 ADJUST 轨的现有顶层位置，不参与排序、默认高一级，无新机制）。顺带口径记录：**任务 2 改判跳过**（曲线贝塞尔手柄归编辑器阶段，2026-10-08 用户裁决）、**任务 1 已完成**（`b529757`：lane 级调色改为相机片段自带）。 |
| 2026-10-09 | **批次 A 关单**：A10c 欠账清偿（`ed2a18e`：CubeLutLoader 缓存释放挂接播放生命周期 + F3+T 双端、静默点告警、validator 拦相机片段 lut_strength、尺寸策略注记）；LUT 输入域适配 `lut_input_gamma`（`76f0fe7`）；A10d 文档（`43ca082`）+ LUT 创作者工作流 `docs/LUT_WORKFLOW.md`（`f8a6265`）+ re 评估 5 处 + 2 处文档滞后回写（`1f60c9d`）；**A11 六色带 12 通道**（`01cae9f`）；**A12 混合模式**（`ff1e5a5`）；**A13' 多实例 master 叠加**（`4aa03d8`）。批次 A 全 ✅；用户裁决本轮只做批次 A，B 起另起。 |
| 2026-10-09（续） | **批次 A 展示脚本集交付**（`a253bec` + `6cf31c3`）：`cinematics/0.3.6/`（scripts/ 13 支自动播脚本入库、resource/ 6 个 cube 不追踪）——01~05 标量/曲线、06 LUT 60s 顺序 6 cube + gamma 纠偏、07~10 混合器/LGG/六色带（绿→黄）/混合模式、11 合成参数关键帧 + lane 级调色、12 多实例叠加双脚本；login 触发器错峰排队（delay=秒，`ScriptManager` ×1000→ms、`TriggerEngine` /50→ticks），一场 8m49s。实机观看验收通过（用户：没什么大问题）；启动窗口默认放大化 = options.txt `overrideWidth/Height` 2560×1392（fabric+forge run）。**欠账**：icv 校验器不查 `meta.name` ≤50 字符（运行时 `ScriptParser` 强制，10 号脚本曾因此拒载后修复）——校验规则待补。 |
| 2026-10-09（续2） | **多脚本同屏偶发黑屏调查完成（只调查未修）**：压力实验（13 脚本同时播）实证 1 次黑屏事件（世界层纯黑/GUI 完好，截图+日志存证 `E:/tmp/icblack/out/`）；三路排查收敛——主嫌疑 = **fail-open 链式 blit**（applyTo 无条件 return true、整屏 blit 无条件信任中转缓冲、全链 0 处 glGetError/checkFramebufferStatus）+ NaN 级联（validator 不在加载/播放路径）+ LUT 单元错位；时序/并发已排除。**用户裁决（修复验收标准）：渲染错误 fail-safe = 返回原始画面直通，绝不能输出 0/黑**。报告：`plans/0.3.6/multi-instance-black-screen.md`；诊断工具 `E:/tmp/icblack/`（黑屏检测器 + 压力脚本 + RUNBOOK，可复用回归）。 |
| 2026-10-09（续3） | **黑屏修复落地 + 结案**：四方向全修（`adfa008` fail-safe 直通 / `5bbc598` NaN 守卫 / `6deb2d7` LUT 绑定自检 / `c579f5f` lane 守门），负对照逐条证明四类机制可整屏黑、修复后正常路径逐字节不变。回归残余「黑」定性 = **极端叠加的合法数学结果**（13 套 master 连乘叠压算到全 0，同场可见全屏红/全屏绿佐证；用户裁定非 bug、作者侧解决，符合全模组调度口径）；gamma 奇异点假设被 shader 公式否决；拆锅实验取消。收尾：删交接文档、`plans/0.3.6/re/` 入 .gitignore 不提交、run 目录还原。**待讨论（先不动）**：F1「失败时返回前有效结果」语义去留。 |
| 2026-10-09（续4） | **明日开工材料就绪**：批次 B~H 开工计划定稿（`plans/0.3.6/next-phase-batches.md`：B 逐项 19 条 + C~H 汇总 + 遗留清点 + Q1–Q32 串行队列 + 明天首批 1 编码 + 5 并行）；注释规范定稿（`plans/0.3.6/code-comment-style.md`，实测基线 0.33 → 目标 ≤25%，CLEANUP-3 分片方案）；时钟抽象计划入库并排入 G4（`d419f93`）；F3/F4 新增类补交（`06b4d3d`，教训：`git add -u` 会漏新建文件，此后新文件显式 add）。F1 失败语义经讨论**保留**（与极端叠加无关、只拦真渲染故障）。 |
| 2026-10-10（续3） | **Q4 B4-P1 ∪ 时钟抽象步骤 1 ✅**——新建 `util/Clock`（@FunctionalInterface `double seconds()`）/`GameClock`（暂停冻结、末实例退出归零，`lastRealNanos=0` 技巧收进类内）/`PreviewClock`（float→double）/`camera/CameraStateHolder`（path/properties 写入缓冲 + snapshot + 四刷新点），`CameraManager` 999→970 行改持双时钟 + 委托读数（调用方零改动），`ScriptPlayer.clockSource` DoubleSupplier→Clock。等值证明：快照四刷新点逐一断言 + GameClock 200k 帧含 100300 冻结帧对拍 mismatch=0 + 预览 1h 累计误差 float 2.793s → double 1.7e-8s（改善）；实机 quadrant 前后捕获 + **同码对照轮**证明像素差异分布不可区分（来自运行间时间抖动非重构）。顺带修 H5-2（挂点 javadoc LaneRendererMixin→GameRendererMixin）与 B4-P0 残留（onScriptReplaced javadoc）。注：PlaybackClock 职责由 CameraManager 直持 GameClock+PreviewClock 承接（等价布局，§15 签名草案口径）。 |
| 2026-10-10（续2） | **B 轮串行队列推进（Q2/Q3 关单）**：**Q2 C4 动作面收敛 ✅ `476b442`**——删 3 个死代码 TriggerAction（StopPlayback/PlaySound/ExecuteCommand）+ TriggerStateStore/PlayerTriggerState 死读方法 4 对（负对照 = 7 符号删前全仓零调用者证据）；H4 结案标注（命令归 EVENT 轨，feedback-04 #1 划线结案 + README 同步）；顺带清 SuccessOnlySource javadoc 悬挂引用。**Q3 B9 ✅**——纯垂直线边界修复（两处调用点同口径：`horizontal < LINE_HORIZONTAL_EPSILON` → 拒绝 + 2 秒限频告警，回退 world；负对照 = HEAD/工作树守卫原文对拍，修复前纯垂直线产出方向无关恒定 yaw = 未定义）+ 「视线/身体朝向」措辞统一（yaw=身体朝向 yBodyRot、pitch=视线 xRot，SCRIPT_FORMAT/TrackSchemas/coordinate-frame 一致）+ coordinate-frame §6 三类线规则收口 + 新增 test_camera_vertical_line.json（icv [OK]）。 |
| 2026-10-10（续） | **几何工作包关单（统一几何模型全链落地）**：定稿 `6975da2`（variable-frame §4.2 取代参考画布 + camera-composition 对齐）；任务一 `501950c`（CanvasTransform 新几何 `resolutionEscape`/`element` + 四图层窗口百分比改造 + fit 消费废除 + 画幅层 pillarbox + OverlayTrackPlayer 删 stepFit；负对照 = 3600 组新旧公式 k=1 对拍 0 偏差 + §4.2.6 例 1 数字 2400×337.5 核对）；任务二（meta.base_resolution 全链：MetaSchemas/ScriptMeta/ScriptParser 告警回落/ScriptValidator 正整数≤16384 + fit 废除拦截/OverlayTrackPlayer 接线 + docs 五处回写；validator 八例 + fit 拦截负对照 + 接线回读 2560×1440）。**B 轮串行队列启动**：Q1（B4-P0）✅ → Q2（C4 动作面收敛）开工。 |
| 2026-10-10 | **今日首批 T1–T6 关单**（编排交接开局）：**T1 B4-P0 删死代码七处**（3 文件 −65/+4；负对照 = 删前 grep 全仓零调用者；主代理亲跑 compile + icv tests 3 既有 FAIL 不变 + release 5/5 + icv2/icgl 全绿）。**T2 B1-a 覆盖链定稿 ∪ B2 结案**（camera-state-plan〈覆盖链定稿〉§0–§15：§8 九条 + §9 逐条定、B2 = 已被 lane 快照模型取代结案、供 B4-P1/P2 接口形状与字段搬迁清单；**待拍板 4 项**＝§14）。**T3 B16-a 数学模型定稿**（math-models〈定稿〉1–12：接口/角度语义/实例粒度/dt/生命周期/回退/Registry+JSON/首批 10 类型/B16-c 接入面/零分配口径；**待拍板 2 项**＝定稿 12）。**T4 F3 Iris 调研 ①/② 源码级回写**（实机缺 Iris/光影包、未实测：光影合成必然早于 lane、同 RETURN 点顺序随 mixin 注册序非契约、每 lane 会重跑整条光影链 + 8 条缺陷/风险；§5 步骤 1/2 标 🟡，附缺件与实测清单）。**T5 E0 模板口径重审**（templates.md §0：模板语义运行时保留零、/icinematics template 命令倾向删、E1–E4 与 G5 全归编辑器、E 批运行时只剩删除任务（须与编辑器骨架本地化同批）；**待拍板 2 项**（命令去留阻塞删除任务））。**T6 H5 滞后清理 8 条 + G6 结案标注**。表状态回写：B2/B4-P0/G6/CLEANUP-2、E1–E4/G5 移入编辑器阶段。**新增欠账**：docs/modules/script.md:28 仍写 ScriptMeta 持有 camera_mob_*（H5 新增条目）；CameraTrackPlayer.onScriptReplaced javadoc 略过时（随 Q4/Q5 顺带，倾向按 code-comment-style §2.7 改）。**另**：用户新派「example 编辑器裁切/缩放语义调查」完成（CropScaleSurvey：Olive = Crop 节点四边 0~1 + Transform 节点 pos/scale/anchor、lossless-cut 无目标点模型；我们 CAMERA dest/source = 归一化矩形、覆盖层 = 画布七参数；缺口 = 相机侧 dest 屏幕口径 vs 画布口径不统一等，详见调查汇报）。 |
| 2026-10-10（续4） | **批次 B 编排开局（036-orch）**：注释规范补「引擎注释增量」§八（`1877e3d`，Godot/Unreal 引擎注释实践调研 → §8.1 引擎域必写定义清单 + §8.2 负面清单 + §8.3 任务书固定注释口径行）。**B3-a 每 lane 独立可见集合设计定稿 ✅**（multi-camera-rendering §12.9：状态拆分 15 项逐项「拆/不拆」、载体定选 b 字段级快照交换 + 5 步机制、CinematicOcclusion 替代关系与 B3-d 删除时点、停播恢复四步、内存预算公式 + 8 lane ≈ 6.3–14.7MB、风险 7 条；**视图中心按合成模型定稿 = 保持现状**——lane 是覆盖层不是替换层（camera-composition「画面 = 合成层输出」），曾误立「网格中心取谁」伪二选一，经用户指正按文档收口；留位② BFS 归槽由 B3-b 收敛）。批次 B 五路调查落档 `.planning/036-orch/`（MC 相机语义 / 剔除链 / Iris-Sodium / ReplayMod / 本仓挂点，共 113KB 原始报告）。 |
| 2026-10-10（续5） | **Q5 B4-P2 ✅**——CameraManager 拆分等值搬迁（`refactor` 提交）：新建 `PlaybackRegistry`（G1 实例表/查询/帧缓存）、`PreviewChannel`（G4 预览通道）、`PlaybackLedger`（G6 网络账本 + pauseTransition 检测）、`PlaybackLifecycle`（G2+G8 生命周期/队列）；`CameraManager` 970→362 行（门面 + 帧驱动，39 个 public 成员签名逐一等值）。验收：compile + icv tests 3 既有 FAIL 不变 + release 5/5 + icv2/icgl 全绿；**预览/接播路径实机 + 改动前后同场景日志对拍**（qs_a 不可打断二次请求 `result=2` 入队 → `NATURAL_END→FINISHED` 后无接播 = parallel-playback 事实核查②语义；qs_b 可打断替换接播 `INTERRUPTED→停→立即新实例` ×20 轮零漂移；quadrant 四相机轨多实例并存、结束 `smartCull 交回原版判定`；改动前后归一化序列逐项一致（elapsed 72.47/72.51 运行间抖动）；全程无异常）。**未验证**：预览通道（`editor.pushScript/play/pause/seek/stop`）需 F9 预览屏开着才能连 WS 驱动，本轮未驱动。教训入档：捕获跑图前先备份 `lane-captures/`（本轮曾覆盖基线）；实机验收严格按 §5 各行口径、不加戏。 |
| 2026-10-10（续6） | **Q6 B4-P3 ✅**——`camera/source/EntityTargetResolver` 等值搬迁（556 行新类；`CameraTrackPlayer` 1580→1094 行）：D 组选择器解析全套（resolveEntity 单入口 / 本地与服务端双路 / TargetLock·PointState·SelectorPolicy·SELECTOR_CALLPOINTS·MISS_RETRY_MS）+ targetLocks 状态整体搬迁，调用点改经新类，onStop/onScriptReplaced 清理同帧同序。等值证据：常量池字面量机械比对 0 缺 0 增 + 逐行实读核对（@p/@s 快路径、@e/uuid 本地解析、服务端节流、锁定切换、平滑数学逐字保留）。验收：compile + icv tests 3 既有 FAIL 不变（tests/trigger + camera follow 全 OK）+ release 5/5 + icv2/icgl 全绿。**接缝与登记**：新增 snapshotLockState/restoreLockState 两入口（captureLowerLanes 锁状态留底/回写，P4 随 LaneSnapshotCollector 归位）；SelectorPolicy 等放宽 public（模块内，P5 收口）；疑似缺陷 8 条只登记未修（含新发现：pitchBaseOf 读 yaw_base_selector、describeSelector 日志路径 Pattern.compile、混合时钟），B10-B15 与待办清单接手。 |
| 2026-10-10（续7） | **Q7 B4-P4 ✅**——`CameraTrackPlayer` 拆分第二步：新建 `CameraKeyframeEvaluator`（884 行，C 组六参数求值/插值/混合 + 片段选取 + 基准辅助，纯计算）、`LaneSnapshotCollector`（85 行，B 组 lane 捕获 + 经 snapshot/restore 两组锁状态留底）、`WorldPointLocator`（137 行，E 组结构/方块定位 + 两级缓存）；`CameraTrackPlayer` 1094→**140 行**（A 组 + onRenderFrame 编排，≤200 达标）；writeAttributes/renderMorph 的全局写入合并为 `writeGlobal` 唯一写入点。等值证据：747 条代码行多重集机械比对全对应（77 条残余全部归因已知变换）+ 缓存语义逐点保留（成功永久/失败 2 秒重试/不写缓存早退）。验收：compile + icv tests 3 既有 FAIL 不变 + release 5/5 + icv2/icgl 全绿 + **quadrant 实机像素回归**（备份纪律：pre-p4 参照留档；前后对照 Q1 峰值压 R ×0.042/×0.043、Q2 压 G ×0.040/×0.043、Q3 压 B ×0.040/×0.031、Q4 对照恒 ≈1、映射与 dest 几何一致；绝对均值差 = 运行间基础画面噪声 + pre 参照字幕污染，非回归）。 |
| 2026-10-10（续8） | **Q8 B14 ✅**——selector 解析缺陷补齐（`feat` 提交）：`EntityTargetResolver.requiresServerSelector` 形态分类扩展——`@a`/`@r`/`@p[…]`/`@a[…]`/`@r[…]`（含 `[team=…]`）进服务端原版解析；`@n` = 原版 1.20.1 不存在（EntitySelectorParser 仅 p/a/r/s/e 五分支），按「未知形式 warn 不崩」保留；@p/@s 快路径、@e/uuid 本地路径、warn 口径逐点不变。等值证据：谓词方法体独立编译 36 例全绿（补齐 10 例 + 既有 @e 6 例 + 不变面 20 例）。新增测试脚本 3 支（follow = @a / @r / @e[team=ic_red]，requires 接入既有序列；`_random` 因 id 34 字符超 32 上限返修改名 `_rand`）。验收：compile + icv tests 3 既有 FAIL 不变 + release 5/5 + icv2/icgl 全绿 + **实机解析抽验**（@a 锁定玩家、@e[team] 建队/召唤/入队后锁定铁傀儡 = team 匹配端到端生效、@r 同服务端路径、@n 持续 warn 且播放 12s 正常走完、全程零异常）。**登记**：`@s[…]`（带选项 @s）仍走本地 warn 分支——不在已知缺陷清单与本任务范围，留 B15 通用化扫面；`@p[team=…]` 路由由谓词证明、实机未单测（单人局无意义）。 |
| 2026-10-10（续9） | **Q9 B10 ✅**——selector 调用点隔离补全（`feat` 提交）：`SELECTOR_CALLPOINTS` 6→8（增 `yaw_base_from`/`yaw_base_to`；lineDir/isClipUsable 在 P4 后已传对应 role 字符串，仅名单补录，evaluator 零 diff）；`TrackSchemas` 声明 4 策略 × 8 调用点 = 32 字段（`<字段>_<调用点>` 命名统一，缺省 null = 回落通用字段）；`ScriptValidator` 同口径校验（通用字段一并纳入，全仓既有取值核对零回归）；`docs/SCRIPT_FORMAT.md` 调用点级策略覆盖表 + `AI_SCRIPTING_GUIDE.md` 同步。等值证据：回落链未改（不填 = 逐点不变）+ 子代理 5 组 validator 冒烟（基底/合法注入/未知后缀/非法值精确报错/通用字段）+ schema 冒烟（122 字段 32 新增 default=null）。验收：compile + icv tests 3 既有 FAIL 不变 + release 5/5 + icv2/icgl 全绿。**登记**：调用点名单在 EntityTargetResolver 与 TrackSchemas/ScriptValidator 两处重复（维护不变式注释标注，不抽公共常量以免 schema 依赖 camera 包）；`facing_origin`/`facing_target` 未在既有字段表单独登记（既有缺口，B15 文档扫面）。 |
| 2026-10-10（续10） | **Q10 B11 ✅**——selector 缓存键含调用点/锚点（`fix` 提交）：`ClientEntitySelectorCache` 键 = `Key(callpoint, anchorId, selector)` 单处定义（record），PENDING 同键分桶；`EntityTargetResolver` 在建锁时构键一次存 `TargetLock.cacheKey`（零每帧分配）、`resolveServerSelector` 逐帧只读；服务端零改动（C2S 仍传 selector 原文）。失效契约（javadoc 单处定义）：键三分量任一变化 = 旧键自然不命中；死亡/卸载/维度切换 = 时效兜底（resolvedAt + selector_refresh + 重试节流）；显式清理仅 clear()。等值证据：真实编译产物 Key 类 7/7 键语义实证（同三分量同键、异调用点/异锚点异键、HashMap 三键共存、旧键对照互串）。验收：compile + icv tests 3 既有 FAIL 不变 + release 5/5 + icv2/icgl 全绿 + **实机双锁抽验**（同一 selector len=37 服务 follow 与 look_at：各自等待→锁定同一铁傀儡、look_at 即时生效、零异常）。**登记**：同 selector 多调用点的服务端请求 1→N（隔离必然代价，MAX_PENDING 全局上限不变）；TargetLock 锁键暂不含锚点分量（锚点恒 "camera" 一一对应，B12 锚点可配置时决断锁键是否同扩）。 |
| 2026-10-10（续11） | **Q11 B12 ✅**——selector 锚点可配置（`feat` 提交）：`EntityTargetResolver` 锚点四值 `selector_anchor` = camera（缺省=现状）/player/target/origin，取值回落链 = `<字段>_<调用点>` → 通用 `selector_anchor` → camera，四值集外按 camera；`anchorPosition` 单处定义（camera 分支原样回传相机位置、player=玩家脚底、target=锁实体脚底、origin=点源坐标，不可解析一律回落相机位置）；点源解析窄化到五点源类型（坐标/世界/实体/方块/结构，消除递归锚点循环风险）+ `resolveEntity` 拆分公共入口（锚点已算）/私有重载（锚点给定），点源实体分支固定传 camera 防自递归。schema/校验/文档同口径（TrackSchemas 声明 ×8 调用点、ScriptValidator 四值集校验、SCRIPT_FORMAT/AI_SCRIPTING_GUIDE 字段表）。等值/实证：scoped javac 探针 18/18（四值 + 回落 + 点源全形态）、validator 8/8、语料 A/B 字节级一致；实机判别冒烟（暖机片段先送相机到 B 侧再 look_at，双脚本仅锚点不同）：camera 锚点锁 B 侧傀儡 target=(12.18,64.35,-144.71) ✓ 与设计一致，player 锚点解析到玩家脚边傀儡（客户端无实体可绑→按空片段兜底）——锚点确实决定服务端 sort 参考点。**随带发现（非 B12 缺陷，记限制）**：相机模式下 ChunkMap 实体配对/区块差集以相机为中心（ChunkMapTrackedEntityCameraMixin/ChunkMapCameraMixin 设计如此），锚点选出的、相机跟踪半径外的目标只能服务端解析、客户端绑不上——anchor=player/target 选远目标时 look_at 按「目标不可用」兜底，作者侧需知悉；跟踪范围并集属批次 B3/预加载域，不在本项动。 |
| 2026-10-10（续12） | **Q12 B13 ✅**——selector 多候选与择一策略（`feat` 提交）：`selector_pick` 三值 first（缺省=现状）/nearest/alive + `selector_pick_<调用点>` × 8，回落链 = `<字段>_<调用点>` → 通用 → first，三值集外按 first。择一点 = 服务端 UUID 循环（resolveServerSelector）+ 本地 @e 分支（resolveLocalSelector），锁定/切换逻辑零改动。语义定稿：first = 现状逐点收编（本地=距锚点最近的可用候选、服务端=回传序首个可用，「现状」原样写进文档）；nearest = 客户端可用候选内距锚点最近（B12 anchorPosition 为距离基准，平方距离比较）；alive = 返回序首个存活；服务端路径 first≡alive（可用==存活）属定义使然、文档写明。等值/实证：A/B 对拍 13 场景逐字节一致（REGRESSION byte-identical）；scoped javac 探针 21/21（回落链 9 + 本地 6 + 服务端 6）；validator 新规则差分（pick 非法值报错、旧语料 stdout 逐字节一致含 tests 3 既有 FAIL 不变）；实机判别冒烟（6 候选两簇 + sort=furthest 制造回序≠距离序）：first 与缺省锁定同一 uuid（远簇 target=(42.18,64.35,-144.71)）=「缺省=first=现状」同 uuid 级实证，nearest 锁近簇（target=(14.18,64.35,-142.71)）✓。 |
| 2026-10-10（续13） | **Q13 B15 ✅**——selector 通用化（`refactor` 提交）：新包 `selector/`（`EntitySelectorService` 通用机制全套：解析双路/目标锁切换/择一/锚点取位/平滑/捕获留底 + `SelectorSchema` 定义单源（8 调用点名/锚点四值/pick 三值/`<字段>_<调用点>` 模式）+ `SelectorPolicy`）；`camera/source/EntityTargetResolver` 783→214 行收为相机消费适配层（Keyframe/PositionData 读取 + 回落链 + 点源五形态），公开方法签名不变，Evaluator/Collector/CameraTrackPlayer 零改动；TrackSchemas/ScriptValidator 改引单源、删「改一处须同步另一处」注释（三处名单合一）。等值/实证：A/B 行为对拍 73 行 sha256 一致（13 场景 + pick 21 + 锚点 23 探针）；validator 五套语料 274 行 sha256 一致（tests 3 既有 FAIL 不变）；边界 grep 零命中（selector/ 不 import script./camera.）；icv2/icgl 28/28 全绿；实机双冒烟复跑与 B12/B13 记录逐位一致（择一同 uuid 双锁远簇 42.18,64.35,-144.71 + 近簇 14.18,64.35,-142.71；锚点单锁 12.18,64.35,-144.71）。 |
| 2026-10-10（续14） | **批次 C 设计立项（用户新增需求，设计归当日、实现归 C 批）**：两轮侦查穷举（播放关系 30 行语义空间 + 条件系统四轴设计空间）→ 用户四裁决：①播放关系 = 三态枚举 `meta.play_mode`（parallel/exclusive/interruptible）@脚本 meta ②条件系统 = **片段元数据**（clip.rules）、不新开轨道、复用触发器条件词汇（all_of/any）③等待点融合 = 一套词汇两种规则形态（`if→do` 持续 / `until→do` 阻塞挂起），判定点/等待点收编为特例 ④命名主代理提案「判定规则 / rules[{if\|until, do}]」。产出 `playback-relations.md`（含队列形同虚设/INTERRUPTED 不可达/全局 fadeOut 三个 bug 级发现与修复清单）+ `condition-system.md`（挂起语义/判定源/动作集/JSON 形态）；`wait-point-track.md` 标记被吸收；「脚本退出机制」预留登记 pending-discussions 〇-2。 |
| 2026-10-10（续15） | **退出模型定稿（用户口径，condition-system.md §8.1）**：退出 = 三族条件集——①默认·人为干预（强制退出/跳过/投票跳过，既有字段不动）②默认·**内容播完**（全部内容消费完毕：轨片段尽头 + 循环次数尽 + 在跑分支完成；按内容状态追踪、不按墙钟——时间误差不参与）③声明·条件退出（clip.rules 的 exit / 分支执行完退出 / until 等待点内退出，全走判定规则词汇）。`total_duration` 降级为统计/信息位兼可选兜底上限，**不再是退出触发**；分支比「脚本时长」长 → 播完（内容驱动）。ExitReason 五值不变（②③归 NATURAL_END）；macro_loop 无限圈 = 只能 ①/③ 退出（「循环+条件退出」= 播到 X 发生为止）。pending-discussions 〇-2-1 结清。 |

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
| A13 | 多实例 master 合并 → 按层级叠加（见「用户裁决新增」A13'） | A8 | ☐ | |
| A5 | 六条 hue 曲线（HvH/HvS/HvL、LvS/SvS/SvL） | A3 | ✅ | `a1408fe` |
| A6 | RGB 通道混合器（3×3 矩阵 9 参数） | A1 | ✅ | `0a74539` |
| A7 | Lift/Gamma/Gain 色轮（或 LOG 色轮） | A2 | ✅ | `c4f0901` |
| A9 | 调整层 = **ADJUST 轨**（顶层、不参与排序、默认比其他层级高一个）作用于**整幅画面**；**无新机制**（2026-10-08 用户口径） | A8 | ✅（文档口径修正结案，无代码） | — |
| A10 | LUT（Color Lookup + 强度） | A9 | ☐ | |
| A11 | PS 式六色带微调（Hue/Sat 分色带） | A5 | ☐ | |
| A12 | 混合模式作用于调整层（正片叠底/屏幕/柔光/叠加） | A9 | ☐ | |

## 批次 B：相机底层

| ID | 任务 | 依赖 | 状态 |
|---|---|---|---|
| B1 | Base/Modifier 覆盖链（优先级、通道掩码、REPLACE/ADD/MULTIPLY + 示例） | — | ☐ |
| B2 | 每 lane 独立 CameraPath/CameraProperties 核对（可能已被 lane 快照模型取代 → 结案回写文档） | — | ☐ |
| B3 | 每 lane 独立可见集合/遮挡剔除（长期解，消除 smartCull=false 缓解的代价） | B1 | ☐ |
| B4 | 外部 API（common api 包）+ 相机核心拆分与死代码清理（方案已定稿：`camera-core-split.md`，2026-10-08 用户扩容） | B1,B3 | ☐ | |
| B5 | 统一点源清单（yaw_base_from/to、facing_target 接受完整点源） | — | ☐ |
| B6 | look_at 方块来源 + 部位百分比微调 | — | ☐ |
| B7 | 偏移下放到点源 / 通道相对化 | B5 | ☐ |
| B8 | 方向锁（yaw/pitch 分轴锁值） | B7 | ☐ |
| B9 | 垂直线边界修复 + 「视线/身体朝向」措辞 | — | ☐ |
| B10 | selector 调用点隔离补全（yaw_base_from/to + schema 声明） | — | ☐ |
| B11 | selector 缓存键含调用点/锚点 | B10 | ☐ |
| B12 | selector 锚点可配置 | B10 | ☐ |
| B13 | selector 多候选与择一策略 | B10 | ☐ |
| B14 | selector 解析缺陷（@a/@r/@n/@p[team]） | — | ☐ |
| B15 | selector 通用化 | B10-B13 | ☐ |
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
| E1 | 嵌套引用（片段级 ← 轨道级 id） | — | ☐ |
| E2 | 轨道级模板 | E1 | ☐ |
| E3 | 脚本级模板（meta/触发器/requires 骨架） | E2 | ☐ |
| E4 | 纯数据/用户自定义模板 | E3 | ☐ |

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
| G4 | 时间精度（float → 高精度） | — | ☐ |
| G5 | 叠化 hold 自动补帧工具/模板 | — | ☐ |
| G6 | 关键帧结构升级（自带插值/手柄）——按方案 E 结案（文档标注） | — | ⊘ 待标注 |

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
| CLEANUP-2 | 删除运行时 pip 层（被 lane 取代；PipLayer 是占位实现从未接入真实画面）；WebUI 便捷操作留编辑器阶段 | — | ☐ |
| P1 | **计划文档《脚本状态机与状态追踪》**：布尔值点状事件判定 → **事实追踪**（用户点名；后续优化基于它） | — | ✅ `6c1097f`（plans/0.3.6/state-tracking.md） |
| P1-impl | 按 P1 计划实现状态机/状态追踪优化 | P1 | ☐ |
| P2 | **计划文档《Iris/Oculus 兼容调研计划》**：挂点/可行性/风险/工作量（用户点名：先写计划，结论定策略） | — | ✅ `2ff77b6`（plans/0.3.6/iris-oculus-compat.md） |
| P3 | **计划文档《触发器系统的连续性》**：现状 = 全采样但不连续（离散轮询）；目标 = 做成连续的（与状态追踪同类思路；用户点名，独立成篇） | — | ✅ `8d6fc61`（plans/0.3.6/trigger-continuity.md） |
| P3-impl | 按 P3 计划实现触发器连续性 | P3 | ☐ |
| B3' | 每 lane 独立可见集合 → **做**（用户裁决；工程大，拆多个子任务：设计 → 实现 → 性能实测） | — | ☐ |
| A13' | 多实例 master 合并 → **按层级叠加**（lane 包内/包上调整 → 合成 → master 整体调整；多实例 master 按实例顺序叠加应用） | A8 | ☐ | |
| 任务2改判 | **曲线贝塞尔手柄归编辑器阶段**（2026-10-08 用户裁决，替代「手柄本轮补」）：手柄属编辑器侧运算（运行时只读只执行、**不含手柄逻辑**），产物数据形态随编辑器阶段定稿；已标注 screen-color-adjust.md「本版本明确不做」 | — | ✅ 标注完成 | |
| 创作机制口径 | **运行时只读只执行；修改/生成/调配运算归编辑器**（2026-10-08 用户裁决，已入通用原则）：曲线手柄、模板等创作性机制不进运行时代码，编辑器运算产出运行时直接执行的纯数据——**E 批次（模板）等任务书开工前按此口径重审**（模板展开归编辑器） | — | ✅ 口径记录 | |

> 同脚本重入 = 保持单实例（用户裁决：脚本=整体；后来者居上针对不同脚本）——无代码改动，仅口径记录（已入 pending-discussions §〇）。

## 编辑器阶段（最后做，此轮不执行）

> 前置：先出编辑器规划文档（参考 `example/editor/{olive,lossless-cut}`，逐功能对照模组能力；核心 = 只产出游戏可解析的固定格式脚本）。
> 清单：调色编辑器 UI（含 `editor/src/operations.ts` 的 `continuousChannels('ADJUST')` 同步 15 通道、`demo.ts` 补 ADJUST 轨）、组合器编辑器 UI、转场面板、模板面板、PresetsPanel 残留处置、合成参数专用面板 + 多 lane 预览、Gizmo（variable-frame 步骤 6）、脚本架构图拖拽布局持久化 + 增量刷新、Bezier 控制点可视化、选中片段全屏 lane 渲染、demo.ts 离线 schema 同步。

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
| 2026-10-08（续4） | **任务 3（文档口径修正）完成**（无代码）：批次 A 表 **A8 行改述**为「lane 级调色 = 相机片段自带（CAMERA clip / 关键帧携带调色，作用于该相机轨产出的 lane，渲染完、合成前）」（commit 列标注 `8114a85` → `b529757`，原实现数据形态错误：自创 `scope=lane` + ADJUST 轨挂调色）；**A9 行结案**为「调整层 = ADJUST 轨（顶层、不参与排序、默认比其他层级高一个）作用于整幅画面；无新机制（2026-10-08 用户口径）」（文档口径结案，无代码）；**调整层 / 两级分层口径改写**（`screen-color-adjust.md`：lane 级 = 相机片段字段 → 调整层/master = ADJUST 轨；「调整层」即 ADJUST 轨的现有顶层位置，不参与排序、默认高一级，无新机制）。顺带口径记录：**任务 2 改判跳过**（曲线贝塞尔手柄归编辑器阶段，2026-10-08 用户裁决）、**任务 1 已完成**（`b529757`：lane 级调色改为相机片段自带）。 |

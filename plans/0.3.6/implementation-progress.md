# 0.3.6 全量实现进度（todo / 追踪文档）

> 生成：2026-10-07 晚。**明天从这里接着做。**
> 目标：**0.3.6 计划全部实现**（不允许"差一点"）；**编辑器（游戏外 WebUI）放到最后**（需严密规划：参考 `example/editor/{olive,lossless-cut}`，核心 = 只产出游戏可解析的固定格式脚本）。
> 执行方式：一个子代理只做一个小任务（任务书写全需求）→ 主代理验收（编译 + 冒烟/读码）→ 中文 commit → 更新本表。**并行口径（2026-10-08 用户明确）**：**计划类任务可并行**（各写各的文档、互不冲突）；**执行类任务必须串行**（改代码的多子代理易出错——一个完成并验收提交后才开下一个）。
> 依据：`plans/0.3.6/implementation-audit-2026-10-07.md`（缺口总清单）；`plans/0.3.6/pending-discussions.md`（未定项清查：多数属执行时再定型、任务落地时定稿；少数需拍板）；工作笔记 `.planning/036-impl/state-brief-{arch,flow}.md`（现状/工具）。

## 两条通用原则（每个任务都必须遵守）

- **数据驱动**：先下定义（机制/模型）→ 唯一解释器读它/调控它 → 参数全部来自脚本数据；**演示/效果不写死进代码**（四象限 = 一个脚本四相机轨，不是代码演示）。
- **调控顺序（画面完整性）**：画面处理（调色等）**只动 RGB、alpha 逐位直通**；透明度（alpha/opacity）一律在**画面合成完成之后**、在**合成层**调控；绝不把透明烤进画面、绝不先处理 alpha。
- **决策从文档推导（2026-10-08 用户指正）**：计划文档已有口径/倾向/落地步骤的，**直接照办一路顺下去**，不要重复问用户；只有文档确实未覆盖的（新需求、真二选一）才问。

---

## 批次 A：调色系统（screen-color-adjust）

| ID | 任务 | 依赖 | 状态 | commit |
|---|---|---|---|---|
| A1 | R/G/B 通道（master，-1~1 乘性、操作栈第 6 步） | — | ✅ | `d1ae22e` |
| A8 | lane 级调色（ADJUST 轨 scope/lane + 每 lane 合成前 pass + 单脚本多 lane 级绑定相机轨） | — | ✅ | `8114a85` |
| A14 | 四象限交付物重写：单脚本四相机轨（4 机位 + dest 象限 + 每格 RGB 关键帧 + BR opacity 0.5 + BR 裁切动画） | A1,A8 | ✅ | `d0fc80b` |
| A2 | 完整 HSL 通道（hue 色相旋转 ±180° + lightness 亮度） | — | ✅ | `d03626a` |
| A3 | RGB 复合曲线（形态 b：曲线点集 + curve_strength 关键帧，CPU 采样 LUT） | A2 | ✅ | `6f91150` |
| A4 | 每通道曲线（R/G/B 三条 + 各自 strength） | A3 | ✅ | `45f03ed` |
| A13 | 多实例 master 合并 → 按层级叠加（见「用户裁决新增」A13'） | A8 | ☐ | |
| A5 | 六条 hue 曲线（HvH/HvS/HvL、LvS/SvS/SvL） | A3 | ☐ | |
| A6 | RGB 通道混合器（3×3 矩阵 9 参数） | A1 | ☐ | |
| A7 | Lift/Gamma/Gain 色轮（或 LOG 色轮） | A2 | ☐ | |
| A9 | 调整层（作用于其下所有图层） | A8 | ☐ | |
| A10 | LUT（Color Lookup + 强度） | A9 | ☐ | |
| A11 | PS 式六色带微调（Hue/Sat 分色带） | A5 | ☐ | |
| A12 | 混合模式作用于调整层（正片叠底/屏幕/柔光/叠加） | A9 | ☐ | |

## 批次 B：相机底层

| ID | 任务 | 依赖 | 状态 |
|---|---|---|---|
| B1 | Base/Modifier 覆盖链（优先级、通道掩码、REPLACE/ADD/MULTIPLY + 示例） | — | ☐ |
| B2 | 每 lane 独立 CameraPath/CameraProperties 核对（可能已被 lane 快照模型取代 → 结案回写文档） | — | ☐ |
| B3 | 每 lane 独立可见集合/遮挡剔除（长期解，消除 smartCull=false 缓解的代价） | B1 | ☐ |
| B4 | 外部 API（common api 包） | B1,B3 | ☐ |
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
| A13' | 多实例 master 合并 → **按层级叠加**（lane 包内/包上调整 → 合成 → master 整体调整；多实例 master 按实例顺序叠加应用） | A8 | ☐ |

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
| 2026-10-08（续） | A3 复合曲线（`6f91150`）、CLEANUP-2 pip 层删除（`5b3265d`）、P1 状态追踪计划（`6c1097f`）、P2 光影调研计划（`2ff77b6`）、P3 触发器连续性计划（`8d6fc61`）、A4 每通道曲线（`45f03ed`）完成。H2（wait-point）依赖修正为 C2+P1-impl+P3-impl。**下一步 = A5（六条 hue 曲线）**。 |

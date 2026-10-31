# 0.3.6 计划索引（状态看板）

> 本目录归档 0.3.6 的计划、方向稿与反馈处理记录。
> 长期计划方向稿只确定方向、已确认项、可能的问题和待定项；接口、字段、公式、JSON、迁移步骤等执行时再查再定。

**通用原则（沿用全文）**：

- 文档约定：一个文档一个点；不写伪代码，写实现思路与每一步的交付物。
- 通用原则：先定义工具（底层），再考虑怎么调用、整合工具（应用层）——先底层后应用；模板、转场等应用层文档都遵循此分层。
- 效果原则：大部分效果写成**系统可追踪的数据**（轨道 → clip → 关键帧参数），不写死代码；机制性能力最小化。转场即例子：叠化 = 底层 A 保持不透明 + 上层 B opacity 0→1（hold 等值关键帧由作者 / 工具写入），黑场/白场 = OVERLAY 轨预配置 clip。参考项目：ReplayMod 路径系统（Timeline/Path/Keyframe 全数据化）、olive（节点参数全关键帧）。
- 时间轴原则：**允许 clip 时间重叠**（lane 模型红利，旧单相机时代不允许——一个时间点只能有一个相机状态）；重叠解析 = 先按轨道层级，再按轨道内 clip 顺序（后面的 clip 在上）。叠化 = **底 A 不透明 + 上 B opacity 0→1**（hold 等值关键帧由工具写入，运行时只执行）；不允许重叠会露原版画面。
- 版本原则：**不维护自己的过时东西**——每个版本自己的代码保持最干净；不写迁移 shim、不保留旧格式/旧写法兼容层、不留旧代码路径；设计变更直接替换旧语义并在文档标注（如"0.3.6 起"）。注意：**MC 多版本支持是要做的目标**（common 层架构为此服务），本条只管模组自己的代码与脚本格式，与跨 MC 版本适配不冲突。
- 文档状态原则（2026-10-24）：**正文只写现行实现与现行口径**，不写「计划做 X → 后来取消」的沿革。`不做 / 取消` 的决定只在 `implementation-progress.md` 留一行 `⊘ 不做`（`pending-discussions.md` §三 各一条），正文提到该能力时直接写现行行为（例：字幕完整呈现、不裁切；lane 视距跟随玩家设置）。理由：上下文被压缩成片段后，「先写要做、后写已取消」会被读成仍然要做。
- 比例原则：**一切按比例缩放，不用绝对像素**——位置/尺寸/缩放全是归一化比例；计算全程浮点，**最后一步才映射到屏幕像素**，中间不取整；窗口从 600×800 到 2K 构图一致。（**2026-10-10 起参照口径更新**：参考画布方案废除、`fit` 字段废除，画布 = 窗口，统一几何模型见 `D-transition/variable-frame.md` §4.2。）
- 画面完整性原则：**每个画面层（lane / 覆盖层）产出的是一张完整、不透明的画面**——渲染期间不允许出现透明像素（雾、背景等一律以不透明色呈现）；**透明度只作用于完整的画面上**（合成层的 opacity 参数），而不是渲染时把透明"烤"进画面本身。反面教材（已修复）：lane 清屏色 alpha=0 + 合成 discard → 原版画面从雾区天窗透出。
- 相机边界：作者操控的是模组内部相机状态与覆盖链；原版 `Camera` 是 lane 渲染适配器，不拿原版玩家相机的限制推断脚本能力。位置与朝向各自声明基准。
- 执行与组合：循环以执行次数和执行事实为准；局部秒数仍可定位关键帧／规则时间入口，不充当全局完成条件。无限表示无自然完成点，不自动截断作者后续内容；效果按轨道层级与实例执行顺序叠放，不由 JSON 字段排列顺序决定。
- 组合原则：能力默认可组合；冲突按实际通道与作用域处理，避免整组互斥。创作策略与执行机制分离，运行时不自动拆分片段或改写编排。片段提供局部时间坐标；采样属性负责参数值，规则与曲线在脚本级独立集合声明、通过片段引用定位内容，运行态按播放实例、绑定与执行隔离；规则判定成功、动作完成与片段完成是不同事实。

**远期讨论口径**：0.3.6 完成后才评估的议题归 `plans/future/`；活跃讨论全部归本目录。

**状态图例**：✅ 已完成　🔵 方向已确认、实现未做　🟡 框架讨论/待定　☐ 未开工

> **2026-10-07 事实核查**：当轮源码路径、核查范围与裁决保留为历史快照。当前评审以明确的目标契约和对应专篇为依据，区分已实现基线与未实现目标；文件修改时间和旧关单标记不能作为设计优劣或语义优先级的判断依据。

---

## 目录结构（按批次轮次）

```
plans/0.3.6/
├── README.md                本文档
├── implementation-progress.md  全量实现进度（真源）
├── pending-discussions.md      未决项清单
├── mod-architecture-diagram.md 架构图
├── C-trigger/         C 轮：触发器与判定规则
├── D-transition/      D 轮：转场/遮罩/覆盖层
├── F-playback/        F 轮：播放/渲染主线
├── G-script-model/    G 轮：script-model、时钟抽象、编辑器播放语义
├── H-other/           H 轮：区域同步/HUD 隐藏/实机验证/触发器状态层（H7/H8）
├── I-script-core/     I 轮：对象模型、脚本级规则/路径集合、规则控制与事实接力、次数执行（未实现）
├── J-media/           J 轮：媒体（运行时解码/播放、编辑器预览流、分片传输；未实现，可独立顺延）
├── Z-editor/          Z 轮：编辑器（最后一轮；固定格式脚本生成器，字段全量适配与打磨）
├── done/              主链路已完成（非 A/B 轮）
├── misc/              其余（规范/注释收敛）
├── _archive/          已结案过程文档、过时元文档、性能原型数据（quadrant-perf）
└── consolidation/     本版整理结论（只读）
```

---

## 一、已完成（归档至 `plans/complete/0.3.6/`）

### A 轮：调色系统 ✅ 已完成（A1–A14 全关单，2026-10-09）
- `screen-color-adjust.md` → `complete/0.3.6/A-color/`
- 落地：R/G/B 通道、完整 HSL、RGB 复合曲线、每通道曲线、六条 hue 曲线、通道混合器（9）、LGG 色轮（9）、LUT、六色带微调（12 通道）、混合模式（blend_mode）、lane 级调色、多实例 master 按层级叠加（A13'）。

### B 轮：相机底层 ✅ 已完成（B1–B19 全关单，2026-10-11）
- 8 篇 → `complete/0.3.6/B-camera/`：camera-state-plan（覆盖链）、coordinate-frame（坐标系）、selector-model（选择器）、math-models（数学库）、temporal-interpolation（时间插值）、transition（过渡）、hysteresis（迟滞）、camera-core-split（核心拆分+api 包）。

### 0.3.5 反馈 ✅ 已完成
- 6 篇 → `complete/0.3.6/feedback-0.3.5/`：01/02 随游戏内编辑器退役作废；03 exit_buffer 归一化已修；04 部分已调（轮询间隔可配归 C3）；05 on_enter 复位已修。

---

## 二、批次状态（本目录，按轮次）

| 轮次 | 目录 | 文档 | 状态 |
|---|---|---|---|
| C 触发器与判定 | `C-trigger/` | trigger-conditions（C1–C4）、playback-relations（C5）、condition-system（C6）、point-source-time-mode（C7） | ✅ C1–C7 + C-reg + C-pause 既有实现已关单；**C 轮不重开**。规则/曲线等新改造归 I 轮，见 [I-script-core/README](I-script-core/README.md)；H7/H8 仍是独立事实层／连续判定计划 |
| D 转场/遮罩 | `D-transition/` | scene-transition（D1–D3）、overlay-color-mask（D4–D7）、variable-frame（D8–D10）；执行记录见 `execution-round.md` / `color-mask-execution.md` | ✅ **D1–D8 / D10 已完成（2026-10-17），D 轮关单**；D9 不做（字幕不裁切）；D1–D3 纯显式 clip / 关键帧（C 最终方案），D2 lane rect/circle，D3 显式 z 序，见 `scene-transition.md` §3–§9 |
| F 播放/渲染 | `F-playback/`（索引：[README](F-playback/README.md)） | parallel-playback（F2）、multi-camera-rendering（F3–F5）、camera-composition、iris-oculus-compat（P2）、camera-center-entities（F6，相机中心实体可见性）、camera-pool（F7，相机租约池）、drop-preload（已落地）、cross-dimension-camera（F8，per-view 维度渲染） | ✅ **F 轮已关单（2026-10-26）**；F2/F3/F5 已完成；F4 不做（lane 视距自动跟随玩家）；drop-preload 已落地（2026-10-25）；失败处理第 1 批 P0 已落地、P1/P2 待做（不属 F 轮关单）；F6 C0–C3/C6 已实现并提交（`8354731`）；F7 已实现并提交（`38f3972`）；F8 P0+WP1–WP4 已实现并实机验证（`c0b1ac1`/`50aa5fb`，2026-10-26），已知限制为相机附近生物无 AI。实体持续更新方案见 [H10 entity-liveness](H-other/entity-liveness.md)（H 轮，未实现）。F3 验收、F5 矩阵、光影 A/B 见 [F-playback/README](F-playback/README.md)。 |
| G script-model | `G-script-model/` | script-model（G1–G6）、clock-abstraction（G4）、[G-math](G-script-model/G-math/README.md)（数学表达式/路径曲线 15 篇细化）、[editor-playback](G-script-model/editor-playback.md)（编辑器播放语义） | G6 ✅；G1/G2 由 I 轮吸收、未实现；G3 延后；G4 按时钟抽象计划推进，局部秒数与执行语义不互相替代；G5 归 Z 轮；G-math 现有三次 Bézier + 弧长 LUT，其余曲线/算子未实现；编辑器播放语义未实现 |
| H 其它 | `H-other/` | region-sync（H1）、screen-layers（画面层，H4–H6）、state-tracking（H7 = P1-impl）、trigger-continuity（H8 = P3-impl）、skip-vote-and-hud（H9）、entity-liveness（H10，相机视图实体活性） | H1 ☐；H2 ⊘ 已被 C6 判定规则吸收（等待点收编为 `until→do` 阻塞形态，wait-point-track 归档 `_archive/`）；H4 ✅；H5 ☐；H6 ☐；**H7/H8 ☐ 零实现**（2026-10-18 逐条核对）；**H9 🟡 有实现但不完整**（判定两处口径不一致 + HUD 两行分居）；**H10 ☐ 设计稿**（相机视图实体活性，F8 后续）；画面层强制隐藏实现已落地（仅 Forge）、统一层名与默认白名单未实现；详见 [`H-other/README.md`](H-other/README.md) |
| I 脚本底层 | [`I-script-core/`](I-script-core/README.md) | 对象模型／schema、脚本级规则/路径集合、规则控制与事实接力、规则命令信任边界、分支出口、实际状态过渡、次数执行、迁移（编辑器对象模型见 Z） | 🔵 未实现；规则与路径采用脚本级独立集合、通过片段引用定位内容；规则目标权威见 [`rule-system.md`](I-script-core/rule-system.md)：`>=` 时间门、实例级挂起、事实接力、成功≠动作完成、延迟动作、正常后继片段默认+入口显式覆盖、失败 fallback 独立沿原主线；规则命令信任边界见 [`rule-command-trust.md`](I-script-core/rule-command-trust.md)：客户端只发动作引用、服务端解析脚本命令、实例授权与权限上限；具体引用与序列化待结构评审 |
| J 媒体 | [`J-media/`](J-media/README.md) | 运行时视频解码/播放、音频解码、共享帧捕获、编辑器预览编码流、远程媒体分片传输、实时录屏导出；不改脚本字段 | 🔵 未实现；只依赖 OVERLAY `image` 字段与 F8 视图；三条契约 = 视频时间取片段局部时间、传输走分片、失败降级不崩 |
| Z 编辑器 | [`Z-editor/`](Z-editor/README.md) | 固定格式脚本生成器：脚本创建/编辑/预览、模板引擎、对象与路径编辑、转场与效果生成、调色与合成面板、几何工具、架构图、触发器编辑器 | 🟡 主体已实现（桌面程序、服务端、协议、握手校验、预览推流、飞控绑定、架构图步骤 1–4）；模板引擎与对象模型未实现；剩余工作排在所有轮次之后，等 0.3.6 字段定稿启动 |
| 主链路已完成 | `done/` | script-i18n | ✅ 既有实现记录保留；循环目标见 I 轮 [`loop-execution.md`](I-script-core/loop-execution.md)；编辑器文档见 [`Z-editor/README.md`](Z-editor/README.md) |

---

## 三、待定小项

- Bezier 曲线编辑器（控制点可视化，随编辑器线排期）。

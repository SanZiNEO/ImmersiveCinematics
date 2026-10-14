# 0.3.6 计划索引（状态看板）

> 本目录归档 0.3.6 的计划、方向稿与反馈处理记录。
> 长期计划方向稿只确定方向、已确认项、可能的问题和待定项；接口、字段、公式、JSON、迁移步骤等执行时再查再定。

**通用原则（沿用全文）**：

- 文档约定：一个文档一个点；不写伪代码，写实现思路与每一步的交付物。
- 通用原则：先定义工具（底层），再考虑怎么调用、整合工具（应用层）——先底层后应用；模板、转场等应用层文档都遵循此分层。
- 效果原则：大部分效果写成**系统可追踪的数据**（轨道 → clip → 关键帧参数），不写死代码；机制性能力最小化。转场即例子：叠化 = 末尾帧/首帧复制延长（hold 等值关键帧）+ opacity 关键帧交叉，黑场/白场 = OVERLAY 轨预配置 clip。参考项目：ReplayMod 路径系统（Timeline/Path/Keyframe 全数据化）、olive（节点参数全关键帧）。
- 时间轴原则：**允许 clip 时间重叠**（lane 模型红利，旧单相机时代不允许——一个时间点只能有一个相机状态）；重叠解析 = 先按轨道层级，再按轨道内 clip 顺序（后面的 clip 在上）。叠化 = **末尾帧/首帧复制延长（hold 等值关键帧，工具自动补充）** + opacity 关键帧；不允许重叠会露原版画面。
- 版本原则：**不维护自己的过时东西**——每个版本自己的代码保持最干净；不写迁移 shim、不保留旧格式/旧写法兼容层、不留旧代码路径；设计变更直接替换旧语义并在文档标注（如"0.3.6 起"）。注意：**MC 多版本支持是要做的目标**（common 层架构为此服务），本条只管模组自己的代码与脚本格式，与跨 MC 版本适配不冲突。
- 比例原则：**一切按比例缩放，不用绝对像素**——位置/尺寸/缩放全是归一化比例；计算全程浮点，**最后一步才映射到屏幕像素**，中间不取整；窗口从 600×800 到 2K 构图一致。（**2026-10-10 起参照口径更新**：参考画布方案废除、`fit` 字段废除，画布 = 窗口，统一几何模型见 `D-transition/variable-frame.md` §4.2。）
- 画面完整性原则：**每个画面层（lane / 覆盖层）产出的是一张完整、不透明的画面**——渲染期间不允许出现透明像素（雾、背景等一律以不透明色呈现）；**透明度只作用于完整的画面上**（合成层的 opacity 参数），而不是渲染时把透明"烤"进画面本身。反面教材（已修复）：lane 清屏色 alpha=0 + 合成 discard → 原版画面从雾区天窗透出。

**0.4.0 口径**：0.4.0 不再作为独立排期桶，活跃讨论全部归本目录；0.4.0 仅保留未搬迁的远期文档（跨维度运镜、运动模型、路径形状）。

**状态图例**：✅ 已完成　🔵 方向已确认、实现未做　🟡 框架讨论/待定　☐ 未开工

> **2026-10-07 事实核查**：本目录各指导性文档已完成一轮源码事实核查（MC/Forge 源码、`example/` 参考模组、仓库代码），每篇文档末尾新增「事实核查（2026-10-07）」小节，记录核实为真/已修正/补全/未验证四类结论与证据；`mod-architecture-diagram.md` 未改动。冲突按「最后修改时间晚者为准」裁决（裁决记录在各文档核查小节）。该小节为历史快照，路径以本目录现行结构为准。

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
├── E-template/        E 轮：模板
├── F-playback/        F 轮：播放/渲染主线
├── G-script-model/    G 轮：script-model（关键帧/时间精度）
├── H-other/           H 轮：区域同步/HUD 隐藏/实机验证
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

## 二、待执行批次（本目录，按轮次）

| 轮次 | 目录 | 文档 | 状态 |
|---|---|---|---|
| C 触发器与判定 | `C-trigger/` | trigger-conditions（C1–C4）、playback-relations（C5）、condition-system（C6）、point-source-time-mode（C7）、trigger-continuity（P3）、state-tracking（P1） | ✅ **已完成（C1–C7 + C-reg + ②族改按执行事实判定 + C-pause，2026-10-14 关单）**；文档仍留本目录——`trigger-continuity` / `state-tracking` 属 **P3-impl / P1-impl** 方向稿（未落地），随其实现后再归档 |
| D 转场/遮罩 | `D-transition/` | scene-transition（D1–D3）、overlay-color-mask（D4–D7）、variable-frame（D8–D10） | 全 ☐ |
| E 模板 | `E-template/` | templates | ⊘ 归编辑器阶段（E0 重审） |
| F 播放/渲染 | `F-playback/` | parallel-playback（F2）、multi-camera-rendering（F3–F5）、camera-composition、iris-oculus-compat（P2） | F2–F5 ☐ |
| G script-model | `G-script-model/` | script-model（G1–G6）、clock-abstraction（G4） | G6 ✅；G1–G4 ☐；G5 归编辑器 |
| H 其它 | `H-other/` | region-sync（H1）、hud-hard-hide（hard_hide_hud，H6 实机验证） | H1 ☐；H2 ⊘ 已被 C6 判定规则吸收（等待点收编为 `until→do` 阻塞形态，wait-point-track 归档 `_archive/`）；H4 ✅；H5 ☐；H6 ☐ |
| 主链路已完成 | `done/` | script-loop、script-i18n、editor-webui-migration、editor-script-graph | ✅（script-loop / script-i18n 已落地；编辑器两篇主体落地，剩余步骤归编辑器阶段） |

---

## 三、待定小项

- Bezier 曲线编辑器（控制点可视化，原 0.4.0 条目，随编辑器线排期）。

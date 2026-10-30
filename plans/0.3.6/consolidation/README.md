# 0.3.6 计划文档整理（只读分析，不修改任何现有文档/代码）

> 整理日期：2026-10-12  
> 触发背景：批次 A（调色）与批次 B（相机底层）已全部关单，`plans/0.3.6/` 下计划文档在「方向稿 + 事实核查 + 落地记录 + 定稿」多层叠加后出现大量已过时、跨篇重复、过程记录膨胀的问题。  
> 本文档只做**分析与方案**，**不修改任何现有文件**；所有结论与建议均存放于本目录 `consolidation/` 下，供后续决定是否执行。  
> 方法：5 个并行审阅代理逐篇 Read 全文 → 交叉核对 `implementation-progress.md`（进度唯一真源）与各文档落地标注 → 汇总为本目录下 5 份结果文件。

---

## 一、目录内容

| 文件                               | 内容                                        |
| -------------------------------- | ----------------------------------------- |
| `README.md`                      | 本总览（结论摘要 + 方法 + 关键事实基线）                   |
| `pending-items.md`               | **「待讨论」完整清单**（逐条 + 当前真实状态 + 归属批次）         |
| `outdated-redundant-analysis.md` | **过时/冗余/膨胀逐条判定 + 整理方案**（每一条：依据 → 选项 → 建议） |
| `lean-outline.md`                | **精简版文档大纲**（目标结构，与当前进度一致）                 |
| `decisions.md`                   | **待拍板项决策记录**（2026-10-12 用户拍板 + pillarbox 代码核实） |

> 状态更新（2026-10-12）：`re/`（7 篇 2026-10-09 评估）**已删除**；§〇 的 6 个待拍板点已全部由用户拍板（见 `decisions.md`）。
> 状态更新（2026-10-12·执行）：**目录重排已按本方案执行**——文档按批次归入 `C-trigger/`–`H-other/` / `done/` / `misc/` / `_archive/` 与 `plans/complete/0.3.6/`（A/B 轮 + 0.3.5 反馈），全树链接同步改写；「执行建议」1–4（pending-discussions 基线重建、过程文档归档合并、待定列表回写、事实核查剥离）**尚未执行**。
> 状态更新（2026-10-14）：**批次 C 已关单**（C1–C7 + C-reg + ②族改按执行事实判定 + C-pause；见 `implementation-progress.md` 批次 C 表 + 进度日志）。本目录 §二-3「C 实现 ☐」及 `pending-items.md` / `lean-outline.md` 中同类表述均为 **2026-10-12 快照**，已失效（本目录为只读快照，不再回改正文，仅此注记）。其余（D/F/G/H、P1-impl / P3-impl、CLEANUP-3）状态不变。

---

## 二、关键事实基线（判断依据）

1. **批次 A（调色）**：A1–A14 全部 ✅（含 LUT、六色带、混合模式、多实例 master 叠加 A13'）。
2. **批次 B（相机底层）**：B1–B19 全部 ✅（覆盖链、坐标系统一、选择器通用化、数学模型库、时间插值、过渡、迟滞、核心拆分+api 包）。
3. **批次 C**：C4 动作面收敛 ✅；C5/C6/C7 设计已定稿（`playback-relations.md` / `condition-system.md` / `point-source-time-mode.md`），实现 ☐；C1/C2/C3 ☐。
4. **批次 D/E/F/G/H**：D 全 ☐；E 归 Z 轮（E0 已重审）；F 全 ☐；G6 ✅（标注）、G1–G4 ☐、G5 归 Z 轮；H4 ✅、H1/H2/H5/H6 ☐。
5. **P1/P3** 计划文档 ✅，P1-impl / P3-impl ☐；CLEANUP-2 ✅，CLEANUP-3（注释收敛）☐。

> 由此产生的核心矛盾：`implementation-audit-2026-10-07.md`、`next-phase-batches.md` 两份「开工前」元文档的结论均早于 B 轮完成（`re/` 已删除），其中「math-models / hysteresis / camera-core-split 未实现」「过渡/时间插值/坐标系/选择器部分实现」等判定现已全部失效。



---

## 三、结论摘要（详见另三份文件）

1. **「待讨论」条目**：多数已随 A/B 轮销项；仍真正开放的是「批次 C/D/F/G/H 待办 + 少量长期项（编辑器呈现、光影 Iris/Oculus、性能实测）」，共约 20 类，详见 `pending-items.md`。需用户拍板的项已全部拍板（见 `decisions.md`）。
2. **过时/冗余/膨胀**：三份元文档（re/、audit、next-phase-batches）整体过时；4 篇「过程/调查记录」已完成使命可归档（render-routes、render-second-pass-cost、quadrant-prototype-handover/results、multi-instance-black-screen）；2 篇被取代可归档（wait-point-track 被 condition-system 吸收、editor-leftover-resources 清理已执行）；各篇「事实核查（2026-10-07）」小节普遍行号漂移、属可剥离的历史快照；`screen-color-adjust.md`（131KB）与 `multi-camera-rendering.md`（69KB）是膨胀最重的两篇。跨篇重复最严重的是「叠化 = 双 clip opacity 交叉 + hold 复制延长」「预热 = 重叠窗口」「性能 3.4–3.6ms」三组结论（各重复 ≥5 次）。
3. **精简大纲**：目标是把「方向稿 + 过程记录 + 定稿」三层压缩为「现行规格 + 未决清单 + 归档」两层，元文档从 6 份收敛为 2 份活文档，详见 `lean-outline.md`。

---

## 四、执行建议（不在此次范围内）

本次仅产出分析与方案，未动任何现有文件。若要落地整理，建议顺序：

1. 先重建 `pending-discussions.md` 基线（只留真正未决项）——它是后续所有整理的索引。
2. 归档/合并 6 篇已完成使命的过程文档。
3. 回写 2 处「待定列表未销项」的文档错位（selector-model / hysteresis 的 §5/§6）。
4. 各篇「事实核查」小节剥离为统一归档附录或就地折叠。
5. 最后按 `lean-outline.md` 重排目录。

> 任何实际删除/合并动作前，应先做一次 `git` 存档或移动而非直接删除，保留可回溯。

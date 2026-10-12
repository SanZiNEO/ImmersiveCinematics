# 过时 / 冗余 / 膨胀逐条判定与整理方案

> 每条按「**依据 → 选项 → 建议**」展开。原则：先给证据，再给至少两个可选项及取舍，最后给建议。
> 分类：**A. 整体过时的元文档**　**B. 已完成使命可归档的过程文档**　**C. 被取代/已清理可归档**　**D. 单篇内部的过时小节**　**E. 跨文档冗余**　**F. 篇幅膨胀**　**G. 状态/口径未同步**。

---

## A. 整体过时的元文档（结论早于 B 轮完成）

### A1. `re/`（7 篇评估报告，2026-10-09）
- **依据**：评估日 2026-10-09，B 轮 10-11 才关单。其总览表与分报告的「实现程度」列大量失效——hysteresis/math-models/camera-core-split 判「⬜ 未实现」、transition/temporal-interpolation/coordinate-frame/selector-model 判「◐ 部分实现」，现均已被 B 轮落地翻 ✅。且 progress 日志已记「`re/` 入 .gitignore 不提交」。
- **选项**：(a) 整体删除（已被进度日志 + 各文档落地标注取代）；(b) 保留作「10-09 时点快照」只读证据。
- **建议**：**(a) 整体归档/删除**。它是一次性评审产物，价值已被 B 轮实际执行覆盖；保留只会制造「两个真相」。若需追溯，git 历史仍在。
- **✅ 已执行（2026-10-12）**：`re/` 目录已删除。

### A2. `implementation-audit-2026-10-07.md`
- **依据**：§二「功能缺口清单」16–24 条（调色二三批、覆盖链、坐标系、选择器、数学模型、迟滞、过渡 3-8、时间插值 3-6、每 lane 可见集等）现已全部由 A/B 轮落地；仅触发器（C1-C3）、转场/遮罩（D）、编辑器步骤、region-sync/wait-point（H1/H2）仍为缺口。§三「文档滞后」12 条多数已由 H5 清理。
- **选项**：(a) 归档（审计使命完成）；(b) 改写为「剩余缺口（C–H 批次）」精简版。
- **建议**：**(a) 归档**。剩余缺口已由 `implementation-progress.md` 的 C–H 表 + `pending-items.md` 精确承载，无需保留一份过时的全量审计。

### A3. `next-phase-batches.md`
- **依据**：定位是「批次 B~H 开工计划（2026-10-09）」，其中 §一（B 批次 19 条逐条拆解）与 §4.1 串行队列 Q1–Q21（全部是 B 轮子任务）**已执行完毕**；仅 §二 C–H 清点、§三遗留（P1-impl/P3-impl）、§4.2 并行计划件里 F3/E0/H5 相关项仍有效。
- **选项**：(a) 归档整篇（执行记录）；(b) 保留 §二/§三，删除 §一/§4.1 的 B 轮内容，改题为「C–H 批次开工计划」。
- **建议**：**(b) 精简改写**。C–H 的清点表 + 依赖主链 + 冲突合并点（§4.4）仍是对后续批次最实用的排期依据，值得保留；B 轮逐条已执行部分剥离。

---

## B. 已完成使命、可归档的过程/调查文档

### B1. `render-routes.md`（33 行，临时草稿）
- **依据**：文件头自标「临时文档，稳定后并入 multi-camera-rendering.md」；唯一待定项（渲染顺序）已实测定稿（renderLevel RETURN）。
- **选项**：(a) 直接删除；(b) 内容并入 multi-camera-rendering §4 后删除。
- **建议**：**(b) 并入后删除**。渲染顺序结论一行即可并入 multi-camera-rendering §4「渲染顺序」已销项处。

### B2. `render-second-pass-cost.md`（337 行，渲染开销调查）
- **依据**：核心数字（+0.56ms/遍、线性）已被 `quadrant-perf/summary.md` 更权威实测取代；§6.2/6.3「模组本体无法直接测」已过时（后续原型已实测）；§1.1 自标「历史快照（lane 落地前）」。但 §2（Iris 对照）、§3（Sodium 状态分析）、§5（降耗手段）仍有长期参考价值。
- **选项**：(a) 整篇归档；(b) 抽 §2/§3/§5 并入 multi-camera-rendering §6/§7 后归档。
- **建议**：**(b) 抽精髓并入后归档**。

### B3. `quadrant-prototype-handover.md`（88 行，交接记录）
- **依据**：状态自标「已完成、验收通过」，疑点均已在 results 中解释；§4「死循环处理」已被 results §3.4 证伪（不需绕过），属过时内容；原型已并入 main 并正式化为 LaneRenderer。
- **选项**：(a) 整篇归档；(b) 保留作历史。
- **建议**：**(a) 归档**，无并入必要。

### B4. `quadrant-prototype-results.md`（158 行，原型结论）
- **依据**：原型已删、正式化为 LaneRenderer；但 §3「五个坑」是渲染底层的长期教训，被 render-routes / multi-camera-rendering 反复引用。
- **选项**：(a) 整篇归档；(b) 抽 §3 五个坑并入 multi-camera-rendering §12 后归档。
- **建议**：**(b) 抽 §3 并入 multi-camera-rendering 后归档**（与 B2 合并为一次「渲染底层经验」整合动作）。

### B5. `multi-instance-black-screen.md`（69 行，黑屏结案记录）
- **依据**：§七已明确四方向全修入库、结案；唯一残留 F1 语义已指向进度日志。⚠ 它被 `code-comment-style.md` 多处作为出处链接（fail-safe 直通、LUT 绑定背景）。
- **选项**：(a) 归档，并同步改 code-comment-style 的引用路径；(b) 保留原地只读。
- **建议**：**(a) 归档 + 改引用**。若移动/删除需保留 F1 指针。

### B6. `editor-leftover-resources.md`（254 行，资源清理记录）
- **依据**：74 文件清理已执行、目录状态与文档一致；全篇为一次性清点+删除记录。§2.3「保留清单」（icon.png/skip_key.png/6 shader/lang）是唯一仍有用的现状说明。
- **选项**：(a) 归档；(b) 归档并抽 §2.3 为一行「当前资源保留清单」。
- **建议**：**(b)**，§2.3 保留清单可并入 mod-architecture-diagram 或单独一行。

---

## C. 被取代 / 已清理、可归档

### C1. `wait-point-track.md`（305 行）
- **依据**：头部自标「已被 condition-system.md 吸收，正文保留作历史框架参考」；方向（新开 WAIT_POINT 轨道）已被「判定规则系统（clip.rules 阻塞规则）」取代。事实核查 67 行（死亡/重生、时钟/音频现状）为 MC 源码记录。
- **选项**：(a) 整体归档（仅保留头部指针到 condition-system）；(b) 删除正文、留一个指针文件。
- **建议**：**(a) 归档，仅留头部指针**（H2 实现时直接查 condition-system.md）。

### C2. `overlay-color-mask.md` 的「调色/滤镜」部分
- **依据**：§2.2 已把颜色调色/滤镜移交 screen-color-adjust（已标注）；但「混合模式」存在两处语义不清：本文 §4.2 的「遮罩层混合模式」 vs screen-color-adjust 已落地的「调整层 blend_mode（ADJUST 轨）」。两者是否同物未明确。
- **选项**：(a) 本文收窄为「纯色/渐变/局部遮罩」并明写「混合模式一律走 screen-color-adjust 的 blend_mode 枚举」；(b) 保留独立混合模式。
- **建议**：**(a)**——在 screen-color-adjust 里明确 blend_mode 是否覆盖遮罩层，若覆盖则 overlay-color-mask 删去混合模式条目。

---

## D. 单篇内部的过时小节（统一动作：剥离/折叠「事实核查」与「已落地过程记录」）

### D1. 各篇「事实核查（2026-10-07）」小节
- **依据**：几乎每篇都有，内容为 2026-10-07 的代码行号级证据，多处自标「行号会漂移」「已过时」「描述删除前状态」。行号在 B 轮大重构（CameraManager 1016→150 行、CameraTrackPlayer 1570→180 行）后已全部失效。
- **选项**：(a) 逐篇删除事实核查节；(b) 集中归档到单一 `_archive/fact-check-2026-10-07.md`；(c) 就地折叠为折叠块。
- **建议**：**(b) 集中归档**——事实核查有历史价值（记录了当时的代码状态），但不应占用每篇正文；抽到一份归档文件，正文只留「已核实」结论行。

### D2. `camera-state-plan.md` §4（staged 系统删除记录）
- **依据**：staged 体系已整体删除，§4 为纯历史；新旧口径并存（§3 旧口径「staged 未删」已被 §4 落地记录覆盖）。
- **选项**：(a) 删 §4 + 收敛 §3；(b) 保留作「0.3.6 起 staged 删除」一句话注记。
- **建议**：**(a)**，正文只留一行「0.3.6 起 staged 已删除，见 git」。

### D3. `variable-frame.md` §4.1（参考画布定稿）+ 事实核查⑤⑥
- **依据**：§4.1 自标「已被取代，保留存档，不要按本节实现」；§4.2 统一几何模型是现行口径；事实核查⑤⑥记录的是旧口径落地。
- **选项**：(a) 删 §4.1 + ⑤⑥；(b) 折叠。
- **建议**：**(a)**，只留 §4.2 + §11 改动清单（后者是待执行项，非历史）。

### D4. `multi-camera-rendering.md` §5（脚本/数据模型空壳）+ §12.7（原型处置）
- **依据**：§5 仅剩 4 行「已被取代」；§12.7 记录 QuadrantProto 删除历史；§12.8 开头自注「本节只作历史记录」。
- **选项**：(a) 删 §5/§12.7/§12.8 历史部分；(b) 保留。
- **建议**：**(a)**，与 B2/B4 的「渲染经验整合」一并做。

### D5. `templates.md` 的新旧口径叠加
- **依据**：全文大量「E0 后」回写叠加在旧「运行时」口径上（§1 落地记录=运行时越界实现、§6 已改归属编辑器），新旧并存、阅读成本高。
- **选项**：(a) 重写为「模板体系 = 编辑器阶段规格 + 待删运行时清单」两段；(b) 保留。
- **建议**：**(a) 改写**，明确「运行时已实现部分待删（随编辑器骨架本地化同批）」。

---

## E. 跨文档冗余（合并为单一权威定义，其余改引用）

### E1. 「叠化 = 双 clip opacity 交叉 + hold 复制延长」+「预热 = 重叠窗口」
- **依据**：在 camera-composition §1/§4/§7、multi-camera-rendering §3.3、script-loop §4、script-model §1.1、scene-transition §3.2 五处重复 ≥5 次。
- **选项**：(a) 只在 camera-composition §4（或 scene-transition §3.2）留权威定义，其余改「见 X」；(b) 维持多处。
- **建议**：**(a)**，权威定义落 camera-composition（它是「画面合成」总纲）。

### E2. 「性能 3.4–3.6ms / 主画面 2–3ms / 线性 / 显存不随 lane 增长」
- **依据**：在 parallel-playback §1、camera-composition §1/§5、multi-camera-rendering §4/§6/事实核查、quadrant-perf/summary 多处重复，且曾发生「≈1.2–1.4×」比值勘误，至今 quadrant-perf/summary 的更正与 multi-camera-rendering 事实核查②存在未闭环。
- **选项**：(a) 只留 quadrant-perf/summary 一处数据源，其余引用；(b) 各篇各自保留。
- **建议**：**(a)**，并顺手闭环 summary 比值更正。

### E3. 「主相机替换链已退役」
- **依据**：parallel-playback §3.3、camera-composition §7、multi-camera-rendering 多处重复。
- **建议**：只留 parallel-playback（或 mod-architecture-diagram 图注）一处。

### E4. camera-state-plan §15 / §13 vs camera-core-split §2.1 / §五
- **依据**：PlaybackClock/CameraStateHolder 字段搬迁、B2 结案结论两处跨文档重复。
- **建议**：B2 结案只留 camera-state-plan §13（camera-core-split §五 已标注「见 camera-state-plan §13」）；字段搬迁细节只留 camera-core-split §2.1。

### E5. 渲染调查三件套 ↔ multi-camera-rendering
- **依据**：render-second-pass-cost / render-routes / quadrant-prototype-results 与 multi-camera-rendering §4/§6/§12 内容重叠。
- **建议**：见 B1/B2/B4，一并整合。

---

## F. 篇幅膨胀（抽「实施日志」为独立归档，正文只留规格）

### F1. `screen-color-adjust.md`（131KB / 716 行）——最重
- **依据**：17 段「增量」+ 两段验证占全文 2/3（479 行），每段重复「字段表 + 公式 + 7~9 处同步点 + 测试脚本 + 冒烟数字（31/33/34/67/92/99…项）」，结构高度重复；正文 §4/§6「方向/待定」与文首定稿「已定」双写割裂。
- **选项**：(a) 抽 17 段增量为 `_archive/screen-color-adjust-实施日志.md`，正文只留「47 标量+10 曲线字段总表 + 操作栈 20 步 + 数据落点/挂点结论 + 未做项」；(b) 整篇保留。
- **建议**：**(a)**。这是全目录精简收益最大的一处。

### F2. `multi-camera-rendering.md`（69KB / 579 行）
- **依据**：§12 落地记录 290 行与 §1–11 方向稿双轨并存；§12.9（B3 设计定稿 ~155 行）几乎可独立成文。
- **选项**：(a) §1–11 方向稿收敛为「现行规格」，§12 保留为「实现文档」，§12.9 拆出独立或折叠；(b) 维持。
- **建议**：**(a)**，与 D4/E5 整合一次完成。

### F3. `camera-state-plan.md`（66KB）与 `implementation-progress.md`（88KB）
- **依据**：camera-state-plan 的「覆盖链定稿」314 行 + 事实核查 45 行；progress 进度日志 237 行里 A/B 轮逐条验收记录已属历史。
- **选项**：(a) progress 把 A/B 轮归档为「已关单」摘要（几行），日志只保留 C 轮起 + 用户裁决口径；(b) 保留全量日志。
- **建议**：**(a)**——进度文档的价值在「剩余待办」，不在「已完成的逐条验收」。

### F4. `variable-frame.md`（64KB / 576 行）
- **依据**：事实核查⑤⑥⑦ + §4.1 记录已取代口径，占相当篇幅。
- **建议**：见 D3。

---

## G. 状态/口径未同步（就地回写，非删除）

### G1. `selector-model.md` §5/§6/§7 与 `hysteresis.md` §6/§7 未就地销项
- **依据**：这两篇的「可能的问题/待定」条目**完全未就地标注「已定」**，答案只写在 §4.6 落地形态 / 「定稿与落地记录」里；读者只看 §5/§6 会误以为未定。（对照：math-models.md 是范本，§5/§6 逐条已就地销项。）
- **选项**：(a) 逐条补「已定，见 §4.6 / 定稿」箭头（与其它文档体例一致）；(b) 把 §4.6 / 定稿上移为「定稿」节。
- **建议**：**(a)**，低成本、消除误导。

### G2. 待定列表与正文结论不同步（parallel-playback §6、script-model §7、camera-composition §6、clock-abstraction §6-1）
- **依据**：这些「待定」列表里含「已就地结案但列表未回写/未删除」的条目（如 parallel-playback §6 仍列「实例 id 协议」已落地、clock-abstraction §6-1 已按 util/ 落地）。
- **建议**：随 G1 一并回写。

### G3. `README.md` 索引状态滞后
- **依据**：editor-script-graph 仍标 🔵（自身步骤 1-4 已 ✅）；§七「待定小项 Bezier 曲线编辑器」仍列（已归编辑器阶段）。
- **建议**：README 重写为精简看板（见 lean-outline）。

### G4. `mod-architecture-diagram.md` WAIT_POINT 节点
- **依据**：图中 WAIT_POINT 轨道节点与 condition-system 已定「不新开轨道、等待点=阻塞规则」冲突。
- **建议**：改图为判定规则阻塞语义（`until`）。

### G5. `docs/` 下已删字段（feedback-02 §六）
- **依据**：`docs/SCRIPT_FORMAT.md:59-63`、`docs/AI_SCRIPTING_GUIDE.md:125`、`docs/modules/editor.md`、`docs/modules/script.md` 仍写已删的 `preload`/`camera_mob_*`。
- **建议**：随 H5 文档滞后清理一并回写（这是唯一不随编辑器退役作废的活待办）。

---

## 汇总表（按动作归类）

| 动作 | 文档 |
|---|---|
| **归档/删除（使命完成或过时）** | re/（7 篇）、implementation-audit、render-routes、render-second-pass-cost、quadrant-prototype-handover、quadrant-prototype-results、multi-instance-black-screen、editor-leftover-resources、wait-point-track |
| **精简改写（剥过程留规格）** | next-phase-batches、screen-color-adjust、multi-camera-rendering、camera-state-plan、variable-frame、templates、implementation-progress、README |
| **就地回写（销项/状态/口径）** | selector-model、hysteresis、parallel-playback、script-model、camera-composition、clock-abstraction、mod-architecture-diagram、docs/ 四件、feedback-02 §六 |
| **保留不动（仍活跃）** | script-i18n、state-tracking、trigger-continuity、iris-oculus-compat、condition-system、playback-relations、point-source-time-mode、code-comment-style、trigger-conditions、region-sync、hud-hard-hide、overlay-color-mask、editor-webui-migration、editor-script-graph、script-loop、scene-transition（待定项保留、过程记录剥离） |

> 注：以上「归档/删除」均为**建议**，本次未执行任何删除。实际执行时应先 `git` 存档或移动至 `_archive/` 而非直接删除。

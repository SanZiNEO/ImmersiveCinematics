# 0.3.6 精简版文档大纲（目标结构）

> 设计目标：把当前「方向稿 + 事实核查 + 落地记录 + 定稿」多层叠加的结构，压缩为「**现行规格 + 未决清单 + 归档**」两层，与 A/B 轮已完成的现状对齐。
> 原则：
> 1. 一个主题一份权威文档；已完成的实现细节剥离为归档/实施日志，正文只留「现行口径 + 未决项」。
> 2. 元文档从 6 份收敛为 2 份活文档（索引 + 进度），其余归档。
> 3. 过程/调查/反馈/原型记录统一进 `_archive/` 只读区。

```
plans/0.3.6/
├── README.md                       # ① 索引看板（精简重写，见下）
├── progress.md                     # ② 进度（由 implementation-progress 精简而来）
├── pending-items.md                # ③ 未决清单（由 pending-discussions 重建基线）
│
├── playback/                       # 播放与画面架构（现行规格）
│   ├── parallel-playback.md
│   ├── camera-composition.md       # 权威：四参数合成 + 叠化/预热定义（E1 收敛点）
│   ├── multi-camera-rendering.md   # 权威：渲染底层实现 + 难点 + 性能（吸收 render-routes/
│   │                               #       render-second-pass-cost §2/3/5 + quadrant 五坑）
│   ├── script-loop.md
│   ├── variable-frame.md           # 只留 §4.2 统一几何模型 + §11 改动清单（删 §4.1）
│   ├── script-model.md
│   └── scene-transition.md
│
├── editor/
│   ├── editor-webui-migration.md   # 只留 §2 通信协议现状 + 未决项；退役过程归档
│   ├── editor-script-graph.md
│   └── templates.md                # 改写为「Z 轮规格 + 待删运行时清单」
│
├── trigger-runtime/                # 触发器与运行时表现
│   ├── trigger-conditions.md
│   ├── region-sync.md
│   ├── condition-system.md         # 吸收 wait-point-track（等待点=阻塞规则）
│   ├── playback-relations.md
│   ├── point-source-time-mode.md
│   ├── state-tracking.md           # P1
│   ├── trigger-continuity.md       # P3
│   ├── hud-hard-hide.md
│   ├── overlay-color-mask.md       # 收窄：纯色/渐变/局部遮罩；混合模式指向 screen-color-adjust
│   └── screen-color-adjust.md      # 只留字段总表 + 操作栈 + 挂点结论；17 段增量抽走
│
├── camera-core/                    # 相机底层（B 轮已落地 → 现行规格 + 遗留）
│   ├── camera-state-plan.md        # 只留 §2/§5/定稿/§10 覆盖目标 + 遗留；删 §4 staged
│   ├── coordinate-frame.md         # 已定结论 + 未结 7 条
│   ├── selector-model.md           # 保留 §4.6 落地形态；补 §5/§6 销项
│   ├── math-models.md              # 范本，保留定稿
│   ├── temporal-interpolation.md   # 只留 §7 裁定
│   ├── transition.md               # 保留落地记录 + 迁移门槛
│   ├── hysteresis.md               # 保留定稿；补 §6/§7 销项
│   └── camera-core-split.md        # 保留 P5 落地证据（可并入 camera-state-plan）
│
├── misc/                           # 其余（规范/调研/方案）
│   ├── script-i18n.md
│   ├── clock-abstraction.md        # G4 方案（§6-1 回改「已定」）
│   ├── iris-oculus-compat.md       # P2
│   ├── code-comment-style.md       # CLEANUP-3
│   └── mod-architecture-diagram.md # 更新 WAIT_POINT → 判定规则阻塞语义
│
├── _archive/                       # 只读归档（历史/过程/证据）
│   ├── fact-check-2026-10-07.md    # 各篇「事实核查」小节集中归档
│   ├── implementation-audit-2026-10-07.md
│   ├── re-2026-10-09/              # 原 re/ 7 篇
│   ├── next-phase-batches.md       # 原 B~H 开工计划（B 轮已执行）
│   ├── render-routes.md            # 已并入 multi-camera-rendering
│   ├── render-second-pass-cost.md  # 精髓已并入 multi-camera-rendering
│   ├── quadrant-prototype-handover.md
│   ├── quadrant-prototype-results.md  # 五坑已并入 multi-camera-rendering
│   ├── multi-instance-black-screen.md # 结案记录（F1 指针保留）
│   ├── editor-leftover-resources.md   # 清理记录（§2.3 保留清单抽出）
│   ├── wait-point-track.md            # 已被 condition-system 吸收
│   └── feedback-0.3.5/                # 反馈处理（02 §六 活待办先迁出）
│
└── quadrant-perf/                  # 只读性能证据（CSV + 结论 + curves.html），保留原地
```

---

## 三个精简重写件的目标形态

### 1. `README.md`（索引看板）
- 只保留：文档地图（上树）+ 每文档一行现状（✅/🔵/🟡/归档）+ 图例 + 「待用户拍板」5 条。
- 删除：历史标记说明（🔒）、2026-10-07 事实核查说明、七篇统摄 roadmap 注（已被 camera-core-split 解决）。
- 状态列以 `pending-items.md` + `progress.md` 为准，消除 G3 滞后。

### 2. `progress.md`（进度）
- 结构：① 已关单（A 调色 / B 相机底层）各一行摘要（含「验收齐全、无遗留」）；② 待办批次 C/D/F/G/H + P1-impl/P3-impl + CLEANUP-3（沿用现有表格，去掉已 ✅ 行）；③ 用户裁决口径（保留「运行时只读只执行」「版本策略」「几何口径」等仍在生效的）。
- 删除：A/B 轮逐条验收的进度日志明细（237 行 → 摘要几行 + 关键 commit 号）。

### 3. `pending-items.md`（未决清单）
- 由本目录 `pending-items.md` 直接迁移（已重建基线：只留真正未决项；拍板项见 `decisions.md`）。
- 删除原 `pending-discussions.md` 的「〇-2 用户预留」历史快照与大量已销项条目。

---

## 与当前状态的一致性检查

| 现状 | 大纲处理 |
|---|---|
| A/B 轮完成 | camera-core/ 组 + screen-color-adjust 转「现行规格」，剥离落地过程；progress 归档已关单 |
| C 设计定稿、实现未做 | condition-system / playback-relations / point-source-time-mode 保留为「规格」（§7/§9 待拍板项已给本篇答案） |
| D/F/G/H 未做 | 各主题文档保留「待定项」章节，指向 progress 待办 |
| P1/P3 方向稿 | state-tracking / trigger-continuity 保留为活跃方向稿 |
| 过程/调查/反馈/原型已结案 | 统一进 `_archive/` 只读区 |
| 跨文档重复（叠化/预热/性能/退役） | 收敛到单一权威定义（camera-composition / multi-camera-rendering / quadrant-perf） |

---

## 落地顺序建议（若决定执行）

1. 建 `_archive/`，先把 9 篇「归档/删除」类文档移入（git mv，非 rm）。
2. 重建 `pending-items.md`（基线）+ 精简 `progress.md` + 重写 `README.md`（三者是索引，先改）。
3. 回写 G1/G2/G3/G4/G5 的销项与口径。
4. 按 D/F 剥离各篇「事实核查」与「实施日志」。
5. 按 E 收敛跨文档冗余。
6. 最后重排目录结构为上方树。

> 全程不碰代码；每步 git 提交独立，便于回溯。

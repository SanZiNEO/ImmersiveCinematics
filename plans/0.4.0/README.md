# 0.4.0 远期方向
**最后更新**: 2026-08-13

> 部分功能已提前到 0.3.x 实现。以下为剩余远期方向，细节后续讨论。
> 2026-08-09：镜头跟踪扩展（非玩家实体/结构目标）与镜头呼吸扰动已在 0.3.4 完成，
> 对应旧设计文档（camera-tracking-system.md / camera-breath-shake.md）已移入
> `plans/complete/0.3.4/` 并标注"已实现"。
> 2026-08-13：**区块预加载**已提前至 0.3.5 实施（`plans/0.3.5/chunk-preload.md`，含磁盘分流节流）；
> 旧草案 `camera-chunk-preload.md` 与音频草案 `audio-system.md` 已移入 `plans/complete/0.3.5/` 归档（被 0.3.5 的
> `audio-listener-model.md` 取代）。0.4.0 剩余：维度字段、跨维度运镜、相机实例队列、画中画（见
> `camera-queue-pip-dimension.md`）及下方列表。

---

## PAUSE_POINT 轨道

脚本播放到指定时间点时暂停，等待条件满足后继续。

---

## 维度字段（clip 级 `dimension` 声明）— 已归 0.3.5

> 2026-08-13 确认：维度字段（clip 级 `dimension` 声明 + 基础处理）纳入 0.3.5（`plans/0.3.5/chunk-preload.md`），
> 与区块预加载一起落地。0.4.0 保留跨维度运镜（F 类，`camera-queue-pip-dimension.md`）——消费该字段做维度切换。

---

## 编辑器音频联动

编辑器预览时音频随播放头同步、拖拽音频 clip/关键帧时实时响应。

依赖 0.3.4 AUDIO 轨道完成。

---

## Bezier 曲线编辑器

interpolation 增加 `"bezier"` 选项，控制点可视化。

---

> 预设片段库（预设系统）已在 0.3.5 设计：`plans/0.3.5/presets.md`（参数化脚本模板，一键生成），不排 0.4.0。
> 编辑器音频联动（预览随播放头同步、拖拽实时跟随）归入 0.3.5 音频重构（`plans/0.3.5/audio-listener-model.md`：AUDIO 轨道回归原版后
> repositionAudio/syncToTime 整套重写），不排 0.4.0。

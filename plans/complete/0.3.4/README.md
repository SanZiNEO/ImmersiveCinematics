# 0.3.4 路线图 —【已完成，2026-08-09 归档】

**创建日期**: 2026-07-27  
**归档日期**: 2026-08-09（转入 `plans/complete/`）

**版本**: 0.3.4  
**基础**: 0.3.3 (Architectury 迁移 + Schema 驱动数据模型 + 遗留修复全部完成)

> ✅ 五个功能全部实现并实测：AUDIO / OVERLAY / EVENT（运行时）/ 镜头跟踪 / 呼吸扰动。
> 镜头跟踪实现为**关键帧级**设计（超出本计划 clip 级方案），含实体选择器子集与结构目标
> （服务端 /locate 同源定位，不依赖区块预加载）；EVENT 轨道编辑器按确认方案复用
> clip 块 + 关键帧表达。详见 `docs/SCRIPT_FORMAT.md` 与 `camera-tracking-system.md` 归档标注。

---

## 概述

0.3.4 从原 0.4.0 计划中选取功能提前实现。

### 三个新轨道

| 轨道 | 说明 |
|------|------|
| [AUDIO](./audio-system.md) | OGG 播放，关键帧控制 volume/position，直接 LWJGL OpenAL |
| [OVERLAY](./overlay-track.md) | 覆盖层系统：fade / image / subtitle / PiP，多层叠加 |
| [EVENT](./event-track-rework.md) | 从 clip 段改为 keyframe 驱动多点触发 |

### 两个增强

| 功能 | 说明 |
|------|------|
| [镜头跟踪](./camera-tracking-system.md) | Camera clip 级 tracking，关键帧+跟踪二合一 |
| [镜头呼吸扰动](./camera-breath-shake.md) | Camera clip 级配置，运行时叠加随机微晃 |

---

## 依赖关系

```
AUDIO         ← 独立
OVERLAY       ← 依赖 OverlayManager（已有）
EVENT         ← 独立
TRACKING      ← 独立（玩家/坐标无需区块加载）
BREATH        ← 独立
```

---

## 不做 / 留 0.3.5+

- PAUSE_POINT 轨道
- 区块预加载（0.3.5）
- 维度字段（0.3.5）
- Bezier 曲线编辑器
- 预设片段库
- 编辑器音频实时跟随拖拽
- 音频倍速变调/音高处理
- 跟踪系统：非玩家实体/结构目标（等预加载完成）

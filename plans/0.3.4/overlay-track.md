# 覆盖层轨道
**创建日期**: 2026-07-27

## 是什么

OVERLAY 轨道在现有 OverlayManager/OverlayLayer 架构上扩展，新增一种轨道类型，支持多层叠加。

多条 OVERLAY 轨道可以同时存在（无数量限制），`ScriptPlayer` 为每条轨道创建独立的 `OverlayTrackPlayer`，`OverlayManager` 按 zIndex 排序渲染。

## 子类型

| layer_type | 渲染效果 | 关键帧控制 |
|-----------|---------|-----------|
| `fade` | 全屏颜色遮罩 | opacity / 位置大小 |
| `image` | PNG 纹理叠加 | opacity / 位置大小 / 锚点 |
| `subtitle` | 文字字幕 | opacity / 位置大小 / 锚点 |
| `pip` | 画中画第二视角 | opacity / 位置大小 / 锚点 |

## 坐标系统

以覆盖层对象左上角为坐标原点，屏幕空间坐标：

```
(0,0) ──────────── screen ────
  │  ┌───── overlay ─────┐
  │  │ (x, y)            │
  │  │  宽 w × 高 h     │
  │  │  锚点 (anchor)    │
  │  └───────────────────┘
```

## 数据模型

新增 `TrackType.OVERLAY`（不限数量）。

### Clip 级字段（静态）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `layer_type` | enum | 是 | `fade` / `image` / `subtitle` / `pip` |
| `color` | string | 否 | 颜色值，用于 fade |
| `path` | string | 否 | 图片路径，用于 image |
| `text` | string | 否 | 文字内容，用于 subtitle |
| `fade_in` | float | 否 | 淡入时长 |
| `fade_out` | float | 否 | 淡出时长 |

### Keyframe 字段（动态控制）

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `time` | float | — | 关键帧时间点（必填） |
| `opacity` | float | 1.0 | 透明度 0~1 |
| `x` | float | 0 | 屏幕 X 坐标 |
| `y` | float | 0 | 屏幕 Y 坐标 |
| `width` | float | — | 覆盖层宽度 |
| `height` | float | — | 覆盖层高度 |
| `anchor_x` | float | 0.5 | 锚点水平 0~1 |
| `anchor_y` | float | 0.5 | 锚点垂直 0~1 |

## 编辑器

- TimelineArea 显示多条 OVERLAY 轨道行（和现有多轨道渲染一致）
- 属性面板：layer_type 选择、颜色拾取、路径输入、文字输入
- 关键帧编辑：opacity / x / y / width / height / anchor

## 渲染层级

在现有 Letterbox 层之上叠加：

```
zIndex 0:  LetterboxLayer
zIndex 10: ScreenFadeLayer  (第一条 OVERLAY 轨道)
zIndex 20: ImageLayer       (第二条 OVERLAY 轨道)
zIndex 30: SubtitleLayer    (第三条 OVERLAY 轨道)
zIndex 40: PipLayer         (第四条 OVERLAY 轨道)
```

每条 OVERLAY 轨道对应一个 zIndex 层级，层间叠加渲染。

## 改动文件

| 文件 | 操作 |
|------|------|
| `overlay/ScreenFadeLayer.java` | 新增 — zIndex 10+ |
| `overlay/ImageLayer.java` | 新增 — zIndex 20+ |
| `overlay/SubtitleLayer.java` | 新增 — zIndex 30+ |
| `overlay/PipLayer.java` | 新增 — zIndex 40+ |
| `script/OverlayTrackPlayer.java` | 新增 |
| `script/TrackType.java` | 修改 — 新增 OVERLAY（不限数量） |
| `schema.json` | 修改 — 新增 OVERLAY 定义 |
| `script/TrackPlayer.java` | 修改 — 工厂 case OVERLAY |
| `editor/area/LeftPanelArea.java` | 修改 — OVERLAY 属性面板 |
| `editor/area/TimelineArea.java` | 修改 — OVERLAY 轨道渲染 |

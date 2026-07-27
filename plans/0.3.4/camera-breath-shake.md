# 镜头呼吸扰动
**创建日期**: 2026-07-27

## 是什么

Camera clip 级配置项，运镜全程或片段性叠加随机微晃，模拟手持/呼吸感。

## 不做

- 不修改关键帧系统
- 不作为独立轨道
- 不打进 camera keyframe

作为 clip 输出后的独立后处理层，避免污染镜头设计数据。

## 数据模型

CAMERA clip 新增字段：
- `breath_enabled`: bool（默认 false）
- `breath_intensity`: float 0.0~1.0（扰动幅度上限）
- `breath_seed`: int（随机种子，可复现）

## 运行时

`CameraTrackPlayer.writeAttributes()` 末尾：如果 clip.breath_enabled，在 yaw/pitch/roll 上叠加随机值。每帧用 `globalTime * 100 + seed` 做种子保证复现。

## 改动文件

| 文件 | 操作 |
|------|------|
| `schema.json` | 修改 — CAMERA clip 加 3 个字段 |
| `script/CameraTrackPlayer.java` | 修改 — writeAttributes 末尾叠加 |

# 音频轨道
**创建日期**: 2026-07-27

## 是什么

AUDIO 轨道让脚本能够播放 OGG 音频文件，用关键帧实时控制音量和空间位置。

## 做什么

1. **什么时候播** — clip 的 start_time / duration 决定
2. **在什么坐标** — keyframe 的 x/y/z 控制（支持 relative/absolute 模式）
3. **什么音量** — keyframe 的 volume 控制

## 不做

- 倍速变调
- 音高调整
- 音频滤镜
- 混音处理

全部交给 OpenAL 处理。

## 播放通路

直接 LWJGL OpenAL，不经过 Minecraft SoundEngine/SoundManager：
- OGG 文件用 STBVorbis 解码
- OpenAL 上下文直接用 MC 已初始化的
- 文件放 `run/immersive_cinematics/video/` 下
- `minecraft:` 前缀走原版 SoundEvent

## 数据模型

clip 字段：`sound`(required) / `source` / `attenuation` / `position_mode`
keyframe 字段：`volume` / `x` / `y` / `z`

## 编辑器

AUDIO 属性面板：sound 路径输入、source/attenuation/position_mode 下拉、volume 滑块、坐标字段。

## 改动文件

| 文件 | 操作 |
|------|------|
| `script/CinematicAudioInstance.java` | 新增 — OpenAL 音源管理 |
| `script/AudioTrackPlayer.java` | 修改 — 空桩改为完整实现 |
| `schema.json` | (已有字段，不改) |
| `editor/area/LeftPanelArea.java` | 修改 — AUDIO 属性面板 |
| `editor/area/TimelineArea.java` | 修改 — AUDIO 轨道渲染确认 |

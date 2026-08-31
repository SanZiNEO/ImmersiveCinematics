# 0.3.5 归档说明

> 状态：0.3.5 功能开发与收尾已完成，已归档到 `plans/complete/0.3.5/`。
> 本 README 按“实际代码实现”整理：保留原初设计，同时标出最终落地实现。

## 一、最终实现方案（以实际代码为准）

| 文档 | 说明 |
|---|---|
| `prewarm-release-diff.md` | **区块预加载最终实现**：状态边界统一差集、下一片段预热、结束释放差集复用 |
| `no-fake-camera-region.md` | **无假人相机区域最终实现**：坐标锚点 + CameraEntitySyncManager 实体同步 |
| `arch-refactor.md` | **去 Architectury 最终实现**：MultiLoader 重构 |
| `schema-java-metadata.md` | **Schema Java 元数据化最终实现**：`SchemaRegistry` / `FieldDef` |
| `input-handoff.md` | 输入中间层退出重同步最终实现 |
| `player-movement-control.md` | EVENT position 假输入移动最终实现 |
| `audio-listener-model.md` | 音频听者 / 回归 SoundEngine 最终实现 |
| `dynamic-yaw-reference.md` | yaw_base / pitch_base / line 最终实现 |
| `look-at-relative-target.md` | look_at_target 对象最终实现 |
| `editor-modularization.md` | 编辑器模块化最终实现 |
| `editor-interaction-improvements.md` | 中间帧继承 / 飞行取景最终实现 |
| `presets.md` | 预设系统最终实现 |
| `gif-overlay.md` | GIF Overlay 最终实现 |
| `breath-disturbance.md` | 呼吸扰动 v2 最终实现 |
| `script-folder-organization.md` | 脚本目录组织最终实现 |
| `trigger-prerequisites.md` | 触发器前置依赖最终实现 |
| `block-based-reference.md` | 方块基准（partial）最终实现 |
| `error-handling-no-swallow.md` | 错误处理不吞异常最终实现 |
| `tangent-orientation.md` | 切线朝向最终实现（已落地，待编辑器微调） |

## 二、原初设计 / 历史方案（保留用于追溯）

| 文档 | 说明 |
|---|---|
| `preload-camera-region-unified.md` | 区块预加载与相机区域**原初统一设计（假人完全接管版）**；实际代码后改为“状态边界统一差集”，最终见 `prewarm-release-diff.md` |
| `chunk-preload.md` | 3.5 历史方案（手动 ticket + scanChunk + 手动补发），被统一设计取代 |
| `camera-region-mechanics-5.5.md` | 5.5 轮细节追溯，已并入统一设计 |
| `audio-playback-model.md` | 音频草案，被 `audio-listener-model.md` 取代 |
| `audio-relative-position-modes.md` | 音频相对位置讨论记录，结论并入 `audio-listener-model.md` |

## 三、问题记录 / 审查记录（历史归档）

| 文档 | 说明 |
|---|---|
| `code-review-round.md` | 代码审查问题集 |
| `round6-issues.md` | 0.3.5 收尾问题记录 |
| `final-round-issues.md` | 最后一轮问题记录 |
| `known-issues.md` | 已知问题（已修复） |

## 四、进度与总览

| 文档 | 说明 |
|---|---|
| `1.md` | 0.3.5 六轮任务拆分与进度 |

## 五、留后 / 未做项

- `block-based-reference.md`：`look_at_target` 使用 block 留后（低优先级）。
- `1.md`：部分低优先级项留后，不影响 0.3.5 主体完成。

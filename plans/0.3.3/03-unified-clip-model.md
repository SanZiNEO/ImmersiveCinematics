# ⑤ — 运行时数据模型统一：Clip/Keyframe

**隶属**: 01 — 迁移前修复（可选，可留到 Architectury 时做）  
**改动范围**: 仅 `script/` 包数据模型层，零编辑器代码改动

---

## 问题

当前每个轨道类型有独立的 Clip 和 Keyframe 类：

| 轨道 | Clip 类 | Keyframe 类 |
|------|---------|-------------|
| CAMERA | `CameraClip` | `CameraKeyframe` |
| LETTERBOX | `LetterboxClip` | `LetterboxKeyframe` |
| AUDIO | `AudioClip` | ❌ 无 |
| EVENT | `EventClip` | ❌ 无 |
| MOD_EVENT | `ModEventClip` | ❌ 无 |

新增轨道类型需要创建两个 Java 类，`TimelineTrack` 需新增 `getXxxClips()` 方法。

## 修复方向

合并为通用容器：

- **`Clip`** — `startTime`、`duration`、轨道类型、`List<Keyframe>`
- **`Keyframe`** — `time`、`Map<String, Object> data`
- **`TimelineTrack`** — 统一 `getClips()`，删除 5 个 `getXxxClips()`
- **TrackPlayer 工厂** — `switch` 分发保留（播放逻辑本质不同）

## 改动文件

| 文件 | 改动 |
|------|------|
| 新增 `Clip.java` | 统一容器 |
| 新增 `Keyframe.java` | 统一容器 |
| `CameraClip.java` | 删除 |
| `CameraKeyframe.java` | 删除 |
| `LetterboxClip.java` | 删除 |
| `LetterboxKeyframe.java` | 删除 |
| `AudioClip.java` | 删除 |
| `EventClip.java` | 删除 |
| `ModEventClip.java` | 删除 |
| `TimelineTrack.java` | 5 个 `getXxxClips()` → 统一 `getClips()` |
| `ScriptParser.java` | 统一解析 |
| `CameraTrackPlayer.java` | 适配新接口 |
| `LetterboxTrackPlayer.java` | 同上 |
| `AudioTrackPlayer.java` | 同上 |
| `ModEventTrackPlayer.java` | 同上 |
| `ScriptEventManager.java` | 同上 |
| `ScriptPlayer.java` | 同上 |

## 参考

ReplayMod 的 `Keyframe` + `Property` 体系。本地参考：`example/ReplayMod-stable/`。

# ⑤ — Schema 驱动的统一 Clip/Keyframe 数据模型

> 这个方案覆盖了原 01 ⭐位置（运行时数据模型统一），还延展到了编辑器的 schema 驱动。  
> 改动范围：
> - `script/` — 数据模型（Clip/Keyframe 统一）+ Parser 统一
> - `editor/` — 编辑器属性面板读 schema 自动渲染
> - `schema.json` — 新增，作为顶层定义文件

---

## 现状与问题

每种轨道有自己的 Clip 类和 Keyframe 类：

| 轨道 | Clip 类 | Keyframe 类 |
|------|---------|-------------|
| CAMERA | `CameraClip` | `CameraKeyframe` |
| LETTERBOX | `LetterboxClip` | `LetterboxKeyframe` |
| AUDIO | `AudioClip` | ❌ |
| EVENT | `EventClip` | ❌ |
| MOD_EVENT | `ModEventClip` | ❌ |

衍生问题：
- 新增轨道类型 → 建 2 个类 + 改 `TimelineTrack.getXxxClips()` + 加解析方法 + 编辑器加 UI = 至少 5 个文件
- 改通用字段（如加 `fade_in`）→ 改 5 个 Clip 类
- 编辑器和运行时各自手写字段定义，常改了一边忘另一边

---

## 方案

### 顶层 schema 定义

新增 `schema.json`，定义所有轨道类型和它们的字段：

```json
{
  "track_types": {
    "CAMERA": {
      "clips": {
        "transition": { "type": "enum", "values": ["cut", "morph"], "default": "cut" },
        "transition_duration": { "type": "float", "default": 0.5 },
        "interpolation": { "type": "enum", "values": ["linear"], "default": "linear" },
        "position_mode": { "type": "enum", "values": ["relative", "absolute"], "default": "relative" },
        "loop": { "type": "bool", "default": false },
        "loop_count": { "type": "int", "default": -1 },
        "keyframes": { "type": "keyframe_list", "keyframe_type": "CAMERA" }
      },
      "keyframes": {
        "position": { "type": "position", "required": true },
        "yaw": { "type": "float", "default": 0 },
        "pitch": { "type": "float", "default": 0 },
        "roll": { "type": "float", "default": 0 },
        "fov": { "type": "float", "default": 70 },
        "zoom": { "type": "float", "default": 1.0 },
        "dof": { "type": "float", "default": 0 }
      }
    },
    "LETTERBOX": {
      "clips": {
        "keyframes": { "type": "keyframe_list", "keyframe_type": "LETTERBOX" }
      },
      "keyframes": {
        "aspect_ratio": { "type": "float", "default": 2.35 }
      }
    },
    "AUDIO": {
      "clips": {
        "sound": { "type": "string", "required": true },
        "volume": { "type": "float", "default": 1.0 },
        "pitch": { "type": "float", "default": 1.0 },
        "loop": { "type": "bool", "default": false },
        "fade_in": { "type": "float", "default": 0 },
        "fade_out": { "type": "float", "default": 0 }
      }
    },
    "EVENT": {
      "clips": {
        "event_type": { "type": "string", "required": true },
        "command": { "type": "string", "required": true }
      }
    },
    "MOD_EVENT": {
      "clips": {
        "event_type": { "type": "string", "required": true },
        "data": { "type": "map" }
      }
    }
  },
  "common_clip_fields": {
    "start_time": { "type": "float", "required": true },
    "duration": { "type": "float", "required": true }
  },
  "common_keyframe_fields": {
    "time": { "type": "float", "required": true }
  },
  "position_types": {
    "relative": { "dx": "float", "dy": "float", "dz": "float" },
    "absolute": { "x": "float", "y": "float", "z": "float" }
  }
}
```

### 数据模型

```
common/script/
├── Clip.java          ← start_time, duration, track_type, Map<String, Object> data, List<Keyframe> keyframes
├── Keyframe.java      ← time, Map<String, Object> data
├── SchemaLoader.java  ← 加载 schema.json，提供字段定义查询
├── TimelineTrack.java ← 统一 getClips()，删除 5 个 getXxxClips()
└── TrackPlayer.java / 各 TrackPlayer 实现 ← 播放逻辑手写，不变
```

**删除**：`CameraClip.java`、`CameraKeyframe.java`、`LetterboxClip.java`、`LetterboxKeyframe.java`、`AudioClip.java`、`EventClip.java`、`ModEventClip.java`

### ScriptParser

不再按 5 种轨道各写一个解析方法：
- 读 `schema.json` 知道当前轨道类型有哪些字段、类型、默认值
- 遍历 clip 字段列表，按类型解析
- 遍历 keyframe 字段列表，按类型解析
- 未知字段不报错（向前兼容），缺必填字段才抛异常

```java
TrackType type = TrackType.valueOf(trackObj.get("type").getAsString());
TrackTypeSchema schema = SchemaLoader.getTrackType(type);
JsonArray clipsArr = trackObj.getAsJsonArray("clips");
for (JsonElement e : clipsArr) {
    Clip clip = parseClip(e.getAsJsonObject(), schema);
    // ...
}
```

### TrackPlayer

播放逻辑不变，但取字段改为通用接口：

```java
// 旧：float yaw = kf.getYaw();
// 新：float yaw = kf.getFloat("yaw");
```

`CameraTrackPlayer` 从 `clip.getKeyframes()` 取数据，按 `position_mode` 解 position。  
`LetterboxTrackPlayer` 从 `clip.getKeyframes()` 取 `aspect_ratio`。  
`AudioTrackPlayer` 从 `clip.getString("sound")` 取音效 ID。

### 编辑器 UI

`LeftPanelArea` 不手写每个字段的 UI 控件位置：
- 读 `schema.json`，知道当前选中 clip/关键帧有什么字段
- 字段类型 → 自动对应控件（float→slider、bool→toggle、string→输入框）
- `EditorOperations` 对应方法改为通用字段操作

---

## 改动文件清单

| 文件 | 改动 |
|------|------|
| 新增 `schema.json` | 顶层定义文件，`common/src/main/resources/` |
| 新增 `script/Clip.java` | 通用 Clip 容器 |
| 新增 `script/Keyframe.java` | 通用 Keyframe 容器 |
| 新增 `script/SchemaLoader.java` | 加载 + 查询 schema |
| `script/CameraClip.java` | 删除 |
| `script/CameraKeyframe.java` | 删除 |
| `script/LetterboxClip.java` | 删除 |
| `script/LetterboxKeyframe.java` | 删除 |
| `script/AudioClip.java` | 删除 |
| `script/EventClip.java` | 删除 |
| `script/ModEventClip.java` | 删除 |
| `script/TimelineTrack.java` | 5 个 `getXxxClips()` → 统一 `getClips()` |
| `script/ScriptParser.java` | 从 5 个解析方法改为 schema 驱动统一解析 |
| `script/TrackPlayer.java` | 工厂方法适配新 Clip |
| `script/CameraTrackPlayer.java` | 适配 `clip.getKeyframes()`、`kf.getFloat("yaw")` |
| `script/LetterboxTrackPlayer.java` | 同上 |
| `script/AudioTrackPlayer.java` | 同上 |
| `script/ModEventTrackPlayer.java` | 同上 |
| `script/ScriptPlayer.java` | 适配新接口 |
| `script/ScriptEventManager.java` | 适配新接口 |
| `editor/EditorOperations.java` | 字段生成改为读 schema |
| `editor/area/LeftPanelArea.java` | 属性面板改为 schema 驱动 |
| `editor/trigger/*` | 不受影响（触发器定义不变） |

---

## 新增轨道类型的流程

```
1. schema.json 加一段定义（字段名、类型、默认值）
2. 写一个 TrackPlayer 实现（播放逻辑）
= 完成
```

不需要动：Parser、TimelineTrack、编辑器 UI、任何现有 Clip 类。

---

## 与原有 03-unified-clip-model.md 的差异

| 维度 | 旧（原文档） | 新（当前方案） |
|------|------------|--------------|
| 范围 | 仅 `script/` 包，零编辑器 | `script/` + `editor/` + `schema.json` |
| 编辑器适配 | 不改 | 属性面板读 schema 自动渲染 |
| Parser | 统一解析 | schema 驱动，不硬编码类型 |
| 字段驱动 | 硬编码字段名 | schema 定义字段 |
| 新增类型 | 建类 + 改 getXxxClips + Parser | 改 schema + 写 TrackPlayer |

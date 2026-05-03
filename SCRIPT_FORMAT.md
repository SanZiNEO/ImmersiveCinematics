# Immersive Cinematics — 脚本格式参考

本文档面向脚本作者，完整说明 `.json` 脚本文件的全部可用字段与结构。

---

## 根结构

```json
{
  "meta":     { ... },
  "timeline": { ... }
}
```

---

## 1. `meta` — 脚本元信息

### 1a. 身份标识

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | string | 是 | — | 脚本唯一 ID，仅允许 `[a-zA-Z0-9_]`，最长 32 字符 |
| `name` | string | 是 | — | 脚本显示名称，最长 50 字符 |
| `author` | string | 是 | — | 作者名，最长 30 字符 |
| `version` | int | 是 | — | 固定为 `3`（仅支持版本 3） |
| `description` | string | 否 | `""` | 脚本描述文本 |
| `dimension` | string | 否 | `null` | 限制脚本只在指定维度可用 |


### 1b. 运行时行为 (RuntimeBehavior)

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `block_keyboard` | boolean | `true` | 播放期间屏蔽键盘输入 |
| `block_mouse` | boolean | `true` | 播放期间屏蔽鼠标输入 |
| `block_mob_ai` | boolean | `false` | 清除附近怪物 AI（性能消耗较大，慎用） |
| `hide_hud` | boolean | `true` | 隐藏全部 HUD |
| `hide_arm` | boolean | `true` | 隐藏第一人称手臂 |
| `suppress_bob` | boolean | `true` | 抑制视角晃动 |
| `hide_chat` | boolean | `null` | `null`=跟随 `hide_hud`，`true/false`=强制显隐 |
| `hide_scoreboard` | boolean | `null` | 同上 |
| `hide_action_bar` | boolean | `null` | 同上 |
| `hide_title` | boolean | `null` | 同上 |
| `hide_subtitles` | boolean | `null` | 同上 |
| `hide_hotbar` | boolean | `null` | 同上 |
| `hide_crosshair` | boolean | `null` | 同上 |
| `render_player_model` | boolean | `true` | 是否渲染玩家模型（第三人称时） |
| `pause_when_game_paused` | boolean | `true` | 游戏暂停时是否暂停过场动画 |
| `interruptible` | boolean | `true` | 是否允许被其他脚本打断 |
| `skippable` | boolean | `true` | 是否允许玩家长按跳过 |
| `hold_at_end` | boolean | `false` | 播放完毕后是否停留在最后一帧 |


### 1c. Triggers（触发条件）

```json
"triggers": [
  {
    "id": "on_login",
    "type": "login",
    "conditions": {},
    "repeatable": false,
    "delay": 1.5
  }
]
```

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | string | 是 | — | 触发器唯一标识 |
| `type` | string | 是 | — | 触发器类型，见下方列表 |
| `conditions` | object | 否 | `{}` | 类型对应的条件参数 |
| `repeatable` | boolean | 否 | `false` | 是否可重复触发 |
| `delay` | number | 否 | `0` | 触发后延迟执行秒数 |

全部触发类型及条件参数见 `TRIGGER_TYPES.md`。

---

## 2. `timeline` — 时间线

```json
"timeline": {
  "total_duration": 60.0,
  "tracks": [ ... ]
}
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `total_duration` | float | 是 | 总时长（秒），正数=定长，负数=无限 |
| `tracks` | array | 是 | 轨道数组 |

---

## 3. 轨道类型

每条轨道包含 `type` 和 `clips[]`。

| 轨道类型 | 说明 |
|---------|------|
| `"camera"` | 相机位置/朝向/光学控制 |
| `"letterbox"` | 遮幅黑边 |
| `"audio"` | 音频播放 |
| `"event"` | 服务端命令事件 |
| `"mod_event"` | 第三方模组扩展事件 |

---

## 4. Camera 轨道

### Clip 字段

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `start_time` | float | 是 | — | 全局时间线起始点（秒） |
| `duration` | float | 是 | — | 持续时间，正数=定长，负数=无限 |
| `transition` | string | 否 | `"cut"` | `"cut"`=硬切，`"morph"`=线性过渡 |
| `transition_duration` | float | 否 | `0.5` | morph 过渡时长（秒） |
| `interpolation` | string | 否 | `"linear"` | `"linear"` 或 `"smooth"`（预留） |
| `position_mode` | string | 否 | `"relative"` | `"relative"`=相对玩家，`"absolute"`=世界坐标 |
| `loop` | boolean | 否 | `false` | 是否循环播放 |
| `loop_count` | int | 否 | `-1` | `-1`=无限循环 |
| `curve` | object | 否 | `null` | 贝塞尔路径曲线 |
| `keyframes` | array | 是 | — | 关键帧数组，至少 1 个 |

### curve（贝塞尔曲线）

```json
"curve": {
  "type": "bezier",
  "control_points": [
    { "x": 10, "y": 1.5, "z": 3 },
    { "x": 0,  "y": 2.0, "z": -2 }
  ]
}
```

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `type` | string | 否 | `"bezier"` | 曲线类型 |
| `control_points` | array | 是 | — | 2 个控制点，每个含 `x`/`y`/`z` |

### Keyframe 字段

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `time` | float | 是 | — | 在 clip 内的时间偏移（秒），从 0 开始 |
| `position` | object | 是 | — | 位置，格式见下方 |
| `yaw` | float | 是 | — | 偏航角（度）。0=南，90=西，±180=北 |
| `pitch` | float | 是 | — | 俯仰角（度）。正=向下看 |
| `roll` | float | 是 | — | 翻滚角（度）。正=左倾 |
| `fov` | float | 是 | — | 视场角（度），标准 70 |
| `zoom` | float | 否 | `1.0` | 缩放倍率，`>1`=放大 |
| `dof` | float | 否 | `0.0` | 景深强度（预留） |

### Position（相对模式 `relative`）

```json
"position": { "dx": 30, "dy": 2, "dz": 0 }
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `dx` | float | 相对玩家位置的 X 偏移 |
| `dy` | float | 相对玩家位置的 Y 偏移 |
| `dz` | float | 相对玩家位置的 Z 偏移 |

### Position（绝对模式 `absolute`）

```json
"position": { "x": 100, "y": 64, "z": 200 }
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `x` | float | 世界坐标 X |
| `y` | float | 世界坐标 Y |
| `z` | float | 世界坐标 Z |

---

## 5. Letterbox 轨道

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `start_time` | float | 是 | — | 起始时间 |
| `duration` | float | 是 | — | 持续时间 |
| `enabled` | boolean | 否 | `true` | 是否启用遮幅 |
| `aspect_ratio` | float | 否 | `2.35` | 画面宽高比。常见：`2.35`（电影）, `1.778`（16:9）, `2.0` |
| `fade_in` | float | 否 | `0.5` | 渐入时长（秒），`0`=瞬间出现 |
| `fade_out` | float | 否 | `0.5` | 渐出时长（秒），`0`=瞬间消失 |

---

## 6. Audio 轨道

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `start_time` | float | 是 | — | 起始时间 |
| `duration` | float | 是 | — | 持续时间 |
| `sound` | string | 是 | — | 声音 ID，如 `"minecraft:ambient.cave"` |
| `volume` | float | 否 | `1.0` | 音量（`0.0` ~ `1.0`） |
| `pitch` | float | 否 | `1.0` | 音调（`0.5` ~ `2.0`） |
| `loop` | boolean | 否 | `false` | 是否循环 |
| `fade_in` | float | 否 | `0.0` | 淡入时长（秒） |
| `fade_out` | float | 否 | `0.0` | 淡出时长（秒） |

---

## 7. Event 轨道

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `start_time` | float | 是 | 起始时间 |
| `duration` | float | 是 | `0`=瞬间执行 |
| `event_type` | string | 是 | 固定为 `"command"` |
| `command` | string | 是 | 要执行的命令，如 `"/time set 6000"` |

---

## 8. ModEvent 轨道

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `start_time` | float | 是 | — | 起始时间 |
| `duration` | float | 是 | — | 持续时间 |
| `event_type` | string | 是 | — | 自定义事件 ID，如 `"mymod:animation"` |
| `data` | object | 否 | `{}` | 任意自定义数据 |

---

## 完整示例

```json
{
  "meta": {
    "id": "my_cinematic",
    "name": "我的过场动画",
    "author": "ImmersiveCinematics",
    "version": 3,
    "description": "一个完整的示例脚本",
    "block_keyboard": true,
    "block_mouse": true,
    "hide_hud": true,
    "hide_arm": true,
    "suppress_bob": true,
    "pause_when_game_paused": true,
    "interruptible": true,
    "skippable": true,
    "hold_at_end": false,
    "triggers": [
      {
        "id": "on_login",
        "type": "login",
        "repeatable": true,
        "delay": 1.0
      }
    ]
  },
  "timeline": {
    "total_duration": 30.0,
    "tracks": [
      {
        "type": "camera",
        "clips": [
          {
            "start_time": 0.0,
            "duration": 10.0,
            "transition": "cut",
            "interpolation": "linear",
            "position_mode": "relative",
            "keyframes": [
              {
                "time": 0.0,
                "position": { "dx": 5, "dy": 2, "dz": 3 },
                "yaw": 90, "pitch": 5, "roll": 0,
                "fov": 70, "zoom": 1.0, "dof": 0
              },
              {
                "time": 10.0,
                "position": { "dx": 0, "dy": 2, "dz": 0 },
                "yaw": 0, "pitch": 10, "roll": 0,
                "fov": 70, "zoom": 1.0, "dof": 0
              }
            ]
          }
        ]
      },
      {
        "type": "letterbox",
        "clips": [
          {
            "start_time": 0.0,
            "duration": 30.0,
            "aspect_ratio": 2.35,
            "fade_in": 0.5,
            "fade_out": 0.5
          }
        ]
      },
      {
        "type": "audio",
        "clips": [
          {
            "start_time": 0.0,
            "duration": 30.0,
            "sound": "minecraft:music.game",
            "volume": 0.8,
            "loop": false
          }
        ]
      },
      {
        "type": "event",
        "clips": [
          {
            "start_time": 5.0,
            "duration": 0.0,
            "event_type": "command",
            "command": "/weather clear"
          }
        ]
      }
    ]
  }
}
```

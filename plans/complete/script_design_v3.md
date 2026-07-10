# 脚本设计文档 v3

---

## 完整示例脚本

```json
{
  "meta": {
    "id": "example",
    "name": "脚本名称",
    "author": "作者",
    "version": 3,
    "description": "描述",

    "dimension": "minecraft:overworld",

    "block_keyboard": true,
    "block_mouse": true,
    "block_mob_ai": true,

    "hide_hud": true,
    "hide_arm": true,
    "suppress_bob": true,
    "hide_chat": false,
    "hide_scoreboard": true,
    "hide_action_bar": false,
    "hide_title": false,
    "hide_subtitles": true,
    "hide_hotbar": false,
    "hide_crosshair": false,

    "render_player_model": true,
    "pause_when_game_paused": true,

    "interruptible": true,
    "skippable": true,
    "hold_at_end": false,

    "triggers": [
      {
        "type": "location",
        "conditions": {
          "dimension": "minecraft:overworld",
          "position": { "x": 100, "y": 64, "z": 200 },
          "radius": 15
        },
        "repeatable": false
      }
    ]
  },
  "timeline": {
    "total_duration": 10,
    "tracks": [
      {
        "type": "CAMERA",
        "clips": [
          {
            "start_time": 0,
            "duration": 10,
            "transition": "cut",
            "transition_duration": 2.0,
            "position_mode": "relative",
            "loop": false,
            "loop_count": -1,
            "curve": {
              "type": "bezier",
              "control_points": [
                { "x": 0, "y": 5, "z": 0 },
                { "x": 5, "y": 5, "z": 5 }
              ]
            },
            "keyframes": [
              { "time": 0, "position": { "dx": 0, "dy": 0, "dz": 0 }, "yaw": 0, "pitch": 0, "roll": 0, "fov": 70, "zoom": 1.0, "dof": 0 },
              { "time": 10, "position": { "dx": 10, "dy": 0, "dz": 0 }, "yaw": 90, "pitch": 10, "roll": 5, "fov": 60, "zoom": 1.5, "dof": 0 }
            ]
          }
        ]
      },
      {
        "type": "LETTERBOX",
        "clips": [
          {
            "start_time": 0,
            "duration": 10,
            "enabled": true,
            "aspect_ratio": 2.35,
            "fade_in": 0.5,
            "fade_out": 0.5
          }
        ]
      }
    ]
  }
}
```

---

## 属性说明

### meta — 脚本元信息

| 字段 | 类型 | 默认 | 说明 |
|------|------|:----:|------|
| `id` | string | — | 脚本唯一标识，加载时使用 `cinematics:id` |
| `name` | string | — | 显示名称 |
| `author` | string | — | 作者 |
| `version` | int | — | 版本号 |
| `description` | string | — | 描述 |

### meta — 输入屏蔽

| 字段 | 类型 | 默认 | 说明 |
|------|------|:----:|------|
| `block_keyboard` | bool | true | 屏蔽键盘输入（Esc 暂停放行） |
| `block_mouse` | bool | true | 屏蔽鼠标输入（移动/按键/滚轮） |
| `block_mob_ai` | bool | false | 禁止生物对玩家寻敌（单机有效） |

### meta — HUD 控制

`hide_hud` 控制全局 HUD 隐藏。以下子控制为**可空**——不写则跟随 `hide_hud`，写了 true/false 则覆盖：

| 字段 | 类型 | 默认 | 覆盖对象 |
|------|------|:----:|---------|
| `hide_hud` | bool | true | 全局 HUD 开关 |
| `hide_arm` | bool | true | 第一人称手臂 |
| `suppress_bob` | bool | true | 视角晃动（行走/受伤/反胃） |
| `hide_chat` | bool? | null | 聊天面板 |
| `hide_scoreboard` | bool? | null | 计分板侧栏 + 玩家列表(TAB) |
| `hide_action_bar` | bool? | null | 动作栏提示 |
| `hide_title` | bool? | null | 标题文字 |
| `hide_subtitles` | bool? | null | 字幕 |
| `hide_hotbar` | bool? | null | 快捷栏 |
| `hide_crosshair` | bool? | null | 准星 |

### meta — 渲染

| 字段 | 类型 | 默认 | 说明 |
|------|------|:----:|------|
| `render_player_model` | bool | true | 渲染玩家第三人称模型 |
| `pause_when_game_paused` | bool | true | 游戏暂停时脚本是否暂停 |

### meta — 退出控制

| 字段 | 类型 | 默认 | 说明 |
|------|------|:----:|------|
| `interruptible` | bool | true | 是否允许被其他脚本打断 |
| `skippable` | bool | true | 是否允许用户长按 C(3s) 跳过 |
| `hold_at_end` | bool | false | 播完后是否保持最后一帧不退出 |

### meta — 维度管理

| 字段 | 类型 | 默认 | 说明 |
|------|------|:----:|------|
| `dimension` | string | null | 脚本关联维度 ID，如 `minecraft:the_end`。null=跟随玩家当前维度 |

### meta — 触发器（预留）

| 字段 | 类型 | 默认 | 说明 |
|------|------|:----:|------|
| `triggers` | array | [] | 触发器定义数组，由 TriggerEngine 消费 |

每个触发器元素：

| 字段 | 类型 | 默认 | 说明 |
|------|------|:----:|------|
| `type` | string | — | 触发器类型：`location` / `advancement` / `command` 等 |
| `conditions` | object | {} | 触发条件，结构取决于 `type` |
| `repeatable` | bool | false | 是否可重复触发 |

### timeline — 时间轴

| 字段 | 类型 | 默认 | 说明 |
|------|------|:----:|------|
| `total_duration` | float | — | 总时长（秒），负数=无限 |

### Camera clip

| 字段 | 类型 | 默认 | 说明 |
|------|------|:----:|------|
| `start_time` | float | — | 在时间轴上的开始时间 |
| `duration` | float | — | 持续时长，负数=无限 |
| `transition` | enum | `cut` | 过渡方式：`cut`(硬切) / `morph`(线性飞越) |
| `transition_duration` | float | 0.5 | morph 过渡时长（秒），从上一片段末帧线性飞向下一片段首帧 |
| `position_mode` | enum | `relative` | 坐标模式：`relative`(dx/dy/dz) / `absolute`(x/y/z) |
| `loop` | bool | false | 是否循环播放 |
| `loop_count` | int | -1 | 循环次数，-1=无限 |
| `curve` | object | null | 贝塞尔曲线路径（见下文） |
| `keyframes` | array | — | 关键帧数组（至少1个） |

**已移除的字段**：`speed`（片段速度倍率）、`interpolation`（速度曲线类型）、`property_overrides`（属性级速度覆盖）。关键帧之间为匀速直线运动，要调速请插更多关键帧。

### Camera keyframe

| 字段 | 类型 | 默认 | 说明 |
|------|------|:----:|------|
| `time` | float | — | 片段内时间（秒），单调递增 |
| `position` | object | — | 坐标：`relative` 用 dx/dy/dz，`absolute` 用 x/y/z |
| `yaw` | float | — | 偏航角（度），0=南 90=西 -90=东 ±180=北 |
| `pitch` | float | — | 俯仰角（度），正值=低头 负值=抬头 |
| `roll` | float | — | 滚转角（度），正值=左倾 |
| `fov` | float | — | 视场角（度），标准70 |
| `zoom` | float | 1.0 | 缩放倍率（通过 FOV 除法实现） |
| `dof` | float | 0.0 | 景深（预留给光影包） |

**已移除的字段**：`speed`（关键帧瞬时速度）、`curve_bias`（速度切线弯曲）。速度曲线引擎 `SpeedCurve` 已删除，所有插值退化为 `s = t`（匀速）。

### curve（贝塞尔曲线）

| 字段 | 类型 | 默认 | 说明 |
|------|------|:----:|------|
| `type` | string | `bezier` | 曲线类型 |
| `control_points` | array | — | 两个控制点 `[{x,y,z}, {x,y,z}]` |

### Letterbox clip

| 字段 | 类型 | 默认 | 说明 |
|------|------|:----:|------|
| `start_time` | float | — | 开始时间 |
| `duration` | float | — | 持续时长 |
| `enabled` | bool | true | 是否启用黑边 |
| `aspect_ratio` | float | — | 目标画幅比，如 2.35(宽银幕) / 1.778(16:9) |
| `fade_in` | float | 0.5 | 入场动画时长（秒） |
| `fade_out` | float | 0.5 | 退场动画时长（秒） |

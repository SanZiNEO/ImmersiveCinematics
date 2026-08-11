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
| `priority` | int | `0` | 播放优先级，数值越大越优先；**仅用于队列内排序**（优先级不能大于打断——不可打断脚本永不被打断，新请求一律排队） |


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
| `on_enter` | boolean | 否 | `false` | 仅位置类触发器有效：仅在首次进入区域时触发，已在区域内不重复 |
| `exit_buffer` | number | 否 | `0` | 配合 `on_enter`：玩家离开触发区域多少格后才标记为"已离开"，防止边界抖动 |

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

每条轨道包含 `type`、`clips[]`，以及可选的 `id` / `name`。

**轨道级字段：**

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 轨道类型，见下表 |
| `id` | string | 否 | 轨道唯一标识（编辑器自动生成 `{type小写}_{n}`）。**同类型多条轨道靠 `id` 区分管理**（如多条 OVERLAY 轨道：`overlay_1`、`overlay_2`）；layout/上下层关系引用也用 `id` |
| `name` | string | 否 | 轨道显示名/引用名（编辑器可重命名） |
| `clips` | array | 是 | clip 数组 |

| 轨道类型 | 说明 |
|---------|------|
| `"camera"` | 相机位置/朝向/光学控制 |
| `"letterbox"` | 遮幅黑边 |
| `"audio"` | 音频播放 |
| `"event"` | 服务端命令事件 |
| `"mod_event"` | 第三方模组扩展事件 |
| `"overlay"` | 覆盖层（fade 全屏颜色 / image 图片 / subtitle 字幕 / pip 画中画），**支持多条同类型轨道同时渲染** |

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
| `loop` | boolean | 否 | `false` | 是否循环播放（生命周期开关） |
| `loop_count` | int | 否 | `-1` | 循环次数：`-1`=无限循环；正整数=播 N 个周期后停在末帧；`0` 非法（运行时按 1 处理） |
| `loop_mode` | string | 否 | `"repeat"` | 循环时间映射：`"repeat"`=从头到尾重复；`"pingpong"`=往复折返（监控来回摇） |
| `curve` | object | 否 | `null` | 贝塞尔路径曲线 |
| `keyframes` | array | 是 | — | 关键帧数组，至少 1 个 |

> **v3 迁移**：`position_mode`、`cam_tracking_follow*`、`cam_tracking_look_at*` 已全部迁移到**关键帧级**（见下方 Keyframe 字段）。clip 级不再支持这些字段（保留会被 validate 报废弃提示）。

### 循环（loop）语义

`loop` + `loop_count` 决定片段的**生命周期**（活跃窗口），`loop_mode` 决定周期内的**时间映射**：

- **无限循环**（`loop: true` + `loop_count: -1`）：片段一旦开始就永不结束，脚本也随之持续播放（唯一退出方式是 skippable/interruptible 的手动退出）。适合常驻跟随视角（follow/look_at 固定镜头）：
  ```json
  { "start_time": 0, "duration": 10, "loop": true, "loop_count": -1,
    "keyframes": [
      { "time": 0,  "follow": "entity", "follow_selector": "@p", "position": { "dx": 0, "dy": 4, "dz": -5 }, "yaw": 0, "pitch": -10 },
      { "time": 10, "follow": "entity", "follow_selector": "@p", "position": { "dx": 0, "dy": 4, "dz": -5 }, "yaw": 0, "pitch": -10 }
    ] }
  ```
- **有限循环**（`loop: true` + `loop_count: N`）：播放 N 个周期后停在末帧，活跃窗口 = `start_time + 周期 × N`（周期 = 末关键帧时间 − 首关键帧时间，validate 会检查与 `duration` 的一致性）。
- **往复折返**（`loop: true` + `loop_mode: "pingpong"`）：周期内走到末帧后反向走回首帧，适合监控视角来回摇——关键帧只写半程即可：
  ```json
  { "start_time": 0, "duration": 5, "loop": true, "loop_count": -1, "loop_mode": "pingpong",
    "keyframes": [
      { "time": 0, "yaw": -30 },
      { "time": 5, "yaw": 30 }
    ] }
  ```
- `loop_count: 0` 非法，解析时记录错误并按 1 处理。
- 无限循环片段后接的其他片段会作为特写覆盖播放（其窗口内优先渲染），播完回落到循环视角。

### curve（贝塞尔曲线）

```json
"curve": {
  "type": "bezier",
  "control_points": [
    { "x": 10, "y": 1.5, "z": 3 },     // 绝对模式（世界坐标）
    { "dx": 0, "dy": 2.0, "dz": -2 }   // 相对模式（相对段起点关键帧的偏移）
  ]
}
```

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `type` | string | 否 | `"bezier"` | 曲线类型 |
| `control_points` | array | 是 | — | 2 个控制点，**每个自描述**：含 `x`/`y`/`z` = 世界绝对坐标；含 `dx`/`dy`/`dz` = 相对**段起点关键帧**的偏移（与 position 同模式，可混用） |

> **相对控制点**（dx/dy/dz）解决"相对模式下控制点不可写"的问题：相对场景（如以玩家触发点为出发点绕圆）玩家位置运行时才知道，绝对坐标写不了；相对控制点运行时求值为 `段起点 + 偏移`，绕圆等直接可写。绝对控制点行为与旧版一致。

### Keyframe 字段

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `time` | float | 是 | — | 在 clip 内的时间偏移（秒），从 0 开始 |
| `position` | object | 是 | — | 位置，格式见下方。**follow=entity 时表示相对实体脚底的偏移** |
| `position_mode` | string | 否 | `"relative"` | 该关键帧的坐标模式：`"relative"`=相对触发点（position 用 dx/dy/dz），`"absolute"`=世界坐标（position 用 x/y/z）。可与前后关键帧不同，两端世界坐标平滑插值 |
| `follow` | string | 否 | `"none"` | `"none"`=不跟随；`"entity"`=位置跟随目标实体（动态，每帧取实体插值位置 + position 偏移）。follow↔普通关键帧之间两端世界坐标插值 → 平滑过渡 |
| `follow_selector` | string | 否 | `"@p"` | 跟随目标选择器（见下方"目标选择器"） |
| `look_at` | string | 否 | `"none"` | `"none"`=用 yaw/pitch；`"coordinate"`=注视固定点（xyz 或结构中心）；`"entity"`=注视实体正中心（渲染帧插值位置+半高）。look_at 关键帧的目标点之间插值 → 切换/开关平滑过渡 |
| `look_at_selector` | string | 否 | `"@p"` | 注视目标选择器（`entity` 模式） |
| `look_at_target_x/y/z` | float | 否 | `0/64/0` | 注视固定坐标（`coordinate` 模式）。**与 `look_at_target_structure` 互斥**（编辑器：填结构后坐标输入隐藏） |
| `look_at_target_structure` | string | 否 | `""` | 注视结构中心（`coordinate` 模式）：填结构 id（如 `minecraft:village`）。播放时服务端自动定位**结构 bounding box 中心**（就近搜索，原版 /locate 同范围）并替换为坐标后推送；编辑器里为注册表下拉补全；多人服务器播放同样生效。**与 `look_at_target_x/y/z` 互斥**：指定结构后定位失败也不回退坐标，该端无注视目标（回退角度插值） |
| `yaw` | float | 是 | — | 偏航角（度）。0=南，90=西，±180=北。`look_at != none` 时被覆盖 |
| `pitch` | float | 是 | — | 俯仰角（度）。正=向下看。`look_at != none` 时被覆盖 |
| `roll` | float | 是 | — | 翻滚角（度）。正=屏幕顺时针（画面向右倒），任何朝向一致 |
| `fov` | float | 是 | — | 视场角（度），标准 70 |
| `zoom` | float | 否 | `1.0` | 缩放倍率，`>1`=放大 |

**目标选择器**（`follow_selector` / `look_at_selector`）：

| 写法 | 行为 |
|------|------|
| `@p` / `@s` | 玩家 |
| `@e` | 离相机最近的活实体 |
| `@e[type=minecraft:iron_golem]` | 类型过滤后就近（模组 boss 用其注册 id） |
| `@e[name=自定义名]` | 命名牌名字过滤后就近 |
| `uuid:xxxxxxxx-…` | UUID 直绑（唯一确定，不排序） |

无匹配时：follow 停在上一帧位置、look_at 回退 yaw/pitch。

### Position（相对模式 `relative`）

```json
"position": { "dx": 30, "dy": 2, "dz": 0 }
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `dx` | float | 相对基准点的 X 偏移（**follow=entity 时 = 相对实体脚底的 X 偏移**） |
| `dy` | float | 相对基准点的 Y 偏移（**follow=entity 时 = 相对实体脚底的 Y 偏移**） |
| `dz` | float | 相对基准点的 Z 偏移（**follow=entity 时 = 相对实体脚底的 Z 偏移**） |
| `relative_origin` | string | 可选，相对基准。缺省 = 玩家激活位置；`"coordinate"` = 相对固定坐标（配 `relative_origin_x/y/z`）；其他字符串 = 结构 id，相对**结构中心**（如 `"minecraft:village"`，服务端自动定位，就近搜索） |
| `relative_origin_x/y/z` | float | `"coordinate"` 基准时的基准坐标 |

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

### Clip 字段

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `start_time` | float | 是 | — | 起始时间 |
| `duration` | float | 是 | — | 持续时间，正数=定长，负数=无限 |
| `keyframes` | array | 是 | — | 关键帧数组（统一关键帧级调控，clip 级 `aspect_ratio` 简写已移除） |

### Keyframe 字段

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `time` | float | 是 | — | 在 clip 内的时间偏移（秒），从 0 开始 |
| `aspect_ratio` | float | 否 | `2.35` | 目标宽高比。`0`=无遮幅（全屏），`2.35`=宽银幕电影，`1.778`=16:9 |

关键帧之间宽高比线性插值。

**恒定遮幅示例：**

```json
{
  "type": "letterbox",
  "clips": [
    {
      "start_time": 0.0,
      "duration": 30.0,
      "keyframes": [
        { "time": 0.0, "aspect_ratio": 2.35 },
        { "time": 30.0, "aspect_ratio": 2.35 }
      ]
    }
  ]
}
```

**动态遮幅 — 开场渐显、终场渐隐：**

```json
{
  "type": "letterbox",
  "clips": [
    {
      "start_time": 0.0,
      "duration": 30.0,
      "keyframes": [
        { "time": 0.0, "aspect_ratio": 0.0 },
        { "time": 1.0, "aspect_ratio": 2.35 },
        { "time": 28.0, "aspect_ratio": 2.35 },
        { "time": 30.0, "aspect_ratio": 0.0 }
      ]
    }
  ]
}
```

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
| `position_mode` | string | 否 | `"relative"` | 音源位置模式：`"relative"` = 每帧跟随**玩家**（玩家位置 + 关键帧 x/y/z 偏移，随身声；接收者=玩家，距离恒 0 **强制无衰减**）；`"absolute"` = 偏移直接作为世界坐标（音源固定，可走空间衰减） |
| `attenuation` | string | 否 | `"none"` | 空间衰减（仅 `absolute` 模式生效）：`"none"` 无衰减 / `"linear"` 线性（默认距离 16 格）/ `"inverse"` 反比。相对模式强制无衰减 |

---

## 7. Event 轨道

`command` 一律写在**关键帧**上（clip 级 command 旧写法已移除）；关键帧可只写 `time` + `command`。片段首尾关键帧允许 command 为空值（仅时间占位，供编辑器绘制片段图形）。

| Clip 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `start_time` | float | 是 | 起始时间 |
| `duration` | float | 是 | `0`=瞬间执行 |
| `event_type` | string | 否 | 固定为 `"command"` |

| Keyframe 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `time` | float | 是 | 在 clip 内的时间偏移（秒），从 0 开始 |
| `command` | string | 否 | 要执行的命令，如 `"/time set 6000"`；空 = 仅占位关键帧 |
| `event_type` | string | 否 | 固定为 `"command"` |

```json
{
  "type": "event",
  "clips": [
    {
      "start_time": 0.0,
      "duration": 5.0,
      "keyframes": [
        { "time": 0.0, "command": "" },
        { "time": 2.0, "command": "/time set 6000" },
        { "time": 5.0, "command": "/say 结束" }
      ]
    }
  ]
}
```

---

## 8. ModEvent 轨道

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `start_time` | float | 是 | — | 起始时间 |
| `duration` | float | 是 | — | 持续时间 |
| `event_type` | string | 是 | — | 自定义事件 ID，如 `"mymod:animation"` |
| `data` | object | 否 | `{}` | 任意自定义数据 |

---

## 9. OVERLAY 轨道（覆盖层：图片 / 字幕 / 淡化）

在屏幕上渲染图片、字幕、全屏淡化或画中画。**支持多条 OVERLAY 轨道同时渲染**（如图片一条轨道、字幕一条轨道，用 `id` 区分），层间按 `z_index` 分层（大者在上）。

### Clip 字段

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `start_time` | float | 是 | — | 起始时间 |
| `duration` | float | 是 | — | 持续时间 |
| `layer_type` | string | 是 | — | `"fade"` 全屏颜色 / `"image"` 图片 / `"subtitle"` 字幕 / `"pip"` 画中画 |
| `path` | string | image 必需 | — | 图片文件名（如 `"my_image.png"`）。**只支持 PNG**，文件放 `<游戏目录>/immersive_cinematics/resource/` 下，用英文命名 |
| `text` | string | subtitle 必需 | — | 字幕文本，`\n` 换行 |
| `color` | string | fade 必需 | — | 淡化颜色，如 `"#000000"` |
| `z_index` | int | 否 | `20` | 层级，越大越靠上（subtitle 建议 30+） |
| `interpolation` | string | 否 | `"linear"` | `"linear"` 线性 / `"smooth"` 平滑样条（Centripetal Catmull-Rom，非均匀关键帧下速度均匀、无折线拐弯） |
| `keyframes` | array | 是 | — | 关键帧数组 |

### Keyframe 字段（坐标 = 屏幕百分比）

**所有坐标都是屏幕宽高的百分比（0 ~ 1）**，与窗口/分辨率无关：

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `time` | float | — | 在 clip 内的时间偏移（秒） |
| `x` | float | `0` | 元素**中心**的水平位置：`0.5` = 屏幕正中，`1` = 中心在屏幕右缘 |
| `y` | float | `0` | 元素**中心**的垂直位置：`0.5` = 屏幕正中，`1` = 中心在屏幕底缘 |
| `scale_x` | float | `1` | 图片：宽度 = 原图分辨率 × 该乘数；**字幕：固定字号后的横向百分比缩放**（`1` = 基准字号原尺寸） |
| `scale_y` | float | `1` | 图片：高度 = 原图分辨率 × 该乘数；**字幕：固定字号后的纵向百分比缩放** |
| `font_scale` | float | `1` | **字幕专用**：字号倍数（`1` = 原版 9px 字号）。矩阵缩放实现，与 MC `/title` 大字同一机制；可与 `scale_x/y` 叠加（最终缩放 = `font_scale × scale_x/y`），字号等比用 `font_scale`，非等比微调用 `scale_x/y` |
| `opacity` | float | `0` | 透明度（`0` = 完全透明，`1` = 不透明）。**淡入/淡出完全由该字段的关键帧表达**，代码层不叠加其他淡化 |

> 字幕缩放是两级语义：`font_scale` 调整基准字号（1.0 = 原版 9px），`scale_x/scale_y` 在固定字号基础上做百分比缩放（与图片的 scale 语义一致），两者可叠加。`fade`/`pip` 的字段见各自文档。**x/y = 0.5 即屏幕居中**；贴边需按元素尺寸/2 折算（如贴左缘 = 元素半宽），避免元素移出屏幕。

### 示例：图片 + 字幕双 OVERLAY 轨道同时渲染

```json
{
  "type": "overlay",
  "id": "overlay_1",
  "clips": [
    {
      "start_time": 0,
      "duration": 12,
      "layer_type": "image",
      "path": "test_image.png",
      "z_index": 20,
      "interpolation": "smooth",
      "keyframes": [
        { "time": 0,  "x": 0.5, "y": 0.5, "scale_x": 0.5, "scale_y": 0.5, "opacity": 0 },
        { "time": 1,  "x": 0.5, "y": 0.5, "scale_x": 0.55, "scale_y": 0.55, "opacity": 1 },
        { "time": 11, "x": 0.5, "y": 0.45, "scale_x": 0.6, "scale_y": 0.6, "opacity": 1 },
        { "time": 12, "x": 0.5, "y": 0.45, "scale_x": 0.5, "scale_y": 0.5, "opacity": 0 }
      ]
    }
  ]
},
{
  "type": "overlay",
  "id": "overlay_2",
  "clips": [
    {
      "start_time": 0,
      "duration": 12,
      "layer_type": "subtitle",
      "text": "副标题文字",
      "z_index": 30,
      "interpolation": "smooth",
      "keyframes": [
        { "time": 0,  "x": 0.5, "y": 0.5, "font_scale": 2.0, "opacity": 0 },
        { "time": 1,  "x": 0.5, "y": 0.5, "font_scale": 2.0, "opacity": 1 },
        { "time": 11, "x": 0.5, "y": 0.6, "font_scale": 2.0, "scale_x": 1.2, "scale_y": 0.8, "opacity": 1 },
        { "time": 12, "x": 0.5, "y": 0.6, "font_scale": 2.0, "opacity": 0 }
      ]
    }
  ]
}
```

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
                "fov": 70, "zoom": 1.0
              },
              {
                "time": 10.0,
                "position": { "dx": 0, "dy": 2, "dz": 0 },
                "yaw": 0, "pitch": 10, "roll": 0,
                "fov": 70, "zoom": 1.0
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
            "keyframes": [
              { "time": 0.0, "aspect_ratio": 0.0 },
              { "time": 1.0, "aspect_ratio": 2.35 },
              { "time": 28.0, "aspect_ratio": 2.35 },
              { "time": 30.0, "aspect_ratio": 0.0 }
            ]
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
            "keyframes": [
              { "time": 0.0, "command": "/weather clear" }
            ]
          }
        ]
      }
    ]
  }
}
```

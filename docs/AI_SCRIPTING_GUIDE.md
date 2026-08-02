# ImmersiveCinematics — AI 脚本编写指南

本文档面向 **AI 编程助手**，目标是让 AI 在不玩游戏、看不到画面的情况下，仅凭 JSON 数值就能写出空间关系正确、节奏自然的电影脚本。

配套参考（字段全量定义）：
- 脚本格式全字段：`docs/SCRIPT_FORMAT.md`
- 触发器类型全表：`docs/TRIGGER_TYPES.md`

---

## 1. 空间与方向（最重要，先读这个）

### 1.1 三个前提

1. **玩家进入游戏默认面朝南方**（yaw = 0）。所有脚本里的位置偏移都以"玩家触发脚本时的位置"为原点。
2. **位置跟随玩家，朝向不跟随玩家**：`dx/dz/dy` 是相对玩家位置的偏移（玩家走动，镜头跟着走）；而 `yaw/pitch/roll` 是**世界方向**，与玩家面朝哪里无关（玩家转身，镜头朝向不变）。
3. 相机没有碰撞箱，可以穿过任何方块，但**镜头穿墙会挡住画面**——运镜路径要规划在开阔空间，或利用俯视/仰视避开遮挡。

### 1.2 位置偏移（relative 模式）

| 值 | 含义 |
|---|---|
| `dx` | 0 = 玩家所在位置；**正数 = 东**，负数 = 西 |
| `dz` | 0 = 玩家所在位置；**正数 = 南**，负数 = 北 |
| `dy` | 0 = 玩家脚底；**2 ≈ 玩家头顶**；正数 = 上，负数 = 下 |

> 例：`{ "dx": 30, "dy": 2, "dz": 0 }` = 玩家东侧 30 格、头顶高度。
> 例：`{ "dx": 0, "dy": 12, "dz": 0 }` = 玩家正上方 12 格（航拍位）。

### 1.3 朝向角

| 值 | 含义 |
|---|---|
| `yaw` | **0 = 南，90 = 西，180 = 北，-90 = 东**。正方向 = 顺时针（从上方俯视，南→西→北→东→南） |
| `pitch` | **正数 = 向下看，负数 = 向上看**。90 = 垂直看地面，-90 = 垂直看天。玩家平视 = 0 |
| `roll` | **正数 = 屏幕顺时针旋转（画面向右倒），负数 = 逆时针**。任何朝向下方向一致 |

**yaw 写法约定：统一用 -180 ~ 180 范围**。东面写 `-90`，不写 `270`；正北可写 `180` 或 `-180`。避免 `-90`/`270` 混用造成 AI 自我矛盾。

### 1.4 方位对照表（拍玩家时的关键公式）

镜头要拍到玩家，**相机在哪个方位，`yaw` 就朝相反方向**（看向玩家）：

| 相机位置（相对玩家） | 看向玩家的 `yaw` | 镜头效果 |
|---|---|---|
| 东侧（dx > 0） | `90`（朝西） | 侧面机位 |
| 西侧（dx < 0） | `-90`（朝东） | 侧面机位 |
| 南侧（dz > 0） | `180`（朝北） | **玩家正面**（玩家面朝南，这是正脸机位） |
| 北侧（dz < 0） | `0`（朝南） | 玩家背影方向（跟拍机位） |
| 东南（dx > 0, dz > 0） | `135` | 斜侧机位 |
| 东北（dx > 0, dz < 0） | `45` | 斜侧机位 |
| 西南（dx < 0, dz > 0） | `-135` | 斜侧机位 |
| 西北（dx < 0, dz < 0） | `-45` | 斜侧机位 |

> 记忆法：相机在**东** → 朝**西**看（yaw 90）；相机在**南** → 朝**北**看（yaw 180）；依此类推，yaw 永远是相机方位的反方向。
>
> **反例（旧脚本的坑）**：相机在玩家北侧（dz = -15）却写 `yaw: 180`（朝北）→ 镜头背对玩家，玩家完全出画。方位和 yaw 对不上 = 拍不到人。

### 1.5 俯仰配合

拍人物时 `pitch` 一般取 **-5 ~ 15**：
- `5` = 轻微俯视（默认舒适机位）
- `0` = 平视（角色对话）
- `-10` = 仰视（显高大、压迫感，适合拍 Boss）
- `40~60` = 高空俯拍（航拍、开场全景）
- `90` = 完全垂直向下（正头顶）
### 1.6 光学参数
| 值 | 含义 |
|---|---|
| `fov` | 视野角。**玩家默认 70**，指南基准 70。**越小 = 越放大（长焦）**，越大 = 越广角。可用范围建议 30 ~ 110 |
| `zoom` | 倍数缩放，正常倍数关系。1 = 原倍，1.5 = 放大 1.5 倍，2 = 放大 2 倍 |

> `fov` 和 `zoom` 都会放大画面，**二选一使用**，不要同时调（效果叠加会失控）。

---

## 2. 运镜速度（五档）

位移速度 = 两关键帧位置距离 ÷ 时间差。写脚本前先算好，避免镜头忽快忽慢。

| 档位 | 速度 | 适用场景 |
|---|---|---|
| 慢推 | < 1 格/秒（如 0.5） | 极慢推进、梦幻感、片头 |
| 踱步 | 1 格/秒 | 缓慢移动、庄重感（"踱步"感） |
| **适中** | **2 格/秒** | **标准运镜，默认选择** |
| 稍快 | 3 格/秒 | 轻快但不晕，推进/横移常用 |
| 快 | 4 格/秒 | 快速运动，仍有控制感 |
| 非常快 | 5 格/秒 | 极快（动作戏、掠过），**慎用**，超过 5 会眩晕 |

**旋转速度参考**：
- `yaw` 摇头：舒适 ≤ 45°/秒；明显扫视 60~90°/秒
- `pitch` 抬头/低头：≤ 30°/秒

**方向约定**：
- `yaw` **增大** = 镜头顺时针转头（南→西→北→东）
- `yaw` **减小** = 镜头逆时针转头
- `pitch` 增大 = 向下压，减小 = 向上抬

---

## 3. 脚本能写哪些值

### 3.1 结构

```json
{
  "meta": { "...": "身份 + 行为开关 + 触发器" },
  "timeline": { "total_duration": 30.0, "tracks": [ "...轨道们..." ] }
}
```

### 3.2 meta 必填

| 字段 | 说明 |
|---|---|
| `id` | 脚本唯一 ID，仅允许字母/数字/下划线，≤32 字符 |
| `name` | 显示名称 ≤50 字符 |
| `author` | 作者 ≤30 字符 |
| `version` | **固定 3** |

常用行为开关（默认值合理，不写也行）：`block_keyboard: true`、`block_mouse: true`、`hide_hud: true`、`hide_arm: true`、`suppress_bob: true`、`skippable: true`、`interruptible: true`、`hold_at_end: false`、`pause_when_game_paused: true`。

> **`hide_skip_hud` 必读**：长按跳过键的提示（右下角图标+进度环）由 `hide_skip_hud` 控制——**不写时跟随 `hide_hud` 被隐藏**。凡是 `hide_hud: true` 的脚本，想保留跳过提示就必须显式写 **`"hide_skip_hud": false`**，否则玩家看不到"按 C 跳过"的提示。

### 3.3 timeline

| 字段 | 说明 |
|---|---|
| `total_duration` | 总时长秒。正数 = 定长；负数 = 无限循环 |
| `tracks` | 轨道数组。CAMERA 最多 1 条，LETTERBOX/EVENT 建议 1 条，AUDIO/OVERLAY/MOD_EVENT 不限 |

### 3.4 CAMERA 轨道（核心）

**clip 级字段**：

| 字段 | 说明 |
|---|---|
| `start_time` | 在全局时间线的起点（秒） |
| `duration` | 持续时长。正数 = 定长；负数 = 无限 |
| `transition` | `"cut"` = 硬切（默认）；`"morph"` = 与下一段平滑过渡 |
| `transition_duration` | **仅 morph 时有效**，过渡时长秒。cut 时不要写 |
| `position_mode` | `"relative"` = 相对玩家（默认，推荐）；`"absolute"` = 世界坐标（写 `x/y/z`，用于固定机位） |
| `loop` / `loop_count` | 循环播放（`loop: true` 时 `loop_count: -1` = 无限循环） |
| `curve` | 贝塞尔路径（可选，见 SCRIPT_FORMAT.md） |
| `cam_tracking_look_at` | `"coordinate"`（注视固定坐标）/ `"entity"`（注视玩家）——覆盖 yaw/pitch，镜头始终对准目标 |
| `cam_tracking_follow` | `"entity"` = 跟随玩家移动（位置 = 玩家 + 偏移） |
| `cam_breath_enabled` | 呼吸扰动（手持感），配 `cam_breath_intensity`（0.05~0.1）和 `cam_breath_seed` |

**keyframe 字段**（每个关键帧都是完整镜头状态）：

```json
{
  "time": 0,
  "position": { "dx": 30, "dy": 2, "dz": 0 },
  "yaw": 90,
  "pitch": 5,
  "roll": 0,
  "fov": 70,
  "zoom": 1.0
}
```

规则：
- `time` 是 **clip 内**偏移（从 0 到 duration），必须严格递增
- 关键帧之间所有值**匀速线性插值**（两点定一段运动）
- 至少 1 个关键帧；想表现"静止"就用首尾两个相同值

### 3.5 其他轨道（一句话速览）

| 轨道 | 用途 |
|---|---|
| `LETTERBOX` | 宽银幕黑边。keyframe 的 `aspect_ratio`：0 = 无黑边，2.35 = 电影宽银幕 |
| `AUDIO` | 播放音频（`sound` + `source: "file"`），keyframe 控制 `volume` 和空间 `x/y/z`。**音频文件必须用英文命名**（中文名在 Windows 下会解码失败） |
| `EVENT` | 时间点执行服务端命令（keyframe 的 `command`），如 `"say 开始！"` |
| `OVERLAY` | 覆盖层：`fade`（全屏颜色）/ `image`（图片）/ `subtitle`（字幕）/ `pip`（画中画） |
| `MOD_EVENT` | 第三方模组自定义事件 |

### 3.6 触发器（什么时候播）

`meta.triggers` 数组，常用：

| 类型 | 触发时机 | 关键条件字段 |
|---|---|---|
| `login` | 玩家登录 | 无 |
| `location` | 进入区域 | `dimension` / `position`+`radius` / `corner1`+`corner2` |
| `structure` | 进入结构 | `structure`（如 `"village"`）+ `radius` |
| `advancement` | 获得进度 | `advancement` |
| `biome` | 进入群系 | `biome` |
| `entity_kill` | 击杀实体 | `entity` |
| `item_craft` | 合成物品 | `item` |
| `custom` | 外部调用 | `event_id` |

通用字段：`repeatable`（可重复触发）、`delay`（延迟秒）、`on_enter`（仅进入时触发，位置类用）。完整 16 种见 `docs/TRIGGER_TYPES.md`。

---

## 4. 常用运镜配方（写精细化脚本的组合套路）

| 配方 | 怎么做 |
|---|---|
| **开场推进** | 相机东侧 20~30 格（dx=25, dy=2），yaw=90，pitch=5，2~3 格/秒推进到 8~10 格 |
| **环绕** | 位置绕玩家转（dx/dz 按方位表变化），yaw 同步按对照表保持看向玩家 |
| **摇头（自转）** | 位置不动，yaw 单调增减。例：90→135→180（顺时针扫过，45°/段） |
| **拉升** | dy 1→12，pitch 5→55，fov 70→80（低机位升空展开全景） |
| **俯拍降落** | dy 12→3，pitch 55→15（航拍收回，回到人物） |
| **变焦特写** | 位置不动，fov 70→50（或 zoom 1→2），pitch 略压（特写情绪） |
| **荷兰角** | roll 0→15（紧张、不安感），配 zoom 1.2~1.5，慎用 |
| **注视追踪** | `cam_tracking_look_at: "entity"`，位置走关键帧，镜头自动锁定玩家 |
| **手持感** | `cam_breath_enabled: true` + `cam_breath_intensity: 0.05`，让固定机位"活"起来 |

**精细化的关键**：
1. 一段 motion 至少 3 个关键帧（起→中→止），中间帧做缓急变化，比两点直线"有呼吸"
2. 段落之间用 `morph` 过渡衔接，避免硬切跳变（morph 只影响两段之间，写在下段 clip 上）
3. 特写/情绪段落用变焦 + 微俯 + 慢速（1 格/秒），全景段落用快一点（2~3 格/秒）
4. 长脚本分段设计：开场全景 → 中段叙事特写 → 结尾收回

---

## 5. 完整示例

### 示例 A：登录开场（15 秒，相对模式）

```json
{
  "meta": {
    "id": "welcome_intro",
    "name": "欢迎开场",
    "author": "AI",
    "version": 3,
    "description": "登录后：东侧推进 + 环绕 + 变焦特写，宽银幕黑边",
    "triggers": [
      { "id": "on_login", "type": "login", "repeatable": true, "delay": 1.0, "conditions": {} }
    ],
    "block_keyboard": true,
    "block_mouse": true,
    "hide_hud": true,
    "hide_arm": true,
    "suppress_bob": true,
    "skippable": true,
    "hold_at_end": false,
    "hide_skip_hud": false
  },
  "timeline": {
    "total_duration": 15.0,
    "tracks": [
      {
        "type": "CAMERA",
        "clips": [
          {
            "start_time": 0,
            "duration": 6,
            "transition": "cut",
            "position_mode": "relative",
            "keyframes": [
              { "time": 0,   "position": { "dx": 24, "dy": 2, "dz": 0 },  "yaw": 90,  "pitch": 5,  "roll": 0, "fov": 70, "zoom": 1.0 },
              { "time": 3,   "position": { "dx": 18, "dy": 2, "dz": 0 },  "yaw": 90,  "pitch": 5,  "roll": 0, "fov": 68, "zoom": 1.1 },
              { "time": 6,   "position": { "dx": 10, "dy": 2, "dz": 0 },  "yaw": 90,  "pitch": 8,  "roll": 0, "fov": 65, "zoom": 1.2 }
            ]
          },
          {
            "start_time": 6,
            "duration": 5,
            "transition": "morph",
            "transition_duration": 1.0,
            "position_mode": "relative",
            "keyframes": [
              { "time": 0, "position": { "dx": 10, "dy": 2, "dz": 0 },  "yaw": 90, "pitch": 8,  "roll": 0, "fov": 65, "zoom": 1.2 },
              { "time": 5, "position": { "dx": 6,  "dy": 2, "dz": 6 },  "yaw": 135, "pitch": 10, "roll": 0, "fov": 62, "zoom": 1.3 }
            ]
          },
          {
            "start_time": 11,
            "duration": 4,
            "transition": "cut",
            "position_mode": "relative",
            "keyframes": [
              { "time": 0, "position": { "dx": 6, "dy": 2, "dz": 6 }, "yaw": 135, "pitch": 10, "roll": 0, "fov": 62, "zoom": 1.3 },
              { "time": 4, "position": { "dx": 5, "dy": 2, "dz": 5 }, "yaw": 135, "pitch": 12, "roll": 0, "fov": 55, "zoom": 1.5 }
            ]
          }
        ]
      },
      {
        "type": "LETTERBOX",
        "clips": [
          {
            "start_time": 0,
            "duration": 15,
            "keyframes": [
              { "time": 0,  "aspect_ratio": 0.0 },
              { "time": 1,  "aspect_ratio": 2.35 },
              { "time": 14, "aspect_ratio": 2.35 },
              { "time": 15, "aspect_ratio": 0.0 }
            ]
          }
        ]
      }
    ]
  }
}
```

拆解（验证空间逻辑）：
- 第 1 段：相机**东侧 24 格**（dx=24），`yaw=90`（朝西）→ 正对玩家 ✓；24→10 格 = 14 格 / 6 秒 ≈ 2.3 格/秒（适中档）✓；fov 70→65 轻微变焦
- 第 2 段：东侧移到**东南侧**（dx=6, dz=6），yaw 90→135（东→东南朝向，对照表 135 ✓），morph 平滑衔接
- 第 3 段：fov 62→55 + zoom 1.3→1.5 特写收尾，pitch 12 微俯
- LETTERBOX：开场 1 秒拉出宽银幕，结束 1 秒收回

### 示例 B：环绕 + 拉升 + 俯拍（18 秒，展示完整运镜组合）

```json
{
  "meta": {
    "id": "orbit_showcase",
    "name": "环绕与拉升",
    "author": "AI",
    "version": 3,
    "triggers": [
      { "id": "on_login", "type": "login", "repeatable": true, "delay": 1.0, "conditions": {} }
    ],
    "block_keyboard": true,
    "block_mouse": true,
    "hide_hud": true,
    "hide_arm": true,
    "suppress_bob": true,
    "skippable": true,
    "hold_at_end": false,
    "hide_skip_hud": false
  },
  "timeline": {
    "total_duration": 18.0,
    "tracks": [
      {
        "type": "CAMERA",
        "clips": [
          {
            "start_time": 0,
            "duration": 8,
            "transition": "cut",
            "position_mode": "relative",
            "keyframes": [
              { "time": 0, "position": { "dx": 12, "dy": 2, "dz": 0 },  "yaw": 90,  "pitch": 5, "roll": 0, "fov": 70, "zoom": 1.0 },
              { "time": 4, "position": { "dx": 0,  "dy": 2, "dz": -12 }, "yaw": 0,   "pitch": 5, "roll": 0, "fov": 70, "zoom": 1.0 },
              { "time": 8, "position": { "dx": -12, "dy": 2, "dz": 0 },  "yaw": -90, "pitch": 5, "roll": 0, "fov": 70, "zoom": 1.0 }
            ]
          },
          {
            "start_time": 8,
            "duration": 5,
            "transition": "morph",
            "transition_duration": 1.0,
            "position_mode": "relative",
            "keyframes": [
              { "time": 0, "position": { "dx": -12, "dy": 2, "dz": 0 },  "yaw": -90, "pitch": 5,  "roll": 0, "fov": 70, "zoom": 1.0 },
              { "time": 5, "position": { "dx": 0,   "dy": 12, "dz": 0 },  "yaw": 180, "pitch": 55, "roll": 0, "fov": 80, "zoom": 1.0 }
            ]
          },
          {
            "start_time": 13,
            "duration": 5,
            "transition": "cut",
            "position_mode": "relative",
            "keyframes": [
              { "time": 0, "position": { "dx": 0, "dy": 12, "dz": 0 }, "yaw": 180, "pitch": 55, "roll": 0, "fov": 80, "zoom": 1.0 },
              { "time": 5, "position": { "dx": 0, "dy": 3,  "dz": 8 }, "yaw": 180, "pitch": 20, "roll": 0, "fov": 70, "zoom": 1.0 }
            ]
          }
        ]
      }
    ]
  }
}
```

拆解：
- 第 1 段：**环绕半圈**。东(dx=12, yaw=90) → 北(dz=-12, yaw=0) → 西(dx=-12, yaw=-90)，每 4 秒 90°，位置-朝向严格按对照表同步 ✓
- 第 2 段：**拉升**。西侧 12 格 → 头顶 12 格，yaw -90→180（转向北）+ pitch 5→55（压向地面），fov 70→80 展开全景
- 第 3 段：**俯拍降落**。头顶 → 南侧 8 格（dz=8, yaw=180 朝北 = 正对玩家 ✓），pitch 55→20 回到人物

---

## 6. 常见错误清单（AI 必读，写完自查）

| # | 错误 | 正确做法 |
|---|---|---|
| 1 | **背对玩家**：相机在北侧（dz<0）却写 yaw=180 | 相机在哪侧，yaw 朝反方向。查 §1.4 对照表 |
| 2 | yaw 混用 `-90` 和 `270` | 统一 -180~180，东写 `-90` |
| 3 | `transition: "cut"` 还写 `transition_duration` | 该字段只对 morph 有效，cut 时删掉 |
| 4 | `fov` 和 `zoom` 同时调大 | 二选一，避免放大叠加失控 |
| 5 | 位移跨度 ÷ 时长 > 5 格/秒 | 会晕。按 §2 五档控制（默认 2 格/秒） |
| 6 | `pitch` 超出 ±90 | 无效。俯视 0~90，仰视 0~-90 |
| 7 | `roll` 无节制使用 | 只在特写/情绪段用，±30 以内，用完归 0 |
| 8 | keyframe 的 `time` 不递增或重复 | 必须严格递增（解析器会报错） |
| 9 | clip 之间时间错乱（start_time 不接上一段结尾） | 衔接：下一段 start_time = 上一段 start_time + duration；留白 = 玩家视角空档 |
| 10 | relative 模式写了 `x/y/z`（或反之） | relative 用 `dx/dy/dz`，absolute 用 `x/y/z`，不要混 |
| 11 | 特写后忘记恢复 | 段落结尾把 fov 回到 70、zoom 回到 1、roll 回到 0 |
| 12 | `duration` 写 0 | 非法。正数 = 定长，负数 = 无限 |
| 13 | 音频/图片资源用中文或其他非 ASCII 命名 | Windows 下 stb 解码走 ANSI 代码页，中文路径打不开。**资源文件统一英文命名**（如 `second_waltz.ogg`） |

---

## 7. 交付前校验清单

- [ ] `meta.version = 3`，`id` 只含字母/数字/下划线
- [ ] `total_duration` 等于最后一段 clip 的结束时间（或为负 = 无限）
- [ ] 每个 clip：`duration ≠ 0`，CAMERA 至少 1 个关键帧
- [ ] 每个 clip 内：keyframe 的 `time` 严格递增
- [ ] 逐段核对：相机方位 ↔ yaw 是否匹配（§1.4 对照表）
- [ ] 逐段核算：位移距离 ÷ 时长 ∈ 五档合理范围
- [ ] pitch 在 ±90 内；roll 用后归零；fov 30~110；zoom 1~2
- [ ] 特写段落结尾恢复 fov 70 / zoom 1 / roll 0
- [ ] triggers 的 `type` 拼写正确、条件字段齐全（见 TRIGGER_TYPES.md）

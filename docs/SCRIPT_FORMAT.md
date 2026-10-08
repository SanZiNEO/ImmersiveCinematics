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

## 0. 脚本目录组织约定

脚本统一放在 `<游戏目录>/immersive_cinematics/scripts/`。支持**子文件夹组织**——按章节/场景/剧情线建文件夹放脚本，加载时递归遍历（深度 ≤ 5）。编辑器脚本列表显示相对路径（如 `chapter1/boss_fight`）；`/icinematics` 命令 Tab 补全显示**命令标识**：目录名转命名空间 + 冒号 + 文件名（如 `chapter1:boss_fight`）。

```
immersive_cinematics/
├── scripts/
│   ├── intro/            # 序章脚本
│   │   ├── welcome.json
│   │   └── village.json
│   ├── chapter1/         # 第一章脚本
│   │   └── boss_fight.json
│   └── showcase_01.json  # 根目录平铺也可以（兼容现状）
└── resource/
    ├── intro/            # 音频/图片按同样结构组织
    │   └── bgm.ogg
    ├── lang/             # 文本资源（字幕 / 描述的 @lang: 引用，见 §12）
    │   ├── zh_cn.json
    │   └── en_us.json
    └── overlay.png       # 根目录平铺也可以
```

- 脚本 `id` 仍是 `meta.id`（全局唯一），子目录**只是文件组织**，不参与 id 语义；同 `id` 冲突时按相对路径提示。
- 命令用“目录:文件名”标识定位文件：`/icinematics play chapter1:boss_fight`（Tab 补全会给出建议；根目录脚本直接写 `showcase_01`）。
- 音频/图片已有子路径支持（`resource/` 下 `path` 写 `"sub/bgm.ogg"` 即可）。
- 文本资源（`resource/lang/<语言>.json`）见 [§12](#12-文本资源与多语言langkey)——字幕 `text` 与 `meta.description` 支持 `@lang:<key>` 引用。

---

## 1. `meta` — 脚本元信息

### 1a. 身份标识

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | string | 是 | — | 脚本唯一 ID，仅允许 `[a-zA-Z0-9_]`，最长 32 字符 |
| `name` | string | 是 | — | 脚本显示名称，最长 50 字符 |
| `author` | string | 是 | — | 作者名，最长 30 字符 |
| `version` | int | 是 | — | 固定为 `3`（仅支持版本 3） |
| `description` | string | 否 | `""` | 脚本描述文本；支持 `@lang:<key>` 引用（见 [§12](#12-文本资源与多语言langkey)） |
| `dimension` | string | 否 | `""` | 限制脚本只在指定维度可用；空 = 不限制 |
| `preload` | boolean | 否 | `true` | 脚本级区块预加载开关：`false` 关闭本脚本的预加载（不发任何预载请求）；`true`/缺省 = 跟随全局配置启用。仅当脚本存在**非空 CAMERA 轨道**时才实际触发预加载，无 CAMERA 轨道（纯 HUD/字幕/事件等）不发送预载请求 |
| `listener` | string | 否 | `"player"` | 音频听者：`"player"`=默认，电影相机下仍以玩家视角听音；`"camera"`=听者切换到镜头位置，环境音/方块音/水声等按相机采样 |
| `camera_mob_spawn` | boolean | 否 | `false` | 脚本级相机区域刷怪开关：是否允许相机锚点附近按原版规则自然刷怪 |
| `camera_mob_radius` | int | 否 | `2` | 脚本级相机刷怪半径（区块），范围 1~16 |
| `camera_mob_ai` | boolean | 否 | `false` | 脚本级相机区实体 AI 开关：`true`=实体正常 tick/AI，`false`=仅静态布景 |


### 1b. 运行时行为 (RuntimeBehavior)

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `block_keyboard` | boolean | `true` | 播放期间屏蔽键盘输入 |
| `block_mouse` | boolean | `true` | 播放期间屏蔽鼠标输入 |
| `block_mob_ai` | boolean | `false` | 清除附近怪物 AI（性能消耗较大，慎用） |
| `hide_hud` | boolean | `true` | 隐藏全部 HUD |
| `hide_arm` | boolean/null | `null` | **三态**：隐藏第一人称手臂与手持物品。`null`=跟随 `hide_hud`，`true/false`=强制 |
| `suppress_bob` | boolean/null | `null` | **三态**：抑制视角晃动。`null`=跟随 `hide_hud` |
| `suppress_distortion` | boolean/null | `null` | **三态**：抑制反胃/下界传送门等画面扭曲。`null`=跟随 `hide_hud` |
| `hide_chat` | boolean/null | `null` | `null`=跟随 `hide_hud`，`true/false`=强制显隐 |
| `hide_scoreboard` | boolean/null | `null` | 同上 |
| `hide_action_bar` | boolean/null | `null` | 同上 |
| `hide_title` | boolean/null | `null` | 同上 |
| `hide_subtitles` | boolean/null | `null` | 同上 |
| `hide_hotbar` | boolean/null | `null` | 同上 |
| `hide_crosshair` | boolean/null | `null` | 同上 |
| `hide_bossbar` | boolean/null | `null` | 同上 |
| `hide_skip_hud` | boolean/null | `null` | 同上；控制长按跳过提示（右下角图标+进度环） |
| `hud_layers` | object | `{}` | 模组/自定义 HUD 层的显隐覆盖：`{ "modid:layer": true/false }`，`true`=隐藏、`false`=显示 |
| `hard_hide_hud` | boolean/null | `null` | **三态·仅 Forge**：强硬隐藏模式。`true`=除原版 HUD 与注册表 overlay 外，**连"不走 overlay 注册表、直接在 `RenderGuiEvent.Pre` 里自绘"的第三方 HUD 也一起隐藏**（接管整段 HUD 渲染，再按白名单重画）；`null`（缺省）/`false`=normal，行为与不带此字段完全一致。仅当 `hide_hud` 生效时才接管（`hide_hud=false` 时不接管）。**与 `hide_*` 的区别**：null 不回落 `hide_hud`——缺省即 normal。Fabric 无对应语义（字段被解析但不生效） |
| `render_player_model` | boolean | `true` | 是否渲染玩家模型（第三人称时） |
| `pause_when_game_paused` | boolean | `true` | 游戏暂停时是否暂停过场动画 |
| `interruptible` | boolean | `true` | 是否允许被其他脚本打断 |
| `skippable` | boolean | `true` | 是否允许玩家长按跳过 |
| `hold_at_end` | boolean | `false` | 播放完毕后是否停留在最后一帧 |
| `macro_loop` | boolean | `false` | 宏观循环开关：**从 `a` 到 `b` 重复执行**（循环 = 播放控制层的重复执行，不是时间折叠）。区间 `[a,b]` = `timeline.loop_start` / `loop_end`（缺省 `a=0`、`b=宏观末端`）；宏观末端 = 各片段展开结束时刻的最大值（含片段自身循环展开），**不是** `total_duration`。缺省 `loop_end` 时若存在永不结束片段 → 末端不存在 → 不循环（时间照直走）。圈数见 `macro_loop_count`。与 `hold_at_end` **互斥**（无限圈数下 `hold_at_end` 不会生效；校验器告警）。服务端 EVENT 不参与循环（仅客户端表现层） |
| `macro_loop_count` | int | `-1` | 宏观循环次数：`-1`（缺省）= 无限重复，脚本不再自然结束（退出走跳过/打断路径）；正整数 N = 从 `a` 到 `b` 重复 N 圈后自然结束（总播放时长 = `a + N × (b − a)`）。`0` 非法（运行时按 1 处理） |
| `macro_loop_mode` | enum | `repeat` | 宏观循环模式：`repeat`=折回起点重复 / `pingpong`=往复折返。**第一版只实现 `repeat`**，`pingpong` 按 `repeat` 播放（校验器会告警） |
| `priority` | int | `0` | 播放优先级，数值越大越优先；**仅用于队列内排序**（优先级不能大于打断——不可打断脚本永不被打断，新请求一律排队） |
| `skip_vote_ratio` | int | 无（用全局配置） | **可选**。多人跳过投票所需比例（10~100，百分比），仅当所有看过此脚本的观众投票后才生效。缺省/非法值 → 回落到全局配置 `skipVoteRatio`（默认 100 = 全票）。例：`50` = 半数观众投跳过即强制停止 |

> 运行时行为在脚本播放激活期间生效，**不要求存在活跃 CAMERA clip**；纯 HUD/字幕/手臂等行为的显隐只判断当前是否处于电影播放状态。
>
> `hard_hide_hud` 是**整段播放级**的模式开关（不做逐帧切换），并集口径与其它行为开关一致：任一活跃实例显式声明 `true` 即生效。
> 开启后其他模组在 `RenderGuiEvent.Pre` 里的**非绘制副作用**（状态准备、缓存刷新等）也会一并被跳过——这是"接管整段渲染"的固有代价。
> 细节与取舍见 `plans/0.3.6/hud-hard-hide.md`（§落地标注）。


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
| `on_enter` | boolean | 否 | `false` | 仅位置类触发器有效：仅在首次进入区域时触发，已在区域内不重复；离开区域后复位，可再次进入触发（0.3.6 修复）。播放期间不更新状态机，跳过镜头不会立刻重播 |
| `exit_buffer` | number | 否 | `0` | 配合 `on_enter`：玩家离开触发区域多少格后才标记为"已离开"，防止边界抖动。外扩按 `corner1` 为最小角、`corner2` 为最大角计算，角落点写反会让该轴外扩变缩小（见 `plans/0.3.6/feedback-0.3.5/03-exit-buffer-corner-order.md`） |
| `requires` | array | 否 | `[]` | **前置依赖**：AND 语义，全部满足才允许触发。旧写法为前置脚本 id 字符串数组，等价于“脚本**播放完成**”（开始播放且结束播放，跳过/打断/自然播完都算）；也支持对象型前置条件 `{ "type": "script_played"/"script_started"/"script_completed", "script": "xxx" }` 或其他模组注册的自定义类型。示例：`"requires": ["script_a"]` |

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
| `loop_start` | float | 否 | **宏观循环区间起点 `a`**（秒，缺省 `0`），仅在 `meta.macro_loop=true` 时被读取 |
| `loop_end` | float | 否 | **宏观循环区间终点 `b`**（秒，缺省 = 宏观末端）。声明后即按 `[loop_start, loop_end]` 重复（可用于子区间循环）；`≤ loop_start` 或 `a` 之后区间为空时不循环 |

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
| `"adjust"` | 画面颜色调整（`scope` = `master` 作用于合成输出 / `lane` 作用于指定相机轨的画面）：曝光 / 对比度 / 高光 / 阴影 / 白 / 黑 / 色相 / 饱和度 / 自然饱和度 / 亮度 / 色温 / 色调 / R/G/B 通道系数 / 灰度 / 反相，全部参数可关键帧 |

---

## 4. Camera 轨道

### Clip 字段

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `start_time` | float | 是 | — | 全局时间线起始点（秒） |
| `duration` | float | 是 | — | 持续时间，正数=定长，负数=无限 |
| `transition` | string | 否 | `"cut"` | `"cut"`=硬切，`"morph"`=线性过渡 |
| `transition_duration` | float | 否 | `0.5` | morph 过渡时长（秒） |
| `dimension` | string | 否 | `""` | CAMERA clip 声明的维度；与玩家当前维度不同时仅日志提示（0.4.0 预留，不自动切换） |
| `orient` | string | 否 | `"manual"` | 朝向模式：`"manual"`=用关键帧角度；`"tangent"`=沿路径切线方向看，可配合 `yaw_offset`/`pitch_offset` |
| `yaw_offset` | float | 否 | `0` | `orient=tangent` 时的水平偏移角（度） |
| `pitch_offset` | float | 否 | `0` | `orient=tangent` 时的垂直偏移角（度） |
| `loop` | boolean | 否 | `false` | 是否循环播放（生命周期开关） |
| `loop_count` | int | 否 | `-1` | 循环次数：`-1`=无限循环；正整数=播 N 个周期后停在末帧；`0` 非法（运行时按 1 处理） |
| `loop_mode` | string | 否 | `"repeat"` | 循环时间映射：`"repeat"`=从头到尾重复；`"pingpong"`=往复折返（监控来回摇） |
| `curve` | object | 否 | `null` | 贝塞尔路径曲线 |
| `cam_breath_enabled` | boolean | 否 | `false` | 呼吸扰动总开关 |
| `cam_breath_type` | string | 否 | `"perlin"` | 扰动类型：`perlin`（默认，平滑手持感）/ `perlin_axis`（每轴独立、更随机）/ `sine`（规律正弦"呼吸感"）/ `trauma`（冲击衰减，受击镜头晃动） |
| `cam_breath_intensity` | float | 否 | `0.05` | 振幅/力度（约等于角度） |
| `cam_breath_seed` | int | 否 | `0` | 波形种子（决定形状/相位，同 seed + 同时间 → 同抖动，重放一致） |
| `cam_breath_speed` | float | 否 | `1.0` | 时间推进速度，越大晃得越快 |
| `cam_breath_trauma` | float | 否 | `1.0` | 仅 `cam_breath_type=trauma`：初始冲击强度 0~1 |
| `cam_breath_decay` | float | 否 | `0.5` | 仅 `cam_breath_type=trauma`：强度每秒衰减速率 |
| `keyframes` | array | 是 | — | 关键帧数组，至少 1 个 |

> **v3 迁移**：`position_mode` 已迁移到**关键帧级**；旧 `cam_tracking_follow*`/`cam_tracking_look_at*` 字段已由关键帧级 `follow`/`look_at` 系列字段取代。clip 级不再支持这些旧字段（保留会被 validate 报废弃提示）。

> **0.3.6 起：插值 = 匀速线性 + 编辑器烘焙缓动**。clip 级 `interpolation` 字段（旧 `"linear"` / `"smooth"`）已**移除**——旧 `"smooth"`（Centripetal Catmull-Rom 样条，会改变运动轨迹）一并退役，不留双轨；保留该字段会被 validate 报出。运行时关键帧之间一律**匀速线性插值**（两点定一段匀速直线），没有隐式数学。想要"缓动 / 速度曲线"：在编辑器里选速度曲线，由**编辑器按曲线采样、把补出的关键帧写进脚本**（脚本里存的是显式关键帧），运行时照常线性求值——运动与脚本内容完全一致。

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
- **永不结束的片段 = 时间轴终点**：其后的其他片段不播放（0.3.6 起，替代旧"特写覆盖"语义）。
- **片段允许时间重叠**（0.3.6 起）：重叠区按轨道层级 → 轨道内 clip 顺序分层，后面的 clip 覆盖前面 clip 的上层；叠化 = 上一个 clip 末尾帧复制延长（hold）+ 下一个 clip 首帧复制延长，交叉窗口内 opacity 关键帧。

### 宏观循环（`meta.macro_loop`）

**循环 = 播放控制层的重复执行，不是时间折叠**：`macro_loop: true` 表示"从 `a` 到 `b` 这一段重复执行 N 圈"。

- **区间 `[a,b]`**：`a` = `timeline.loop_start`（缺省 `0`），`b` = `timeline.loop_end`（缺省 = 宏观末端）。`b` 未声明时，存在永不结束片段（`duration<0` 或 `loop_count=-1`）→ 宏观末端不存在 → **不循环**（时间照直走）；显式声明 `loop_end` 则区间由作者给定，按声明值重复。
- **圈内局部时间**：`t < a` 直通；`t ≥ a` → `a + (t − a) mod (b − a)`。圈边界（局部时间回绕）即折返点，EVENT 轨的玩家行走目标索引按圈复位。
- **圈数 `macro_loop_count`**：`-1`（缺省）= 无限重复（脚本不再自然结束，退出走跳过/打断）；正整数 N = 重复 N 圈后自然结束，总播放时长 = `a + N × (b − a)`。
- 相机 / overlay / 音频等执行侧只看到"圈内局部时间"，不感知循环；服务端 EVENT 不参与循环。
- **编辑器预览不做循环展开**：预览按真实时间线播放（既不展开也不折叠）。

```json
"meta": { "macro_loop": true, "macro_loop_count": 3 },
"timeline": { "total_duration": 20, "loop_start": 4, "loop_end": 12, "tracks": [ ... ] }
```
上例 = 播放到 `t=4` 后，在 `[4,12)` 之间重复 3 圈（`t=28` 结束）。

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
| `look_at_target` | object | 否 | `null` | `look_at=coordinate` 时的相对目标对象，优先级高于散字段绝对坐标。支持：`{x,y,z}` 绝对点、`{dx,dy,dz}` 相对触发点、`{relative_to:<selector>,dx,dy,dz}` 相对实体、`{relative_to:"coordinate",relative_x/y/z,dx,dy,dz}` 相对固定坐标 |
| `selector_refresh` | float | 否 | `1.0` | 目标存活时的重新扫描间隔（秒）。**目标丢失**（死亡/移除/未加载）后不受此值限制：锁进入搜索态并保持最后画面，按固定节奏（0.2 秒）持续重找，找到即恢复。作用于本关键帧所有 selector 字段 |
| `selector_switch_while_alive` | bool | 否 | `true` | `true`=目标存活时也按 `selector_refresh` 扫描并切到新的最近目标；`false`=当前目标活着就不换。**目标丢失后不受此项限制**：进入搜索态持续重找，解析到任意符合规则的目标就立即恢复 |
| `selector_switch_interval` | float | 否 | = `selector_refresh` | 两次真实切换之间的最小间隔（秒），与扫描频率无关：扫描到新目标但距上次切换不足 N 秒 → 继续用旧目标。`0`=不限制。缺省 = `selector_refresh` |
| `selector_switch_smooth` | float | 否 | `0.0` | 目标真的切换时（含丢失后恢复到新目标），注视点/跟随位置在 N 秒内以 smoothstep 过渡；`0`=硬切。作用于本关键帧所有目标点 |
| `yaw_base` | string | 否 | `"world"` | `yaw` 的基准方向：`"world"`=0 世界角（`yaw` 即世界朝向）；`"entity"`=实体视线水平角（用 `yaw_base_selector`）；`"line"`=从 `yaw_base_from` 到 `yaw_base_to` 的连线水平角。此时 `yaw` 为相对基准的偏移 |
| `pitch_base` | string | 否 | `"world"` | `pitch` 的基准俯仰：同上，`entity` 取实体视线俯仰、`line` 取连线垂直角 |
| `yaw_base_selector` | string | 否 | `"@p"` | `yaw_base/pitch_base=entity` 时的实体选择器 |
| `yaw_base_from` / `yaw_base_to` | string | 否 | `""` | `yaw_base/pitch_base=line` 时的两个端点选择器 |
| `yaw` | float | 是 | — | 偏航角（度）。0=南，90=西，±180=北。`look_at != none` 时被覆盖；使用 `yaw_base` 时表示相对基准的偏移 |
| `pitch` | float | 是 | — | 俯仰角（度）。正=向下看。`look_at != none` 时被覆盖 |
| `roll` | float | 是 | — | 翻滚角（度）。正=屏幕顺时针（画面向右倒），任何朝向一致 |
| `fov` | float | 是 | — | 视场角（度），标准 70 |
| `zoom` | float | 否 | `1.0` | 缩放倍率，`>1`=放大 |
| `opacity` | float | 否 | `1.0` | 合成不透明度（`0`~`1`）。叠化 = 两个 clip 的 opacity 交叉关键帧 |
| `dest` | object | 否 | 全屏 `{x:0,y:0,w:1,h:1}` | 目标区域：本 clip 画面铺到屏幕的归一化矩形，格式见下方"合成参数" |
| `source` | object | 否 | 全幅 `{x:0,y:0,w:1,h:1}` | 取材区域：从本 clip 画面内取哪一块的归一化矩形，格式见下方"合成参数" |

**目标选择器**（`follow_selector` / `look_at_selector`）：

| 写法 | 行为 |
|------|------|
| `@p` / `@s` | 玩家 |
| `@e` | 离相机最近的活实体 |
| `@e[type=minecraft:iron_golem]` | 类型过滤后就近（模组 boss 用其注册 id） |
| `@e[type=#your_mod:units]` | 实体类型 tag 过滤（推荐：只选单位、排除投掷物）。tag 由数据包/模组提供 |
| `@e[type=!#minecraft:impact_projectiles]` | 反向实体类型 tag：排除投掷物等 |
| `@e[name=自定义名]` | 命名牌名字过滤后就近 |
| `uuid:xxxxxxxx-…` | UUID 直绑（唯一确定，不排序） |

> `type=` 可以是 `#tag` / `!#tag`，也可以在一条 selector 里组合多个 `type=!xxx`。
> 含 `nbt=` / `type=#tag` / `type=!` / 多个 `type=` 的 selector 会交给服务端用原版
> `EntitySelectorParser` 解析（`nbt=` 只做 NBT 子集匹配，不区分生物/投掷物；要区分种类请用 `type=`）。
>
> 无匹配时：follow 停在上一帧位置、look_at 回退 yaw/pitch。

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

### 合成参数（`opacity` / `dest` / `source`）

三个合成参数**全部是 CAMERA 关键帧字段**（随时间插值），用于把一个 clip 的画面铺到屏幕：

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `opacity` | float | `1.0` | 叠放不透明度，`0`=完全透明、`1`=不透明。范围 `0`~`1`（validate 检查） |
| `dest` | object | `{x:0,y:0,w:1,h:1}`（全屏） | **目标区域**：画面铺到屏幕的归一化矩形，各分量 `0`~`1`（屏幕宽/高的百分比） |
| `source` | object | `{x:0,y:0,w:1,h:1}`（全幅） | **取材区域**：从画面内取哪一块的归一化矩形，各分量 `0`~`1`（画面内 UV） |

矩形对象格式（`dest` 与 `source` 相同）：

```json
{ "x": 0.6, "y": 0.05, "w": 0.35, "h": 0.35 }
```

| 分量 | 类型 | 说明 |
|------|------|------|
| `x` | float | 左上角 X（归一化，`0`=左缘 / 顶缘） |
| `y` | float | 左上角 Y（归一化，`0`=顶缘） |
| `w` | float | 宽度（归一化） |
| `h` | float | 高度（归一化） |

> **为什么用对象而非平铺字段**：矩形是一个整体语义（四个分量同属一个参数），与既有 `position` / `look_at_target` 的复合对象写法一致；关键帧按"复合值整体插值"（`KeyframeInterpolator` 对 `position` 即是如此），对象形态让 dest/source 随时间整体插值更自然，字段表也更小。

**叠放顺序（`order`）不写成字段**：层级由**轨道层级 → 轨道内 clip 顺序**决定（后面的 clip 在上一层），作者无需显式书写。

**常见形态**（都是同一套参数的特例）：

| 形态 | 写法 |
|------|------|
| 主相机（默认） | `dest` 全屏、`opacity` = 1（都可省略） |
| PIP 画中画 | `dest` = 小矩形（如右下角 `{x:0.6,y:0.05,w:0.35,h:0.35}`） |
| 分屏 | 两个 clip 各占半屏（`dest` 左/右或上/下） |
| 叠化 | 两个全屏 clip 的 `opacity` 交叉关键帧（A 末 1→0、B 首 0→1） |
| 数字变焦 / 局部取景 | `source` 取局部矩形、`dest` 全屏 |

> 渲染消费已落地（2026-10-07）：脚本 lane 的合成参数由 `client/lane/ScriptLaneDriver` 按本节字段
> 逐帧从 clip 关键帧插值取出，交给 `LaneCompositor` 上屏（缺省 = `1` / 全屏 / 全幅）。
> 插值为**匀速线性**（0.3.6 起运行时统一线性；缓动 = 编辑器烘焙成显式关键帧）。

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
| `sound` | string | 是 | — | 声音 ID 或音频文件名：`source="file"` 时写资源目录下的文件名（如 `"bgm.ogg"`）；`source="minecraft"` 时写原版声音 ID（如 `"minecraft:ambient.cave"`） |
| `source` | string | 否 | `"file"` | 音频来源：`"file"`= `resource/` 下的外部文件 / `"minecraft"`= 原版声音事件 |
| `category` | string | 否 | `"music"` | 音频类别：`"music"` 或 `"ambient"` |
| `volume` | float | 否 | `1.0` | 音量（`0.0` ~ `1.0`）；关键帧可对 `volume` 做淡入淡出 |
| `pitch` | float | 否 | `1.0` | 音调（`0.5` ~ `2.0`） |
| `loop` | boolean | 否 | `false` | 是否循环 |
| `fade_in` | float | 否 | `0.0` | 淡入时长（秒） |
| `fade_out` | float | 否 | `0.0` | 淡出时长（秒） |
| `position_mode` | string | 否 | `"relative"` | 音源位置模式：`"relative"` = 每帧跟随**玩家**（玩家位置 + 关键帧 x/y/z 偏移，随身声；接收者=玩家，距离恒 0 **强制无衰减**）；`"absolute"` = 关键帧 x/y/z 作为世界坐标（音源固定，可走空间衰减） |
| `attenuation` | string | 否 | `"linear"` | 空间衰减（仅 `absolute` 模式生效）：`"none"` 无衰减 / `"linear"` 线性（默认距离 16 格）/ `"inverse"` 反比。相对模式强制无衰减 |

AUDIO 关键帧包含 `volume`、`x`、`y`、`z`，用于逐关键帧控制音量与空间位置。

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

## 9. OVERLAY 轨道（覆盖层：图片 / 字幕 / 淡化 / 画中画）

在屏幕上渲染图片、字幕、全屏淡化或画中画。**支持多条 OVERLAY 轨道同时渲染**（如图片一条轨道、字幕一条轨道，用 `id` 区分），层间按 `z_index` 分层（大者在上）。

### Clip 字段

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `start_time` | float | 是 | — | 起始时间 |
| `duration` | float | 是 | — | 持续时间 |
| `layer_type` | string | 是 | — | `"fade"` 全屏颜色 / `"image"` 图片 / `"subtitle"` 字幕 / `"pip"` 画中画 |
| `path` | string | image 必需 | — | 图片文件名（如 `"my_image.png"` 或 `"flame.gif"`）。支持 **PNG / GIF**（GIF 自动拆帧按帧延迟轮播），文件放 `<游戏目录>/immersive_cinematics/resource/` 下，用英文命名 |
| `text` | string | subtitle 必需 | — | 字幕文本，`\n` 换行；支持 `@lang:<key>` 文本资源引用（见 [§12](#12-文本资源与多语言langkey)） |
| `color` | string | fade 必需 | — | 淡化颜色，如 `"#000000"` |
| `z_index` | int | 否 | `10` | 层级，越大越靠上。**0.3.6 起默认统一 10**（旧文档写 20）；想让字幕压在 fade 之上，给字幕写更大的值（如 30） |
| `keyframes` | array | 是 | — | 关键帧数组 |

### Keyframe 字段（统一参数：坐标 = 参考画布百分比）

**所有位置/缩放都是相对「参考画布」的比例（0 ~ 1），与窗口/分辨率无关**。参考画布 = 固定 16:9、像素锚点 1920×1080（`CanvasTransform`，全局常量，不进脚本 `meta`）：

- **1920×1080 窗口下画布 = 屏幕**：`x = 0.5` 就是屏幕正中，图片 `scale = 1` 就是原图 1:1 像素（与 0.3.6 之前的行为逐像素一致）。
- **非 16:9 窗口按 Fit 适配**：画布等比缩放居中，宽高比不一致的余量成为黑边（黑边区**不属画布**，由画幅层 letterbox 绘制 —— **LETTERBOX 轨有活跃 clip 时**才绘制，未启用时该区域显示游戏原画面）；元素按画布坐标落在画布内，构图在所有设备上一致。

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `time` | float | — | 在 clip 内的时间偏移（秒） |
| `x` | float | `0.5` | 元素**中心**在参考画布上的水平位置：`0.5` = 画布正中，`1` = 中心在画布右缘（不钳制，可越界） |
| `y` | float | `0.5` | 元素**中心**在参考画布上的垂直位置 |
| `anchor_x` | float | `0.5` | **锚点**（缩放绕点），元素**自身**归一化：`0` = 左缘，`1` = 右缘。`0.5` = 绕元素中心缩放（位置 = 元素中心） |
| `anchor_y` | float | `0.5` | 锚点（元素自身归一化，`0` = 上缘，`1` = 下缘） |
| `scale_x` | float | `1` | 缩放倍数（相对**基准尺寸**）：图片 = 原图分辨率 × 该乘数（`1` = 原图 1:1 像素）；字幕 = 文字块百分比缩放；pip = 占画布宽的比例（`0.5` = 半个画布宽） |
| `scale_y` | float | `1` | 同上（纵向） |
| `source` | object | 全幅 `{0,0,1,1}` | **取材**（`image` 专用）：`{"x":0,"y":0,"w":1,"h":1}` 为素材归一化子矩形，裁掉不要的部分（如只要左半：`{"x":0,"y":0,"w":0.5,"h":1}`） |
| `fit` | string | `"fit"` | **适配**（`image` 专用）：`"fit"` 完整放下（可能留透明边）/ `"fill"` 铺满裁切 / `"stretch"` 拉伸填满。离散值，按步进取值（取该时刻或之前最近的关键帧值） |
| `font_scale` | float | `1` | **字幕专用**：字号倍数（`1` = 原版 9px 字号）。矩阵缩放实现，与 MC `/title` 大字同一机制；它决定文字块本身的基准尺寸，可与 `scale_x/y` 叠加（最终缩放 = `font_scale × scale_x/y`） |
| `opacity` | float | `1` | 透明度（`0` = 完全透明，`1` = 不透明）。**淡入/淡出完全由该字段的关键帧表达**，代码层不叠加其他淡化。**0.3.6 起默认 1（旧默认 0 = 不可见）**：不写 opacity 的 clip 会直接显示 |

> **逐类基准尺寸**（`scale = 1` 时元素多大）：图片 = 原图像素；字幕 = 当前字号下的文字块；`pip` = 铺满画布。位置始终指**未缩放基准矩形**的中心；锚点非 0.5 时，缩放会改变元素的视觉中心（同 CSS `transform-origin`）。
>
> **逐层生效范围**（0.3.6 起）：`fade` 是效果层，基准尺寸 = 铺满画布，**x/y/anchor/scale 对它不生效**（只读 `opacity`）；`pip` 无纹理，`source`/`fit` 对它不生效。**x/y = 0.5 即画布居中**；贴边需按元素尺寸/2 折算（如贴左缘 = 元素半宽），避免元素移出画布。
>
> **取材与适配的先后**：先按 `source` 裁出素材子矩形，再按 `fit` 把它铺进元素框。默认 `fit: "fit"` 时，`scale_x ≠ scale_y` 的非等比缩放**不会拉伸素材**（等比放进元素框，框内留透明边）；要旧的「非等比直接拉伸」效果请写 `"fit": "stretch"`。
>
> **插值**（0.3.6 起）：关键帧之间**匀速线性**（每两个相邻关键帧定一段匀速运动），clip 级 `interpolation` 字段已移除。`source` 按分量整体线性，`fit`/`z_index` 是离散值（步进取值）。想要缓动/速度曲线：编辑器按曲线采样、把补出的关键帧写进脚本（脚本里存显式关键帧），运行时照常线性求值。

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

## 10. Adjust 轨道（画面颜色调整）

对画面做颜色调整，两个**作用域**（clip 级字段 `scope`，见下表）：

- `master`（缺省）：作用于**合成后的最终画面**（master 层，架构图 RADJ 节点）——在 lane 合成之后、GUI 之前作用于整屏画面。
- `lane`：作用于**指定相机轨的画面**（lane 级）——在该 lane 渲染完成之后、合成之前，只影响那一条相机轨的输出（分屏 / 画中画里可以各格独立调色）。

- **不影响 GUI**：字幕 / 黑边 / 跳过提示由 GUI 阶段绘制，调色不作用于它们（挂点在世界渲染阶段，早于 GUI）。
- **本版本 = 17 个标量通道**（12 标量 + R/G/B 每通道系数 + 完整 HSL 的 `hue` / `lightness`）**+ RGB 复合曲线**（曲线组形态 b：曲线定义一次 + `curve_strength` 关键帧控混合强度）。每通道曲线（R / G / B 各一条，第二批方向）、色轮 / 通道混合器 / LUT、以及「调整层」（作用于其下所有层）是后续批次（见 `plans/0.3.6/screen-color-adjust.md` §3 / §7）。
- **支持多条 ADJUST 轨道**：同一时刻以**后面的轨道**为准（轨道层级靠后的覆盖靠前的）——master 与 lane 级各自适用（多条 lane 级指向同一相机轨时，也是后面的轨道生效）。

### 执行顺序与 alpha 契约（两条作用域共用）

```
lane 渲染（含 lane 内发光描边）
  → lane 级调整（scope=lane：只动 RGB，alpha 直通）
  → 合成（opacity / dest / source）
  → 全部 lane 完成后
  → master 调整（scope=master：作用于合成输出，只动 RGB，alpha 直通）
```

- **调色只动 RGB**：`alpha` 逐位直通（着色器 `fragColor.a = src.a`）——透明度只在合成层由 `opacity` 调控（`LaneCompositor` / OVERLAY 层 opacity），调色不承担任何透明度语义。
- **lane 级参数按相机轨走**：同一条相机轨本帧产出的所有 lane 共用该轨的 lane 级参数（叠化重叠窗口下一条轨可能同时产出多条 lane）。

### Clip 字段

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `start_time` | float | 是 | — | 起始时间 |
| `duration` | float | 是 | — | 持续时间 |
| `keyframes` | array | 是 | — | 关键帧数组 |
| `scope` | enum | 否 | `master` | 作用域：`master`（合成输出 = 最终显示画面）/ `lane`（指定相机轨的画面，合成前） |
| `lane` | int | `scope=lane` 时必填 | — | 目标相机轨序号：**0 起，按 timeline 中 CAMERA 轨出现顺序**（其它类型轨道不占号）。必须满足 `0 ≤ lane < 本脚本 CAMERA 轨数量` |
| `curve` | array | 否 | — | **RGB 复合曲线**（曲线组形态 b）：控制点数组 `[[x, y], ...]`，至少 2 点、`x` 严格递增、`x` / `y` 各 0~1；不写 = 无曲线。见下方「曲线（`curve` + `curve_strength`）」 |

> 作用域与曲线都是 clip 级字段（不随时间变，故不挂关键帧）；17 个标量通道与 `curve_strength` 全部写在关键帧上（与 letterbox/EVENT/AUDIO/OVERLAY 同一套「统一关键帧级调控」规则）。`scope=master` 时写 `lane` 是多余字段（校验会提示）。

### 曲线（`curve` + `curve_strength`）— RGB 复合曲线（形态 b）

**曲线定义一次（clip 级 `curve`），关键帧只控混合强度（`curve_strength`）**：整条曲线的作用量可以随时间淡入淡出，但曲线形状本身不随时间变（形态 a「曲线点集也打关键帧」留作后续增强）。

```json
"curve": [ [0, 0], [0.5, 0.8], [1, 1] ]
```

- **控制点**：`[x, y]`，`x` = 输入亮度（0~1）、`y` = 输出（0~1）；**至少 2 点**、`x` **严格递增**（不递增 / 越界 / 点数不足会被校验与解析拦下）。
- **插值**：CPU 侧把控制点采样成 **256 点 LUT**（**Fritsch–Carlson 单调三次插值**：单调控制点 ⇒ 单调曲线、段内**无过冲**），渲染侧逐通道查表；`x` 小于首点 / 大于末点时**钳制到端点值**（不外推）。
- **混合**：`c = mix(c, curve(c), curve_strength)`，逐通道（R / G / B 各查各的）。
- **`curve_strength`**：0~1，**缺省 1**——写了 `curve` 就是全量生效；关键帧把它写回 `0` = 曲线淡出（不需要额外的开关字段）。**没有 `curve` 时 `curve_strength` 被忽略**。
- **操作栈位置**：**R/G/B 通道系数之后、HSL 之前**（第 7 步）——曲线作用在「色温 / 色调 + 通道系数」之后，所以曲线拉回的黑场 / 白场不会再被通道系数放大。

### Keyframe 字段（17 个标量通道 + 曲线强度）

**17 个标量通道缺省 0 = 无效果**（`curve_strength` 例外：缺省 1）：不写 = 不做这项调整；把通道写回 0 = 这项调整淡出（不需要额外的开关字段）。
所有通道**匀速线性插值**，所以任何一项都能随时间淡入淡出。

| 字段 | 类型 | 默认 | 范围 | 说明 |
|------|------|------|------|------|
| `exposure` | float | `0` | -5 ~ 5 | 曝光，单位 EV（×2^EV）：`0.5` = 提亮半档，`-1` = 压暗一档 |
| `contrast` | float | `0` | -1 ~ 1 | 对比度，以中灰 0.5 为轴：`+1` = 对比翻倍，`-1` = 全部压成中灰 |
| `highlights` | float | `0` | -1 ~ 1 | 高光：正 = 亮部提亮、负 = 亮部压暗（按像素亮度加权，暗部几乎不动） |
| `shadows` | float | `0` | -1 ~ 1 | 阴影：正 = 暗部提亮、负 = 暗部压黑（按像素亮度加权，亮部几乎不动） |
| `whites` | float | `0` | -1 ~ 1 | 白场：正 = 抬白点（亮部更亮、可能过曝）、负 = 压白点（亮部变暗） |
| `blacks` | float | `0` | -1 ~ 1 | 黑场：正 = 抬黑场（暗部发灰）、负 = 压黑场（暗部更黑） |
| `hue` | float | `0` | -1 ~ 1 | 色相旋转（HSL 的 H 通道）：`±1` = 旋转 ±180°（`0.5` = +90°、`-0.5` = -90°）；灰点（饱和度为 0）不受影响 |
| `saturation` | float | `0` | -1 ~ 1 | 饱和度（HSL 的 S 通道）：`-1` = 全灰，`1` = 双倍 |
| `vibrance` | float | `0` | -1 ~ 1 | 自然饱和度：正 = 优先加强低饱和区域（已经很艳的地方少动），负 = 整体降饱和 |
| `lightness` | float | `0` | -1 ~ 1 | 亮度（HSL 的 L 通道）：正 = 向白推（`1` = 全白）、负 = 向黑压（`-1` = 全黑），双向混合、两端为满量程 |
| `temperature` | float | `0` | -1 ~ 1 | 色温：正 = 暖（偏红黄）、负 = 冷（偏蓝）；按亮度归一化，不改变整体明暗 |
| `tint` | float | `0` | -1 ~ 1 | 色调：正 = 品红、负 = 绿 |
| `red` | float | `0` | -1 ~ 1 | R 通道系数（乘性）：`-1` = 红通道归零、`-0.5` = 红通道减半、`+1` = 红通道双倍 |
| `green` | float | `0` | -1 ~ 1 | G 通道系数（乘性），口径同 `red` |
| `blue` | float | `0` | -1 ~ 1 | B 通道系数（乘性），口径同 `red` |
| `curve_strength` | float | `1` | 0 ~ 1 | **RGB 复合曲线的混合强度**（`curve` 存在时生效）：`1` = 曲线全量、`0.5` = 一半、`0` = 曲线淡出；**没有 `curve` 时忽略** |
| `grayscale` | float | `0` | 0 ~ 1 | 灰度强度：`1` = 完全黑白（按 Rec.709 亮度），`0.5` = 半黑白 |
| `invert` | float | `0` | 0 ~ 1 | 反相强度：`1` = 完全反相，`0.5` = 半反相 |

**操作顺序固定**（同一关键帧里多个通道同时生效时按此顺序计算，不可调）：
曝光 → 对比度 → 高光/阴影 → 白场/黑场 → 色温/色调 → R/G/B 通道系数 → **RGB 复合曲线（`curve`）** → 色相旋转 → 饱和度 → 自然饱和度 → 亮度 → 灰度 → 反相。

> **HSL 组共用一次换算**：`hue` / `saturation` / `vibrance` / `lightness` 在同一块里按
> 「色相旋转 → 饱和度 / 自然饱和度 → 亮度」处理；四个值全为 `0` 时整块跳过（逐位恒等）。
> 色相旋转是「先算的」——但它只动 H，`saturation` / `vibrance` 只动 S、`lightness` 只动 L，三者互不影响。

> **两个「灰」不是同一个灰**：`grayscale` 用 Rec.709 亮度（0.2126 / 0.7152 / 0.0722）；
> `saturation = -1` 走 HSL，得到的是 HSL 亮度 L =（max+min)/2。彩色转灰时两者数值不同（都正确，口径不同）。

### 示例：从正常画面逐步偏暖、黑白化

```json
{
  "type": "adjust",
  "id": "adjust_1",
  "clips": [
    {
      "start_time": 0,
      "duration": 12,
      "keyframes": [
        { "time": 0,  "temperature": 0,   "grayscale": 0 },
        { "time": 2,  "temperature": 0.3, "grayscale": 0 },
        { "time": 6,  "temperature": 0.3, "grayscale": 0.5 },
        { "time": 12, "temperature": 0,   "grayscale": 1 }
      ]
    }
  ]
}
```

### 示例：RGB 复合曲线（提亮中间调 + 曲线淡入淡出）

曲线定义一次（clip 级），`curve_strength` 打关键帧做「风格化程度」的淡入淡出：

```json
{
  "type": "adjust",
  "id": "adjust_curve",
  "clips": [
    {
      "start_time": 0,
      "duration": 8,
      "curve": [ [0, 0], [0.5, 0.8], [1, 1] ],
      "keyframes": [
        { "time": 0, "curve_strength": 0 },
        { "time": 2, "curve_strength": 1 },
        { "time": 4, "curve_strength": 0.5 },
        { "time": 6, "curve_strength": 1 }
      ]
    }
  ]
}
```

> 不写 `curve_strength` 就是 `1`（曲线全量生效）；只想让曲线一直生效，写 `"keyframes": [ { "time": 0 } ]` 即可。
> 曲线只动 RGB，`alpha` 逐位直通（透明度仍只由合成参数 `opacity` 调控）。

### 示例：反相脉冲（0.5 秒闪一下）

```json
{
  "type": "adjust",
  "id": "adjust_2",
  "clips": [
    {
      "start_time": 6,
      "duration": 1,
      "keyframes": [
        { "time": 0,   "invert": 0 },
        { "time": 0.5, "invert": 1 },
        { "time": 1,   "invert": 0 }
      ]
    }
  ]
}
```

### 示例：四象限各自调色（lane 级）

4 条 CAMERA 轨各占一格（`dest` 半屏）→ 4 条 lane 级 ADJUST 轨分别指向它们（`lane` = CAMERA 轨出现顺序 0~3）+ 1 条 master 轨做整体风格化：

```json
{
  "timeline": {
    "tracks": [
      { "type": "CAMERA", "clips": [ { "start_time": 0, "duration": 10,
        "keyframes": [ { "time": 0, "dest": { "x": 0, "y": 0, "w": 0.5, "h": 0.5 }, "position": { "dx": 0, "dy": 0, "dz": 0 } } ] } ] },
      { "type": "CAMERA", "clips": [ { "start_time": 0, "duration": 10,
        "keyframes": [ { "time": 0, "dest": { "x": 0.5, "y": 0, "w": 0.5, "h": 0.5 }, "position": { "dx": 0, "dy": 0, "dz": 0 } } ] } ] },
      { "type": "ADJUST", "clips": [ { "start_time": 0, "duration": 10, "scope": "lane", "lane": 0,
        "keyframes": [ { "time": 0, "grayscale": 0 }, { "time": 5, "grayscale": 1 } ] } ] },
      { "type": "ADJUST", "clips": [ { "start_time": 0, "duration": 10, "scope": "lane", "lane": 1,
        "keyframes": [ { "time": 0, "temperature": 0 }, { "time": 5, "temperature": 0.6 } ] } ] },
      { "type": "ADJUST", "clips": [ { "start_time": 0, "duration": 10,
        "keyframes": [ { "time": 0, "saturation": 0 }, { "time": 5, "saturation": -0.5 } ] } ] }
    ]
  }
}
```

> 上面第 3 条轨（无 `scope`）就是 master：它作用于**合成后**的整屏画面，所以「lane 0 黑白 + 整体降饱和」会叠加（lane 级先算、master 后算）。

---

## 11. 模板（参数化生成片段）

模板 = **参数化生成器**：填几个目标参数 → 生成标准脚本 JSON。生成物与手写脚本完全等价（走同一套解析 / 校验），可继续编辑。

当前版本（0.3.6 第一版）只有**片段级模板**（生成一段 clip）；轨道级（如"环绕整圈 = 三段 120° 拼圆"）与脚本级模板是后续步骤。

命令入口（需要权限 2）：

| 命令 | 作用 |
|------|------|
| `/icinematics template list` | 列出内置模板与参数（含默认值 / 枚举候选） |
| `/icinematics template <id> [key=value ...]` | 展开为完整脚本 → 写入 `scripts/generated/<name>.json` → 当场校验 |

- 保留参数：`name=<文件名>`（默认 = 模板 id，只允许小写字母 / 数字 / 下划线）、`start=<起始秒>`（默认 0）。
- 生成物放在 `scripts/generated/` 子目录：`/icinematics play generated:<name>` 直接播放，`/icinematics validate generated:<name>` 再校验。

### 内置模板

| id | 产物轨道 | 说明 |
|----|---------|------|
| `fade` | OVERLAY | 黑场 / 白场：`color`（black / white）、`duration`、`fade_in`（压场）、`fade_out`（亮起）；两个 fade 都为 0 就是纯色场（"黑场 1s"） |
| `static_breath` | CAMERA | 固定机位 + 呼吸：位置 / 朝向 / fov 钉死，晃动由 `cam_breath_*` 程序化扰动产生 |
| `dolly` | CAMERA | 推近 / 拉远：`direction`（in / out）；`fov_start ≠ fov_end` 即希区柯克变焦 |
| `orbit_arc` | CAMERA | 环绕弧线（一段三次贝塞尔，`sweep` 绝对值 ≤ 120°）：`center_mode`（trigger / entity）决定圆心是触发点还是选择器目标 |

> **环绕的数学**：控制点到端点的距离 = `R × 4/3 × tan(θ/4)`（θ=90° → 0.5523R，θ=120° → 0.7698R）。
> 位置与控制点都写**相对**形式（位置相对基准点，控制点相对段起点）——"以玩家触发点为圆心绕圆"这类运行时才知道坐标的场景可直接生成。整圈 = 三段 120° 拼圆（轨道级模板，后续步骤）。

**示例**：

```
/icinematics template fade color=black duration=1
/icinematics template orbit_arc radius=10 height=2.5 sweep=90 duration=6 name=orbit_player
```

### 代码侧（模板是什么）

| 类 | 职责 |
|----|------|
| `script/template/ClipTemplate` | 片段级模板接口：`id` / `name` / `trackType` / `params` / `expand(args)` |
| `script/template/TemplateParam` | 参数声明——**复用 `script/schema` 的 `FieldDef`**（类型 / 默认值 / 枚举候选 / 分组），不是第二套参数体系 |
| `script/template/TemplateArgs` | 一次展开的实参：按声明类型归一 + 兜默认值，展开器直接读 |
| `script/template/TemplateRegistry` | 内置库注册表 |
| `script/template/TemplateScriptAssembler` | 把展开出的 clip 装进新建脚本骨架（`ScriptTemplate`）→ 可播放脚本 |

---

## 12. 文本资源与多语言（`@lang:<key>`）

字幕 `text` 与 `meta.description` 可以**只写引用 key**，译文集中放在资源目录——改译文不动脚本，同一份脚本在不同语言的客户端显示各自语言。

### 12a. 目录与文件

```text
<游戏目录>/immersive_cinematics/resource/lang/
├── zh_cn.json
└── en_us.json
```

一个语言一个文件，内容是**扁平字典**（`key → 译文`，与 MC 自己的 lang 文件同构）：

```json
{
  "boss_fight.intro": "欢迎，勇者",
  "boss_fight.outro": "再会"
}
```

- 语言代码与 MC 一致（`zh_cn` / `en_us` / `ja_jp`…），**文件名严格小写**（Windows 大小写不敏感、Linux 敏感，统一小写两端表现一致）。
- 只需提供自己需要的语言，缺的语言走回退链（见 12c）。
- 资源目录与图片/音频同待遇：**本地读取、不进脚本、不进存档、不走服务器流量**——多人服下每个客户端按自己的语言显示，服务端不需要知道任何语言信息。

### 12b. 引用语法

| 写法 | 结果 |
|------|------|
| `"欢迎，勇者"` | 普通文案，原样渲染（**不查表**） |
| `"@lang:boss_fight.intro"` | 查表 → 当前语言译文 |
| `"@@lang:boss_fight.intro"` | 转义：渲染为字面量 `@lang:boss_fight.intro` |

- **`@lang:` 前缀是必需的**：字幕本来就是任意文案，裸 key 与正常文字无法区分，加前缀让规则一句话说清——以 `@lang:` 开头才查表。
- 译文里允许 `\n` 换行（与 `text` 一样，先查表后分行）。
- key 语法：一段或多段 `[a-zA-Z0-9_]`（每段 ≤ 32 字符）用 `.` 连接，总长 ≤ 256，例如 `boss_fight.intro`。建议 `<脚本id>.<用途>`——字典是全局一份，命名空间是唯一的防撞手段。不合法的 key 按“未找到”处理（原样显示引用串）。
- **不支持占位符参数**（`%s` 之类按字面处理）：脚本目前没有变量系统。

### 12c. 回退链与生效时机

```text
当前语言（如 zh_cn） → en_us → 原样显示引用串（@lang:<key>）
```

- 与 MC 自己的做法一致（`en_us` 兜底、当前语言覆盖），**不做语言族回退**（`zh_cn` 不会回落到任意 `zh_*`）。
- 第三段刻意“不静默”：缺 key 时玩家看到 `@lang:boss_fight.intro`，一眼看出作者漏了 key，而不是一片空白。
- 资源缺失 / 缺 key 只记调试日志，**不阻塞播放**。
- 语言文件在客户端进程内缓存：**改译文后重启客户端生效**（与图片/音频一致，不参与 MC 资源重载）。

### 12d. 适用范围

| 字段 | 是否支持 | 说明 |
|------|---------|------|
| OVERLAY `subtitle` 的 `text` | ✅ | 唯一的“文本内容”字段 |
| `meta.description` | ✅ | 给人读的说明（架构图节点 tooltip / 属性面板） |
| `meta.name` / `meta.id` / `meta.author` | ❌ | 可读标识 / 依赖键 / 人名，必须稳定不翻译 |
| EVENT 的 `command` | ❌ | 命令走服务端执行，属另一条链路 |

### 12e. 示例

```json
{
  "meta": {
    "id": "boss_fight",
    "name": "Boss Fight",
    "author": "ImmersiveCinematics",
    "version": 3,
    "description": "@lang:boss_fight.desc"
  },
  "timeline": {
    "total_duration": 12,
    "tracks": [
      { "type": "overlay", "id": "overlay_sub", "clips": [
        { "start_time": 0, "duration": 12, "layer_type": "subtitle",
          "text": "@lang:boss_fight.intro",
          "keyframes": [
            { "time": 0,  "opacity": 0 },
            { "time": 1,  "opacity": 1 },
            { "time": 11, "opacity": 1 },
            { "time": 12, "opacity": 0 }
          ] }
      ] }
    ]
  }
}
```

`resource/lang/zh_cn.json` / `en_us.json` 里写：

```json
{ "boss_fight.intro": "欢迎，勇者", "boss_fight.desc": "第一章 BOSS 战过场" }
```

```json
{ "boss_fight.intro": "Welcome, hero", "boss_fight.desc": "Chapter 1 boss cutscene" }
```

### 12f. 编辑器支持

- 字幕 `text` 与脚本 `description` 字段是**引用选择器**：候选项来自 `resource/lang/*.json` 的 key 并集，选项上显示当前编辑器语言下的译文；图片 `path` 字段同样有 `resource/` 文件选择。
- 当前值缺 key / 缺文件时字段下方给出黄色提示（**只警告不阻塞**——资源目录本来就可以后补）。
- 编辑器**不写**语言文件：译文请直接编辑 `resource/lang/*.json`（key × 语言的矩阵编辑面板后置）。

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
            "keyframes": [
              {
                "time": 0.0,
                "position_mode": "relative",
                "position": { "dx": 5, "dy": 2, "dz": 3 },
                "yaw": 90, "pitch": 5, "roll": 0,
                "fov": 70, "zoom": 1.0
              },
              {
                "time": 10.0,
                "position_mode": "relative",
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

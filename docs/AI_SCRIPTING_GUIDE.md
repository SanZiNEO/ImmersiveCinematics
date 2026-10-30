> **过期文档（OUTDATED）——不得作为当前设计或实施依据**
>
> 本文未随最新设计更新，仅保留作历史参考。AI 与自动化工具不得据此推断当前格式、功能状态、接口或实施要求，也不得直接照搬其中示例用于新实现。
>
> 当前规划请先查阅 [0.3.6 计划状态索引](../plans/0.3.6/README.md) 与 [I 轮实施基准](../plans/0.3.6/I-script-core/README.md)，再按状态查阅对应未完成轮次。
>
> 计划目标不代表已经实现；核实当前代码行为时须结合源码，不得将旧文或未实现计划当作现状。

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

常用行为开关（默认值合理，不写也行）：`block_keyboard: true`、`block_mouse: true`、`hide_hud: true`、`skippable: true`、`interruptible: true`、`hold_at_end: false`、`pause_when_game_paused: true`。`hide_arm`/`suppress_bob` 等隐藏类字段缺省为三态 `null`（跟随 `hide_hud`），需要显式覆盖时才写 `true`/`false`。

> **宏观循环 `macro_loop`**：`true` = **从 `a` 到 `b` 重复执行**——循环是播放控制层的重复执行，不是时间折叠。区间 `[a,b]` 取 `timeline.loop_start` / `loop_end`（不写则 `a=0`、`b=宏观末端` = 各片段展开结束时刻的最大值，含片段自身循环展开；**不是** `total_duration`）。圈数 `macro_loop_count`：`-1`（缺省）= 无限重复（脚本不再自然结束，靠跳过/打断退出）；正整数 N = 重复 N 圈后自然结束（总时长 = `a + N × (b − a)`）。不写 `loop_end` 且存在永不结束片段时末端不存在 → 不循环。与 `hold_at_end` **互斥**（同时开会告警）。模式 `macro_loop_mode`（`repeat` 默认 / `pingpong`）**第一版只实现 `repeat`**，写 `pingpong` 会告警并按 `repeat` 播放。服务端 EVENT 不参与循环；编辑器预览按真实时间线播放（不循环、不展开）。

> **远距离运镜的区块预加载**：预加载**没有脚本级开关**（0.3.6 起移除 `meta.preload`）——只有存在**非空 CAMERA 轨道**的脚本才会实际触发预加载（纯 HUD/字幕/事件脚本不会），是否启用由全局配置 `preloadEnabled` 决定。镜头要飞到玩家视距外（跨区块/跨维度）时，只要该脚本有 CAMERA 轨道就会自动预加载，无需（也无法）在脚本里写开关。

> **`interruptible` 与 `priority` 必读（播放队列规则）**：
> - **可打断（`interruptible: true`，默认）** = 区域切换型脚本：新脚本请求时**立即替换**当前脚本（打断就替换）。适合"进 A 区播 A、进 B 区播 B"的场景。
> - **不可打断（`interruptible: false`）** = 强制过程型脚本：播放期间其它脚本请求一律**排队**（不设上限），等它播完自动接播。适合强制观看的过场、循环氛围镜头。
> - **`priority`**（int，默认 0，可选）：数值越大越优先，**仅用于排队顺序**（高优先先接播）。注意：优先级**不能大于打断**——不可打断的脚本永远不会被抢占，`priority` 再高也只能排队。
> - **`play_mode`**（enum，默认 `parallel`，可选）：**跨脚本**播放关系（在播侧语义）——`parallel` = 不阻塞新来者、也不被跨脚本打断（= 现状）；`exclusive` = 我播时其它脚本一律**排队**，我播完按 `priority` 降序 + FIFO 接播（无 `exclusive` 时一次排空所有可播项；同脚本仍在播的排队项等它结束再启动）；`interruptible` = 可被新来者**顶掉**（硬切让位）。它管**跨脚本**关系，上面的 `interruptible`（bool）管**同脚本**再触发的替换，两者共存。完整到达组合规则见 `docs/SCRIPT_FORMAT.md` §1b。
> - 玩家视角（无脚本覆盖）由**时间空隙**自然产生：相机片段之间留空档，那段玩家恢复自由视角。

> **`hide_skip_hud` 必读**：长按跳过键的提示（右下角图标+进度环）由 `hide_skip_hud` 控制——**不写时默认显示（开启）**，只在显式写 **`"hide_skip_hud": true`** 时才隐藏。它不回落 `hide_hud`：`hide_hud: true` 的脚本照样显示跳过提示，除非显式关掉。

> **写完必须自查**：脚本写完后在游戏内执行 `/icinematics validate <文件名>`，**直到输出"§a校验通过"才能交付**。该命令会一次列出所有问题（结构错误/缺失字段/语义错误/缺省字段提示），逐条修掉即可——不要在没有校验的情况下直接交付脚本。

### 3.3 timeline

| 字段 | 说明 |
|---|---|
| `total_duration` | 声明时长秒（正数，不允许 0）。**统计 / 信息位**：脚本按**实际执行内容**判定播完（执行路径上已无剩余可执行内容），本值不决定结束时刻（比内容长就早结束、比内容短就继续播完） |
| `tracks` | 轨道数组。CAMERA 可多条（每条 = 一个画面 lane，轨道层级后面的在上；最后一轨的顶层 clip 决定主相机）；LETTERBOX/EVENT 建议 1 条，AUDIO/OVERLAY/MOD_EVENT 不限 |
| `loop_start` / `loop_end` | **宏观循环区间 `[a,b]`**（秒），仅在 `meta.macro_loop=true` 时被读取：`a` 缺省 `0`，`b` 缺省 = 宏观末端。写 `loop_end` 即按 `[loop_start, loop_end]` 重复（子区间循环）。编辑器 A-B 快捷键（I / O）写的就是这两个字段 |

### 3.4 CAMERA 轨道（核心）

**clip 级字段**：

| 字段 | 说明 |
|---|---|
| `start_time` | 在全局时间线的起点（秒） |
| `duration` | 持续时长。正数 = 定长；负数 = 无限 |
| `transition` | `"cut"` = 硬切（默认）；`"morph"` = 与**下一段**做相机状态过渡（写在本段上：窗口以两段交界为中心，两侧实时相机值交叉）。**只改相机六参数，不叠化/划像画面**；画面转场见 §4.3 |
| `transition_duration` | **仅 morph 时有效**，过渡时长（秒，缺省 0.5）；cut 时不要写 |
| `transition_curve` | 过渡权重曲线（可选）：`{"type": "smoothstep"}` 这类数学模型定义，控制过渡节奏；缺省 = 线性 |
| `transition_params` | **分参数过渡**（可选）：`{"roll": {"mode": "hold"}, "fov": {"mode": "cut"}}`——每个通道独立选 `blend` / `hold` / `cut`，可各自给 `duration` / `curve`；缺省 = 全六通道同权重线性 |
| `transition_interrupt` | 打断规则（可选，只在过渡被打断时生效）：`"blend"`（默认，从当前混合中间态续接）/ `"snap"`（硬切到新目标） |
| `loop` / `loop_count` | 循环生命周期：`loop: true` + `loop_count: -1` = 无限循环（配合 follow/look_at 可做常驻跟随视角）；正整数 = 播 N 个周期后停在末帧；`loop_count: 0` 非法（按 1 处理） |
| `loop_mode` | 循环时间映射：`"repeat"`（默认）= 从头到尾重复；`"pingpong"` = 往复折返（监控视角来回摇，关键帧只写半程即可） |
| `curve` | 贝塞尔路径（可选，见 SCRIPT_FORMAT.md） |
| `cam_breath_enabled` | 呼吸扰动（手持感，clip 级），配 `cam_breath_intensity`（0.05~0.1）、`cam_breath_seed`、`cam_breath_type`（`perlin` 默认 / `perlin_axis` / `sine` / `trauma`）、`cam_breath_speed`（越大晃越快）；`trauma` 额外配 `cam_breath_trauma`（初始冲击 0~1）与 `cam_breath_decay`（衰减速率）；同 seed 重放一致 |
| `motion_position` / `motion_yaw` / `motion_pitch` / `motion_roll` / `motion_fov` / `motion_zoom` | **迟滞（相机参数运动响应，可选）**：给该通道挂一个数学模型 `{type, …}`，把求值出来的值平滑 / 延迟 / 缓动后再写进相机状态；缺省 `null` = 不接模型（**默认关**）。常用三型：`{"type": "exponential_smooth"}`（粘滞；`tau` 省略 = 0.15 秒，无过冲）、`{"type": "spring"}`（阻尼弹簧；`damping` < 1 回弹）、`{"type": "inertia"}`（惯性：跟随移动目标不落后、起步慢、停止后过冲再回稳）。**六通道建议起点 = `exponential_smooth`**（通道差异只是建议，见 SCRIPT_FORMAT.md「相机参数运动模型」）；要启用必须写明 `type`（写 `{}` 会校验报错并回落无效果） |
| `modifier` | **覆盖链 Modifier 声明**（可选，一个片段至多一条）：在关键帧求值出的六参数之上按通道做一次固定合成，如 `{"channels": ["yaw"], "mode": "add", "yaw": 30}` = yaw 再 +30°。字段形态见下方"覆盖链 Modifier" |
| `suppress_modifiers` | `true` = 本片段活跃时**跳过全部 Modifier**（链输出 = 本片段原始六参数）；缺省 `false` |
| `mask_shape` | **CAMERA lane 合成遮罩**（clip 级）：`full`（默认）/ `rect` / `circle`。几何在关键帧 `mask_x` / `mask_y` / `mask_width` / `mask_height` / `mask_radius`；只作用于该片段产出的相机画面，不裁切字幕/图片，与 `source`/`dest` 独立。CAMERA 不接受 `gradient` / `gradient_*` / `blend_mode`（`gradient` / `gradient_*` 专属 fade；`blend_mode` 属于 ADJUST / fade） |

> **跟随/注视/坐标模式都在关键帧级配置**（`position_mode`、`follow`、`look_at` 系列字段写在 keyframe 对象里，见下）。

**keyframe 字段**（每个关键帧都是完整镜头状态；`position_mode`/`follow`/`look_at` 可逐关键帧独立配置，关键帧间自动平滑过渡）：

```json
{
  "time": 0,
  "position": { "dx": 30, "dy": 2, "dz": 0 },
  "position_mode": "relative",
  "follow": "none",
  "look_at": "none",
  "yaw": 90,
  "pitch": 5,
  "roll": 0,
  "fov": 70,
  "zoom": 1.0
}
```

| 字段 | 说明 |
|---|---|
| `position_mode` | `"relative"`（默认）= 相对位置点源（position 写 dx/dy/dz 或 fwd/up/right；点源 = `facing_origin`，缺省玩家激活位置）；`"absolute"` = 世界坐标（写 x/y/z） |
| `follow` | `"none"`（默认）= 位置走关键帧；`"entity"` = 位置跟随实体（position 的 dx/dy/dz 变成相对实体脚底的偏移）。**follow↔普通关键帧之间是平滑过渡**（两端都是世界坐标，直接插值） |
| `follow_selector` | 跟随目标选择器，默认 `@p`（见下方"目标选择器"） |
| `look_at` | `"none"`（默认）= 用 yaw/pitch；`"player"` = 注视玩家激活位置；`"coordinate"` = 注视固定点（xyz 或结构中心）；`"entity"` = 注视实体（包围盒内取点，部位见 `look_at_part`，缺省中心）；`"block"` = 注视就近搜索到的方块中心（见 `look_at_target_block`）。**look_at 关键帧的目标点之间插值，切换/开关平滑过渡**（如 0s 看玩家 → 15s 看铁傀儡） |
| `look_at_selector` | 注视目标选择器（`entity` 模式），默认 `@p` |
| `look_at_part` | `look_at=entity` 的**部位百分比**：`{x,y,z}` 每轴 `0`~`100`（`50` = 包围盒中心 = 不写的现行为；`0`/`100` = 该轴最小/最大侧），缺省分量 = `50`；越界钳制在 `0`~`100`（取点恒在包围盒内）。看头 `{"y":90}`、看脚 `{"y":0}`。只对 `look_at=entity` 有效（坐标/结构/方块来源直接改坐标），写在其它模式下会被 validate 报错 |
| `look_at_target_x/y/z` | 注视固定坐标（`coordinate` 模式）。**与 `look_at_target_structure` 互斥** |
| `look_at_target_structure` | 注视结构中心（`coordinate` 模式）：填结构 id 如 `minecraft:village`。播放时服务端自动定位**结构 bounding box 中心**并替换为坐标（就近搜索，原版 /locate 同范围，多人服务器也生效）；编辑器里是注册表下拉补全。**与坐标互斥**：指定结构后定位失败不回退坐标，该端无注视目标（回退角度插值） |
| `look_at_target_block` | 注视方块中心（`block` 模式）：`"block:<方块 id>[:<半径>]"`（与 `facing_origin` / `yaw_base_from` 同一套方块形态，半径缺省 16 格），取就近搜索到的方块中心。不可解析（附近没有该方块 / 多人服无服务端世界）= 该端无注视目标（回退角度插值） |
| `look_at_offset` | 注视点偏移（点源通用偏移，见下方"偏移写法"）：先取点后加偏移（`look_at=entity` 时 = 部位百分比取点之后再加偏移） |
| `yaw_base` / `pitch_base` | 朝向基准：`"world"`（默认，`yaw`/`pitch` 即世界角）/ `"entity"`（实体**身体朝向**水平角 + **视线**俯仰，用 `yaw_base_selector`）/ `"line"`（`yaw_base_from` → `yaw_base_to` 的连线方向）。用基准时 `yaw`/`pitch` 是相对基准的偏移 |
| `yaw_lock` / `pitch_lock` | 方向锁（每轴一把，不写 = 跟 `yaw_base`/`pitch_base` 的来源）：写了 = 该轴基准固定为这个**世界角绝对值**（度），关键帧 `yaw`/`pitch` 仍叠加（`"yaw_lock": 100` + `"yaw": 5` = 105）。典型第三人称 = `"pitch_lock": 15` + `"yaw_base": "entity"`（pitch 锁死、yaw 跟身体朝向）。某轴被锁 = 该轴的基准来源不参与（`yaw_base: "line"` 端点缺失也不再按空片段处理）；`look_at != none` 时静态侧整体被覆盖（锁与偏移都不参与） |
| `yaw_base_from` / `yaw_base_to` | `line` 基准的两个端点，**各是一个点源**（见下方"点源写法"）。退化连线（零长度 / 纯垂直）被拒 → 回退 `world` 基准 |
| `facing_origin` / `facing_target` | **位置点源**（= 基准坐标系原点）与**前轴目标**，**都是点源**（见下方"点源写法"）。`facing_origin` 不写 = 世界轴偏移用玩家激活位置、基准坐标系偏移用玩家实体 `@p`；`facing_target` 不写 = 位置点源自身朝向 |

**点源写法**（`facing_origin` / `facing_target` / `yaw_base_from` / `yaw_base_to` 通用；`look_at` 的坐标 / 结构 / 实体 / 方块来源同一套）：
`"player"`（玩家激活位置）；`"coordinate"` + `<字段>_x/_y/_z`（固定坐标，三个都要写）；`"block:minecraft:obsidian:32"`
（玩家附近最近匹配方块中心，半径缺省 16 格）；`@e[type=…]` / `uuid:…`（实体选择器——`facing_origin` / 连线端点 /
`look_at=entity` 按 `look_at_part` 取包围盒内点，`facing_target` 取包围盒中心）；其余非空字符串 = 结构 id
（结构 bounding box 中心）。空串 / 不写 = 无点源。
不可解析 = 该端无目标，片段按空处理（不写相机）。结构 / 方块在单人 / 集成服务器直接定位；多人服务器上只有
`look_at_target_structure` 与 `facing_origin` 由服务端推送前替换为坐标，其余字段的结构 / 方块形态在多人服不可解析。

**偏移写法**（点源通用属性）：任一来源都能叠一层偏移，写 `<字段>_offset`（如 `look_at_offset` / `facing_target_offset` /
`yaw_base_from_offset`）；位置通道的偏移写在 `position` 对象里（`dx/dy/dz` 或 `fwd/up/right`）。
分量名决定表达空间：`dx`/`dy`/`dz` = 世界轴（缺省），`fwd`/`up`/`right` = **基准坐标系**（原点 = `facing_origin`、
前轴 = `facing_target`）；两类不能混写。`base` 偏移需要本关键帧有基准坐标系（写 `facing_origin` / `facing_target`，
或位置用 `fwd`/`up`/`right`），否则不生效（校验器提示）。

**通道相对化**：位置 / yaw / pitch / 注视点四项各自独立——位置跟基准不影响 yaw，yaw 跟基准不影响 pitch（水平面 /
垂直面分开取来源）；`look_at != none` 时注视点接管 yaw 与 pitch（互斥、不叠）。

**朝向叠加次序**（`look_at = none` 时，yaw / pitch 各按本式）：最终角 = 该轴基准（写了 `yaw_lock` / `pitch_lock`
= 锁值；不写 = `yaw_base` / `pitch_base` 的方向源）+ 关键帧 `yaw` / `pitch` 偏移；`look_at != none` = 动态目标点
接管，静态侧（锁 + 基准 + 偏移）全部不参与（同一关键帧两种写法可并存，同一时刻只有一侧生效）。clip 级
`orient: "tangent"`（切线朝向）与 `look_at` 同属动态侧：锁与偏移都不参与。

点源字段与坐标形态的配套字段（`<字段>_x/_y/_z`）都写在**关键帧**上（`look_at=block` 的 `look_at_target_block`
用同一套方块形态）；`position` 对象只承载位置通道的偏移
（`dx/dy/dz` 世界轴 / `x/y/z` 绝对 / `fwd`/`up`/`right` 基准坐标系——基准坐标系的上轴固定为世界竖直，
`position.up_axis` 已移除，写它会报错）。

**目标选择器**（`follow_selector` / `look_at_selector`）：`@p`/`@s`（玩家）、`@a`/`@a[...]`（全体玩家）、`@r`/`@r[...]`（随机玩家）——`@a`/`@r` 与带选项形态（`@p[...]` 等）交服务端用原版解析、语义同原版；`@e`（离锚点最近实体）、`@e[type=minecraft:sheep]`（类型过滤后就近，模组 boss 用其注册 id）、`@e[name=自定义名]`（命名牌名字过滤后就近）、`uuid:xxxxxxxx-…`（UUID 直绑）——就近基准（锚点）缺省为相机当前位置，可用 `selector_anchor` 改为 `player`（玩家脚底）/ `target`（该调用点当前锁定目标脚底，未解析到目标时回落相机）/ `origin`（`position` 的位置侧点源坐标，形态见上方"点源写法"；`position` 缺失或点源解析失败时回落相机）。原版 1.20.1 无 `@n`，按未知形式处理（warn 日志 + 不解析，不崩溃）。

每个选择器调用点（`follow` / `look_at` / `yaw_base` / `yaw_base_from` / `yaw_base_to` / `facing_origin` / `facing_target`）的锁定策略、锚点与择一规则可单独覆盖：写 `selector_refresh_<调用点>` / `selector_anchor_<调用点>` / `selector_pick_<调用点>` 等（4 个策略字段 + 锚点 + 择一都有调用点变体），缺省回落通用 `selector_*`（详见 `docs/SCRIPT_FORMAT.md` §4「调用点级策略覆盖」）。

**多候选择一**（`selector_pick`）：一个 selector 匹配到多个实体时取哪一个。`"first"`（缺省）= 0.3.6 之前的择一行为（本地 `@e` 系列取距锚点最近的活实体，服务端选择器按服务端回传顺序取首个可用）；`"nearest"` = 在客户端可用候选内取距锚点最近者（服务端选择器由此与本地同口径）；`"alive"` = 按候选返回顺序取首个存活者、不比距离。可用 = 客户端已加载且存活；距离 = 世界坐标平方距离比较。示例：`"selector_pick": "nearest"` + `"selector_anchor": "player"` = 取离玩家最近的候选。

规则：
- `time` 是 **clip 内**偏移（从 0 到 duration），必须严格递增
- 关键帧之间所有值**匀速线性插值**（两点定一段运动）；0.3.6 起运行时统一线性、无 clip 级 `interpolation` 字段——缓动/速度曲线由编辑器烘焙成显式关键帧
- 至少 1 个关键帧；想表现"静止"就用首尾两个相同值

**画面合成与 lane 遮罩（关键帧级）**：

| 字段 | 默认 | 说明 |
|---|---|---|
| `opacity` | `1` | 合成不透明度（0~1）。**叠化 = 下层 clip 保持 1、上层 clip 在重叠窗口 0→1**；不要两边同时淡出 |
| `dest` | 全屏 `{0,0,1,1}` | 画面铺到窗口的归一化矩形（窗口宽/高比例） |
| `source` | 全幅 `{0,0,1,1}` | 从本 clip 画面取材的归一化矩形（画面内 UV） |
| `mask_x` / `mask_y` | `0.5` | **CAMERA lane 遮罩中心**，窗口比例，有限值、可越界；`full` 不读取 |
| `mask_width` / `mask_height` | `1` | `rect` 宽/高占窗口比例，有限非负、不设上限；任一为 `0` = 该 lane 不绘制（显露下层）；`circle`/`full` 不读取 |
| `mask_radius` | `0.5` | `circle` 半径 / 窗口**短边**比例，有限非负、不设上限；`0` = 不绘制；非方形窗口仍是真圆 |

- `mask_shape` 只在 **clip 级**，`mask_x/y/width/height/radius` 只在**关键帧**；写错层级 / 轨道会被拒绝。`full`（默认）不读任何几何，旧脚本输出不变；遮罩几何独立于 `source` / `dest`（始终按播放窗口坐标，不随 `dest` 缩放/平移），合成 alpha = `opacity × coverage`，区域外保持底图。
- `gradient` / `gradient_*` 专属 OVERLAY 的 fade；`blend_mode` 属于 ADJUST 轨或 OVERLAY fade；CAMERA 都不支持。`image` / `subtitle` 不接受 `mask_shape` / `mask_*`，字幕永远完整呈现（不做文字裁切）。

最小矩形划像（CAMERA 轨的**上层 B clip 节选**，不是完整脚本；下层 A 另放一条全屏 clip）：

```json
{
  "start_time": 2, "duration": 3, "transition": "cut", "mask_shape": "rect",
  "keyframes": [
    { "time": 0, "position": { "dx": 6, "dy": 2, "dz": 0 }, "yaw": 90, "pitch": 10, "roll": 0, "fov": 70, "zoom": 1,
      "opacity": 1, "mask_x": 0.5, "mask_y": 0.5, "mask_width": 0, "mask_height": 1 },
    { "time": 2, "position": { "dx": 6, "dy": 2, "dz": 0 }, "yaw": 90, "pitch": 10, "roll": 0, "fov": 70, "zoom": 1,
      "opacity": 1, "mask_x": 0.5, "mask_y": 0.5, "mask_width": 1, "mask_height": 1 },
    { "time": 3, "position": { "dx": 6, "dy": 2, "dz": 0 }, "yaw": 90, "pitch": 10, "roll": 0, "fov": 70, "zoom": 1,
      "opacity": 1, "mask_x": 0.5, "mask_y": 0.5, "mask_width": 1, "mask_height": 1 }
  ]
}
```

**覆盖链 Modifier（`modifier` / `suppress_modifiers`，clip 级）**

相机状态每帧 = 关键帧求值出的六参数（Base），再按 `modifier` 声明做一次合成。一个片段至多一条声明；要多个效果就写在多个片段上（按 `priority` 升序逐条合成）。Modifier 只作用主相机那份（玩家看到的主画面 = 最上层 lane 的相机）；次级 lane（分屏里的其他格子）保持各自片段的原始相机。

```json
"modifier": {
  "channels": ["yaw", "pitch"],   // 必填：生效通道（position / yaw / pitch / roll / fov / zoom 的子集；没列出的通道零改动）
  "mode": "add",                  // 可选：add / multiply / replace；缺省 = 偏移类 add、缩放类 multiply
  "priority": 100,                // 可选：应用序，升序（小者先）；缺省 0
  "yaw": 30,                      // 值键 = 通道名；position 写 {"x": …, "y": …, "z": …}
  "pitch": -15
}
```

| 想做的效果 | 写法 |
|---|---|
| 关键帧角度之外再加固定偏移 | `{"channels": ["yaw", "pitch"], "mode": "add", "yaw": 30, "pitch": -15}` |
| 整体拉近 / 推远（视野缩放） | `{"channels": ["fov"], "mode": "multiply", "fov": 0.7}`（fov 70 → 49） |
| 强制朝向 / 强制光学参数（覆盖关键帧动画） | `{"channels": ["yaw", "fov"], "mode": "replace", "yaw": 180, "fov": 30}` |
| 把机位钉在世界坐标 | `{"channels": ["position"], "mode": "replace", "position": {"x": 100, "y": 70, "z": 200}}` |
| 某片段不要任何 Modifier | 该片段写 `"suppress_modifiers": true` |

- `channels` 列出的通道必须给值、给了值的通道必须写进 `channels`（validate 会报）；`replace` 必须显式写（缺省是 `add` / `multiply`）。
- 声明只在**它所在片段的活跃窗内**生效（片段播完即失效；`duration < 0` 或 `loop_count: -1` 的片段自 `start_time` 起永续）——想「这一段有、那一段没有」，分别写在各自的片段上即可。
- morph 过渡的重叠窗内，前后两段的声明同时生效（叠加序 = `priority` 升序）。
- 并行脚本各自活跃片段的声明都会作用到主相机；`"suppress_modifiers": true` 是逃生门：本片段活跃时跳过**全部** Modifier。
- 不写这两个字段 = 行为与不启用覆盖链时逐位一致。

### 3.5 其他轨道（一句话速览）

| 轨道 | 用途 |
|---|---|
| `LETTERBOX` | 画幅黑边。关键帧 `aspect_ratio`：0 = 无黑边，2.35 = 宽银幕，窄于窗口时为左右黑边；`opacity`：0~1，默认1。两者均匀速线性插值，缓动由编辑器烘焙，不写 clip 级简写 |
| `AUDIO` | 播放音频（`sound` + `source: "file"`），keyframe 控制 `volume` 和空间 `x/y/z`。**音频文件必须用英文命名**（中文名在 Windows 下会解码失败） |
| `EVENT` | 时间点执行服务端命令（keyframe 的 `command`），如 `"say 开始！"` |
| `OVERLAY` | 覆盖层：`fade`（颜色遮罩，可全屏或局部）/ `image`（图片）/ `subtitle`（字幕）。**支持多条 OVERLAY 轨道同时渲染**（图片一条轨道、字幕一条轨道，靠轨道 `id` 区分） |
| `MOD_EVENT` | 第三方模组自定义事件 |

> **统一关键帧级调控（必读）**：**所有轨道一律用 `keyframes` 表达调控**——letterbox 的 `aspect_ratio`、EVENT 的 `command`、AUDIO 的 `volume`、OVERLAY 的 `opacity/x/y/scale` 全部写在关键帧上，**没有 clip 级简写**（旧写法如 letterbox clip 直接写 `aspect_ratio`、EVENT clip 直接写 `command` 已移除，写了会被校验拦下）。EVENT 片段的首尾关键帧允许 `command` 为空（仅时间占位，供编辑器绘制片段图形）。

### 3.5b OVERLAY 轨道详细（图片 / 字幕 / fade）

**位置 = 播放窗口百分比，缩放 = 素材原始像素的倍数**（0.3.6 起「画布 = 窗口」）——这是 OVERLAY 与 CAMERA 最大的区别（CAMERA 是方块坐标，OVERLAY 是画面坐标）。位置 `x`/`y` 直接用窗口百分比（与分辨率无关）；尺寸是素材自身像素的倍数，播放时整体乘**分辨率转义系数 `k = min(W播/W基, H播/H基)`**（`W基×H基` = `meta.base_resolution`，缺省 `1920×1080`；`W播×H播` = 播放窗口）。不再有「参考画布 / 适配（`fit`）」这一层。`1920×1080` 窗口 + 缺省 `base_resolution` 时 `k = 1`（与旧行为逐像素一致）；窗口宽高比与 `base_resolution` 不一致时单边留空（模型不画任何东西，要黑边就写 LETTERBOX 轨的 `aspect_ratio`），位置偏差 ≤ 留边厚度。

| 字段 | 说明 |
|---|---|
| `layer_type` | `"image"` 图片 / `"subtitle"` 字幕 / `"fade"` 颜色遮罩（全屏或局部） |
| `x` / `y` | 元素**中心**的**窗口**位置：`0.5` = 窗口正中（**默认 0.5**，0.3.6 起；旧默认 0），`1` = 元素中心到窗口右/下缘。可越界 |
| `anchor_x` / `anchor_y` | **锚点**（缩放绕点），元素自身归一化，默认 `0.5` = 绕元素中心缩放（同 CSS `transform-origin`） |
| `scale_x` / `scale_y` | 相对**素材原始像素尺寸**的倍数：图片 = **原图像素 × 乘数**（`1` = 原尺寸，`0.5` = 半尺寸），上屏还要再乘 `k`。**图片按原图分辨率载入，不要写死像素尺寸** |
| `source` | **取材**（仅 image）：`{"x":0,"y":0,"w":1,"h":1}` = 素材归一化子矩形（原点为素材左上角），如左半 `{"x":0,"y":0,"w":0.5,"h":1}`；字幕完整呈现，不消费此字段 |
| `opacity` | 透明度（0~1，**默认 1**）。**淡入淡出 = 关键帧里写 opacity 0→1→0**，代码层不叠加其他淡化 |
| `path` | 图片文件名，支持 **PNG / GIF**（GIF 自动拆帧轮播），放 `<游戏目录>/immersive_cinematics/resource/`，英文命名 |
| `text` | 字幕文本（`\n` 换行）。可以写 `"@lang:<key>"` 引用文本资源：译文放 `resource/lang/<语言>.json`，回退链 = 当前语言 → `en_us` → 原样显示引用串。字幕/描述支持，`name`/`id`/`author` 不翻译 |
| `z_index` | 层级，大者在上（**默认 10**，0.3.6 起）。只在本模组覆盖层之间排序，不改原版 HUD。普通 fade 不写时保持 10；字幕示例写 100；转场 fade 要压住字幕时由作者**显式**写 1000（不是自动最高层）。同一条 OVERLAY 轨同一时刻只消费首个活跃 clip，同时显示请各用独立轨 |

> `fade` 是效果层（铺满窗口），`x/y/anchor/scale` 对它不生效；它读 `opacity`，声明高级颜色遮罩时再读 `color` / `mask_*` / `gradient_*`（见下方 fade 高级颜色遮罩）。`fade` 的基色在 clip 级 `color`（如 `"#000000"`），关键帧 `color` 可覆盖；渐变模式由 `stops` 决定颜色。
>
> **编辑基准分辨率**：脚本 `meta` 里可选写 `"base_resolution": {"w":2560,"h":1440}`（正整数，不设人为上限；当前存储表示范围 `1` ~ `2147483647`）声明构图分辨率；播放时 `k = min(W播/W基, H播/H基)` 把基准构图转义到窗口。不写 = `1920×1080`。**`fit` 已移除**：元素框由素材派生，形状由 `scale_x` / `scale_y` 表达；写 `fit` 会被校验拦下。

#### fade 高级颜色遮罩（0.3.6）

仍然是 `layer_type: "fade"`，不新增图层类型。下面这些 fade 高级字段只由 OVERLAY 的 fade 消费（clip 级 `color` 是通用字段，image / subtitle 上写不会报错但也不消费）；**在 OVERLAY 内，`gradient` / `mask_shape` / `blend_mode`、`mask_*` / `gradient_*` 以及关键帧 `color` 写在 image / subtitle 上会被解析 / 校验直接拒绝**。它们只改变颜色层的覆盖范围与混色，**不裁切字幕或画面**，字幕仍完整呈现。CAMERA 片段另有同名的 `mask_shape` / `mask_*` lane 遮罩（见 §3.4），归属不同、不能互写；CAMERA 不接受 `gradient` / `gradient_*` / `blend_mode`。

| 级别 | 字段 | 缺省 | 说明 |
|---|---|---|---|
| clip | `color` | `#000000` | `#RRGGBB`（`#` 可省）；纯色模式的基色 / 关键帧缺省回落，渐变模式不参与取色 |
| clip | `gradient` | 无（纯色） | `{type: "linear"｜"radial", stops: [{offset, color}, ...]}` |
| clip | `mask_shape` | `full` | `full` / `rect` / `circle`（颜色覆盖范围） |
| clip | `blend_mode` | `normal` | `normal` / `multiply` / `screen` / `soft_light` / `overlay` |
| 关键帧 | `color` | 回落 clip `color` | 纯色模式逐 RGB 通道线性插值；渐变模式不消费（显式值仍校验格式） |
| 关键帧 | `mask_x` / `mask_y` | `0.5` | 覆盖中心，窗口比例，有限值，可越界 |
| 关键帧 | `mask_width` / `mask_height` | `1` | `rect` 宽 / 高占窗口比例，有限且非负，不设上限；任一为 0 不绘制 |
| 关键帧 | `mask_radius` | `0.5` | `circle` 半径 / 窗口**短边**比例，有限且非负；0 不绘制 |
| 关键帧 | `gradient_angle` | `0` | `linear` 方向角（度），有限值；0 = 向右，90 = 向下 |
| 关键帧 | `gradient_center_x` / `gradient_center_y` | `0.5` | 渐变中心，窗口比例，有限值，可越界 |
| 关键帧 | `gradient_radius` | `0.5` | `radial` 半径 / 窗口短边比例，有限且必须 > 0 |

字段归属（OVERLAY fade）：`gradient` / `mask_shape` / `blend_mode` 只写 clip 级；`mask_*` / `gradient_*` 只写关键帧；`color` 两级都可写（关键帧缺省回落 clip）。写错层级会被解析 / 校验拒绝。这里的 `mask_*` 是颜色层几何；CAMERA 的同名 lane 遮罩见 §3.4，两者不互通。

- **stops**：至少 2 个、不设数量上限；`offset` 有限且 `0`~`1`、严格递增；首尾不必是 `0` / `1`，范围外保持端点。stops 定义在 clip 级、整段固定；关键帧只动几何，不能改 stops 数量或内容。
- **几何**（`W` / `H` = 窗口宽 / 高）：`rect` 中心 `(mask_x×W, mask_y×H)`、半宽 `mask_width×W/2`、半高 `mask_height×H/2`；`circle` 同中心、半径 `mask_radius×min(W,H)`（非方形窗口仍是真圆）。`linear` 用 `t = 0.5 + dot(p-center, (cosθ,sinθ)) / (|cosθ|W + |sinθ|H)`；`radial` 用 `t = distance(p-center) / (gradient_radius×min(W,H))`，再钳到 0~1。
- **混合**：`normal` 直替换；`multiply = base×src`；`screen = 1-(1-base)(1-src)`；`soft_light` 按 W3C——分支看 src、D 曲线作用于 base（`D(x)=x≤0.25 ? ((16x-12)x+4)x : sqrt(x)`）；`overlay` 分支看 base（`2×base×src` / `1-2(1-base)(1-src)`）。最终 `RGB = mix(base, B(base,src), opacity×coverage)`，不改变区域外像素或 alpha。fade **没有** `blend_amount`，混合强度就是关键帧 `opacity`。
- **路径与精度**：纯色 + `full` + `normal` 仍走旧的全屏 fill 快路径；多色渐变烘成 1024×1 采样表。这是实现采样精度，任意密集 stops 不保证逐像素与解析式完全一致（不声明误差上限）。
- **示例**：`cinematics/0.3.6/tests/overlay/test_color_mask.json`（线性 / 径向 / rect / circle / 五种混合 / 几何关键帧）与 `cinematics/0.3.6/tests/overlay/test_overlay_zindex.json`（z 层级）。

**多轨道写法**（图片 + 字幕同时显示，各自一条 OVERLAY 轨道）：

```json
{ "type": "overlay", "id": "overlay_1",
  "clips": [{ "start_time": 0, "duration": 12, "layer_type": "image", "path": "pic.png", "z_index": 20,
    "keyframes": [
      { "time": 0,  "x": 0.0, "y": 0.0, "scale_x": 0.5, "scale_y": 0.5, "opacity": 0 },
      { "time": 1,  "x": 0.03, "y": 0.03, "scale_x": 0.55, "scale_y": 0.55, "opacity": 1 },
      { "time": 11, "x": 0.4, "y": 0.3, "scale_x": 0.6, "scale_y": 0.6, "opacity": 1 },
      { "time": 12, "x": 0.45, "y": 0.45, "scale_x": 0.5, "scale_y": 0.5, "opacity": 0 } ] }] },
{ "type": "overlay", "id": "overlay_2",
  "clips": [{ "start_time": 0, "duration": 12, "layer_type": "subtitle", "text": "副标题", "z_index": 100,
    "keyframes": [
      { "time": 0,  "x": 0.02, "y": 0.5, "opacity": 0 },
      { "time": 1,  "x": 0.02, "y": 0.5, "opacity": 1 },
      { "time": 11, "x": 0.4,  "y": 0.6, "opacity": 1 },
      { "time": 12, "x": 0.4,  "y": 0.6, "opacity": 0 } ] }] }
```

**写 OVERLAY 的要点**：
1. **x/y = 0.5 即窗口居中**（元素中心锚点）；贴边需按元素尺寸/2 折算（如贴左缘 = 元素半宽），避免元素移出窗口
2. 想"中途消失再出现"：关键帧里 opacity 置 0 持续几秒再回 1
3. 图片和字幕各用一条轨道（同轨道多个 clip 同时段会互相抢占，不要那样写）
4. **插值 = 匀速线性**（0.3.6 起）：相邻两个关键帧之间匀速直线运动，**没有** clip 级 `interpolation` 字段。要"缓动/速度曲线"：在编辑器里选曲线，由编辑器把补出的关键帧写进脚本（脚本里是显式关键帧），运行时不求值——不要手写 `interpolation` 字段
5. **不写 opacity 就是全不透明**（0.3.6 起默认 1）：做淡入淡出必须显式写 `opacity` 关键帧；不写 `x`/`y` 则元素居中（默认 0.5）

### 3.6 触发器（什么时候播）

`meta.triggers` 数组，常用：

| 类型 | 触发时机 | 关键条件字段 |
|---|---|---|
| `login` | 玩家登录 | 无 |
| `location` | 进入区域 | `dimension` / `position`+`radius` / `corner1`+`corner2` |
| `structure` | 进入结构 | `structure`（如 `"village"`）+ `radius` |
| `advancement` | 获得进度（事件携带 id 匹配） | `advancement` |
| `biome` | 进入群系 | `biome` |
| `entity_kill` | 击杀实体 | `entity`（+场景条件 `dimension`/`biome`/`position`） |
| `death` | 玩家死亡 | 场景条件全可选：`dimension` / `biome` / `position`+`radius` / `corner1`+`corner2`（按死亡时刻记录判定） |
| `entity_interact` / `block_interact` | 右键交互目标 | `target` |
| `item_on_interact` | 持物交互 | `item` + `target`（+`target_type`；`"item": ""` = 空手） |
| `item_craft` | 合成物品 | `item` |
| `item_use` | 右键按下 | `item` |
| `item_consume` | 用完（吃完/喝完） | `item` |
| `item_release` | 弓/弩/三叉戟/望远镜松手 | `item` |
| `item_instant_use` | 扔投掷物（雪球/珍珠/药水/经验瓶） | `item` |
| `item_use_interrupt` | 吃一半松手/中断使用 | `item` |
| `item_pickup` / `item_drop` | 拾取/丢弃物品 | `item` |
| `xp` | 经验达标（轮询） | `level` / `total` |
| `dimension` | 驻留维度（轮询） | `dimension` |
| `observation` | 准星注视目标（轮询） | `target` + `target_type` + `reach` |
| `facing` | 视线朝向落在区间（轮询） | `yaw1` / `pitch1` / `yaw2` / `pitch2` |
| `all_of` | 多重条件 AND（轮询） | `list`（子条件数组） |
| `any` | 多重条件 OR（轮询） | `list`（子条件数组） |

组合器（`all_of` / `any`）的 `list` 子条件可以是**轮询类**触发器（`location` / `facing` / `observation` / `structure` / `biome` / `xp` / `inventory` / `gamestage` / `dimension`）、**前置条件**（`{"type":"script_played","conditions":{"script":"脚本 id"}}`，锁存语义：发生过即真），也可以是**另一个组合器**（嵌套，最多 8 层、含顶层自身）；事件类不可用。

通用字段：`repeatable`（可重复触发）、`delay`（延迟秒）、`on_enter`（仅进入时触发，位置类用）、`poll_interval`（**检测频率覆盖**，tick，`1`~`600`，**仅轮询类**有效；缺省回落到脚本级 `meta.trigger_poll_interval` → 类型级默认，事件驱动写 = 无效）。完整 27 种见 `docs/TRIGGER_TYPES.md`。

### 3.7 判定规则（`rules`：片段执行中满足条件就做事）

触发器管「脚本什么时候开始播」，**规则管「播到某段时满足条件做什么」**。写在**片段**上（clip 级 `rules` 数组，任意轨道都可挂）：

```json
"rules": [
  { "if":   { "type": "location", "position": { "x": 130, "y": 64, "z": -170 }, "radius": 4 },
    "do":   [ { "type": "play_sound", "sound": "minecraft:ambient.cave" } ], "cooldown": 5 },
  { "until": { "type": "time", "at": 12.0 },
    "do":    [ { "type": "goto_clip", "track": "CAMERA", "at": 20 } ],
    "timeout": 20, "on_timeout": [ { "type": "exit" } ] }
]
```

- 两种形态：`if`（条件成立那一刻做 `do`，不冻结播放）；`until`（条件不成立就**冻结本实例播放时钟**等它成立——等待点；`timeout` 真实时钟先到则走 `on_timeout`）。
- 判定源与触发器同词汇：`time`（片段本地时钟 `at` / `from`+`to`）、`location`、`facing`、`biome`、`dimension`、`camera_pos`、`camera_facing`、`camera_fov` + 组合器 `all_of` / `any`。
- 动作 v1 九种（只作用本播放实例）：`camera_set`（`channels` + 可选 `mode` / `priority` + 通道值：触发起持续叠加到片段结束）、`overlay_set`（`layer_type:"fade"` + `opacity` / `color`）、`adjust_set`（基础校色 15 通道，如 `{"exposure":0.5}`）、`goto_clip`（`{"track":"CAMERA","at":秒}` 时间寻址）、`pause` / `resume`、`exit`（声明式退出，`hold_at_end` 也照退）、`play_sound`（`sound` + 可选 `volume` / `pitch`）、`emit_command`（`command`，服务端执行、仅该脚本观看者）。
- 无限等待用 `until` 不写 `timeout`（**没有** `hold` 动作）；动作失败只记限频日志、播放照走（不中断）。
- **结束与退出**：脚本播到**执行内容末端**（所有片段走到尽头、有限循环圈数计满、`goto_clip` 跳转后的新路径也走到尽头、无挂起）就自然结束——`timeline.total_duration` 不是结束判据（只是统计 / 信息位，不参与判定）。无限内容（`loop_count: -1` / `macro_loop_count: -1`）永不自然结束，要用规则 `exit`（③族）或让玩家跳过/打断（①族）收尾；`hold_at_end: true` = 自然结束后停在最后一帧等干预。

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
| **注视追踪** | `look_at: "entity"` + `look_at_selector`，位置走关键帧，镜头自动锁定目标（也可 `look_at: "coordinate"` 锁定固定点/结构、`look_at: "block"` 锁定就近方块中心） |
| **看指定部位** | `look_at: "entity"` + `look_at_part: {"y": 90}`（看头）、`{"y": 0}`（看脚）：实体包围盒内按每轴百分比取点，越界钳制在体内 |
| **手持感** | `cam_breath_enabled: true` + `cam_breath_intensity: 0.05`，让固定机位"活"起来 |
| **黑场/白场转场** | OVERLAY 轨放两个 `fade` clip（压场 `opacity 0→1`、亮起 `1→0`），黑场 `color: "#000000"`、白场 `"#FFFFFF"`；两段 CAMERA 的硬切藏在黑/白里（见 §4.1） |

**精细化的关键**：
1. 一段 motion 至少 3 个关键帧（起→中→止），中间帧做缓急变化，比两点直线"有呼吸"
2. 段落之间用 `morph` 过渡衔接，避免硬切跳变——**过渡窗口以两段交界（前一段末尾）为中心、向两侧各展开 t/2**：`transition` / `transition_duration` 写在前一段上，后一段的 `start_time` 就填交界时刻（= 前一段末尾；编辑器自动对齐）。进阶写法（曲线 / 分参数 / 打断）见 §4.2
3. 特写/情绪段落用变焦 + 微俯 + 慢速（1 格/秒），全景段落用快一点（2~3 格/秒）
4. 长脚本分段设计：开场全景 → 中段叙事特写 → 结尾收回

### 4.1 黑场 / 白场转场（OVERLAY 轨预配置 clip）

黑场 / 白场不是独立机制，就是 **OVERLAY 轨上两个"预配置好的 fade clip"**——`layer_type: "fade"`，黑场 `color: "#000000"`、白场 `color: "#FFFFFF"`，只差一个颜色。作者只决定"放哪、多长"：

- **压场 clip**：`opacity` 关键帧 `0 → 1`（画面渐黑 / 渐白）
- **亮起 clip**：`opacity` 关键帧 `1 → 0`（画面渐亮）
- 两个 clip **接在同一个 OVERLAY 轨道**上（`亮起.start_time = 压场.start_time + 压场.duration`），衔接点两侧都是 `opacity 1` → 中间整段全黑/全白，把两段 CAMERA 之间的硬切藏在里面。

```json
{ "type": "OVERLAY",
  "clips": [
    { "start_time": 0, "duration": 4, "layer_type": "fade", "color": "#000000", "z_index": 1000,
      "keyframes": [
        { "time": 0, "opacity": 0 },
        { "time": 2, "opacity": 1 },
        { "time": 4, "opacity": 1 } ] },
    { "start_time": 4, "duration": 4, "layer_type": "fade", "color": "#000000", "z_index": 1000,
      "keyframes": [
        { "time": 0, "opacity": 1 },
        { "time": 2, "opacity": 0 },
        { "time": 4, "opacity": 0 } ] }
  ] }
```

**要点**：
1. **关键帧 `time` 是 clip 内偏移**（每个 clip 都从 0 开始），不是全局时间——第二段 `start_time: 4`，其关键帧仍是 `0 / 2 / 4`。
2. 想"黑场多停一会"：压场 clip 末尾多写一个 `opacity 1` 的关键帧（如上 `2 → 4` 保持全黑），或把 `duration` 拉长。
3. **是否压住字幕由作者显式决定**：普通 fade 不写 `z_index` 时保持默认 `10`；字幕示例写 `100`；转场 fade 要盖住字幕就写 `1000`（如上例）。`z_index` 大者在上，但**不是自动最高层**，也不改原版 HUD。
4. 黑场与白场**没有任何实现差异**，只换 `color`；两个脚本实例见 `cinematics/0.3.6/tests/transition/test_fade_black.json` / `test_fade_white.json`。
5. **同时显示的层必须各用独立 OVERLAY 轨道**：同一条轨同一时刻只消费首个活跃 clip，`z_index` 只排层序，不能让同轨第二个 clip 同时渲染。转场 fade、普通 fade、字幕同屏时请分轨。

### 4.2 过渡（morph）的进阶写法

`transition` 只写三个开关就够日常用（`"morph"` + `transition_duration`）；想让转场更讲究，再加这三个字段（都写在前一段上）。这里的 `transition` **只改相机六参数，不叠化/划像画面**；画面转场见 §4.3。常用写法：

| 想达到的效果 | 怎么写 |
|---|---|
| **节奏**（慢起快收 / 两端都缓） | `"transition_curve": { "type": "smoothstep" }`（可选 `ease_in` / `ease_out` / `ease_in_out` / `cubic_bezier`）；缺省 = 线性 |
| **某些通道不动 / 直接切** | `"transition_params": { "roll": { "mode": "hold" }, "fov": { "mode": "cut" } }`——`hold` = 过渡期间保持旧值（转完再切），`cut` = 交界处直接切；不写的通道照常混合 |
| **某通道单独时长**（如位置慢、变焦快） | `"transition_params": { "position": { "duration": 2 }, "fov": { "duration": 0.4 } }`（秒，窗口仍以交界为中心） |
| **连续转场被打断时**（A→B 还没转完就进了 B→C） | `"transition_interrupt": "blend"`（默认，从当前混合中间态平滑转向新目标、时长重算）/ `"snap"`（立刻硬切到新目标） |

**要点**：
1. **缺省 = 全六通道同一权重、线性、时长 `transition_duration`**——不写新字段的脚本行为与以前完全一致。
2. 过渡只改**相机六参数在两段交界前后**的取值；段内关键帧插值不受影响（缓动仍由编辑器烘焙成关键帧），画面也不会因此叠化/划像。
3. `transition_interrupt` 只在"过渡进行中又开始新过渡"时生效；没有被谁打断的过渡照常按声明时长走完。

```json
{ "start_time": 8, "duration": 6,
  "transition": "morph", "transition_duration": 1.5,
  "transition_curve": { "type": "ease_in_out" },
  "transition_params": { "roll": { "mode": "hold" } },
  "keyframes": [ /* … */ ] }
```

### 4.3 画面转场（叠化 / 划像 / 黑场）

画面转场不用 `transition`，也**没有** `TRANSITION` 轨、`transition_in` / `transition_out` 或运行时生成器——就是普通 clip + 关键帧（下表 fixture 均在 `cinematics/0.3.6/tests/transition/` 下）：

| 转场 | 最小写法 | 正式 fixture |
|---|---|---|
| **叠化** | 同一 CAMERA 轨两条全屏 clip：下层 A 全程 `opacity: 1`，上层 B 在重叠窗口 `opacity: 0 → 1` | `cinematics/0.3.6/tests/transition/test_dissolve.json` |
| **矩形划像** | 上层 B 写 clip 级 `"mask_shape": "rect"`，关键帧 `mask_width`（或 `mask_height`）`0 → 1` | `cinematics/0.3.6/tests/transition/test_wipe.json` |
| **圆形划像** | 上层 B 写 `"mask_shape": "circle"`，关键帧 `mask_radius` `0 → 1.5`（按窗口短边真圆） | `cinematics/0.3.6/tests/transition/test_wipe.json` |
| **黑场 / 白场** | OVERLAY 轨 `fade` 压场 `0 → 1`、亮起 `1 → 0`，转场 fade 显式 `z_index: 1000` | `test_fade_black.json` / `test_fade_white.json` |
| **显式层级** | 普通 fade 默认 `10`、字幕 `100`、转场 fade `1000`，各自独立 OVERLAY 轨 | `test_transition_zindex.json` |

**叠化的关键**：底 A 必须保持不透明（`opacity: 1`），只让上层 B 淡入。普通 source-over 结果 = `B×a + A×(1−a)`；如果写成 A `1→0` + B `0→1`，两层半透明处会漏出原版基画面（权重 `(1−a)(1−b)`），且不是线性交叉。不要依赖运行时配对/归一，脚本里直接写显式关键帧。

CAMERA 轨 `clips` 节选（不是完整脚本；两条 clip 放在同一 CAMERA 轨，A 在下、B 在上）：

```json
[
  {
    "start_time": 0, "duration": 6, "transition": "cut",
    "keyframes": [
      { "time": 0, "position": { "dx": 0, "dy": 2, "dz": -6 }, "yaw": 0, "pitch": 10, "roll": 0, "fov": 70, "zoom": 1, "opacity": 1 },
      { "time": 6, "position": { "dx": 0, "dy": 2, "dz": -6 }, "yaw": 0, "pitch": 10, "roll": 0, "fov": 70, "zoom": 1, "opacity": 1 }
    ]
  },
  {
    "start_time": 4, "duration": 6, "transition": "cut",
    "keyframes": [
      { "time": 0, "position": { "dx": 6, "dy": 2, "dz": 0 }, "yaw": 90, "pitch": 10, "roll": 0, "fov": 70, "zoom": 1, "opacity": 0 },
      { "time": 2, "position": { "dx": 6, "dy": 2, "dz": 0 }, "yaw": 90, "pitch": 10, "roll": 0, "fov": 70, "zoom": 1, "opacity": 1 },
      { "time": 6, "position": { "dx": 6, "dy": 2, "dz": 0 }, "yaw": 90, "pitch": 10, "roll": 0, "fov": 70, "zoom": 1, "opacity": 1 }
    ]
  }
]
```

> 划像的最小 clip 写法见 §3.4「画面合成与 lane 遮罩」；完整可运行版本以正式 fixture 为准。

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
            "transition": "morph",
            "transition_duration": 1.0,
            "keyframes": [
              { "time": 0,   "position": { "dx": 24, "dy": 2, "dz": 0 },  "yaw": 90,  "pitch": 5,  "roll": 0, "fov": 70, "zoom": 1.0 },
              { "time": 3,   "position": { "dx": 18, "dy": 2, "dz": 0 },  "yaw": 90,  "pitch": 5,  "roll": 0, "fov": 68, "zoom": 1.1 },
              { "time": 6,   "position": { "dx": 10, "dy": 2, "dz": 0 },  "yaw": 90,  "pitch": 8,  "roll": 0, "fov": 65, "zoom": 1.2 }
            ]
          },
          {
            "start_time": 5.5,
            "duration": 5,
            "transition": "cut",
            "keyframes": [
              { "time": 0, "position": { "dx": 10, "dy": 2, "dz": 0 },  "yaw": 90, "pitch": 8,  "roll": 0, "fov": 65, "zoom": 1.2 },
              { "time": 5, "position": { "dx": 6,  "dy": 2, "dz": 6 },  "yaw": 135, "pitch": 10, "roll": 0, "fov": 62, "zoom": 1.3 }
            ]
          },
          {
            "start_time": 10.5,
            "duration": 4,
            "transition": "cut",
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
            "keyframes": [
              { "time": 0, "position": { "dx": -12, "dy": 2, "dz": 0 },  "yaw": -90, "pitch": 5,  "roll": 0, "fov": 70, "zoom": 1.0 },
              { "time": 5, "position": { "dx": 0,   "dy": 12, "dz": 0 },  "yaw": 180, "pitch": 55, "roll": 0, "fov": 80, "zoom": 1.0 }
            ]
          },
          {
            "start_time": 13,
            "duration": 5,
            "transition": "cut",
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
| 14 | 字幕淡出/淡入的 opacity 落在 0.001~0.015 区间（如 `0.005`） | 该区间会被 MC 字体渲染补全为完全不透明（透明度低于 1.6% 时闪全亮）。**淡入淡出直接写 0 和 1**，不要写中间小值 |
| 15 | 叠化写成 A `1→0` + B `0→1`（两边同时淡出） | 底 A 保持 `opacity: 1`，只让上层 B `0→1`；否则两层半透明处会漏出原版基画面 |
| 16 | 把 fade 的 `gradient` / `gradient_*` 写到 CAMERA，或以为 CAMERA 的 `mask_*` 能裁切字幕/图片 | CAMERA 只支持 clip 级 `mask_shape` + 关键帧 `mask_x/y/width/height/radius`（lane 几何遮罩，不是颜色层/文字裁切）；`gradient` / `gradient_*` 专属 OVERLAY fade，`blend_mode` 属于既有 ADJUST 轨或 fade，CAMERA 都不支持 |
| 17 | 同一条 OVERLAY 轨放两个同时显示的 clip | 每条轨同一时刻只消费首个活跃 clip；字幕 / 普通 fade / 转场 fade 同屏时各用独立轨 |
| 18 | 以为 `transition: "morph"` 会叠化画面 | 它只混合相机六参数；画面叠化/划像用重叠 clip + `opacity` 或 `mask_shape`（§4.3） |

---

## 7. 交付前校验清单

- [ ] `meta.version = 3`，`id` 只含字母/数字/下划线
- [ ] `total_duration` = 最后一段 clip 的结束时间（声明时长与内容末端一致最省心；不一致时脚本按**实际执行内容**结束——`total_duration` 只是统计位，不参与结束判定）
- [ ] 每个 clip：`duration ≠ 0`，**所有轨道都有 `keyframes`**（letterbox/EVENT/AUDIO/OVERLAY 同 CAMERA，无 clip 级简写）
- [ ] 每个 clip 内：keyframe 的 `time` 严格递增
- [ ] 逐段核对：相机方位 ↔ yaw 是否匹配（§1.4 对照表）
- [ ] 逐段核算：位移距离 ÷ 时长 ∈ 五档合理范围
- [ ] pitch 在 ±90 内；roll 用后归零；fov 30~110；zoom 1~2
- [ ] 特写段落结尾恢复 fov 70 / zoom 1 / roll 0
- [ ] triggers 的 `type` 拼写正确、条件字段齐全（见 TRIGGER_TYPES.md）

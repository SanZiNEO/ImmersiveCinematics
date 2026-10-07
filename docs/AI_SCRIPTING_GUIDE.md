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

> **远距离场景记得保留预加载**：`meta.preload` 默认 `true`，但只有存在**非空 CAMERA 轨道**的脚本才会实际触发预加载（纯 HUD/字幕/事件脚本不会）。如果镜头要飞到玩家视距外（跨区块/跨维度），**不要写 `"preload": false`**；只有确认该脚本不需要预加载（例如纯 HUD/字幕/本地小范围）时才关掉，避免不必要开销。

> **`interruptible` 与 `priority` 必读（播放队列规则）**：
> - **可打断（`interruptible: true`，默认）** = 区域切换型脚本：新脚本请求时**立即替换**当前脚本（打断就替换）。适合"进 A 区播 A、进 B 区播 B"的场景。
> - **不可打断（`interruptible: false`）** = 强制过程型脚本：播放期间其它脚本请求一律**排队**（容量 8），等它播完自动接播。适合强制观看的过场、循环氛围镜头。
> - **`priority`**（int，默认 0，可选）：数值越大越优先，**仅用于排队顺序**（高优先先接播）。注意：优先级**不能大于打断**——不可打断的脚本永远不会被抢占，`priority` 再高也只能排队。
> - 玩家视角（无脚本覆盖）由**时间空隙**自然产生：相机片段之间留空档，那段玩家恢复自由视角。

> **`hide_skip_hud` 必读**：长按跳过键的提示（右下角图标+进度环）由 `hide_skip_hud` 控制——**不写时跟随 `hide_hud` 被隐藏**。凡是 `hide_hud: true` 的脚本，想保留跳过提示就必须显式写 **`"hide_skip_hud": false`**，否则玩家看不到"按 C 跳过"的提示。

> **写完必须自查**：脚本写完后在游戏内执行 `/icinematics validate <文件名>`，**直到输出"§a校验通过"才能交付**。该命令会一次列出所有问题（结构错误/缺失字段/语义错误/缺省字段提示），逐条修掉即可——不要在没有校验的情况下直接交付脚本。

### 3.3 timeline

| 字段 | 说明 |
|---|---|
| `total_duration` | 总时长秒。正数 = 定长；负数 = 无限时长（脚本永不宣布结束，但**不会**回卷循环） |
| `tracks` | 轨道数组。CAMERA 可多条（每条 = 一个画面 lane，轨道层级后面的在上；最后一轨的顶层 clip 决定主相机）；LETTERBOX/EVENT 建议 1 条，AUDIO/OVERLAY/MOD_EVENT 不限 |
| `loop_start` / `loop_end` | **宏观循环区间 `[a,b]`**（秒），仅在 `meta.macro_loop=true` 时被读取：`a` 缺省 `0`，`b` 缺省 = 宏观末端。写 `loop_end` 即按 `[loop_start, loop_end]` 重复（子区间循环）。编辑器 A-B 快捷键（I / O）写的就是这两个字段 |

### 3.4 CAMERA 轨道（核心）

**clip 级字段**：

| 字段 | 说明 |
|---|---|
| `start_time` | 在全局时间线的起点（秒） |
| `duration` | 持续时长。正数 = 定长；负数 = 无限 |
| `transition` | `"cut"` = 硬切（默认）；`"morph"` = 本 clip 尾部与下一 clip 的交叉淡化过渡（重叠 t/2） |
| `transition_duration` | **仅 morph 时有效**，过渡时长秒（后段 start 需 = 本段末尾 − t/2）。cut 时不要写 |
| `loop` / `loop_count` | 循环生命周期：`loop: true` + `loop_count: -1` = 无限循环（配合 follow/look_at 可做常驻跟随视角）；正整数 = 播 N 个周期后停在末帧；`loop_count: 0` 非法（按 1 处理） |
| `loop_mode` | 循环时间映射：`"repeat"`（默认）= 从头到尾重复；`"pingpong"` = 往复折返（监控视角来回摇，关键帧只写半程即可） |
| `curve` | 贝塞尔路径（可选，见 SCRIPT_FORMAT.md） |
| `cam_breath_enabled` | 呼吸扰动（手持感，clip 级），配 `cam_breath_intensity`（0.05~0.1）、`cam_breath_seed`、`cam_breath_type`（`perlin` 默认 / `perlin_axis` / `sine` / `trauma`）、`cam_breath_speed`（越大晃越快）；`trauma` 额外配 `cam_breath_trauma`（初始冲击 0~1）与 `cam_breath_decay`（衰减速率）；同 seed 重放一致 |

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
| `position_mode` | `"relative"`（默认）= 相对基准点（position 写 dx/dy/dz；基准默认 = 玩家激活位置，可用 `relative_origin` 指定坐标/结构中心）；`"absolute"` = 世界坐标（写 x/y/z） |
| `follow` | `"none"`（默认）= 位置走关键帧；`"entity"` = 位置跟随实体（position 的 dx/dy/dz 变成相对实体脚底的偏移）。**follow↔普通关键帧之间是平滑过渡**（两端都是世界坐标，直接插值） |
| `follow_selector` | 跟随目标选择器，默认 `@p`（见下方"目标选择器"） |
| `look_at` | `"none"`（默认）= 用 yaw/pitch；`"coordinate"` = 注视固定点（xyz 或结构中心）；`"entity"` = 注视实体正中心。**look_at 关键帧的目标点之间插值，切换/开关平滑过渡**（如 0s 看玩家 → 15s 看铁傀儡） |
| `look_at_selector` | 注视目标选择器（`entity` 模式），默认 `@p` |
| `look_at_target_x/y/z` | 注视固定坐标（`coordinate` 模式）。**与 `look_at_target_structure` 互斥** |
| `look_at_target_structure` | 注视结构中心（`coordinate` 模式）：填结构 id 如 `minecraft:village`。播放时服务端自动定位**结构 bounding box 中心**并替换为坐标（就近搜索，原版 /locate 同范围，多人服务器也生效）；编辑器里是注册表下拉补全。**与坐标互斥**：指定结构后定位失败不回退坐标，该端无注视目标（回退角度插值） |

**目标选择器**（`follow_selector` / `look_at_selector`）：`@p`/`@s`（玩家）、`@e`（离相机最近实体）、`@e[type=minecraft:sheep]`（类型过滤后就近，模组 boss 用其注册 id）、`@e[name=自定义名]`（命名牌名字过滤后就近）、`uuid:xxxxxxxx-…`（UUID 直绑）——就近基准为相机当前位置。

规则：
- `time` 是 **clip 内**偏移（从 0 到 duration），必须严格递增
- 关键帧之间所有值**匀速线性插值**（两点定一段运动）；0.3.6 起运行时统一线性、无 clip 级 `interpolation` 字段——缓动/速度曲线由编辑器烘焙成显式关键帧
- 至少 1 个关键帧；想表现"静止"就用首尾两个相同值

### 3.5 其他轨道（一句话速览）

| 轨道 | 用途 |
|---|---|
| `LETTERBOX` | 宽银幕黑边。keyframe 的 `aspect_ratio`：0 = 无黑边，2.35 = 电影宽银幕 |
| `AUDIO` | 播放音频（`sound` + `source: "file"`），keyframe 控制 `volume` 和空间 `x/y/z`。**音频文件必须用英文命名**（中文名在 Windows 下会解码失败） |
| `EVENT` | 时间点执行服务端命令（keyframe 的 `command`），如 `"say 开始！"` |
| `OVERLAY` | 覆盖层：`fade`（全屏颜色）/ `image`（图片）/ `subtitle`（字幕）/ `pip`（画中画）。**支持多条 OVERLAY 轨道同时渲染**（图片一条轨道、字幕一条轨道，靠轨道 `id` 区分） |
| `MOD_EVENT` | 第三方模组自定义事件 |

> **统一关键帧级调控（必读）**：**所有轨道一律用 `keyframes` 表达调控**——letterbox 的 `aspect_ratio`、EVENT 的 `command`、AUDIO 的 `volume`、OVERLAY 的 `opacity/x/y/scale` 全部写在关键帧上，**没有 clip 级简写**（旧写法如 letterbox clip 直接写 `aspect_ratio`、EVENT clip 直接写 `command` 已移除，写了会被校验拦下）。EVENT 片段的首尾关键帧允许 `command` 为空（仅时间占位，供编辑器绘制片段图形）。

### 3.5b OVERLAY 轨道详细（图片/字幕）

**坐标 = 参考画布百分比（0~1），与分辨率无关**——这是 OVERLAY 与 CAMERA 最大的区别（CAMERA 是方块坐标，OVERLAY 是画面坐标）。参考画布 = 固定 **16:9**、像素锚点 **1920×1080**（0.3.6 起，全局常量）：**1920×1080 窗口下画布 = 屏幕**（与旧「屏幕百分比」完全一致）；非 16:9 窗口按 Fit 等比缩放居中，宽高比不一致的余量是黑边（由画幅层 letterbox 绘制 —— LETTERBOX 轨有活跃 clip 时才绘制，不属画布），元素按画布坐标落在画布内 —— 同一套参数在任何设备上构图一致。

| 字段 | 说明 |
|---|---|
| `layer_type` | `"image"` 图片 / `"subtitle"` 字幕 / `"fade"` 全屏色 / `"pip"` 画中画 |
| `x` / `y` | 元素**中心**的画布位置：`0.5` = 画布正中（**默认 0.5**，0.3.6 起；旧默认 0），`1` = 元素中心到画布右/下缘。可越界 |
| `anchor_x` / `anchor_y` | **锚点**（缩放绕点），元素自身归一化，默认 `0.5` = 绕元素中心缩放（同 CSS `transform-origin`） |
| `scale_x` / `scale_y` | 相对**基准尺寸**的倍数：图片 = **原图分辨率 × 乘数**（`1` = 原尺寸，`0.5` = 半尺寸）；pip = 占画布宽/高的比例。**图片按原图分辨率载入，不要写死像素尺寸** |
| `source` | **取材**（image）：`{"x":0,"y":0,"w":1,"h":1}` = 素材归一化子矩形，裁掉不要的部分（只要左半 = `{"x":0,"y":0,"w":0.5,"h":1}`） |
| `fit` | **适配**（image）：`"fit"` 完整放下 / `"fill"` 铺满裁切 / `"stretch"` 拉伸填满（默认 `"fit"`）。离散值，步进取值 |
| `opacity` | 透明度（0~1，**默认 1**）。**淡入淡出 = 关键帧里写 opacity 0→1→0**，代码层不叠加其他淡化 |
| `path` | 图片文件名，支持 **PNG / GIF**（GIF 自动拆帧轮播），放 `<游戏目录>/immersive_cinematics/resource/`，英文命名 |
| `text` | 字幕文本（`\n` 换行）。可以写 `"@lang:<key>"` 引用文本资源：译文放 `resource/lang/<语言>.json`，回退链 = 当前语言 → `en_us` → 原样显示引用串。字幕/描述支持，`name`/`id`/`author` 不翻译 |
| `z_index` | 层级，大者在上（**默认 10**，0.3.6 起；要让字幕/图片压在 fade 之上就写更大的值，如字幕 30） |

> `fade` 是效果层（铺满画布），`x/y/anchor/scale` 对它不生效，只读 `opacity`；`pip` 无纹理，`source`/`fit` 对它不生效。`fade` 的颜色在 clip 级 `color`（如 `"#000000"`）。

**多轨道写法**（图片 + 字幕同时显示，各自一条 OVERLAY 轨道）：

```json
{ "type": "overlay", "id": "overlay_1",
  "clips": [{ "start_time": 0, "duration": 12, "layer_type": "image", "path": "pic.png",
    "keyframes": [
      { "time": 0,  "x": 0.0, "y": 0.0, "scale_x": 0.5, "scale_y": 0.5, "opacity": 0 },
      { "time": 1,  "x": 0.03, "y": 0.03, "scale_x": 0.55, "scale_y": 0.55, "opacity": 1 },
      { "time": 11, "x": 0.4, "y": 0.3, "scale_x": 0.6, "scale_y": 0.6, "opacity": 1 },
      { "time": 12, "x": 0.45, "y": 0.45, "scale_x": 0.5, "scale_y": 0.5, "opacity": 0 } ] }] },
{ "type": "overlay", "id": "overlay_2",
  "clips": [{ "start_time": 0, "duration": 12, "layer_type": "subtitle", "text": "副标题",
    "keyframes": [
      { "time": 0,  "x": 0.02, "y": 0.5, "opacity": 0 },
      { "time": 1,  "x": 0.02, "y": 0.5, "opacity": 1 },
      { "time": 11, "x": 0.4,  "y": 0.6, "opacity": 1 },
      { "time": 12, "x": 0.4,  "y": 0.6, "opacity": 0 } ] }] }
```

**写 OVERLAY 的要点**：
1. **x/y = 0.5 即画布居中**（元素中心锚点）；贴边需按元素尺寸/2 折算（如贴左缘 = 元素半宽），避免元素移出画布
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

组合器（`all_of` / `any`）的 `list` 子条件可以是**轮询类**触发器（`location` / `facing` / `observation` / `structure` / `biome` / `xp` / `inventory` / `gamestage` / `dimension`），也可以是**前置条件**（`{"type":"script_played","conditions":{"script":"脚本 id"}}`，锁存语义：发生过即真）；事件类与嵌套组合器不可用。

通用字段：`repeatable`（可重复触发）、`delay`（延迟秒）、`on_enter`（仅进入时触发，位置类用）。完整 26 种见 `docs/TRIGGER_TYPES.md`。

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
| **注视追踪** | `look_at: "entity"` + `look_at_selector`，位置走关键帧，镜头自动锁定目标（也可 `look_at: "coordinate"` 锁定固定点/结构） |
| **手持感** | `cam_breath_enabled: true` + `cam_breath_intensity: 0.05`，让固定机位"活"起来 |
| **黑场/白场转场** | OVERLAY 轨放两个 `fade` clip（压场 `opacity 0→1`、亮起 `1→0`），黑场 `color: "#000000"`、白场 `"#FFFFFF"`；两段 CAMERA 的硬切藏在黑/白里（见 §4.1） |

**精细化的关键**：
1. 一段 motion 至少 3 个关键帧（起→中→止），中间帧做缓急变化，比两点直线"有呼吸"
2. 段落之间用 `morph` 过渡衔接，避免硬切跳变——**morph 是交叉淡化：过渡时长 t 秒 = 前一段尾部 t/2 与后一段头部 t/2 重叠**。`transition` 写在前一段 clip 上（表示"本 clip 尾部与下一 clip 的过渡"），**后一段的 `start_time` 必须 = 前一段末尾 − t/2**（编辑器会自动对齐；手写脚本要自己算）
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
    { "start_time": 0, "duration": 4, "layer_type": "fade", "color": "#000000", "z_index": 10,
      "keyframes": [
        { "time": 0, "opacity": 0 },
        { "time": 2, "opacity": 1 },
        { "time": 4, "opacity": 1 } ] },
    { "start_time": 4, "duration": 4, "layer_type": "fade", "color": "#000000", "z_index": 10,
      "keyframes": [
        { "time": 0, "opacity": 1 },
        { "time": 2, "opacity": 0 },
        { "time": 4, "opacity": 0 } ] }
  ] }
```

**要点**：
1. **关键帧 `time` 是 clip 内偏移**（每个 clip 都从 0 开始），不是全局时间——第二段 `start_time: 4`，其关键帧仍是 `0 / 2 / 4`。
2. 想"黑场多停一会"：压场 clip 末尾多写一个 `opacity 1` 的关键帧（如上 `2 → 4` 保持全黑），或把 `duration` 拉长。
3. **压不住字幕**：fade 的默认 `z_index` 是 10，比字幕（按 §3.5b 惯例写 30 或更高）低，`z_index` 大者在上 → 黑场期间字幕仍然可见。要让遮罩盖住字幕，得给 fade clip 写更高的 `z_index`（如 200）——转场遮罩是否该压住字幕设计上仍待定。
4. 黑场与白场**没有任何实现差异**，只换 `color`；两个脚本实例见 `cinematics/tests/transition/test_fade_black.json` / `test_fade_white.json`。

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

---

## 7. 交付前校验清单

- [ ] `meta.version = 3`，`id` 只含字母/数字/下划线
- [ ] `total_duration` 等于最后一段 clip 的结束时间（或为负 = 无限）
- [ ] 每个 clip：`duration ≠ 0`，**所有轨道都有 `keyframes`**（letterbox/EVENT/AUDIO/OVERLAY 同 CAMERA，无 clip 级简写）
- [ ] 每个 clip 内：keyframe 的 `time` 严格递增
- [ ] 逐段核对：相机方位 ↔ yaw 是否匹配（§1.4 对照表）
- [ ] 逐段核算：位移距离 ÷ 时长 ∈ 五档合理范围
- [ ] pitch 在 ±90 内；roll 用后归零；fov 30~110；zoom 1~2
- [ ] 特写段落结尾恢复 fov 70 / zoom 1 / roll 0
- [ ] triggers 的 `type` 拼写正确、条件字段齐全（见 TRIGGER_TYPES.md）

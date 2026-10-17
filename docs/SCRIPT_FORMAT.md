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
- 停止同样按脚本定位：`/icinematics stop <脚本>` 只停该脚本（已加载脚本 id，或 `play` 同款“目录:文件名”标识——服务端解析文件取 `meta.id` 后下发）；不写脚本 = 停全部脚本。玩家选择器写在脚本之后；只给选择器（如 `/icinematics stop @a`）= 停全部脚本。
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
| `base_resolution` | object | 否 | `null`（= `1920×1080`） | **编辑基准分辨率** `{"w":2560,"h":1440}`：`w`/`h` 为正整数，不设人为分辨率上限；当前整数存储的表示范围为 `1` ~ `2147483647`。作为分辨率转义系数 `k = min(W播/W基, H播/H基)` 的分母（见 [§9](#9-overlay-轨道覆盖层图片--字幕--淡化--画中画)）。缺省 = `1920×1080`。非对象、缺分量、非正整数或超出存储范围 → 校验器拒绝；解析期告警并回落缺省 |
| `listener` | string | 否 | `"player"` | 音频听者：`"player"`=默认，电影相机下仍以玩家视角听音；`"camera"`=听者切换到镜头位置，环境音/方块音/水声等按相机采样 |

> **0.3.6 起移除的脚本级开关**：`meta.preload` 与 `meta.camera_mob_spawn` / `meta.camera_mob_radius` / `meta.camera_mob_ai` 已删除（解析器只读已声明字段，未知 meta 键被忽略、写了不生效）。区块预加载由全局配置 `preloadEnabled` + 脚本是否存在**非空 CAMERA 轨道**自动决定；相机区域刷怪由相机锚点按原版规则自动处理——均无脚本级开关。


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
| `hide_skip_hud` | boolean/null | `null` | **三态**：仅显式 `true` 隐藏长按跳过提示（右下角图标+进度环）；`null`（缺省）/`false` = **显示（默认开启）**，不回落 `hide_hud` |
| `hud_layers` | object | `{}` | 模组/自定义 HUD 层的显隐覆盖：`{ "modid:layer": true/false }`，`true`=隐藏、`false`=显示 |
| `hard_hide_hud` | boolean/null | `null` | **三态·仅 Forge**：强硬隐藏模式。`true`=除原版 HUD 与注册表 overlay 外，**连"不走 overlay 注册表、直接在 `RenderGuiEvent.Pre` 里自绘"的第三方 HUD 也一起隐藏**（接管整段 HUD 渲染，再按白名单重画）；`null`（缺省）/`false`=normal，行为与不带此字段完全一致。仅当 `hide_hud` 生效时才接管（`hide_hud=false` 时不接管）。**与 `hide_*` 的区别**：null 不回落 `hide_hud`——缺省即 normal。Fabric 无对应语义（字段被解析但不生效） |
| `render_player_model` | boolean | `true` | 是否渲染玩家模型（第三人称时） |
| `pause_when_game_paused` | boolean | `true` | 游戏暂停时是否暂停过场动画 |
| `interruptible` | boolean | `true` | 是否允许被其他脚本打断（**同脚本**再触发的替换语义 + 退出原因门控；跨脚本关系见 `play_mode`） |
| `play_mode` | enum | `parallel` | **跨脚本播放关系**（三态，**在播侧**语义——声明「本脚本在播期间与其它脚本的关系」）：`parallel` = 不阻塞新来者（直接并行加入）、也不被跨脚本打断；`exclusive` = 我播时其它脚本的启动请求一律**排队**，我播完按 `priority` 降序 + FIFO 接播；`interruptible` = 可被新来者**顶掉**（硬切：实例销毁让位）。取值域外 / 缺失 = `parallel`（旧脚本零回归；非法值告警并按 `parallel` 处理）。**与 `interruptible` 分工**：`interruptible`（bool）管**同脚本**再触发的替换语义，`play_mode` 管**跨脚本**关系，两者共存、不合并（同名值 `interruptible` 语义同向） |
| `skippable` | boolean | `true` | 是否允许玩家长按跳过 |
| `hold_at_end` | boolean | `false` | 内容播完后是否停留在最后一帧（驻留点在**内容末端**——各片段展开结束时刻的最大值，不是 `total_duration`） |
| `macro_loop` | boolean | `false` | 宏观循环开关：**从 `a` 到 `b` 重复执行**（循环 = 播放控制层的重复执行，不是时间折叠）。区间 `[a,b]` = `timeline.loop_start` / `loop_end`（缺省 `a=0`、`b=宏观末端`）；宏观末端 = 各片段展开结束时刻的最大值（含片段自身循环展开），**不是** `total_duration`。缺省 `loop_end` 时若存在永不结束片段 → 末端不存在 → 不循环（时间照直走）。圈数见 `macro_loop_count`。与 `hold_at_end` **互斥**（无限圈数下 `hold_at_end` 不会生效；校验器告警）。服务端 EVENT 不参与循环（仅客户端表现层） |
| `macro_loop_count` | int | `-1` | 宏观循环次数：`-1`（缺省）= 无限重复，脚本不再自然结束（退出走跳过/打断路径）；正整数 N = 从 `a` 到 `b` 重复 N 圈后自然结束（总播放时长 = `a + N × (b − a)`）。`0` 非法（运行时按 1 处理） |
| `macro_loop_mode` | enum | `repeat` | 宏观循环模式：`repeat`=折回起点重复 / `pingpong`=往复折返。**第一版只实现 `repeat`**，`pingpong` 按 `repeat` 播放（校验器会告警） |
| `priority` | int | `0` | 播放优先级，数值越大越优先；**仅用于队列内排序**（优先级不能大于打断——不可打断脚本永不被打断，新请求一律排队） |
| `skip_vote_ratio` | int | 无（用全局配置） | **可选**。多人跳过投票所需比例（10~100，百分比），仅当所有看过此脚本的观众投票后才生效。缺省/非法值 → 回落到全局配置 `skipVoteRatio`（默认 100 = 全票）。例：`50` = 半数观众投跳过即强制停止 |
| `trigger_poll_interval` | int | 无（用类型级默认） | **可选**。脚本级检测频率覆盖（tick，`1`~`600`）：该脚本**全部**轮询类触发器的缺省轮询间隔，可被触发器级 `poll_interval` 覆盖。生效链 = 触发器级 ?? 脚本级 ?? 类型级默认（`Config.triggerPollInterval_*`）；仅轮询类生效。缺省/非法值 → 告警 + 回落到类型级默认。例：`10` = 该脚本轮询类触发器默认每 10 tick 检测一次（0.3.6） |

> **`play_mode` 到达组合规则**（新来者 B 到达时按**在播集合 S** 裁决，语义轴 = 在播侧）：S 空 / 仅 `parallel` → B 直接开播（并行加入）；S 含 `exclusive` → B **入队**（不设容量上限；同脚本重复请求替换自己的排队位，不占新格）；S 无 `exclusive` 但含 `interruptible` → 顶掉**全部** `interruptible` 实例后开播（S 里的 `parallel` 实例继续；打断形态 = 硬切）。B 开播后 B 自己的 `play_mode` 对后续到达者生效。**队列出队条件** = 阻塞它的 `exclusive` 实例全部结束（`parallel` / `interruptible` 实例还活着也出队——它们不阻塞队列）。出队按 `priority` 降序 + FIFO **逐项判定**：该脚本已有游戏实例在播（含退出渐出）则保留原位、不挡后面的可播项；无 `exclusive` 时一次排空所有可播项，排空途中启动的新 `exclusive` 会立即阻塞剩余项。预览实例不参与排队与接播。**同脚本再触发优先**于跨脚本裁决：`interruptible`（bool）= `true` → 立即替换该实例 / `false` → 入队。`hold_at_end` 驻留实例算「在播」（参与 `exclusive` 阻塞判定）。预览实例不参与（编辑器预览与游戏实例互不影响）。设计依据：`plans/0.3.6/C-trigger/playback-relations.md` §2/§4。

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
| `poll_interval` | int | 否 | 无（用脚本级 / 类型级） | **检测频率覆盖**（tick，`1`~`600`）：只改本触发器的轮询节奏，**仅轮询类触发器有效**；事件驱动触发器写 = 无效（校验器提示）。非法值（越界 / 非整数）解析期告警 + 按"未写"处理（回落脚本级 `meta.trigger_poll_interval` → 类型级默认）。详见 `TRIGGER_TYPES.md` §「检测频率可配」（0.3.6） |
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
| `total_duration` | float | 是 | **声明时长（秒，正数；不允许 0）**——**统计 / 信息位**（记录、真同步、补帧参考），**不是播放结束判据**：脚本按**实际执行内容**判定播完（实际执行路径上已无剩余可执行内容——无待进入 / 执行中的片段、无待完成循环圈数）即自然结束，比声明值短就早结束、比声明值长（分支延长等）就继续播完。内容比声明值长时限频告警（每 ~10s 一条）提示核对声明值 |
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
| `"overlay"` | 覆盖层（fade 颜色遮罩 / image 图片 / subtitle 字幕），**支持多条同类型轨道同时渲染** |
| `"adjust"` | 画面颜色调整（**整体画面 = master**：作用于合成输出）：曝光 / 对比度 / 高光 / 阴影 / 白 / 黑 / 色相 / 饱和度 / 自然饱和度 / 亮度 / 色温 / 色调 / R/G/B 通道系数 / 灰度 / 反相，全部参数可关键帧；**单条相机轨画面的调色写在 CAMERA 片段上**（见 §4「相机片段调色」） |

---

## 4. Camera 轨道

### Clip 字段

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `start_time` | float | 是 | — | 全局时间线起始点（秒） |
| `duration` | float | 是 | — | 持续时间，正数=定长，负数=无限 |
| `transition` | string | 否 | `"cut"` | `"cut"`=硬切，`"morph"`=过渡（写在**前一段**上，见「过渡」） |
| `transition_duration` | float | 否 | `0.5` | 过渡时长（秒）；分参数未声明通道的缺省时长 |
| `transition_curve` | object | 否 | `null` | 过渡权重曲线（数学模型定义 `{type, …}`，SCALAR 域）；缺省 = 线性斜坡 |
| `transition_params` | object | 否 | `null` | 分参数过渡声明 `{<通道>: {mode?, duration?, curve?}}`；缺省 = 全六通道 blend |
| `transition_interrupt` | string | 否 | `"blend"` | 打断规则：`"blend"`=从当前混合中间态续接；`"snap"`=硬切到新目标 |
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
| `motion_position` | object | 否 | `null` | 位置通道的数学模型定义（`{type, …}`）；缺省 = 不接模型，见「相机参数运动模型」 |
| `motion_yaw` | object | 否 | `null` | yaw 通道的数学模型定义（同上） |
| `motion_pitch` | object | 否 | `null` | pitch 通道的数学模型定义（同上） |
| `motion_roll` | object | 否 | `null` | roll 通道的数学模型定义（同上） |
| `motion_fov` | object | 否 | `null` | fov 通道的数学模型定义（同上） |
| `motion_zoom` | object | 否 | `null` | zoom 通道的数学模型定义（同上） |
| `modifier` | object | 否 | `null` | 片段级静态 Modifier 声明（`{channels, mode?, priority?, <通道值>}`）；缺省 = 本片段不声明，见「覆盖链 Modifier」 |
| `suppress_modifiers` | boolean | 否 | `false` | `true` = 本片段活跃时跳过 Modifier 链（链输出 = 本片段原始六参数），见「覆盖链 Modifier」 |
| `mask_shape` | string | 否 | `"full"` | **CAMERA lane 合成遮罩**：`full` 整幅 / `rect` 矩形 / `circle` 圆形。几何由关键帧 `mask_x` / `mask_y` / `mask_width` / `mask_height` / `mask_radius` 驱动，见下方「lane 合成遮罩」。只作用于该片段产出的 lane 画面，不裁切字幕 / 图片；与 `source` / `dest` 独立。CAMERA 不接受 `gradient` / `gradient_*` / `blend_mode`（`gradient` / `gradient_*` 专属 OVERLAY fade；`blend_mode` 属于 ADJUST 或 fade） |
| `keyframes` | array | 是 | — | 关键帧数组，至少 1 个 |

> **相机片段自带调色**：CAMERA clip 还可写调色字段（clip 级 10 条曲线 + 关键帧级 57 个调色通道），作用于该相机轨产出的 lane——见下方「相机片段调色」。

> **v3 迁移**：`position_mode` 已迁移到**关键帧级**；旧 `cam_tracking_follow*`/`cam_tracking_look_at*` 字段已由关键帧级 `follow`/`look_at` 系列字段取代。clip 级不再支持这些旧字段（保留会被 validate 报废弃提示）。

> **0.3.6 起：插值 = 匀速线性 + 编辑器烘焙缓动**。clip 级 `interpolation` 字段（旧 `"linear"` / `"smooth"`）已**移除**——旧 `"smooth"`（Centripetal Catmull-Rom 样条，会改变运动轨迹）一并退役，不留双轨；保留该字段会被 validate 报出。运行时关键帧之间一律**匀速线性插值**（两点定一段匀速直线），没有隐式数学。想要"缓动 / 速度曲线"：在编辑器里选速度曲线，由**编辑器按曲线采样、把补出的关键帧写进脚本**（脚本里存的是显式关键帧），运行时照常线性求值——运动与脚本内容完全一致。

### 过渡（transition）

**过渡 = 相机从状态 A 到状态 B 的变化过程**：`transition: "morph"` 写在**前一段**（转场的起点侧）上，声明「本段结束进入下一段时做过渡」；`"cut"` = 硬切（瞬时切换，= 时长为 0 的特例）。过渡只发生在两段交界前后，**不改关键帧段内插值**（段内一律匀速线性，缓动由编辑器烘焙成显式关键帧）。

**相机过渡 ≠ 画面转场**：`cut` / `morph` 只改变**相机六参数**（位置 / yaw / pitch / roll / fov / zoom），不会叠化、划像或自动生成第二幅画面。画面转场一律用**显式 clip + 关键帧**表达：

- **叠化**：同一 CAMERA 轨放两条全屏 clip，下层 A 全程 `opacity: 1`（不透明底图），上层 B 在重叠窗口 `opacity: 0 → 1`。普通 source-over 合成下结果 = `B×a + A×(1−a)`，任何时刻都有一幅不透明画面在底；不存在运行时配对 / 归一，A 保持不透明即可。
- **划像 / 局部显露**：上层 B 用 clip 级 `mask_shape`（`rect` / `circle`）+ 关键帧 `mask_*` 几何逐步扩大覆盖（见下方「lane 合成遮罩」）。
- **黑场 / 白场**：OVERLAY 轨的 `fade` clip（见 §9），把相机硬切藏在全遮期间。

不存在 `TRANSITION` 轨道、`transition_in` / `transition_out` 字段或运行时生成器；画面转场就是普通 clip / 关键帧数据。正式示例见 `cinematics/tests/transition/` 下的 `test_dissolve.json`、`test_wipe.json`、`test_fade_black.json`、`test_fade_white.json`、`test_transition_zindex.json`。

**窗口**：以交界时刻（前一段的窗口末端）为中心，宽度 = 该通道的过渡时长（缺省 `transition_duration`，单位秒）；交界前取 A 侧、交界后取 B 侧的实时值。两侧状态各自按自身时间采样（A 侧走完自己的尾部、B 侧真实进入）。

**六参数各自独立**：`position` / `yaw` / `pitch` / `roll` / `fov` / `zoom` 每个通道可各自选择模式、时长、曲线：

| 通道模式（`mode`） | 语义 |
|---|---|
| `"blend"`（缺省） | 按权重在 A / B 之间混合 |
| `"hold"` | 本通道不参与过渡：窗口内保持 A 侧取值，窗口结束随片段切换为 B 侧 |
| `"cut"` | 本通道不做过渡：交界时刻之前取 A 侧、之后取 B 侧 |

**混合口径**（按通道数值域）：`position` 逐轴线性（世界坐标）；`yaw` / `roll` 走**最短路径**（度，差值环绕到 (-180, 180]，多圈不在本层）；`pitch` / `fov` 线性；`zoom` **几何域**（两侧均 > 0 时按对数等比推进，否则回退线性）。

**权重曲线**（`transition_curve` / 逐通道 `curve`）：值 = 数学模型定义（SCALAR 域，类型清单与写法见下方「相机参数运动模型」），输入 = 该通道的归一化进度（钳到 `0 ~ 1`），输出 = 该通道权重；缺省 = 线性（= 现状的线性斜坡）。曲线是**权重的时间映射**，不是关键帧缓动。

**缺省 = 现状**：不写 `transition_curve` / `transition_params` / `transition_interrupt` 时，全六通道同一权重、线性斜坡、时长 = `transition_duration`。

**打断与链式**（`transition_interrupt`，只在打断时生效；未被打断的过渡一律按声明时长混合）：

| 情形 | 规则 | 效果 |
|---|---|---|
| 过渡进行中，新过渡窗口开始（新的一对片段接管） | `"blend"`（缺省） | 以**打断前的混合中间态**为新的 A 侧、时长重新起算——逐帧连续、不跳变 |
| 同上 | `"snap"` | 立即完成当前过渡（= 时长 0 特例）：新过渡窗口内输出恒取新目标 |
| A→B→C 连续过渡（窗口不重叠） | — | 各段独立过渡；B→C 的 A 侧 = B 的实时状态，自然衔接 |
| A→B→C 窗口重叠（链式 + 打断叠加） | 后开始的过渡接管（`blend` / `snap` 同上） | 叠加序 = **后开始的过渡优先**；A 侧 = 打断前最后渲染的混合值 |
| 同一交界内的过渡走完后 | — | 不再重开：该交界窗口内交常规路径（= 进入片段的实时状态） |

**边界**：

- 与 `look_at` / `follow` 目标切换平滑无关（那是选择器锁定层的 `selector_switch_smooth` 机制，始终生效）。
- 编辑器 seek（`setTime`）= 硬定位：过渡作废，从 seek 点重新开始。
- `meta.play_mode`（排队 / 接播）是**播放控制层**的脚本级行为，与本层过渡正交：接播开始时是否做过渡由接播脚本自己的片段声明决定（三态语义与到达组合规则见 §1b `play_mode`）。
- 过渡声明只写在 CAMERA 轨的片段上；写在其它轨道上不生效（validate 拦下）。

**示例**：一段 1 秒过渡里，`roll` 保持 A 侧、`fov` 直接切、其余通道走 smoothstep 曲线、`zoom` 用 2 秒的缓入缓出：

```json
{
  "start_time": 0, "duration": 4,
  "transition": "morph",
  "transition_duration": 1,
  "transition_curve": { "type": "smoothstep" },
  "transition_interrupt": "blend",
  "transition_params": {
    "roll": { "mode": "hold" },
    "fov":  { "mode": "cut" },
    "zoom": { "mode": "blend", "duration": 2, "curve": { "type": "ease_in_out" } }
  },
  "keyframes": [
    { "time": 0, "position": { "dx": 3, "dy": 2, "dz": 0 }, "yaw": 0, "pitch": 0, "roll": 0, "fov": 70, "zoom": 1 },
    { "time": 4, "position": { "dx": 0, "dy": 3, "dz": -3 }, "yaw": 90, "pitch": 5, "roll": 0, "fov": 70, "zoom": 1 }
  ]
}
```

### 循环（loop）语义

`loop` + `loop_count` 决定片段的**生命周期**（活跃窗口），`loop_mode` 决定周期内的**时间映射**：

- **无限循环**（`loop: true` + `loop_count: -1`）：片段一旦开始就永不结束，脚本也随之持续播放——**永不「内容播完」**，退出走 ① 族（跳过 / 打断）或 ③ 族（规则 `exit`）；需要「播 N 秒就收」时用 `until`（`{"until": {"type": "time", "at": N}, "do": [{"type": "exit"}]}`）。适合常驻跟随视角（follow/look_at 固定镜头）：
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
- **片段允许时间重叠**（0.3.6 起）：重叠区按轨道层级 → 轨道内 clip 顺序分层，后面的 clip 覆盖前面 clip 的上层；叠化 = 上一个 clip 末尾帧复制延长（hold）+ 下一个 clip 首帧复制延长，重叠窗口内**下层 A 保持 `opacity: 1`、上层 B 从 `0` 升到 `1`**（不要两边同时淡出，否则两层半透明处会漏出原版基画面）。

### 宏观循环（`meta.macro_loop`）

**循环 = 播放控制层的重复执行，不是时间折叠**：`macro_loop: true` 表示"从 `a` 到 `b` 这一段重复执行 N 圈"。

- **区间 `[a,b]`**：`a` = `timeline.loop_start`（缺省 `0`），`b` = `timeline.loop_end`（缺省 = 宏观末端）。`b` 未声明时，存在永不结束片段（`duration<0` 或 `loop_count=-1`）→ 宏观末端不存在 → **不循环**（时间照直走）；显式声明 `loop_end` 则区间由作者给定，按声明值重复。
- **圈内局部时间**：`t < a` 直通；`t ≥ a` → `a + (t − a) mod (b − a)`。圈边界（局部时间回绕）即折返点，EVENT 轨的玩家行走目标索引按圈复位。
- **圈数 `macro_loop_count`**：`-1`（缺省）= 无限重复（脚本不再自然结束，退出走跳过/打断/规则 `exit`）；正整数 N = 重复 N 圈后自然结束，总播放时长 = `a + N × (b − a)`。
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

### 相机参数运动模型（`motion_position` / `motion_yaw` / `motion_pitch` / `motion_roll` / `motion_fov` / `motion_zoom`）

**本层名 =「迟滞」（相机参数的运动响应）**：模型把该通道**求值出来的值**做一次变换（平滑 / 延迟 / 缓动 / 组合）后再写进相机状态。

**每个相机片段可给六个参数各挂一个数学模型**：六个字段都是 clip 级；缺省 `null` = 该通道不接模型（行为与不写该字段完全一致）——**默认关，由作者显式启用**。

```json
"motion_yaw": { "type": "spring", "stiffness": 8.0, "damping": 0.75 }
```

**六通道默认建议（作者启用时的推荐起点）**：统一 `exponential_smooth`（`tau` 省略 = 0.15 秒）——可预测、无过冲；下表通道差异只是**建议**，不是缺省行为（缺省仍是「不接模型」）。

| 通道 | 建议起点 | 差异化建议（可选） |
|------|---------|-------------------|
| `motion_position` | `{"type": "exponential_smooth"}` | 要「重量感 / 滑行」用 `inertia`；要回弹用 `spring`（`damping` < 1） |
| `motion_yaw` | 同上 | 匀速跟随时不想落后用 `inertia`；要轻微回弹用 `spring` |
| `motion_pitch` | 同上 | 同 `motion_yaw` |
| `motion_roll` | 同上 | `spring`（`damping` 0.3 ~ 0.6）做轻微摆动 |
| `motion_fov` | 同上 | `spring`（`damping` = 1 临界）做稳而快的推拉 |
| `motion_zoom` | 同上 | 同 `motion_fov` |

- 写 `{}`（没有 `type`）**不是**「用缺省模型」：校验期报 `type 缺失`，运行时该通道回落 `linear`（无效果）——启用必须写明 `type`。

- **值域由字段决定**（作者不用写）：`motion_position` = 向量（x/y/z 逐分量独立）；`motion_yaw` / `motion_pitch` / `motion_roll` = 角度（度；差值 / 混合走最短路径，绝对输出不归一）；`motion_fov` / `motion_zoom` = 标量。
- **组合**把子定义内联：`chain` 用 `models` 数组（按序串联，前一个的输出 = 后一个的输入）；`blend` 用 `a` / `b` + `weight`（两个子模型吃同一输入再混合）。
- **上限**：嵌套深度 ≤ 8 层、单个定义 ≤ 32 个节点；超限 validator 报错，运行时整链按「无模型」处理。
- **有状态模型**（`exponential_smooth` / `spring` / `inertia` 及含它们的组合）首个输入即初值（无起始瞬态）；`dt = 0`（暂停帧）状态与输出保持；单步 `dt` 上限 0.25 秒（超出按上限推进，防弹簧过冲）。
- **失败回退**：未注册 / 缺失 `type`、组合域不匹配 → 该处回落 `linear`（恒等 = 无效果）；未知参数忽略；参数越界钳制到下表范围；`NaN` / `Infinity` 回落该参数缺省。以上都只记日志 / 校验提示，不阻断加载。

| `type` | 参数（缺省；范围） | 有状态 | 语义 |
|--------|-------------------|--------|------|
| `linear` | — | 否 | 恒等（缺省类型 = 回退目标，无效果） |
| `smoothstep` | — | 否 | 平滑插值 `3t²−2t³`；输入钳制到 [0,1] |
| `ease_in` | — | 否 | 缓入 `t²`（起步慢、结束快）；输入钳制到 [0,1] |
| `ease_out` | — | 否 | 缓出 `1−(1−t)²`（起步快、结束慢）；输入钳制到 [0,1] |
| `ease_in_out` | — | 否 | 缓入缓出（两段二次，中点速度最高） |
| `cubic_bezier` | `x1` / `y1` / `x2` / `y2`（缺省 `1/3, 1/3, 2/3, 2/3` = 恒等；`x` ∈ [0,1]、`y` ∈ [-4,4]） | 否 | CSS `cubic-bezier`：控制点 P0=(0,0)、P1=(x1,y1)、P2=(x2,y2)、P3=(1,1)；输入 x = 进度 [0,1]、输出 y（可过冲） |
| `exponential_smooth` | `tau`（缺省 `0.15` 秒；范围 0.001 ~ 100） | 是 | 一阶指数趋近：每步按 `1 − e^(−dt/τ)` 追向输入（τ 越小越快） |
| `spring` | `stiffness`（缺省 `8.0`；范围 0.001 ~ 1000）、`damping`（阻尼比，缺省 `0.75`；范围 0 ~ 10）、`initial_velocity`（缺省 `0`；范围 ±10000） | 是 | 二阶阻尼弹簧：`damping` < 1 欠阻尼（回弹）/ = 1 临界阻尼 / > 1 过阻尼；跟随移动目标有稳态滞后 |
| `inertia` | 同 `spring`（`stiffness` / `damping` / `initial_velocity`，缺省与范围一致） | 是 | 二阶「惯性」：阻尼基准随输入运动 → 跟随移动目标稳态零滞后；起步慢、停止后过冲再回稳；输入静止时 = 同参数 `spring` |
| `chain` | `models`（子定义数组） | 任一子有状态 | 串联：按序逐个施加 |
| `blend` | `a` / `b`（子定义；缺省 = 恒等）、`weight`（缺省 `0.5`；范围 0 ~ 1，0 = 全 `a`、1 = 全 `b`） | 任一子有状态 | 并联混合（角度域按最短路径混合） |

```json
"motion_position": {
  "type": "chain",
  "models": [
    { "type": "exponential_smooth", "tau": 0.2 },
    { "type": "spring", "stiffness": 8.0, "damping": 0.75 }
  ]
},
"motion_yaw": {
  "type": "blend",
  "a": { "type": "spring", "stiffness": 8.0, "damping": 0.75 },
  "b": { "type": "exponential_smooth", "tau": 0.15 },
  "weight": 0.5
}
```

> **曲线族的适用面**：`smoothstep` / `ease_in` / `ease_out` / `ease_in_out` / `cubic_bezier` 按 **[0,1] 归一化量**定义（输入先钳到 [0,1]），服务**运行时**的归一化量求值（过渡权重、组合权重）——直接挂在位置 / 角度 / fov 这类原始量上通常不是想要的（原始量通道用 `spring` / `exponential_smooth` 或恒等 `linear`）。关键帧之间的缓动同样不用它们：由编辑器烘焙成显式关键帧（见上方「0.3.6 起：插值 = 匀速线性 + 编辑器烘焙缓动」）。

> **`domain` 结构键**（可选，一般不用写）：显式声明值域 `scalar` / `angle` / `vector` / `color`，缺省继承所在通道（组合内继承父定义）；组合内与父定义不一致 = 校验期报「组合域不匹配」。

### 覆盖链 Modifier（`modifier` / `suppress_modifiers`）

相机状态每帧分两步产出：**Base 链**决定这一帧的基础六参数（脚本播放 = 脚本导演层），**Modifier 链**在它之上按通道掩码做部分修改，输出即最终状态（= 统一相机快照）。Modifier **只作用主相机那份**（最上层 lane 的相机 = 玩家看到的主画面）；次级 lane（分屏 / 画中画里下面的几格）保持各自片段的原始相机，不叠加 Modifier。

**声明挂在片段上（clip 级，一个片段至多一条）**：要多个效果就写在多个片段上——链按 `priority` 升序逐条合成，多条自然叠加（合成模式 / 掩码 / 应用序都是逐条独立声明的）；每条声明只在**它所在片段的活跃窗内**生效（见下方「生效窗」）。

```json
"modifier": {
  "channels": ["yaw", "pitch"],   // 生效通道（必填）：position / yaw / pitch / roll / fov / zoom 的子集
  "mode": "add",                  // 合成模式（可选）：add / multiply / replace；缺省 = 逐通道缺省
  "priority": 100,                // 应用序（可选）：升序，小者先应用；缺省 0
  "yaw": 30,                      // 值键 = 通道名：position 写 {"x": …, "y": …, "z": …}，其余五个写数字
  "pitch": -15
}
```

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `channels` | array | 是 | — | 生效通道名数组：`position` / `yaw` / `pitch` / `roll` / `fov` / `zoom` 的子集，非空、不重复。**掩码 = 这份名单**：没列出的通道逐位不受影响 |
| `mode` | string | 否 | 逐通道缺省 | 合成模式：`add`（相加）/ `multiply`（相乘）/ `replace`（直写绝对值）。缺省 = 偏移类 `position` / `yaw` / `pitch` / `roll` 用 `add`、缩放类 `fov` / `zoom` 用 `multiply`；`replace` 必须显式写。一条声明只有一个模式，需要不同模式就拆成两条声明 |
| `priority` | int | 否 | `0` | 应用序：升序应用（小者先、大者后——后应用者的结果落在最终值上）；同值 = 先注册者先应用（同一脚本内 = 靠前的片段先）。与 Base 层优先级是两套独立数值，互不比较 |
| `position` | object | 条件 | — | `channels` 含 `position` 时必填：`{"x": …, "y": …, "z": …}`（世界坐标，方块；三轴都要写——`position` 是整通道一位，不分轴） |
| `yaw` / `pitch` / `roll` | float | 条件 | — | `channels` 含该通道时必填（度）：`add` = 角度增量、`multiply` = 倍率、`replace` = 世界角绝对值 |
| `fov` / `zoom` | float | 条件 | — | `channels` 含该通道时必填：`fov` 单位度、`zoom` 单位倍率；`add` = 增量、`multiply` = 倍率、`replace` = 绝对值 |
| `suppress_modifiers` | boolean | 否 | `false` | 与 `modifier` 同级（同样写在片段上）：`true` = 本片段活跃时**整条跳过 Modifier 链**，链输出 = 本片段原始六参数 |

**合成结果**（Base = 关键帧求值出来的那份六参数）：

| 声明 | Base | 结果 |
|---|---|---|
| `{"channels": ["yaw"], "mode": "add", "yaw": 30}` | yaw 0 | yaw 30 |
| `{"channels": ["fov"], "mode": "multiply", "fov": 1.5}` | fov 70 | fov 105 |
| `{"channels": ["yaw", "fov"], "mode": "replace", "yaw": 180, "fov": 30}` | yaw 90 / fov 40 | yaw 180 / fov 30 |
| `{"channels": ["position"], "mode": "replace", "position": {"x": 100, "y": 70, "z": 200}}` | 任意 | 机位恒在世界坐标 (100, 70, 200) |
| `{"channels": ["yaw"], "yaw": 45}`（缺省模式 = `add`） | yaw 90 | yaw 135 |

- **写法约束**（`ScriptValidator` 与 `ScriptParser` 同一口径）：`channels` 列出的通道必须给值、给了值的通道必须写进 `channels`；键名只认 `channels` / `mode` / `priority` 与六个通道名；数值必须是有限数字（`NaN` / `Infinity` 拒绝）；`channels` 为空 / 未知通道名 / 重复通道名都报错。两个字段只写在 CAMERA 片段上（写在别的轨道上会被 validate 报出——不生效）。
- **生效窗 = 声明所在片段的活跃窗**：`[start_time, 窗口末端)`——窗口末端 = `start_time + duration`；有限循环 = `start_time + 周期 × loop_count`；永不结束的片段（`duration < 0` 或 `loop_count: -1`）自 `start_time` 起永续。片段播完声明随之失效：要「这一段抖、那一段不抖」，各自写在各自片段上即可（时间取所属播放实例的时间轴读数，与轨道分发同一口径）。
- **morph 重叠窗内前后两侧并存**：前一段声明（`transition: "morph"` + `transition_duration`）的生效窗末端延长半个过渡时长，后一段从自己的 `start_time` 起生效——重叠窗内两条声明同时参与合成，叠加序仍是优先级升序 → 注册序（同脚本内 = 靠前的片段先）。
- **跨实例**：Modifier 按播放实例注册进链（开播注册、停播注销），并行脚本各自活跃片段的声明都作用于主相机；同一时刻的多条链级声明按上述规则叠加。
- **`suppress_modifiers` 是整链逃生门**：本片段活跃时跳过**全部** Modifier（含其他片段 / 其他实例的声明），链输出 = 本片段原始六参数。
- **链上不做 clamp / 归一化**：合成结果原样交给渲染侧（`fov` 超出可视角、`zoom` 取负等都按数值直通）。
- **无字段 = 零改动**：不写这两个字段的片段，链输出与启用覆盖链之前逐位一致（无声明 = Modifier 链为空）。

> 示例脚本（`cinematics/tests/camera/`）：`test_camera_modifier_baseline`（常规相机脚本 = 基线）、`test_camera_modifier_mask`（掩码关断：只声明 `zoom`，其余通道与基线逐帧一致）、`test_camera_modifier_add` / `_multiply` / `_replace`（三种合成模式）、`test_camera_modifier_nosuppress` / `_suppress`（suppress 开 / 关对照）、`test_camera_modifier_window`（两段跨窗：第一段的声明在第二段失效）。

### Keyframe 字段

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `time` | float | 是 | — | 在 clip 内的时间偏移（秒），从 0 开始 |
| `position` | object | 是 | — | 位置，格式见下方。**follow=entity 时表示相对实体脚底的偏移** |
| `position_mode` | string | 否 | `"relative"` | 该关键帧的坐标模式：`"relative"`=相对触发点（position 用 dx/dy/dz），`"absolute"`=世界坐标（position 用 x/y/z）。可与前后关键帧不同，两端世界坐标平滑插值 |
| `follow` | string | 否 | `"none"` | `"none"`=不跟随；`"entity"`=位置跟随目标实体（动态，每帧取实体插值位置 + position 偏移）。follow↔普通关键帧之间两端世界坐标插值 → 平滑过渡 |
| `follow_selector` | string | 否 | `"@p"` | 跟随目标选择器（见下方"目标选择器"） |
| `facing_origin` | string | 否 | `""` | **位置点源**（一个点源，写法见下方"点源"）：世界轴偏移（`dx`/`dy`/`dz`）与基准坐标系偏移（`fwd`/`up`/`right`）**共用同一位置点源**，同时是基准坐标系的原点。不写 = 世界轴偏移用玩家激活位置、基准坐标系偏移用玩家实体（`@p`，实时跟随） |
| `facing_origin_x/y/z` | float | 否 | `0` | `facing_origin` 取 `"coordinate"` 形态时的世界坐标（三个都要写） |
| `facing_origin_time` | string | 否 | `"live"` | 位置点源时态：`"live"`=逐帧重取 / `"snapshot"`=片段内首次解析到的点冻结（见下方"点源时态"）。只对实体选择器形态生效 |
| `facing_target` | string | 否 | `""` | 基准坐标系的**前轴目标**，一个点源（写法见下方"点源"）：前轴 = 位置点源 → 该目标的连线方向（实体取包围盒中心）。不写 = 位置点源自身朝向（实体 = 身体朝向 + 视线；玩家 = 实时视线）。不可解析 = 该端无目标，片段按空处理 |
| `facing_target_x/y/z` | float | 否 | `0` | `facing_target` 取 `"coordinate"` 形态时的世界坐标（三个都要写） |
| `facing_target_offset` | object | 否 | `null` | `facing_target` 的偏移（点源通用偏移，见下方"偏移"） |
| `facing_target_time` | string | 否 | `"live"` | `facing_target` 的时态（取值同上；`"snapshot"` = 首次解析到的前轴目标点冻结） |
| `look_at` | string | 否 | `"none"` | `"none"`=用 yaw/pitch；`"player"`=注视玩家激活位置；`"coordinate"`=注视固定点（xyz 或结构中心）；`"entity"`=注视实体（包围盒内取点，部位由 `look_at_part` 定，缺省中心）；`"block"`=注视就近搜索到的方块中心（用 `look_at_target_block`）。look_at 关键帧的目标点之间插值 → 切换/开关平滑过渡 |
| `look_at_selector` | string | 否 | `"@p"` | 注视目标选择器（`entity` 模式） |
| `look_at_part` | object | 否 | `null` | `look_at=entity` 的**部位百分比**：`{x,y,z}` 每轴 `0`~`100`（`0`=该轴最小侧、`50`=包围盒中心、`100`=最大侧），缺省分量 = `50`（= 不写该字段的现行为）。取点 = 实体包围盒（宽 `getBbWidth`、高 `getBbHeight`，世界轴对齐）内按百分比定位，**越界值钳制在 `0`~`100`**（取点始终在包围盒内，不伸到体外）。只对 `look_at=entity` 有效：坐标 / 结构 / 方块来源没有部位，要微调直接改坐标——写在其它 `look_at` 模式下会被 validate 报错 |
| `look_at_target_x/y/z` | float | 否 | `0/64/0` | 注视固定坐标（`coordinate` 模式）。**与 `look_at_target_structure` 互斥**（编辑器：填结构后坐标输入隐藏） |
| `look_at_target_structure` | string | 否 | `""` | 注视结构中心（`coordinate` 模式）：填结构 id（如 `minecraft:village`）。播放时服务端自动定位**结构 bounding box 中心**（就近搜索，原版 /locate 同范围）并替换为坐标后推送；编辑器里为注册表下拉补全；多人服务器播放同样生效。**与 `look_at_target_x/y/z` 互斥**：指定结构后定位失败也不回退坐标，该端无注视目标（回退角度插值） |
| `look_at_target_block` | string | 否 | `""` | 注视方块中心（`block` 模式）：**一个点源的方块形态**（写法与 `facing_origin` / `yaw_base_from` 同一套）`"block:<方块 id>[:<半径>]"`，取就近搜索到的**方块中心**（整数坐标 + 0.5，半径缺省 16 格）。不可解析（附近没有该方块 / 多人服务器无服务端世界）= 该端无注视目标（回退角度插值） |
| `look_at_offset` | object | 否 | `null` | 注视点偏移（点源通用偏移，见下方"偏移"）：叠加在来源取点之后（`look_at=entity` 时 = 部位百分比取点之后再加偏移） |
| `look_at_time` | string | 否 | `"live"` | 注视点时态（取值同 `facing_origin_time`）：只对 `look_at=entity` 生效，其余形态恒定 |
| `selector_refresh` | float | 否 | `1.0` | 目标存活时的重新扫描间隔（秒）。**目标丢失**（死亡/移除/未加载）后不受此值限制：锁进入搜索态并保持最后画面，按固定节奏（0.2 秒）持续重找，找到即恢复。作用于本关键帧所有选择器调用点（缺省回落；调用点专属字段见下方"调用点级策略覆盖"） |
| `selector_switch_while_alive` | bool | 否 | `true` | `true`=目标存活时也按 `selector_refresh` 扫描并切到新的最近目标；`false`=当前目标活着就不换。**目标丢失后不受此项限制**：进入搜索态持续重找，解析到任意符合规则的目标就立即恢复 |
| `selector_switch_interval` | float | 否 | = `selector_refresh` | 两次真实切换之间的最小间隔（秒），与扫描频率无关：扫描到新目标但距上次切换不足 N 秒 → 继续用旧目标。`0`=不限制。缺省 = `selector_refresh` |
| `selector_switch_smooth` | float | 否 | `0.0` | 目标真的切换时（含丢失后恢复到新目标），注视点/跟随位置在 N 秒内以 smoothstep 过渡；`0`=硬切。作用于本关键帧所有目标点（缺省回落；调用点专属字段见下方"调用点级策略覆盖"） |
| `selector_anchor` | string | 否 | `"camera"` | 选择器锚点：`sort=nearest` 的**就近参考点**（本地就近排序与服务端解析请求共用）。`"camera"`=相机当前位置（缺省，旧行为）；`"player"`=玩家脚底；`"target"`=该调用点当前锁定目标的脚底（尚未解析到目标时回落相机）；`"origin"`=`position` 的位置点源坐标（形态见上方 §「点源」；`position` 缺失或点源解析失败时回落相机）。作用于本关键帧所有选择器调用点（缺省回落；调用点专属字段见下方"调用点级策略覆盖"） |
| `selector_pick` | string | 否 | `"first"` | 候选择一策略：选择器匹配到多个候选时取定哪一个。`"first"`=**缺省 = 0.3.6 之前的择一行为**（本地 `@e` 系列取距锚点最近的活实体，服务端选择器按服务端回传顺序取首个可用）；`"nearest"`=在客户端可用候选内取距锚点最近者（服务端选择器由此与本地同口径）；`"alive"`=按候选返回顺序取首个存活者、不比距离（本地 = 世界迭代序，服务端 = 回传序）。**可用** = 客户端已加载且存活；**距离** = 世界坐标平方距离比较。作用于本关键帧所有选择器调用点（缺省回落；调用点专属字段见下方"调用点级策略覆盖"） |
| `yaw_base` | string | 否 | `"world"` | `yaw` 的基准方向：`"world"`=0 世界角（`yaw` 即世界朝向）；`"entity"`=实体**身体朝向**水平角（取身体 yaw，用 `yaw_base_selector`）；`"line"`=从 `yaw_base_from` 到 `yaw_base_to` 的连线水平角。此时 `yaw` 为相对基准的偏移 |
| `pitch_base` | string | 否 | `"world"` | `pitch` 的基准俯仰：同上，`entity` 取实体**视线**俯仰、`line` 取连线垂直角 |
| `yaw_base_selector` | string | 否 | `"@p"` | `yaw_base/pitch_base=entity` 时的实体选择器（`yaw` 取该实体身体朝向水平角、`pitch` 取该实体视线俯仰） |
| `yaw_base_from` / `yaw_base_to` | string | 否 | `""` | `yaw_base/pitch_base=line` 时的两个端点，**各是一个点源**（写法见下方"点源"；实体选择器是其中一种形态）。连线退化（两端水平位置重合 = 纯垂直线，或两点重合 = 零长度线）时水平角未定义 → 该朝向输入被拒（回退 `"world"` 基准，运行时限频告警）；纯水平连线（只有水平分量）合法，基准俯仰 = 0。任一端无点源或不可解析 = 该端无目标，片段按空处理 |
| `yaw_base_from_x/y/z` / `yaw_base_to_x/y/z` | float | 否 | `0` | 端点取 `"coordinate"` 形态时的世界坐标（三个都要写；缺省 `0` 会把点源钉在世界原点，校验器直接拦下） |
| `yaw_base_from_offset` / `yaw_base_to_offset` | object | 否 | `null` | 连线端点的偏移（点源通用偏移，见下方"偏移"） |
| `yaw_base_from_time` / `yaw_base_to_time` | string | 否 | `"live"` | 连线端点的时态（取值同 `facing_origin_time`；`"snapshot"` = 首次解析到的端点冻结） |
| `yaw` | float | 是 | — | 偏航角（度）。0=南，90=西，±180=北。`look_at != none` 时被覆盖；使用 `yaw_base` 时表示相对基准的偏移，写了 `yaw_lock` 时表示相对锁值的偏移 |
| `pitch` | float | 是 | — | 俯仰角（度）。正=向下看。`look_at != none` 时被覆盖；写了 `pitch_lock` 时表示相对锁值的偏移 |
| `yaw_lock` | float | 否 | `null` | 偏航轴锁值（度，世界角绝对值）：写了 = 该轴基准固定为该值（不再查 `yaw_base` 的方向源），关键帧 `yaw` 偏移仍叠加；不写 = 跟 `yaw_base` 的来源。`look_at != none` 时静态侧整体不参与（锁不生效）。两轴独立：可一轴锁、另一轴跟来源 |
| `pitch_lock` | float | 否 | `null` | 俯仰轴锁值（度，世界角绝对值）：同上，锁 `pitch_base` 的基准。典型第三人称 = `"pitch_lock": 15` + `"yaw_base": "entity"`（pitch 锁死、yaw 跟身体朝向） |
| `roll` | float | 是 | — | 翻滚角（度）。正=屏幕顺时针（画面向右倒），任何朝向一致 |
| `fov` | float | 是 | — | 视场角（度），标准 70 |
| `zoom` | float | 否 | `1.0` | 缩放倍率，`>1`=放大 |
| `opacity` | float | 否 | `1.0` | 合成不透明度（`0`~`1`）。叠化 = **下层 clip 保持 `1`、上层 clip 在重叠窗口 `0 → 1`**（普通 source-over 线性交叉；不要写成两边同时淡出） |
| `dest` | object | 否 | 全屏 `{x:0,y:0,w:1,h:1}` | 目标区域：本 clip 画面铺到屏幕的归一化矩形，格式见下方"合成参数" |
| `source` | object | 否 | 全幅 `{x:0,y:0,w:1,h:1}` | 取材区域：从本 clip 画面内取哪一块的归一化矩形，格式见下方"合成参数" |
| `mask_x` / `mask_y` | float | 否 | `0.5` | **CAMERA lane 遮罩几何**（`rect` / `circle` 消费）：覆盖区域中心，窗口比例（`0.5` = 窗口中心；有限值，可越界）。`full` 不读取 |
| `mask_width` / `mask_height` | float | 否 | `1` | **CAMERA lane 遮罩几何**（`rect` 消费）：宽 / 高占窗口比例，有限且非负、不设人为上限；任一为 `0` 时该 lane 不绘制（显露下层）。`circle` / `full` 不读取 |
| `mask_radius` | float | 否 | `0.5` | **CAMERA lane 遮罩几何**（`circle` 消费）：半径 / 窗口**较短边**比例，有限且非负、不设人为上限；`0` 时该 lane 不绘制。非方形窗口在物理像素里仍是真圆 |

**调用点级策略覆盖**（0.3.6）：上面 4 个 `selector_*` 策略字段、`selector_anchor` 与 `selector_pick` 都是**通用回落**——默认作用于本关键帧全部选择器调用点。想给某个调用点单独配置，写 `<字段>_<调用点>`，它优先于通用字段；不写 = 回落通用字段（旧脚本行为逐点不变）。

| 调用点 | 服务的属性（对应字段） | 专属策略字段（以 `selector_refresh` 为例） |
|--------|------------------------|--------------------------------------------|
| `follow` | 位置基准（`follow_selector`） | `selector_refresh_follow` |
| `look_at` | 注视目标（`look_at_selector`） | `selector_refresh_look_at` |
| `yaw_base` | 朝向基准 `entity`（`yaw_base_selector`） | `selector_refresh_yaw_base` |
| `yaw_base_from` | 连线基准 A 点（`yaw_base_from`） | `selector_refresh_yaw_base_from` |
| `yaw_base_to` | 连线基准 B 点（`yaw_base_to`） | `selector_refresh_yaw_base_to` |
| `facing_origin` | 位置点源（`facing_origin`） | `selector_refresh_facing_origin` |
| `facing_target` | 基准坐标系前轴目标（`facing_target`） | `selector_refresh_facing_target` |

4 个策略字段都有调用点变体：`selector_refresh_*` / `selector_switch_while_alive_*` / `selector_switch_interval_*` / `selector_switch_smooth_*`，类型与范围与通用字段一致。示例：`"selector_refresh_look_at": 0.2` + `"selector_switch_smooth_look_at": 0.3` = 注视目标 0.2 秒扫一次、切换用 0.3 秒过渡，而 follow 仍用通用值。

锚点字段同样有调用点变体 `selector_anchor_<调用点>`（取值与缺省规则同 `selector_anchor`）：不写 = 回落通用 `selector_anchor`（再缺省 `camera`）。示例：`"selector_anchor_follow": "target"` = follow 的就近参考点用跟随目标自身（`@e[type=…]` 这类就近 selector 因而稳定锁在同一目标上），其余调用点仍用相机位置。锚点取值（`camera` / `player` / `target` / `origin`）参与目标锁与解析缓存的键：同一 selector 在不同锚点下各持一份解析结果，互不覆盖。

择一字段同样有调用点变体 `selector_pick_<调用点>`（取值与缺省规则同 `selector_pick`）：不写 = 回落通用 `selector_pick`（再缺省 `first`）。示例：`"selector_pick_look_at": "nearest"` = 注视目标在多个候选里就近取，其余调用点仍用通用值。择一在「从候选取定目标」处生效，与切换平滑策略（`selector_switch_*`）相互独立：择一决定「这次扫到谁」，切换策略决定「换不换、怎么过渡」。

**目标选择器**（`follow_selector` / `look_at_selector`）：

| 写法 | 行为 |
|------|------|
| `@p` / `@s` | 玩家（本地快路径，直接取本地玩家） |
| `@p[...]` | 玩家 + 原版选项（交服务端用原版解析，语义同原版） |
| `@a` / `@a[...]` | 全体玩家（可带原版选项，如 `[team=…]`）——交服务端用原版解析，语义同原版 |
| `@r` / `@r[...]` | 随机一名玩家（可带原版选项）——交服务端用原版解析，语义同原版 |
| `@e` | 离锚点最近的活实体（锚点缺省 = 相机位置，见 `selector_anchor`） |
| `@e[type=minecraft:iron_golem]` | 类型过滤后就近（模组 boss 用其注册 id） |
| `@e[type=#your_mod:units]` | 实体类型 tag 过滤（推荐：只选单位、排除投掷物）。tag 由数据包/模组提供 |
| `@e[type=!#minecraft:impact_projectiles]` | 反向实体类型 tag：排除投掷物等 |
| `@e[name=自定义名]` | 命名牌名字过滤后就近 |
| `@e[...]` 其它原版选项（`nbt=` / `tag=` / `distance=` / `sort=` / `limit=` / `team=` …） | 交服务端用原版解析，语义同原版 |
| `uuid:xxxxxxxx-…` | UUID 直绑（唯一确定，不排序） |

> 上表「就近」描述的是缺省 `selector_pick: "first"` 在**本地路径**下的行为。匹配到多个候选时的取定规则由 `selector_pick` 决定：`first` = 本地就近 / 服务端按回传序取首个可用（0.3.6 之前的择一行为）；`nearest` = 客户端可用候选内就近；`alive` = 按返回序取首个存活。

> **解析路径**：无选项 `@p` / `@s` 与 `@e` / `@e[type=…,name=…]` 走**本地快路径**（同步求值）；其余形态——`@a` / `@r` 的裸形态与全部带选项形态（`@p[...]` / `@a[...]` / `@r[...]`）、`@e` 的扩展选项——交**服务端**用原版 `EntitySelectorParser` 解析，再按回传 UUID 映射到客户端实体，**语义与原版一致**（含 `[team=…]` 等原版选项）。
>
> `type=` 可以是 `#tag` / `!#tag`，也可以在一条 selector 里组合多个 `type=!xxx`。
> 含 `nbt=` / `type=#tag` / `type=!` / 多个 `type=` 的 selector 会交给服务端用原版
> `EntitySelectorParser` 解析（`nbt=` 只做 NBT 子集匹配，不区分生物/投掷物；要区分种类请用 `type=`）。
>
> **未知形式**：原版 1.20.1 没有 `@n` 选择器 → 按未知形式处理（warn 日志 + 不解析 = 无匹配）；畸形括号（如缺 `]`）等同。都不崩溃。
>
> 无匹配时：follow 停在上一帧位置、look_at 回退 yaw/pitch。

### 点源（Point Source）

能产出一个**世界坐标**的来源 = **来源 + 偏移 + 时态**（时态见下方 §「点源时态」）。五个形态通用：
`facing_origin` / `facing_target` / `yaw_base_from` / `yaw_base_to` 四个关键帧字段与 `look_at` 的
坐标 / 结构 / 实体 / 方块来源都用这套写法。

| 写法 | 含义 | 取点 |
|------|------|------|
| `"player"` | 玩家激活位置（脚本激活时玩家所在位置，**整场不变 = 等价快照语义**；要实时跟随玩家用选择器 `@p`） | 玩家脚底 |
| `"coordinate"` | 固定坐标：配 `<字段>_x` / `<字段>_y` / `<字段>_z`（三个都要写） | 该坐标 |
| `"block:<方块 id>[:<半径>]"` | 玩家附近搜索最近匹配方块（半径缺省 16 格） | 方块中心（整数坐标 + 0.5） |
| `"@…"` / `"uuid:…"` | 实体选择器（写法与 `follow_selector` 同） | `facing_origin` / 连线端点 / `look_at=entity` 部位（`look_at_part`）= 包围盒内取点；`facing_target` = 包围盒中心 |
| 其余非空字符串 | 结构 id（如 `minecraft:village`） | 结构 bounding box 中心（就近搜索） |
| 空串 / 不写 | 无点源 | 该端无目标 |

- 不可解析（实体未找到 / 结构未定位 / 附近没有该方块）= 该端无目标：片段按空处理（不写相机 → 玩家视角），不引入替代值。
- 结构 / 方块定位需要服务端世界：单人 / 集成服务器直接定位；多人服务器上 `look_at_target_structure` 与
  `facing_origin` 由服务端推送前替换为坐标，**其余字段的结构 / 方块形态在多人服不可解析**（该端无目标）。
- 选择器写法先于结构 id 判定：以 `@` 开头或 `uuid:` 开头的字符串一律按实体选择器解析，不会被当作结构 id。
- `look_at=block` 的注视目标 `look_at_target_block` 用同一套**方块形态**（`block:<方块 id>[:<半径>]`），取点 = 方块中心。
- 字段落点：点源字段（`facing_origin` / `facing_target` / `yaw_base_from` / `yaw_base_to`）与坐标形态的配套坐标
  （`<字段>_x/_y/_z`）都写在**关键帧**上；`position` 对象只承载**位置通道的偏移**（世界轴 `dx/dy/dz`、绝对
  `x/y/z`、基准坐标系 `fwd`/`up`/`right`）。

### 点源时态（`<字段>_time`）

点源 = 来源 + 偏移 + **时态**：`"live"`（缺省）= 每帧重新取点（现状）；`"snapshot"` = 首次解析到的点冻结，
此后每帧读同一份。字段名 = 点源字段名 + `_time`，共 5 个：`facing_origin_time` / `facing_target_time` /
`yaw_base_from_time` / `yaw_base_to_time` / `look_at_time`。

| 来源形态 | `"live"`（缺省） | `"snapshot"` |
|---|---|---|
| 坐标 / 方块 / 结构 | 天生恒定，写不写无差 | 同左（无效果，校验器提示） |
| `"player"` 玩家激活位置 | 玩家激活位置，整场不变（等价快照语义） | 同左（无效果，校验器提示） |
| `"@…"` / `"uuid:…"` 实体选择器 | 跟着实体走（= 跟随语义） | 冻在首次解析到的位置 |

- **实时跟随玩家**用选择器 `@p`：`"player"` 是脚本激活时的玩家位置，不跟人走。
- 时态只对**实体选择器**形态生效；静态来源写了 = 无效果（校验器提示，不拦下）。
- 冻结的是**展开偏移后的最终世界坐标**（偏移一起冻结）。
- 未知取值回落 `live`（校验器报错，脚本仍可播）。
- 快照按**片段 + 字段**分桶：同一片段内该字段的所有关键帧共用同一份快照（片段内取一次）。

| 边界 | 规则 |
|---|---|
| 取快照时刻 | 片段内**首次解析成功**那帧取一次；目标还没找到（解析 `null`）期间不钉死「无点源」，解析成功才捕获 |
| seek（编辑器拖播放头） | **重取**（seek = 硬定位、状态归零） |
| loop 折返 | **不重取**（状态延续，与运动模型 macro loop 同口径） |
| morph 过渡 | 两侧**各用各的快照**（两个片段各持状态，互不覆盖） |

```json
{
  "look_at": "entity", "look_at_selector": "@e[type=minecraft:armor_stand]",
  "look_at_time": "snapshot"
}
```

上例：注视点 = 本片段首次解析到的盔甲架位置，此后盔甲架移动画面不动（要实时跟随请删掉 `look_at_time`）。

### 偏移（点源通用属性）

**点源 = 来源 + 偏移**：任一来源都能叠一层偏移（`<字段>_offset` 对象）；位置通道的偏移写在 `position` 对象里
（`dx/dy/dz` 或 `fwd/up/right`，见下方 §Position）。

| 表达空间 | 分量 | 参照 |
|---|---|---|
| 世界轴（缺省） | `dx` / `dy` / `dz` | 世界 X / Y / Z 轴 |
| 基准坐标系 | `fwd` / `up` / `right` | 本帧基准坐标系的 前 / 上 / 右 轴 |

- 两类分量**不能混写**（校验器拦下）；缺省分量 `0`；单位 = 方块（与坐标同单位）。
- **叠加顺序**：先取点后加偏移——实体来源 = 部位百分比（`look_at_part`）取点后再加偏移。
- **基准坐标系**：原点 = `facing_origin` 点源（缺省玩家激活位置），前轴 = `facing_target` 点源 → 位置点源的
  连线方向（缺省 = 位置点源实体自身朝向 / follow 实体 / 玩家），垂直面固定为世界竖直（上轴 = 世界竖直，
  前轴 / 右轴取水平面内分量，前轴俯仰不参与）；由位置通道建立。**本关键帧没有基准坐标系**（未写
  `facing_origin` / `facing_target`，位置也不用 `fwd`/`up`/`right`）= `base` 表达空间的偏移不生效（校验器提示）。

```json
{
  "facing_origin": "@e[type=minecraft:iron_golem]",
  "facing_target": "coordinate", "facing_target_x": 0, "facing_target_y": 64, "facing_target_z": 10,
  "look_at": "entity", "look_at_selector": "@e[type=minecraft:iron_golem]",
  "look_at_part": { "y": 100 },
  "look_at_offset": { "fwd": 1, "up": 0.2, "right": 0 }
}
```

上例：位置点源 = 铁傀儡脚底（基准坐标系原点），前轴 = 该点 → `(0,64,10)`；注视点 = 铁傀儡头顶（部位百分比
`y=100`）再沿前轴前移 1 格、上移 0.2 格。

### 通道相对化（位置 / yaw / pitch / 注视点各自独立）

| 通道 | 绝对 | 相对 |
|---|---|---|
| 位置 | `position: {x,y,z}`（世界坐标） | `position: {dx,dy,dz}`（位置点源 + 世界轴偏移）/ `{fwd,up,right}`（基准坐标系偏移） |
| yaw | `yaw_base: "world"`（`yaw` = 世界角） | `yaw_base: "entity"` / `"line"`（基准方向 + `yaw` 偏移） |
| pitch | `pitch_base: "world"`（`pitch` = 世界俯仰） | `pitch_base: "entity"` / `"line"`（基准俯仰 + `pitch` 偏移） |
| 注视点 | `look_at_offset: {dx,dy,dz}`（偏移按世界轴表达） | `look_at_offset: {fwd,up,right}`（偏移按基准坐标系表达） |

- 四项**各自独立**：位置跟基准不影响 yaw，yaw 跟基准不影响 pitch（水平面 / 垂直面分开取来源），
  注视点偏移的表达空间不影响位置。
- `look_at != none` 时注视点接管 yaw 与 pitch（**互斥、不叠**）：此时 `yaw_base` / `pitch_base` 不参与。

### 方向锁（`yaw_lock` / `pitch_lock`）

每个轴各自一把锁：**不写** = 跟方向源（`yaw_base` / `pitch_base`）；**写了** = 用作者给的固定值。
锁值 = 该轴**世界角绝对值**（度），不做「相对来源」形态；要相对就先取源再锁。
两轴独立，可以一轴锁值、另一轴跟来源（典型第三人称 = `"pitch_lock": 15` + `"yaw_base": "entity"`）。

```json
{ "yaw_base": "entity", "yaw_base_selector": "@p", "pitch_lock": 15, "yaw": 0, "pitch": 0 }
```

上例：pitch 锁在 15°（俯视），yaw 跟玩家身体朝向；`yaw` / `pitch` 仍是叠加在基准上的偏移。

**叠加次序**（同一关键帧内，yaw 与 pitch 各按本表；自上而下第一条命中的生效）：

| 优先级 | 关键帧写法 | 该轴最终世界角 |
|---|---|---|
| 1 | `look_at != none`（开） | 由动态目标点插值决定；**锁、基准、偏移都不参与**（静态侧整体被覆盖） |
| 2 | clip 级 `orient: "tangent"`（切线朝向，且无 `look_at`） | 由路径切线决定；**锁、基准、偏移都不参与**（与 `look_at` 同属动态侧） |
| 3 | `look_at = none`（关）+ 写 `yaw_lock` / `pitch_lock` | **锁值** + 关键帧 `yaw` / `pitch` 偏移 |
| 4 | `look_at = none`（关）+ 不写锁 | **方向源**（`yaw_base` / `pitch_base`）+ 关键帧偏移 |

- 锁替换的是该轴**基准**（方向源给的值），不是关键帧偏移：`yaw_lock: 100` + `yaw: 5` = 105。
- 某轴被锁 = 该轴的基准来源不参与求值：`yaw_base: "line"` 的端点缺失也不再使片段按空处理（锁不查来源）。
- 同一关键帧可以两种写法并存（既写锁又写 `look_at`），同一时刻只有一侧生效（`look_at` 侧优先）。

### `up_axis` 移除（0.3.6，不兼容）

`position.up_axis` 字段整体移除（schema / 解析 / 校验 / 文档），出现即报错：

- 语义去向：`"view"` ≈ 两轴都不锁（`yaw_base` / `pitch_base` 跟来源）；`"world"` ≈ `pitch_lock: 0`。
- 基准坐标系的上轴**固定为世界竖直**（前轴 / 右轴取水平面内分量，前轴俯仰不参与）。
- 报错文本：`position.up_axis 已移除（0.3.6 起）：基准坐标系的上轴固定为世界竖直（前轴 / 右轴取水平面内分量）；方向侧每轴跟来源 / 锁值改用 yaw_lock / pitch_lock`。

### `position.relative_origin` 系移除（0.3.6，不兼容）

`position.relative_origin` 与配套坐标 `relative_origin_x/y/z` 整体移除（schema / 解析 / 校验 / 文档），出现即报错：

- 语义去向：位置基准统一为**关键帧字段** `facing_origin`（五形态点源，见上方 §「点源」）；偏移仍写在 `position` 对象里（世界轴 `dx/dy/dz` / 基准坐标系 `fwd`/`up`/`right`，见上方 §「偏移」）。
- 字段落点：`facing_origin` / `facing_target` 是**关键帧**字段（与 `yaw_base_from` 同级），不写在 `position` 内（写进 `position` 同样报错）。
- 报错文本：`position.relative_origin 已移除（0.3.6 起）：位置基准统一用关键帧字段 facing_origin（写法见 docs/SCRIPT_FORMAT.md §「点源」）`。

```json
// 旧写法（0.3.6 前，出现即报错）
"position": { "relative_origin": "coordinate", "relative_origin_x": 0, "relative_origin_y": 64, "relative_origin_z": 10, "dx": 3, "dy": 2, "dz": 0 }

// 新写法
"facing_origin": "coordinate", "facing_origin_x": 0, "facing_origin_y": 64, "facing_origin_z": 10,
"position": { "dx": 3, "dy": 2, "dz": 0 }
```

### `look_at_target` 对象形态移除（0.3.6，不兼容）

`look_at_target` 对象形态（含对象内 `relative_to` / `relative_x/y/z` / 对象内偏移）整体移除（schema / 解析 / 校验 / 文档），出现即报错：

- 语义去向：注视目标统一为**点源 + 偏移**的平面字段——实体用 `look_at_selector`（部位百分比 `look_at_part`）、坐标用 `look_at_target_x/y/z`、结构用 `look_at_target_structure`、方块用 `look_at_target_block`，偏移用 `look_at_offset`（世界轴 `dx/dy/dz` / 基准坐标系 `fwd`/`up`/`right`，见上方 §「偏移」）。
- 报错文本：`<路径>.look_at_target 已移除（0.3.6 起）：注视目标统一为点源 + 偏移——实体用 look_at_selector（部位百分比 look_at_part）、坐标用 look_at_target_x/y/z、结构用 look_at_target_structure、方块用 look_at_target_block，偏移用 look_at_offset（世界轴 dx/dy/dz / 基准坐标系 fwd/up/right）`。

```json
// 旧写法（0.3.6 前，出现即报错）
"look_at": "coordinate", "look_at_target": { "relative_to": "@p", "dx": 0, "dy": 1, "dz": 0 }

// 新写法
"look_at": "entity", "look_at_selector": "@p", "look_at_offset": { "dy": 1 }
```

### Position（相对模式 `relative`）

```json
"position": { "dx": 30, "dy": 2, "dz": 0 }
```

位置 = **位置点源 + 偏移**：点源 = 关键帧字段 `facing_origin`（五形态，见上方 §「点源」；缺省 = 玩家激活位置），
偏移 = 本对象。

| 字段 | 类型 | 说明 |
|------|------|------|
| `dx` | float | 相对位置点源的世界 X 偏移（**follow=entity 时 = 相对实体脚底的 X 偏移**） |
| `dy` | float | 相对位置点源的世界 Y 偏移（**follow=entity 时 = 相对实体脚底的 Y 偏移**） |
| `dz` | float | 相对位置点源的世界 Z 偏移（**follow=entity 时 = 相对实体脚底的 Z 偏移**） |

`position` 对象里也可承载**基准坐标系偏移**（`fwd`/`up`/`right`，与 `dx/dy/dz` 互斥；
原点 = `facing_origin`，前轴 = `facing_target`，上轴 = 世界竖直，见上方 §「偏移」）：

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `fwd` | float | `0` | 沿前轴 前后（正=前 负=后） |
| `up` | float | `0` | 沿上轴 上下（正=上 负=下）；上轴固定为世界竖直 |
| `right` | float | `0` | 沿右轴 左右（正=右 负=左） |

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

> **为什么用对象而非平铺字段**：矩形是一个整体语义（四个分量同属一个参数），与既有 `position` 的复合对象写法一致；关键帧按"复合值整体插值"（`KeyframeInterpolator` 对 `position` 即是如此），对象形态让 dest/source 随时间整体插值更自然，字段表也更小。

**叠放顺序（`order`）不写成字段**：层级由**轨道层级 → 轨道内 clip 顺序**决定（后面的 clip 在上一层），作者无需显式书写。

**常见形态**（都是同一套参数的特例）：

| 形态 | 写法 |
|------|------|
| 主相机（默认） | `dest` 全屏、`opacity` = 1（都可省略） |
| PIP 画中画 | `dest` = 小矩形（如右下角 `{x:0.6,y:0.05,w:0.35,h:0.35}`） |
| 分屏 | 两个 clip 各占半屏（`dest` 左/右或上/下） |
| 叠化 | 同轨两条全屏 clip：下层 A 全程 `opacity: 1`，上层 B 在重叠窗口 `0 → 1`（不要写成 A `1→0` + B `0→1`） |
| 数字变焦 / 局部取景 | `source` 取局部矩形、`dest` 全屏 |

> 渲染消费已落地（2026-10-07）：脚本 lane 的合成参数由 `client/lane/ScriptLaneDriver` 按本节字段
> 逐帧从 clip 关键帧插值取出，交给 `LaneCompositor` 上屏（缺省 = `1` / 全屏 / 全幅）。
> 插值为**匀速线性**（0.3.6 起运行时统一线性；缓动 = 编辑器烘焙成显式关键帧）。

### lane 合成遮罩（`mask_shape` / `mask_*`）

CAMERA 片段可以给**该片段产出的整幅 lane 画面**加一个几何覆盖范围；遮罩只决定 lane 哪些像素参与合成，**不裁切字幕、图片或其它覆盖层**，也不改变 `source` / `dest` 的取材与放置。

- **字段归属**：`mask_shape` 写在 CAMERA **clip 级**；`mask_x` / `mask_y` / `mask_width` / `mask_height` / `mask_radius` 只写在**关键帧**。写错层级 / 写错轨道会被解析 / 校验带字段路径拒绝，不会静默生效；非法形状枚举、非有限值、负尺寸同样拒绝。
- **形状**：`full`（缺省）= 整幅；`rect` = 窗口归一化矩形；`circle` = 窗口物理像素里的真圆。
- **几何**（`W` / `H` = 播放窗口宽 / 高）：
  - `rect`：中心 `(mask_x × W, mask_y × H)`；半宽 `mask_width × W / 2`、半高 `mask_height × H / 2`。
  - `circle`：中心同上；半径 `mask_radius × min(W, H)`（按**短边**比例，非方形窗口仍是真圆）。
  - `mask_x` / `mask_y` 缺省 `0.5`，有限值，允许在窗口外；`mask_width` / `mask_height` 缺省 `1`，有限且非负、不设人为上限；`mask_radius` 缺省 `0.5`，有限且非负、不设人为上限。
  - 覆盖率是硬边 `0` / `1`（不做抗锯齿）：形状内覆盖率为 1，形状外为 0；区域外像素保持底图。
  - `rect` 的 `mask_width` 或 `mask_height` 为 `0`、或 `circle` 的 `mask_radius` 为 `0` = **该 lane 不绘制**（显露下层），这是划像的正常起点，不是「整屏全遮」。
- **与 opacity 的关系**：合成 alpha = `opacity × coverage`；先按形状算覆盖率，再与关键帧 `opacity` 相乘。遮罩不读取 lane 纹理的 alpha，也不改变 `source` / `dest` 的 UV / 顶点映射。
- **坐标口径**：遮罩几何始终按**播放窗口坐标**计算，不随 `dest` 的放置矩形缩放或平移；`dest` 只决定 lane 贴到哪里，`source` 只决定取材。
- **时间**：几何按该 clip 的本地时间与其它关键帧通道同一套匀速线性插值；hold / loop / pingpong 的时间语义与相机六参数一致。
- **兼容**：`full`（缺省）不读取任何几何通道，输出与旧版本逐像素一致；不写 `mask_shape` 的旧 clip 自动按 `full` 处理。
- **失败行为**：非 `full` 而专用合成 shader 不可用时，该 lane 跳过并记录错误，不会退回无遮罩输出；`full` 沿用既有回退。
- **能力边界**：`gradient` / `gradient_*` 仍只属于 OVERLAY 的 `layer_type=fade`；`blend_mode` 只属于 ADJUST 轨或 OVERLAY fade；CAMERA 都不接受。`mask_shape` / `mask_*` 也不适用于 `image` / `subtitle`，字幕永远完整呈现（不做文字裁切）。

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

> 完整可运行写法见 `cinematics/tests/transition/test_wipe.json`（rect 横 / 纵、circle 真圆）与 `test_dissolve.json`（叠化）。

### 相机片段调色

**每个相机片段直接携带自己的调色**（与 `dest` / `source` / `opacity` 同层，都写在 clip 的关键帧上）：该调色作用于**该相机轨产出的每条 lane**——在该 lane 渲染完成之后、合成之前生效（分屏 / 画中画里每一格可以各自调色）。ADJUST 轨只作用于整体画面（master），不参与 lane 级调色（见 §10）。

- **字段与 §10 完全同一套**（通道口径、缺省值、操作栈顺序、曲线写法全部相同，见 §10 Adjust 轨道）：
  - **clip 级 10 条曲线**（不随时间变，故挂 clip）：`rgb_curve`（RGB 复合曲线）+ `r_curve` / `g_curve` / `b_curve`（每通道曲线）+ `hv_h_curve` / `hv_s_curve` / `hv_l_curve` / `lv_s_curve` / `sv_s_curve` / `sv_l_curve`（六条 hue 曲线）。
  - **关键帧级 57 个通道**：47 个标量通道（缺省 `0` = 无效果）+ 10 个曲线强度（`curve_strength` / `r_curve_strength` / `g_curve_strength` / `b_curve_strength` / `hv_h_strength` ~ `sv_l_strength`，缺省 `1`）。
- **按片段本地时间采样**：每条 lane 用它自己相机片段的本地时间求值（叠化重叠窗口下一条轨可能同时产出多条 lane，各自独立采样）。参数全为缺省 = 不调色，走原路径（lane 渲染完直接进合成）。
- **每个关键帧都应写上所需通道**：关键帧缺某字段按缺省值解（标量 `0`、强度 `1`），只在一个关键帧里写、另一个不写，会在中间挖出折点——要让某项整段生效，就在该片段每个关键帧都写上它。
- **只承载 RGB（硬性口径）**：调色只动 RGB，`alpha` 逐位直通（着色器 `fragColor.a = src.a`）；**透明度（opacity / alpha）一律在画面合成完成之后、由合成层调控**（`LaneCompositor` / OVERLAY 层 `opacity`），绝不烤进画面、也不在调色里先处理 alpha。
- `scope` / `lane` 字段已移除（0.3.6 起 lane 级调色 = 相机片段字段）；写在 CAMERA 或 ADJUST clip 上都会被 validate 报出。

```json
{
  "type": "CAMERA",
  "clips": [
    {
      "start_time": 0,
      "duration": 8,
      "rgb_curve": [ [0, 0], [0.5, 0.8], [1, 1] ],
      "keyframes": [
        { "time": 0, "position": { "dx": 0, "dy": 3, "dz": -8 }, "yaw": 0, "pitch": 0, "roll": 0, "fov": 70, "zoom": 1,
          "dest": { "x": 0, "y": 0, "w": 0.5, "h": 1 }, "opacity": 1, "grayscale": 0, "curve_strength": 0 },
        { "time": 8, "position": { "dx": 0, "dy": 3, "dz": -8 }, "yaw": 0, "pitch": 0, "roll": 0, "fov": 70, "zoom": 1,
          "dest": { "x": 0, "y": 0, "w": 0.5, "h": 1 }, "opacity": 1, "grayscale": 1, "curve_strength": 1 }
      ]
    }
  ]
}
```

> 上例这条左半屏相机片段：画面在 8 秒内从彩色渐变为黑白、同时把 `rgb_curve` 的强度淡入——两项都只作用于这一格的 lane；`opacity`（合成参数）照旧在合成阶段控透明度。

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
| `opacity` | float | 否 | `1` | 黑边整体不透明度，`0` ~ `1`：`0` 不显示，`0.5` 半透明，`1` 纯黑；上下黑边与左右黑边均适用 |

画幅比和不透明度均按相邻关键帧**匀速线性插值**，首帧前 / 末帧后保持端点值；无活跃片段时不绘制黑边。运行时不额外施加 smoothstep，缓动由编辑器烘焙为显式关键帧。无限片段的本地时间照常推进。可运行 `cinematics/tests/overlay/test_letterbox_opacity.json` 查看分阶段示例。

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

## 9. OVERLAY 轨道（覆盖层：图片 / 字幕 / 颜色遮罩 / 画中画）

在屏幕上渲染图片、字幕、颜色遮罩（全屏或局部）或画中画。**支持多条 OVERLAY 轨道同时渲染**（如图片一条轨道、字幕一条轨道，用 `id` 区分），层间按 `z_index` 分层（大者在上）。

**层级与同时显示（显式 z 序）**：

- 每条 OVERLAY 轨同一时刻**只消费首个活跃 clip**（`OverlayTrackPlayer.findActiveClip`）。需要同时显示的层（如字幕 + 转场黑场 + 普通 fade）必须**各自使用独立 OVERLAY 轨道**；把两个同时段 clip 放进同一条轨，后面的 clip 不会同时渲染。
- `z_index` 只在本模组覆盖层之间排序，**不改原版 HUD**。普通 `fade` 不写 `z_index` 时保持默认 `10`；字幕示例写 `100`；转场黑/白场需要压住字幕时由作者**显式**写 `1000`。这不是「转场自动置顶」——遮罩是否盖住字幕由作者决定。
- 本节的「fade 专用」字段均指 OVERLAY 的 `layer_type=fade`；CAMERA 片段另有同名的 lane 遮罩字段（见 §4「lane 合成遮罩」），两者归属不同，不能互写。

### Clip 字段

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `start_time` | float | 是 | — | 起始时间 |
| `duration` | float | 是 | — | 持续时间 |
| `layer_type` | string | 是 | — | `"fade"` 颜色遮罩（全屏或局部）/ `"image"` 图片 / `"subtitle"` 字幕 |
| `path` | string | image 必需 | — | 图片文件名（如 `"my_image.png"` 或 `"flame.gif"`）。支持 **PNG / GIF**（GIF 自动拆帧按帧延迟轮播），文件放 `<游戏目录>/immersive_cinematics/resource/` 下，用英文命名 |
| `text` | string | subtitle 必需 | — | 字幕文本，`\n` 换行；支持 `@lang:<key>` 文本资源引用（见 [§12](#12-文本资源与多语言langkey)） |
| `color` | string | 否 | `#000000` | fade 颜色：`#RRGGBB` 六位十六进制（`#` 可省、大小写均可；不支持 ARGB）。纯色模式的基色 / 关键帧 `color` 缺省回落；渐变模式不参与取色（颜色由 `gradient.stops` 决定） |
| `gradient` | object | 否 | 无（纯色） | **fade 专用**：多色渐变 `{type: "linear"｜"radial", stops: [...]}`，见下方「fade 高级颜色遮罩」 |
| `mask_shape` | string | 否 | `full` | **仅 OVERLAY 的 `layer_type=fade` 使用**：`full` 整窗口 / `rect` 局部矩形 / `circle` 圆形。这是**颜色层**覆盖范围，**不是字幕或画面纹理裁切**；CAMERA 的同名字段是另一套 lane 遮罩（§4） |
| `blend_mode` | string | 否 | `normal` | **fade 专用（颜色层混合）**：`normal` / `multiply` / `screen` / `soft_light` / `overlay`，公式与 ADJUST 轨同一 W3C 口径 |
| `z_index` | int | 否 | `10` | 层级，越大越靠上。**0.3.6 起默认统一 10**（旧文档写 20）；只在本模组覆盖层之间排序，不改原版 HUD。普通 fade 保持 10，字幕示例写 100；转场 fade 要压住字幕时由作者显式写 1000（不是自动最高层）。同轨只消费首个活跃 clip，同时显示请分轨 |
| `keyframes` | array | 是 | — | 关键帧数组 |

### Keyframe 字段（统一参数：位置 = 窗口百分比，缩放 = 素材原始像素的倍数）

**画布 = 窗口**（0.3.6 起）：位置直接用**播放窗口**百分比（与分辨率无关）；尺寸是**素材自身**像素的倍数，播放时整体乘分辨率转义系数 `k`。不再有「参考画布 / 适配（`fit`）」这一层。

- **位置 `x` / `y`** = 窗口百分比：`x = 0.5` 就是播放窗口正中，任何分辨率下都一样。
- **缩放 `scale_x` / `scale_y`** = 相对**素材原始像素尺寸**的倍数：图片 = 原图像素（`scale_x = 2` → 两倍宽）；字幕 = `font_scale` 下的文字块像素。
- **分辨率转义 `k = min(W播/W基, H播/H基)`**：`W基×H基` = `meta.base_resolution`（缺省 `1920×1080`），`W播×H播` = 播放窗口物理像素。上屏尺寸 = `裁切后素材像素 × scale × k`；`k` 只保证**基准构图整体**不溢出窗口，单个元素可按作者意图越界。
- **非基准宽高比**（窗口宽高比 ≠ `base_resolution` 宽高比）：窗口里会单边留空 —— 模型不画任何东西，该区域显示下层画面；要黑边就写 LETTERBOX 轨的 `aspect_ratio`。位置按窗口百分比取，与「纯等比映射」的偏差 ≤ 留边厚度（中心为 0、边缘最大）。
- **`1920×1080` 窗口 + 缺省 `base_resolution` + `source` 整幅** 时 `k = 1`，与 0.3.6 之前逐像素一致。

> 下表中标注「fade 专用」的字段均指 **OVERLAY 的 `layer_type=fade`**。CAMERA 片段另有同名的 `mask_shape` / `mask_*` lane 几何（见 §4），两者作用对象不同、归属不同，不能互写；`image` / `subtitle` 不接受这些字段，字幕永远完整呈现。

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `time` | float | — | 在 clip 内的时间偏移（秒） |
| `x` | float | `0.5` | 元素**中心**在**播放窗口**上的水平位置：`0.5` = 窗口正中，`1` = 中心在窗口右缘（不钳制，可越界） |
| `y` | float | `0.5` | 元素**中心**在播放窗口上的垂直位置 |
| `anchor_x` | float | `0.5` | **锚点**（缩放绕点），元素**自身**归一化：`0` = 左缘，`1` = 右缘。`0.5` = 绕元素中心缩放（位置 = 元素中心） |
| `anchor_y` | float | `0.5` | 锚点（元素自身归一化，`0` = 上缘，`1` = 下缘） |
| `scale_x` | float | `1` | 缩放倍数（相对**素材原始像素尺寸**）：图片 = 原图像素 × 该乘数（`1` = 原图 1:1 像素）；字幕 = 文字块像素 × `font_scale` × 该乘数。上屏还要再乘 `k` |
| `scale_y` | float | `1` | 同上（纵向） |
| `source` | object | 全幅 `{0,0,1,1}` | **取材**（`image` 专用）：`{"x":0,"y":0,"w":1,"h":1}` 为素材归一化子矩形（**原点 = 素材左上角**），裁掉不要的部分（如只要左半：`{"x":0,"y":0,"w":0.5,"h":1}`） |
| `font_scale` | float | `1` | **字幕专用**：字号倍数（`1` = 原版 9px 字号）。矩阵缩放实现，与 MC `/title` 大字同一机制；它决定文字块本身的基准尺寸，可与 `scale_x/y` 叠加（最终缩放 = `font_scale × scale_x/y`） |
| `opacity` | float | `1` | 透明度（`0` = 完全透明，`1` = 不透明）。**淡入/淡出完全由该字段的关键帧表达**，代码层不叠加其他淡化。**0.3.6 起默认 1（旧默认 0 = 不可见）**：不写 opacity 的 clip 会直接显示 |
| `color` | string | 回落 clip 级 `color` | **fade 专用**：纯色模式颜色关键帧（`#RRGGBB`），逐 RGB 通道线性插值；首帧前 / 末帧后保持端点。渐变模式不消费（颜色来自 stops），但显式值仍校验格式 |
| `mask_x` / `mask_y` | float | `0.5` | **OVERLAY fade 专用**：覆盖区域中心，窗口比例（`0.5` = 窗口中心；可越界）。`full` 不读取 |
| `mask_width` / `mask_height` | float | `1` | **OVERLAY fade 专用**：`rect` 宽 / 高占窗口比例，非负，不设人为上限；任一为 `0` 不绘制。`circle` / `full` 不读取 |
| `mask_radius` | float | `0.5` | **OVERLAY fade 专用**：`circle` 半径 / 窗口**较短边**比例，非负；`0` 不绘制。非方形窗口仍是真圆 |
| `gradient_angle` | float | `0` | **fade 专用**：`linear` 方向角（度），有限值；`0` = 向右，`90` = 向下。`radial` 不读取 |
| `gradient_center_x` / `gradient_center_y` | float | `0.5` | **fade 专用**：渐变中心，窗口比例，有限值，可越界；`linear` / `radial` 都读取 |
| `gradient_radius` | float | `0.5` | **fade 专用**：`radial` 半径 / 窗口**较短边**比例，必须 `> 0`。`linear` 不读取 |

> **逐类基准尺寸**（`scale = 1` 时元素多大）：图片 = 裁切后素材像素 × `k`（整幅时 = 原图像素 × `k`）；字幕 = `font_scale` 下文字块像素 × `k`；效果层 / 画幅层 = 铺满窗口（无缩放概念）。位置始终指**未缩放基准矩形**的中心；锚点非 0.5 时，缩放会改变元素的视觉中心（同 CSS `transform-origin`）。
>
> **逐层生效范围**（0.3.6 起）：`fade` 是效果层，基准尺寸 = 铺满**窗口**，**x/y/anchor/scale 对它不生效**；它读取 `opacity`，并在声明高级颜色遮罩时读取下方 `color` / `mask_*` / `gradient_*` 通道。**x/y = 0.5 即窗口居中**；贴边需按元素尺寸/2 折算（如贴左缘 = 元素半宽），避免元素移出窗口。
> 字幕按**完整文本**呈现，不消费 `source`；取材裁切适用于图片与相机画面 lane，不要求每种图层具备全部参数能力。**在 OVERLAY 内，fade 的 `gradient` / `mask_shape` / `blend_mode`、`mask_*` / `gradient_*` 与关键帧 `color` 只属于 `layer_type=fade`**：写在 `image` / `subtitle` 上会被解析 / 校验直接拒绝，不会静默生效（clip 级 `color` 是通用字段，image / subtitle 不消费）；字幕不做任何裁切。CAMERA 的 `mask_shape` / `mask_*` 是 lane 合成遮罩（§4），不是这里的颜色层字段。
>
> **取材先于缩放**：先按 `source` 从素材裁出子矩形，再按 `scale` 放大（上屏尺寸 = `裁切后素材像素 × scale × k`）。**适配字段 `fit` 已移除（0.3.6 起）**：元素框由素材派生，素材恒按框铺满，宽高比由 `scale_x : scale_y` 直接表达 —— 旧 `fit: "stretch"` 语义不变，旧 `fit: "fit"` / `"fill"` 需改写 `scale`（写 `fit` 的脚本会被校验拦下）。
>
> **插值**（0.3.6 起）：关键帧之间**匀速线性**（每两个相邻关键帧定一段匀速运动），clip 级 `interpolation` 字段已移除。`source` 按分量整体线性，`z_index` 是离散值（步进取值）。想要缓动/速度曲线：编辑器按曲线采样、把补出的关键帧写进脚本（脚本里存显式关键帧），运行时照常线性求值。

### fade 高级颜色遮罩（0.3.6 D4–D7）

**不新增 `layer_type` / 轨道 / 转场机制**：仍是 `layer_type: "fade"`，旧的纯色全屏写法与规则 `overlay_set` 的临时 fade 都不变。只有声明了渐变、`rect` / `circle` 或非 `normal` 混合时才走专用 shader；**纯色 + `full` + `normal` 继续走原有全屏 fill 快路径**（不新建 FBO / shader，逐像素表现与旧 fade 一致）。规则 `overlay_set` 仍只支持 `layer_type` / `color` / `opacity` / `z_index`，不扩高级字段。

**字段归属（OVERLAY fade）**：`gradient` / `mask_shape` / `blend_mode` 只写在 fade 的 **clip 级**；`mask_*` / `gradient_*` 只写在 fade 的**关键帧**；`color` 两级都可写（关键帧缺省回落 clip）。写错层级会被解析 / 校验拒绝，不会静默生效。这里的 `mask_*` 是**颜色层**几何；CAMERA 的同名字段是 lane 合成遮罩（§4），两者不互通，CAMERA 也不接受 `gradient` / `gradient_*` / `blend_mode`。

**渐变定义（clip 级，整段固定）**：

```json
"gradient": {
  "type": "linear",
  "stops": [
    { "offset": 0,    "color": "#FF0000" },
    { "offset": 0.15, "color": "#00FF00" },
    { "offset": 1,    "color": "#0000FF" }
  ]
}
```

- `type` 必填：`"linear"` 或 `"radial"`。
- `stops` 至少 **2** 个，**不设数量上限**；每个 `offset` 必须是有限数字、范围 `0`~`1`、**严格递增**；首尾不必是 `0` / `1`，范围外保持端点颜色。
- 每个 stop 的 `color` 为 `#RRGGBB`（与 fade 的 `color` 同一口径）。`stops` 是结构数据，**定义在 clip 级、整段不变**；关键帧只驱动方向 / 中心 / 半径，不支持动画改变 stops 的数量或内容。
- 不写 `gradient` = 纯色，颜色取 clip 级 `color` / 关键帧 `color`。

**覆盖形状与几何（关键帧通道）**：

下式中 `W` / `H` = 播放窗口宽 / 高（GUI 像素）。

| 形状 | 中心 / 尺寸 |
|------|-------------|
| `full`（缺省） | 整窗口；`mask_*` 不读取 |
| `rect` | 中心 `(mask_x × W, mask_y × H)`；半宽 `mask_width × W / 2`，半高 `mask_height × H / 2`；`mask_width` 或 `mask_height` 为 `0` 不绘制 |
| `circle` | 中心同上；半径 `mask_radius × min(W, H)`（按窗口**短边**比例，非方形窗口仍是真圆）；`mask_radius = 0` 不绘制 |

- `mask_x` / `mask_y`：窗口比例，缺省 `0.5`，有限值，**允许在窗口外**。
- `mask_width` / `mask_height`：窗口比例，缺省 `1`，有限且非负、不设人为上限。
- `mask_radius`：窗口短边比例，缺省 `0.5`，有限且非负。
- `rect` / `circle` 只绘制「形状包围盒 ∩ 窗口」，形状外像素不改变；边缘为硬边，不做抗锯齿。

**渐变几何与采样**：

- `linear`：方向 `d = (cos θ, sin θ)`，`L = |cos θ| × W + |sin θ| × H`，`t = 0.5 + dot(p - center, d) / L`。θ = `gradient_angle`（度），缺省 `0` = 向右，`90` = 向下，有限值。
- `radial`：`t = distance(p - center) / (gradient_radius × min(W, H))`。`gradient_radius` 缺省 `0.5`，有限且必须 `> 0`。
- `gradient_center_x` / `gradient_center_y`：窗口比例，缺省 `0.5`，有限值，可越界；`linear` / `radial` 都读取。
- `t` 钳制到 `0`~`1` 后查 stops（范围外保持端点）。`gradient_angle` 只对 `linear` 生效，`gradient_radius` 只对 `radial` 生效；另一形态的对应通道不读取。

**混合模式（clip 级 `blend_mode`）**：

base = 本颜色层之前的实际屏幕内容；src = 本层颜色；二者均为逐通道 `0`~`1` 的 RGB。`amount = opacity × coverage`（coverage：形状内 `1`、形状外 `0`）。

| `blend_mode` | `B(base, src)` |
|--------------|----------------|
| `normal`（缺省） | `src` |
| `multiply` | `base × src` |
| `screen` | `1 - (1 - base) × (1 - src)` |
| `soft_light` | `src <= 0.5`：`base - (1 - 2×src) × base × (1 - base)`；否则 `base + (2×src - 1) × (D(base) - base)` |
| `overlay` | `base <= 0.5`：`2 × base × src`；否则 `1 - 2 × (1 - base) × (1 - src)` |

- `soft_light` 的 `D(x) = x <= 0.25 ? ((16x - 12)x + 4)x : sqrt(x)`；按 W3C compositing-1，**分支看 src、D 曲线作用于 base**（与 ADJUST 轨的 `blend_mode` 同一语义）。
- `normal` 用 `amount` 作为 alpha 做常规混合；纯色 + `full` + `normal` 走旧快路径，其余形态走 shader 的 `normal` 路径。
- fade **没有** ADJUST 轨的 `blend_amount`：混合强度就是关键帧 `opacity`（再乘形状 coverage），`opacity = 0` 时该层不产生任何混合。
- 非 `normal` 需要读取 backdrop：实现先把主 framebuffer 复制到 scratch，再按模式混合，**不读写同一纹理**。最终 `RGB = mix(base, B(base, src), amount)`；只改 RGB，不改变区域外像素、后续 GUI 或 framebuffer alpha。不同层按当前 `z_index` 顺序逐层合成。

**实现采样精度**：多色 stops 在 CPU 侧烘成 **1024×1** RGB 采样表（首尾 texel 对齐 `t = 0` / `1`，段内线性），片元按 `t` 取 texel 中心并做线性过滤。stops 数量不设上限，但任意密集或极窄区间的 stops 不保证逐像素与解析式完全一致；不承诺任意 stops 分布下的统一误差上限。

**资源与重载**：有渐变的高级 fade 层持有自己的 LUT；共享 shader / scratch 在最后一个高级层释放或资源重载时释放。移除层会调用 `close()`；F3+T / 资源包重载通过 `invalidate()` 排队到渲染线程释放资源，存活层下一帧按新代次重建。shader / LUT / scratch 创建失败时跳过该颜色层并记录日志，不会伪造纯黑或回退到不相符的效果。详见 [overlay 模块](modules/overlay.md)。

**仓库内 fixture**：`cinematics/tests/overlay/test_color_mask.json`（D4–D6 分阶段：线性 / 径向 / `rect` / `circle` / 五种混合 / 几何关键帧 / 纯色颜色关键帧）与 `cinematics/tests/overlay/test_overlay_zindex.json`（D7 z 层级演示）。运行方式与现有测试脚本一致，像素结果以实际客户端测量为准。

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
      "z_index": 100,
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

## 10. Adjust 轨道（整体画面调色 = master）

对**合成后的最终画面**（master 层，架构图 RADJ 节点）做颜色调整——在 lane 合成之后、GUI 之前作用于整屏画面。**单条相机轨画面的调色不写在这里**：调色直接写在 CAMERA 片段上（见 §4「相机片段调色」），作用于该相机轨产出的 lane（lane 渲染完成、合成之前）。

- **不影响 GUI**：字幕 / 黑边 / 跳过提示由 GUI 阶段绘制，调色不作用于它们（挂点在世界渲染阶段，早于 GUI）。
- **本版本 = 47 个标量通道**（12 标量 + R/G/B 每通道系数 + **RGB 通道混合器（`mix_rr` ~ `mix_bb` 九个）** + 完整 HSL 的 `hue` / `lightness` + **Lift / Gamma / Gain 色轮（`lift_r` ~ `gain_b` 九个）** + **PS 式六色带微调（`hue_red` / `sat_red` ~ `hue_magenta` / `sat_magenta` 十二个）**）**+ RGB 复合曲线 + 每通道曲线（R / G / B 各一条）+ 六条 hue 曲线（HvH / HvS / HvL、LvS / SvS / SvL）**（曲线组形态 b：曲线定义一次 + 各自的强度关键帧控混合强度）**+ 整体画面 LUT（clip 级 `lut` / `lut_input_gamma` + 关键帧 `lut_strength`，作用于整个世界画面；相机片段上写 `lut` / `lut_strength` 会被 validator 拦下）+ 层级混合（clip 级 `blend_mode` + 关键帧 `blend_amount`：调整层输出与基画面按混合模式整体混合，见下方「层级混合」）**。「调整层」（作用于其下所有层）由本轨承担——**调整层 = ADJUST 轨**（管整幅画面），无独立新层。
- **支持多条 ADJUST 轨道**：同一时刻以**后面的轨道**为准（轨道层级靠后的覆盖靠前的）。

### 执行顺序与 alpha 契约

```
lane 渲染（含 lane 内发光描边）
  → 相机片段调色（写在该 lane 相机片段上的调色：只动 RGB，alpha 直通）
  → 合成（opacity / dest / source）
  → 全部 lane 完成后
  → master 调整（ADJUST 轨：作用于合成输出，只动 RGB，alpha 直通）
```

- **调色只动 RGB**：`alpha` 逐位直通（着色器 `fragColor.a = src.a`）——透明度只在合成层由 `opacity` 调控（`LaneCompositor` / OVERLAY 层 opacity），调色不承担任何透明度语义。
- **相机片段调色按片段本地时间采样**：每条 lane 用它自己相机片段的本地时间求值（叠化重叠窗口下一条轨可能同时产出多条 lane，各自独立采样）。

### Clip 字段

| 字段 | 类型 | 必需 | 默认 | 说明 |
|------|------|------|------|------|
| `start_time` | float | 是 | — | 起始时间 |
| `duration` | float | 是 | — | 持续时间 |
| `keyframes` | array | 是 | — | 关键帧数组 |
| `rgb_curve` | array | 否 | — | **RGB 复合曲线**（曲线组形态 b）：控制点数组 `[[x, y], ...]`，至少 2 点、`x` 严格递增、`x` / `y` 各 0~1；不写 = 无曲线。见下方「曲线（`rgb_curve` / `r_curve` / `g_curve` / `b_curve`）」 |
| `r_curve` | array | 否 | — | **R 每通道曲线**：结构同 `rgb_curve`，只作用于红通道；不写 = 该通道无曲线 |
| `g_curve` | array | 否 | — | **G 每通道曲线**：结构同 `rgb_curve`，只作用于绿通道 |
| `b_curve` | array | 否 | — | **B 每通道曲线**：结构同 `rgb_curve`，只作用于蓝通道 |
| `hv_h_curve` | array | 否 | — | **HvH 曲线**：以 **hue** 为键、输出 **hue**（色相 → 色相，按色相分区旋转）；结构同 `rgb_curve` |
| `hv_s_curve` | array | 否 | — | **HvS 曲线**：以 **hue** 为键、输出 **饱和度**（按色相分区调饱和） |
| `hv_l_curve` | array | 否 | — | **HvL 曲线**：以 **hue** 为键、输出 **亮度**（按色相分区调明暗） |
| `lv_s_curve` | array | 否 | — | **LvS 曲线**：以 **亮度** 为键、输出 **饱和度**（暗部 / 亮部分区调饱和） |
| `sv_s_curve` | array | 否 | — | **SvS 曲线**：以 **饱和度** 为键、输出 **饱和度**（低饱和 / 高饱和分区调饱和） |
| `sv_l_curve` | array | 否 | — | **SvL 曲线**：以 **饱和度** 为键、输出 **亮度**（低饱和 / 高饱和分区调明暗） |
| `lut` | string | 否 | — | **整体画面 LUT**（master 级）：`resource/` 目录下的 `.cube` **文件名**（非空、不含路径分隔符 / 盘符，如 `"Teal and Orange.cube"`）；不写 = 无 LUT。**只写在 ADJUST 轨**——相机片段上写 `lut` 会被 validator 拦下（LUT 作用于整个世界画面，见下方「LUT（`lut` / `lut_input_gamma` / `lut_strength`）」） |
| `lut_input_gamma` | float | 否 | `1.0` | **LUT 输入域适配**：查表前 `v = pow(clamp(c, 0, 1), gamma)`（**必须 > 0**）；给现成 LUT（为别的素材调的）套上后整体偏亮 / 偏色时用来纠偏，自制（从游戏帧调的）LUT 保持缺省 `1.0`；**`lut` 为空时忽略** |
| `blend_mode` | string | 否 | `"normal"` | **层级混合的混合模式**（混合模式作用于调整层）：`normal`（直替换，= 不写）/ `multiply` / `screen` / `soft_light` / `overlay`——把**调整层输出**（调色操作栈全部步骤算出的颜色）与**基画面**（原图）按该模式整体混合，强度由关键帧 `blend_amount` 控制。**ADJUST 的 `blend_mode` 只写在 ADJUST 轨**——相机片段上写会被 validator 拦下；fade 颜色遮罩的同名字段是另一套（见 §9，公式同口径、作用对象不同）。详见下方「层级混合（`blend_mode` / `blend_amount`）」 |

> 十条曲线、`lut` / `lut_input_gamma` 与 `blend_mode` 都是 clip 级字段（不随时间变，故不挂关键帧）；47 个标量通道、十条曲线强度（`curve_strength` / `r_curve_strength` / `g_curve_strength` / `b_curve_strength` / `hv_h_strength` ~ `sv_l_strength`）、LUT 混合强度 `lut_strength` 与层级混合强度 `blend_amount` 全部写在关键帧上（与 letterbox/EVENT/AUDIO/OVERLAY 同一套「统一关键帧级调控」规则）。

### 曲线（`rgb_curve` / `r_curve` / `g_curve` / `b_curve` / `hv_h_curve` ~ `sv_l_curve`）— 复合曲线 + 每通道曲线 + 六条 hue 曲线（形态 b）

**曲线定义一次（clip 级），关键帧只控各自的混合强度**：每条曲线的作用量都可以随时间淡入淡出，但曲线形状本身不随时间变（形态 a「曲线点集也打关键帧」留作后续增强）。

```json
"rgb_curve":   [ [0, 0], [0.5, 0.8], [1, 1] ]
"r_curve": [ [0, 0], [0.5, 0.65], [1, 1] ]
"g_curve": [ [0, 0], [0.5, 0.35], [1, 1] ]
"b_curve": [ [0, 0], [0.5, 0.8], [1, 1] ]
```

- **四条曲线**：`rgb_curve` = RGB 复合曲线（三个通道共用一条，按各通道自己的输入查表）；`r_curve` / `g_curve` / `b_curve` = 每通道曲线（各只作用于自己的通道，PS 式「每通道曲线」）。四条各自独立，可以只写其中任意几条。
- **控制点**：`[x, y]`，`x` = 输入（0~1）、`y` = 输出（0~1）；**至少 2 点**、`x` **严格递增**（不递增 / 越界 / 点数不足会被校验与解析拦下）。
- **插值**：CPU 侧把控制点采样成 **256 点 LUT**（**Fritsch–Carlson 单调三次插值**：单调控制点 ⇒ 单调曲线、段内**无过冲**），渲染侧逐通道查表；`x` 小于首点 / 大于末点时**钳制到端点值**（不外推）。
- **混合**：`c = mix(c, rgb_curve(c), 强度)`，逐通道；每通道曲线只写自己的那个分量（`r_curve` 不改 g / b）。
- **强度**：`curve_strength` / `r_curve_strength` / `g_curve_strength` / `b_curve_strength`，各自 0~1、**缺省 1**——写了对应曲线就是全量生效；关键帧把它写回 `0` = 该曲线淡出（不需要额外的开关字段）。**没有对应曲线时该强度被忽略**。
- **操作栈位置**：**R/G/B 通道系数 + RGB 通道混合器之后、HSL 之前**——复合曲线在前（第 8 步）、每通道曲线紧随其后（第 9 步）。所以每通道曲线看到的是「复合曲线处理之后」的值（先整体风格化、再按通道微调），且曲线作用在「色温 / 色调 + 通道系数 + 通道混合器」之后：曲线拉回的黑场 / 白场不会再被通道系数 / 混合矩阵放大。
- **六条 hue 曲线**（`hv_h_curve` / `hv_s_curve` / `hv_l_curve` / `lv_s_curve` / `sv_s_curve` / `sv_l_curve`，DaVinci 曲线页口径）：控制点写法与上面四条完全相同，**但键（`x` 输入）与目标（`y` 输出）不一定是同一个量**——键是「进入 HSL 块时」的 hue / 亮度 / 饱和度，目标是 hue / 亮度 / 饱和度之一（见下表）。它们在 **HSL 块内**（标量 HSL 之后、灰度之前）生效，与标量 HSL **共用同一次换算**。

### 六条 hue 曲线（`hv_h_curve` ~ `sv_l_curve`）— 键 → 值的映射

| 曲线 | 键（输入 `x`） | 目标（输出 `y`） | 效果 |
|------|------|------|------|
| `hv_h_curve` | hue | hue | 按色相分区**旋转色相**（HvH）：红区抬到 0.1 = 红色系整体往橙偏 |
| `hv_s_curve` | hue | 饱和度 | 按色相分区**调饱和**（HvS）：红区拉到 1 = 只给红色系提饱和 |
| `hv_l_curve` | hue | 亮度 | 按色相分区**调明暗**（HvL）：红区抬到 0.6 = 只提亮红色系 |
| `lv_s_curve` | 亮度 | 饱和度 | 按明暗分区**调饱和**（LvS）：暗部压到 0.25 = 暗部降饱和（褪色阴影） |
| `sv_s_curve` | 饱和度 | 饱和度 | 按饱和分区**调饱和**（SvS）：中饱和抬到 0.7 = 只加强「不太艳」的部分 |
| `sv_l_curve` | 饱和度 | 亮度 | 按饱和分区**调明暗**（SvL）：高饱和压到 0.35 = 只压暗已经很艳的部分 |

```json
"hv_h_curve": [ [0, 0.1], [0.5, 0.5], [1, 1] ]
"hv_s_curve": [ [0, 1], [0.5, 0.5], [0.65, 1], [1, 1] ]
"hv_l_curve": [ [0, 0.6], [0.5, 0.5], [1, 0.5] ]
"lv_s_curve": [ [0, 0.25], [0.5, 0.5], [1, 1] ]
"sv_s_curve": [ [0, 0], [0.5, 0.7], [1, 1] ]
"sv_l_curve": [ [0, 0.5], [0.5, 0.5], [1, 0.35] ]
```

- **键是「进入 HSL 块时」的值**（`h0` / `s0` / `l0`，即 `rgb2hsl` 的原始输出）——**不受同一帧里 `hue` / `saturation` / `vibrance` / `lightness` 四个标量通道的改动影响**。想「只对原本的红色系提饱和」时，同帧开着 `hue` 旋转也不会让这条曲线跟着转。
- **目标是「值」而不是「增量」**：`y = 1` 表示「把该像素的饱和度拉到 1」，不是「加 1」。所以没有对应曲线时不要写强度——**没有曲线时强度被忽略**（渲染侧那一步直接跳过）。恒等曲线（`[ [0,0], [1,1] ]`）对 `hv_s_curve` 这类「键 ≠ 目标」的曲线**不是**无效果（它把饱和度置为 hue 值）。
- **强度**：`hv_h_strength` / `hv_s_strength` / `hv_l_strength` / `lv_s_strength` / `sv_s_strength` / `sv_l_strength`，各自 0~1、**缺省 1**；混合口径与上面四条一致（`值 = mix(原值, 曲线值, 强度)`）。
- **顺序固定**：HvH → HvS → HvL → LvS → SvS → SvL；**同一目标分量的多条按此序依次叠加**（比如 HvS 与 SvS 都写饱和度：先按色相调、再按饱和调，后者看到的是前者处理后的饱和度）。
- **灰点安全**：饱和度为 0 的像素（灰阶）`h` 无意义，但 `hsl2rgb` 在 `s <= 0` 时直接返回灰度——HvH / HvS / HvL 的键取到 0 也不会跳色或产生 NaN。
- **操作栈位置**：HSL 块内、四个标量 HSL 通道之后、灰度之前（第 16 步）；只动 H / S / L，**alpha 逐位直通**不变。

### PS 式六色带微调（`hue_red` / `sat_red` ~ `hue_magenta` / `sat_magenta`）— 分色带 Hue / Sat

PS「色相 / 饱和度」的六色带形态：色相环固定分成六条带，每条带各一对 `hue` / `sat`，**只动落在该带内的像素**（带内从中心到边界平滑衰减、带外完全不受影响）。

| 色带 | 中心角 | 色相通道 | 饱和度通道 |
|------|--------|----------|------------|
| 红 | 0° | `hue_red` | `sat_red` |
| 黄 | 60° | `hue_yellow` | `sat_yellow` |
| 绿 | 120° | `hue_green` | `sat_green` |
| 青 | 180° | `hue_cyan` | `sat_cyan` |
| 蓝 | 240° | `hue_blue` | `sat_blue` |
| 品红 | 300° | `hue_magenta` | `sat_magenta` |

- **带权重** = 升余弦锥形 `w = 0.5·(1 + cos(π·|Δ| / 30°))`（`|Δ|` = 像素色相与带中心的最短角距，单位度；`|Δ| ≥ 30° → w = 0`）：中心 `w = 1`、`±15°` 处 `w = 0.5`、`±30°`（带边界）`w = 0`。六条带各撑 ±30°、恰好铺满色相环——**边界上的像素（如 30° 的橙）两条相邻带都是 0，等于不受影响**（连续、不跳变、不产生负权重）。
- **像素级语义**：`Δhue = Σ wᵢ·hueᵢ`（再 ×180°）、`Δsat = Σ wᵢ·satᵢ`；**先旋色相、再改饱和**：`h' = fract(h + 0.5·Δhue)`、`s' = clamp(s·(1 + Δsat), 0, 1)`；亮度不动。
- **典型用法**：`hue_red = 0.1` = 红色系整体往橙偏（+18°）；`sat_red = 0.5` = 只给红色系提 50% 饱和；`sat_green = -1` = 绿色系全灰；`hue_cyan` / `sat_magenta` 等同理。十二个通道全 0 = 无效果（缺省即无效果，不需要开关字段）。
- **灰点安全**：饱和度为 0 的像素色相无意义，但 `s = 0` 时 `hsl2rgb` 直接返回灰度——`hue_*` 对灰阶 / 黑白像素没有影响（**透明像素的 RGB = 黑也保持为黑**，不会被「复活」）。
- **操作栈位置**：HSL 块（含六条 hue 曲线）之后、灰度之前（第 17 步）；只动 H / S，**alpha 逐位直通**。

### Keyframe 字段（47 个标量通道 + 十条曲线强度 + LUT 强度）

**47 个标量通道缺省 0 = 无效果**（十条曲线强度与 LUT 强度例外：缺省 1）：不写 = 不做这项调整；把通道写回 0 = 这项调整淡出（不需要额外的开关字段）。
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
| `mix_rr` | float | `0` | -1 ~ 1 | **通道混合器：输出 R ← 输入 R**（额外系数，叠加在单位阵上）：`-1` = 去掉红通道自身的贡献、`+0.5` = 红再加半份自己 |
| `mix_rg` | float | `0` | -1 ~ 1 | **输出 R ← 输入 G**：`0.3` = 把绿的三成并进红 |
| `mix_rb` | float | `0` | -1 ~ 1 | **输出 R ← 输入 B** |
| `mix_gr` | float | `0` | -1 ~ 1 | **输出 G ← 输入 R** |
| `mix_gg` | float | `0` | -1 ~ 1 | **输出 G ← 输入 G**（额外系数，同 `mix_rr`） |
| `mix_gb` | float | `0` | -1 ~ 1 | **输出 G ← 输入 B** |
| `mix_br` | float | `0` | -1 ~ 1 | **输出 B ← 输入 R** |
| `mix_bg` | float | `0` | -1 ~ 1 | **输出 B ← 输入 G** |
| `mix_bb` | float | `0` | -1 ~ 1 | **输出 B ← 输入 B**（`-1` = 蓝通道归零） |
| `curve_strength` | float | `1` | 0 ~ 1 | **RGB 复合曲线的混合强度**（`rgb_curve` 存在时生效）：`1` = 曲线全量、`0.5` = 一半、`0` = 曲线淡出；**没有 `rgb_curve` 时忽略** |
| `r_curve_strength` | float | `1` | 0 ~ 1 | **R 每通道曲线的混合强度**（`r_curve` 存在时生效），口径同 `curve_strength` |
| `g_curve_strength` | float | `1` | 0 ~ 1 | **G 每通道曲线的混合强度**（`g_curve` 存在时生效），口径同 `curve_strength` |
| `b_curve_strength` | float | `1` | 0 ~ 1 | **B 每通道曲线的混合强度**（`b_curve` 存在时生效），口径同 `curve_strength` |
| `lift_r` | float | `0` | -1 ~ 1 | **Lift 色轮（阴影，R）**：`c' = c + 值·(1-c)`——正 = 抬阴影（纯黑被抬到「值」的亮度）、负 = 压黑；`c = 1` 的通道不动 |
| `lift_g` | float | `0` | -1 ~ 1 | **Lift 色轮（阴影，G）**，口径同 `lift_r` |
| `lift_b` | float | `0` | -1 ~ 1 | **Lift 色轮（阴影，B）**，口径同 `lift_r` |
| `gamma_r` | float | `0` | -1 ~ 1 | **Gamma 色轮（中间调，R）**：`c' = pow(max(c,0), exp2(-值))`——`0` = 恒等；正 = 中间调提亮（`+1` = 平方根）、负 = 压暗（`-1` = 平方）；黑白两端不动 |
| `gamma_g` | float | `0` | -1 ~ 1 | **Gamma 色轮（中间调，G）**，口径同 `gamma_r` |
| `gamma_b` | float | `0` | -1 ~ 1 | **Gamma 色轮（中间调，B）**，口径同 `gamma_r` |
| `gain_r` | float | `0` | -1 ~ 1 | **Gain 色轮（高光，R）**：`c' = c·(1+值)`——`-1` = 该通道归零、`+0.5` = ×1.5、`+1` = ×2（可能被钳到白） |
| `gain_g` | float | `0` | -1 ~ 1 | **Gain 色轮（高光，G）**，口径同 `gain_r` |
| `gain_b` | float | `0` | -1 ~ 1 | **Gain 色轮（高光，B）**，口径同 `gain_r` |
| `lut_strength` | float | `1` | 0 ~ 1 | **整体画面 LUT 的混合强度**（clip 级 `lut` 存在时生效）：`1` = LUT 全量、`0.5` = 一半、`0` = LUT 淡出；**没有 `lut` 时忽略** |
| `hv_h_strength` | float | `1` | 0 ~ 1 | **HvH 曲线的混合强度**（`hv_h_curve` 存在时生效），口径同 `curve_strength` |
| `hv_s_strength` | float | `1` | 0 ~ 1 | **HvS 曲线的混合强度**（`hv_s_curve` 存在时生效），同上 |
| `hv_l_strength` | float | `1` | 0 ~ 1 | **HvL 曲线的混合强度**（`hv_l_curve` 存在时生效），同上 |
| `lv_s_strength` | float | `1` | 0 ~ 1 | **LvS 曲线的混合强度**（`lv_s_curve` 存在时生效），同上 |
| `sv_s_strength` | float | `1` | 0 ~ 1 | **SvS 曲线的混合强度**（`sv_s_curve` 存在时生效），同上 |
| `sv_l_strength` | float | `1` | 0 ~ 1 | **SvL 曲线的混合强度**（`sv_l_curve` 存在时生效），同上 |
| `hue_red` | float | `0` | -1 ~ 1 | **PS 式六色带：红带（中心 0°）色相旋转**：`±1` = ±180°；只作用于落在红带内的像素（带权重 = 升余弦锥形，`±30°` 支撑） |
| `sat_red` | float | `0` | -1 ~ 1 | **六色带：红带饱和度增量**：`sat *= (1 + Δsat)`、钳制 0~1；`-1` = 该带内全灰、`+1` = 双倍 |
| `hue_yellow` | float | `0` | -1 ~ 1 | **六色带：黄带（中心 60°）色相旋转**，口径同 `hue_red` |
| `sat_yellow` | float | `0` | -1 ~ 1 | **六色带：黄带饱和度增量**，口径同 `sat_red` |
| `hue_green` | float | `0` | -1 ~ 1 | **六色带：绿带（中心 120°）色相旋转**，口径同 `hue_red` |
| `sat_green` | float | `0` | -1 ~ 1 | **六色带：绿带饱和度增量**，口径同 `sat_red` |
| `hue_cyan` | float | `0` | -1 ~ 1 | **六色带：青带（中心 180°）色相旋转**，口径同 `hue_red` |
| `sat_cyan` | float | `0` | -1 ~ 1 | **六色带：青带饱和度增量**，口径同 `sat_red` |
| `hue_blue` | float | `0` | -1 ~ 1 | **六色带：蓝带（中心 240°）色相旋转**，口径同 `hue_red` |
| `sat_blue` | float | `0` | -1 ~ 1 | **六色带：蓝带饱和度增量**，口径同 `sat_red` |
| `hue_magenta` | float | `0` | -1 ~ 1 | **六色带：品红带（中心 300°）色相旋转**，口径同 `hue_red` |
| `sat_magenta` | float | `0` | -1 ~ 1 | **六色带：品红带饱和度增量**，口径同 `sat_red` |
| `grayscale` | float | `0` | 0 ~ 1 | 灰度强度：`1` = 完全黑白（按 Rec.709 亮度），`0.5` = 半黑白 |
| `invert` | float | `0` | 0 ~ 1 | 反相强度：`1` = 完全反相，`0.5` = 半反相 |
| `blend_amount` | float | `1` | 0 ~ 1 | **层级混合强度**（clip 级 `blend_mode` 存在时生效）：`1` = 调整层全量（缺省）、`0.5` = 一半、`0` = **调整层整体透明**（输出 = 基画面 = 画面逐位不变）；可随时间淡入淡出。**只写在 ADJUST 轨**——相机片段关键帧上写 `blend_amount` 会被 validator 拦下 |

**操作顺序固定**（同一关键帧里多个通道同时生效时按此顺序计算，不可调）：
曝光 → 对比度 → 高光/阴影 → 白场/黑场 → 色温/色调 → R/G/B 通道系数 → **RGB 通道混合器（`mix_rr` ~ `mix_bb` 3×3 矩阵）** → **RGB 复合曲线（`rgb_curve`）** → **每通道曲线（`r_curve` / `g_curve` / `b_curve`）** → **Lift / Gamma / Gain 色轮（`lift_r` ~ `gain_b`）** → **整体画面 LUT（`lut` + `lut_strength`）** → 色相旋转 → 饱和度 → 自然饱和度 → 亮度 → **六条 hue 曲线（`hv_h_curve` → `hv_s_curve` → `hv_l_curve` → `lv_s_curve` → `sv_s_curve` → `sv_l_curve`）** → **PS 式六色带微调（`hue_red` / `sat_red` → … → `hue_magenta` / `sat_magenta`）** → 灰度 → 反相 → **层级混合（`blend_mode` + `blend_amount`：调整层输出 vs 基画面，全部步骤之后整体施加）**。

> **通道混合器是 3×3 矩阵**：实际矩阵 = **单位阵 + 参数矩阵**（所以九个全 0 = 单位阵 = 无效果）。
> 行 = 输出通道、列 = 输入通道：`out.r = (1+mix_rr)·r + mix_rg·g + mix_rb·b`，g / b 行同式
> （`out.g = mix_gr·r + (1+mix_gg)·g + mix_gb·b`、`out.b = mix_br·r + mix_bg·g + (1+mix_bb)·b`）。
> 它在 **R/G/B 通道系数之后**（系数是逐通道乘性，矩阵是通道间混合）、**曲线之前**——
> 曲线拉回的黑场 / 白场不会被矩阵再放大，且矩阵结果与通道系数同段受 `[0,1]` 钳制管辖（HSL 输入有界）。
> 九个参数全 0 时跳过整个乘法（逐位恒等）；只动 RGB，**alpha 逐位直通**。

> **HSL 组共用一次换算**：`hue` / `saturation` / `vibrance` / `lightness` 与六条 hue 曲线在同一个块里处理
> （一次 `rgb2hsl`、一次 `hsl2rgb`）：标量通道按「色相旋转 → 饱和度 / 自然饱和度 → 亮度」，随后六条 hue 曲线按上表顺序、
> 各自以「进入块时」的 `h0` / `s0` / `l0` 为键查表混合。
> 四个标量值全为 `0` **且**六条曲线都不生效时整块跳过（逐位恒等）；只有标量通道生效时与不带曲线功能时逐位一致。
> 色相旋转是「先算的」——但它只动 H，`saturation` / `vibrance` 只动 S、`lightness` 只动 L，三者互不影响。

> **两个「灰」不是同一个灰**：`grayscale` 用 Rec.709 亮度（0.2126 / 0.7152 / 0.0722）；
> `saturation = -1` 走 HSL，得到的是 HSL 亮度 L =（max+min)/2。彩色转灰时两者数值不同（都正确，口径不同）。

### RGB 通道混合器（`mix_rr` ~ `mix_bb`）— 3×3 矩阵

PS 式「通道混合器」：把 R / G / B 三个输入通道按 3×3 矩阵重新组合。**矩阵 = 单位阵 + 参数矩阵**，所以九个参数缺省全 `0` 就是单位阵 = 无效果：

```text
out.r = (1+mix_rr)·r + mix_rg·g + mix_rb·b
out.g = mix_gr·r + (1+mix_gg)·g + mix_gb·b
out.b = mix_br·r + mix_bg·g + (1+mix_bb)·b
```

- **行 = 输出通道、列 = 输入通道**：`mix_rg` = 「把绿并进红」的量、`mix_gr` = 「把红并进绿」的量；**对角线**（`mix_rr` / `mix_gg` / `mix_bb`）是「本通道自己再加 / 再减多少份」，`-1` = 去掉该通道自身的贡献（如 `mix_bb = -1` = 蓝通道归零，蓝画面只剩红绿混合的结果）。
- **范围 `-1 ~ 1`**、缺省 `0`；所有系数都可以打关键帧（各自独立，能随时间淡入淡出）。
- **操作栈位置**：**R/G/B 通道系数之后、曲线之前**（第 7 步）——通道系数是逐通道乘性、混合器是通道间重组；矩阵结果与通道系数同段受 `[0,1]` 钳制管辖（HSL 输入有界）。九个全 `0` 时跳过整个乘法（逐位恒等）。
- **只动 RGB**：`alpha` 逐位直通不变。

```json
{
  "type": "adjust",
  "id": "channel_mixer",
  "clips": [
    {
      "start_time": 0,
      "duration": 8,
      "keyframes": [
        { "time": 0, "mix_rg": 0.3, "mix_gr": -0.2, "mix_bb": -1 },
        { "time": 4, "mix_rr": 0.5, "mix_rg": 0.3, "mix_rb": 0.2, "mix_gr": -0.2, "mix_gg": 0.2, "mix_gb": -0.1, "mix_br": 0.3, "mix_bg": 0.4, "mix_bb": -1 },
        { "time": 8, "mix_rg": 0.3, "mix_gr": -0.2, "mix_bb": -1 }
      ]
    }
  ]
}
```

> 上面这条：常量部分 = 「绿的三成并进红、红的 -0.2 份并进绿、蓝归零」，`t=4` 时额外把红 / 绿各自加半份自己并把蓝的 0.3 / 0.4 份分别并进红 / 绿——矩阵系数全程线性插值。

### Lift / Gamma / Gain 色轮（`lift_r` ~ `gain_b`）— 阴影 / 中间调 / 高光三组逐通道色轮

达芬奇 / Lumetri 式的三组色轮，**逐通道**（R / G / B 各一个参数），每组管一个亮度区间：

```text
Lift（阴影）    c' = c + lift·(1 - c)              正 = 抬阴影、负 = 压黑（c = 1 的通道不动）
Gamma（中间调） c' = pow(max(c, 0), exp2(-gamma))  0 = 恒等；正 = 中间调提亮、负 = 压暗（黑白两端不动）
Gain（高光）    c' = c·(1 + gain)                  正 = 乘性提亮、负 = 压暗（可能被钳到白）
```

- **Lift**：往白端（`1`）混合——纯黑 `0` 被抬到「`lift` 值」的亮度（`lift_r = 0.5` → 红通道从 0 抬到 0.5）、纯白不动（`1 + lift·(1-1) = 1`）；负值反向（`-0.5` 把 0 压到 0 以下，最终被钳到 0）。做「发灰的阴影」（抬黑场）或「更实的黑」（压黑）。
- **Gamma**：幂次曲线，指数 = `exp2(-gamma)`——`gamma = +1` → 指数 0.5（平方根，中间调提亮）、`gamma = -1` → 指数 2（平方，中间调压暗）、`gamma = 0` → 指数 1（恒等）。`c = 0` / `c = 1` 两端恒为 0 / 1（黑白不动），只动中间调。中灰 128 在 `+1` 下变 181、在 `-1` 下变 64。
- **Gain**：乘性增益 = `1 + 值`——`-1` = 该通道归零、`+0.5` = ×1.5、`+1` = ×2（`128 × 2 = 256` 被钳到 255）。做「高光提亮」或「高光压暗」。
- **三组都是逐通道**：`lift_r` 只动红、`gamma_b` 只动蓝——可以做整体色调（三通道同值）、也可以做「只抬红色阴影」这类分区偏色；每个参数独立可打关键帧、随时间淡入淡出。
- **范围 `-1 ~ 1`**、缺省 `0` = 无效果；九个全 `0` 时整步跳过（画面逐位不变）。
- **操作栈位置**：**每通道曲线之后、`[0,1]` 钳制之前**（第 10 步）——色轮看到的是「通道系数 / 混合器 / 曲线」处理之后的值，而它的输出与前面各步同受那一次钳制管辖（HSL 输入仍有界）。
- **只动 RGB**：`alpha` 逐位直通不变。

```json
{
  "type": "adjust",
  "id": "lgg_wheels",
  "clips": [
    {
      "start_time": 0,
      "duration": 8,
      "keyframes": [
        { "time": 0, "lift_r": 0, "lift_g": 0.25, "gamma_b": -1, "gain_g": 0.5 },
        { "time": 4, "lift_r": 0.5, "lift_g": 0.25, "gamma_b": -1, "gain_g": 0.5, "gain_r": -1, "gamma_r": 1 },
        { "time": 8, "lift_r": 0, "lift_g": 0.25, "gamma_b": -1, "gain_g": 0.5 }
      ]
    }
  ]
}
```

> 上面这条：常量部分 = 「绿阴影抬 0.25 + 蓝中间调压暗 + 绿高光 ×1.5」，`t=4` 时额外把红阴影抬 0.5、红中间调提亮、红高光压到 0——九个通道全程线性插值，写回 0 即该项淡出。

### LUT（`lut` / `lut_input_gamma` / `lut_strength`）— 整体画面 3D 查找表

达芬奇 / Lumetri 式的 **`.cube` LUT**：在专业调色工具里调好色、导出 `.cube` 放进资源目录，脚本里引用文件名即可把整幅世界画面烘焙成该风格（如 Teal and Orange 这类电影感套色）。

- **文件与兼容**：`.cube` 放在 `resource/` 目录下（与图片 / 语言文件同一个资源目录），`lut` 写**文件名**（如 `"Teal and Orange.cube"`）——非空、不含路径分隔符 / 盘符，扩展名大小写不敏感。解析为纯 Java 实现（零外部库），兼容达芬奇等主流工具的导出：**1D shaper 与 `DOMAIN_MIN` / `DOMAIN_MAX` 在加载期就烘焙进一张 ≤ 64³ 的合成 3D 表**，运行时只做一次 3D 查表（**四面体插值**）。创作者工作流（捕获游戏帧 → 调色 → 导出 → 引用）见 [LUT 工作流](./LUT_WORKFLOW.md)。
- **字段**：clip 级 `lut`（string，缺省不写 = 无 LUT）与 `lut_input_gamma`（float，缺省 `1.0`、必须 `> 0`）；关键帧级 `lut_strength`（0 ~ 1，缺省 `1`，可随时间淡入淡出）。
- **查表口径**：`v = pow(clamp(c, 0, 1), lut_input_gamma)` → 查 3D 表 → `c = mix(c, lut(v), clamp(lut_strength, 0, 1))`。`lut_input_gamma` 是给**现成 LUT**（为别的素材 / 别的游戏调的）准备的输入域适配：套上后整体偏亮 / 偏色时用它纠偏，自制（从游戏帧调的）LUT 保持缺省 `1.0`；`lut` 为空时忽略。
- **操作栈位置**：**Lift / Gamma / Gain 色轮之后、HSL 块之前**（第 11 步，后续步骤顺延为 12 ~ 18）——LUT 看到的是「基础校色 + 通道 + 曲线 + 色轮」处理后的值；输出不额外钳制（LUT 数据本身可超出 `[0,1]`），与前面各步同由 HSL 块前那一次 `[0,1]` 钳制兜底。
- **master 级语义**：LUT 作用于**整个世界画面**（合成之后、GUI 之前），只写在 **ADJUST 轨**的片段上；相机片段上写 `lut` / `lut_strength` 不生效，会被 validator 直接拦下。
- **不影响 GUI**：黑边 / 字幕 / 跳过提示在 GUI 阶段绘制，不被 LUT 套色。
- **零差异**：不写 `lut`（或 `lut_strength` 写回 `0`）→ 整步跳过，画面逐位不变；只动 RGB，**alpha 逐位直通**。

```json
{
  "type": "adjust",
  "id": "film_look",
  "clips": [
    {
      "start_time": 0,
      "duration": 8,
      "lut": "Teal and Orange.cube",
      "lut_input_gamma": 1.0,
      "keyframes": [
        { "time": 0, "lut_strength": 0 },
        { "time": 2, "lut_strength": 1 },
        { "time": 6, "lut_strength": 1 },
        { "time": 8, "lut_strength": 0 }
      ]
    }
  ]
}
```

> 上面这条：整幅画面从无到有套上 Teal and Orange（2 秒淡入），保持 4 秒后淡出——`lut_strength` 全程线性插值，写回 `0` 即 LUT 淡出。

### 层级混合（`blend_mode` / `blend_amount`）— 混合模式作用于调整层

PS「调整图层 + 图层混合模式」的运行时形态：把**调整层的输出**（调色操作栈全部步骤算出的颜色）与**基画面**（处理前的原图）按**混合模式**整体混合。**不是操作栈步骤**，而是层级语义（调整层 vs 底下的画面）——所以它排在全部步骤之后（第 20 步），看到的是「处理后的整幅画面」与「处理前的整幅画面」。

- **字段**：clip 级 `blend_mode`（string，缺省 `"normal"`）+ 关键帧 `blend_amount`（0 ~ 1，缺省 `1`，可随时间淡入淡出）。
- **口径（逐通道 RGB、均 0~1）**：`c = mix(基画面, blend(基画面, 调整层输出, 模式), blend_amount)`：

  | `blend_mode` | 公式（`a` = 基画面、`b` = 调整层输出） |
  |---|---|
  | `normal` | `b`（直替换 = 现状；不写 `blend_mode` 就是这个） |
  | `multiply` | `a·b`（相乘 → 压暗） |
  | `screen` | `1 - (1-a)·(1-b)`（反相相乘 → 提亮） |
  | `soft_light` | `b ≤ 0.5 ? a - (1-2b)·a·(1-a) : a + (2b-1)·(d(a)-a)`，`d(a) = a ≤ 0.25 ? ((16a-12)·a+4)·a : sqrt(a)`（柔光；按 W3C compositing-1：分支看调整层输出 `b`，`d` 曲线作用于基画面 `a`） |
  | `overlay` | `a ≤ 0.5 ? 2ab : 1 - 2(1-a)(1-b)`（叠加：基画面暗处走 multiply、亮处走 screen） |

- **`blend_amount`**：`1` = 调整层全量（缺省）、`0.5` = 一半（调整层半透明）、`0` = **调整层整体透明**（输出 = 基画面，画面逐位不变）。做「风格渐入 / 渐出」就把它打关键帧。
- **零差异**：不写 `blend_mode`（= `normal`）+ `blend_amount` 缺省 `1` → 混合步骤整段跳过，画面与不带混合功能时**逐字节一致**。
- **ADJUST 的 `blend_mode` 只写在 ADJUST 轨**：混合模式作用于调整层（= ADJUST 轨）；相机片段上写 `blend_mode` / `blend_amount` 不生效，会被 validator 直接拦下。fade 的 `blend_mode` 是颜色层自己的字段（§9），与本节的调整层混合互不替代。
- **只动 RGB**：`alpha` 逐位直通，透明度仍只在合成层由 `opacity` 调控。

```json
{
  "type": "ADJUST",
  "clips": [
    {
      "start_time": 0,
      "duration": 8,
      "blend_mode": "multiply",
      "keyframes": [
        { "time": 0, "saturation": 0.4, "blend_amount": 0 },
        { "time": 2, "saturation": 0.4, "blend_amount": 1 },
        { "time": 6, "saturation": 0.4, "blend_amount": 1 },
        { "time": 8, "saturation": 0.4, "blend_amount": 0 }
      ]
    }
  ]
}
```

> 上面这条：提饱和后的画面以 `multiply` 压在原画上（整体压暗、颜色更浓），2 秒渐入、保持 4 秒、2 秒渐出——`blend_amount` 全程线性插值，写回 `0` 即调整层整体淡出（画面回到原样）。

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
      "rgb_curve": [ [0, 0], [0.5, 0.8], [1, 1] ],
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

### 示例：每通道曲线（R 提亮 / G 压暗 / B 更亮 + 各自淡入淡出）

三条每通道曲线各管一个通道，三个强度关键帧各自动（`r_curve` 淡入、`g_curve` 恒定全量、`b_curve` 半量起）：

```json
{
  "type": "adjust",
  "id": "adjust_channel_curves",
  "clips": [
    {
      "start_time": 0,
      "duration": 8,
      "r_curve": [ [0, 0], [0.5, 0.65], [1, 1] ],
      "g_curve": [ [0, 0], [0.5, 0.35], [1, 1] ],
      "b_curve": [ [0, 0], [0.5, 0.8], [1, 1] ],
      "keyframes": [
        { "time": 0, "r_curve_strength": 0, "g_curve_strength": 1, "b_curve_strength": 0.5 },
        { "time": 2, "r_curve_strength": 1, "g_curve_strength": 0.5, "b_curve_strength": 1 },
        { "time": 4, "r_curve_strength": 0.5, "g_curve_strength": 0, "b_curve_strength": 0 },
        { "time": 6, "r_curve_strength": 1, "g_curve_strength": 1, "b_curve_strength": 1 }
      ]
    }
  ]
}
```

> 三条曲线各自独立：只写 `r_curve` 就只影响红通道（g / b 逐位不变）。
> 不写某个强度就是 `1`（对应曲线全量生效）；没有对应曲线的强度会被忽略。
> 与复合曲线可以同时用：复合曲线先算（整体风格化），每通道曲线后算（按通道微调）。

### 示例：六条 hue 曲线（按色相 / 饱和度 / 亮度分区调色）

「红色系往橙偏 + 只给红色系提饱和 + 暗部降饱和 + 高饱和压暗」——四条曲线各管一件事，两条强度做淡入淡出：

```json
{
  "type": "adjust",
  "id": "adjust_hue_curves",
  "clips": [
    {
      "start_time": 0,
      "duration": 8,
      "hv_h_curve": [ [0, 0.1], [0.5, 0.5], [1, 1] ],
      "hv_s_curve": [ [0, 1], [0.5, 0.5], [0.65, 1], [1, 1] ],
      "lv_s_curve": [ [0, 0.25], [0.5, 0.5], [1, 1] ],
      "sv_l_curve": [ [0, 0.5], [0.5, 0.5], [1, 0.35] ],
      "keyframes": [
        { "time": 0, "hv_h_strength": 0, "hv_s_strength": 0, "lv_s_strength": 0.5, "sv_l_strength": 0 },
        { "time": 2, "hv_h_strength": 1, "hv_s_strength": 1, "lv_s_strength": 1,   "sv_l_strength": 0.5 },
        { "time": 4, "hv_h_strength": 0.5, "hv_s_strength": 0, "lv_s_strength": 0, "sv_l_strength": 1 },
        { "time": 6, "hv_h_strength": 1, "hv_s_strength": 1, "lv_s_strength": 1,   "sv_l_strength": 1 }
      ]
    }
  ]
}
```

> 键取「进入 HSL 块时」的值：上面这段里即使同帧再写 `hue: 0.5`（整体转 180°），`hv_s_curve` 仍然是按**原本**的色相
> 决定给谁提饱和（不会跟着转）。只改颜色、不碰透明度（`alpha` 逐位直通）。

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

### 示例：四象限各自调色（相机片段调色 + master）

4 条 CAMERA 轨各占一格（`dest` 半屏）→ 调色直接写在各自相机片段的关键帧上（每条轨的画面独立调色）；再加 1 条 ADJUST 轨做整体风格化：

```json
{
  "timeline": {
    "tracks": [
      { "type": "CAMERA", "clips": [ { "start_time": 0, "duration": 10,
        "keyframes": [
          { "time": 0, "dest": { "x": 0, "y": 0, "w": 0.5, "h": 0.5 }, "position": { "dx": 0, "dy": 0, "dz": 0 }, "grayscale": 0 },
          { "time": 5, "dest": { "x": 0, "y": 0, "w": 0.5, "h": 0.5 }, "position": { "dx": 0, "dy": 0, "dz": 0 }, "grayscale": 1 } ] } ] },
      { "type": "CAMERA", "clips": [ { "start_time": 0, "duration": 10,
        "keyframes": [
          { "time": 0, "dest": { "x": 0.5, "y": 0, "w": 0.5, "h": 0.5 }, "position": { "dx": 0, "dy": 0, "dz": 0 }, "temperature": 0 },
          { "time": 5, "dest": { "x": 0.5, "y": 0, "w": 0.5, "h": 0.5 }, "position": { "dx": 0, "dy": 0, "dz": 0 }, "temperature": 0.6 } ] } ] },
      { "type": "ADJUST", "clips": [ { "start_time": 0, "duration": 10,
        "keyframes": [ { "time": 0, "saturation": 0 }, { "time": 5, "saturation": -0.5 } ] } ] }
    ]
  }
}
```

> 前两条 CAMERA 轨各自调色：第 1 格黑白化、第 2 格偏暖，只影响各自那一格的画面（在该 lane 渲染完成、合成之前生效）。最后一条 ADJUST 轨（master）作用于**合成后**的整屏画面，所以「第 1 格黑白 + 整体降饱和」会叠加（相机片段调色先算、master 后算）。
> 每个关键帧都要写上所需通道——缺字段按缺省 `0` 解，会在中间挖出折点（上面两格的关键帧各自都写了 `grayscale` / `temperature`）。

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
> 位置与控制点都写**相对**形式（位置相对位置点源，控制点相对段起点）——"以玩家触发点为圆心绕圆"这类运行时才知道坐标的场景可直接生成。整圈 = 三段 120° 拼圆（轨道级模板，后续步骤）。

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

## 13. 判定规则（rules）— 片段级条件与动作

**规则 = 挂在片段上的「条件 + 动作」**：片段执行期间持续判定，条件成立则执行动作（改播放 / 分支 / 发声 / 控制）。字段是 **clip 级 `rules` 数组**（与 `keyframes` 平级），**任意轨道**的片段都可挂（判定与动作都不限轨道）。语义等价代码里的 `if` / `while`：判定成立 → 做事。

### 13a. 两种形态（`if` / `until` 恰写一个）

```json
"rules": [
  { "if":   { "type": "location", "position": { "x": 130, "y": 64, "z": -170 }, "radius": 4 },
    "do":   [ { "type": "play_sound", "sound": "minecraft:ambient.cave" } ], "cooldown": 5 },
  { "until": { "type": "time", "at": 12.0 },
    "do":    [ { "type": "goto_clip", "track": "CAMERA", "at": 20 } ],
    "timeout": 20, "on_timeout": [ { "type": "exit" } ] }
]
```

| 形态 | 语义 | 判定节奏 |
|------|------|---------|
| `if`（持续规则） | 条件成立的那一刻触发 `do`；播放时钟**不冻结** | 每渲染帧；`mode: "enter"`（缺省）= 假→真边沿触发；`mode: "dwell"` + `dwell: 秒` = 持续成立满 N 秒才触发 |
| `until`（阻塞规则） | 条件假 → **挂起本实例（保持内容位置）**；条件成立（或 `timeout` 真实时钟先到）→ 解除挂起并执行 `do`（超时走 `on_timeout`） | 挂起期间照常判定（这正是 until 的语义） |

公共字段：`do`（必填，动作数组，至少一个）、`once`（缺省 `true` = 本片段内只触发一次，片段 loop 折返重置）、`cooldown`（缺省 `0` = 不静默，触发后 N 秒内不再触发，走播放时钟）、`mode` / `dwell`（`if` 专用）、`timeout`（缺省 `-1` = 无限等待，走真实时钟）/ `on_timeout`（`until` 专用）。`until` 下写 `once` / `cooldown` 会被校验拦下（阻塞规则触发即解除，无重复触发概念）。

**挂起（`until` / 动作 `pause` 生效期间）四条**：① 本实例**全部轨**一起停（含 EVENT 轨时间线，服务端一并冻结）；游戏世界、触发器、其它播放实例照常。② **暂停 = 保持内容位置**（`(片段, 片段内偏移秒)`）：后续关键帧不推进、片段不换，恢复从该位置续（不按墙钟补算、无时间账本）；暂停期间该位置随握手包上报服务端，服务端 EVENT 时间线按它推进。③ 本片段的规则**继续判定**（含同片段其它 `if` 规则），但**时间门（`time` 条件）与 dwell 驻留不走**（播放时钟停着）——释放 `pause` 的 `resume` 因此必须写在**触发 `pause` 的同一片段**里（跨片段 / 时间门 / dwell 门的 `resume` 不可达）：同片段既无 `until`、也无未被 `once` / 冷却门挡住的 `resume` 动作时，运行期**自动解除 + 限频告警**（校验器按「同片段无 `resume` 动作 / `until`」静态同口径报 issue）。④ 打断 / 跳过 / 强退不受挂起影响（照常生效）。

### 13b. 判定源（v1 八种 + 组合器）

`time`（相对片段本地时钟：`at` 到点或 `from`/`to` 区间，**挂起期间不走**）、`location`、`facing`、`biome`、`dimension`、`camera_pos`、`camera_facing`、`camera_fov`；组合器 `all_of` / `any`（`list` 元素 = `{type, conditions}`，嵌套 ≤ 8 层含顶层）。字段名与取值语义与 `meta.triggers[].conditions` 同源（同一份 schema）。**判定端 = 客户端逐实例**：各看各的本地玩家与相机（多人广播脚本 = 个人化运镜的自然语义）。事件类 / 前置条件类判定源留 v2。

### 13c. 动作集 v1（九种）

动作**只作用触发它的那个播放实例**（跨脚本作用走 v2 的跨脚本族）。同类「参数调整」动作在**同一片段内再次触发 = 覆盖**（先回收旧的那一份），不无限叠加。

| 动作 | 参数 | 语义 |
|------|------|------|
| `camera_set` | `channels`（必填，通道名数组）+ `mode?`（`add` / `multiply` / `replace`，缺省逐通道：偏移类 add、缩放类 multiply）+ `priority?`（应用序，缺省 0）+ 各通道值 | 触发即向**覆盖链**注册一条 Modifier，**持续叠加**到片段结束（`position` 整通道一位，写 `{x, y, z}`；`yaw` / `pitch` / `roll` / `fov` / `zoom` 写数字）。`blend` 过渡 / `look_at` / `follow` 偏移叠加留 v2 |
| `overlay_set` | `layer_type`（v1 只支持 `"fade"`）+ `color?`（`#RRGGBB`，缺省黑）+ `opacity?`（0~1，缺省 1）+ `z_index?`（缺省 10） | 建一层**临时覆盖层**（归属本实例），持续到片段结束移除。`image` / `subtitle` 留 v2 |
| `adjust_set` | 基础校色 15 个标量通道（`exposure` / `contrast` / `highlights` / `shadows` / `whites` / `blacks` / `hue` / `saturation` / `vibrance` / `lightness` / `temperature` / `tint` / `red` / `green` / `blue`；参数名与 ADJUST 轨关键帧字段同名） | 建一层**master 规则调整层**（叠加在该实例 ADJUST 轨输出之上；未写通道不动），持续到片段结束清除 |
| `goto_clip` | `track`（轨道类型名，如 `CAMERA`）+ `at`（目标已播放时间秒） | 时间寻址跳转：目标时间须落在该轨某个片段的窗内；越界 / 该轨无片段 → 限频日志 + 不动 |
| `pause` / `resume` | 无 | **执行状态暂停**：`pause` 保持本实例内容位置（后续关键帧不推进），`resume` 从该位置续。释放者须在**触发 `pause` 的同一片段**（`resume` 动作 / `until`）——跨片段、时间门 / dwell 门的 `resume` 不可达；同片段既无 `until` 也无未被 `once` / 冷却门挡住的 `resume` 时运行期**自动解除 + 限频告警**（校验器静态同口径 issue）。脚本重建 / 停播强制解除，不留冻结实例 |
| `exit` | 无 | **声明式退出**：跳过 `hold_at_end` 拒绝（声明式优先于末尾驻留），完成原因 = `FINISHED`（归 NATURAL_END 映射，退出原因五值不扩） |
| `play_sound` | `sound`（必填，音效 id）+ `volume?` / `pitch?`（缺省 1） | 本实例一次性声音（相对听者、无衰减）；随实例停止清理，挂起期间停 |
| `emit_command` | `command`（必填，不含前导 `/`；多条用 `&&` 分隔） | 交**服务端**执行，口径与 EVENT 轨一致（`&&` 拆分 + 成功结果回执）；**仅该脚本当前播放实例的观看者**会执行（防命令注入） |
| `hold` | — | **未实现（销项）**：无限等待用 `until` 无 timeout 表达（`{"until": {<条件>}, "do": [...]}`，缺省 `timeout` = 无限等待）。写了 `hold` 会被校验拦下 |

### 13d. 退出模型里的位置（边界三条）

1. **① 族（人为干预）不受规则影响**：强退 / 跳过 / 投票跳过仍按 `meta` 的 `skippable` / `skipVoteRatio` / `interruptible` 门控。
2. **② 族（内容播完）**：**实际执行路径上已无剩余可执行内容** → 自然结束（`hold_at_end` 时驻留）。判据 = 每实例的**执行事实**——每个片段「待进入 / 执行中 / 已消费完」的消费状态（有限循环片段要求展开圈数计满，无限片段永不消费完），剩余内容计数归零即播完；`goto_clip` 分支跳转 = 路径变更，跳转后原路径剩余内容不再计入、跳转目标之后的内容计入。**按执行内容判定、不按墙钟**（不拿播放时钟与片段窗口末做数值比较）；`total_duration` 只是统计 / 信息位，不参与结束判定。挂起中（规则 `until` / 动作 `pause`）不判播完。无限内容（`loop_count: -1` 片段 / `duration < 0` / `macro_loop` 无限圈）永不「内容播完」，只能 ①③ 族退出。
3. **③ 族（声明式退出）= 规则动作 `exit`**：走 `clip.rules`，不新开 `meta` 字段；映射归 NATURAL_END（完成原因五值不扩）。`hold_at_end` 脚本上规则 `exit` 照常生效。

### 13e. 回退语义（失败即回主支）

动作执行失败（参数非法 / 目标越界 / 目标片段缺失 / 执行异常）**一律不传播、不中断播放**：限频日志（`logs/immersive_cinematics/script-errors.log`）+ 继续按时间线走——「动作失败 = 什么都没发生」。回退目标恒为**主支**（分支无栈、不做嵌套恢复）：`goto_clip` 成功跳转后再失败也回主支当前状态，时间线继续。

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

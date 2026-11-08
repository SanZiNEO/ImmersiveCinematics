# 脚本格式参考（objects 格式）

ImmersiveCinematics 的脚本是一个 JSON 文件，用一条时间线描述镜头、声音、事件和屏幕效果。本文档描述当前的 `objects` 格式。旧写法（`meta.version`、`timeline.total_duration`、片段级 `keyframes` / `curve` / `rules` / `interpolation`）已全部移除，清单见第 7 节。

## 1. 概览

脚本是一个 JSON 对象，顶层只有六类键：

| 键 | 必填 | 作用 |
|---|---|---|
| `meta` | 是 | 身份、触发条件、行为开关、屏幕层策略 |
| `timeline` | 是 | 轨道与片段；所有时间轴内容都在这里 |
| `clips` / `rules` / `rule_bindings` / `paths` | 否 | 脚本级集合，见第 5 节 |

三条硬约定：

1. **没有版本号**：`meta.version` 不存在，格式由字段形状判定。
2. **没有总时长**：`timeline.total_duration` 不存在；末端取所有片段 `start_time + duration` 的最大值，出现无限片段或无限宏观循环 → 内容无限。
3. **显示范围由片段派生**：片段窗口外不产生任何效果，轨道没有独立开关时间。

## 2. meta

`meta` 必填，承载脚本身份、触发条件、行为开关与屏幕层策略。

### 2.1 身份与环境

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `id` / `name` | string | `untitled` / `Untitled` | 必填；脚本 id 与显示名 |
| `author` / `description` | string | `""` | 可选 |
| `dimension` | string | `""` | 维度过滤，空 = 不限 |
| `base_resolution` | object | `null` | 编辑基准分辨率 `{w, h}`；`null` = 1920×1080 |
| `listener` | enum | `player` | 声音听者：`player` / `camera` |
| `priority` | int | `0` | 脚本优先级 |

### 2.2 行为开关

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `block_keyboard` / `block_mouse` | bool | `true` | 屏蔽键盘 / 鼠标 |
| `block_mob_ai` | bool | `false` | 冻结生物 AI |
| `pause_when_game_paused` | bool | `true` | 游戏暂停时脚本暂停 |
| `interruptible` | bool | `true` | 同脚本再次触发时是否替换当前实例 |
| `play_mode` | enum | `parallel` | 跨脚本关系：`parallel` / `exclusive` / `interruptible` |
| `skippable` / `hold_at_end` | bool | `true` / `false` | 允许跳过 / 结束后保持最后一帧 |
| `macro_loop` | bool | `false` | 宏观循环开关 |
| `macro_loop_count` | int | `-1` | 循环次数；负数 = 无限 |
| `macro_loop_mode` | enum | `repeat` | `repeat` / `pingpong` |
| `skip_vote_ratio` | int | `null` | 整数百分比 10~100；非法值解析期报错 |
| `trigger_poll_interval` | int | `null` | 轮询类触发器的脚本级缺省间隔（tick，1~600）；非法值告警并按未写处理 |

画面层没有独立的 `hide_*` / `suppress_*` 开关，全部走 §2.3 的 `screen_layers`。

### 2.3 屏幕层

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `screen_layers` | object | `null` | 层名 → bool，**值 = 显示**：`true` 显示、`false` 隐藏 |

`screen_layers` 是画面层的唯一入口。**接管是唯一模式且默认生效**：只要任一层判隐藏，就取消整帧 HUD 绘制并只重画白名单内的层；未声明的层不显示（allowlist 语义）。

层名键域三类：15 个规范层名（`hud.hotbar` / `hud.crosshair` / `hud.health` / `hud.experience` / `hud.bossbar` / `hud.chat` / `hud.scoreboard` / `hud.action_bar` / `hud.title` / `hud.subtitle` / `hud.skip_prompt` / `viewmodel` / `view_bobbing` / `screen_effects` / `player_model`）、第三方注册的分类名、未注册 overlay 的完整资源 ID。

**Fabric 与 Forge 行为一致**：

- 两端都**整帧接管**：取消整帧 HUD / GUI 绘制，再按 `screen_layers` 白名单重画。
- 第三方 HUD 在 Fabric 上要参与白名单，必须注册进层表（`FabricHudLayerRegistry.register(layerId, category, renderer)`：三参，`renderer` 接收 `GuiGraphics` 与 `partialTick`；或跨加载器门面 `HudLayerRegistry.register`），并在绘制前查询 `FabricHudVisibility.isHidden(layerId)`。`renderer` 只在接管重画的末尾调用。
- 原生 `HudRenderCallback` 的绘制在接管时随整帧取消而不显示——不注册、也不查询可见性的第三方 HUD 不会被重画。

### 2.4 triggers[]

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 是 | 触发器 id，供规则事实与诊断引用 |
| `type` | string | 是 | 触发类型（如 `biome`） |
| `repeatable` | bool | 否 | 是否可重复触发 |
| `delay` | number | 否 | 触发延迟（秒） |
| `conditions` | object | 否 | 与 `type` 对应的条件体 |
| `continuity` | object | 否 | 连续语义；省略 = 全部关闭 |

`continuity` 三个子字段都可省：`sweep`（bool，缺省 `false`，只对 `location` / `structure` / `facing` 有几何语义）、`dwell`（int tick，0~1200，只对轮询类生效）、`window`（int tick，0~1200，只对 `all_of` / `any` 生效）。

真实夹具（`test_objects_camera_basic.json`）：

```json
"triggers": [{ "id": "trg_login", "type": "biome", "repeatable": false,
  "delay": 0, "conditions": { "biome": "*" } }]
```

## 3. timeline 与片段

`timeline` 只有 `tracks[]`；每条轨道是 `{ "type": "CAMERA", "clips": [ … ] }`，`type` 决定 `objects` 的字段集（第 4 节）。

### 3.1 片段键

片段是**白名单对象**：只认下列 8 个键，其余键一律进 `objects`（写在片段上会报错）。

| 键 | 必填 | 说明 |
|---|---|---|
| `start_time` / `duration` | 是 | 起点（秒，>= 0）/ 时长（正数 = 有限，负数 = 无限单次） |
| `objects` | 是 | 采样属性，见 3.2 |
| `loop` / `loop_count` / `loop_mode` | 否 | 片段循环；`loop_mode` = `repeat` / `pingpong` |
| `id` | 否 | 被规则 / 路径 / 分支 / 出口引用时必填 |
| `exit` | 否 | 出口策略，见第 6 节 |

### 3.2 objects 的两种形态

每个键是**完整对象路径**（复合值先展开，见 3.4），值是二者之一：

| 形态 | JSON | 语义 |
|---|---|---|
| 常量 | `{"value": 70}` | 片段窗口内保持不变 |
| 关键帧 | `{"keyframes": [{"time": 0, "value": 70}, {"time": 6, "value": 90}]}` | 按时间采样 |

- `time` 是**片段局部秒**，从 0 开始；同一对象的 `time` **必须严格递增**，相等或倒序 → 解析期报错。
- 关键帧可超出 `[0, duration]`：**保留但忽略**；`keyframes` 不能为空数组。

### 3.3 插值

插值不写在脚本里，由字段元数据决定：

| interp | 适用 | 行为 |
|---|---|---|
| `LINEAR` | 标量 | 线性插值 |
| `ANGLE` | 角度 | 沿最短弧插值 |
| `GEOMETRIC` | 缩放类 | 等比插值 |
| `STEP` | 枚举 / 布尔 / 引用 | 阶跃，取左侧关键帧的值 |

`interp = NONE` 的项是 `VALUE` 模式，只能写 `value`，写 `keyframes` 报错。

### 3.4 复合值展开

`objects` 里**不允许**写复合名本身，必须写成叶子路径：

| 复合写法 | 展开为 |
|---|---|
| `position` | `position.x` / `position.y` / `position.z` |
| `dest`、`source` | `.x` / `.y` / `.w` / `.h` |
| 关键帧内的 `color`（0~1 映射） | `color.r` / `color.g` / `color.b` |
| `look_at_part` | `.x` / `.y` / `.z` |
| 任意 `*_offset` | `.x` / `.y` / `.z` + `.space` |

`color` 有两套写法：`OVERLAY` 的 `color` 是字符串常量（`"#336699"`），关键帧形式必须拆成 `color.r/g/b`（0~1 浮点）。

## 4. 轨道字段表

`objects` 里的每个键都是下表的 `path`。表头：`mode`（`ANIMATED` = 可写 `value` 或 `keyframes`；`VALUE` = 只能 `value`；`EVENT` = 事件记录）、`type`（`REF` = 选择器 / 实体引用，`STRUCT` = 嵌套对象）、`interp`（插值，见 3.3）、`default`（`—` = 无默认）、`enum`（合法值）、`requires`（依赖，如 `orient=tangent`）。

轨道类型即 `type` 取值：`CAMERA` / `LETTERBOX` / `AUDIO` / `EVENT` / `MOD_EVENT` / `OVERLAY` / `ADJUST`。

### 4.1 CAMERA

`objects` 可用字段（未列出即报错）：

| path | mode | type | interp | default | enum | requires |
|---|---|---|---|---|---|---|
| `position.x` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `position.y` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `position.z` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `position_mode` | ANIMATED | ENUM | STEP | relative | relative / absolute |  |
| `position_space` | ANIMATED | ENUM | STEP | world | world / base |  |
| `position_origin` | ANIMATED | REF | STEP |  |  |  |
| `position_origin_x` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `position_origin_y` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `position_origin_z` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `position_origin_freeze` | ANIMATED | BOOL | STEP | false |  |  |
| `facing_source` | ANIMATED | ENUM | STEP | auto | auto / world / entity / line |  |
| `facing_target` | ANIMATED | REF | STEP |  |  |  |
| `facing_target_x` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `facing_target_y` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `facing_target_z` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `facing_target_offset.x` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `facing_target_offset.y` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `facing_target_offset.z` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `facing_target_offset.space` | ANIMATED | ENUM | STEP | world | world / base |  |
| `facing_target_freeze` | ANIMATED | BOOL | STEP | false |  |  |
| `facing_entity` | ANIMATED | REF | STEP |  |  |  |
| `yaw` | ANIMATED | FLOAT | ANGLE | 0.0 |  |  |
| `pitch` | ANIMATED | FLOAT | ANGLE | 0.0 |  |  |
| `roll` | ANIMATED | FLOAT | ANGLE | 0.0 |  |  |
| `yaw_lock` | ANIMATED | NULLABLE_FLOAT | ANGLE | — |  |  |
| `pitch_lock` | ANIMATED | NULLABLE_FLOAT | ANGLE | — |  |  |
| `yaw_base` | ANIMATED | ENUM | STEP | world | world / entity / line |  |
| `pitch_base` | ANIMATED | ENUM | STEP | world | world / entity / line |  |
| `yaw_base_selector` | ANIMATED | REF | STEP | @p |  |  |
| `yaw_base_from` | ANIMATED | REF | STEP |  |  |  |
| `yaw_base_from_x` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `yaw_base_from_y` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `yaw_base_from_z` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `yaw_base_from_offset.x` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `yaw_base_from_offset.y` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `yaw_base_from_offset.z` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `yaw_base_from_offset.space` | ANIMATED | ENUM | STEP | world | world / base |  |
| `yaw_base_from_freeze` | ANIMATED | BOOL | STEP | false |  |  |
| `yaw_base_to` | ANIMATED | REF | STEP |  |  |  |
| `yaw_base_to_x` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `yaw_base_to_y` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `yaw_base_to_z` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `yaw_base_to_offset.x` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `yaw_base_to_offset.y` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `yaw_base_to_offset.z` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `yaw_base_to_offset.space` | ANIMATED | ENUM | STEP | world | world / base |  |
| `yaw_base_to_freeze` | ANIMATED | BOOL | STEP | false |  |  |
| `look_at` | ANIMATED | ENUM | STEP | none | none / player / coordinate / entity / block |  |
| `look_at_selector` | ANIMATED | REF | STEP | @p |  |  |
| `look_at_part.x` | ANIMATED | FLOAT | LINEAR | 50.0 |  |  |
| `look_at_part.y` | ANIMATED | FLOAT | LINEAR | 50.0 |  |  |
| `look_at_part.z` | ANIMATED | FLOAT | LINEAR | 50.0 |  |  |
| `look_at_target_x` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `look_at_target_y` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `look_at_target_z` | ANIMATED | FLOAT | LINEAR | — |  |  |
| `look_at_target_structure` | ANIMATED | STRING | STEP |  |  |  |
| `look_at_target_block` | ANIMATED | STRING | STEP |  |  |  |
| `look_at_offset.x` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `look_at_offset.y` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `look_at_offset.z` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `look_at_offset.space` | ANIMATED | ENUM | STEP | world | world / base |  |
| `look_at_freeze` | ANIMATED | BOOL | STEP | false |  |  |
| `orient` | VALUE | ENUM | NONE | manual | manual / tangent |  |
| `yaw_offset` | ANIMATED | FLOAT | ANGLE | 0.0 |  | orient=tangent |
| `pitch_offset` | ANIMATED | FLOAT | ANGLE | 0.0 |  | orient=tangent |
| `fov` | ANIMATED | FLOAT | LINEAR | 70.0 |  |  |
| `zoom` | ANIMATED | FLOAT | GEOMETRIC | 1.0 |  |  |
| `opacity` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `dest.x` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `dest.y` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `dest.w` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `dest.h` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `source.x` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `source.y` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `source.w` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `source.h` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `mask_x` | ANIMATED | FLOAT | LINEAR | 0.5 |  |  |
| `mask_y` | ANIMATED | FLOAT | LINEAR | 0.5 |  |  |
| `mask_width` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `mask_height` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `mask_radius` | ANIMATED | FLOAT | LINEAR | 0.5 |  |  |
| `mask_shape` | VALUE | ENUM | NONE | full | full / rect / circle |  |
| `dimension` | VALUE | STRING | NONE |  |  |  |
| `exposure` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `contrast` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `highlights` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `shadows` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `whites` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `blacks` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `hue` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `saturation` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `vibrance` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `lightness` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `temperature` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `tint` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `red` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `green` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `blue` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_rr` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_rg` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_rb` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_gr` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_gg` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_gb` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_br` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_bg` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_bb` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `curve_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `r_curve_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `g_curve_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `b_curve_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `lift_r` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `lift_g` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `lift_b` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `gamma_r` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `gamma_g` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `gamma_b` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `gain_r` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `gain_g` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `gain_b` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `hv_h_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `hv_s_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `hv_l_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `lv_s_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `sv_s_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `sv_l_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `hue_red` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `sat_red` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `hue_yellow` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `sat_yellow` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `hue_green` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `sat_green` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `hue_cyan` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `sat_cyan` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `hue_blue` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `sat_blue` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `hue_magenta` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `sat_magenta` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `grayscale` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `invert` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `rgb_curve` | VALUE | STRUCT | NONE | — |  |  |
| `r_curve` | VALUE | STRUCT | NONE | — |  |  |
| `g_curve` | VALUE | STRUCT | NONE | — |  |  |
| `b_curve` | VALUE | STRUCT | NONE | — |  |  |
| `hv_h_curve` | VALUE | STRUCT | NONE | — |  |  |
| `hv_s_curve` | VALUE | STRUCT | NONE | — |  |  |
| `hv_l_curve` | VALUE | STRUCT | NONE | — |  |  |
| `lv_s_curve` | VALUE | STRUCT | NONE | — |  |  |
| `sv_s_curve` | VALUE | STRUCT | NONE | — |  |  |
| `sv_l_curve` | VALUE | STRUCT | NONE | — |  |  |
| `motion_position` | VALUE | STRUCT | NONE | — |  |  |
| `motion_yaw` | VALUE | STRUCT | NONE | — |  |  |
| `motion_pitch` | VALUE | STRUCT | NONE | — |  |  |
| `motion_roll` | VALUE | STRUCT | NONE | — |  |  |
| `motion_fov` | VALUE | STRUCT | NONE | — |  |  |
| `motion_zoom` | VALUE | STRUCT | NONE | — |  |  |
| `cam_breath_enabled` | VALUE | BOOL | NONE | false |  |  |
| `cam_breath_intensity` | VALUE | FLOAT | NONE | 0.05 |  |  |
| `cam_breath_seed` | VALUE | INT | NONE | 0 |  |  |
| `cam_breath_type` | VALUE | ENUM | NONE | perlin | perlin / perlin_axis / sine / trauma |  |
| `cam_breath_speed` | VALUE | FLOAT | NONE | 1.0 |  |  |
| `cam_breath_trauma` | VALUE | FLOAT | NONE | 1.0 |  |  |
| `cam_breath_decay` | VALUE | FLOAT | NONE | 0.5 |  |  |
| `transition` | VALUE | ENUM | NONE | cut | cut / morph |  |
| `transition_duration` | VALUE | FLOAT | NONE | 0.5 |  |  |
| `transition_curve` | VALUE | STRUCT | NONE | — |  |  |
| `transition_params` | VALUE | STRUCT | NONE | — |  |  |
| `transition_interrupt` | VALUE | ENUM | NONE | blend | blend / snap |  |
| `modifier` | VALUE | STRUCT | NONE | — |  |  |
| `suppress_modifiers` | VALUE | BOOL | NONE | false |  |  |
| `selector_refresh` | ANIMATED | FLOAT | STEP | 1.0 |  |  |
| `selector_switch_while_alive` | ANIMATED | BOOL | STEP | true |  |  |
| `selector_switch_interval` | ANIMATED | FLOAT | STEP | 1.0 |  |  |
| `selector_switch_smooth` | ANIMATED | FLOAT | STEP | 0.0 |  |  |
| `selector_anchor` | ANIMATED | ENUM | STEP | camera | camera / player / target / origin |  |
| `selector_pick` | ANIMATED | ENUM | STEP | first | first / nearest / alive |  |
| `selector_refresh_position_origin` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_switch_while_alive_position_origin` | ANIMATED | BOOL | STEP | — |  |  |
| `selector_switch_interval_position_origin` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_switch_smooth_position_origin` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_anchor_position_origin` | ANIMATED | ENUM | STEP | — | camera / player / target / origin |  |
| `selector_pick_position_origin` | ANIMATED | ENUM | STEP | — | first / nearest / alive |  |
| `selector_refresh_look_at` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_switch_while_alive_look_at` | ANIMATED | BOOL | STEP | — |  |  |
| `selector_switch_interval_look_at` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_switch_smooth_look_at` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_anchor_look_at` | ANIMATED | ENUM | STEP | — | camera / player / target / origin |  |
| `selector_pick_look_at` | ANIMATED | ENUM | STEP | — | first / nearest / alive |  |
| `selector_refresh_yaw_base` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_switch_while_alive_yaw_base` | ANIMATED | BOOL | STEP | — |  |  |
| `selector_switch_interval_yaw_base` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_switch_smooth_yaw_base` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_anchor_yaw_base` | ANIMATED | ENUM | STEP | — | camera / player / target / origin |  |
| `selector_pick_yaw_base` | ANIMATED | ENUM | STEP | — | first / nearest / alive |  |
| `selector_refresh_yaw_base_from` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_switch_while_alive_yaw_base_from` | ANIMATED | BOOL | STEP | — |  |  |
| `selector_switch_interval_yaw_base_from` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_switch_smooth_yaw_base_from` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_anchor_yaw_base_from` | ANIMATED | ENUM | STEP | — | camera / player / target / origin |  |
| `selector_pick_yaw_base_from` | ANIMATED | ENUM | STEP | — | first / nearest / alive |  |
| `selector_refresh_yaw_base_to` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_switch_while_alive_yaw_base_to` | ANIMATED | BOOL | STEP | — |  |  |
| `selector_switch_interval_yaw_base_to` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_switch_smooth_yaw_base_to` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_anchor_yaw_base_to` | ANIMATED | ENUM | STEP | — | camera / player / target / origin |  |
| `selector_pick_yaw_base_to` | ANIMATED | ENUM | STEP | — | first / nearest / alive |  |
| `selector_refresh_facing_target` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_switch_while_alive_facing_target` | ANIMATED | BOOL | STEP | — |  |  |
| `selector_switch_interval_facing_target` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_switch_smooth_facing_target` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_anchor_facing_target` | ANIMATED | ENUM | STEP | — | camera / player / target / origin |  |
| `selector_pick_facing_target` | ANIMATED | ENUM | STEP | — | first / nearest / alive |  |
| `selector_refresh_facing_entity` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_switch_while_alive_facing_entity` | ANIMATED | BOOL | STEP | — |  |  |
| `selector_switch_interval_facing_entity` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_switch_smooth_facing_entity` | ANIMATED | FLOAT | STEP | — |  |  |
| `selector_anchor_facing_entity` | ANIMATED | ENUM | STEP | — | camera / player / target / origin |  |
| `selector_pick_facing_entity` | ANIMATED | ENUM | STEP | — | first / nearest / alive |  |

`objects` 真实夹具（`test_objects_camera_basic.json`）：

```json
{ "type": "CAMERA", "clips": [{ "start_time": 0, "duration": 6, "objects": {
  "position_mode": { "value": "absolute" },
  "position.x": { "keyframes": [{ "time": 0, "value": 0 }, { "time": 6, "value": 10 }] },
  "yaw": { "keyframes": [{ "time": 0, "value": 0 }, { "time": 6, "value": 90 }] },
  "fov": { "keyframes": [{ "time": 0, "value": 70 }, { "time": 6, "value": 90 }] },
  "mask_shape": { "value": "rect" } } }] }
```

### 4.2 LETTERBOX

`objects` 可用字段（未列出即报错）：

| path | mode | type | interp | default | enum | requires |
|---|---|---|---|---|---|---|
| `aspect_ratio` | ANIMATED | FLOAT | LINEAR | 2.35 |  |  |
| `opacity` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |

`objects` 真实夹具（`test_objects_letterbox.json`）：

```json
{ "type": "LETTERBOX", "clips": [{ "start_time": 0, "duration": 5, "objects": {
  "aspect_ratio": { "keyframes": [{ "time": 0, "value": 2.35 }, { "time": 5, "value": 1.85 }] },
  "opacity": { "keyframes": [{ "time": 0, "value": 1 }, { "time": 5, "value": 0.8 }] } } }] }
```

### 4.3 AUDIO

`objects` 可用字段（未列出即报错）：

| path | mode | type | interp | default | enum | requires |
|---|---|---|---|---|---|---|
| `volume` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `x` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `y` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `z` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `sound` | VALUE | STRING | NONE | — |  |  |
| `source` | VALUE | ENUM | NONE | file | file / minecraft |  |
| `pitch` | VALUE | FLOAT | NONE | 1.0 |  |  |
| `file_loop` | VALUE | BOOL | NONE | false |  |  |
| `fade_in` | VALUE | FLOAT | NONE | 0.0 |  |  |
| `fade_out` | VALUE | FLOAT | NONE | 0.0 |  |  |
| `attenuation` | VALUE | ENUM | NONE | linear | none / linear |  |
| `position_mode` | VALUE | ENUM | NONE | relative | relative / absolute |  |
| `category` | VALUE | ENUM | NONE | music | music / ambient |  |

`objects` 真实夹具（`test_objects_audio.json`）：

```json
{ "type": "AUDIO", "clips": [{ "start_time": 0, "duration": 4, "objects": {
  "sound": { "value": "second_waltz.ogg" }, "source": { "value": "file" },
  "file_loop": { "value": false }, "position_mode": { "value": "relative" },
  "attenuation": { "value": "linear" }, "category": { "value": "ambient" },
  "fade_in": { "value": 0.5 },
  "volume": { "keyframes": [{ "time": 0, "value": 0 }, { "time": 4, "value": 1 }] },
  "x": { "keyframes": [{ "time": 0, "value": 0 }, { "time": 4, "value": 30 }] } } }] }
```

### 4.4 EVENT

`objects` 可用字段（未列出即报错）：

| path | mode | type | interp | default | enum | requires |
|---|---|---|---|---|---|---|
| `event` | EVENT | EVENT_RECORD | NONE | — |  |  |
| `event_type` | VALUE | STRING | NONE | command |  |  |

`objects` 真实夹具（`test_objects_event.json`）：

```json
{ "type": "EVENT", "clips": [{ "start_time": 0, "duration": 4, "objects": {
  "event_type": { "value": "command" },
  "event": { "keyframes": [
    { "time": 1, "value": { "command": "/say t=1", "event_type": "command" } },
    { "time": 2, "value": { "command": "/say t=2", "event_type": "command",
                            "position": { "x": 100, "z": -200 } } } ] } } }] }
```

### 4.5 MOD_EVENT

`objects` 可用字段（未列出即报错）：

| path | mode | type | interp | default | enum | requires |
|---|---|---|---|---|---|---|
| `mod_event` | EVENT | EVENT_RECORD | NONE | — |  |  |
| `event_type` | VALUE | STRING | NONE | mod_event |  |  |
| `data` | VALUE | STRUCT | NONE | — |  |  |

`objects` 真实夹具（`test_objects_event.json`）：

```json
{ "type": "MOD_EVENT", "clips": [{ "start_time": 0, "duration": 4, "objects": {
  "event_type": { "value": "mod_event" },
  "mod_event": { "keyframes": [{ "time": 1, "value": {
    "event_type": "mymod:example", "data": { "radius": 8, "mode": "pulse" } } }] } } }] }
```

### 4.6 OVERLAY

`objects` 可用字段（未列出即报错）：

| path | mode | type | interp | default | enum | requires |
|---|---|---|---|---|---|---|
| `x` | ANIMATED | FLOAT | LINEAR | 0.5 |  |  |
| `y` | ANIMATED | FLOAT | LINEAR | 0.5 |  |  |
| `anchor_x` | ANIMATED | FLOAT | LINEAR | 0.5 |  |  |
| `anchor_y` | ANIMATED | FLOAT | LINEAR | 0.5 |  |  |
| `scale_x` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `scale_y` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `source.x` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `source.y` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `source.w` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `source.h` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `opacity` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `font_scale` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `color.r` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `color.g` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `color.b` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mask_x` | ANIMATED | FLOAT | LINEAR | 0.5 |  |  |
| `mask_y` | ANIMATED | FLOAT | LINEAR | 0.5 |  |  |
| `mask_width` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `mask_height` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `mask_radius` | ANIMATED | FLOAT | LINEAR | 0.5 |  |  |
| `gradient_angle` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `gradient_center_x` | ANIMATED | FLOAT | LINEAR | 0.5 |  |  |
| `gradient_center_y` | ANIMATED | FLOAT | LINEAR | 0.5 |  |  |
| `gradient_radius` | ANIMATED | FLOAT | LINEAR | 0.5 |  |  |
| `layer_type` | VALUE | ENUM | NONE | fade | fade / image / subtitle |  |
| `color` | VALUE | STRING | NONE | #000000 |  |  |
| `gradient` | VALUE | STRUCT | NONE | — |  |  |
| `mask_shape` | VALUE | ENUM | NONE | full | full / rect / circle |  |
| `blend_mode` | VALUE | ENUM | NONE | normal | normal / multiply / screen / soft_light / overlay |  |
| `path` | VALUE | STRING | NONE |  |  |  |
| `text` | VALUE | STRING | NONE |  |  |  |
| `z_index` | VALUE | INT | NONE | 10 |  |  |

`objects` 真实夹具（`test_objects_overlay.json`，节选 fade 与 subtitle 两条轨）：

```json
{ "type": "OVERLAY", "clips": [
  { "start_time": 0, "duration": 6, "objects": {
    "layer_type": { "value": "fade" }, "color": { "value": "#336699" },
    "color.r": { "keyframes": [{ "time": 0, "value": 0.2 }, { "time": 6, "value": 1 }] },
    "mask_shape": { "value": "circle" },
    "mask_radius": { "keyframes": [{ "time": 0, "value": 0.2 }, { "time": 6, "value": 0.45 }] },
    "gradient": { "value": { "type": "linear", "stops": [
      { "offset": 0, "color": "#336699" }, { "offset": 1, "color": "#000000" }] } },
    "opacity": { "keyframes": [{ "time": 0, "value": 0 }, { "time": 6, "value": 1 }] } } },
  { "start_time": 0, "duration": 6, "objects": {
    "layer_type": { "value": "subtitle" }, "text": { "value": "测试字幕" },
    "font_scale": { "keyframes": [{ "time": 0, "value": 1 }, { "time": 6, "value": 1.4 }] },
    "z_index": { "value": 100 } } }] }
```

### 4.7 ADJUST

`objects` 可用字段（未列出即报错）：

| path | mode | type | interp | default | enum | requires |
|---|---|---|---|---|---|---|
| `exposure` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `contrast` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `highlights` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `shadows` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `whites` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `blacks` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `hue` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `saturation` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `vibrance` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `lightness` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `temperature` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `tint` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `red` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `green` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `blue` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_rr` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_rg` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_rb` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_gr` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_gg` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_gb` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_br` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_bg` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `mix_bb` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `curve_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `r_curve_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `g_curve_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `b_curve_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `lift_r` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `lift_g` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `lift_b` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `gamma_r` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `gamma_g` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `gamma_b` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `gain_r` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `gain_g` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `gain_b` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `lut_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `hv_h_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `hv_s_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `hv_l_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `lv_s_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `sv_s_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `sv_l_strength` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `hue_red` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `sat_red` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `hue_yellow` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `sat_yellow` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `hue_green` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `sat_green` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `hue_cyan` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `sat_cyan` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `hue_blue` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `sat_blue` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `hue_magenta` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `sat_magenta` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `grayscale` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `invert` | ANIMATED | FLOAT | LINEAR | 0.0 |  |  |
| `blend_amount` | ANIMATED | FLOAT | LINEAR | 1.0 |  |  |
| `rgb_curve` | VALUE | STRUCT | NONE | — |  |  |
| `r_curve` | VALUE | STRUCT | NONE | — |  |  |
| `g_curve` | VALUE | STRUCT | NONE | — |  |  |
| `b_curve` | VALUE | STRUCT | NONE | — |  |  |
| `hv_h_curve` | VALUE | STRUCT | NONE | — |  |  |
| `hv_s_curve` | VALUE | STRUCT | NONE | — |  |  |
| `hv_l_curve` | VALUE | STRUCT | NONE | — |  |  |
| `lv_s_curve` | VALUE | STRUCT | NONE | — |  |  |
| `sv_s_curve` | VALUE | STRUCT | NONE | — |  |  |
| `sv_l_curve` | VALUE | STRUCT | NONE | — |  |  |
| `lut` | VALUE | STRING | NONE | — |  |  |
| `lut_input_gamma` | VALUE | FLOAT | NONE | 1.0 |  |  |
| `blend_mode` | VALUE | ENUM | NONE | normal | normal / multiply / screen / soft_light / overlay |  |

`objects` 真实夹具（`test_objects_adjust.json`）：

```json
{ "type": "ADJUST", "clips": [{ "start_time": 0, "duration": 5, "objects": {
  "exposure": { "keyframes": [{ "time": 0, "value": 0 }, { "time": 5, "value": 0.5 }] },
  "contrast": { "keyframes": [{ "time": 0, "value": 0 }, { "time": 5, "value": 0.3 }] },
  "rgb_curve": { "value": [[0, 0], [0.5, 0.65], [1, 1]] },
  "curve_strength": { "keyframes": [{ "time": 0, "value": 0 }, { "time": 5, "value": 1 }] },
  "blend_mode": { "value": "normal" },
  "blend_amount": { "keyframes": [{ "time": 0, "value": 1 }, { "time": 5, "value": 0.7 }] } } }] }
```

## 5. 脚本级集合

四个集合都可选；出现时按各自的键白名单校验。

### 5.1 clips

独立片段不挂在时间线上，**没有 `start_time`**，由 `goto` 出口或 `goto_clip` 动作跳入。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 | 被规则 / 路径 / 分支 / 出口引用时必填 |
| `track` / `duration` | string / number | 是 | 轨道类型（决定 `objects` 字段集）/ 正数有限、负数无限 |
| `objects` | object | 是 | 采样属性，形态同 3.2 |
| `exit` | object | 否 | 出口策略，见第 6 节 |

```json
{ "id": "branch_a", "track": "CAMERA", "duration": 2.0,
  "objects": { "position_mode": { "value": "absolute" }, "yaw": { "value": 180 } },
  "exit": { "on_complete": "continue" } }
```

### 5.2 rules

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 是 | 脚本内唯一 |
| `condition` | object | 是 | 条件树（状态谓词 / `all_of` / `any` / `clip_fact` / `rule_fact`） |
| `actions` | array | 是 | 至少一个动作 |
| `policy` | object | 否 | `once`（缺省 true）/ `cooldown` / `mode` / `dwell` / `timeout` / `on_timeout` |

```json
{ "id": "r_gate", "condition": { "type": "camera_fov", "from": 0, "to": 180 },
  "actions": [{ "type": "adjust_set", "exposure": 0.35, "saturation": 1.4 }],
  "policy": { "once": true } }
```

### 5.3 rule_bindings

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` / `rule` / `clip` | string | 是 | 绑定 id、命中 `rules[].id`、宿主片段 id；三者脚本内可解析 |
| `gate` | object | 是 | `{ "at": 秒 }`，目标片段执行局部秒 |
| `control` | enum | 否 | `observe`（缺省）/ `hold`（到达门后未成立则挂起） |
| `schedule` | object | 否 | `on` = `clip_start` / `clip_complete` / `local_time` / `delay` / `fact`，配套 `clip` / `at` / `seconds` / `binding` / `fact` |
| `exit` | object | 否 | 入口覆盖出口 |

```json
{ "id": "b_delay", "rule": "r_delay", "clip": "c1", "gate": { "at": 1.5 },
  "schedule": { "on": "delay", "seconds": 0.5 } }
```

### 5.4 paths

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` / `clip` | string | 是 | `^[A-Za-z0-9_]{1,64}$`；目标片段必须是 objects 片段 |
| `from` / `to` | number | 是 | 必须命中该片段 `objects` 中 `position.x/y/z` 的节点时间并集；`from < to` 且两端相邻 |
| `axes` | array | 否 | 轴集合，缺省 `["x","y","z"]` |
| `geometry` | object | 是 | `type`（`line` 为线性别名，另有注册曲线类型）+ `control_points` |
| `timing` | object | 否 | `type` = `linear`（缺省）/ `keyframes`；`points` 为 `[{t,s}]`，两端 0/1、单调不减、至少 2 点 |

```json
{ "id": "p1", "clip": "c1", "from": 0, "to": 6, "axes": ["x", "z"],
  "geometry": { "type": "bezier", "control_points": [[0, 0], [4, 8], [10, 10]] },
  "timing": { "type": "linear" } }
```

## 6. exit 出口策略

`exit` 可出现在时间线片段、独立片段和 `rule_bindings[]`。键白名单只有 `on_complete` / `target` / `entry_time`。

| `on_complete` | 行为 | 额外字段 |
|---|---|---|
| `continue` | 继续时间线 | — |
| `goto` | 跳到目标片段 | `target` 必填且可解析；`entry_time` 缺省 0，>= 0 |
| `return` | 返回调用者 | — |
| `end_execution` | 结束本次执行 | — |
| `end_script` | 结束整个脚本 | — |

只有 `goto` 允许 `target` / `entry_time`；其他类型写了 `target` 报错。

```json
"exit": { "on_complete": "goto", "target": "branch_a", "entry_time": 0 }
```

## 7. 已移除字段

下列写法**出现即报错**——统一走未知字段白名单（`meta` / 片段 / `objects` 各有一份键白名单），不再有专属"已移除"文案：

| 旧写法 | 新写法 |
|---|---|
| `meta.version` | 删除；格式无版本号 |
| `timeline.total_duration` | 删除；由片段窗口派生 |
| 片段级 `keyframes` | `objects.<path>.keyframes` |
| 片段级 `curve` | 脚本级 `paths[]` |
| 片段级 `rules` | 脚本级 `rules[]` + `rule_bindings[]` |
| 片段级 `interpolation` | 删除；插值由字段元数据决定 |
| `follow` / `follow_selector` | `position_origin` / `position_origin_x/y/z` |
| `facing_origin` / `_x` / `_y` / `_z` / `_time` | `position_origin` / `position_origin_x/y/z` / `position_origin_freeze` |
| `selector_*_follow` / `selector_*_facing_origin` | `selector_*_position_origin` |
| 片段级 `orient` / `yaw_offset` / `pitch_offset` | 移入 `objects` |
| AUDIO 文件循环旧名 `loop` | `file_loop` |
| `meta.hide_hud` / `hide_*` / `suppress_*` / `render_player_model` | `meta.screen_layers`（值 = 显示） |
| `meta.hud_layers` | `meta.screen_layers` |
| `meta.hard_hide_hud` | 删除；画面层由 `screen_layers` 控制 |
| `meta.screen_layer_mode` | 删除；接管恒生效，无模式字段 |

## 8. 校验规则

| 阶段 | 行为 |
|---|---|
| parser（`ScriptParser`） | **首错即抛** `ScriptParseException`，消息带 JSON 路径 |
| validator（`ScriptValidator`） | **收集 issue**，一次报全，不抛 |

常见错误类型：

| 类别 | 例子 |
|---|---|
| 未知键 | 片段上写了 `keyframes`；非 `goto` 的 `exit` 写了 `target` |
| 未知对象路径 | `objects` 里出现该轨道 schema 没有的字段 |
| 类型 / 枚举不符 | `loop_mode` 写了 `reverse`；`on_complete` 写了 `jump` |
| 关键帧非法 | 同一对象 `time` 相等或倒序；空 `keyframes` |
| 引用不可解析 | `rule_bindings[].clip` / `paths[].clip` / `exit.target` 指向不存在的片段 |
| 路径端点非法 | `paths[].from` 未命中位置节点时间；`from >= to`；两端不相邻 |
| 值域非法 | `skip_vote_ratio` 不在 10~100；`continuity.dwell` 超过 1200 tick |

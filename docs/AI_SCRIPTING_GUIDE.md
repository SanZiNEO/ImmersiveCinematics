# AI / 自动化脚本编写指南（0.3.6）

> 面向：用 AI 或脚本批量生成/修改过场脚本的人。
> 字段级完整表在 [`SCRIPT_FORMAT.md`](SCRIPT_FORMAT.md)；本文只讲**怎么写对**、**哪里最容易写错**。
> 作者入口是编辑器（产物 = 固定格式 JSON）；手写 JSON 是调试/迁移手段，不是推荐路径。

## 一、先记住三条

1. **一个片段 = 一个 `objects` 容器**。片段里除了播放控制字段，其余全部写成 `objects.<路径>`；`objects` 的键是 schema 声明的稳定路径（`position.x`、`opacity`、`z_index`……）。
2. **没有格式版本号，也没有总时长**。`meta.version` 与 `timeline.total_duration` 已删除，出现即报错；内容末端由片段窗口派生。
3. **旧写法一律不兼容**。clip 级 `keyframes` / `curve` / `rules`、`follow`、`facing_origin*`、HUD 旧字段、AUDIO 文件循环旧名 `loop` 等旧写法全部按未知字段报错（统一白名单，无专属文案），清单见 §六。

## 二、脚本骨架

```json
{
  "meta": {
    "id": "chapter1/boss_fight",
    "name": "Boss Fight",
    "author": "your_name",
    "triggers": [ { "id": "trg_login", "type": "biome", "conditions": { "biome": "*" } } ]
  },
  "timeline": {
    "tracks": [
      { "type": "CAMERA", "clips": [ { "id": "c1", "start_time": 0, "duration": 5, "objects": { } } ] }
    ]
  },
  "clips": [],
  "rules": [],
  "rule_bindings": [],
  "paths": []
}
```

- 顶层只有这 6 个键：`meta` / `timeline` / `clips` / `rules` / `rule_bindings` / `paths`；后四个可省。
- `meta.id`：`^[a-zA-Z0-9_]{1,32}$`（注意 **≤32**，带 `/` 或超长会被拒）。
- `meta.name` ≤50、`meta.author` ≤30（可空串，但字段要在）。

## 三、片段怎么写

### 3.1 播放控制字段（只允许这些留在 clip 外层）

| 字段 | 说明 |
|---|---|
| `start_time` | 默认放置锚点（秒）；独立片段 `clips[]` **禁止**写 |
| `duration` | 片段时长（秒）；负数 = 无限单次 |
| `loop` / `loop_count` / `loop_mode` | 执行循环；`pingpong` 只对 CAMERA / OVERLAY / LETTERBOX 生效 |
| `id` | 片段身份；**被规则/路径/出口/分支引用时必须写** |
| `exit` | 片段默认出口策略（见 §五） |
| `objects` | 其余全部数据 |

### 3.2 `objects` 的两种形态

```json
"objects": {
  "position_mode": { "value": "absolute" },
  "position.x":    { "keyframes": [ { "time": 0, "value": 0 }, { "time": 5, "value": 10 } ] },
  "roll":          { "value": 0 }
}
```

- `value` = 静态值；`keyframes` = 逐帧采样（覆盖 `value`）。
- **value-only 字段不能带 keyframes**（`mask_shape`、`layer_type`、`sound`、`z_index`、`orient`……）；写了会被校验拒绝。
- 同一对象的 `time` **必须严格递增**；不同对象同一时间合法。
- 超界关键帧（`time < 0` 或有限片段 `time > duration`）保留但不参与求值，**不报错**。
- 没有 keyframes 的片段是合法的（静态相机/静态覆盖层）。

### 3.3 复合值必须拆叶子

| 旧写法 | 新写法 |
|---|---|
| `position: {x,y,z}` | `position.x` / `position.y` / `position.z` |
| `dest: {x,y,w,h}` | `dest.x` / `dest.y` / `dest.w` / `dest.h` |
| `source: {x,y,w,h}` | `source.x` / `source.y` / `source.w` / `source.h` |
| 关键帧 `color: {r,g,b}` | `color.r` / `color.g` / `color.b`（0~1） |
| `look_at_part: {x,y,z}` | `look_at_part.x` / `.y` / `.z`（0~100） |
| `*_offset: {dx,dy,dz}` | `*_offset.x` / `.y` / `.z` + `*_offset.space`（`world`/`base`） |

> clip 级 `color` 是**字符串** `"#RRGGBB"`（图层基色），和关键帧里的 `color.r/g/b` 是两回事。

### 3.4 位置：点源 + 偏移

| `position_mode` | `position_space` | `position.x/y/z` 的含义 |
|---|---|---|
| `absolute` | `world` | 直接世界坐标；**不允许**同时写 `position_origin` |
| `relative` | `world` | 原点 + 世界轴偏移 |
| `relative` | `base` | 原点 + 基准系偏移，`x/y/z` 读作 `fwd/up/right` |

- 原点：`position_origin` 空 = 玩家激活位置（`base` 空间下 = 隐式 `@p`）；`coordinate` 配 `position_origin_x/y/z`；选择器 / `block:id[:radius]` / 结构 id 原样写。
- 冻结：`position_origin_freeze=true` 只在实体选择器形态有意义。
- `base` 空间的方向源：`facing_source` = `auto`/`world`/`entity`/`line`，配 `facing_entity` 或 `facing_target`。
- `yaw_offset` / `pitch_offset` 需要 `orient="tangent"`，否则不生效。

### 3.5 音频与视频资源

- `AUDIO` 的文件路径放在 `objects.sound.value`，并使用 `objects.source.value = "file"`；路径相对于 `<游戏目录>/immersive_cinematics/resource/`。`.ogg` / `.wav` 内置解码；`.mp3` / `.aac` / `.flac` / `.m4a` / `.opus` / `.wma` / `.oga` / `.ogx` 需要外部 FFmpeg，整段异步解码后起播。`source = "minecraft"` 仍指向资源包中的 OGG 资源，不是外部格式入口。
- 视频使用 **`OVERLAY` 轨道 + `layer_type = "image"`**，把 `.mp4` / `.webm` / `.mkv` 路径放在 `objects.path.value`；不要编造 `VIDEO` 轨道或 `layer_type = "video"`。位置、缩放、取材和透明度仍用 OVERLAY 的原有字段。
- 外部工具只从 `<游戏目录>/ffmpeg/` 读取，不查 `PATH`、不自动下载；Windows 文件名为 `ffmpeg.exe` / `ffprobe.exe`，其他平台无 `.exe`。视频需要两者；扩展音频解码只需要 `ffmpeg`。静态脚本校验通过不代表工具、素材或媒体解码已经可用。
- `AUDIO.file_loop` 是声音文件循环，不是视频控制字段；视频时间由 OVERLAY 片段的局部时间驱动。不要把编辑器固定的 1920×1080@60 预览规格写成脚本字段，也不要由预览能力推断已有录像导出接口。
- 当前分类资源列表与解码能力不同：`resource.list(kind=image)` 只列 PNG/GIF，`kind=audio` 只列 OGG/WAV。没有在列表看到 MP4/MP3 等文件，不代表脚本不能按正确路径使用它们；也不要假设前端选择器已接入全部新格式。
- 字段与视频 JSON 示例见 [SCRIPT_FORMAT §4.3、§4.6](SCRIPT_FORMAT.md)；工具安装位置、失败行为与当前限制见 [媒体说明](modules/media.md)。

## 四、脚本级集合

### 4.1 `clips[]`（独立片段）

```json
"clips": [ { "id": "cam_extra", "track": "CAMERA", "duration": 3, "objects": { } } ]
```

- 必填 `id` / `track` / `duration`；**无 `start_time`**（没有默认放置）。
- 只由分支/出口启动；启动后按自己的 `track` 进入对应轨道渲染。

### 4.2 `rules[]` + `rule_bindings[]`

```json
"rules": [ { "id": "r_door", "condition": { "type": "all_of", "list": [ ] }, "actions": [ { "type": "exit" } ], "policy": { "once": true } } ],
"rule_bindings": [ { "id": "b_door", "rule": "r_door", "clip": "c1", "gate": { "at": 2.0 }, "control": "observe" } ]
```

- 定义只描述行为（`condition` / `actions` / `policy`）；**绑定**才指向片段与时间门。
- `gate.at` **必填**：目标片段的执行局部秒；到门后开始/继续判定，迟到成立仍执行。
- `control`：`observe`（非阻塞，缺省）/ `hold`（到门未成立即挂起本实例）。
- `schedule`：动作释放条件（片段开始/完成、局部时间、延迟、事实）；`exit`：绑定级出口覆盖。
- `goto_clip` 动作只接受 `{ "clip": "<id>", "entry_time": <秒> }`；旧 `{track, at}` 已移除。

### 4.3 `paths[]`（路径集合）

```json
"paths": [ { "id": "p1", "clip": "c1", "from": 0, "to": 2, "axes": ["x","y","z"], "geometry": { "type": "bezier", "control_points": [ ] }, "timing": { "type": "linear" } } ]
```

- `from` / `to` 必须命中目标片段 `position.x/y/z` 的**节点时间并集**，且**相邻**。
- 一条路径只管它选中的轴；未选轴继续按自己的关键帧求值。
- 直线路径也可以带 `timing`。

## 五、出口策略 `exit`

```json
"exit": { "on_complete": "goto", "target": "c2", "entry_time": 1.5 }
```

| `on_complete` | 含义 | 允许 `target`/`entry_time` |
|---|---|---|
| `continue`（缺省） | 继续主线 | 否 |
| `goto` | 跳到目标片段 | 是（`target` 必填，`entry_time` 缺省 0） |
| `return` | 返回发起处 | 否 |
| `end_execution` | 结束本次执行 | 否 |
| `end_script` | 结束脚本 | 否 |

优先级：**入口显式覆盖 > 片段默认 > `continue`**。失败 fallback 是独立策略，不走这张表。

## 六、已移除字段（出现即报错：未知字段）

| 旧写法 | 新写法 |
|---|---|
| `meta.version` | 删除（不再有格式版本号） |
| `timeline.total_duration` | 删除（有限末端/显示范围由片段窗口派生） |
| clip 级 `keyframes` | `objects.<路径>.keyframes` |
| clip 级 `curve` | `paths[]`（每个旧段一条） |
| clip 级 `rules` | 脚本级 `rules[]` + `rule_bindings[]` |
| clip 级 `interpolation` | 删除（缓动由编辑器烘焙成关键帧） |
| `follow` / `follow_selector` | `position_origin` + `position_origin_freeze=false` |
| `facing_origin` / `_x/_y/_z` / `_time` | `position_origin` / `position_origin_x/y/z` / `position_origin_freeze` |
| `selector_*_follow` / `selector_*_facing_origin` | `selector_*_position_origin` |
| clip 级 `orient` / `yaw_offset` / `pitch_offset` | 写进 `objects` |
| AUDIO 文件循环 `loop` | `objects.file_loop`（`loop` 只作执行循环） |
| `meta.hud_layers` | `meta.screen_layers`（极性翻转：新 `true` = 显示） |
| `meta.hard_hide_hud` | 删除；画面层由 `screen_layers` 控制，有隐藏层即整帧接管 |
| `meta.screen_layer_mode` | 删除；接管恒生效，无模式字段 |

## 七、最容易写错的十件事

1. `meta.id` 超过 32 字符，或用了 `/`、`-`。
2. 把数值通道写在 clip 级（新格式只允许 §3.1 的白名单键）。
3. 给 value-only 字段写 keyframes（`mask_shape`、`z_index`、`sound`…）。
4. 同一对象里两条 keyframe 时间相同（必须严格递增）。
5. `absolute` 位置同时写 `position_origin`（校验拒绝）。
6. `yaw_offset` / `pitch_offset` 没配 `orient="tangent"`。
7. 引用片段（规则绑定、路径、`goto`、`exit.target`）却没给该片段写 `id`。
8. `paths[]` 的 `from`/`to` 不是目标片段 `position` 节点时间，或不相邻。
9. `rule_bindings[].gate.at` 超过目标片段时长。
10. 用 AUDIO 的 `loop` 想表达「文件循环」——那是 `objects.file_loop`。

## 八、校验行为

- **parser**：结构/类型/必填错误，**首错即抛**（`ScriptParseException`，带 JSON 路径）。
- **validator**：一次收集多条 issue（`/icinematics validate` 与无头校验器同源）。
- 未知对象路径、未知枚举值、`requires` 不满足、`appliesTo` 错层、非有限值、重复时间都会被拦。
- 运行时假设数据已通过校验，不在每帧做类型检查。

## 九、参考

- 字段表：`SCRIPT_FORMAT.md`。
- 设计口径：`plans/0.3.6/I-script-core/README.md`（对象模型 / 规则 / 路径 / 循环）。
- 迁移说明与差异：`plans/0.3.6/I-script-core/migration-report.md`。

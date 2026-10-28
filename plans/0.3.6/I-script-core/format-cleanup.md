# 格式清理：删除 `meta.version` 与 `timeline.total_duration`

> 状态：未实现
> 范围：两个字段的删除、`contentEnd`/`infinite` 派生、所有消费点、旧字段拒绝口径
> 关联：`object-model.md`、`loop-execution.md`、`migration.md`、`editor-object-model.md`；取代 `I-script-core/README.md` §3 的方向描述

## 一、摘要

新脚本格式不再有 `meta.version` 和 `timeline.total_duration`。版本判断没有信息量（只有一个值 3）；总时长可以从片段窗口推导。运行时用 `contentEnd = max(clip.getWindowEnd())` 和 `infinite = any(clip.isEffectivelyInfinite())` 替代；`hold_at_end`、`goto_clip` 范围、图服务、命令输出、模板、编辑器 seek/zoom 全部改用派生值。旧字段出现时按“已移除”报错，不做兼容读取；一次性迁移器负责删除。

## 二、为什么做

### 2.1 问题

- `meta.version` 被 parser/validator/editor/schema 硬要求等于 3；它不区分语法、不驱动迁移、不改变解析，只是一个必须同步的常量。
- `timeline.total_duration` 被 parser/validator 要求存在且非 0；但真实内容末端是各片段窗口的最大值。编辑器要重算/维护，容易和片段窗口不一致；手写脚本不作为推荐入口。
- 运行时又用 `total_duration` 做 hold-at-end、goto_clip 范围、命令输出、图节点时长；这些值和 `contentEnd` 可能不一致。
- 编辑器每次 commit/load 都 `recalcDuration` 写回 `total_duration`；循环/片段窗口变化时容易漂移。
- 模板/示例/测试都要带这两个字段，迁移/新增脚本多一步无意义工作。

### 2.2 目标

- parser/validator/schema/editor/templates 不再要求/写入这两个字段。
- `contentEnd`/`infinite` 是唯一派生口径；所有消费者读同一份。
- 旧字段出现时明确报错“已移除”，不静默忽略、不双读。
- 编辑器 seek-to-end/zoom-to-fit/时间轴总长用 `contentEnd`；无限脚本显示“无限”而不是某个大数。
- 迁移器删除字段；新脚本/模板不再生成。

### 2.3 非目标

- 不引入脚本格式版本号/判别字段；以后破坏性改动才加。
- 不保留 `total_duration` 作为缓存/提示字段；不提供“可选兼容”。
- 不改变 `hold_at_end`/`goto_clip`/图服务的语义，只换数据来源。
- 不改变宏观循环的 `loop_start`/`loop_end`/`macro_loop_count`。

## 三、方案

### 3.1 新 `meta`

```json
{
  "meta": {
    "id": "chapter1/boss_fight",
    "name": "Boss Fight",
    "author": "...",
    "triggers": [],
    "skippable": true,
    "interruptible": true,
    "hold_at_end": false,
    "listener": "player"
  },
  "timeline": {
    "loop_start": 0,
    "loop_end": 10,
    "macro_loop_count": -1,
    "tracks": []
  }
}
```

- `meta.version` 删除；`meta` 的身份/运行时行为/触发器字段保留。
- `timeline.total_duration` 删除；`timeline` 只保留轨道和宏观循环参数。
- 没有 `version` 的脚本不是“旧版本兼容”，而是唯一格式；旧字段出现即错误。

### 3.2 派生口径

```text
contentEnd = max over clips of clip.getWindowEnd()
             (no clips -> 0)
infinite   = any clip.isEffectivelyInfinite()
```

- `clip.getWindowEnd()` 见 `loop-execution.md`：有限 = `start + duration × 执行次数`；无限 = ∞。
- `contentEnd` 是有限值；只要有无限片段，`infinite=true`，`contentEnd` 仅作为“有限部分末端”/图显示参考，不用于 hold/范围上限。
- `hold_at_end`：有限脚本在 `contentEnd` 驻留；无限脚本没有驻留点，validator 告警（与无限宏观循环互斥）。
- `goto_clip` 的时间范围：有限 `[0, contentEnd]`；无限无上限（只校验非负/有限）。
- `ScriptGraphService` 节点：`duration = contentEnd`、`infinite = infinite`；片段节点用 `clip.getWindowEnd()`。
- `CinematicCommand` 打印：显示 `contentEnd` 或“无限”，不再打印 `total_duration`。
- 编辑器 seek-to-end/zoom-to-fit/总长：用 `contentEnd`；无限脚本禁用 seek-to-end 或显示“无限”。
- 模板：不写 `total_duration`；需要时长时用片段窗口求和/最大值。

### 3.3 旧字段拒绝

parser/validator 遇到以下字段直接报错，错误信息包含“已移除”与替代：

| 旧字段 | 位置 | 处理 |
|---|---|---|
| `meta.version` | meta | 错误“已移除：meta.version” |
| `timeline.total_duration` | timeline | 错误“已移除：timeline.total_duration；用 contentEnd 派生” |
| `keyframes`（共享快照数组） | clip | 错误“已移除：keyframes；用 objects” |
| `curve`（旧 clip 级 3D 曲线） | CAMERA clip | 错误“已移除：clip 级 curve；用 objects.curve（axes:["x","y","z"] + 3D control_points）” |
| `follow` / `follow_selector` | CAMERA keyframe | 错误“已移除：follow；用 position_origin + position_origin_freeze=false（实体 + false = 跟随）” |
| `facing_origin` / `facing_origin_x/y/z` / `facing_origin_time` | CAMERA keyframe | 错误“已移除：facing_origin；用 position_origin / position_origin_x/y/z / position_origin_freeze” |
| `selector_*_follow` / `selector_*_facing_origin` | CAMERA keyframe | 错误“已移除：selector 调用点 follow/facing_origin；用 selector_*_position_origin” |
| `orient` / `yaw_offset` / `pitch_offset` | CAMERA clip | 错误“已移除：clip 级朝向字段；用 objects 的 orient/yaw_offset/pitch_offset” |
| `loop`（AUDIO clip 级文件循环） | AUDIO clip | 错误“已移除：AUDIO loop；用 objects 的 file_loop” |
| `interpolation`（clip 级） | 任意 clip | 错误“已移除：clip.interpolation；缓动由编辑器烘焙” |

- 旧字段不在 parser 里“读一半/忽略一半”；一次性迁移器负责转换。
- 未知字段仍按现有严格校验报错；不把“未知”与“已移除”混为一谈，但错误信息可以都带路径。
- 不提供 `--legacy` 开关；迁移在运行前完成。

### 3.4 触点清单

| 层 | 文件/位置 | 改动 |
|---|---|---|
| parser | `ScriptParser.java` | 删除 version 检查（142-156）、total_duration 要求（258-261）；拒绝旧字段 |
| meta | `ScriptMeta.java` / `MetaSchemas.java` | 删除 version 字段/schema；保留身份/行为/触发器 |
| timeline | `Timeline.java` | 删除 `totalDuration`；提供 `contentEnd()`/`infinite()` 或由 `CinematicScript` 计算 |
| script | `CinematicScript.java` | 删除 version/totalDuration 暴露；提供派生 |
| validator | `ScriptValidator.java` | 删除 version/total_duration 检查（200-210、293-304）；旧字段错误 |
| player | `ScriptPlayer.java` | `contentEnd` 已从 `getWindowEnd` 派生（779-796）；删除 total_duration 读取；lap/consumption 用 duration |
| frame | `FrameDriver.java` | hold-at-end 用 `contentEnd`（141-144） |
| rules | `RuleActions.java` | goto_clip 范围用 `contentEnd`/infinite（320-323） |
| command | `CinematicCommand.java` | 打印 contentEnd/infinite（241-242） |
| graph | `webui/ScriptGraphService.java` | 节点 duration/infinite 用派生（184-187） |
| template | `ScriptTemplate.java` / `TemplateScriptAssembler.java` | 删除 total_duration 写入（47-49/58-60）；对象生成 |
| editor types | `editor/src/types.ts` | 删除 `ScriptMeta.version`、`Timeline.total_duration` |
| editor store | `store.ts` | 删除 `recalcDuration`/commit 写入/load 重算（146-152、358-375） |
| editor ops | `operations.ts` | 删除 version/total_duration 校验（644-652）；用 contentEnd |
| editor app | `App.vue` | seek-to-end/step/zoom 用 contentEnd（207/222/229） |
| editor demo | `demo.ts` | 删除 version/total_duration |
| editor schema | `schema.ts` | 不再为 version/total_duration 填默认/保留 |
| docs | `docs/SCRIPT_FORMAT.md`、`AI_SCRIPTING_GUIDE.md`、`docs/modules/script.md`、`editor.md` | 删除字段/示例/校验说明；改 objects/loop/contentEnd |
| fixtures | `cinematics/0.3.6/**` | 183+ 个 `total_duration` 由迁移器删除；version 同 |
| templates | `plans/0.3.6/E-template/*` | 删除 total_duration；对象生成 |
| packet | `F-playback/f5-script-packet-limit.md` | 加对象格式体积说明；无 total_duration |

### 3.5 编辑器行为

- 打开脚本：不再校验 `version===3`/`total_duration!==0`；改为校验 `objects` 结构。
- 保存/推送：不写 `version`/`total_duration`；不再 `recalcDuration`。
- 时间轴总长：`contentEnd`；无限脚本显示“∞”，缩放/seek 以有限部分为参考。
- 新建脚本：模板不生成这两个字段；默认 `timeline` 只有 tracks/loop 参数。
- 旧脚本：先运行迁移器；编辑器不提供“旧格式打开”模式。

### 3.6 运行时行为

- `contentEnd` 在脚本加载时预计算；无限标志预计算；不在每帧遍历片段。
- `hold_at_end`：`min(t, contentEnd)`；无限脚本不 clamp。
- `goto_clip`：目标时间有限/非负；有限脚本不超过 contentEnd；无限脚本不设上限。
- 图服务/命令输出：用预计算值；不读已删除字段。
- 日志/告警：显示 contentEnd/infinite；不再出现 total_duration。

## 四、技术细节

### 4.1 核心模型

```text
CinematicScript
  meta
  timeline
  contentEnd   // 加载时派生
  infinite     // 加载时派生
```

- `contentEnd`/`infinite` 是派生只读字段；不序列化。
- `Timeline` 只持有 `tracks`/`loopStart`/`loopEnd`/`macroLoop*`；不持有总时长。
- `ScriptMeta` 不持有 version；身份/行为/触发器不变。
- 旧字段检测在 parser 的顶层结构校验里做，先于对象解析；错误信息稳定。

### 4.2 契约

- **创建**：parser 读入后计算 contentEnd/infinite；`CinematicScript` 持有。
- **销毁**：随脚本销毁；无缓存失效问题（脚本不可变）。
- **状态归谁**：派生值归 `CinematicScript`；消费者只读。
- **边界**：parser 不写回脚本；编辑器不写派生值；迁移器删除旧字段但不写派生值。

### 4.3 数据形态

- `contentEnd` float；无片段 = 0；无限脚本仍有一个有限部分末端，但 `infinite=true`。
- `infinite` bool；`duration<0` 或 `loop && loop_count<0` 任一。
- `meta` 的 JSON 不再有 `version`；`timeline` 不再有 `total_duration`。
- 旧字段错误信息包含字段路径和替代；不包含“请升级”之类模糊文案。

### 4.4 边界

- 不改变 `hold_at_end` 的驻留语义；只换端点来源。
- 不改变 `goto_clip` 的分支语义；只换范围。
- 不改变宏观循环参数；`loop_end` 缺省仍用 contentEnd/无限判断。
- 不改变图服务的节点类型；只换 duration/infinite 来源。
- 不改变编辑器布局/交互；只换总长来源和校验。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 保留 `meta.version` | 继续要求 3，未来再加 | 常量同步；不驱动任何行为；迁移靠外部 | 没有信息量 |
| 保留 `total_duration` 作为可选缓存 | 作者可写，运行时不信任 | 双口径；编辑器/作者可能写错；校验要比较 | 派生值唯一 |
| 用 `total_duration` 作为权威 | 片段窗口超出时截断 | 与循环/片段窗口冲突；hold/goto 不一致 | contentEnd 权威 |
| 运行时兼容旧字段 | parser 双读/忽略 | 两套语义；旧格式永远清不掉 | 一次性迁移 |
| 加格式版本号区分 | `version=4` | 又回到版本字段；迁移仍需外部 | 不做版本 |

**本方案的缺点**：

- 删除 `total_duration` 后，任何需要“脚本总长”的外部工具都要自己派生；提供 `contentEnd` API/图服务输出。
- 无限脚本的 `contentEnd` 只是有限部分末端，容易被误用为总长；文档/命名要明确。
- 旧字段错误会让未迁移脚本直接不可用；迁移必须先跑。
- 编辑器/模板/测试的触点很多，漏一处就会写回旧字段；验收要全仓 grep。
- 没有版本号后，未来破坏性改动只能靠字段存在性/迁移器识别；需要严格的“旧字段已移除”清单。

## 六、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 未实现 | — | — |

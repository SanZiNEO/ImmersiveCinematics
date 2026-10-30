# 格式清理：删除 `meta.version` 与 `timeline.total_duration`

> 状态：未实现
> 范围：两个字段的删除、有限内容末端/无限标志/有限显示范围派生、所有消费点、旧字段拒绝口径
> 关联：`object-model.md`、`loop-execution.md`、`rule-system.md`（本轮规则方向）、`migration.md`、`../Z-editor/object-model.md`；本篇负责格式字段、派生范围与相关消费者的契约

## 一、摘要

目标格式删除 `meta.version` 与 `timeline.total_duration`，不再要求作者维护一个可与实际执行路径分离的总时长。静态有限编排可派生有限末端估计；无限编排不伪造有限自然末端，有限部分显示范围独立计算。派生范围服务显示、统计和定位提示；自然完成、末尾驻留及分支可达性分别服从实际执行状态、最终状态与目标内容约束，不由估计时长驱动。旧 `clip.rules` 与 clip 级 `curve` 按已移除／迁移处理；规则／曲线集合不成为新的总时长或完成来源。

## 二、为什么做

### 2.1 问题

- `meta.version` 被 parser/validator/editor/schema 硬要求等于 3；它不区分语法、不驱动迁移、不改变解析，只是一个必须同步的常量。
- `timeline.total_duration` 被 parser/validator 要求存在且非 0；但真实内容末端是各片段窗口的最大值。编辑器要重算/维护，容易和片段窗口不一致；手写脚本不作为推荐入口。
- 运行时又用 `total_duration` 做 hold-at-end、goto_clip 范围、命令输出、图节点时长；这些值和 `contentEnd` 可能不一致。
- 现有 `Clip.getWindowEnd()` 对无限片段返回 `Float.MAX_VALUE`，`ScriptPlayer.contentEnd()` 对无限内容返回 `NaN`；文档又把 `contentEnd` 写成“有限值/有限部分末端”，同一概念有多套值，容易被当成完成判据。
- `goto_clip` 若只校验“目标非负/有限”，会把任意空档当合法目标，丢失作者编排的片段覆盖/可达约束。
- 编辑器每次 commit/load 都 `recalcDuration` 写回 `total_duration`；循环/片段窗口变化时容易漂移。
- 模板/示例/测试都要带这两个字段，迁移/新增脚本多一步无意义工作。

### 2.2 目标

- parser/validator/schema/editor/templates 不再要求/写入这两个字段。
- 派生口径统一：`infinite` / `contentEnd` / `finiteDisplayEnd` 各司其职；不用 ∞/NaN/MAX 当完成判据或 wire 值。
- 完成判定由播放实例的执行记录给出；`contentEnd` 只做显示/定位/统计。
- 旧字段出现时明确报错“已移除”，不静默忽略、不双读。
- 编辑器 seek-to-end/zoom-to-fit/时间轴总长用有限末端；含无限片段时显示“无限”，zoom/seek 用 `finiteDisplayEnd`，不拿大数代替。
- 迁移器删除字段；新脚本/模板不再生成。

### 2.3 非目标

- 不引入脚本格式版本号/判别字段；以后破坏性改动才加。
- 不保留 `total_duration` 作为缓存/提示字段；不提供“可选兼容”。
- 不改变 `hold_at_end` 的驻留行为/goto_clip 的分支语义/图服务节点类型；`hold_at_end` 驻留点改由自然完成给出，goto_clip 换范围但保留目标可达/片段覆盖约束。
- 不改变宏观循环的 `loop_start`/`loop_end`/`macro_loop_count`。
- 不定义新的数值哨兵 wire 字段（∞/NaN/MAX）；“无有限末端”用标志表达。

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
infinite = 静态编排中含无自然完成点的执行（片段或宏观无限重复）
contentEnd = infinite
  ? 无有限值
  : max(clip.windowEnd)      // no clips -> 0
finiteDisplayEnd = max over finite clips of finite windowEnd
```

- `clip.windowEnd` 见 `loop-execution.md`：有限 = `start + duration × 执行次数`；无限没有有限值，用 `isEffectivelyInfinite=true` 表达。
- `contentEnd` 只在**全部内容静态有限**时给出有限估计；含无限片段时没有有限 `contentEnd`（`infinite=true`）。不写 `∞`/`NaN`/`MAX`，也不拿它当完成判据。
- `finiteDisplayEnd` 是编辑器缩放/seek/图显示的“有限部分范围”，单独派生；它不声称内容在此结束，也不用于 hold/goto 上界。具体数值口径待定，不把显示范围塞进完成语义。
- **自然完成**由实例的实际执行路径、剩余次数、明确等待和在跑分支决定；普通非阻塞观察不阻止完成，声明中存在无限内容也不能代替路径事实。
- `hold_at_end` 由有限内容实际完成后的最终状态决定；真正持续无限的执行没有自然驻留点，不用末端数值 clamp，也不把静态显示标志当结束开关。
- `goto_clip` 保留目标可定位／内容覆盖与执行路径约束；有限末端估计只能辅助定位，不能用显示总长代替可达性判断。含无限内容也不只检查非负／有限就放行任意空档。
- `ScriptGraphService` 节点：有限内容给 `duration = contentEnd`；含无限时给 `infinite=true`，不伪造 duration；片段节点用 `clip.windowEnd`（有限时）。
- `CinematicCommand` 打印：有限内容显示 `contentEnd`；含无限显示“无限/无有限末端”，不再打印 `total_duration`。
- 编辑器 seek-to-end/zoom-to-fit/总长：有限内容用 `contentEnd`；含无限时总长显示“无限”，zoom/seek 以 `finiteDisplayEnd` 为参考，禁用 seek-to-end。
- 模板：不写 `total_duration`；需要时长时用片段窗口求和/最大值（仅有限内容）。

### 3.3 旧字段拒绝

parser/validator 遇到以下字段直接报错，错误信息包含“已移除”与替代：

| 旧字段 | 位置 | 处理 |
|---|---|---|
| `meta.version` | meta | 错误“已移除：meta.version” |
| `timeline.total_duration` | timeline | 错误“已移除：timeline.total_duration；用片段窗口派生有限末端/显示范围” |
| `keyframes`（共享快照数组） | clip | 错误“已移除：keyframes；用 objects” |
| `curve`（旧 clip 单份 3D 曲线） | CAMERA clip | 错误“旧曲线形式已移除；按各旧段迁移至曲线集合条目：引用片段与目标位置对象／组中两个相邻关键帧”；不预设 `objects.curve` value-only，也不把曲线做成片段内隐式全片段控制点；引用与序列化按结构评审结果 |
| `rules`（v1 clip 级规则元数据） | clip | 错误“已移除：clip 级 rules；规则改为脚本级集合，绑定引用片段与局部时间”；迁移器拆为规则定义与绑定，运行激活不写入脚本 |
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
| timeline | `Timeline.java` | 删除 `totalDuration`；提供 `contentEnd`/`finiteDisplayEnd`/`infinite` 或由 `CinematicScript` 计算 |
| script | `CinematicScript.java` | 删除 version/totalDuration 暴露；提供派生 |
| validator | `ScriptValidator.java` | 删除 version/total_duration 检查（200-210、293-304）；旧字段错误 |
| player | `ScriptPlayer.java` | 删除 total_duration 读取；完成/消费读执行记录（见 `loop-execution.md`）；`contentEnd` 只做显示/定位，不再用 `NaN`/`MAX` 当无终点 |
| frame | `FrameDriver.java` | hold-at-end 用自然完成点/有限末端（141-144），不读 total_duration |
| rules | `RuleActions.java` | goto_clip 范围用 `contentEnd`/无限标志，并保留目标可达/片段覆盖约束（320-323） |
| command | `CinematicCommand.java` | 打印 `contentEnd` 或“无限/无有限末端”（241-242） |
| graph | `webui/ScriptGraphService.java` | 节点 duration/infinite 用派生；含无限不伪造 duration（184-187） |
| template | 编辑器侧模板引擎（Z 轮） | 产物不写 `total_duration`；运行时 `script/template/`、`ScriptTemplate` 随 Z 轮删除，不在本表修改 |
| editor types | `editor/src/types.ts` | 删除 `ScriptMeta.version`、`Timeline.total_duration` |
| editor store | `store.ts` | 删除 `recalcDuration`/commit 写入/load 重算（146-152、358-375） |
| editor ops | `operations.ts` | 删除 version/total_duration 校验（644-652）；用 contentEnd/finiteDisplayEnd |
| editor app | `App.vue` | seek-to-end/step/zoom 用 contentEnd/finiteDisplayEnd（207/222/229） |
| editor demo | `demo.ts` | 删除 version/total_duration |
| editor schema | `schema.ts` | 不再为 version/total_duration 填默认/保留 |
| docs | `docs/SCRIPT_FORMAT.md`、`AI_SCRIPTING_GUIDE.md`、`docs/modules/script.md`、`editor.md` | 删除字段/示例/校验说明；改 objects/loop/有限末端/显示范围 |
| fixtures | `cinematics/0.3.6/**` | 183+ 个 `total_duration` 由迁移器删除；version 同 |
| templates | `plans/0.3.6/Z-editor/templates.md` | 删除 total_duration；对象生成 |
| packet | `F-playback/f5-script-packet-limit.md` | 加对象格式体积说明；无 total_duration |

### 3.5 编辑器行为

- 打开脚本：不再校验 `version===3`/`total_duration!==0`；改为校验 `objects` 结构。
- 保存/推送：不写 `version`/`total_duration`；不再 `recalcDuration`。
- 时间轴总长：有限内容用 `contentEnd`；含无限片段时总长显示“无限”，缩放/seek 以 `finiteDisplayEnd` 为参考，禁用 seek-to-end。
- 新建脚本：模板不生成这两个字段；默认 `timeline` 只有 tracks/loop 参数。
- 旧脚本：先运行迁移器；编辑器不提供“旧格式打开”模式。

### 3.6 运行时行为

- `contentEnd`/`finiteDisplayEnd`/`infinite` 在脚本加载时预计算；不在每帧遍历片段。
- 自然完成看实际路径上的内容、明确等待和在跑分支；普通观察不计入剩余内容，不用窗口／末端数值比较。
- `hold_at_end` 保持实际完成后的最终状态；仍在无限执行或明确等待时没有自然完成驻留点，静态显示估计不能驱动驻留。
- `goto_clip` 验证目标内容的定位／覆盖和路径约束；统计范围可作提示，不是另一个权威总时长。无限不意味着可放行任意空档。
- 图服务/命令输出：用预计算值；含无限时不伪造 duration；不读已删除字段。
- 日志/告警：显示 contentEnd/infinite/“无有限末端”；不再出现 total_duration。

## 四、技术细节

### 4.1 核心模型

```text
CinematicScript
  meta
  timeline
  infinite              // 加载时派生
  contentEnd?           // 仅 infinite=false 时有值
  finiteDisplayEnd?     // 有限部分显示范围
```

- `infinite`/`contentEnd`/`finiteDisplayEnd` 是派生只读字段；不序列化。
- `Timeline` 只持有 `tracks`/`loopStart`/`loopEnd`/`macroLoop*`；不持有总时长。
- `ScriptMeta` 不持有 version；身份/行为/触发器不变。
- 旧字段检测在 parser 的顶层结构校验里做，先于对象解析；错误信息稳定。

### 4.2 契约

- **创建**：parser 读入后计算 `infinite`/`contentEnd`/`finiteDisplayEnd`；`CinematicScript` 持有。
- **销毁**：随脚本销毁；无缓存失效问题（脚本不可变）。
- **状态归谁**：派生值归 `CinematicScript`；消费者只读。自然完成状态归播放实例的执行记录，不归脚本派生值。
- **边界**：parser 不写回脚本；编辑器不写派生值；迁移器删除旧字段但不写派生值；`contentEnd` 不参与完成判定。

### 4.3 数据形态

- `infinite` 为静态无有限末端标志，涵盖负时长、片段无限重复及宏观无限重复；只作显示／推导信息，不替代实际执行路径判定。
- `contentEnd` float，仅在 `infinite=false` 时有值；无片段 = 0；含无限片段时没有有限值（不写 `∞`/`NaN`/`MAX`）。
- `finiteDisplayEnd` float，单独派生，可为空；只服务显示/缩放/seek，不声称内容在此结束，也不用于 hold/goto 上界。
- `meta` 的 JSON 不再有 `version`；`timeline` 不再有 `total_duration`。
- 旧字段错误信息包含字段路径和替代；不包含“请升级”之类模糊文案。

### 4.4 边界

- 不改变 `hold_at_end` 的驻留行为；驻留点由自然完成给出（有限内容），无限内容没有自然完成点、不驻留。
- 不改变 `goto_clip` 的分支语义；只换范围，并保留目标可达/片段覆盖约束（不能只校验非负/有限）。
- 不改变宏观循环参数；`loop_end` 缺省仍用有限内容末端/无限判断。
- 不改变图服务的节点类型；只换 duration/infinite 来源；含无限不伪造 duration。
- 不改变编辑器布局/交互；只换总长/有限显示范围来源和校验。
- “无有限末端”用标志表达，不引入新的数值哨兵 wire 字段。
- 规则集合与曲线集合是脚本级独立声明，不改变 `total_duration` 删除与完成判定；集合条目只引用片段内容，不复制关键帧，也不成为新的总时长或完成来源。
- 正常完成去向与失败 fallback 属于分支出口策略，不在格式清理范围；`goto_clip` 只换定位范围并保留可达／覆盖约束。
- 规则控制阶段（判定、实例级挂起、事实接力、动作延迟）属于规则系统；已承诺但未释放的动作作为执行依赖参与自然完成，不由 `total_duration` 或派生显示范围代替。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 保留 `meta.version` | 继续要求 3，未来再加 | 常量同步；不驱动任何行为；迁移靠外部 | 没有信息量 |
| 保留 `total_duration` 作为可选缓存 | 作者可写，运行时不信任 | 双口径；编辑器/作者可能写错；校验要比较 | 派生值唯一 |
| 用 `total_duration` 作为权威 | 片段窗口超出时截断 | 等待／分支／无限编排会被静态时长截断 | 执行事实唯一权威，有限末端只作派生信息 |
| 运行时兼容旧字段 | parser 双读/忽略 | 两套语义；旧格式永远清不掉 | 一次性迁移 |
| 加格式版本号区分 | `version=4` | 又回到版本字段；迁移仍需外部 | 不做版本 |
| 用 `contentEnd=∞`/大数表示无限 | 数值哨兵代替“无有限末端” | 与“无有限末端”不同义；完成判据被数值污染 | 标志 + 单独有限显示范围 |
| `goto_clip` 只校非负/有限 | 去掉统计上界后不再查覆盖 | 任意空档/不可达目标被放行；丢失作者编排约束 | 保留目标可达/片段覆盖校验 |
| 把规则/曲线集合做成片段内隐式字段 | 继续用 `clip.rules` / clip 级 `curve` 承载结构 | 结构职责与字段容器混同；引用、重排与多段关系不可见 | 脚本级独立集合，条目引用片段内容 |

**本方案的缺点**：

- 删除 `total_duration` 后，任何需要“脚本总长”的外部工具都要自己派生；有限内容用 `contentEnd`，含无限时只能显示“无有限末端”。
- 含无限片段时没有有限 `contentEnd`，外部工具必须接受“无有限末端”；`finiteDisplayEnd` 只是显示范围，容易被误用为总长。
- `goto_clip` 仍要查目标可达/片段覆盖，规则目标必须显式可达；只靠时间范围不够。
- 自然完成与显示末端分离，调用方不能再用一个数判断结束；需要读执行记录。
- 旧字段错误会让未迁移脚本直接不可用；迁移必须先跑。
- 编辑器/模板/测试的触点很多，漏一处就会写回旧字段；验收要全仓 grep。
- 没有版本号后，未来破坏性改动只能靠字段存在性/迁移器识别；需要严格的“旧字段已移除”清单。

## 六、实现结果

状态：未实现

当前代码仍要求/写入 `meta.version` 和 `timeline.total_duration`；`Clip.getWindowEnd()` 对无限返回 `Float.MAX_VALUE`，`ScriptPlayer.contentEnd()` 返回 `NaN`；`goto_clip` 仍用 `getTotalDuration()` 做上界；规则仍是 `clip.rules` 片段元数据，曲线仍是 clip 级单份，旧字段拒绝与脚本级集合未实现。这些是旧实现，不是本设计已落地。

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 删除 version/total_duration | 未实现；parser/validator/schema/editor 仍要求/写入 | 本设计未落地 |
| 有限末端/无限标志/显示范围分离 | 未实现；`getWindowEnd` 用 `MAX_VALUE`、`contentEnd` 用 `NaN` | 数值哨兵仍在 |
| `goto_clip` 用有限末端 + 可达/覆盖约束 | 未实现；仍用 `getTotalDuration()` 做上界，`hasClipAt` 覆盖检查已在 | 上界来源未换，覆盖约束需保留 |
| 规则／曲线脚本级集合；旧 `clip.rules`、clip 级 `curve` 按已移除／迁移处理 | 未实现；规则仍是 `clip.rules` 片段元数据，曲线仍是 clip 级单份；parser 仍读旧载体 | 目标结构与迁移未落地 |

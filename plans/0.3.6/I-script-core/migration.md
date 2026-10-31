# 一次性迁移（旧脚本 → 对象模型）

> 状态：未实现
> 范围：旧 `keyframes` 快照 → `objects`、复合值展开、稀疏字段保真、旧 `curve` 逐段展开为脚本级路径集合、旧 `clip.rules` → 脚本级规则集合、`orient`/offset 保留、`meta.version`/`timeline.total_duration` 删除、AUDIO `loop` 改名、EVENT 记录化、验收/回滚
> 关联：`object-model.md`、`loop-execution.md`、`format-cleanup.md`、`I-script-core/rule-system.md`（规则新方向专篇，未完成轮次；旧 `C-trigger/condition-system.md` 仅作 v1 实现参照）、各 `track-*.md`

## 一、摘要

迁移采用一次性、离线、可回滚的转换：旧共享快照拆成采样属性，旧片段路径按各实际区间转换为**脚本级路径集合**的条目（每条引用目标片段与目标位置序列中的两个相邻关键帧），旧片段级 `clip.rules` 转换为**脚本级规则定义 + 绑定**（绑定引用目标片段与执行局部时间门），运行激活不写入脚本。保真范围必须区分：保持语义的字段与数学路径做旧／新对照；明确改变的朝向混合、过渡效果归属、规则生命周期与规则阶段（实例级持有、成功事实、动作延迟、事实接力）按新目标报告差异，不通过修正关键帧或伪造规则状态重新植入旧语义。动态输入无法静态等值时报告人工复核，不承诺对未知运行环境完全等价。具体结构引用与序列化在目标格式评审后确定，新运行时不保留旧格式分支。

## 二、为什么做

### 2.1 问题

- 223 个脚本 / 813 个 clip / 2411 个关键帧都是共享快照；手工拆对象不现实，且容易改变运动。
- 旧运行时对缺失字段取字段默认值，新模型对缺失对象取对象值/默认值；机械拆 map 会让稀疏字段从“默认回落”变成“hold/插值”，静默改变画面。
- `position`/`dest`/`source`/`color` 是复合值；旧运行时按端点整体求值/插值，新模型按叶子采样；直接拆会改变曲线、position_origin、look_at、混合段。
- `curve` 在旧运行时是 clip 级一份 3D 控制点，隐式套到每个 from→to 段；新设计是脚本级路径集合，每条条目引用目标片段与目标位置序列中的两个相邻关键帧，各自持有 geometry。只写一份控制点不能覆盖多个旧段；按轴拆成 1D 曲线又会改变路径形状/速度。路径条目的序列化/引用待评审。`orient`/offset 保留，但前置条件变为显式。
- `clip.rules` 在旧运行时是片段级数组，规则随片段活跃窗建槽；新设计是脚本级规则定义 + 绑定，绑定引用目标片段与执行局部时间门，并区分判定成功、动作调度与完成。旧规则没有实例级判定持有、成功事实、延迟动作或事实接力；这些不能被迁移器凭空补出。
- `meta.version`/`timeline.total_duration` 删除后，旧脚本不能直接加载；必须一次性转换。
- AUDIO 的 `loop` 改名 `file_loop`，旧字段不能双读。

### 2.2 目标

- 转换结果通过目标 parser／validator；语义保持路径在容差内对照一致，明确设计变更单列报告，不要求与旧实现错误等值。
- 复合值展开为叶子；仅对可保真的稀疏字段生成修正数据，超界关键帧保留但不参与；不得将动态输入烘焙成某次采样环境的固定轨迹。
- 旧片段路径按实际区间转换为脚本级路径条目，分别保留目标片段、端点、控制点与弧长含义；同一片段的多条旧段各成一条，不合并成一份。`orient`／offset 和动态点源保留语义声明。新模型明确改变的求值／生命周期行为不得以修正帧伪装成已保真。
- 旧 `clip.rules` 转换为脚本级 RuleDefinition + RuleBinding：旧条件/动作/策略成为定义，旧片段的规则生效位置成为绑定的目标片段与执行局部时间门；RuleActivation 不写入脚本。旧规则没有的新阶段（实例级持有、成功事实、动作延迟、事实接力）报告为“未迁移能力”，不伪造状态。
- 普通片段与分支片段按同一对象 schema 迁移；分支只影响执行来源/编排去向，不生成第二套内容格式。
- 转换器可重复运行（对新格式幂等/跳过）、可 dry-run、可回滚、输出报告。
- 迁移后脚本体积不超过 4 MiB 解压包限制；超限时报告并给出压缩/分段建议。
- 不保留旧格式兼容层；转换器是唯一旧格式入口。

### 2.3 非目标

- 不做运行时双格式/旧字段兼容。
- 不手工重写 223 个脚本；只处理转换器无法保真的少数动态片段（报告 + 人工复核）。
- 不改变脚本作者意图（镜头运动/命令/音频）；只改数据形态。
- 迁移本身不新增规则能力；旧规则按 RuleDefinition/RuleBinding 迁移，RuleActivation 不写入脚本，旧数据没有的阶段不伪造。
- 不把普通片段/分支片段迁成两套内容格式，不强制全局片段库或新 sequence 库；分支用途只影响编排去向。
- 不做编辑器内“打开旧格式”；迁移先于编辑器。

## 三、方案

### 3.1 转换器形态

- 位置：`tools/script-migrate/`（Java CLI），与运行时分离；可以复用当前 `ScriptParser`/`KeyframeInterpolator` 的**冻结副本**做旧格式读取/对照，但不被运行时依赖。
- 输入：`cinematics/**/*.json`（默认 `cinematics/0.3.6/tests` + `release` + `demo` + `mod_showcase`）。
- 输出：同路径覆盖（先备份）或 `--out` 目录；`--dry-run` 只报告；`--check` 只做保真校验；`--backup` 默认开。
- 报告：每个脚本的 clip/keyframe 数、对象数、插入的修正关键帧数、曲线按旧段展开/对应项数/复核项、最大误差、体积、警告/需人工复核项。
- 退出码：0 全部成功；1 有需人工复核/超差；2 结构错误。
- 幂等：输入已含 `objects` 且无 `keyframes` → 跳过（报告“已是新格式”）；输入同时含两者 → 错误。
- 回滚：备份目录保留原文件；失败不覆盖；成功也保留备份直到人工确认。

### 3.2 转换步骤

1. **读取/校验旧格式**：`meta.version==3`、`timeline.total_duration` 存在、clip `keyframes` 数组、时间严格递增、字段类型符合旧 schema。旧格式错误直接失败，不“修复”。
2. **元数据**：删除 `meta.version`、`timeline.total_duration`；保留 `meta` 其他字段、`timeline.tracks`/宏观循环参数。
3. **clip 外层字段**：
   - 只保留播放控制字段：`start_time`/`duration`/`loop`/`loop_count`/`loop_mode`；不把 `rules` 固定为 clip 外层目标字段，规则按脚本级 RuleDefinition/RuleBinding 结构迁移，RuleActivation 不写入脚本；非阻塞观察不阻止片段自然完成。
   - `transition*`/`dimension`/`mask_shape`/`motion_*`/`modifier`/`suppress_modifiers`/`cam_breath_*`/调色曲线/LUT/`blend_mode`/`orient`/offset 等作用于片段的普通数据进 `objects`；是否带 keyframes 由 schema `mode` 决定。路径条目属于脚本级集合，不进 `objects`。
   - AUDIO `loop` → `file_loop`；AUDIO 片段执行循环用公共 `loop`/`loop_count`。
   - 旧 EVENT/MOD_EVENT 的 clip 级 `event_type`/`data` 迁为 objects 的 value-only 字段，作为事件记录的默认值。
4. **对象展开**：
   - 标量字段：路径不变（`yaw`、`opacity`、`mask_x`、`mix_rr`、`lut_strength`……）。
   - `position`：按旧 map 的键确定 mode/space，拆成 `position.x/y/z` + `position_mode` + `position_space`。
   - `position_origin`：旧 `facing_origin` 非空 → `position_origin`；空串不写；`facing_origin_x/y/z` → `position_origin_x/y/z`；旧 `facing_origin_time` 仅实体选择器转 `position_origin_freeze`（`snapshot` → `true`，`live`/缺省 → 不写，即 `false`），静态来源丢弃并报告。
   - 旧 `facing_target_time`/`yaw_base_from_time`/`yaw_base_to_time`/`look_at_time` 仅实体选择器转 `facing_target_freeze`/`yaw_base_from_freeze`/`yaw_base_to_freeze`/`look_at_freeze`（`snapshot` → `true`，`live`/缺省 → 不写）；静态来源丢弃并报告。
   - 旧 `follow=entity` → `position_origin=<follow_selector>` + `position_origin_freeze=false`；旧 `follow=none` 不生成 `position_origin`；旧 `follow`/`follow_selector` 不再写出。
   - 旧 `follow=entity` 且 `facing_origin` 非空：两个位置点源；默认以 `follow_selector` 为 `position_origin`，旧 `facing_origin` 的差异报告人工复核，不静默丢。
   - 旧 `base` 段 `facing_target` 为空：旧方向源 = 旧运行时实际解析结果（原点实体 / 隐式 `@p` / follow 实体 / 玩家）；迁移写 `facing_source=entity`，旧选择器写进 `facing_entity`（原点本身就是该实体/玩家时可省略），固定旧方向；报告新增方向源。
   - 选择器调用点：`selector_*_follow` / `selector_*_facing_origin` → `selector_*_position_origin`；两组同时存在时报告冲突，按 `follow` 优先，人工复核。
   - 旧 `selector_*_follow` 若 `facing_entity` 取自 follow selector，同一组策略复制到 `selector_*_facing_entity`，保持旧跟随目标锁定行为；其余情况 `facing_entity` 用通用策略并报告。
   - `dest`/`source`：拆 `.x/.y/.w/.h`。
   - `color`（OVERLAY fade）：拆 `color.r/g/b`（0~1）。
   - `look_at_part`：拆 `.x/.y/.z`。
   - `look_at_offset`/`facing_target_offset`/`yaw_base_from_offset`/`yaw_base_to_offset`：拆 `.x/.y/.z` + `.space`（world/base）。
   - `curve`：旧 clip 级 3D 控制点按旧段逐段迁移为**脚本级路径集合**条目。对每个相邻旧全局关键帧段（from→to）生成一条条目：目标片段 = 该旧 clip；端点 = 该片段目标位置序列中的 from/to 关键帧（该时刻 `position` 缺失时按旧默认/回落求值，必要时 materialize 成位置组（三轴同时间关键帧））；两个控制点按旧定义复制（相对控制点相对该段起点；绝对控制点原样）；保留 3D 弧长，不拆轴、不烘焙、不合并；geometry 与 timing 分离。同一片段的多条旧段各成一条，不限制条数。无路径条目覆盖的段正常按 `position` SampledProperty 插值；不要求整片段三轴全局成组。路径条目的序列化/引用待评审；`objects.curve` 与片段级 `curve` 只是前稿/现行实现参照，当前代码是 `clip.getCurve` 单份曲线。旧段任一端 `follow=entity` 时旧运行时忽略 curve（线性）；迁移对该段不写路径条目，报告；新格式动态段可用路径条目。
   - 旧 curve 在位置转世界后求值：相对控制点是世界轴偏移，绝对控制点是世界坐标。`world` 段可直接复制；`base` 段新路径在 base 空间求值，静态 base 可换算，动态 base 无法预计算 → 报告人工复核，不静默改形状。
   - `orient`：value-only；`yaw_offset`/`pitch_offset`：旧 clip 常量写成 value-only 或单值 animated，保留 `requires=orient=tangent`。
   - `transition*`/morph：旧 clip 的过渡字段迁为 objects 的 value-only/animated 普通属性；新运行时按每侧完整状态快照混合，窗口 = 交界 ± maxDuration/2（3 秒窗口 = 前后各 1.5 秒），迁移不烘焙过渡帧、不生成过渡快照。
   - 其他 map（`data`/`gradient`/`modifier`/调色曲线）不拆；它们是 value-only 结构或离散事件记录。
5. **规则迁移**：
   - 旧 `clip.rules[]` 每条规则 → 脚本级 RuleDefinition（内层条件树、动作、策略）+ 一个 RuleBinding（目标片段 = 该旧 clip）。RuleActivation 不写入脚本。
   - 绑定时间门：旧规则若已有可提取的时间条件，取该局部时间值作为门；否则以该片段执行起点 0 作为门，保留原条件树。时间门使用片段执行局部时间；关键帧只提供该局部时间点，不作为独立激活机制。旧规则“随片段活跃窗生效”的语义与新绑定生命周期差异单列报告，不静默宣称等价。
   - 旧 `if`/`until`、once、cooldown、dwell、timeout、on_timeout 等保留为定义/策略方向；旧数据没有的实例级判定持有、判定成功事实、动作延迟、事实接力不补写，报告为“未迁移能力”。
   - 同一旧片段多条规则各成一条定义/绑定；不同片段复用相同规则不合并定义，除非可证明条件/动作完全一致且不共享运行态。若旧规则可提取多个独立时间条件，各成独立绑定，不把多个“时间 >= t”OR 成最早入口。
   - 规则动作目标若指向片段，按稳定片段引用表达；旧 `goto_clip` 的时间寻址在目标片段标识体系定稿前保留原语义并报告。
6. **稀疏字段保真**：见 §3.4。
7. **动态相机保真**：见 §3.5。
8. **EVENT/MOD_EVENT**：旧关键帧 → `event`/`mod_event` 对象记录；`command`/`position`/`event_type`/`data` 放入记录；一条旧关键帧 = 一个事件。
9. **写出**：按稳定顺序输出（tracks 顺序、clip 顺序、objects 路径排序或 schema 顺序）；不写 version/total_duration；不写空对象/默认值（可省略）。
10. **校验**：用新 parser/validator 读回；用旧 evaluator 与新 sampler 对照；输出报告。

### 3.3 对象构造

- 每个对象收集旧关键帧中的出现值；对象 `value` 取旧 clip 级基量/字段默认（如果存在），否则省略。
- 旧关键帧字段缺失：
  - 如果该字段在整个 clip 从未出现 → 不创建对象；新模型用 schema 默认，等价。
  - 如果该字段出现过 → 进入稀疏保真流程；不能简单只写出现点。
- 同一对象内时间严格递增；旧关键帧时间已严格递增，展开后同一字段的时间仍递增；重复时间错误。
- 超界关键帧（有限片段 `time<0` 或 `time>duration`；无限执行 `time<0`）保留在对象 keyframes 中；新 parser 加载后忽略，不清理、不报错。
- `yaw_lock`/`pitch_lock`：旧字段缺失 = 不锁。若 clip 内出现过锁值，创建 `nullable_float` 对象，在每个旧全局关键帧处写 `null` 或数值；若从未出现，不创建。
- `zoom`：`interp=geometric`；旧值 >0；若旧值 ≤0，转换器报错/警告（旧运行时可能回退，不能静默改）。
- `color.r/g/b`：旧 clip 级 `color` 作为动态默认；对象只在旧关键帧有 `color` 时创建；未创建的通道回落 value-only 基色。
- `volume`：旧 clip 级 `volume` 作为动态默认；对象只在旧关键帧有 `volume` 时创建。
- 曲线端点 materialize：旧 `curve` 的段边界是**全局**关键帧，不是只在 `position` 出现的时刻。若某段端点时刻 `position` 未出现，转换器按旧有效值（存在值或默认）写三轴 `position` 关键帧作为路径条目端点；这是“目标位置序列中两个相邻关键帧”的端点对应要求，只 materialize 端点，不要求全片段位置帧成组，也不是把曲线烘焙成采样点或普通稀疏修正帧。路径条目的引用/序列化待评审。

### 3.4 稀疏字段保真

旧运行时的行为：

- 每个全局关键帧是一个稀疏快照；字段缺失时，该字段在该端点取 schema 默认值。
- 相邻两个**全局**关键帧之间，字段从 `from` 端点的有效值（存在值或默认）插值到 `to` 端点的有效值。
- 因此，一个字段即使只在 t=0 出现，t=1/2/3 的全局关键帧都会把它拉回默认；不是“hold 到下一次出现”。

新模型的行为：

- 对象只在自己的 keyframes 上插值；缺失的时间不会自动回落默认。

保真算法（逐对象）：

1. 取旧 clip 的所有全局关键帧时间 `T`（范围内）作为候选时间。
2. 取该对象在旧关键帧中的出现时间 `S ⊆ T`。
3. 以 `S` 为初始 keyframes（值 = 旧值）。
4. 对每个 `t ∈ T`，用旧 evaluator 算旧有效值 `old(t)`；用新 sampler 算 `new(t)`。若 `|old-new| > ε`，在 `t` 插入旧值 keyframe。路径段内 `position` 的差异不能用“插位置点并复用旧控制点”来修：只能调整路径条目的段级 geometry（端点/控制点）或报告；路径条目的结构契约待评审，迁移不做静默重建。
5. 再在相邻候选时间的中点/固定采样网格上重复比较；若差异超差，插入修正帧；路径段内 `position` 同样按上一条处理，不用复用旧控制点的方式伪造保形；直到所有检查点满足容差或达到迭代上限。
6. 对线性对象，若插入点落在相邻两点连线上（误差 < ε），可删除该冗余点（无损压缩）；对 step 对象，连续同值可合并。
7. 输出对象 keyframes；记录插入/压缩数量。

- 容差：位置 < 1e-3 格；角度 < 0.01°；透明度/颜色/调色通道 < 1e-4；音量 < 1e-4；矩形/比例 < 1e-5。可按对象类型配置。
- 路径段内的位置采样不是自由关键帧：`old(t)` 与 `new(t)` 的差异只能通过路径条目的段级 geometry（端点/控制点/段划分）解释或报告；不能靠插段内位置点“修”曲线，也不能用复用旧控制点的方式冒充保形。路径条目的结构契约待评审。
- 这套算法对“字段只在部分全局关键帧出现”的脚本是保真的；对“字段在整个 clip 从未出现”的对象不创建，等价默认。
- 如果旧字段值本身是非有限/非法，转换器报错；不尝试修复。

### 3.5 相机位置/路径/朝向

旧相机按复合端点求值，新模型按独立属性与路径条目装配。迁移保留仍有效的路径与参数含义，但不假定两套求值在所有场景等价；动态或明确改变的部分必须分类处理：

| 旧特性 | 迁移策略 |
|---|---|
| 静态 `position`（absolute world / relative world/base，无 `position_origin` 动态） | 按 §3.2 拆叶子；用 §3.4 保真；不把世界位置烘焙成新关键帧，保留原 `position_mode`/space |
| 旧 `follow=entity`／位置点源／选择器 | 归一到新点源与冻结声明，保留动态来源；可静态等值的部分做对照，无法保证的动态差异报告复核，不固定成某次运行坐标 |
| `look_at` 动态目标 | 保留注视目标与选择器，不并入位置点源；目标模型按实际状态计算朝向，不能用手动角度修正帧绕过 look_at 的覆盖语义 |
| `curve` | 逐旧段迁移为脚本级路径条目：每个旧全局关键帧段（from→to）生成一条条目，目标片段 = 该 clip，端点 = 该 from/to 的旧有效位置（必要时仅对端点 materialize 成位置组）；控制点按旧定义复制（相对控制点相对该段起点，绝对控制点原样）；保留 3D 弧长；不拆轴、不烘焙、不合并。路径条目的序列化/引用待评审。`world` 段可直接复制；`base` 段需换算到 base 空间，动态 base 无法预计算时报告人工复核 |
| `orient=tangent` / `yaw_offset` / `pitch_offset` | 原样保留：`orient` 是 value-only 字段；`yaw_offset`/`pitch_offset` 是 animated 字段（旧 clip 常量写成 `value`，无 keyframes），前置条件 `orient=tangent`；不烘焙成 yaw/pitch 关键帧 |
| 至少一端 `look_at` 启用的混合段伪目标 | 两端 none 的角度插值可继续对照；混合段的旧伪目标仅为基线，新目标按实际朝向状态处理。差异作为设计变更报告，不宣称插角度帧即可保持动态目标下完全等值 |
| morph 两侧片段效果 | 保留各侧效果声明；混合前分别求值是目标变更，不生成关键帧模拟旧 next motion／prev breath 装配；报告受影响片段 |
| v1 规则元数据 | 提取为脚本级 RuleDefinition + RuleBinding：旧规则条件/动作/策略成定义，旧片段成为绑定的目标片段；可提取的局部时间条件作为时间门，否则用执行起点 0。旧窗口不能自动成为新生命周期；实例级持有、成功事实、动作延迟、事实接力是旧数据没有的能力，列入“未迁移”复核，不伪造状态 |
| `yaw_base=entity/line` 动态基准 | 保留 `yaw_base*` 字段；若旧端点求值与新逐帧不同，插入修正关键帧；动态实体无法预计算时标记人工复核 |

- 旧 `curve` 是 3D 曲线；迁移逐旧段保留同一条 3D 贝塞尔：每段自己的端点/控制点/相对绝对/3D 弧长不变，不做按轴拆分，不合并成一份。段边界 = 旧全局关键帧；端点缺失 `position` 时 materialize 旧有效位置。新条目的端点相邻性只在目标位置序列内判断，旧全局关键帧只用于确定旧段边界，不成为新运行时把其它对象帧并入的总编号。不是“一份控制点写一次”就算迁移完成；路径条目的序列化/引用待评审。
- 旧段任一端 `follow=entity` 时旧运行时忽略 curve（线性）；迁移对该段不写路径条目 geometry，报告；新格式动态段可用路径条目，但不回填旧 follow 段。
- 2 轴组合只会在作者显式写两个轴时出现；迁移器不会把旧 3D 曲线降成 2 轴。2 轴路径条目只保证该平面内 2D 弧长，不承诺 3D 匀速；未选轴仍按自身 SampledProperty 求值。见 `track-camera.md` §3.2。
- `orient=tangent` 的切线由当前路径条目的路径导数与未选轴 SampledProperty 导数合成；迁移后与旧切线对拍。
- 旧 `absolute + follow=entity`：旧实现 follow 优先，`position` 实际是实体脚底偏移；迁移归一为 `relative + world + position_origin=<follow_selector> + position_origin_freeze=false`，保留偏移，不保留 `absolute`。
- 旧 `relative/base + follow=entity`：旧实现 base 原点走 `facing_origin`；新设计原点=跟随实体脚底。若旧 `facing_origin` 为空或不是跟随实体，迁移报告人工复核，不静默改原点。
- 旧 `relative/base` 且 `facing_target` 为空：旧方向源 = 旧运行时实际解析结果（原点实体 / 隐式 `@p` / follow 实体 / 玩家）；迁移写 `facing_source=entity`，旧选择器写进 `facing_entity`（原点本身就是该实体/玩家时可省略），固定旧方向；报告新增方向源。
- `position_origin` 实体选择器 `position_origin_freeze=false` 是动态点源，`true` 是静态点源；`follow=none` 不代表静态。迁移保留点源冻结状态，不按字段名猜静/动。
- 旧 `position_mode`/`follow`/`facing_origin`/`facing_origin_time` 切换，或 `base` 下 `facing_target`/`facing_target_time` 切换：迁移时在切换点插三轴位置关键帧；新格式对应 `position_mode`/`position_origin`/`position_origin_freeze`（base 下还有 `facing_source`/`facing_target`/`facing_target_freeze`/`facing_entity`）切换；base 轴没有独立角度偏移字段；路径条目与切换点的关系按结构契约处理，迁移不静默改绑，必要时报告人工复核。
- 迁移报告列出路径按旧段展开数量/每段端点与控制点对应、`base` 段换算/动态复核清单、`orient`/offset 保留情况；不再有“烘焙采样数/30Hz”。

### 3.6 EVENT / MOD_EVENT

- 旧 EVENT clip：每个关键帧 → `event` 对象的一个 keyframe：
  - `value.event_type` = 旧 `event_type` 或 clip 默认 `command`；
  - `value.command` = 旧 `command`（空字符串保留为占位）；
  - `value.position` = 旧 `position` map（`{x,z}`/`{dx,dz}`）原样放入记录。
- 旧 MOD_EVENT clip：每个关键帧 → `mod_event` 对象记录（`event_type`/`data`）。
- 事件时间保留；超界事件保留但新 runtime 忽略；重复时间错误。
- 事件身份不在脚本里；新 runtime 用 `(instance, clip, objectPath, eventIndex)`。

### 3.7 `meta.version` / `total_duration`

- 删除；不写替代字段。
- 旧 `total_duration` 只在内容静态有限时与有限末端估计比较；含无限内容时单列“旧总长 vs 无有限自然末端”差异。转换器只报告，不改片段，不把旧总长写成新控制时长或完成判据。
- 旧 `total_duration` 若小于片段窗口，旧运行时会截断/hold；新有限末端估计按实际内容/片段窗口推导，不能用旧总长或 `max(windowEnd)` 驱动消费。报告差异，人工确认；不把旧值写进新格式。

### 3.8 AUDIO `loop`

- 旧 AUDIO clip 的 `loop`（bool）→ `file_loop`。
- 如果旧 AUDIO clip 同时有公共 `loop`/`loop_count`（片段执行循环），保留公共字段；两者独立。
- 旧 AUDIO `loop=true` 且 `duration` 很长：新 `file_loop=true`，执行循环不变。

### 3.9 输出顺序/格式

- `objects` 键按 schema 声明顺序（或路径字典序）输出，保证 diff 稳定。
- 对象内 keyframes 按时间升序；不写重复时间。
- 省略 `value` 等于 schema 默认且无 keyframes 的对象；省略空 `keyframes`。
- JSON 缩进/换行沿用现有风格；不重排 tracks/clips。
- 不写 `meta.version`、`timeline.total_duration`、AUDIO `loop`（改 `file_loop`）。
- 不写 `follow`/`follow_selector`/`facing_origin`/`facing_origin_x/y/z`；统一写 `position_origin`/`position_origin_x/y/z`/`position_origin_freeze`。
- 不写旧 `_time` 字段（`facing_origin_time`/`facing_target_time`/`yaw_base_from_time`/`yaw_base_to_time`/`look_at_time`）；统一写对应 `_freeze` 布尔。
- 旧 `base` 段补出的方向源写 `facing_source=entity` + `facing_entity`（原点已是该实体/玩家时可省）；旧 `facing_target` 写 `facing_source=line` 并保留目标；不写不存在的 `facing_target`/`facing_entity` 空值。
- 不写 `selector_*_follow`/`selector_*_facing_origin`；统一写 `selector_*_position_origin`；需要时复制到 `selector_*_facing_entity`。
- 写脚本级路径集合：每个旧全局关键帧段生成一条条目，保留目标片段引用 + 端点对应 + 控制点 + 3D 弧长；路径条目的序列化/引用待评审，不写旧 clip 级 `curve` 形态，也不把一份控制点套到多个段。
- 写脚本级规则集合：旧 `clip.rules` 转为 RuleDefinition + RuleBinding（目标片段 + 时间门），RuleActivation 不写；旧数据没有的实例级持有/成功事实/动作延迟/事实接力不写。

### 3.10 报告与人工复核

报告字段：

- 脚本路径、clip 数、旧 keyframe 数、新字段数、修正关键帧数；
- 最大位置/角度/透明度/颜色/音量误差（曲线按旧段展开，不再有按轴拆分的路径差异；`base` 动态段单独列误差/复核）；
- 曲线按旧段展开数量/每段端点与控制点对应、`base` 段换算/动态复核清单、`orient`/offset 保留情况；
- 旧 `total_duration` vs 有限末端估计差异（含无限内容单列“无有限自然末端”）；
- 动态 position_origin/选择器片段清单（人工复核）；
- 旧 `base` 段补出 `facing_source` / `facing_entity` 的清单（方向源来源、原选择器、是否复制 selector 策略）；
- 规则迁移清单：定义/绑定数量、时间门来源（旧时间条件或片段起点 0）、动作目标片段、旧 `if`/`until`/once/cooldown/dwell/timeout 保留情况；旧数据没有的实例级判定持有、判定成功事实、动作延迟、事实接力列为“未迁移能力”。
- 明确语义变更清单：混合段朝向、morph 片段效果归属、规则生命周期／观察范围／动作时效／成功与完成事实、分支正常后继与失败 fallback；分别记录可自动转换与必须复核的内容，不混入普通数值误差。
- 体积（压缩前/后）、是否接近 4 MiB；
- 旧字段/非法值/重复时间/非有限值错误。

人工复核只针对报告标记的片段；大多数静态脚本应自动通过。

## 四、技术细节

### 4.1 核心模型

```text
MigrationJob
  oldScript
  newScript
  report
  parityChecker
```

- `OldScriptReader`：旧格式解析/校验（冻结副本）。
- `OldEvaluator`：旧 `KeyframeInterpolator`/相机求值（冻结副本），用于 `old(t)`。
- `NewSampler`：新对象采样器（与运行时同源），用于 `new(t)`。
- `ParityChecker`：在 `T` + 采样网格上比较；输出差异/插入点。
- `ObjectBuilder`：按字段映射构建对象；处理稀疏/复合/锁/颜色。
- `CurveBuilder`：旧 `BezierPathStrategy`/`TangentOrientation` 采样；按旧段生成脚本级路径条目（目标片段/端点/控制点/3D 弧长），序列化/引用待评审，`base` 段控制点换算/复核。
- `RuleBuilder`：旧 `clip.rules` → RuleDefinition + RuleBinding；提取可用的局部时间门，保留旧条件/动作/策略；旧数据没有的阶段不补写。
- `ReportWriter`：JSON/Markdown 报告。

### 4.2 契约

- **创建**：CLI 启动时加载 schema/旧 reader/新 sampler；每个脚本一个 job。
- **销毁**：进程结束；不修改运行时状态。
- **状态归谁**：job 持有旧/新脚本/报告；不跨脚本共享可变状态。
- **边界**：转换器不调用运行时单例；不依赖客户端/服务端；可在无游戏进程的 JVM 跑。
- **幂等**：新格式输入跳过；不重复插入 keyframes。

### 4.3 数据形态

- 旧/新脚本都用 JSON tree 或强类型 AST；转换器不直接字符串替换。
- 对象 keyframes 的时间/值用旧 AST 的数值；不重新解析字符串。
- 复合 map 的键顺序不影响结果；`position` 的键集合决定 mode/space。
- 颜色 `#RRGGBB` → 0~1 float；保留 8 位精度（`/255`）。
- 报告用相对路径；备份目录带时间戳。

### 4.4 边界

- 不改变旧脚本的 tracks/clips 顺序；不合并/拆分 clip。
- 不改变 `start_time`/`duration`/`loop*`；只改数据形态。
- 不清理超界关键帧；保留，新 runtime 忽略。
- 不修复旧脚本的非法值；报错/人工处理。
- 不把动态实体目标烘焙成静态世界坐标；保留语义或标记复核。
- 不把旧规则迁移成旧数据没有的阶段（实例级判定持有、成功事实、动作延迟、事实接力）；只迁移可表达的定义/绑定并报告差异。
- 不把普通/分支片段迁成两套内容格式；分支用途只影响编排去向，内容模型不变。
- 迁移不定义正常完成默认/入口覆盖/失败 fallback；只迁移旧编排可表达的部分，去向语义归 `rule-system.md`。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 手工迁移 | 逐个脚本改 | 223 个脚本/2411 关键帧，易错、不可重复 | 转换器 |
| 运行时双格式 | parser 同时读 keyframes/objects | 两套语义/校验/编辑器；旧格式永不清 | 一次性迁移 |
| 只拆 map，不保稀疏 | 出现点建对象，缺失 hold | 静默改变运动；旧脚本画面变 | 修正关键帧保真 |
| 只写一份 `curve` 原样映射 | 旧 clip 级控制点复制成新格式一份 | 一份控制点无法表达多个旧段，段归属也不明确；插入关键帧会改变其余段 | 逐旧段迁移为脚本级路径条目，每条自己的目标片段/端点/控制点；序列化/引用待评审 |
| 按轴拆成三条 1D 曲线 | 每轴一条标量曲线 | 改变路径形状/速度；无法定义 2D/3D 弧长 | 逐段保留 3D 曲线 |
| 旧 `clip.rules` 原样搬成新字段 | 仍以片段数组承载规则 | 规则生命周期仍被片段窗口限制；多入口/成功事实/延迟无法表达 | 脚本级 RuleDefinition + RuleBinding；RuleActivation 运行期 |
| 迁移时补写旧数据没有的规则阶段 | 给旧规则自动加实例级持有/成功事实/延迟 | 改变作者原意；把未实现能力伪装成已迁移 | 只迁移可表达部分，新阶段列“未迁移能力” |
| 全量materialize 每个全局帧 | 每个对象在每个全局关键帧写值 | 体积爆炸；4 MiB 风险 | materialize + 无损压缩 + 修正帧 |
| 全部烘焙世界坐标 | 相机位置/朝向全采样 | 丢失 position_origin 动态；脚本变大 | 拆叶子 + 修正帧，动态保语义 |
| 迁移器放编辑器 | 编辑器内转换 | 依赖 WebUI/浏览器；批量/CI 难 | 独立 Java CLI |
| 保留旧 `total_duration` | 新格式继续写 | 与有限末端估计/有限显示范围双口径；含无限时无有限末端 | 删除 |

**本方案的缺点**：

- 保真算法复杂；稀疏字段/动态相机需要旧 evaluator 冻结副本，工具链多一套代码。
- 修正关键帧可能仍很多；4 MiB 限制下需要压缩/分段，报告可能要求人工处理。
- 旧 `curve` 逐段迁移后路径条目数量 = 旧全局关键帧段数；脚本会比“一份控制点”大，但目标片段/端点归属明确、可逐段编辑。3D 弧长保留；只有作者后续显式改成 2 轴组合时，才会出现 2D 弧长不承诺 3D 匀速的语义边界。路径条目的序列化/引用待评审，迁移输出形态要等结构契约定稿。
- 旧规则迁移只能保留定义/绑定与可提取的时间门；实例级持有、成功事实、动作延迟、事实接力等新阶段在旧数据中不存在，必须由作者后续显式声明，不能自动补全。
- 旧规则的时间门来源可能不唯一（旧时间条件/片段起点）；迁移需报告选择，不能把“窗口起点生效”静默解释成新的持续判定语义。
- `base` 段的旧控制点是世界轴定义，新曲线在 base 空间求值；静态 base 可精确换算，动态 base 只能报告/人工复核，不能保证逐点保真。
- 动态 position_origin/选择器无法完全预计算；人工复核不可避免。
- 旧 `total_duration` 与有限末端估计不一致（或含无限内容、没有有限末端）时，迁移只能报告，不能自动决定作者意图。
- 转换器/新 sampler 必须与运行时同源，否则“校验通过但游戏里不同”；需要共享 schema/采样代码。

## 六、实现结果

状态：未实现

| 设计目标 | 实现 | 说明 |
|---|---|---|
| 旧 clip 级 `curve` 逐旧段迁移为脚本级路径条目，每条目标片段/端点/控制点对应；旧 `clip.rules` → 脚本级 RuleDefinition + RuleBinding | 当前 `clip.getCurve` 返回单份曲线，旧运行时对每个 from→to 段隐式复用；`objects.curve` 从未实现；旧规则是片段级 `clip.rules`，没有定义/绑定/时间门分层 | 旧格式只有一份 `curve` 与片段级规则数组；脚本级结构是新增设计，序列化/引用待评审，迁移器尚未实现 |
| 旧规则迁移保留定义/绑定与可提取时间门；新阶段不伪造 | 转换器尚未实现规则迁移；旧规则没有实例级持有/成功事实/延迟/接力 | 迁移未实现 |
| 旧 `total_duration` 仅在有限时比较有限末端；含无限单列无有限末端 | 当前旧字段直接删除，转换器尚未实现有限/无限分口径报告 | 迁移未实现 |

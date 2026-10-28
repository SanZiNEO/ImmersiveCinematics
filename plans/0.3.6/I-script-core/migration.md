# 一次性迁移（旧脚本 → 对象模型）

> 状态：未实现
> 范围：旧 `keyframes` 快照 → `objects`、复合值展开、稀疏字段保真、`curve` 原样迁移、`orient`/offset 保留、`meta.version`/`timeline.total_duration` 删除、AUDIO `loop` 改名、EVENT 记录化、验收/回滚
> 关联：`object-model.md`、`loop-execution.md`、`format-cleanup.md`、各 `track-*.md`

## 一、摘要

迁移是一次性、离线、可回滚的转换：读取旧格式（`meta.version=3`、`timeline.total_duration`、clip 级 `keyframes` 快照），写出新格式（`objects`、无 version/total_duration）。转换器用旧运行时的求值结果做对照，优先保留语义对象（`position_origin`/选择器/`look_at`）；旧 3D `curve` 原样写入 `objects.curve`（`axes:["x","y","z"]`，控制点不拆），`orient`/offset 原样保留；`look_at=none` 与混合段保持现行语义，差异超过容差时插入修正关键帧。保真校验在旧全局关键帧时刻 + 密集采样点上做。迁移不改运行时；新 parser 只认新格式。

## 二、为什么做

### 2.1 问题

- 223 个脚本 / 813 个 clip / 2411 个关键帧都是共享快照；手工拆对象不现实，且容易改变运动。
- 旧运行时对缺失字段取字段默认值，新模型对缺失对象取对象值/默认值；机械拆 map 会让稀疏字段从“默认回落”变成“hold/插值”，静默改变画面。
- `position`/`dest`/`source`/`color` 是复合值；旧运行时按端点整体求值/插值，新模型按叶子采样；直接拆会改变曲线、position_origin、look_at、混合段。
- `curve` 在旧运行时是 3D 共享参数；新格式保留为一个 `curve` + 轴集合，不拆成三条 1D 曲线；按轴拆会改变路径形状/速度。`orient`/offset 保留，但前置条件变为显式。
- `meta.version`/`timeline.total_duration` 删除后，旧脚本不能直接加载；必须一次性转换。
- AUDIO 的 `loop` 改名 `file_loop`，旧字段不能双读。

### 2.2 目标

- 旧脚本在迁移后可被新 parser/validator 加载，播放结果与旧运行时在容差内一致。
- 复合值展开为叶子；稀疏字段用修正关键帧保真；超界关键帧保留但不参与。
- 旧 3D `curve` 原样迁移为 `axes:["x","y","z"]`，控制点不拆；`orient`/offset 原样保留；`look_at=none`/混合段保持语义并用修正帧对齐；动态 `position_origin`/选择器保留语义对象。
- 转换器可重复运行（对新格式幂等/跳过）、可 dry-run、可回滚、输出报告。
- 迁移后脚本体积不超过 4 MiB 解压包限制；超限时报告并给出压缩/分段建议。
- 不保留旧格式兼容层；转换器是唯一旧格式入口。

### 2.3 非目标

- 不做运行时双格式/旧字段兼容。
- 不手工重写 223 个脚本；只处理转换器无法保真的少数动态片段（报告 + 人工复核）。
- 不改变脚本作者意图（镜头运动/命令/音频）；只改数据形态。
- 不迁移 `meta.version` 之外的元数据语义；不新增触发器/规则。
- 不做编辑器内“打开旧格式”；迁移先于编辑器。

## 三、方案

### 3.1 转换器形态

- 位置：`tools/script-migrate/`（Java CLI），与运行时分离；可以复用当前 `ScriptParser`/`KeyframeInterpolator` 的**冻结副本**做旧格式读取/对照，但不被运行时依赖。
- 输入：`cinematics/**/*.json`（默认 `cinematics/0.3.6/tests` + `release` + `demo` + `mod_showcase`）。
- 输出：同路径覆盖（先备份）或 `--out` 目录；`--dry-run` 只报告；`--check` 只做保真校验；`--backup` 默认开。
- 报告：每个脚本的 clip/keyframe 数、对象数、插入的修正关键帧数、curve 原样迁移片段数、最大误差、体积、警告/需人工复核项。
- 退出码：0 全部成功；1 有需人工复核/超差；2 结构错误。
- 幂等：输入已含 `objects` 且无 `keyframes` → 跳过（报告“已是新格式”）；输入同时含两者 → 错误。
- 回滚：备份目录保留原文件；失败不覆盖；成功也保留备份直到人工确认。

### 3.2 转换步骤

1. **读取/校验旧格式**：`meta.version==3`、`timeline.total_duration` 存在、clip `keyframes` 数组、时间严格递增、字段类型符合旧 schema。旧格式错误直接失败，不“修复”。
2. **元数据**：删除 `meta.version`、`timeline.total_duration`；保留 `meta` 其他字段、`timeline.tracks`/宏观循环参数。
3. **clip 外层字段**：
   - 只保留播放控制/结构：`start_time`/`duration`/`loop`/`loop_count`/`loop_mode`/`rules`。
   - `transition*`/`dimension`/`mask_shape`/`motion_*`/`modifier`/`suppress_modifiers`/`cam_breath_*`/调色曲线/LUT/`blend_mode`/`orient`/`curve`/offset 等作用于片段的字段进 `objects`；是否带 keyframes 由 schema `mode` 决定。
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
   - `curve`：旧 3D `control_points` 原样写入 `objects.curve`，`axes` 写 `["x","y","z"]`；旧共享关键帧天然三轴成组，满足新约束；控制点相对/绝对不变，不拆、不烘焙。旧段任一端 `follow=entity` 时旧运行时忽略 curve（线性）；迁移对该段不写 curve（或标记线性），报告；新格式动态段可用 curve。
   - `orient`：value-only；`yaw_offset`/`pitch_offset`：旧 clip 常量写成 value-only 或单值 animated，保留 `requires=orient=tangent`。
   - 其他 map（`data`/`gradient`/`modifier`/调色曲线）不拆；它们是 value-only 结构或离散事件记录。
5. **稀疏字段保真**：见 §3.4。
6. **动态相机保真**：见 §3.5。
7. **EVENT/MOD_EVENT**：旧关键帧 → `event`/`mod_event` 对象记录；`command`/`position`/`event_type`/`data` 放入记录；一条旧关键帧 = 一个事件。
8. **写出**：按稳定顺序输出（tracks 顺序、clip 顺序、objects 路径排序或 schema 顺序）；不写 version/total_duration；不写空对象/默认值（可省略）。
9. **校验**：用新 parser/validator 读回；用旧 evaluator 与新 sampler 对照；输出报告。

### 3.3 对象构造

- 每个对象收集旧关键帧中的出现值；对象 `value` 取旧 clip 级基量/字段默认（如果存在），否则省略。
- 旧关键帧字段缺失：
  - 如果该字段在整个 clip 从未出现 → 不创建对象；新模型用 schema 默认，等价。
  - 如果该字段出现过 → 进入稀疏保真流程；不能简单只写出现点。
- 同一对象内时间严格递增；旧关键帧时间已严格递增，展开后同一字段的时间仍递增；重复时间错误。
- 超界关键帧（`time<0` 或 `time>duration`）保留在对象 keyframes 中；新 parser 加载后忽略，不清理、不报错。
- `yaw_lock`/`pitch_lock`：旧字段缺失 = 不锁。若 clip 内出现过锁值，创建 `nullable_float` 对象，在每个旧全局关键帧处写 `null` 或数值；若从未出现，不创建。
- `zoom`：`interp=geometric`；旧值 >0；若旧值 ≤0，转换器报错/警告（旧运行时可能回退，不能静默改）。
- `color.r/g/b`：旧 clip 级 `color` 作为动态默认；对象只在旧关键帧有 `color` 时创建；未创建的通道回落 value-only 基色。
- `volume`：旧 clip 级 `volume` 作为动态默认；对象只在旧关键帧有 `volume` 时创建。

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
4. 对每个 `t ∈ T`，用旧 evaluator 算旧有效值 `old(t)`；用新 sampler 算 `new(t)`。若 `|old-new| > ε`，在 `t` 插入旧值 keyframe。
5. 再在相邻候选时间的中点/固定采样网格上重复比较；若差异超差，插入修正帧；直到所有检查点满足容差或达到迭代上限。
6. 对线性对象，若插入点落在相邻两点连线上（误差 < ε），可删除该冗余点（无损压缩）；对 step 对象，连续同值可合并。
7. 输出对象 keyframes；记录插入/压缩数量。

- 容差：位置 < 1e-3 格；角度 < 0.01°；透明度/颜色/调色通道 < 1e-4；音量 < 1e-4；矩形/比例 < 1e-5。可按对象类型配置。
- 这套算法对“字段只在部分全局关键帧出现”的脚本是保真的；对“字段在整个 clip 从未出现”的对象不创建，等价默认。
- 如果旧字段值本身是非有限/非法，转换器报错；不尝试修复。

### 3.5 相机位置/曲线/朝向

旧相机不是“逐叶子采样”，而是端点复合求值。I 轮保留 `curve`/`orient`/offset，不做采样率烘焙；迁移只做数据形态转换：

| 旧特性 | 迁移策略 |
|---|---|
| 静态 `position`（absolute world / relative world/base，无 `position_origin` 动态） | 按 §3.2 拆叶子；用 §3.4 保真；不把世界位置烘焙成新关键帧，保留原 `position_mode`/space |
| 旧 `follow=entity` / 旧位置点源 / 选择器 | 归一到 `position_origin` + `position_origin_freeze`；旧 `follow_selector` → `position_origin`；选择器调用点 `selector_*_follow` → `selector_*_position_origin`；用旧/新对照插入修正关键帧 |
| `look_at` 动态目标 | 保留朝向语义对象（`look_at*`/`selector_*`）；不并入位置点源；用旧/新对照插入修正关键帧 |
| `curve` | 旧 3D 控制点原样保留；`axes:["x","y","z"]`；每个控制点的相对/绝对不变；保留 3D 弧长。旧脚本共享关键帧天然三轴成组，迁移后满足 `curve` 成组约束；不烘焙成位置关键帧 |
| `orient=tangent` / `yaw_offset` / `pitch_offset` | 原样保留：`orient` 是 value-only 字段；`yaw_offset`/`pitch_offset` 是 animated 字段（旧 clip 常量写成 `value`，无 keyframes），前置条件 `orient=tangent`；不烘焙成 yaw/pitch 关键帧 |
| `look_at=none` 的伪目标 / 混合段 | 保持现行语义；不烘焙最终 yaw/pitch。是否改成纯手动朝向另开设计，不在迁移里偷偷改 |
| `yaw_base=entity/line` 动态基准 | 保留 `yaw_base*` 字段；若旧端点求值与新逐帧不同，插入修正关键帧；动态实体无法预计算时标记人工复核 |

- 旧 `curve` 是 3D 曲线，新格式用 `axes:["x","y","z"]` 保留同一条 3D 贝塞尔；控制点、相对/绝对和 3D 弧长都不变，不做按轴拆分。
- 旧段任一端 `follow=entity` 时旧运行时忽略 curve（线性）；迁移对该段不写 curve（或标记线性），报告；新格式动态段可用 curve，但不回填旧 follow 段。
- 2 轴组合只会在作者显式写两个轴时出现；迁移器不会把旧 3D 曲线降成 2 轴。2 轴组合的第三轴线性/2D 弧长语义见 `track-camera.md` §3.2。
- `orient=tangent` 的切线仍由完整 3D 路径导数组合；迁移后与旧 3D 切线同源。
- 旧 `absolute + follow=entity`：旧实现 follow 优先，`position` 实际是实体脚底偏移；迁移归一为 `relative + world + position_origin=<follow_selector> + position_origin_freeze=false`，保留偏移，不保留 `absolute`。
- 旧 `relative/base + follow=entity`：旧实现 base 原点走 `facing_origin`；新设计原点=跟随实体脚底。若旧 `facing_origin` 为空或不是跟随实体，迁移报告人工复核，不静默改原点。
- 旧 `relative/base` 且 `facing_target` 为空：旧方向源 = 旧运行时实际解析结果（原点实体 / 隐式 `@p` / follow 实体 / 玩家）；迁移写 `facing_source=entity`，旧选择器写进 `facing_entity`（原点本身就是该实体/玩家时可省略），固定旧方向；报告新增方向源。
- `position_origin` 实体选择器 `position_origin_freeze=false` 是动态点源，`true` 是静态点源；`follow=none` 不代表静态。迁移保留点源冻结状态，不按字段名猜静/动。
- 旧 `position_mode`/`follow`/`facing_origin`/`facing_origin_time` 切换，或 `base` 下 `facing_target`/`facing_target_time` 切换：迁移时在切换点插三轴位置关键帧；新格式对应 `position_mode`/`position_origin`/`position_origin_freeze`（base 下还有 `facing_source`/`facing_target`/`facing_target_freeze`/`facing_entity`）切换；base 轴没有独立角度偏移字段；有 `curve` 时切换点必须落在位置组上，否则报告人工复核。
- 迁移报告列出 `curve` 原样迁移片段、控制点保留情况、`orient`/offset 保留情况；不再有“烘焙采样数/30Hz”。

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
- 旧 `total_duration` 不用于校验新 `contentEnd`；转换器可以报告“旧 total_duration 与新 contentEnd 不一致”，但不改片段。
- 旧 `total_duration` 若小于片段窗口，旧运行时会截断/hold；新 `contentEnd` 用窗口最大值，可能改变行为。报告差异，人工确认；不把旧值写进新格式。

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
- 保留 `curve`（`axes:["x","y","z"]` + 原 3D 控制点）、`orient`、`yaw_offset`/`pitch_offset`；不写旧 clip 级 `curve` 形态。

### 3.10 报告与人工复核

报告字段：

- 脚本路径、clip 数、旧 keyframe 数、新字段数、修正关键帧数；
- 最大位置/角度/透明度/颜色/音量误差（`curve` 原样迁移，不再有按轴拆分的路径差异）；
- `curve` 原样迁移片段/控制点保留情况、`orient`/offset 保留情况；
- 旧 `total_duration` vs 新 `contentEnd` 差异；
- 动态 position_origin/选择器片段清单（人工复核）；
- 旧 `base` 段补出 `facing_source` / `facing_entity` 的清单（方向源来源、原选择器、是否复制 selector 策略）；
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
- `CurveBaker`：旧 `BezierPathStrategy`/`TangentOrientation` 采样。
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

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 手工迁移 | 逐个脚本改 | 223 个脚本/2411 关键帧，易错、不可重复 | 转换器 |
| 运行时双格式 | parser 同时读 keyframes/objects | 两套语义/校验/编辑器；旧格式永不清 | 一次性迁移 |
| 只拆 map，不保稀疏 | 出现点建对象，缺失 hold | 静默改变运动；旧脚本画面变 | 修正关键帧保真 |
| 全量materialize 每个全局帧 | 每个对象在每个全局关键帧写值 | 体积爆炸；4 MiB 风险 | materialize + 无损压缩 + 修正帧 |
| 全部烘焙世界坐标 | 相机位置/朝向全采样 | 丢失 position_origin 动态；脚本变大 | 拆叶子 + 修正帧，动态保语义 |
| 迁移器放编辑器 | 编辑器内转换 | 依赖 WebUI/浏览器；批量/CI 难 | 独立 Java CLI |
| 保留旧 `total_duration` | 新格式继续写 | 与 contentEnd 双口径 | 删除 |

**本方案的缺点**：

- 保真算法复杂；稀疏字段/动态相机需要旧 evaluator 冻结副本，工具链多一套代码。
- 修正关键帧可能仍很多；4 MiB 限制下需要压缩/分段，报告可能要求人工处理。
- 旧 `curve` 原样迁移为 3 轴，3D 弧长保留；只有作者后续显式改成 2 轴组合时，才会出现第三轴线性/2D 弧长的语义边界。
- 动态 position_origin/选择器无法完全预计算；人工复核不可避免。
- 旧 `total_duration` 与 `contentEnd` 不一致时，迁移只能报告，不能自动决定作者意图。
- 转换器/新 sampler 必须与运行时同源，否则“校验通过但游戏里不同”；需要共享 schema/采样代码。

## 六、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 未实现 | — | — |

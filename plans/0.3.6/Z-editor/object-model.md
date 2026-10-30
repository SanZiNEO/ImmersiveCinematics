# 编辑器对象模型（Editor Object Model）

> 状态：未实现
> 范围：编辑器数据模型、对象/关键帧编辑、时间轴、schema 驱动表单、缓动烘焙、脚本级曲线/规则集合编辑、`orient`/offset 属性编辑、保存/推送/校验、旧编辑器假设的清理
> 关联：`../I-script-core/object-model.md`、`../I-script-core/schema-and-types.md`、`../I-script-core/format-cleanup.md`、`../I-script-core/migration.md`、`../I-script-core/rule-system.md`（规则新方向专篇，未完成轮次）；参考 `docs/modules/editor.md`、`editor/FEATURE_CHECKLIST.md`

## 一、摘要

编辑器从“一个 clip 一组全字段关键帧”改为“一个 clip 一组 SampledProperty，每个属性有自己的 `value`/可选 keyframes”，并与**脚本级曲线集合与规则集合**分开编辑。选中属性后，右侧只编辑该属性；时间轴按属性渲染关键帧。曲线编辑器先选目标片段，再在目标位置节点序列中选择两个相邻关键帧；同一片段可有多条曲线。规则编辑器分开 RuleDefinition（内层条件树/动作/策略）与 RuleBinding（目标片段 + 执行局部时间门 + 阶段/动作调度声明），同一规则可有多条绑定。schema 驱动属性列表/表单/插值显示，不再维护按轨道的硬编码通道表。编辑器不再写 `meta.version`/`timeline.total_duration`，不再强制边界关键帧、不再把超界关键帧钳进 `[0,duration]`、不再跨属性去重。缓动可以在保存/推送时烘焙成显式关键帧；曲线条目由脚本级集合保留，`orient`/`yaw_offset`/`pitch_offset` 是运行时属性，不烘焙、不剥离。普通片段与分支片段共用同一对象编辑模型，不提供分支专用内容格式。统一原则：组合优先、通道级覆盖、定义与运行态分离、创作策略与执行机制分离、结构按片段引用定位。

## 二、为什么做

### 2.1 问题

- `Keyframe` 是平铺 interface，包含所有轨道字段；`KeyframePanel` 一屏铺所有字段，无法只编辑一个对象。
- `operations.ts` 的 `continuousChannels` 硬编码 CAMERA/AUDIO/OVERLAY/ADJUST 通道；新增字段要改多处，且与 schema 漂移。
- `interpolateNewKeyframe` 复制/插值所有字段；`sampleBakedKeyframe` 复制所有离散字段；对象模型下这些都会把别的对象的值带进来。
- 编辑器强制 `meta.version===3`、`total_duration!==0`、片段不重叠、关键帧在 `[0,duration]`、边界关键帧、同时间去重；其中片段重叠与 Java validator 冲突（lane 模型允许重叠），其余与对象模型冲突。
- 每次 commit/load 重算 `total_duration`；循环/窗口变化时漂移。
- 缓动烘焙按全局快照段处理，无法按对象段独立烘焙。
- 离线 demo/schema 内置字段表，与服务端 schema 漂移。
- 曲线只有片段级一份控制点，规则只有片段级数组；编辑器无法表达“先选片段、再选两个相邻位置关键帧”的多曲线编辑，也无法为同一规则维护多条独立时间门绑定。

### 2.2 目标

- 一个对象一个编辑上下文；关键帧增删改只影响该对象。
- 对象列表/表单/插值/默认值全部来自 schema；新增对象不改编辑器硬编码。
- 时间轴支持对象行/分组、跨对象同时间关键帧、超界关键帧显示（不钳制）。
- 保存/推送只写显式对象值；不写 version/total_duration；不写编辑器作者元数据。
- 缓动按字段段烘焙；曲线条目/`orient`/offset 原样写回，不烘焙。烘焙后运行时关键帧只有线性/角度/几何/step。
- 曲线编辑按“目标片段 → 目标位置序列 → 两个相邻关键帧”组织；同一片段可有多条曲线；重排/插入/删除后显式重映射或提示重建。
- 规则编辑分开定义与绑定；同一规则可有多条绑定，各绑定显示目标片段与执行局部时间门，不合并成最早入口。
- 普通/分支片段共用同一对象编辑模型；不提供“分支专用内容编辑器”，不强制把片段迁入全局库。
- 校验以 Java parser/validator 为权威；本地只做快速结构检查，不重复实现语义。
- 旧脚本先迁移；编辑器不提供旧格式打开模式。

### 2.3 非目标

- 不做新的曲线数学/缓动算法；沿用现有作者能力，只改数据落点。
- 不做多用户协同/版本控制；保存/推送仍是单文档。
- 不改变 WebUI 传输协议；仍走 `script.save`/`editor.pushScript`。
- 不实现多 lane 预览（camera-composition 步骤 6 的独立项）；对象模型为它提供选择上下文。
- 不做运行时热重载 schema；schema 变更随模组发布。
- 不新增强制全局片段库、段落/sequence 库、独立分支内容编辑器或独立分支引擎。

## 三、方案

### 3.1 作者看到的形态

- 左侧：SampledProperty 列表，按 schema `section` 分组（位置 / 朝向 / 注视 / 合成 / 调色……）；搜索路径/标签；可选属性显示“未设置”。
- 中间：时间轴；每个 SampledProperty 一行（或分组折叠）；菱形只属于该属性；同一时间多个属性的菱形可以并排/叠加显示，不合并。
- 右侧：选中属性的表单；`value` + keyframes 列表；当前播放头处“添加关键帧”；step 属性显示台阶图标；`nullable_float` 有“启用/不启用”。
- 片段面板：只放播放控制字段（start/duration/loop 等）；transition/motion/调色等都在属性列表里，不再有 total_duration/version。
- 位置点源：`position_mode`/`position_space`/`position_origin`/`position_origin_freeze`/`facing_source` 按点源模型联动；`absolute` 禁用 `base`/原点，`position_origin=实体 + position_origin_freeze=false` 是“跟随实体”预设；空原点在 `world` = 玩家激活位置，在 `base` = 隐式 `@p`（不冻结）；`player` = 玩家激活位置；`base` 的方向源用 `facing_source` 单值选择：`auto` / `world` / `entity` / `line`；`auto` 有 `facing_target` → line、有 `facing_entity` → entity、都没有按原点回落；`entity` 显示 `facing_entity`（可选，缺省原点实体/玩家），`line` 显示 `facing_target`，`world` 隐藏两个载荷；`facing_entity` 只取 body yaw 且无冻结开关；base 轴没有独立角度偏移字段；改动时在切换点插三轴位置关键帧，不能只改 step 值。
- 冻结：每个点源对象各有自己的 `_freeze`（`position_origin_freeze` / `facing_target_freeze` / `look_at_freeze` / `yaw_base_from_freeze` / `yaw_base_to_freeze`）；`facing_entity` 始终实时，没有 `_freeze`。`true` 固定解析结果（点/端点，含偏移展开），不是停时间；不做整帧/整参考系冻结。
- `facing_source`/`facing_target`/`facing_entity` 只在 `position_space=base` 显示；`world` 下置灰并提示“base 专用”。base 轴没有独立角度偏移字段；相机朝向偏移是 `yaw`/`pitch`（相对 `yaw_base`/`pitch_base`）。
- 脚本级曲线集合：左侧/时间轴另设曲线列表，与 SampledProperty 对象列表分开。每条曲线先选目标片段，再在目标位置节点序列中选择两个相邻关键帧；列表可显示 1/2…序号供选择，但底层绑定是“目标片段 + 端点引用”，重排/插入/删除后必须重映射或报错。同一片段可有多条曲线，列表必须显示每条的目标片段、端点对、控制点参考空间与几何。曲线条目不是 value-only 对象，也不混进普通关键帧面板；曲线只编辑所选轴，未选轴仍在各自对象面板独立打帧。
- 脚本级规则集合：RuleDefinition 在行为定义列表编辑（内层条件树/动作/策略）；RuleBinding 在绑定列表编辑，显示定义引用、目标片段、执行局部时间门、关键帧时间来源、判定阶段控制（实例级持有/非阻塞观察）与动作延迟目标（片段开始/完成、局部时间、延迟、事实）；同一规则可有多条绑定，各绑定独立显示。RuleActivation 只在预览/运行态显示（播放实例，绑定身份，执行身份）及其判定成功、动作待执行/完成/取消；不写回定义。时间门前的有效事件/事实可采集但动作不提前提交，编辑器不把时间门显示成“到达后才开始监听”。动作目标片段可与时间参照片段不同，编辑器分别显示。
- `orient` 是模式选择，`yaw_offset`/`pitch_offset` 只在 `orient=tangent` 时显示并可打关键帧。缓动：在 SampledProperty 段上打开作者工具，保存时烘焙；曲线条目内不得用插位置点并复用旧控制点的方式伪造保形。
- 普通/分支片段：对象列表、表单、关键帧编辑完全共用；分支用途不产生第二套属性面板。

### 3.2 数据模型

```text
ScriptDoc
  meta
  timeline
    tracks[]
      type
      clips[]
        start_time
        duration
        clipData
        objects: Record<path, AnimObject>
          value?
          keyframes: ObjectKeyframe[]
            time
            value
            authoring?: { easing?: ... }   // 编辑器元数据，保存时剥离

  curves: ScriptCurve[]
    targetClipId
    positionPath?          // 目标位置对象/组
    endpointA/B            // 目标位置序列中两个相邻关键帧
    axes
    geometry               // 控制点/弧长
    progress
  rules: ScriptRule[]
    conditionTree
    actions
    policy
    bindings: ScriptRuleBinding[]
      targetClipId
      timeGate
      timeSource?          // 关键帧引用或作者秒
      control?             // 实例级持有等待 / 非阻塞观察
      actionSchedule?      // 片段开始/完成、局部时间、延迟、事实
      actionTargetClipId?  // 动作目标片段，可与时间参照片段不同
      authoring?

Selection
  clipId
  objectPath?
  keyframeIndex?
  curveId?
  ruleId?
  bindingId?
```

- `AnimObject`/`ObjectKeyframe` 与运行时 JSON 同形；`authoring` 只存在于编辑器内存/草稿，不写脚本。
- 文档模型在脚本级包含曲线集合与规则集合，与 `timeline` 并列存储；RuleActivation 只在运行态预览，不写入文档。曲线/规则/绑定不使用 `objectPath` 选择状态，也不共享 keyframe 容器。
- `clipData` 只含播放控制字段（start/duration/loop 等）；其余字段都在 `objects` 里，不进入片段面板。
- 对象路径是稳定键；选择状态用 `clipId + objectPath`，不用数组下标。
- 同一对象 keyframes 时间严格递增；不同对象可以同时间。
- 超界 keyframes 保留在模型中；时间轴显示为“轴外”样式；运行时不采样。
- 普通/分支片段在 `ScriptDoc` 中使用同一 `clips[]` 结构与同一对象模型；分支用途不新增集合类型。

### 3.3 SampledProperty 与脚本级结构操作

- **添加 SampledProperty 值/关键帧**：从 schema 选择属性；在播放头时间加 keyframe；如果属性还没有 `value`，用 schema 默认/当前采样值填充。
- **删除关键帧**：只删该属性该索引；不删除属性（属性可回落到 `value`/默认）。
- **移动关键帧**：只改该属性时间；允许拖到 `[0,duration]` 外（显示警告/轴外样式），不钳制；同一属性内重复时间时合并/拒绝（按编辑器交互定，建议拒绝并提示）。
- **曲线条目操作**：添加/删除/重排曲线条目只改脚本级曲线集合，不伪造 SampledProperty 关键帧；每条先选目标片段，再在目标位置节点序列中选择两个相邻关键帧。重排/插入/删除位置关键帧后，端点绑定必须重映射或报错；插入位置关键帧时，作者可以显式重建/重绑定以产生新几何；不得静默复用旧控制点改变路径，不强制算法/拒绝合法创作。同一片段可添加多条曲线，各自选择端点对。结构条目的序列化/引用表达待评审。
- **RuleBinding 操作**：绑定/解绑只改脚本级规则集合，指向 RuleDefinition、目标片段与执行局部时间门；同一规则可添加多条绑定，各绑定独立；RuleActivation 不在编辑器文档里持久化，预览只读显示（播放实例，绑定身份，执行身份）。时间门前的有效事件/事实可在预览中标记为“已采集、未提交”，不把动作提前显示为已执行。
- **复制/粘贴**：复制 SampledProperty keyframes（相对时间），粘贴到同一/另一属性；曲线/规则条目单独复制，并保留目标片段引用或提示重新选择，不把结构条目混进属性关键帧。
- **SampledProperty 值编辑**：改 `value`；如果属性有 keyframes，`value` 只在 keyframes 为空时生效；第一帧之前用第一帧，不回落到 `value`（与运行时采样一致）。
- **添加属性**：作者不能发明路径；schema 里有的属性才能编辑。新增可调项是代码/schema 变更，不是编辑器任意加键。
- **删除属性**：从属性列表移除该 SampledProperty（或清空 keyframes/value）；不删除 schema。

### 3.4 时间轴

- 行模型：`clip -> objectPath -> keyframes`；按 section 分组，可折叠。
- 脚本级结构：时间轴另设曲线列表与规则列表；曲线显示目标片段、端点对、几何/进度，RuleBinding 显示目标片段、执行局部时间门与定义引用；序号只是选择 UI，绑定不得靠序号。同一片段多条曲线/同一规则多绑定并排显示。RuleActivation 只在预览/运行态显示，不写回文档。
- 菱形位置：`keyframe.time / clip.duration`；超界 keyframe 显示在 clip 区域外/轴外轨道，不裁剪。
- 多选：可以选多个 SampledProperty 的关键帧，批量移动/删除；批量操作只改选中属性。
- 播放头：全局时间；添加关键帧时换算成 clip-local time；不强制落在整数帧。
- 缩放/滚动：按有限部分显示范围或当前 clip 窗口；含无限内容时显示“无有限自然末端”标记与可展示的有限部分，不用 ∞/NaN/MAX 数值哨兵作为完成/定位判据。
- 片段重叠：允许；时间轴按 lane/轨道显示重叠 clip，不再报“片段重叠”错误。

### 3.5 表单与 schema

- SampledProperty 表单字段来自 schema：`labelKey` i18n、`unit`、`min/max`、`enum`、`ref`、`nullable`、`appliesTo`。
- `interp` 只读显示（线性/角度/几何/step）；作者不能逐属性改插值（schema 决定）。
- 属性列表按 `section` 分组；`appliesTo` 不匹配当前层类型的属性隐藏/置灰。
- 默认值：属性未设置时显示 schema 默认；不强制写进脚本。
- 验证错误定位到 `clipId + objectPath + keyframeIndex`；未知路径/类型错误在加载时显示。

### 3.6 缓动、脚本级结构与朝向属性

- **缓动**：编辑器可以在 SampledProperty 段上设置 easing（作者元数据）；保存/推送时按属性段烘焙：
  - 只处理该属性相邻 keyframes；
  - 按曲线采样/固定步长插入显式 keyframes；
  - 删除 `authoring.easing`；
  - step 属性不烘焙；
  - 不得用“插入位置关键帧并复用旧控制点”的方式伪造曲线条目保形/速度；曲线条目内的进度/缓动表达由结构契约决定。
- **曲线条目**：独立曲线编辑器，不混进 SampledProperty 表单；每条先选目标片段，再在目标位置节点序列中选择两个相邻关键帧作为 A→B 端点；可显示 1/2…序号供选择，但底层绑定是“目标片段 + 端点引用”。同一片段可有多条曲线，列表显示目标片段、端点对与几何。位置节点只是路径寻址域，不把 `fov`/`roll` 拉回共享 `from/to`。几何与进度分离；控制点 XYZ 可分别编辑。曲线只负责 axes 中选中的坐标轴，未选轴继续按自身 SampledProperty 的局部时间/关键帧求值，段外关键帧仍独立，不要求整片段三轴全局成组。插入位置关键帧时作者可显式重建/重绑定产生新几何；不得静默复用旧控制点改变路径，不强制算法/拒绝合法创作。结构条目的序列化/引用待评审。
- **RuleDefinition/RuleBinding**：RuleDefinition 在行为定义编辑器编辑（内层条件树/等待/计时/观察/动作，不可变）；RuleBinding 在绑定编辑器编辑（定义引用 + 目标片段 + 执行局部时间门 + 可选关键帧时间来源）。同一规则可有多条绑定，各绑定独立；RuleActivation 只在预览/运行态显示（播放实例，绑定身份，执行身份），不写回定义；非阻塞观察不阻止片段自然完成。时间门前有效事件/事实可采集但动作不提前提交。判定成功后已承诺未释放的动作是明确执行依赖，参与自然完成但不冻结内容推进；等待片段开始与等待片段完成分别显示。
- **`orient`**：value-only SampledProperty；`tangent` 时显示 `yaw_offset`/`pitch_offset`，这两个属性是 animated，可逐关键帧微调；`manual`/`look_at` 时隐藏/置灰。
- 编辑器不把曲线条目/`orient`/offset 烘焙成 `position`/`yaw`/`pitch` 关键帧；曲线条目由脚本级集合保留，`orient`/offset 运行时保留。
- 编辑曲线条目控制点不改变 `position` 关键帧；切线/偏移由运行时按结构/属性求值。2 轴条目只保证该平面内 2D 弧长，不承诺 3D 匀速；未选轴仍可独立打帧。
- 过渡／morph：按当前目标编辑 value-only 的 `transition*` 声明，不生成混合快照或过渡关键帧；预览消费运行时两侧实际状态，窗口为交界 ± maxDuration/2（3 秒窗口前后各 1.5 秒）。不从通用属性动画能力推导这些声明已支持逐帧变化。

### 3.7 保存/推送/加载

- **加载**：读新格式；校验 `objects` 与脚本级曲线/规则集合；不重算 total_duration；不强制边界；选择第一个 CAMERA clip/对象；推送到服务端预览；seek 0。
- **保存**：深拷贝 → 按属性剥离默认值/空字段 → 烘焙缓动（不烘焙曲线条目/`orient`/offset）→ 剥离 `authoring` → 校验 → `script.save`；错误不阻止保存（保持现有“保存草稿”行为）。
- **推送**：深拷贝 → 烘焙缓动/剥离 → 去掉静默 AUDIO 轨（现有行为，可保留）→ `editor.pushScript`。
- **不变量**：只做必要结构维护（clip 排序、transition 对齐、点源/参考系切换与位置关键帧对齐、曲线条目端点重映射/报错、RuleBinding 定义/目标片段/时间门引用校验）；不写 total_duration、不强制边界、不跨属性去重、不钳制 keyframes。普通/分支片段共用同一保存/校验路径。
- **校验**：以服务端 `script.validate` 为准；本地只做快速结构检查（路径存在、时间递增、类型），不实现完整语义；片段重叠不再报错。
- **错误展示**：按对象/关键帧定位；支持跳转到时间轴位置。

### 3.8 离线 demo / schema 快照

- `demo.ts` 不再内置 version/total_duration/平铺字段表；使用一份从服务端 schema 生成的快照（构建时生成或手写同步）。
- 离线 demo 只用于 UI 开发；schema 快照带生成版本/日期，避免长期漂移。
- 新增对象时先更新 schema 快照；CI 检查快照与 `TrackSchemas` 的路径集合一致。

## 四、技术细节

### 4.1 核心模型

```text
EditorStore
  doc: ScriptDoc
  selection: Selection
  undoStack/redoStack
  schema: TrackSchemaMap
```

- `ScriptDoc` 与运行时 JSON 同形（除 `authoring`）；保存时序列化。
- `TrackSchemaMap` 来自 WebUI/构建快照；对象列表/表单只读；曲线/规则集合的声明形态也来自 schema，具体条目属于文档。
- `Selection` 用 `clipId`（稳定 id，不是数组下标）+ `objectPath`，或 `curveId`/`ruleId`/`bindingId`；移动/排序 clip 不丢选择，曲线/绑定重排必须显式更新引用。
- `undoStack` 存文档快照/操作；对象操作只影响局部，避免全量重算。

### 4.2 契约

- **创建**：加载脚本/新建文档时构建 `ScriptDoc`；schema 加载一次。
- **销毁**：关闭文档/页面；撤销栈释放。
- **状态归谁**：文档归 store；选择/播放头归 UI；schema 只读；服务端预览是派生。
- **边界**：编辑器不执行运行时求值；预览用服务端 sampler/推送；本地不实现相机/调色数学。
- **保存**：编辑器是脚本作者工具，不改变运行时语义；缓动烘焙只把作者意图落成显式 keyframes，曲线条目由脚本级集合保留，`orient`/offset 原样保留。

### 4.3 数据形态

- `objects` 是 `Record<path, AnimObject>`（SampledProperty）；JSON 序列化时按 schema 顺序/路径排序，保证 diff 稳定。
- 脚本级曲线/规则集合与 `objects` 分开存储：曲线条目持有目标片段/端点对/几何/进度；RuleDefinition 只读行为，RuleBinding 持有定义引用/目标片段/时间门/阶段与动作调度声明；RuleActivation 不持久化，只来自运行态预览。
- `authoring` 不序列化；如果以后要 sidecar，另存文件，不混入脚本。
- 对象 keyframes 时间 float 秒；不取整；超界保留。
- `clipId` 是编辑器内部稳定 id（加载时生成/从路径+索引派生）；不写脚本。
- 有限末端估计只在所有内容静态有限时由实际内容/片段窗口计算（不是 `max(windowEnd)` 的别名，也不驱动消费）；含无限内容时记录“无有限自然末端”，有限部分显示范围单独派生；不存 `total_duration`。

### 4.4 边界

- 不改变 WebUI 协议/服务端 API；只改 payload 形态。
- 不实现运行时校验；服务端 validator 是权威。
- 不实现多 lane 预览；对象模型只提供选择/编辑。
- 不实现结构层 sidecar；CurveDef/RuleDef/RuleBinding 的序列化/引用表达待评审，编辑器不预设字段位置或 sidecar 方案。
- 编辑器不定义正常完成默认/入口覆盖/失败 fallback 的语义；只显示并校验片段默认策略与入口覆盖字段，去向归 `../I-script-core/rule-system.md`。
- 结构评审项（非用户待确认）：事实记录起点/有效期/消费/跨执行匹配、稳定片段引用、持有/调度 schema 表达、集合序列化/迁移/编辑器呈现；编辑器不预设这些字段形态。
- 不改变 i18n 框架；只加 `labelKey`。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 保留平铺 Keyframe 表单 | 一个表单编辑所有字段 | 无法单对象编辑；新增字段改 UI；与对象模型冲突 | 对象列表 + 单对象表单 |
| 硬编码通道表 | `continuousChannels` 按轨道列字段 | schema/编辑器漂移；新增字段改多处 | schema 驱动 |
| 本地完整校验 | 编辑器再实现一套 parser/validator | 双口径；片段重叠等冲突；维护成本 | 服务端权威 + 本地结构检查 |
| 强制边界/钳制 | 保证 keyframes 在 `[0,duration]` | 与“超界忽略”语义冲突；缩小时丢作者数据 | 保留超界，显示轴外 |
| 跨对象去重 | 同一时间只留一个 keyframe | 对象独立关键帧的核心被破坏 | 同对象严格递增，跨对象允许 |
| clip 级一份控制点套所有段 | 一个字段只有一组 P1/P2，隐式作用到每个位置段 | 不同段不能有不同曲线；同片段不能有多条候选路径；插入关键帧会改变其余段 | 脚本级曲线集合，每条引用目标片段与端点对 |
| 曲线只按全片段关键帧总编号 | 把 FOV/调色等所有对象的帧拼成总序列，曲线按总序号取相邻帧 | 其它对象增删帧会改变位置曲线端点 | 端点相邻性只在目标位置序列内判断 |
| 曲线条目只靠序号绑定 | 第 1 项套第 1 段、第 2 项套第 2 段 | 重排/插入/删除后静默指错；作者无法看出绑定的是哪段 | 条目持有目标片段 + 端点引用；重排不得静默改绑 |
| 曲线条目/`orient` 烘焙成关键帧 | 编辑器保存时采样成 `position`/`yaw`/`pitch` | 脚本变大、曲线不可逆、旧 3D 弧长路径无法逐点保真 | 曲线条目由脚本级集合保留，`orient`/offset 运行时保留，不烘焙 |
| 多个时间入口合并成一条绑定 | 编辑器只暴露一个最早时间门 | 后续入口被吞；once/边沿/计时无法按入口编辑 | 同一规则多条绑定，各自显示目标片段与时间门 |
| 分支专用内容编辑器 / 全局片段库 | 分支另开一套表单或把全部片段迁入全局库 | 两套对象模型/保存路径；普通片段也被迫改承载 | 普通/分支共用同一对象编辑模型，分支只改编排去向 |
| 编辑器内打开旧格式 | 双 parser/双 UI | 旧格式永不清；测试矩阵翻倍 | 先迁移 |

**本方案的缺点**：

- CAMERA 160+ 字段让字段列表/时间轴很密；需要折叠/搜索/虚拟滚动，否则可用性差。
- 缓动烘焙后不可逆；重新打开只能编辑烘焙后的关键帧，作者可能觉得“缓动丢了”。曲线条目/`orient`/offset 不烘焙，保留可编辑性。
- 脚本级结构需要独立入口：曲线条目编辑、RuleDefinition 行为编辑、RuleBinding 绑定编辑、RuleActivation 只读预览；序列化/引用/身份字段仍待评审，不能复用 SampledProperty 表单。
- 曲线条目的几何保真要求保留，但不锁死编辑算法：插入关键帧后如何表达/维护端点关系由结构契约决定；不能用复用旧控制点冒充保形。
- 2 轴曲线条目只保证该平面内 2D 弧长，不承诺 3D 匀速；未选轴仍按自身 SampledProperty 求值，编辑器不能替作者补第三轴。
- 规则阶段（实例级持有、成功事实、动作延迟、事实接力）在编辑器里需要独立状态展示；把成功与完成合并会误导作者。
- 超界关键帧显示/拖动交互复杂；用户可能误以为会生效。
- 本地校验只做结构检查，保存时服务端错误可能延迟出现；需要清晰的错误面板。
- schema 快照与运行时 schema 可能漂移；需要 CI 检查。
- 对象数量多时撤销栈/深拷贝成本高；需要增量/结构共享，否则大脚本卡顿。

## 六、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 脚本级曲线集合编辑（目标片段 + 相邻端点）+ RuleDefinition/Binding/Activation 分层；结构独立于 SampledProperty | 旧编辑器只有 clip 级 `curve` 字段（一组控制点），没有段选择/绑定；`objects.curve` 从未实现；规则是片段级 `clip.rules`，没有定义/绑定/时间门分层 | 旧格式只有一份 `curve` 与片段级规则数组；脚本级结构是新增设计，序列化/引用待评审 |
| 规则阶段可视化（实例级持有、判定成功、动作延迟、事实接力） | 旧编辑器没有规则阶段/成功/延迟/接力呈现 | 规则阶段模型未实现 |
| 有限末端估计/有限显示范围分离；含无限不伪造末端 | 旧编辑器重算 `total_duration`，按 `contentEnd` 缩放，无限显示固定哨兵 | 旧实现把派生值当文档字段/定位依据 |

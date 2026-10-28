# 编辑器对象模型（Editor Object Model）

> 状态：未实现
> 范围：编辑器数据模型、对象/关键帧编辑、时间轴、schema 驱动表单、缓动烘焙、`curve`/`orient`/offset 字段编辑、保存/推送/校验、旧编辑器假设的清理
> 关联：`object-model.md`、`schema-and-types.md`、`format-cleanup.md`、`migration.md`；参考 `docs/modules/editor.md`、`editor/FEATURE_CHECKLIST.md`

## 一、摘要

编辑器从“一个 clip 一组全字段关键帧”改为“一个 clip 一组字段，每个字段有自己的 `value`/可选 keyframes”。选中字段后，右侧只编辑该字段；时间轴按字段渲染关键帧。schema 驱动字段列表/表单/插值显示，不再维护按轨道的硬编码通道表。编辑器不再写 `meta.version`/`timeline.total_duration`，不再强制边界关键帧、不再把超界关键帧钳进 `[0,duration]`、不再跨对象去重。缓动可以在保存/推送时烘焙成显式关键帧；`curve`/`orient`/`yaw_offset`/`pitch_offset` 是运行时字段，不烘焙、不剥离。

## 二、为什么做

### 2.1 问题

- `Keyframe` 是平铺 interface，包含所有轨道字段；`KeyframePanel` 一屏铺所有字段，无法只编辑一个对象。
- `operations.ts` 的 `continuousChannels` 硬编码 CAMERA/AUDIO/OVERLAY/ADJUST 通道；新增字段要改多处，且与 schema 漂移。
- `interpolateNewKeyframe` 复制/插值所有字段；`sampleBakedKeyframe` 复制所有离散字段；对象模型下这些都会把别的对象的值带进来。
- 编辑器强制 `meta.version===3`、`total_duration!==0`、片段不重叠、关键帧在 `[0,duration]`、边界关键帧、同时间去重；其中片段重叠与 Java validator 冲突（lane 模型允许重叠），其余与对象模型冲突。
- 每次 commit/load 重算 `total_duration`；循环/窗口变化时漂移。
- 缓动烘焙按全局快照段处理，无法按对象段独立烘焙。
- 离线 demo/schema 内置字段表，与服务端 schema 漂移。

### 2.2 目标

- 一个对象一个编辑上下文；关键帧增删改只影响该对象。
- 对象列表/表单/插值/默认值全部来自 schema；新增对象不改编辑器硬编码。
- 时间轴支持对象行/分组、跨对象同时间关键帧、超界关键帧显示（不钳制）。
- 保存/推送只写显式对象值；不写 version/total_duration；不写编辑器作者元数据。
- 缓动按字段段烘焙；`curve`/`orient`/offset 作为字段原样写回，不烘焙。烘焙后运行时关键帧只有线性/角度/几何/step。
- 校验以 Java parser/validator 为权威；本地只做快速结构检查，不重复实现语义。
- 旧脚本先迁移；编辑器不提供旧格式打开模式。

### 2.3 非目标

- 不做新的曲线数学/缓动算法；沿用现有作者能力，只改数据落点。
- 不做多用户协同/版本控制；保存/推送仍是单文档。
- 不改变 WebUI 传输协议；仍走 `script.save`/`editor.pushScript`。
- 不实现多 lane 预览（camera-composition 步骤 6 的独立项）；对象模型为它提供选择上下文。
- 不做运行时热重载 schema；schema 变更随模组发布。

## 三、方案

### 3.1 作者看到的形态

- 左侧：对象列表，按 schema `section` 分组（位置 / 朝向 / 注视 / 合成 / 调色……）；搜索路径/标签；可选对象显示“未设置”。
- 中间：时间轴；每个对象一行（或分组折叠）；菱形只属于该对象；同一时间多个对象的菱形可以并排/叠加显示，不合并。
- 右侧：选中对象的表单；`value` + keyframes 列表；当前播放头处“添加关键帧”；step 对象显示台阶图标；`nullable_float` 有“启用/不启用”。
- 片段面板：只放播放控制字段（start/duration/loop/rules）；transition/motion/调色等都在字段列表里，不再有 total_duration/version。
- 位置点源：`position_mode`/`position_space`/`position_origin`/`position_origin_freeze`/`facing_source` 按点源模型联动；`absolute` 禁用 `base`/原点，`position_origin=实体 + position_origin_freeze=false` 是“跟随实体”预设；空原点在 `world` = 玩家激活位置，在 `base` = 隐式 `@p`（不冻结）；`player` = 玩家激活位置；`base` 的方向源用 `facing_source` 单值选择：`auto` / `world` / `entity` / `line`；`auto` 有 `facing_target` → line、有 `facing_entity` → entity、都没有按原点回落；`entity` 显示 `facing_entity`（可选，缺省原点实体/玩家），`line` 显示 `facing_target`，`world` 隐藏两个载荷；`facing_entity` 只取 body yaw 且无冻结开关；base 轴没有独立角度偏移字段；改动时在切换点插三轴位置关键帧，不能只改 step 值。
- 冻结：每个点源对象各有自己的 `_freeze`（`position_origin_freeze` / `facing_target_freeze` / `look_at_freeze` / `yaw_base_from_freeze` / `yaw_base_to_freeze`）；`facing_entity` 始终实时，没有 `_freeze`。`true` 固定解析结果（点/端点，含偏移展开），不是停时间；不做整帧/整参考系冻结。
- `facing_source`/`facing_target`/`facing_entity` 只在 `position_space=base` 显示；`world` 下置灰并提示“base 专用”。base 轴没有独立角度偏移字段；相机朝向偏移是 `yaw`/`pitch`（相对 `yaw_base`/`pitch_base`）。
- 曲线：`curve` 显示轴集合多选（至少 2 个、顺序无关）和两个 3D 控制点，直接写字段值；开启 `curve` 后 `position` 关键帧三轴成组，常量轴补当前值。`orient` 是模式选择，`yaw_offset`/`pitch_offset` 只在 `orient=tangent` 时显示并可打关键帧。缓动：在字段段上打开作者工具，保存时烘焙。

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

Selection
  clipId
  objectPath?
  keyframeIndex?
```

- `AnimObject`/`ObjectKeyframe` 与运行时 JSON 同形；`authoring` 只存在于编辑器内存/草稿，不写脚本。
- `clipData` 只含播放控制字段（start/duration/loop/rules）；其余字段都在 `objects` 里，不进入片段面板。
- 对象路径是稳定键；选择状态用 `clipId + objectPath`，不用数组下标。
- 同一对象 keyframes 时间严格递增；不同对象可以同时间。
- 超界 keyframes 保留在模型中；时间轴显示为“轴外”样式；运行时不采样。

### 3.3 对象操作

- **添加对象值/关键帧**：从 schema 选择对象；在播放头时间加 keyframe；如果对象还没有 `value`，用 schema 默认/当前采样值填充。
- **删除关键帧**：只删该对象该索引；不删除对象（对象可回落到 `value`/默认）。
- **移动关键帧**：只改该对象时间；允许拖到 `[0,duration]` 外（显示警告/轴外样式），不钳制；同一对象内重复时间时合并/拒绝（按编辑器交互定，建议拒绝并提示）。
- **复制/粘贴**：复制对象 keyframes（相对时间），粘贴到同一/另一对象；不复制其他对象。
- **对象值编辑**：改 `value`；如果对象有 keyframes，`value` 只影响第一帧之前/空组，不覆盖 keyframes。
- **添加字段**：作者不能发明路径；schema 里有的对象才能编辑。新增可调项是代码/schema 变更，不是编辑器任意加键。
- **删除字段**：从对象列表移除该对象（或清空 keyframes/value）；不删除 schema。

### 3.4 时间轴

- 行模型：`clip -> objectPath -> keyframes`；按 section 分组，可折叠。
- 菱形位置：`keyframe.time / clip.duration`；超界 keyframe 显示在 clip 区域外/轴外轨道，不裁剪。
- 多选：可以选多个对象的关键帧，批量移动/删除；批量操作只改选中对象。
- 播放头：全局时间；添加关键帧时换算成 clip-local time；不强制落在整数帧。
- 缩放/滚动：按 `contentEnd` 或当前 clip 窗口；无限脚本显示有限部分 + “∞”。
- 片段重叠：允许；时间轴按 lane/轨道显示重叠 clip，不再报“片段重叠”错误。

### 3.5 表单与 schema

- 对象表单字段来自 schema：`labelKey` i18n、`unit`、`min/max`、`enum`、`ref`、`nullable`、`appliesTo`。
- `interp` 只读显示（线性/角度/几何/step）；作者不能逐对象改插值（schema 决定）。
- 对象列表按 `section` 分组；`appliesTo` 不匹配当前层类型的对象隐藏/置灰。
- 默认值：对象未设置时显示 schema 默认；不强制写进脚本。
- 验证错误定位到 `clipId + objectPath + keyframeIndex`；未知路径/类型错误在加载时显示。

### 3.6 缓动、曲线与朝向字段

- **缓动**：编辑器可以在字段段上设置 easing（作者元数据）；保存/推送时按字段段烘焙：
  - 只处理该字段相邻 keyframes；
  - 按曲线采样/固定步长插入显式 keyframes；
  - 删除 `authoring.easing`；
  - step 字段不烘焙。
- **`curve`**：value-only 字段；编辑器显示轴集合多选（至少 2 个、去重、顺序无关）和两个 3D 控制点；控制点可相对/绝对。开启 `curve` 后，`position.x/y/z` 关键帧必须三轴成组：加/删/移动一个轴的关键帧时三轴同步，常量轴补当前值；关闭 `curve` 后恢复三轴独立。保存时直接写 `curve.axes` + `curve.control_points`，不采样成 `position` 关键帧。
- **`orient`**：value-only 模式；`tangent` 时显示 `yaw_offset`/`pitch_offset`，这两个字段是 animated，可逐关键帧微调；`manual`/`look_at` 时隐藏/置灰。
- 编辑器不把 `curve`/`orient`/offset 烘焙成 `position`/`yaw`/`pitch` 关键帧；运行时保留这些字段。
- 编辑曲线控制点不改变 `position` 关键帧；开启/关闭 `curve` 时三轴关键帧成组/恢复独立。切线/偏移由运行时按字段求值。轴集合选 2 轴时只在该平面内做 2D 弧长，第三轴线性变化时 3D 速度不一定匀；编辑器提示“要整条 3D 路径匀速就选 3 轴”。

### 3.7 保存/推送/加载

- **加载**：读新格式；校验 `objects`；不重算 total_duration；不强制边界；选择第一个 CAMERA clip/对象；推送到服务端预览；seek 0。
- **保存**：深拷贝 → 按字段剥离默认值/空字段 → 烘焙缓动（不烘焙 `curve`/`orient`/offset）→ 剥离 `authoring` → 校验 → `script.save`；错误不阻止保存（保持现有“保存草稿”行为）。
- **推送**：深拷贝 → 烘焙缓动/剥离 → 去掉静默 AUDIO 轨（现有行为，可保留）→ `editor.pushScript`。
- **不变量**：只做必要结构维护（clip 排序、transition 对齐、`curve` 开启时三轴关键帧成组、点源/参考系切换与位置关键帧对齐）；不写 total_duration、不强制边界、不跨对象去重、不钳制 keyframes。
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
- `TrackSchemaMap` 来自 WebUI/构建快照；对象列表/表单只读。
- `Selection` 用 `clipId`（稳定 id，不是数组下标）+ `objectPath`；移动/排序 clip 不丢选择。
- `undoStack` 存文档快照/操作；对象操作只影响局部，避免全量重算。

### 4.2 契约

- **创建**：加载脚本/新建文档时构建 `ScriptDoc`；schema 加载一次。
- **销毁**：关闭文档/页面；撤销栈释放。
- **状态归谁**：文档归 store；选择/播放头归 UI；schema 只读；服务端预览是派生。
- **边界**：编辑器不执行运行时求值；预览用服务端 sampler/推送；本地不实现相机/调色数学。
- **保存**：编辑器是脚本作者工具，不改变运行时语义；缓动烘焙只把作者意图落成显式 keyframes，`curve`/`orient`/offset 原样保留。

### 4.3 数据形态

- `objects` 是 `Record<path, AnimObject>`；JSON 序列化时按 schema 顺序/路径排序，保证 diff 稳定。
- `authoring` 不序列化；如果以后要 sidecar，另存文件，不混入脚本。
- 对象 keyframes 时间 float 秒；不取整；超界保留。
- `clipId` 是编辑器内部稳定 id（加载时生成/从路径+索引派生）；不写脚本。
- `contentEnd` 由文档片段窗口计算；不存 `total_duration`。

### 4.4 边界

- 不改变 WebUI 协议/服务端 API；只改 payload 形态。
- 不实现运行时校验；服务端 validator 是权威。
- 不实现多 lane 预览；对象模型只提供选择/编辑。
- 不实现曲线 sidecar；`curve` 本身是运行时字段，编辑器直接编辑并保存，不需要 sidecar。
- 不改变 i18n 框架；只加 `labelKey`。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 保留平铺 Keyframe 表单 | 一个表单编辑所有字段 | 无法单对象编辑；新增字段改 UI；与对象模型冲突 | 对象列表 + 单对象表单 |
| 硬编码通道表 | `continuousChannels` 按轨道列字段 | schema/编辑器漂移；新增字段改多处 | schema 驱动 |
| 本地完整校验 | 编辑器再实现一套 parser/validator | 双口径；片段重叠等冲突；维护成本 | 服务端权威 + 本地结构检查 |
| 强制边界/钳制 | 保证 keyframes 在 `[0,duration]` | 与“超界忽略”语义冲突；缩小时丢作者数据 | 保留超界，显示轴外 |
| 跨对象去重 | 同一时间只留一个 keyframe | 对象独立关键帧的核心被破坏 | 同对象严格递增，跨对象允许 |
| `curve`/`orient` 烘焙成关键帧 | 编辑器保存时采样成 `position`/`yaw`/`pitch` | 脚本变大、曲线不可逆、旧 3D 弧长路径无法逐点保真 | 保留运行时字段，轴集合 + 3D 控制点 |
| 编辑器内打开旧格式 | 双 parser/双 UI | 旧格式永不清；测试矩阵翻倍 | 先迁移 |

**本方案的缺点**：

- CAMERA 160+ 字段让字段列表/时间轴很密；需要折叠/搜索/虚拟滚动，否则可用性差。
- 缓动烘焙后不可逆；重新打开只能编辑烘焙后的关键帧，作者可能觉得“缓动丢了”。`curve`/`orient`/offset 不烘焙，保留可编辑性。
- 有 `curve` 时三轴关键帧必须成组，编辑器要同步加/删/移动并给常量轴补值；作者不能只在 x 上打点。
- 2 轴 `curve` 只保证该平面内匀速；第三轴线性变化时 3D 速度不一定匀，编辑器只能提示选 3 轴，不能自动补。
- 超界关键帧显示/拖动交互复杂；用户可能误以为会生效。
- 本地校验只做结构检查，保存时服务端错误可能延迟出现；需要清晰的错误面板。
- schema 快照与运行时 schema 可能漂移；需要 CI 检查。
- 对象数量多时撤销栈/深拷贝成本高；需要增量/结构共享，否则大脚本卡顿。

## 六、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 未实现 | — | — |

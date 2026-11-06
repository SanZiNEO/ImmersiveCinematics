# 路径集合：脚本级路径条目

> 状态：已实现（I4-8 关闭）
> 能力清单：顶层 `paths[]`；`PathEntry(id, target, from, to, axes, geometry, timing)`；端点按目标片段 `position` 节点时间引用并要求相邻；`geometry` 复用 `PathCurveRegistry`（`line` / `bezier` 及注册表其余类型）；`timing` 线性 / 关键帧；`PathCollectionSource` 经 `CameraKeyframeEvaluator#setPathBindingSource` 接入相机
> 范围：脚本级路径集合的归属、条目形态（目标片段 + 相邻端点对）、geometry（形状）、timing（时间映射）、多条目、时间基准、求值顺序、与采样属性的边界
> 关联：`rule-system.md`、`rule-time-gate.md`、`object-model.md`、`schema-and-types.md`、`track-camera.md`、`runtime-evaluation.md`、`migration.md`、`rule-implementation.md`

## 一、摘要

路径是脚本级独立集合中的声明，与规则集合并列，不是片段元数据、不是 value-only 字段、也不是新轨道。每条路径先引用一个目标片段，再引用该片段**目标位置节点序列**中的两个相邻关键帧作为端点，并持有该段的 `geometry` 与 `timing`。

**形状与运动分离**：

- `geometry` 决定路径的形状与总长度（`line` 直线 / `bezier` 三次贝塞尔）；
- `timing` 决定沿路径的推进（片段局部时间 → 归一化进度，缺省线性）。

两者互不修改：改 `geometry` 不改变 `timing` 的映射定义，改 `timing` 不改变形状。直线与曲线都是 `geometry` 的取值，**直线路径同样可以带 `timing`**。

**数量口径**：路径条目数量不设上限；同一片段可以有多条路径，各自持有自己的端点对与 `geometry`，互不覆盖。

## 二、为什么做

- 现行 `Clip.curve` 是一个片段一份控制点，隐式套到每个位置段：同一片段想给不同段不同形状、或给同一段并列多条候选路径时，没有承载位置。
- 只把条目定义成“曲线”时，直线段没有承载位置，也没有地方表达“沿这段路走多快”；速度只能由关键帧时间间接决定，无法在段内单独调控。
- `objects.curve` 前稿把形状当 value-only 属性，同样表达不了“目标片段 + 相邻关键帧对 + 形状 + 运动”。
- 把路径绑在全片段关键帧总编号上时，FOV／调色等对象增删帧会改变路径端点；端点相邻性必须只在目标位置序列内判断。

## 三、方案

### 3.1 归属

- 路径集合是**脚本级**的，与 `meta`、`timeline` 并列；不占用 `objects` 的路径键空间，不是新轨道。
- 条目只通过引用定位片段内容，不复制关键帧，不改变片段内部对象与关键帧的归属。
- 片段引用是稳定身份，不依赖片段在轨道中的下标或默认放置；同一片段被分支/规则在不同执行入口启动时，路径仍按该次执行的局部时间求值。目标片段可以内联在轨道，也可以是脚本级独立片段，不要求它在任何轨道里（见 `clip-identity.md`）。
- 集合在解析期建成，播放期只读；脚本替换 = 重建集合。

### 3.2 条目形态

- **PathDefinition（路径定义）**：目标片段引用 + 端点对 + `geometry` + `timing`；一条路径对应一段。
- 端点 = 该片段目标位置节点序列中的两个**相邻**关键帧；相邻性只在这个序列内判断，不把 FOV、调色、覆盖层等其它对象的帧拼成全片段总序列。
- 条目持有目标片段 + 端点引用；重排／插入／删除后必须重映射或报错，不得静默改绑。
- 一条路径一个绑定；同一片段可被多条路径引用。

### 3.3 形状（geometry）

- `geometry.type` 是形状枚举，首批 `line` / `bezier`，可扩展。
- `line`：端点之间直线，没有控制点，长度为端点直线距离。
- `bezier`：三次贝塞尔，两个内部控制点；分量可分别编辑；控制点参考空间显式（相对段起点／绝对世界轴）。
- 轴集合至少两个、去重、顺序无关；2 轴只保证该平面内 2D 弧长，不承诺 3D 匀速；要整条 3D 路径匀速就选 3 轴。
- 切线由本段路径导数与未选轴的采样属性导数合成；不把全片段位置帧强制成组。

### 3.4 运动（timing）

- `timing` 是时间映射：片段局部时间 `t ∈ [0, T]` → 归一化进度 `s ∈ [0, 1]`，单调不减。
- 缺省不存在 = 线性：`s(t) = t / T`。
- 非线性表达：一组单调关键帧 `{t, s}`（端点固定 0 与 1），运行时按单调插值。引用缓动定义不在当前实现内：`timing.type` 只接受 `linear` / `keyframes`。
- 求值顺序固定：

  ```text
  s_norm = timing(t)        // 0 → 1，单调
  s      = s_norm * L       // L = geometry 的总弧长
  P      = geometry(s)      // 路径点；geometry 本身不被修改
  ```

- 弧长参数化的含义：形状保持不变，按累计弧长 `s ∈ [0, L]` 索引，相当于把路径拉直成一根长度为 `L` 的绳子。线性 `timing` 下速度大小恒为 `L / T`；非线性 `timing` 下瞬时速度是 `ds/dt`，平均速度仍为 `L / T`。路径形状不直接进入速度公式，只通过 `L` 影响；`L` 是沿路径的路程，不是端点直线距离。
- 段边界的速度衔接由相邻路径条目的 `timing` 端点斜率表达，不新增“过渡窗口”机制；编辑器可提供“平滑接缝”自动生成匹配的端点斜率。

### 3.5 多条目与缺省

- 同一片段可以有多条路径；每条只负责自己的端点对与 `geometry`，互不覆盖。
- 没有路径条目的段按采样属性插值（端点直线 + 线性时间），等价 `line` + 线性 `timing`。
- 插入位置关键帧时，作者可以显式重建／重绑定以产生新几何；不得静默复用旧控制点改变路径。

## 四、技术细节

- 加载期编译 `片段 → 路径条目[]` 索引，运行期不扫全集合。
- 运行时在同一 `executionLocalTime` 采样全部属性；路径条目按目标片段与端点对求 `geometry`／`timing`，不进 `SampledClip`。
- 路径条目与规则集合分开承载、分开求值；规则不并入路径集合，也不改变路径几何。
- 迁移：旧 clip 级 `curve` 按旧全局关键帧段逐段展开为路径条目（`geometry.type = bezier`，控制点原样，`timing` 缺省线性）；旧段没有曲线时不生成条目（缺省直线 + 线性）；不能只把旧一份控制点搬进一条全片段路径。

## 五、边界

- 相机侧装配（路径与未选轴合成位置、`orient=tangent` 用条目导数）归 `track-camera.md`；本篇只定集合契约。
- 调色曲线（`rgb_curve`、每通道曲线、六条 hue 曲线）与 `transition_curve` 是 value-only 结构或缓动声明，**不属于**本集合。
- 路径条目不是完成判据，不成为新的总时长来源。
- 迁移（旧 `Clip.curve` → 路径条目）归 I-5（`migration.md`），不在本集合的运行时契约内。

## 六、结构评审项（已定稿：I4-1 / I4-8）

| 评审项 | 定稿口径 |
|---|---|
| 字段名与序列化 | 顶层 `paths[]`；条目 = `id` / `clip` / `from` / `to` / `axes`（可省，缺省三轴）/ `geometry`（必填）/ `timing`（可省，缺省线性）。见 `docs/SCRIPT_FORMAT.md` §5 |
| 稳定片段引用与端点引用 | 片段用 `id` 引用；端点用目标片段 `position.x/y/z` 节点时间（不是下标），要求 `from < to` 且相邻；插入关键帧不改变端点，删除端点 → 加载期错误（显式重映射） |
| `timing` 的序列化与校验 | `{"type":"linear"}` 或 `{"type":"keyframes","points":[{"t":..,"s":..}]}`：至少 2 点、`t` 与 `s` 序列单调不减、端点固定 0/1 |
| 多条路径与同一段并存 | 端点对完全相同 → 加载期错误；区间部分重叠合法，解析顺序 = `startTime` 最大者优先（与 `PathBindingResolver` 一致）；编辑器呈现归 `../Z-editor/object-model.md` |

## 七、实现结果

状态：已实现（I4-8 关闭）。

- **数据模型**：`script/path/PathEntry`（`record PathEntry(String id, Clip target, float from, float to, AxisConstraint axes, PathSpec geometry, PathTiming timing)`）、`PathCollection`（`EMPTY` / `entries()` / `entriesFor(Clip)` / `isEmpty()`）、`PathCollectionSource implements PathBindingSource`（按片段缓存绑定列表）。
- **解析 / 校验**（`PathCollectionParser`，错误即抛 `ScriptParseException` 带路径）：
  - `id` 必填唯一；`clip` 必须解析到带 `objects` 的片段；
  - `from` / `to` 必须命中目标片段 `position` 节点时间且 `to` 是 `from` 的下一个节点（相邻性只在 `position.x/y/z` 序列内判断）；
  - `axes` 缺省三轴，至少一项、去重、顺序无关；
  - `geometry` 必填，`line` 只接受 `type`，其余类型按 `PathCurveRegistry` 透传；
  - `timing` 缺省线性，`keyframes` 校验单调与端点 0/1；
  - 同一片段端点对完全相同 → 错误；部分重叠合法。
- **相机接线**：`CameraTrackPlayer` 按脚本切换 `evaluator.setPathBindingSource(new PathCollectionSource(script.getPathCollection()))`（无集合时 `PathBindingSource.NONE`）；`CameraKeyframeEvaluator#setPathBindingSource` 转发到 `ObjectCameraEvaluator`。路径与未选轴采样属性的合成归 `track-camera.md`。
- **使用边**：解析期 `UsageIndex.reference(PATH_ENTRY, ...)`，路径条目计入片段的 `PATH_ENTRY` 使用边。
- **未实现（I-5）**：旧 `Clip.curve` → 路径条目的迁移器（`migration.md`）；旧 `clip.curve` 读取链路仍在旧格式脚本里生效。

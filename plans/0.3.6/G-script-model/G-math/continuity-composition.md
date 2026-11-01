# 连续性与复合路径

> 注：本文的 G0/G1/G2 指几何连续性，不是脚本模型的 G1/G2 批次项。
> 状态：已实现（表达式与算子层）
> 范围：G0/G1/G2 定义、段间约束、复合路径、回旋线插入、校验与降级
> 关联：`expression-contract.md`、`curve-clothoid.md`、`curve-bezier.md`、`../../I-script-core/path-collection.md`

## 一、摘要

复合路径由多段曲线首尾相接组成；段间连续性不是“自动保证”，而是显式声明和校验的属性。G0 管位置、G1 管切线方向、G2 管曲率；需要曲率连续时可在段间插入回旋线过渡。复合路径本身也是路径表达式，可继续被弧长、轴约束等算子消费。

## 二、为什么做

### 2.1 问题

- 多段 Bézier 拼接时，端点位置可能不连续，切线可能折角，曲率可能跳变。
- 现有逐段路径策略没有“段间连续性”契约，作者只能靠控制点手工对齐。
- 相机快速运镜时，曲率阶跃会表现为顿挫；仅 G1 不够。
- 段间连续性如果不校验，错误会延迟到画面才暴露。

### 2.2 目标

- 明确 G0/G1/G2 的数学条件；复合路径可声明最低连续性。
- 段间连续性在加载/构建期校验；不满足时明确报错或降级并告警。
- 需要 G2 时可选回旋线过渡；不需要时不强行插入。
- 复合路径的段序列、端点绑定、参数映射与 I 轮路径条目解耦。

### 2.3 非目标

- 不追求全局 C2；G2 只保证几何曲率连续，不保证参数化高阶连续。
- 不自动改写作者控制点来“修复”连续性；修复必须是显式操作。
- 不把复合路径做成新轨道；它仍是路径表达式。

## 三、方案

作者/调用方组合多段路径：

- 每段可以是不同曲线类型；段间声明 `G0`/`G1`/`G2`。
- 构建期检查端点位置、切线方向、曲率；不满足按声明降级/报错。
- 直线→圆弧、圆弧→圆弧需要 G2 时，可插入回旋线过渡；过渡段由 `curve-clothoid.md` 定义。
- 复合路径对外仍是一个路径表达式：给 `u` 返回位置/导数；`u` 到段的映射由复合路径维护。

## 四、技术细节

### 4.1 核心模型

- 复合路径：有序段序列 + 段间连续性声明 + 可选过渡段。
- 每段有定义域；复合路径维护全局 `u` 到段 `u` 的映射（按段定义域跨度比例，详见 4.3）。
- 段间约束是声明，不是自动求解；控制点/切线由作者或生成器给出。

### 4.2 契约

- G0：`P_i(end)=P_{i+1}(start)`。
- G1：端点切线同向非零；`T_i(end) × T_{i+1}(start)=0` 且点积 > 0。
- G2：G1 + 曲率向量相等（含方向）；`κ=0` 时方向无定义，按零曲率处理；标量形式为 `κ_i(end)=κ_{i+1}(start)`。
- 实现的 G2 判据是**标量形式**：`Curvature.kappa`（`κ = |P'×P''|/|P'|³`，非负）在两端处的相对差；
  曲率向量方向（主法向是否同向）不在本轮校验范围内——需要该判据时先在段间插入过渡段，或由 I 轮自行比较主法向。
- 参数化：G 连续与参数化无关；C 连续要求参数导数也匹配。复合路径只承诺 G，不承诺 C。
- 校验：端点距离、切线夹角、曲率误差设容差（`κ≈0` 用绝对误差）；不满足按声明降级（G2→G1→G0）并告警。
- 回旋线：G2 声明下，若相邻段曲率不匹配，可插入过渡；过渡端点曲率匹配两侧。
- 复合路径的 `u` 映射：按段定义域跨度比例分配（不是弧长）；段边界 `u` 明确，不跳变。

### 4.3 数据形态

已落地两个类型，都在 `math/path/`，都不进注册表（复合路径是算子层，不是作者面曲线）。

**`Continuity`**（`Continuity.java`）——纯静态算子，无状态：

- `enum Order { G0, G1, G2 }`，`ordinal()` 递增即阶次递增。
- `record Report(Order declared, Order achieved, double positionGap, double tangentAngle, double curvatureError)`；
  `satisfied()` = `achieved != null && achieved.ordinal() >= declared.ordinal()`。
- 容差常量：`POSITION_TOLERANCE = 1e-9`（世界单位）、`TANGENT_TOLERANCE = 1e-6`（rad）、
  `CURVATURE_TOLERANCE = 1e-6`（相对）、`CURVATURE_ABSOLUTE_TOLERANCE = 1e-9`（两端 κ 绝对值都 `< 1e-9` 时改用绝对差）。
- `check(left, right, declared)`：取 `left.domain().max()` 与 `right.domain().min()`；位置距离直接量，
  切向夹角 `acos(clamp(P'·P' / (|P'||P'|)))` 落在 `[0, π]`（反向给 π，因此「同向」已包含在角度里），
  任一端 `|P'| < 1e-12` 时夹角记 `+Inf` 直接判不满足；曲率取 `Curvature.kappa`。
- `achieved` 从 G2 往下退：三项全过 → G2；位置与切向过 → G1；只有位置过 → G0；位置也不连 → `null`。
  降级只记录，不改几何。

**`CompositePath`**（`CompositePath.java`）——`PathExpression` + `AnalyticArcLength`：

- 构造：`CompositePath(List<PathExpression> segments, Continuity.Order declared)`；段数 ≥ 1、元素非 null、
  `declared` 非 null，否则 IAE。段序列与接缝报告都 `List.copyOf` 成只读。
- **`u` 映射**：`fraction_i = span_i / Σspan`，段起点 `uStart_i = (Σ_{j<i} span_j) / Σspan`；
  全局 `u` 落在第 `i` 段时局部参数 `= seg.min + (u - uStart_i) · Σspan`，再 clamp 到该段定义域。
  段边界左闭右开（`u == uStart_{i+1}` 归后一段），因此 `u = 0` 精确落在首段起点、`u = 1` 精确落在末段终点。
  定义域恒为 `PathDomain.UNIT`，`type()` 为 `"composite"`。
- **链式求导**：每段的 `span_i / fraction_i` 都等于 `Σspan`（同一条曲线的 `dv/du`），所以
  `derivative(u) = seg'(u_local) · Σspan`、`secondDerivative(u) = seg''(u_local) · (Σspan)²`、
  `speed(u) = seg.speed(u_local) · Σspan`；`speed` 与 `|derivative|` 同源。
- **校验与降级**：构造期对每个相邻接缝跑 `Continuity.check`，结果以只读 `List<Report>` 保存；
  `achieved()` 取所有接缝的最低阶次，任一处 `achieved == null` 则整体 `null`，无接缝视为 G2。
  声明高于实际不报错也不改控制点，由调用方决定告警或插入回旋线过渡（见 `curve-clothoid.md`）。
- **能力**：`ANALYTIC_SECOND_DERIVATIVE` 仅当所有段都声明；`ANALYTIC_ARC_LENGTH` 仅当所有段都实现
  `AnalyticArcLength`。弧长恒可算：解析段走解析式、其余段按裁剪区间退回 `ArcLength` 数值积分，故
  `ArcLength` 的 `instanceof` 快路径在任何段组合下都不会算错。
- **退化**：1 段且该段定义域为 UNIT 时与委派逐位相同（`Σspan = 1`，`u` 映射为恒等）；其他定义域按
  `u` 线性映射回段域，端点仍精确。

### 4.4 边界

- 复合路径不负责“哪段对应哪个片段/关键帧”；那是 I 轮路径条目的职责。
- 复合路径不自动修复控制点；修复是编辑器/生成器的显式操作。
- 复合路径不保证时间连续；时间映射在播放层。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 每段独立、不校验 | 段间靠作者手工对齐 | 折角/曲率跳变延迟暴露 | 显式声明 + 校验 |
| 全局样条 | 一条 B-spline/三次样条贯穿所有点 | 失去段级类型/绑定；局部调整难 | 复合路径保留段级类型 |
| 自动求解控制点 | 构建期强行 G2 | 改写作者意图；可能无解 | 只校验/降级，不自动改 |
| 全部用回旋线 | 每段都过渡 | 过度设计；多数段不需要 G2 | 按需插入 |

**本方案的缺点**：

- G2 校验/回旋线插入增加构建复杂度。
- 段间连续性降级会改变观感；必须告警。
- 复合路径的 `u` 映射与段定义域/弧长相关，跨段匀速需要额外处理。

## 六、实现结果

状态：已实现（表达式与算子层）。回旋线过渡段**插入策略**未实现——`ClothoidPathSpec`（`curve-clothoid.md`）
已提供过渡几何，「何时插、插多长」留给 I 轮路径条目，本轮只做校验与降级记录。

- `math/path/Continuity.java`：`Order` / `Report` / 四个容差常量 / `check(left, right, declared)`。
- `math/path/CompositePath.java`：跨段 `u` 映射、接缝校验缓存、链式求导、逐段裁剪累加弧长。
- 未新增作者面类型：`composite` 不进 `PathCurveRegistry`。

### 6.1 实测（`E:/tmp/gcurve/Probe.java`，470 项检查全部通过）

接缝夹具用圆弧族（圆心 `(0,64,0)`，法向 +Y，圆落在 XZ 平面，`v = normal × u`）：

| 用例 | 实测 | 判定 |
|---|---|---|
| G2 接缝（同圆续弧 R=10，0°→90° 接 90°→180°） | gap `0.000e+00`、angle `0.000e+00`、κ 相对误差 `2.776e-16` | `achieved = G2` |
| G1 接缝（R=10 接 R=5，位置与切线对齐） | gap `3.062e-16`、angle `0.000e+00`、κ 相对误差 `5.000e-01` | `achieved = G1` |
| G0 接缝（位置对齐、切线折 90°） | gap `6.123e-16`、angle `1.571e+00` | `achieved = G0` |
| 位置不连（错开 45° 的另一条弧） | gap `7.654e+00` | `achieved = null`，`satisfied() = false` |
| 切线退化（零长 Bézier 接圆弧） | angle `+Inf`、gap `8.882e-16` | `achieved = G0` |
| 降级方向 | 声明 G2/实际 G1 → `false`；声明 G1/实际 G2 → `true`；声明 G0/实际 G2 → `true` | 符合契约 |

复合路径（段 1 = 三次 Bézier 跨度 1，段 2 = 三次 B-spline 显式节点 `{0,0,0,0,3,3,3,3}` 跨度 3）：

| 用例 | 实测 |
|---|---|
| `uStart` / `uFraction` | `{0, 0.25}` / `{0.25, 0.75}`（`uStart(1) == 0.25` 精确） |
| `position(0.25)` vs 首段末端 | 距离 `0.000e+00`（≤1e-12） |
| `position(0)` / `position(1)` vs 首段首 / 末段末 | 均为 `0.000e+00` |
| `u = 0.125` → 首段局部 0.5 / `u = 0.625` → 末段局部 1.5 | 均为 `0.000e+00` |
| 单段复合 vs 委派（101 点） | position / derivative / speed / secondDerivative 全部逐位相同 |
| 复合 `derivative` vs 中心差分（98 点，跳过接缝） | 最大偏差 `1.511e-08`（≤1e-6） |
| `speed` vs `derivative` 模长 | `0.000e+00`（≤1e-12） |
| 两段 90° 圆弧（R=10）复合 `arcLength(0,1)` | `31.415926535898` = 两段解析弧长之和（偏差 0） |
| 裁剪 `(0,0.25)` / `(0.5,1)` / `(0.25,1)` | `7.853981633974` / `15.707963267949` / `23.561944901923`，与逐段解析值偏差 ≤1e-12 |
| 混合（arc + B-spline）`arcLength(0,1)` | `38.276959375117`，解析段 + 数值段逐段累加，偏差 0；不声明 `ANALYTIC_ARC_LENGTH` |
| 逐次分配（1e5 次差分口径） | `position` 额外 `0B`、`speed` 额外 `0B`；`derivative` 额外 `2.0e5B`（≤1 个中间向量/次，JIT 依赖） |

注：`CompositePath` 是 `final` 类，Java 无法「有条件地实现接口」，所以它固定实现 `AnalyticArcLength`，
解析与否通过 `supports(ANALYTIC_ARC_LENGTH)` 表达；`arcLength` 内部对非解析段退回数值积分，
因此该 `instanceof` 恒为真不会导致错误结果。



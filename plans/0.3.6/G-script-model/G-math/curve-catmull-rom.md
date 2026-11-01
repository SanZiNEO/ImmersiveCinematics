# Catmull-Rom 路径

> 状态：已实现（`math/path/CatmullRomPathSpec`，注册名 `catmull_rom`）
> 范围：uniform/chordal/centripetal Catmull-Rom、切线公式、过点插值、端点处理、闭合
> 关联：`expression-contract.md`、`curve-hermite.md`、`continuity-composition.md`、`example/math/code/threejs/CatmullRomCurve3.js`

## 一、摘要

Catmull-Rom 是一类过点插值的三次样条：曲线经过每个控制点，切线由相邻点自动计算，段间达到 C1。它有 uniform、chordal、centripetal 三种参数化；centripetal 最稳，能减少尖点和自交。它适合“相机必须经过这些关键帧”的路径，和 B-spline 的“逼近控制点”形成互补。

## 二、为什么做

### 2.1 问题

- Bézier 只过首末端点，中间控制点不过；作者想“路径必须经过这些位置”时要拆段/调控制点。
- 三次样条要解全局线性方程组，移动一个点会影响整条曲线。
- 现有计划没有 Catmull-Rom 的参数化/端点/闭合口径，作者无法判断它和 Hermite 的边界。

### 2.2 目标

- 提供过点插值的 Catmull-Rom 原语：给点序列，曲线经过所有点，段间 C1。
- 支持 uniform/chordal/centripetal 三种参数化；默认 centripetal。
- 端点用钳制/镜像/重复点处理；闭合路径首尾相接。
- 明确它不精确表示圆/椭圆，也不提供局部形状控制。

### 2.3 非目标

- 不把 Catmull-Rom 当作精确圆锥工具。
- 不替代 B-spline 的局部控制/高阶连续性。
- 不在此篇处理显式切线；显式切线归 Hermite。

## 三、方案

作者选 `catmull-rom`：

- 给点序列（路径必须经过这些点）；可选参数化类型 `uniform` / `chordal` / `centripetal`。
- 默认 centripetal，减少尖点；需要“更松/更紧”的转弯时调 tension 或改用 Hermite。
- 端点默认镜像/钳制；闭合路径首尾重复或显式 `closed`。
- 需要精确圆/椭圆时用 `arc`/`ellipse`，不要用 Catmull-Rom 硬拟合。

## 四、技术细节

### 4.1 核心模型

- 一段 Catmull-Rom 由四个相邻点 `P_{i-1}, P_i, P_{i+1}, P_{i+2}` 定义，曲线段从 `P_i` 到 `P_{i+1}`。
- 切线：uniform 用 `(P_{i+1}-P_{i-1})/2`；chordal/centripetal 按相邻点距离的 α 次幂缩放参数间隔。
- 段间在 `P_i` 处共享切线，达到 C1；不保证 C2。

### 4.2 契约

- 位置：三次 Hermite 形式，端点 `P_i/P_{i+1}`，切线由相邻点计算；或直接写三次系数。
- 参数化：`t_{i+1}=t_i+|P_{i+1}-P_i|^α`；uniform α=0；chordal α=1；centripetal α=0.5（推荐）。
- 切线（Barry–Goldman）：`m_i=[(P_i-P_{i-1})/(t_i-t_{i-1})·(t_{i+1}-t_i)+(P_{i+1}-P_i)/(t_{i+1}-t_i)·(t_i-t_{i-1})]/(t_{i+1}-t_{i-1})`；uniform 时退化为 `(P_{i+1}-P_{i-1})/2`。
- 不同实现的非均匀切线公式可能有差异；验收按上式对拍，不以某个库的实现为准。
- 端点：首点切线用前向差分/镜像点；末点用后向差分/镜像点；闭合时首尾环绕取点（`P_{-1}=P_{n-1}`、`P_{n}=P_0`）。
- 弧长：无闭式解；数值积分/LUT；段边界是控制点。
- 过点性：曲线经过所有输入点；这是它与 B-spline 的核心区别。

### 4.3 数据形态

JSON 形态（CAMERA 片段上的 `curve` 对象；`type` 取注册名 `catmull_rom`）：

| 字段 | kind | 必填 | 缺省 | 语义 |
|---|---|---|---|---|
| `points` | `POINT_LIST` | 是 | — | 内部过点（不含端点），端点由关键帧给出；`{x,y,z}` 绝对 / `{dx,dy,dz}` 相对段起点 |
| `parameterization` | `ENUM` | 否 | `centripetal` | `uniform`（α=0）/ `chordal`（α=1）/ `centripetal`（α=0.5） |
| `tension` | `NUMBER` | 否 | `1.0` | 张力，取值 `[0,1]`；乘在 Barry–Goldman 切线上，0 = 切线全零 |
| `endpoint` | `ENUM` | 否 | `mirror` | 端点幽灵点策略：`mirror`（`2P₀-P₁`）/ `clamp`（重复端点） |

- 未知字段 / 未知枚举取值 / `tension` 越界 / `points` 含 null 一律解析期报错（`PathCurveException`），不静默回落缺省。
- 闭合（`closed`）本期未开放：注册表只接受上表四个字段；需要首尾相接时把首点重复到末点。
- 缓存键包含点、参数化、tension、endpoint；点变化使弧长/求值缓存失效。

### 4.4 边界

- Catmull-Rom 不提供局部形状控制；要局部调整用 B-spline 或 Bézier。
- Catmull-Rom 不精确表示圆/椭圆；精确圆锥归 `exact-conics.md`。
- Catmull-Rom 不处理显式切线；需要指定切线时用 Hermite。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 三次样条 | 解全局方程组，C2 | 非局部；移动一点影响全局 | Catmull-Rom 局部、无需解方程 |
| Hermite | 显式给切线 | 作者要算/填切线 | Catmull-Rom 自动切线更适合“只给点” |
| B-spline | 逼近控制点，局部 | 不过点 | 需要过点时 Catmull-Rom 更直接 |
| Bézier 多段 | 每段两个控制点 | 过点要手工调控制点 | Catmull-Rom 自动过点 |

**本方案的缺点**：

- 不过点就不是 Catmull-Rom；需要局部控制时它不适用。
- 端点处理会影响首末段形状，必须显式声明。
- 均匀参数化在点距差异大时可能尖点/自交；centripetal 更稳但不是万能。

## 六、实现结果

已实现 `math/path/CatmullRomPathSpec`（内嵌 `Parameterization` / `EndpointMode` 两个枚举），并在
`script/schema/PathCurveRegistry` 注册 `catmull_rom`（能力 `ANALYTIC_SECOND_DERIVATIVE`）。

求值口径：

- 点序列 = `[from, points..., to]`，`n = 点数-1` 段；`points` 的 `relative` 形态按 `PathPoint.resolve(from)` 解析。
- 参数化 `t₀=0`、`t_{i+1}=t_i+max(|P_{i+1}-P_i|, 1e-9)^α`；`1e-9` 地板让重合点仍有正参数间隔（不出现 0 除）。
- 端点幽灵点按 §4.2 取（`MIRROR` / `CLAMP`），幽灵参数同式。
- 切线 Barry–Goldman 再乘 `tension`；段 i 用三次 Hermite，`T0 = m_i·(t_{i+1}-t_i)`、
  `T1 = m_{i+1}·(t_{i+1}-t_i)`（把对 `t` 的导数换算到局部 `[0,1]` 参数）。
  位置/一阶/二阶导均为解析式，`speed` 与 `derivative` 共用同一组私有标量辅助方法（`dx/dy/dz`），逐帧零分配。
- `u∈[0,1]` 按段数均分：`g = u·n`、`i = min(n-1, ⌊g⌋)`、`s = g-i`；`dP/du = n·dP/ds`。
- 连续性：对参数 `t` 是 C1（相邻两段在 `P_i` 处共享同一个 `m_i`）。由于 `u` 按段数均分、各段 `t` 间隔不必相等，
  `dP/du` 在段边界按 `(t_{i+1}-t_i)/(t_i-t_{i-1})` 缩放——几何切线方向连续，只有等距点时 `dP/du` 才逐点连续。
- 退化：相邻点重合由 `1e-9` 地板兜住（无 NaN/Inf）；全部点重合时切线恒零、位置恒等、弧长 0。

实测（无头探针，仓库外运行；fixture `cinematics/0.3.6/tests/camera/test_curve_catmull_rom.json` 解析 + 校验 0 issue）：

| 检查 | 结果 |
|---|---|
| 过点性：非均匀 5 点，`u=i/4` 处位置 vs 第 i 个点（三种参数化） | 0（容差 1e-12） |
| C1（t 参数域）：非均匀 5 点内部点左右导数差（三种参数化） | 1.6e-15（容差 1e-9） |
| C1（u 参数域）：等距 5 点内部点左右导数差（三种参数化） | 2.7e-15（容差 1e-9） |
| u 域导数按 dt 比缩放：`dP/du(左)·dt_i/dt_{i-1}` vs `dP/du(右)` | 1.6e-15（容差 1e-9） |
| uniform 等距：`P_i` 处 `dP/dt` vs `(P_{i+1}-P_{i-1})/2` | 0（容差 1e-12） |
| uniform 等距：`P_i` 处 `dP/du` vs `n·(P_{i+1}-P_{i-1})/2` | 0（容差 1e-12） |
| 三种参数化段内位置互差（非均匀 5 点，u = 0.3/0.375/0.6/0.7） | ≥ 2.3e-2（要求 > 1e-6） |
| `tension=0.5` vs `1.0` 段内位置差 | ≥ 2.0e-1（要求 > 1e-6） |
| `derivative` vs 中心差分（h=1e-6，避开段接缝） | 7.8e-10（容差 1e-6） |
| `secondDerivative` vs 一阶导中心差分 | 5.5e-9（容差 1e-5） |
| `speed(u)` vs `|derivative(u)|` | 0（容差 1e-12） |
| `speed` 1e6 次调用分配增量 | 0 字节 |
| 相邻点重合（`p,p,q,q,r`，三种参数化，101 点） | 无 NaN/Inf |
| 全部点相同：位置恒等 / `speed` / `length()` | 8.9e-16 / 0 / 0 |
| 非法参数：`tension` <0 / >1 / NaN、内部点 null、内部点列表 null | IAE |

注：`u=0.5` 在 4 段时正好落在过点 `P₂` 上（三种参数化必然同点），参数化/张力差异只能在段内采样看到。



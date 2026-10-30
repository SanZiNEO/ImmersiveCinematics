# Catmull-Rom 路径

> 状态：未实现
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

- 类型 `catmull-rom`；点数组、参数化类型、可选 tension、`closed`。
- 端点处理策略显式声明（mirror/clamp/repeat），不隐式猜。
- 缓存键包含点、参数化、tension、closed；点变化使弧长/求值缓存失效。

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

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| — | — | — |

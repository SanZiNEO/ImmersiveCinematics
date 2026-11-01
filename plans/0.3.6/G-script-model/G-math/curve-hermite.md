# 三次 Hermite 路径

> 状态：已实现（`math/path/HermitePathSpec`，注册名 `hermite`）
> 范围：三次 Hermite 基函数、端点位置/切线、C1、切线模式、与 Catmull-Rom/Bézier 的关系
> 关联：`expression-contract.md`、`curve-catmull-rom.md`、`curve-bezier.md`、`continuity-composition.md`、`example/math/notes/wisconsin_cs412_lecture13_cubic_hermite.pdf`

## 一、摘要

三次 Hermite 用每段两端的位置和切线定义曲线：位置保证过端点，切线控制进出方向，段间共享切线即可 C1。它比 Bézier 更直接地表达“从这里以这个方向进、从那里以那个方向出”，比 Catmull-Rom 更可控；代价是作者要提供切线，或由切线模式自动生成。

## 二、为什么做

### 2.1 问题

- Bézier 的控制点不直观：作者想要“端点切线方向”，却要反推控制点位置。
- Catmull-Rom 自动切线，但作者不能直接指定“这一段进出方向”。
- 相机路径经常需要“从某个方向进入、从某个方向离开”，需要显式切线控制。

### 2.2 目标

- 提供三次 Hermite 原语：端点位置 + 端点切线；曲线过端点，段间 C1。
- 切线支持手动、Catmull-Rom 自动、有限差分三种模式；默认 Catmull-Rom。
- 明确它与 Bézier 的等价关系：Bézier 控制点可由 Hermite 端点/切线换算。
- 弧长走数值积分/LUT；不精确表示圆。

### 2.3 非目标

- 不把 Hermite 当作精确圆锥工具。
- 不替代 B-spline 的局部控制/高阶连续性。
- 不在此篇处理节点向量；B-spline 单独成篇。

## 三、方案

作者选 `hermite`：

- 给每段两端位置和切线；切线可以手填，也可以选“自动（Catmull-Rom）”或“有限差分”。
- 段间共享端点切线即 C1；要 G1 只需切线同向，不要求模长相等。
- 需要“过点但不想填切线”时用 Catmull-Rom；需要“显式方向”时用 Hermite。
- 需要精确圆/椭圆时用 `arc`/`ellipse`。

## 四、技术细节

### 4.1 核心模型

- 一段三次 Hermite 由 `P0, P1, T0, T1` 定义：两端位置 + 两端切线。
- 基函数：`h00=2u^3-3u^2+1`、`h10=u^3-2u^2+u`、`h01=-2u^3+3u^2`、`h11=u^3-u^2`。
- 位置 `H(u)=h00 P0 + h10 T0 + h01 P1 + h11 T1`。

### 4.2 契约

- 位置：上式；`H(0)=P0`、`H(1)=P1`。
- 导数：`H'(u)` 由基函数导数组合；`H'(0)=T0`、`H'(1)=T1`。
- 切线模式：手动（作者给）、Catmull-Rom（`(P_{i+1}-P_{i-1})/2`）、有限差分（前向/后向/中心）。
- 连续性：段间共享切线即 C1；仅同向则 G1；曲率不保证 G2。
- 与 Bézier 等价：`P1 = P0 + T0/3`、`P2 = P3 - T1/3`；可互相换算。
- 弧长：无闭式解；数值积分/LUT；段边界是端点。

### 4.3 数据形态

JSON 形态（CAMERA 片段上的 `curve` 对象；`type` = `hermite`）：

| 字段 | kind | 必填 | 缺省 | 语义 |
|---|---|---|---|---|
| `tangents` | `POINT_LIST` | 是 | — | 恰好 2 项：`[首端切线, 末端切线]`；数量不是 2 直接报错 |

`tangents` 的两种形态（逐项独立判定，可与另一种混用）：

| 形态 | 写法 | 解析 |
|---|---|---|
| 绝对 | `{x,y,z}` | 切向量就是该世界坐标值本身 |
| 相对 | `{dx,dy,dz}` | 按 `PathPoint.resolve(端点)` 解析：`T0 = from + (dx,dy,dz)`、`T1 = to + (dx,dy,dz)` |

- 相对切线的基点是**各自的端点**（首端相对 `from`、末端相对 `to`），不是世界原点；因此相对形态不是纯方向向量，
  其模长随端点位置平移。要「只给方向、不随端点漂移」请写绝对切线。
- 未知字段 / `tangents` 数量不是 2 / 元素为 null 一律解析期报错（`PathCurveException`），不静默回落。
- 切线模式（手动 / Catmull-Rom 自动 / 有限差分）本期只开放手动：需要自动切线时用 `catmull_rom`。
- 缓存键包含两端切线与端点。

### 4.4 边界

- Hermite 不提供节点向量/局部支撑；需要 B-spline 时用 B-spline。
- Hermite 不精确表示圆/椭圆；精确圆锥归 `exact-conics.md`。
- Hermite 不负责时间重映射；`u/s/t` 分工同 `expression-contract.md`。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| Catmull-Rom | 自动切线，过点 | 不能直接指定切线方向 | 需要显式方向时 Hermite 更合适 |
| Bézier | 控制点间接表达切线 | 作者要反推控制点 | Hermite 直接给切线 |
| B-spline | 节点/局部支撑 | 不过点；作者要理解节点 | 过点+显式切线用 Hermite |
| 三次样条 | 全局解方程，C2 | 非局部；移动一点影响全局 | Hermite 局部、无需解方程 |

**本方案的缺点**：

- 作者要提供/理解切线；切线模长会影响曲率和速度。
- 段间只保证 C1，不保证 C2；G2 需要额外约束。
- 弧长仍要数值方法。

## 六、实现结果

已实现 `math/path/HermitePathSpec`，并在 `script/schema/PathCurveRegistry` 注册 `hermite`
（能力 `ANALYTIC_SECOND_DERIVATIVE`）。

求值口径：

- `T0 = tangentStart.resolve(from)`、`T1 = tangentEnd.resolve(to)`；位置按 §4.1 基函数原式，
  `derivative` / `secondDerivative` 是同一组基函数的解析导数：
  一阶 `6u²-6u / 3u²-4u+1 / -6u²+6u / 3u²-2u`，二阶 `12u-6 / 6u-4 / -12u+6 / 6u-2`。
- `speed` 与 `derivative` 共用同一组私有标量辅助方法（`dx/dy/dz`），逐帧调用零分配；
  一阶与二阶导都走同一条标量组合路径，只是系数不同。
- 定义域 `UNIT`、锚点 `ENDPOINTS`；`P0=P1` 且 `T0=T1=0` 时位置恒为 `P0`、导数为零、弧长 0。

实测（无头探针，仓库外运行；fixture `cinematics/0.3.6/tests/camera/test_curve_hermite.json` 解析 + 校验 0 issue）：

| 检查 | 结果 |
|---|---|
| 与三次 Bézier 等价（`T0=3(P1-P0)`、`T1=3(P3-P2)`）位置最大偏差（101 点） | 2.8e-14（容差 1e-12） |
| 同上 一阶导最大偏差 | 2.3e-14（容差 1e-10） |
| 同上 二阶导最大偏差 | 8.6e-14（容差 1e-9） |
| 端点 `position(0)` / `position(1)` vs `P0` / `P1` | 0 / 0（容差 1e-12） |
| 端点 `derivative(0)` / `derivative(1)` vs `T0` / `T1`（relative 各自相对自己的端点） | 0 / 0（容差 1e-12） |
| `derivative` vs 中心差分（h=1e-6） | 1.0e-9（容差 1e-6） |
| `secondDerivative` vs 一阶导中心差分 | 5.2e-10（容差 1e-5） |
| `speed(u)` vs `|derivative(u)|` | 0（容差 1e-12） |
| `speed` 1e6 次调用分配增量 | 0 字节 |
| 全同（`P0=P1`、切线零）：位置恒等 / `speed` / `length()` | 8.9e-16 / 0 / 0 |
| 非法参数：`tangents` 数量 ≠ 2、切线 null | IAE |

与 Bézier 的换算在实现里不特殊处理：`T0=3(P1-P0)`、`T1=3(P3-P2)` 代入即逐点重合（上表首行）。



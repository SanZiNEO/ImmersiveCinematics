# G-math：数学表达式与路径底层

> 状态：实现中（现有三次 Bézier 路径 + 弧长 LUT；其余曲线/算子未实现）
> 范围：数学表达式的定义/实例/值域/能力边界；路径曲线原语（Bézier、有理 Bézier、B-spline、NURBS、Catmull-Rom、Hermite、回旋线、Fourier）；精确圆锥、弧长参数化与 LUT、连续性与复合、轴约束、验收
> 关联：`../script-model.md`、`../clock-abstraction.md`、`../../I-script-core/path-collection.md`、`example/math/README.md`

本页只做索引和决策记录，不重复各文档正文。一个点一篇文档；接口、公式、schema 以各文档为准。

## 一、文档地图

| 文档 | 负责什么 | 状态 |
|---|---|---|
| [expression-contract.md](expression-contract.md) | 表达式定义/实例、`u/s/t`、最小公共契约、能力接口、算子边界 | 未实现 |
| [curve-bezier.md](curve-bezier.md) | 三次/二次 Bézier：控制点、导数、端点切线、G1、凸包、现有语义 | 实现中 |
| [curve-rational-bezier.md](curve-rational-bezier.md) | 有理 Bézier：权重、齐次坐标、精确圆锥、导数与退化 | 未实现 |
| [curve-bspline.md](curve-bspline.md) | B-spline：节点向量、次数、Cox–de Boor、局部支撑、连续性 | 未实现 |
| [curve-nurbs.md](curve-nurbs.md) | NURBS：权重、节点、精确圆/椭圆、与 B-spline/有理 Bézier 的关系 | 未实现 |
| [curve-catmull-rom.md](curve-catmull-rom.md) | Catmull-Rom：uniform/chordal/centripetal、切线、过点插值、端点 | 未实现 |
| [curve-hermite.md](curve-hermite.md) | 三次 Hermite：基函数、显式切线、C1、与 Catmull-Rom 的关系 | 未实现 |
| [curve-clothoid.md](curve-clothoid.md) | 回旋线/Euler 螺旋：曲率线性、Fresnel、G1/G2 过渡、长度 | 未实现 |
| [curve-fourier.md](curve-fourier.md) | Fourier 路径：周期/非周期、端点约束、拟合、阶数 | 未实现 |
| [exact-conics.md](exact-conics.md) | 精确圆/椭圆：有理二次、NURBS、arc 原语、与 3×120° 近似的关系 | 未实现 |
| [arc-length.md](arc-length.md) | 通用弧长参数化：积分、反解、动态控制点、退化 | 实现中 |
| [arc-length-lut.md](arc-length-lut.md) | 弧长查找表：构建、查询、精度、缓存、零分配 | 实现中 |
| [continuity-composition.md](continuity-composition.md) | G0/G1/G2、复合路径、段间约束、回旋线插入、降级 | 未实现 |
| [axis-constraints.md](axis-constraints.md) | 轴约束：控制点约束 / 切空间 / 投影、对精确性与弧长的影响 | 未实现 |
| [acceptance.md](acceptance.md) | 精确圆锥、弧长、连续性、退化、性能的验收口径 | 未实现 |

## 二、边界

- G-math 只定义数学表达式与路径几何。I 的 `path-collection.md` 负责“路径条目挂到片段/相邻端点”，引用 G-math 的类型与算子，不把路径数学塞进 I。
- 不重开脚本模型的 G1/G2 批次项（分量寻址/字段能力，仍归 I）；本目录的 G1/G2 仅指几何连续性。不往 B 轮已关单的 `math-models.md` 加内容。
- 不碰编辑器/应用层；Z 只消费 schema 与生成产物。
- 曲线类型并列，各自保留自己的数学表示与接口；只保留最小公共契约，不强行统一成一种表达式。
- 本文公式中的 `θ`/角度除特别注明外均为弧度；脚本作者面的角度单位（度/弧度）由 schema 声明，不混用。

## 三、参考包

`example/math/README.md` 是完整索引（`example/` 已被 `.gitignore` 忽略，不进提交）。

- 主参考：`example/math/books/curve-notes/` 四份教材笔记（Bézier / 样条 / 光栅化 / 曲线基础）。
- 代码参考：`example/math/code/threejs/`（MIT，Curve / CubicBezierCurve3 / CatmullRomCurve3 / CurvePath 等）、`example/math/code/godot/`（MIT，Curve2D/Curve3D 烘焙与长度 LUT）。
- 论文/讲义：`example/math/notes/`、`example/math/papers/`，覆盖有理 Bézier 精确圆锥、弧长参数化、回旋线、Fourier、数值积分。
- 本地参考：`example/bbs-mod-1.20.1`（MIT，Hermite/Catmull-Rom/Bézier；`Interpolations.BEZIER` 是 stub，真正实现在 `BezierUtils`）、`example/ImmersiveCinematics-main`（二次 Bézier）。`example/ReplayMod-stable` 只有接线、没有样条源码，不作算法参考。

## 四、参考缺口

- 没有本地 Bézier 专用弧长论文：Gravesen 1997 被 403，只留引用；现有弧长参考是样条通用论文 + 数值积分讲义 + `ArcLengthLUT` 实现。
- 没有通用 3D Fourier 路径拟合教程：现有两篇分别是 2D 车辆轨迹和航天器轨迹。
- 没有回旋线/NURBS 的实现代码：只有论文/讲义；回旋线需要 Fresnel 求值，NURBS 需要节点/权重实现。
- 现有 3×120° 拼圆的径向误差约 0.154% R；4×90° 约 0.0273% R；8×45° 约 0.000425% R（本仓计算，R=1）。

## 五、阅读顺序

先读 `expression-contract.md`（定 `u/s/t`、最小公共契约与能力接口），再读曲线家族（Bézier → 有理 → B-spline → NURBS → Catmull-Rom → Hermite → 回旋线 → Fourier），然后精确圆锥、弧长/LUT、连续性与复合、轴约束，最后 `acceptance.md`。

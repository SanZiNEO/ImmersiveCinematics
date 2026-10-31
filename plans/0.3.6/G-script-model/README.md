# G 轮：脚本模型 / 时钟 / 数学底层 / 编辑器播放

> 状态：G6 已完成；G1/G2 归 I 轮；G3 延后；G4 时钟抽象收尾未做；G-math 15 篇中 3 篇实现中、12 篇未实现；editor-playback 未实现。
> 范围：脚本数据模型的既有基线、时钟抽象、数学曲线/路径底层、编辑器播放语义。
> 关联：[脚本模型](script-model.md)、[时钟抽象](clock-abstraction.md)、[G-math](G-math/README.md)、[编辑器播放](editor-playback.md)、[I 轮对象模型](../I-script-core/README.md)、[Z 编辑器](../Z-editor/README.md)。

本页只做索引与排期，不重复各文档正文。

## 一、摘要

G 轮分四块：脚本模型的既有基线（容器、片段重叠、实例状态）、时钟抽象（单调秒 + 双时钟源）、G-math（表达式契约与曲线/路径数学）、编辑器播放语义（编辑器播放 = 另一种时钟源）。G1/G2 的对象与字段目标已并入 I 轮；G3（meta 关键帧化）延后；G5（编辑器）归 Z。

## 二、文档地图

| 文档 | 负责什么 | 状态 |
|---|---|---|
| [script-model.md](script-model.md) | 脚本容器、片段重叠、每实例状态、既有字段插值基线 | 既有部分已落地；G1/G2 归 I，G3 延后 |
| [clock-abstraction.md](clock-abstraction.md) | 单调秒、GameClock / PreviewClock、实例时钟注入、double/float 边界 | 接口与两实现已落地；double 化、去死参数、覆盖层帧间隔、存储边界未执行 |
| [editor-playback.md](editor-playback.md) | 编辑器播放 = 播放引擎的另一种时钟源；内容行为参与范围 | 未实现 |
| [G-math/README.md](G-math/README.md) | 表达式契约、曲线家族、精确圆锥、弧长/LUT、连续性、轴约束、验收 | 三次 Bézier + 弧长 LUT 已有；其余未实现 |

## 三、排期

### 批次 0：表达式契约（前置）

- `G-math/expression-contract.md`：定义 `u/s/t`、最小公共契约、能力接口、算子边界。
- 产出：所有曲线的统一调用面；后续曲线与 I 轮路径集合都引用这套契约。
- 依赖：无。

### 批次 1：曲线家族（可并行）

| 子批 | 文档 | 产出 |
|---|---|---|
| 1a | `curve-rational-bezier.md` + `exact-conics.md` | 有理 Bézier；精确圆/椭圆（替代 3×120° 近似） |
| 1b | `curve-bspline.md` + `curve-nurbs.md` | B-spline（节点/Cox–de Boor）；NURBS |
| 1c | `curve-catmull-rom.md` + `curve-hermite.md` | Catmull-Rom（三种参数化）；三次 Hermite |
| 1d | `curve-clothoid.md` | 回旋线（Fresnel、G1/G2 过渡） |
| 1e | `curve-fourier.md` | Fourier 路径（周期/非周期、拟合、阶数） |

- 各子批独立，可并行；共用批次 0 的契约。
- 每个曲线一篇验收：数值对拍、端点/切线、退化、零分配。

### 批次 2：弧长与组合（依赖批次 1 接口）

- `arc-length.md`：通用弧长参数化（积分、反解、动态控制点、退化）。
- `arc-length-lut.md`：LUT 泛化到多曲线（构建、查询、精度、缓存、零分配）。
- `continuity-composition.md`：G0/G1/G2、复合路径、段间约束、回旋线插入、降级。
- `axis-constraints.md`：控制点约束/切空间/投影，及其对精确性与弧长的影响。

### 批次 3：时钟抽象收尾（独立，可与 1/2 并行）

- 已播放秒 double 化，去掉 float 中间量。
- 帧驱动回调去死参数或承载已播放秒。
- 覆盖层动画按真实帧间隔推进，20fps 假设去掉。
- 存储 float / 运行 double 的边界契约落定。

### 批次 4：编辑器播放语义（独立，可与 1/2/3 并行）

- `editor-playback.md`：编辑器播放与普通播放共用引擎，唯一差异是时钟源。
- 实例维度拆成“时钟来源”和“参与范围”；内容行为参与，游戏控制不参与。
- 命名统一：`isEditorDriven()` / `clockSource`，去掉“预览模式”措辞。
- 验收：同脚本同时间点画面一致、HUD 行为一致、退出恢复。

### 批次 5：验收与回写

- `G-math/acceptance.md`：精确圆锥、弧长、连续性、退化、性能的汇总验收。
- 文档回写：`SCRIPT_FORMAT` / `AI_SCRIPTING_GUIDE` 的曲线与路径段落由 I/Z 统一改，G 只提供数学口径。
- 与 I 轮对接：I 的 `path-collection.md` 引用 G-math 的类型与算子；引用形式由 I 边开工边定，G 不预设存储结构。

## 四、依赖与边界

- 批次 0 是 1/2 的前置；批次 1 各曲线可并行；批次 2 依赖 1 的接口。
- 批次 3、4 独立，可与 1/2 并行。
- 不重开 B 轮已关单的 `math-models.md`；G-math 只做路径几何与算子。
- 不定义路径条目如何挂到片段/相邻端点（归 I 的 `path-collection.md`）。
- 不碰编辑器 UI（归 Z）；G 只提供 schema 可消费的数学类型。
- G1/G2 的对象/字段目标不在本轮，见 I 轮 `object-model.md`、`schema-and-types.md`。

## 五、验收

- 每篇曲线：解析值/数值对拍、端点与切线、退化输入、零分配、确定性。
- 弧长：反解精度、LUT 误差、动态控制点重建、退化。
- 时钟：暂停/恢复/预览 seek 不跳变；覆盖层淡入淡出与帧率无关；小时级脚本无累计漂移。
- 编辑器播放：同脚本同时间点画面逐帧一致；`hide_hud` / `hud_layers` 生效；键鼠不被屏蔽；退出恢复。

## 六、实现结果

- G6 ✅；G1/G2 由 I 轮吸收；G3 延后；G5 归 Z。
- 三次 Bézier 路径 + 弧长 LUT 已服务现有路径；精确圆锥、B-spline/NURBS、Catmull-Rom/Hermite、回旋线、Fourier、连续性与轴约束未实现。
- 时钟接口与两个实现已落地；double 化与覆盖层帧间隔未完成。
- 编辑器播放语义未实现。

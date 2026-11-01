# Fourier 路径

> 状态：已实现（`math/path/FourierPathSpec` + `math/path/FourierFit`，注册名 `fourier`）
> 范围：Fourier 级数表示、周期/非周期拟合、端点位置/速度约束、阶数选择、误差
> 关联：`expression-contract.md`、`curve-catmull-rom.md`、`curve-bspline.md`、`example/math/papers/gunsaulus_2024_fourier_series_trajectory_design.pdf`

## 一、摘要

Fourier 路径用有限项正弦/余弦级数表示整条曲线，天然高阶平滑、周期性强，适合无缝循环和“全局平滑”的运镜。它不是手调控制点的曲线类型，而是从采样点/关键帧拟合出来的生成型表达式；作者通过“Fourier 平滑/周期拟合”生成，不直接编辑系数。

## 二、为什么做

### 2.1 问题

- 关键帧插值在循环接缝处容易出现位置/速度不连续；手动调贝塞尔控制点很难消缝。
- 需要整条路径高阶连续（位置、速度、加速度甚至更高）时，分段曲线只能逐段保证。
- 周期巡视、待机镜头这类运动，用少量 Fourier 系数就能描述整体形态。

### 2.2 目标

- 从采样点/关键帧拟合出 Fourier 路径表达式；输出仍是路径表达式，可参与弧长、连续性、轴约束。
- 支持周期与非周期：周期路径保证 `f(0)=f(T)`、`f'(0)=f'(T)`；非周期路径用“三次趋势项 + 带约束 Fourier 残差”保证端点位置/速度。
- 阶数可选/可估；拟合误差可观察。
- 作者通过生成器使用，不直接编辑系数。

### 2.3 非目标

- 不保证经过每个关键帧；Fourier 是拟合，不是插值。
- 不替代 Catmull-Rom/Hermite 的“过点”能力。
- 不在此篇做频谱分析/信号处理工具。

## 三、方案

作者选“Fourier 平滑/周期拟合”：

- 输入：一段采样点或关键帧位置序列；可选周期、阶数、端点约束。
- 输出：一条 Fourier 路径表达式；可直接替换原路径，或作为复合路径的一段。
- 周期路径用于循环镜头；非周期路径用于“起点/终点速度有要求”的镜头。
- 拟合误差/阶数在生成时可见；误差过大时提示增加阶数或改用插值曲线。

## 四、技术细节

### 4.1 核心模型

- 定义域 `[0, T]`（`T = period`，u 单位），阶数 `K = cos.size() = sin.size()`。
- 周期形态：`P(u) = a0 + Σ_{k=1..K} [a_k cos(2πk u/T) + b_k sin(2πk u/T)]`。
- 非周期形态：`P(u) = Q(t) + F(t)`，`t = u/T`；`Q` 是三次 Hermite 趋势项（端点位置 + 端点速度），
  `F` 是受约束残差（两端位置与一阶导恒为 0）。
- 系数 `a0`、`a_k`、`b_k` 都是三维向量；总参数 `3(2K+1)`（周期）或 `3·2K`（非周期，`a0` 必须为 0）。

### 4.2 契约

**周期形态**（`periodic = true`）：`P(0)=P(T)`、`P'(0)=P'(T)`、`P''(0)=P''(T)` 由级数天然满足，
接缝位置/速度/加速度连续；声明 `PERIODIC`，`period()` 返回 `T`，调用方先对 u 取模再求值。
锚点 `FREE`：`bind(from, to)` 的 `from` / `to` 都被忽略。

**非周期形态**（`periodic = false`）：锚点 `ENDPOINTS`，`bind(from, to)` 两者都参与。

- 趋势项：`Q(t) = h00·from + h10·(T·V0) + h01·to + h11·(T·V1)`，
  `h00 = 2t³-3t²+1`、`h10 = t³-2t²+t`、`h01 = -2t³+3t²`、`h11 = t³-t²`。
  `V0 / V1` 是 `start_velocity / end_velocity`，单位「位置 / u」，所以 t 空间的趋势切线是 `T·V0`、`T·V1`。
  求导按链式除以 `T` 后，`T` 与 `1/T` 相消：`dQ/du = (h00'·from + h01'·to)/T + h10'·V0 + h11'·V1`，
  因此 `Q(0)=from`、`Q(T)=to`、`dQ/du(0)=V0`、`dQ/du(T)=V1` 都逐项精确（端点速度不带额外舍入）。

**非周期残差基底的构造**：要在不扰动端点位置与端点速度的前提下只改内部形状，
基底必须落在「两端函数值与一阶导都为 0」的子空间里。取

```
g_k(t) = 1 - cos(2πk t)            （偶基底，k = 1..K）
h_k(t) = 4·sin(2πk t)·t·(1-t)      （奇基底，k = 1..K）
F(t)   = Σ_{k=1..K} [ a_k·g_k(t) + b_k·h_k(t) ]
```

- 端点值：`g_k(0)=1-cos0=0`、`g_k(1)=1-cos(2πk)=0`；`h_k(0)=0`、`h_k(1)=4·sin(2πk)·1·0=0`。
- 端点导数：`g_k'(t)=2πk·sin(2πk t)`，两端均为 `0`；
  `h_k'(t)=4·[2πk·cos(2πk t)·t(1-t) + sin(2πk t)(1-2t)]`，`t=0` 与 `t=1` 时括号内两项各自为 0。
- 于是 `F(0)=F(T)=F'(0)=F'(T)=0` 与系数无关：`P(0)=from`、`P(T)=to`、`P'(0)=V0`、`P'(T)=V1`
  全部由趋势项决定，残差只负责内部形状。窗函数 `4t(1-t)` 在 `t=1/2` 取 1，`h_k` 因此在中段贴近
  `sin(2πk t)`，两端被平滑压到 0——代价是带窗的基底无法精确表示纯正弦（见 §六 的阶数收敛表）。
- 常数项在这条形态里没有位置：`g_k` 已含常数分量（`g_k = 1 - cos`），再留一个自由 `a0` 只会让
  同一形状有无穷多组等价系数。故非周期形态要求 `a0 = 0`，违反即 `IllegalArgumentException`。

**系数求解**（`FourierFit.fit`）：

- 周期：采样 `u_i = T·i/n`（`i=0..n-1`，末点不重复），基底 `{1, cos(2πk t), sin(2πk t)}`，未知数 `2K+1`。
- 非周期：采样 `u_i = T·i/(n-1)`（含两端），端点取 `samples[0]` / `samples[n-1]` 与给定速度构造趋势 `Q`，
  先减趋势再对残差拟合 `{g_k, h_k}`，未知数 `2K`。
- 每个分量独立解正规方程 `(AᵀA)c = Aᵀy`（规模 `2K+1` 或 `2K`），高斯消元 + 部分主元；
  主元相对矩阵量级低于 `1e-12` 视为奇异并报错（不静默给出无意义系数）。
  三个分量共用同一次消元（增广三列右端项，与逐分量求解等价）。
- 误差：位置 RMS 与最大误差，在**全部样本**上由拟合出的 `FourierPathSpec` 逐点求值得到。

**阶数与样本数**：`samples.size() ≥ 2·order + 2`、`period > 0`、`order ≥ 0`，否则报错。

**弧长**：无闭式解；数值积分/LUT（`ArcLengthTable`），Fourier 的导数解析可得，积分仍数值。

**连续性**：级数本身无穷阶可导；二阶导解析（`ANALYTIC_SECOND_DERIVATIVE`），拟合阶数决定实际平滑度。

### 4.3 数据形态

JSON 形态（CAMERA 片段上的 `curve` 对象；`type` = `fourier`）：

| 字段 | kind | 必填 | 缺省 | 单位 / 语义 |
|---|---|---|---|---|
| `a0` | `VECTOR` | 否 | `(0,0,0)` | 世界坐标偏移；周期形态的常数项，非周期形态必须为 0 |
| `cos` | `POINT_LIST` | 否 | 空 | 余弦系数 `a_1..a_K`（第 k 项 = 阶数 k）；与 `sin` 等长 |
| `sin` | `POINT_LIST` | 否 | 空 | 正弦系数 `b_1..b_K`；与 `cos` 等长 |
| `period` | `NUMBER` | 是 | — | 参数周期 `T`（u 单位），必须 `> 0` |
| `periodic` | `BOOLEAN` | 否 | `true` | `true` = 周期级数（锚点 FREE）；`false` = 三次趋势 + 受约束残差（ENDPOINTS） |
| `start_velocity` | `VECTOR` | 否 | `(0,0,0)` | 起点速度 `V0`（位置 / u），仅 `periodic=false` 生效 |
| `end_velocity` | `VECTOR` | 否 | `(0,0,0)` | 终点速度 `V1`（位置 / u），仅 `periodic=false` 生效 |

- `cos` / `sin` 用控制点语法：`{dx,dy,dz}`（相对形态）与 `{x,y,z}`（绝对形态）**数值等价**——
  系数是偏移量，不存在“段起点”，两者都按原始分量取值。
- 定义域 `[0, period]`；能力 `{ANALYTIC_SECOND_DERIVATIVE, PERIODIC}`（注册表元数据恒定），
  `PathSpec#supports(PERIODIC)` 只在 `periodic=true` 时为真。
- 缺省（`{"type":"fourier","period":12}`）= 周期形态、0 阶、`a0 = 0`，即常值路径。
- 未知字段 / 类型不符 / 缺必填 / 阶数不等 / 周期非正 / 非周期 `a0≠0` 一律解析期报错
  （`PathCurveException`），不静默回落。
- 作者面不直接编辑系数；生成器输入采样点/关键帧 + 参数，输出表达式。
- 缓存键包含系数、周期、阶数；系数变化使弧长/求值缓存失效。

### 4.4 边界

- Fourier 不保证过点；需要过点时用 Catmull-Rom/Hermite。
- Fourier 不提供局部控制；改一个系数会影响整条曲线。
- Fourier 不负责时间重映射；`u/s/t` 分工同 `expression-contract.md`。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 关键帧插值 | 线性/Catmull-Rom/Hermite | 接缝/局部控制；高阶连续性有限 | 周期/全局平滑用 Fourier |
| B-spline | 局部控制、高阶连续 | 不过点；接缝仍需构造 | Fourier 天然周期 |
| 手动调 Bézier | 控制点消缝 | 费时、难保证速度连续 | Fourier 自动拟合 |
| 多项式拟合 | 高阶多项式 | 易振荡、端点不稳 | Fourier 基更稳、周期自然 |

**本方案的缺点**：

- 不保证过点；关键构图点可能偏移。
- 系数全局影响；不能局部改一段。
- 阶数/端点约束需要调参；拟合误差要可视化。

## 六、实现结果

已实现 `math/path/FourierPathSpec`（定义 + 求值器）与 `math/path/FourierFit`（正规方程拟合），
并在 `script/schema/PathCurveRegistry` 注册 `fourier`
（能力 `ANALYTIC_SECOND_DERIVATIVE` + `PERIODIC`）。

求值口径：

- 周期求值器：`position` / `derivative` / `secondDerivative` / `speed` 共用同一组谐波循环，
  `θ_k = 2πk·u/T`；`P'(u) = Σ (2πk/T)[b_k cos θ_k - a_k sin θ_k]`、
  `P''(u) = -Σ (2πk/T)²[a_k cos θ_k + b_k sin θ_k]`。对 u 取模后任意 u 都能求值（不做夹取）。
- 非周期求值器：`t = clamp(u)/T`，位置 `Q + F`；一阶导 `(h00'·from + h01'·to)/T + h10'·V0 + h11'·V1 + F'/T`；
  二阶导 `(h00''·from + h01''·to)/T² + (h10''·V0 + h11''·V1)/T + F''/T²`。
  `bind` 在构造期预计算 `T·V0 / T·V1`，逐帧只做标量累加。
- `speed` 与 `derivative` 同一算式，只累加标量、不构造向量：1e6 次调用分配 0 字节。
- `FourierFit` 与求值器共用 `FourierPathSpec` 里的趋势项/基函数静态方法（`trend`、`residualEven`、
  `residualOdd`），拟合减趋势与求值不会漂移；误差也由拟合出的 `FourierPathSpec` 求值得到。

实测（无头探针，仓库外运行；原有 313 项检查保留，新增 64 项，共 377 项全通过）：

| 检查 | 结果 |
|---|---|
| 周期系数回收（2 阶、200 样本）系数差 | 1.6e-14（容差 1e-9） |
| 周期系数回收 `rms` / `maxError` | 2.0e-14 / 3.6e-14（容差 1e-9） |
| 周期接缝 `P(0)=P(T)` / `P'(0)=P'(T)` / `P''(0)=P''(T)` | 1.7e-15 / 1.4e-15 / 3.6e-15（容差 1e-10） |
| 非周期 `position(0)=from` / `position(T)=to` | 0 / 0（容差 1e-10） |
| 非周期 `derivative(0)=V0` / `derivative(T)=V1` | 0 / 8.4e-16（容差 1e-10） |
| 残差基底 `F(0)=F(T)=0` / `F'(0)=F'(T)=0` | 0 / 8.6e-16（容差 1e-10） |
| 非周期 order=0 vs 独立写的 Hermite 趋势 | 1.4e-14（容差 1e-12） |
| 周期 order=0：位置恒定、导数为零 | 0 / `(0,0,0)` |
| `derivative` vs 中心差分（h=1e-6）周期 / 非周期 | 1.2e-8 / 2.2e-8（容差 1e-6） |
| `secondDerivative` vs 一阶导中心差分 周期 / 非周期 | 5.6e-9 / 2.6e-9（容差 1e-5） |
| `speed` vs `|derivative|`（101 点 × 两种形态） | 0（容差 1e-12） |
| `speed` 1e6 次调用分配增量（周期 / 非周期） | 0 字节 / 0 字节 |
| 非法参数（`period ≤ 0`、`period` 非有限、`cos/sin` 长度不等、非周期 `a0≠0`、系数/速度非有限、`a0=null`、样本不足、`order<0`、样本非有限） | 逐例 `IllegalArgumentException` |
| 注册表 `create` / 能力集 / 缺省 / 报错 fieldPath | 全部符合（`curve.period`、`curve.bogus` 等） |
| fixture `cinematics/0.3.6/tests/camera/test_curve_fourier.json` | `parse` 成功、`validate` 0 issue、首末帧 0 / 9.9e-16 |

非周期拟合收敛（200 样本，`P(u)=from+(to-from)t+0.5·sin(2πt)`，端点速度取真实 `dP/du`）：

| 阶数 K | `maxError` | `rms` |
|---|---|---|
| 3 | 1.441e-3 | 6.732e-4 |
| 4 | 7.334e-4 | 3.104e-4 |
| 5 | 4.243e-4 | 1.651e-4 |
| 6 | 2.671e-4 | 9.684e-5 |

阶数选择的口径：`h_k = 4·sin(2πk t)·t(1-t)` 带 `t(1-t)` 窗，纯正弦只能被窗函数加权逼近，
所以 `max ≤ 1e-3`、`rms ≤ 1e-4` 这一档要 `K = 6`；`K = 3` 停在 1.4e-3 / 6.7e-4。
这不是解法问题：把端点速度也放开（模型空间再加 `t(1-t)²`、`t²(1-t)` 两个自由度）后，
`K = 3` 对 `0.5·sin(2πt)` 的最优 `rms` 仍有 2.66e-4——`1e-4` 在该基底下不可达。
生成器据此提示“增加阶数”：`K = 6` 达到 2.7e-4 / 9.7e-5，`K = 8` 可到 1.3e-4 / 4.1e-5。

本期不做：按残差/AIC 自动选阶（当前由调用方显式给 `order`）；频谱/信号处理工具；
生成器物化到编辑器 UI（拟合入口 `FourierFit.fit` 已就位，供上层生成器调用）。

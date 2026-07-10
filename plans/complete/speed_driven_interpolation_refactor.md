# 速度驱动插值系统重构计划

> 状态：待实施 | 创建时间：2026-05-02 | 更新时间：2026-05-02
> 
> 理论依据：北京大学《视觉计算与交互导论》第六章（曲线）
> 参考文件：`curve/curve-basic.md`, `curve/curve-bezier.md`, `curve/curve-spline.md`, `curve/curve-rasterization.md`

---

## 一、背景与问题

### 当前系统的根本缺陷

当前插值系统将**路径（几何）**和**速度（时间）**耦合在一起：

```
线性时间 t → applyCurve(t, smooth) → adjustedT → 同时用于路径和属性
                                                    ↓
              position = bezier(adjustedT)  ← 路径被插值曲线扭曲！
              yaw = lerpAngle(yaw0, yaw1, adjustedT)
              fov = lerp(fov0, fov1, adjustedT)
```

**实际测试发现的问题**：
- 贝塞尔曲线路径 + linear 插值 → 运动先快后慢再慢启动加速（非匀速）
- 原因：`adjustedT` 同时喂给路径和属性，插值曲线扭曲了路径参数
- 三层插值体系（脚本级→片段级→关键帧级）+ 两种组合模式（OVERRIDE/COMPOSED）过于复杂且反直觉

### 设计目标

**路径是路径，速度是速度，两者必须正交。**

参考剪辑软件（Premiere/DaVinci）的速度曲线设计：
- 每个属性有独立的速度曲线
- 速度曲线决定"时间如何流逝"
- 路径/属性值决定"空间位置/状态"

---

## 二、理论依据（教材映射）

> 以下理论均来自北大《视觉计算与交互导论》第六章，已与当前代码实现对比验证。

### 2.1 贝塞尔公式验证结果 ✅

**结论：我们的贝塞尔曲线公式是正确的，无需修改。**

[`MathUtil.cubicBezier()`](../src/main/java/com/immersivecinematics/immersive_cinematics/util/MathUtil.java:129) 的实现：

```
B(t) = (1-t)³P0 + 3(1-t)²tP1 + 3(1-t)t²P2 + t³P3
```

与标准伯恩斯坦多项式 $B_{3,k}(t) = \binom{3}{k}(1-t)^{3-k}t^k$ 完全一致。

> ⚠️ 教材 `curve-bezier.md` 第79行的一般公式有笔误：
> 教科书写的 $\binom{n}{k}(1-t)^k t^{n-k}$ 指数写反了，
> 正确应为 $\binom{n}{k}(1-t)^{n-k} t^k$。
> 用 n=2 代入即可发现：教科书公式给出 S(0)=P_n（反了），
> 而同页德卡斯特里奥推导给出 S(0)=P_0（正确）。

### 2.2 弧长参数化 → 路径-速度正交化的数学基础

> 来源：`curve-basic.md` 第24行
> 
> "我们可以选择 t 为从初始点出发到当前点沿曲线的长度，这种参数的选择称为**长度参数化**。"

| 参数化方式 | 含义 | 等距 Δt 的效果 |
|---|---|---|
| 当前：参数坐标 t | 贝塞尔的原始参数 | 弯曲处弧长短→移动慢 |
| 目标：弧长坐标 s | 沿曲线的实际距离 | 任何位置等距→匀速 |

**速度曲线的本质**：在弧长参数化的基础上，用速度函数 v(t) 调制弧长进度：

```
s(t) = ∫₀ᵗ v(τ)dτ / ∫₀ᵀ v(τ)dτ
```

其中 T 是片段总时长。s ∈ [0,1] 是归一化弧长进度，喂给 `cubicBezier(p0, p1, p2, p3, s)` 就能得到匀速（v=1 时）或变速运动。

### 2.3 Cⁿ/Gⁿ 光滑性 → 速度曲线的连续性要求

> 来源：`curve-basic.md` 第58行

| 光滑性 | 定义 | 对速度曲线的意义 |
|---|---|---|
| C⁰ | 坐标连续 | 速度值无跳变（最低要求） |
| **C¹** | **一阶导数连续** | **加速度无跳变（速度曲线必须满足）** |
| G¹ | 切线方向连续 | 视觉上平滑但速度可能突变 |

**设计约束：速度曲线必须满足 C¹ 连续。**

原因：
- C⁰ 但非 C¹：速度值连续但加速度跳变 → 镜头"抖"一下
- C¹：加速度连续 → 镜头运动丝滑
- C²：加加速度连续 → 更丝滑，但用户控制复杂度太高

**这就是为什么 Hermite 样条（C¹）比三次样条（C²）更适合我们**——C¹ 足够丝滑，且允许用户自由控制每个关键帧的切线方向。

### 2.4 贝塞尔端点切线性质 → curve_bias 的几何直觉

> 来源：`curve-bezier.md` 第104行
>
> S'(0) = n(P₁ - P₀), S'(1) = n(Pₙ - Pₙ₋₁)
>
> 端点处切线分别沿着 P₀→P₁ 和 Pₙ→Pₙ₋₁ 的方向。

对速度曲线的意义：
- `curve_bias = 0`：切线沿"前一个→后一个"关键帧方向（Catmull-Rom 风格）
- `curve_bias > 0`：切线偏向"加速"方向，曲线在关键帧处更陡
- `curve_bias < 0`：切线偏向"减速"方向，曲线在关键帧处更平

### 2.5 厄米基函数 → 速度曲线引擎的核心公式

> 来源：`curve-spline.md` 第52行

**三次厄米样条公式**（直接作为 SpeedCurve 的数学引擎）：

```
f(t) = h₀₀(t)·xᵢ + h₁₀(t)·pᵢ + h₀₁(t)·xᵢ₊₁ + h₁₁(t)·pᵢ₊₁
```

其中四个厄米基函数为：

| 基函数 | 公式 | 边界值 |
|---|---|---|
| h₀₀(t) | 2t³ - 3t² + 1 | h₀₀(0)=1, h₀₀(1)=0, h'₀₀(0)=0, h'₀₀(1)=0 |
| h₁₀(t) | t³ - 2t² + t | h'₁₀(0)=1, h'₁₀(1)=0 |
| h₀₁(t) | -2t³ + 3t² | h₀₁(0)=0, h₀₁(1)=1, h'₀₁(0)=0, h'₀₁(1)=0 |
| h₁₁(t) | t³ - t² | h'₁₁(0)=0, h'₁₁(1)=1 |

在我们的速度曲线体系中：

| 符号 | 速度曲线含义 |
|---|---|
| xᵢ | 关键帧 i 的 `speed` 值 |
| xᵢ₊₁ | 关键帧 i+1 的 `speed` 值 |
| pᵢ | 关键帧 i 的切线，由 `curve_bias` 控制 |
| pᵢ₊₁ | 关键帧 i+1 的切线，由 `curve_bias` 控制 |
| t | 片段内的归一化时间 [0,1] |
| f(t) | 时刻 t 的瞬时速度 v(t) |

**关键优势**：Hermite 天然满足 C¹ 连续（基函数保证一阶导数连续），且通过切线 pᵢ 允许用户自由控制弯曲方向——完美匹配 curve_bias 的需求。

### 2.6 Catmull-Rom 样条 → curve_bias=0 的默认切线

> 来源：`curve-spline.md` 第68行
>
> Catmull-Rom 样条使用的计算方法就是 pᵢ = ½(xᵢ₊₁ - xᵢ₋₁)

这是 `curve_bias = 0` 时的默认切线计算：

```
pᵢ = (speedᵢ₊₁ - speedᵢ₋₁) / 2    （Catmull-Rom 默认切线）
```

**边界处理**：
- 第一个关键帧（无 i-1）：p₀ = speed₁ - speed₀（向前差分）
- 最后一个关键帧（无 i+1）：pₙ = speedₙ - speedₙ₋₁（向后差分）

**优势**：用户只需指定 `speed` 值，不设 `curve_bias` 就能得到平滑的速度曲线。只有需要特殊效果（急停、弹射起步等）时才需要手动调 `curve_bias`。

### 2.7 B样条局部性 → 属性级速度覆盖的设计灵感

> 来源：`curve-spline.md` 第120行
>
> Nᵢ,ₚ(t) 会影响到附近连续 p+1 个区间内的值，因此 p 越大基函数影响的范围越大，局部性就越差

B 样条的**局部性**直接启发了属性级速度覆盖设计：
- 修改某个属性的 `property_override` 只影响该属性的插值行为
- 不影响其他属性
- 不影响 clip 级默认值

### 2.8 德卡斯特里奥算法 → 弧长 LUT 的自适应细分

> 来源：`curve-bezier.md` 第23行

德卡斯特里奥算法通过三轮 lerp 构造三阶贝塞尔曲线：

```java
// 德卡斯特里奥算法实现
Vec3 q0 = lerp(p0, p1, t);  // 第一轮
Vec3 q1 = lerp(p1, p2, t);
Vec3 q2 = lerp(p2, p3, t);
Vec3 r0 = lerp(q0, q1, t);  // 第二轮
Vec3 r1 = lerp(q1, q2, t);
Vec3 s  = lerp(r0, r1, t);  // 第三轮
```

**在弧长参数化中的应用**：判断曲线段是否"足够直"可以用德卡斯特里奥的中间点距离作为平坦度标准（参考 `curve-rasterization.md` 的自适应细分思想）：

```
flatness = max(|q0 - q2|, |r0 - r1|)  // 中间层线段的最大距离
如果 flatness < ε，则该段足够直，无需继续细分
```

---

## 三、新架构设计

### 3.1 核心概念：速度驱动模型

```
线性时间 t → 速度曲线 v(t) → 弧长进度 s = ∫v(t)dt → 路径 B(s)
                                                    → 属性 lerp(a0, a1, s)
```

**关键分离**：
- **路径层**：贝塞尔曲线 B(s) 只关心"形状"，s 是弧长参数
- **速度层**：速度曲线 v(t) 只关心"快慢"，t 是线性时间
- **属性层**：各属性用各自的进度插值

**完整公式链**：

```
t ──[Hermite 样条]──→ v(t) ──[积分]──→ s(t) ──[弧长参数化]──→ cubicBezier(P₀,P₁,P₂,P₃, s)
```

- 第一步：Hermite 样条将时间映射为瞬时速度（C¹ 连续，curve_bias 可控）
- 第二步：积分将速度转为弧长进度（保证路径-速度正交）
- 第三步：弧长参数化的贝塞尔曲线将弧长进度映射为空间位置（匀速运动）

### 3.2 速度曲线类型

只保留两种：`linear` 和 `smooth`

#### linear — 线性速度变化

两个关键帧 KF0(speed=s0) 和 KF1(speed=s1) 之间：

```
v(t) = s0 + (s1 - s0) * t           （速度线性变化）
s(t) = s0*t + (s1-s0)*t²/2          （位移：抛物线）
```

| s0 | s1 | 效果 |
|---|---|---|
| 1.0 | 1.0 | 匀速（最常用） |
| 0.0 | 1.0 | 从静止加速 |
| 1.0 | 0.0 | 减速到停止 |
| 0.5 | 1.5 | 慢→快（加速） |
| 1.5 | 0.5 | 快→慢（减速） |

#### smooth — Hermite 样条速度曲线 + curve_bias

**基于厄米基函数**（教材 `curve-spline.md` 公式）：

```
v(t) = h₀₀(t)·sᵢ + h₁₀(t)·pᵢ + h₀₁(t)·sᵢ₊₁ + h₁₁(t)·pᵢ₊₁

其中：
  h₀₀(t) = 2t³ - 3t² + 1
  h₁₀(t) = t³ - 2t² + t
  h₀₁(t) = -2t³ + 3t²
  h₁₁(t) = t³ - t²

  sᵢ, sᵢ₊₁ = 关键帧 i, i+1 的 speed 值
  pᵢ, pᵢ₊₁ = 关键帧 i, i+1 的切线（由 curve_bias 控制）
```

**切线计算**（Method B — 自由控制弯曲方向）：

```
// 默认切线：Catmull-Rom 风格（curve_bias = 0 时）
pᵢ_default = (sᵢ₊₁ - sᵢ₋₁) / 2

// curve_bias 偏移切线
pᵢ = pᵢ_default + curve_biasᵢ * offsetᵢ

// offset 的计算方式：
// 方向：沿"加速"方向（sᵢ₊₁ - sᵢ 的方向）
// 大小：与相邻关键帧的间距成正比
offsetᵢ = (sᵢ₊₁ - sᵢ) * tangentScale

// tangentScale 为常数，控制 curve_bias 的灵敏度
// 建议值：1.0（curve_bias=1 时切线翻倍）
```

**curve_bias**（-1.0 ~ 1.0）控制弯曲方向：

| curve_bias | 切线变化 | 效果 |
|---|---|---|
| 0.0 | Catmull-Rom 默认 | 速度变化在中间最快 |
| > 0 | 切线增大 | 先快后慢（easeOut 感） |
| < 0 | 切线减小 | 先慢后快（easeIn 感） |
| ±1.0 | 极端偏移 | 最大弯曲偏移 |

**C¹ 连续性保证**：Hermite 基函数天然保证 C¹ 连续——每个关键帧处的切线 pᵢ 由 curve_bias 唯一确定，左右两段曲线共享同一切线，一阶导数自动连续。

### 3.3 分层速度控制

```
层级1: 片段默认速度曲线（覆盖所有属性）
层级2: 属性级速度覆盖（可选，单独控制某个属性的速度曲线）
```

**90% 场景**：只设片段级 speed + interpolation，所有属性统一运动
**10% 场景**：需要"位置匀速但FOV缓入"等差异化，使用属性级覆盖

**局部性原则**（受 B 样条启发）：
- 修改某个属性的 `property_override` 只影响该属性的插值行为
- 不影响其他属性
- 不影响 clip 级默认值

---

## 四、JSON 结构变更

### 4.1 片段级（CameraClip）

**移除**：
- `interpolation`（移到片段级速度控制，语义不同）
- `interpolation_scope`（速度驱动模型下无意义）
- ~~`curve_composition_mode`~~（只有一层插值，无需组合）

**新增**：
- `speed`: float, 默认 1.0 — 片段整体速度倍率 (0~2)
- `interpolation`: string, 默认 "linear" — 速度曲线类型 (linear/smooth)
- `property_overrides`: object, 可选 — 属性级速度覆盖

**保留**：
- `start_time`, `duration`, `transition`, `crossfade_duration`
- `curve`（贝塞尔路径控制，与速度正交）
- `position_mode`, `loop`, `loop_count`
- `keyframes`

#### 新 JSON 结构

```json
{
  "start_time": 0,
  "duration": 6,
  "transition": "cut",
  "crossfade_duration": 0.5,
  "speed": 1.0,
  "interpolation": "smooth",
  "position_mode": "relative",
  "loop": false,
  "loop_count": -1,
  "curve": null,
  "property_overrides": {
    "fov": {
      "interpolation": "linear",
      "keyframes": [
        { "time": 0, "speed": 0.3, "curve_bias": 0.0 },
        { "time": 6, "speed": 1.0, "curve_bias": 0.0 }
      ]
    }
  },
  "keyframes": [...]
}
```

### 4.2 关键帧级（CameraKeyframe）

**移除**：
- `interpolation`（由 speed + curve_bias 替代）

**新增**：
- `speed`: float, 默认 1.0 — 该关键帧处的速度 (0~2)
- `curve_bias`: float, 默认 0.0 — smooth 弯曲方向控制 (-1~1)

**保留**：
- `time`, `position`, `yaw`, `pitch`, `roll`, `fov`, `zoom`, `dof`

#### 新 JSON 结构

```json
{
  "time": 0,
  "position": { "dx": 10, "dy": 0, "dz": 0 },
  "yaw": 0,
  "pitch": 5,
  "roll": 0,
  "fov": 70,
  "zoom": 1.0,
  "dof": 0,
  "speed": 0.5,
  "curve_bias": 0.3
}
```

### 4.3 脚本元信息（ScriptMeta）

**移除**：
- `interpolation`（插值降为片段级）
- `curve_composition_mode`（无需组合）

**保留**：
- `id`, `name`, `author`, `version`, `description`
- 15个运行时布尔标志位

### 4.4 属性级速度覆盖（PropertyOverride）

每个属性可以独立定义速度曲线，结构如下：

```json
{
  "PROPERTY_NAME": {
    "interpolation": "smooth",
    "keyframes": [
      { "time": 0, "speed": 0.5, "curve_bias": 0.3 },
      { "time": 6, "speed": 1.5, "curve_bias": -0.2 }
    ]
  }
}
```

支持的 PROPERTY_NAME：
- `position` — 位置路径速度
- `yaw` — 偏航角变化速度
- `pitch` — 俯仰角变化速度
- `roll` — 滚转角变化速度
- `fov` — 视场角变化速度
- `zoom` — 缩放变化速度
- `dof` — 景深变化速度（预留）

---

## 五、贝塞尔弧长参数化

### 5.1 问题

当前贝塞尔曲线 `B(t)` 不是等弧长参数化的：
- 参数 t 匀速变化时，弧长速度不匀速
- 控制点位置导致路径某些段"拉伸"某些段"压缩"
- 这就是用户观察到"先快后慢"的根因

**注意**：贝塞尔公式本身是正确的（已验证），问题出在参数化方式上。

### 5.2 解决方案：LUT 弧长参数化

> 理论依据：`curve-basic.md` 的弧长参数化概念 + `curve-rasterization.md` 的自适应细分思想

**标准方法**：预计算弧长查找表（LUT）

```
1. 将 B(t) 离散化为 N 个采样点
2. 计算累积弧长 L[i] = Σ|B(tᵢ) - B(tᵢ₋₁)|
3. 归一化：s[i] = L[i] / L[N]  （s ∈ [0, 1]）
4. 查找：给定 s，在 LUT 中二分查找对应的 t
5. 用 t 计算 B(t) 得到等弧长位置
```

### 5.3 自适应细分构建 LUT

> 理论依据：`curve-rasterization.md` 的自适应细分 + `curve-bezier.md` 的德卡斯特里奥算法

使用德卡斯特里奥算法的中间层点判断曲线平坦度，自适应决定采样密度：

```java
/**
 * 自适应细分构建弧长 LUT
 * 
 * @param p0, p1, p2, p3  贝塞尔控制点
 * @param tolerance       平坦度容差（建议 0.001）
 * @param maxDepth        最大递归深度（建议 8）
 */
void buildLUTAdaptive(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, 
                      float tolerance, int maxDepth) {
    // 德卡斯特里奥中间层
    Vec3 q0 = lerp(p0, p1, 0.5);
    Vec3 q1 = lerp(p1, p2, 0.5);
    Vec3 q2 = lerp(p2, p3, 0.5);
    Vec3 r0 = lerp(q0, q1, 0.5);
    Vec3 r1 = lerp(q1, q2, 0.5);
    Vec3 mid = lerp(r0, r1, 0.5);
    
    // 平坦度判断：中间层线段的最大距离
    float flatness = Math.max(
        q0.distanceTo(q2),  // 第一层中间点距离
        r0.distanceTo(r1)   // 第二层中间点距离
    );
    
    if (flatness < tolerance || maxDepth <= 0) {
        // 足够平坦，直接记录弧长
        recordArcLength(mid);
    } else {
        // 需要细分：将曲线分为左右两半，递归处理
        // 左半：p0, q0, r0, mid
        buildLUTAdaptive(p0, q0, r0, mid, tolerance, maxDepth - 1);
        // 右半：mid, r1, q2, p3
        buildLUTAdaptive(mid, r1, q2, p3, tolerance, maxDepth - 1);
    }
}
```

**优势**：
- 弯曲处自动密集采样（精度高）
- 直线段稀疏采样（省内存）
- 无需手动指定采样数 N

### 5.4 LUT 查询

```java
/**
 * 给定归一化弧长 s ∈ [0,1]，查找对应的参数 t
 * 
 * @param s 归一化弧长进度
 * @return 贝塞尔参数 t
 */
float lookupT(float s) {
    // 二分查找 s 在 LUT 中的位置
    int idx = binarySearch(arcLengths, s * totalLength);
    
    // 线性插值精化
    float s0 = arcLengths[idx] / totalLength;
    float s1 = arcLengths[idx + 1] / totalLength;
    float frac = (s - s0) / (s1 - s0);
    
    return lerp(tValues[idx], tValues[idx + 1], frac);
}
```

### 5.5 性能参数

| 参数 | 建议值 | 说明 |
|---|---|---|
| 平坦度容差 | 0.001 | 世界坐标单位，约 1mm |
| 最大递归深度 | 8 | 最多 2⁸=256 个采样点 |
| LUT 缓存 | 按片段缓存 | 片段开始时构建，结束时释放 |
| 查询复杂度 | O(log N) | 二分查找 |

---

## 六、代码变更清单

### 6.1 删除的文件

| 文件 | 原因 |
|---|---|
| `InterpolationScope.java` | 速度驱动模型下无意义 |
| `CurveCompositionMode.java` | 只有一层插值，无需组合 |

### 6.2 重写的文件

| 文件 | 变更内容 |
|---|---|
| `InterpolationType.java` | 简化为 LINEAR/SMOOTH 两个枚举值 |
| `CameraKeyframe.java` | 新增 speed/curve_bias，移除 interpolation |
| `CameraClip.java` | 新增 speed/interpolation/property_overrides，移除 interpolation/interpolation_scope |
| `KeyframeInterpolator.java` | **完全重写**：Hermite 速度积分驱动 + 弧长参数化 + 属性级覆盖 |
| `MathUtil.java` | 新增 Hermite 基函数、Catmull-Rom 切线、curve_bias 偏移、弧长 LUT 构建/查询 |
| `ScriptMeta.java` | 移除 interpolation/curveCompositionMode |
| `ScriptParser.java` | 适配新JSON结构，解析 speed/curve_bias/property_overrides |
| `CameraTrackPlayer.java` | 适配新插值器接口 |
| `BezierPathStrategy.java` | 适配弧长参数化 |

### 6.3 新增的文件

| 文件 | 内容 |
|---|---|
| `PropertyOverride.java` | 属性级速度覆盖数据类 |
| `ArcLengthLUT.java` | 贝塞尔弧长查找表（自适应细分构建 + 二分查询） |
| `SpeedCurve.java` | 速度曲线计算（Hermite 基函数 + Catmull-Rom 默认切线 + curve_bias 偏移） |

### 6.4 不变的文件

| 文件 | 原因 |
|---|---|
| `PositionData.java` | 坐标数据不变 |
| `BezierCurve.java` | 控制点数据不变（弧长参数化在运行时计算） |
| `LetterboxClip.java` | 黑边有独立 fade 体系 |
| `AudioClip.java` | 音频有独立 volume/pitch/fade 体系 |
| `EventClip.java` | 事件不涉及插值 |
| `ModEventClip.java` | 模组事件不涉及插值 |
| `ScriptProperties.java` | 运行时布尔标志位不变 |
| `CameraManager.java` | 相机管理不变 |
| `CameraProperties.java` | 相机属性容器不变 |
| `CameraPath.java` | 相机路径容器不变 |
| 所有 Mixin/Handler/Overlay | 渲染层不变 |

---

## 七、实施步骤

### Phase 1: 基础重构（数据层）

1. 简化 `InterpolationType` 为 LINEAR/SMOOTH
2. 删除 `InterpolationScope` 和 `CurveCompositionMode`
3. 修改 `CameraKeyframe`：新增 speed/curve_bias，移除 interpolation
4. 修改 `CameraClip`：新增 speed/interpolation/property_overrides，移除旧属性
5. 修改 `ScriptMeta`：移除 interpolation/curveCompositionMode
6. 新增 `PropertyOverride` 数据类
7. 修改 `ScriptParser`：适配新JSON结构

### Phase 2: 速度曲线引擎

8. 新增 `SpeedCurve`：基于 Hermite 基函数实现速度曲线计算
   - `h00(t) = 2t³ - 3t² + 1`
   - `h10(t) = t³ - 2t² + t`
   - `h01(t) = -2t³ + 3t²`
   - `h11(t) = t³ - t²`
   - Catmull-Rom 默认切线：`pᵢ = (sᵢ₊₁ - sᵢ₋₁) / 2`
   - curve_bias 偏移切线：`pᵢ = pᵢ_default + curve_bias * offset`
9. 修改 `MathUtil`：新增 Hermite 基函数、Catmull-Rom 切线计算、curve_bias 偏移公式
10. 新增 `ArcLengthLUT`：基于德卡斯特里奥自适应细分构建弧长查找表

### Phase 3: 插值器重写

11. 重写 `KeyframeInterpolator`：
    - 速度积分驱动（s = ∫v(t)dt，v(t) 由 Hermite 样条计算）
    - 弧长参数化路径（s → ArcLengthLUT → t → cubicBezier）
    - 属性级速度覆盖（PropertyOverride 独立速度曲线）
12. 修改 `CameraTrackPlayer`：适配新插值器接口
13. 修改 `BezierPathStrategy`：适配弧长参数化

### Phase 4: 测试与验证

14. 更新 `example_orbit.json` 为新JSON结构
15. 逐属性验证速度曲线效果
16. 验证弧长参数化：linear+bezier 应为匀速弧线运动
17. 验证属性级覆盖：不同属性不同速度
18. 验证 C¹ 连续性：速度曲线无加速度跳变

---

## 八、已确认事项

| # | 事项 | 状态 | 结论 |
|---|---|---|---|
| 1 | 贝塞尔曲线公式是否正确 | ✅ 已确认 | 公式正确，与标准伯恩斯坦多项式一致 |
| 2 | 非匀速运动的根因 | ✅ 已确认 | 弧长参数化缺失 + 路径/速度耦合 |
| 3 | 速度曲线数学引擎 | ✅ 已确认 | Hermite 基函数（教材 curve-spline.md） |
| 4 | curve_bias 默认切线 | ✅ 已确认 | Catmull-Rom：pᵢ = (sᵢ₊₁ - sᵢ₋₁) / 2 |
| 5 | 速度曲线连续性要求 | ✅ 已确认 | 必须满足 C¹ 连续（Hermite 天然保证） |
| 6 | 弧长 LUT 构建方法 | ✅ 已确认 | 德卡斯特里奥自适应细分 |
| 7 | 属性级覆盖设计原则 | ✅ 已确认 | B 样条局部性（修改局部不影响全局） |

## 九、待确认事项

| # | 事项 | 状态 |
|---|---|---|
| 1 | curve_bias 的 tangentScale 常数值 | ⚠️ 待实现阶段调参（建议初始值 1.0） |
| 2 | 弧长 LUT 平坦度容差 | ⚠️ 待性能测试（建议初始值 0.001） |
| 3 | 音频/视频速度接口预留方式 | ⚠️ 待后续讨论 |

# 0.3.6 迟滞：相机参数运动响应（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 公式、参数、JSON、默认值等执行时再定
>
> 相关文档：
> - [相机状态与覆盖链](./camera-state-plan.md)
> - [数学函数模型](./math-models.md)
> - [时间插值](./temporal-interpolation.md)
> - [过渡](./transition.md)

---

## 1. 定位（已确认）

本文的“迟滞”指：

> **相机参数的运动响应行为**：缓动、粘滞、阻尼、弹簧、惯性等，让运镜更自然丰富。

**不是**目标选择迟滞、选择器切换策略；那些属于目标策略层。

作用对象是 6 个相机参数：

- 位置：`x, y, z`
- 朝向：`yaw, pitch`
- 附加：`roll, fov, zoom`

作用位置：

- Base Provider：生成基础状态时
- Modifier Chain：修改状态时
- 未来的外部 API / 编辑器预览

---

## 2. 为什么需要（方向）

当前相机参数的变化方式：

- 关键帧插值：linear / smooth / bezier
- 目标切换：selector_switch_smooth
- 呼吸：perlin / sine / trauma
- 其他：直接写值

问题：

- 变化方式分散；
- 很难表达“有重量感的跟随”“粘滞转向”“弹簧停止”；
- 新增行为要改多处；
- 外部模组无法复用同一套运动行为。

方向：统一复用底层数学函数模型，做成可配置、可复用、可组合的相机参数响应。

---

## 3. 行为分类（方向）

### 3.1 缓动

- linear / smoothstep / ease_in / ease_out / ease_in_out / bezier / 自定义曲线

### 3.2 粘滞 / 滞后

- exponential_smooth / viscous_lag / directional_lag / deadband / hysteresis_threshold

### 3.3 阻尼 / 弹簧

- spring / damper / smooth_damp（临界阻尼 / 欠阻尼 / 过阻尼）

### 3.4 惯性 / 动量

- inertia / momentum

### 3.5 振荡 / 噪声（相关）

- sine / perlin / trauma_shake / decay

> 第一版支持哪些行为、参数怎么定执行时再定。

---

## 4. 各通道方向

| 通道 | 方向 |
|---|---|
| position | spring / viscous_lag / inertia |
| yaw | viscous_lag / spring / directional_lag |
| pitch | viscous_lag / spring |
| roll | easing / damped shake |
| fov | smoothstep / viscous_lag |
| zoom | smoothstep / spring |

注意角度环绕、数值域、安全范围。

---

## 5. 与覆盖链 / 数学模型的关系（方向）

```text
Base Provider 产出目标值
  → 迟滞 / 数学模型计算实际值
  → CameraState 基础状态

Modifier Chain 修改状态
  → 也可调用迟滞 / 数学模型
  → 最终状态
```

- 数学模型负责“数学函数怎么定义和复用”
- 迟滞负责“相机参数怎么自然变化”
- 覆盖链负责“谁提供 / 怎么合成”
- 渲染钩子只读最终状态

---

## 6. 可能的问题

- 第一版支持哪些行为
- 各通道默认行为
- 参数命名 / 单位 / 范围
- 与关键帧插值的边界
- 模型定义放在哪一层（片段 / 关键帧 / 脚本级）
- 状态生命周期与重置时机
- dt 语义与帧率一致性
- 与过渡、时间插值的边界
- 编辑器如何配置和预览
- 外部 API 开放时机
- 命名：迟滞 / 运动响应 / 镜头响应

---

## 7. 待定

- 行为清单
- 默认值
- 参数 schema
- 状态管理
- 编辑器 UI
- API

---

## 8. 落地顺序（方向）

1. 定义行为分类和参数方向
2. 复用底层数学模型实现基础行为
3. 接入 6 个相机通道
4. 接入 Base Provider / Modifier Chain
5. 编辑器 UI
6. 内部稳定后考虑外部 API

---

## 9. 相关文档

- [相机状态与覆盖链](./camera-state-plan.md)
- [数学函数模型](./math-models.md)
- [时间插值](./temporal-interpolation.md)
- [过渡](./transition.md)

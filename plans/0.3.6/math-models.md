# 0.3.6 数学函数模型（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 接口、字段、公式、JSON、注册细节等执行时再定
>
> 相关文档：
> - [相机状态与覆盖链](./camera-state-plan.md)
> - [时间插值](./temporal-interpolation.md)
> - [过渡](./transition.md)
> - [迟滞](./hysteresis.md)

---

## 1. 定位

建立**通用数学函数模型库**：

- 一个模型定义可以被模组内多个地方调用；
- 模型不绑定相机、轨道、UI 或具体功能；
- 数据驱动，内部优先，外部 API 顺带。

数学函数模型是底层能力，服务于：

- 相机参数运动
- 目标选择策略
- 音频参数
- UI / HUD 动画
- 编辑器预览
- 外部模组扩展

---

## 2. 已确认方向

1. **定义与实例分离**
   - 模型定义是数据；
   - 每个调用点有独立实例和独立状态。

2. **一处定义，多处调用**
   - 同一个模型定义可被多个通道 / 片段 / 模组引用；
   - 不共享可变状态。

3. **无状态与有状态分离**
   - 无状态：输出只由输入和时间决定；
   - 有状态：输出依赖历史，需要 dt 和状态。

4. **可组合**
   - 支持串联、并联、混合、映射。

5. **可序列化**
   - 模型定义可以用 JSON 描述；
   - 脚本 / 编辑器 / 外部 API 共用。

6. **确定性**
   - 相同输入 + 相同 dt 序列 + 相同 seed → 相同输出。

7. **无全局可变状态**
   - 状态属于实例，避免跨脚本污染。

8. **低开销、可回退、版本化**

---

## 3. 模型分类（方向）

### 3.1 曲线 / 缓动

- linear / smoothstep / ease / bezier / 分段曲线 / 自定义曲线

### 3.2 动态响应

- exponential_smooth / smooth_damp / spring / damper / viscous_lag / inertia / momentum

### 3.3 振荡 / 噪声

- sine / perlin / simplex / value_noise / trauma_shake / seeded_random / decay

### 3.4 非线性变换 / 响应

- deadband / hysteresis_threshold / directional_lag / stick_slip / backlash
- clamp / remap / scale / offset / curve_map

### 3.5 组合

- chain / blend / add / multiply / min / max / select / switch

### 3.6 输入源

- constant / time / keyframe / entity_attribute / external_input / model_input

> 具体模型清单、参数、接口、JSON 结构执行时再定。

---

## 4. 与调用方的关系

```text
数学函数模型（底层）
  ├─ 相机参数运动（迟滞 / 缓动 / 阻尼）
  ├─ 目标选择策略
  ├─ 音频参数
  ├─ UI / HUD 动画
  └─ 外部模组扩展
```

调用方只提供输入和上下文，不关心模型内部实现。

---

## 5. 可能的问题

- 模型接口最终长什么样（标量 / 向量 / 角度 / 颜色）
- 模型实例的粒度（通道 / 角色 / 片段 / 全局）
- dt 来源与采样策略
- 状态重置时机
- 模型定义放在哪一层（关键帧 / 片段 / 脚本级）
- 组合模型的嵌套深度与性能
- 模型失败 / 非法参数的回退策略
- 是否需要模型状态持久化
- 是否支持表达式 / 函数图
- 编辑器如何呈现（类型、参数、预览）
- 外部 API 的注册与版本兼容
- 命名：数学函数模型 / 运动模型 / 响应模型

---

## 6. 待定

- 统一接口形态
- JSON schema
- Registry 设计
- 内置模型清单
- 模型状态生命周期
- 编辑器 UI
- 外部 API 开放时机

---

## 7. 落地顺序（方向）

1. 定义模型分类和统一接口
2. 实现基础模型（曲线、动态响应）
3. 建立 Registry 和 JSON 描述
4. 接入第一个调用方（相机参数运动）
5. 建立“一处定义，多处调用”的复用机制
6. 接入编辑器
7. 内部稳定后开放外部 API

---

## 8. 相关文档

- [相机状态与覆盖链](./camera-state-plan.md)
- [时间插值](./temporal-interpolation.md)
- [过渡](./transition.md)
- [迟滞](./hysteresis.md)

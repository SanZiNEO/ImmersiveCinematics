# 0.3.6 相机状态与覆盖链设计（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 接口、字段、公式、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [数学函数模型](./math-models.md)
> - [时间插值](./temporal-interpolation.md)
> - [过渡](./transition.md)
> - [迟滞](./hysteresis.md)

---

## 1. 定位

**内部优先**：先把相机核心逻辑做干净，让后续扩展有稳定基础。
**API 顺带**：外部模组 API 是内部边界干净后的自然产物，不反过来主导内部设计。

---

## 2. 六参数模型（已确认）

相机最底层是 6 个参数，按组理解：

| 组 | 参数 |
|---|---|
| 位置核心 | `x, y, z` |
| 朝向核心 | `yaw, pitch` |
| 附加三个 | `roll, fov, zoom` |

后续所有相机功能都围绕这 6 个量展开。

对外期望形态：

- 平铺的只读状态接口（position / yaw / pitch / roll / fov / zoom）
- 内部可以分组（位置 / 朝向 / 附加）
- 读侧不直接暴露内部可变对象

---

## 3. 当前问题（方向）

1. 6 个参数分散在 `CameraPath` / `CameraProperties` 两个对象里，没有统一状态边界。
2. `CameraManager` 同时承担生命周期、脚本、预览、时钟、状态读写等多重职责。
3. 多个写入者直接写内部对象，谁最后写谁生效，没有优先级和所有权。
4. `CameraPath` / `CameraProperties` 同时有“当前值”和 staged 过渡值两套职责。
5. Mixin / 渲染层直接依赖具体类，未来扩展会继续扩散。
6. 外部 API 没有稳定入口。

---

## 4. staged 系统（已确认）

全仓检查确认 staged 是死代码：

- `CameraManager.stageTarget*` / `commitStagedState` / `isStagedReady` 无调用者
- `stagedReady` 永远不为 true
- `CameraPath` / `CameraProperties` 的 target / tick / overrideFrom 只被 staged 分支引用
- `TransitionType` 只有自身引用

编辑器不使用 staged：它是固定格式脚本生成器 + 脚本播放器 + HUD 预览，相机值走 direct 写值。

**0.3.6 方向：删除 staged 体系**，避免 direct 与 staged 两套状态模型并存。具体删除范围执行时再核对。

---

## 5. 覆盖链方向（已确认）

采用两个逻辑链：

### 5.1 Base Chain

- 决定“这一帧的基础相机状态”由谁提供
- 每一层是整帧 6 参数
- 例子：脚本导演、编辑器直控、飞行模式、外部接管
- 规则方向：按优先级选择；同优先级按注册顺序；无活跃层时回退
- Base 层天然是整帧覆盖，不做部分覆盖

### 5.2 Modifier Chain

- 在 Base 状态之上做部分参数修改
- 每个 Modifier 方向字段：优先级、通道掩码、合成模式
- 合成模式（已确认）：`REPLACE` / `ADD` / `MULTIPLY`
- 例子：震屏、后坐力、Dolly Zoom、其他镜头效果

### 5.3 组合顺序（方向）

```text
Base Chain 产出基础状态
  → Modifier Chain 按优先级依次修改
  → 最终 CameraState
  → 渲染钩子读取
```

---

## 6. 渲染钩子方向

最终状态统一由 `CameraState` 读取，应用位置保持现状：

| 参数 | 应用层 |
|---|---|
| position | `CameraMixin` |
| yaw / pitch | `CameraMixin` |
| roll | `GameRendererMixin`（PoseStack） |
| fov / zoom | `GameRendererMixin.getFov` |

Mixin 不再直接依赖 `CameraPath` / `CameraProperties`。

---

## 7. 迁移方向（不锁步骤）

- `CameraManager`：逐步收缩到生命周期 / 编排 / API 门面
- `CameraTrackPlayer`：变成 Base Provider
- 编辑器直控 / 飞行：变成 Base Provider
- Mixin / 渲染层：只读统一状态
- `CameraPath` / `CameraProperties`：可能保留为内部实现，也可能被状态 buffer 取代
- 外部 API：Phase 2/3，内部稳定后再评估

---

## 8. 可能的问题

- 优先级层级怎么分（Base 与 Modifier 是否各自独立）
- Base Override 是否需要 `suppressModifiers`
- 每个通道默认合成方式是什么
- 是否需要 blend / transition（与过渡文档联动）
- Modifier 之后是否统一 clamp / normalize
- 无 Base 活跃时回退到玩家相机还是保持最后状态
- 性能：每帧状态对象是否零分配
- 兼容：现有脚本字段和播放行为不能受影响
- 外部 API 的稳定性、版本、线程语义

---

## 9. 待定

- 统一状态接口的最终形态
- Base / Modifier 接口的最终形态
- 优先级数值
- 状态 buffer 设计
- staged 删除的具体范围
- 外部 API 开放时机
- `zoom` 是否保留独立参数，还是只暴露 effectiveFov

---

## 10. 覆盖目标

0.3.6 重构后，现有能力必须继续正常：

- 脚本播放：位置、look_at、follow、yaw_base / pitch_base
- 插值：linear / smooth / bezier / tangent / morph
- roll / fov / zoom
- cam breath
- 编辑器预览 / 直控
- 飞行模式
- 音频听者
- 区块预加载
- 选择器锁定策略

---

## 11. 相关文档

- [数学函数模型](./math-models.md)
- [时间插值](./temporal-interpolation.md)
- [过渡](./transition.md)
- [迟滞](./hysteresis.md)

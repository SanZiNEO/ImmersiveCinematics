# Script System — 扩展设计

---

## dissolve crossfade（溶解过渡）

### 概述

`dissolve` 是一种真正的 alpha 溶解过渡，与 `morph`（线性飞越）不同：

| 特性 | morph | dissolve |
|------|-------|----------|
| 实现方式 | 属性 lerp | 离屏渲染 + alpha 混合 |
| 运动 | 位置/角度从 A 末帧飞向 B 首帧 | A 保持静止，B 在顶部淡入 |
| 视觉效果 | 相机从一个点飞到另一个点 | A 的画面逐渐溶解为 B 的画面 |
| 渲染开销 | 无额外开销 | 需要 FBO 离屏渲染 |
| transition_duration | 确定飞行时长 | 确定溶解时长 |

### JSON 用法

```json
{
  "start_time": 4,
  "duration": 3,
  "transition": "dissolve",
  "transition_duration": 1.5,
  "position_mode": "relative",
  "loop": false,
  "keyframes": [
    { "time": 0,  "position": { "dx": 20, "dy": 1.6, "dz": 0 },  "yaw": 0, "pitch": 5, "roll": 0, "fov": 70, "zoom": 1.0, "dof": 0 },
    { "time": 3,  "position": { "dx": 20, "dy": 1.6, "dz": 15 }, "yaw": 0, "pitch": 5, "roll": 0, "fov": 70, "zoom": 1.0, "dof": 0 }
  ]
}
```

### 架构

```
CameraTrackPlayer.onRenderFrame()
  │
  ├── transition == CUT    → renderSingle() 硬切
  ├── transition == MORPH  → renderBlended() 属性 lerp（已实现）
  └── transition == DISSOLVE → renderDissolve() FBO 混合（待实现）

renderDissolve():
  1. 离屏渲染 A 的当前帧到 FBO_A
  2. 离屏渲染 B 的当前帧到 FBO_B
  3. 在屏幕上绘制全屏四边形，混合 FBO_A 和 FBO_B
     alpha = (globalTime - overlapStart) / transitionDuration
  4. 可选：使用 smoothstep 缓动 alpha
```

### 依赖

- 需要 OpenGL FBO 框架（离屏渲染缓冲区）
- 在 `overlay/` 包下新增 `DissolveOverlayLayer.java`
- 在 `TransitionType.java` 中新增 `DISSOLVE` 枚举值
- 在 `ScriptParser.java` 中支持 `"dissolve"` 解析
- 在 `CameraTrackPlayer.java` 中新增 `renderDissolve()` 方法

### 与现有 morph 的区别

- **morph**：在 `transition_duration` 内，位置/角度从 A 末帧线性变化到 B 首帧。适合相机从一个位置飞到另一个位置的过渡。
- **dissolve**：B 在 A 上方 alpha 溶解。A 的画面内容在 `transition_duration` 内逐渐消失，B 的内容逐渐浮现。适合不同场景之间的柔和切换。

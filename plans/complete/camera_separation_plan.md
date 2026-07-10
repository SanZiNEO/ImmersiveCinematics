# 相机属性分离实施计划 ✅ 已完成

> 所有变更已实施并验证。本文档为压缩归档版，详细代码见实际源文件。

## 架构决策（已实施）

```
CameraPath       →  position(x, y, z)           — 相机在世界中的位置轨迹
CameraProperties →  yaw, pitch, roll, FOV, DOF, Zoom  — 相机的朝向和光学属性
CameraManager    →  唯一桥梁，驱动两者 tick，Mixin 只从这里读
```

## 已完成的变更

| # | 操作 | 文件 |
|---|------|------|
| 1 | 新建 | `CameraPath.java` — 纯位置管理 + partialTick 帧级插值 |
| 2 | 新建 | `CameraProperties.java` — 朝向+光学属性 + lerpAngle 角度环绕 + partialTick 帧级插值 |
| 3 | 新建 | `CameraManager.java` — 单例桥梁 + 双缓冲 staged/commit 架构 |
| 4 | 修改 | `CameraMixin.java` — 从 CameraManager 读取 position/yaw/pitch + onGetEntity + onIsDetached |
| 5 | 修改 | `GameRendererMixin.java` — 从 CameraManager 读取 FOV |
| 6 | 删除 | `EntityRenderDispatcherMixin.java`（纯数据方案无 Entity） |
| 7 | 修改 | `Immersive_cinematics.java` — 移除 Entity 注册，P键改为 toggle + ClientTickEvent |
| 8 | 删除 | `CinematicCameraEntity.java` + `CinematicCameraHandler.java` |
| 9 | 更新 | `immersive_cinematics.mixins.json` — 移除 EntityRenderDispatcherMixin |

## 关键发现（实施中习得）

1. **Camera.setRotation() 内部 Roll 硬编码为 0** — `rotation.rotationYXZ(-yaw, pitch, 0.0F)`，需通过 Forge 事件注入
2. **cancel setup 后 entity 字段为 null** — onGetEntity 必须返回玩家 Entity 避免渲染管线 NPE
3. **detached 字段影响玩家身体渲染** — 但不影响手臂渲染（手臂由 CameraType.isFirstPerson() 控制）
4. **原版 Camera.tick() 只做 eyeHeight 插值** — 与我们的 CameraManager.tick() 完全独立

## 超出原计划的改进

- **双缓冲架构** — staged/commit 支持多段镜头间硬切换，`overrideFrom()` 设置 `previous = current` 消除 1tick 飞越
- **partialTick 帧级插值** — `savePreviousTick()` + `getXxxInterpolated(partialTick)` 消除 20tick/s → 60fps 阶梯感
- **Vec3 依赖可接受** — 纯值类无副作用，未来可替换为三个 float 完全解耦

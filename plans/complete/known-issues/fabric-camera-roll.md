# Fabric 相机 Roll 问题

## 现象（已修复）

Fabric 端 Camera Roll 导致实体/方块碰撞箱被旋转。

## 根因

`GameRendererMixin.onCameraSetup()` 中直接修改 `RenderSystem.getModelViewStack()`，这个矩阵栈不仅控制相机视角，也控制碰撞箱/实体/方块的世界坐标渲染。Roll 旋转应用在错误的作用域，导致场景视觉和碰撞箱同时被旋转。

## 修复方法

将 Roll 合并到 `Camera` 本身的旋转四元数中，而不是修改全局模型视图矩阵：

```
CameraMixin.onSetup()
  ├── setRotation(yaw, pitch)
  ├── rotation.mul(rollQuaternion)   ← 新增：将 Roll 编码到相机旋转里
  └── ci.cancel()

GameRendererMixin.onCameraSetup()  ← 删除：不再需要
```

渲染管线使用 `Camera.rotation()` 获取相机朝向，自然包含 Roll。不需要修改矩阵栈。

## 修改的文件

- `mixin/CameraMixin.java` — 添加 roll 到相机旋转
- `mixin/GameRendererMixin.java` — 删除 `onCameraSetup()` 方法

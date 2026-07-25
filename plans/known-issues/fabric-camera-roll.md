# Fabric 相机 Roll 不生效

## 现象

在 Fabric 端运行测试时，电影脚本中的相机 Roll（翻滚角）参数无效。Forge 端正常。

## 现有实现

Camera Roll 在 `common/` 的 `GameRendererMixin.onCameraSetup()` 中处理：

```
@Inject(method = "renderLevel",
    at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;setup(...)V",
        shift = At.Shift.AFTER))
```

注入后在模型视图矩阵上应用 Roll 旋转：
```java
RenderSystem.getModelViewStack().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(rollDeg));
```

## 猜测原因

1. **Mixin 目标不匹配** — Fabric 加载的 `GameRenderer` 字节码可能和 Forge 有差异（Forge 用 transformers 修改了部分类），导致 `@At` 定位不到 `Camera.setup()` 调用点。Mixin 静默失败没有报错，但 Roll 没有生效。

2. **模型视图栈状态不同** — Fabric 和 Forge 在 `renderLevel()` 中的 `RenderSystem.getModelViewStack()` 的 push/pop 时机可能不同，注入点时栈还没 push 导致矩阵操作被后续重置覆盖。

3. **渲染管线差异** — Fabric 端 Sodium/Oculus/Iris 等渲染优化模组可能绕过了原版 `GameRenderer.renderLevel()`，导致 Mixin 根本不会触发。

## 待验证

- [ ] 在 Mixin 中添加日志输出，确认 `onCameraSetup` 方法是否被调用
- [ ] 验证 `Camera.setup` 的 Mixin target 在 Fabric 端是否能匹配
- [ ] 尝试用 `@At("HEAD")` 注入 `renderLevel` 并用 `LocalCapture` 捕获 PoseStack
- [ ] 检查是否安装了 Sodium/Oculus 等渲染模组

## 参考

旧 Forge 实现使用 `ViewportEvent.ComputeCameraAngles`，通过 Forge 事件总线在 `renderLevel` 中触发。Fabric 没有等效事件，需通过 Mixin 直接注入。

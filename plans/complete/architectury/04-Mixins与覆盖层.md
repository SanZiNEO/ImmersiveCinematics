# 阶段 4：Mixins 与覆盖层

## 目标

迁移 Mixin 系统和覆盖层系统（overlay/）到 common 模块。Mixin 类全部为纯 Mixin 代码不依赖 Forge，覆盖层中除了 `CinematicOverlay.java` 使用 Forge GUI overlay 注册外，其他均为纯 Vanilla。

## 涉及文件

### 旧文件 (old/src/main/java/)

**mixin/**（全部纯 Mixin，无平台依赖 → 移入 common）
| 文件 | 说明 |
|------|------|
| `mixin/CameraMixin.java` | 拦截相机位置/旋转，实现电影相机 |
| `mixin/GameRendererMixin.java` | 拦截 FOV、手臂渲染、镜头摇晃 |
| `mixin/KeyboardHandlerMixin.java` | 电影模式下屏蔽键盘输入 |
| `mixin/MouseHandlerMixin.java` | 电影模式下屏蔽鼠标输入 |
| `mixin/LivingEntityMixin.java` | 电影模式下禁用怪物攻击玩家 |

**overlay/**（纯 Vanilla 部分）
| 文件 | 说明 |
|------|------|
| `overlay/OverlayManager.java` | 覆盖层管理器（纯 Vanilla） |
| `overlay/OverlayLayer.java` | 覆盖层基类（纯 Vanilla） |
| `overlay/LetterboxLayer.java` | 黑边覆盖层实现（纯 Vanilla） |
| `overlay/CinematicOverlay.java` | **Forge 依赖**：RegisterGuiOverlaysEvent + IGuiOverlay → 需改造 |

## 迁移步骤

### Step 1: Mixin 迁移

将 5 个 Mixin 文件复制到：
```
common/src/main/java/com/immersivecinematics/immersive_cinematics/mixin/
```

**无需修改**，Mixins 是 Vanilla 字节码操作，不受加载器影响。

### Step 2: 更新 mixins.json

更新 `common/src/main/resources/immersive_cinematics.mixins.json`：

```json
{
  "required": true,
  "package": "com.immersivecinematics.immersive_cinematics.mixin",
  "compatibilityLevel": "JAVA_17",
  "minVersion": "0.8",
  "client": [
    "CameraMixin",
    "GameRendererMixin",
    "LivingEntityMixin",
    "KeyboardHandlerMixin",
    "MouseHandlerMixin"
  ],
  "mixins": [],
  "injectors": {
    "defaultRequire": 1
  },
  "overwrites": {
    "requireAnnotations": true
  }
}
```

### Step 3: 确认 Forge LoM 配置

在 `forge/build.gradle` 中已有 mixin 配置：
```groovy
loom {
    forge {
        mixinConfig "immersive_cinematics.mixins.json"
    }
}
```
无需修改。

Fabric 会自动识别 `fabric.mod.json` 中声明的 mixin 配置。

### Step 4: Overlay 纯 Vanilla 部分迁移

将以下文件复制到 `common/`：
```
overlay/OverlayManager.java
overlay/OverlayLayer.java
overlay/LetterboxLayer.java
```

这些是纯 Vanilla 渲染代码，直接迁移。

### Step 5: CinematicOverlay 改造

**当前状态**：
- 使用 Forge `RegisterGuiOverlaysEvent` 注册 overlay
- 使用 Forge `IGuiOverlay` 接口进行渲染

**改造方案**：

```java
// common/ - CinematicOverlay.java
// 删除 Forge 事件注册，纯渲染逻辑保留
public class CinematicOverlay {
    public static final String OVERLAY_ID = "cinematic_overlay";

    // 渲染逻辑抽出为静态方法
    public static void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        boolean cameraActive = CameraManager.INSTANCE.isActive();
        boolean overlayAnimating = OverlayManager.INSTANCE.isAnimating();
        if (!cameraActive && !overlayAnimating) return;
        OverlayManager.INSTANCE.render(guiGraphics, screenWidth, screenHeight);
    }
}
```

```java
// fabric/ - ImmersiveCinematicsFabricClient.java
// 使用 ClientGuiEvent.RENDER_HUD
ClientGuiEvent.RENDER_HUD.register((graphics, deltaTracker) -> {
    CinematicOverlay.render(graphics, 
        Minecraft.getInstance().getWindow().getGuiScaledWidth(),
        Minecraft.getInstance().getWindow().getGuiScaledHeight());
});
```

```java
// forge/ - ImmersiveCinematicsForge.java 或 ForgeClientEvents.java
// 同样使用 ClientGuiEvent.RENDER_HUD（Architectury 统一 API）
ClientGuiEvent.RENDER_HUD.register((graphics, deltaTracker) -> {
    CinematicOverlay.render(graphics, 
        Minecraft.getInstance().getWindow().getGuiScaledWidth(),
        Minecraft.getInstance().getWindow().getGuiScaledHeight());
});
```

### Step 6: Camera Roll 处理

旧代码中，Roll 角通过 Forge `ViewportEvent.ComputeCameraAngles` 事件设置。Architectury 没有等效事件，替换方案：

**方案 A**：在 `CameraMixin` 中直接设置 Roll
```java
// CameraMixin.java - 在 onSetup 中追加
@Inject(method = "setup", at = @At("HEAD"), cancellable = true)
private void onSetup(..., CallbackInfo ci) {
    // ... 现有逻辑 ...
    // Roll 通过 Mixin 直接处理
    // (Camera 类没有直接的 setRoll，需要反射或 mixin)
}
```

**方案 B**：保留 Mixin + 在 `GameRendererMixin` 中处理
Roll 最终在渲染管线中通过 `PoseStack.mulPose()` 应用。可在 `GameRendererMixin` 中拦截 `renderLevel` 方法，在 PoseStack 上应用 Roll 旋转。

**建议**：采用方案 B，在 `GameRendererMixin` 中添加 Roll 处理，移除对 Forge `ViewportEvent` 的依赖。

## 编译与测试

### 测试清单
- [ ] Fabric 客户端启动 — mixin 应用正常（`--mixin` 无报错）
- [ ] Forge 客户端启动 — mixin 应用正常
- [ ] 使用 `/immersive_cinematics test_camera` 等测试（如已实现）观察 mixin 效果
- [ ] 相机 Mixin：电影模式下视角正常
- [ ] 键盘/鼠标 Mixin：电影模式下输入被正确屏蔽
- [ ] FOV Mixin：电影模式下 FOV 被正确覆盖
- [ ] 镜头摇晃 Mixin：电影模式下受伤/传送门摇晃被屏蔽
- [ ] Overlay：黑边渲染无异常（即使没有电影播放，不崩溃即可）
- [ ] 禁用旧架构代码编译（注释或删除 old 目录对编译的影响）

### 注意事项

- Mixin 一旦应用，对整个游戏生效。如果 `CameraManager.INSTANCE.isActive()` 返回 false（因为事件尚未注册），所有 mixin 应该不产生副作用（通过 `return` 跳过）。
- 建议在 `CameraManager.isActive()` 返回 `false` 的情况下验证：游戏运行是否完全正常，无任何异常行为。

## 验证方法

进入游戏后：
1. 单人世界加载正常
2. 视角可以正常移动（无抖动/卡死）
3. 按 Esc 正常打开暂停菜单
4. 键盘和鼠标输入正常
5. 按 `F6` 编辑器键无反应（编辑器尚未迁移，正常行为）
6. 按 `C` 跳过键无反应（功能尚未接入）

# 统一控制系统设计

> 状态：📋 设计完成，待实施 | 创建时间：2026-05-02 | 更新时间：2026-05-02

---

## 一、当前问题诊断

### 1.1 属性死代码统计

15 个 ScriptProperties 属性中 **9 个是死代码**——定义了但从未被消费：

| 属性 | 定义处 | 消费处 | 状态 |
|------|--------|--------|------|
| `blockKeyboard` | ScriptProperties/ScriptMeta | **无** | ❌ 死代码 |
| `blockMouse` | ScriptProperties/ScriptMeta | **无** | ❌ 死代码 |
| `blockMobAi` | ScriptProperties/ScriptMeta | **无** | ❌ 死代码（需 C/S 架构） |
| `blockChat` | ScriptProperties/ScriptMeta | **无** | ❌ 死代码 |
| `blockScoreboard` | ScriptProperties/ScriptMeta | **无** | ❌ 死代码 |
| `blockActionBar` | ScriptProperties/ScriptMeta | **无** | ❌ 死代码 |
| `blockParticles` | ScriptProperties/ScriptMeta | **无** | ❌ 死代码（**删除，无法实现**） |
| `renderPlayerModel` | ScriptProperties/ScriptMeta | **无** | ❌ 死代码 |
| `skippable` | ScriptProperties/ScriptMeta | **无** | ❌ 死代码 |
| `hideHud` | ScriptProperties/ScriptMeta | HudOverlayHandler | ✅ 已消费 |
| `hideArm` | ScriptProperties/ScriptMeta | GameRendererMixin | ✅ 已消费 |
| `suppressBob` | ScriptProperties/ScriptMeta | GameRendererMixin | ✅ 已消费 |
| `pauseWhenGamePaused` | ScriptProperties/ScriptMeta | CameraManager（间接） | ⚠️ 双重存储 |
| `interruptible` | ScriptProperties/ScriptMeta | CameraManager.playScript() | ✅ 已消费 |
| `holdAtEnd` | ScriptProperties/ScriptMeta | CameraManager.onRenderFrame() | ✅ 已消费 |

### 1.2 各子系统问题

#### 退出控制（ExitManager）

- `skippable` 是死代码——没有 KeyMapping，没有按键检查
- 退出路径散乱：`deactivateNow()`/`deactivate()`/`stopScript()` 行为不一致
- `holdAtEnd=true` 的脚本没有用户退出路径，永远卡住
- `playScript()` 抢占时直接 `deactivateNow()`，无 fade-out

#### HUD 屏蔽（HudManager）

- 白名单硬编码，只有 `CinematicOverlay`
- `blockChat`/`blockScoreboard`/`blockActionBar` 定义了但从未被使用
- 当前是全有或全无：`hideHud=true` 屏蔽一切，`hideHud=false` 不屏蔽任何东西
- 无法实现"隐藏 HUD 但保留聊天"等细粒度控制

#### 输入屏蔽（InputManager）

- `blockKeyboard`/`blockMouse` 定义了但**整个代码库没有任何输入拦截代码**
- 没有 KeyMapping 注册
- 没有 KeyboardHandler / MouseHandler Mixin
- 脚本播放时玩家仍可自由 WASD 移动、鼠标转视角、打开物品栏

#### 视觉效果（VisualManager）

- `renderPlayerModel` 是死代码——`CameraMixin.onIsDetached()` 无条件返回 true
- `hideArm`/`suppressBob` 已消费但各 Mixin 直接读取 `getCurrentProperties()`，没有统一入口

#### 其他

- `blockParticles`：粒子系统是全局的，服务端创建、客户端只渲染，无法按客户端控制——**删除**
- `blockMobAi`：需要服务端感知脚本状态，当前纯客户端无法实现——保留定义，标记为"需 C/S"
- `pauseWhenGamePaused`：CameraManager 和 ScriptProperties 双重存储，违反单一数据源

---

## 二、统一架构：CinematicController

### 2.1 设计原则

1. **单一入口**：所有控制逻辑通过 `CinematicController` 统一调度，Mixin/Handler 不直接读 `ScriptProperties`
2. **子系统自治**：每个子系统（Exit/Input/Hud/Visual）管理自己的状态，Controller 只负责 `apply()`/`revert()`
3. **属性即配置**：ScriptProperties 是纯数据配置，Controller 是运行时消费者
4. **删除不可实现的属性**：`blockParticles` 删除

### 2.2 架构图

```
┌─────────────────────────────────────────────────────────┐
│                    CinematicController                    │
│                   (统一调度，单例模式)                     │
│                                                          │
│  apply(ScriptProperties)  ─── 脚本启动时调用一次          │
│  revert()                 ─── 脚本停止时调用一次          │
│  requestExit(ExitReason)  ─── 统一退出入口               │
│  onClientTick()           ─── 按键检测（每 tick）         │
│                                                          │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐     │
│  │  ExitManager  │ │ InputManager │ │  HudManager  │     │
│  │              │ │              │ │              │     │
│  │ skippable    │ │ blockKeyboard│ │ hideHud      │     │
│  │ interruptible│ │ blockMouse   │ │ blockChat    │     │
│  │ holdAtEnd    │ │              │ │ blockScoreboard│   │
│  │              │ │              │ │ blockActionBar│    │
│  └──────────────┘ └──────────────┘ └──────────────┘     │
│                                                          │
│  ┌──────────────┐                                       │
│  │ VisualManager│                                       │
│  │              │                                       │
│  │ hideArm      │                                       │
│  │ suppressBob  │                                       │
│  │ renderPlayer │                                       │
│  │ Model        │                                       │
│  └──────────────┘                                       │
└─────────────────────────────────────────────────────────┘
         │              │              │              │
         ▼              ▼              ▼              ▼
   CameraManager    KeyboardMixin  HudOverlay     GameRenderer
   (requestExit)    MouseMixin     Handler        Mixin
                   (cancel input)  (dynamic       CameraMixin
                                   whitelist)
```

### 2.3 CinematicController 核心代码

```java
package com.immersivecinematics.immersive_cinematics.control;

public class CinematicController {

    public static final CinematicController INSTANCE = new CinematicController();

    // === 子系统状态 ===

    // 退出控制
    private boolean skippable = true;
    private boolean interruptible = true;
    private boolean holdAtEnd = false;

    // 输入屏蔽
    private boolean blockKeyboard = true;
    private boolean blockMouse = true;

    // HUD 屏蔽
    private boolean hideHud = true;
    private boolean blockChat = false;
    private boolean blockScoreboard = false;
    private boolean blockActionBar = false;

    // 视觉效果
    private boolean hideArm = true;
    private boolean suppressBob = true;
    private boolean renderPlayerModel = true;

    // === 生命周期 ===

    /**
     * 脚本启动时调用 — 从 ScriptProperties 统一配置所有子系统
     */
    public void apply(ScriptProperties props) {
        // 退出控制
        this.skippable = props.isSkippable();
        this.interruptible = props.isInterruptible();
        this.holdAtEnd = props.isHoldAtEnd();

        // 输入屏蔽
        this.blockKeyboard = props.isBlockKeyboard();
        this.blockMouse = props.isBlockMouse();

        // HUD 屏蔽
        this.hideHud = props.isHideHud();
        this.blockChat = props.isBlockChat();
        this.blockScoreboard = props.isBlockScoreboard();
        this.blockActionBar = props.isBlockActionBar();

        // 视觉效果
        this.hideArm = props.isHideArm();
        this.suppressBob = props.isSuppressBob();
        this.renderPlayerModel = props.isRenderPlayerModel();
    }

    /**
     * 脚本停止时调用 — 统一恢复所有子系统到默认状态
     */
    public void revert() {
        this.skippable = true;
        this.interruptible = true;
        this.holdAtEnd = false;
        this.blockKeyboard = true;
        this.blockMouse = true;
        this.hideHud = true;
        this.blockChat = false;
        this.blockScoreboard = false;
        this.blockActionBar = false;
        this.hideArm = true;
        this.suppressBob = true;
        this.renderPlayerModel = true;
    }

    // === Mixin/Handler 读取接口（只读） ===

    // 退出控制
    public boolean isSkippable() { return skippable; }
    public boolean isInterruptible() { return interruptible; }
    public boolean isHoldAtEnd() { return holdAtEnd; }

    // 输入屏蔽
    public boolean isBlockKeyboard() { return blockKeyboard; }
    public boolean isBlockMouse() { return blockMouse; }

    // HUD 屏蔽
    public boolean isHideHud() { return hideHud; }
    public boolean isBlockChat() { return blockChat; }
    public boolean isBlockScoreboard() { return blockScoreboard; }
    public boolean isBlockActionBar() { return blockActionBar; }

    // 视觉效果
    public boolean isHideArm() { return hideArm; }
    public boolean isSuppressBob() { return suppressBob; }
    public boolean isRenderPlayerModel() { return renderPlayerModel; }
}
```

---

## 三、退出控制系统（ExitManager）

### 3.1 退出优先级

| 优先级 | 触发方式 | 检查条件 | 退出行为 | CompletionReason |
|--------|---------|---------|---------|-----------------|
| **P5 强制退出** | `Ctrl+P`（硬编码，不可更改） | **无** — 无视一切 | 立即 `deactivateNow()`，无 fade-out | `FORCE_QUIT` |
| **P4 系统停止** | `/cinematic stop` 命令 / 服务端 `S2CStopScriptPacket` | **无** — 管理员/系统权限 | `deactivate()` → fade-out → `deactivateNow()` | `STOPPED` |
| **P3 脚本抢占** | `playScript(newScript)` | `interruptible=true` 才允许 | `deactivate()` → fade-out → `deactivateNow()` | `INTERRUPTED` |
| **P2 常规跳过** | 长按 `Esc`（可自定义按键） | `skippable=true` 才允许 | `deactivate()` → fade-out → `deactivateNow()` | `SKIPPED` |
| **P1 自然结束** | 时间耗尽 | `holdAtEnd=false` 才退出 | `holdAtEnd=false` → fade-out 退出；`holdAtEnd=true` → 保持 | `FINISHED` |

### 3.2 ExitReason 枚举

```java
public enum ExitReason {
    FORCE_QUIT,      // P5: 强制退出键 (Ctrl+P)
    SYSTEM_STOP,     // P4: 系统停止（命令/服务端）
    INTERRUPTED,     // P3: 被新脚本抢占
    USER_SKIP,       // P2: 用户常规跳过
    NATURAL_END      // P1: 自然结束
}
```

### 3.3 CompletionReason 枚举

```java
public enum CompletionReason {
    FORCE_QUIT,      // 强制退出
    STOPPED,         // 系统停止
    INTERRUPTED,     // 被新脚本抢占
    SKIPPED,         // 用户跳过
    FINISHED         // 自然播放完成
}
```

### 3.4 CameraManager.requestExit() — 统一退出入口

```java
/**
 * 统一退出入口 — 所有退出路径必须经过此方法
 *
 * @param reason 退出原因
 * @return true=退出成功，false=退出被拒绝
 */
public boolean requestExit(ExitReason reason) {
    CinematicController ctrl = CinematicController.INSTANCE;

    switch (reason) {
        case FORCE_QUIT:
            // P5: 无条件立即退出，安全阀
            deactivateNow();
            return true;

        case SYSTEM_STOP:
            // P4: 系统级停止，无条件但走 fade-out
            deactivate();
            return true;

        case INTERRUPTED:
            // P3: 检查 interruptible
            if (!ctrl.isInterruptible()) {
                LOGGER.debug("脚本不可打断(interruptible=false)，拒绝抢占");
                return false;
            }
            deactivate();
            return true;

        case USER_SKIP:
            // P2: 检查 skippable
            if (!ctrl.isSkippable()) {
                LOGGER.debug("脚本不可跳过(skippable=false)，拒绝用户退出");
                return false;
            }
            deactivate();
            return true;

        case NATURAL_END:
            // P1: 检查 holdAtEnd
            if (ctrl.isHoldAtEnd()) {
                return false;  // 保持最后一帧
            }
            deactivate();
            return true;
    }
    return false;
}
```

### 3.5 三种典型场景验证

#### 场景A：生化危机0 固定机位

```json
{ "skippable": false, "interruptible": true, "hold_at_end": true }
```

| 退出方式 | 结果 | 原因 |
|---------|------|------|
| P5 Ctrl+P | ✅ 立即退出 | 安全阀，无条件 |
| P4 /cinematic stop | ✅ fade-out 退出 | 系统级权限 |
| P3 新脚本抢占 | ✅ fade-out 退出 | `interruptible=true` |
| P2 长按 Esc | ❌ 拒绝 | `skippable=false` |
| P1 自然结束 | ❌ 永不结束 | `duration:-1` |

#### 场景B：监控巡逻视角

```json
{ "skippable": true, "interruptible": true, "hold_at_end": true }
```

| 退出方式 | 结果 | 原因 |
|---------|------|------|
| P5 Ctrl+P | ✅ 立即退出 | 安全阀 |
| P4 /cinematic stop | ✅ fade-out 退出 | 系统级权限 |
| P3 新脚本抢占 | ✅ fade-out 退出 | `interruptible=true` |
| P2 长按 Esc | ✅ fade-out 退出 | `skippable=true` |
| P1 自然结束 | ❌ 永不结束 | `duration:-1` |

#### 场景C：强制过场动画

```json
{ "skippable": false, "interruptible": false, "hold_at_end": false }
```

| 退出方式 | 结果 | 原因 |
|---------|------|------|
| P5 Ctrl+P | ✅ 立即退出 | 安全阀 |
| P4 /cinematic stop | ✅ fade-out 退出 | 系统级权限 |
| P3 新脚本抢占 | ❌ 拒绝 | `interruptible=false` |
| P2 长按 Esc | ❌ 拒绝 | `skippable=false` |
| P1 自然结束 | ✅ fade-out 退出 | `holdAtEnd=false`，30秒后 |

---

## 四、输入屏蔽系统（InputManager）

### 4.1 设计

| 属性 | 控制范围 | 实现方式 |
|------|---------|---------|
| `blockKeyboard` | WASD/跳跃/潜行/物品栏/聊天/丢弃/切换槽位等 | `KeyboardHandler` Mixin 拦截 `keyPress()` |
| `blockMouse` | 鼠标移动/左键/右键/中键/滚轮 | `MouseHandler` Mixin 拦截 `turnPlayer()` + `onMouseButton()` |

### 4.2 退出键豁免

输入屏蔽**必须豁免**退出相关按键，否则用户无法退出脚本：

- `blockKeyboard=true` 时，仍放行：Esc（跳过键）、Ctrl+P（强制退出）
- `blockMouse=true` 时，仍放行：无（鼠标没有退出功能）

### 4.3 KeyboardHandler Mixin

```java
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    /**
     * 拦截按键输入 — 电影模式下屏蔽非退出键
     * <p>
     * CinematicKeyBindings 的按键检测在 onClientTick() 中执行，
     * 此处只负责屏蔽其他按键，避免玩家在脚本播放时操作角色。
     * <p>
     * 豁免键：Esc（跳过键由 CinematicKeyBindings 处理长按逻辑）
     */
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void onKeyPress(long windowPointer, int key, int scanCode,
                            int action, int modifiers, CallbackInfo ci) {
        CinematicController ctrl = CinematicController.INSTANCE;
        if (!CameraManager.INSTANCE.isActive()) return;
        if (!ctrl.isBlockKeyboard()) return;

        // 豁免退出相关键
        // Esc: 跳过键（长按逻辑由 CinematicKeyBindings 处理）
        // Ctrl+P: 强制退出键（由 CinematicKeyBindings 直接检测 GLFW 状态）
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            return;  // 放行，让 CinematicKeyBindings 处理长按
        }

        // 屏蔽其他所有按键
        ci.cancel();
    }
}
```

### 4.4 MouseHandler Mixin

```java
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    /**
     * 拦截鼠标移动 — 电影模式下禁止鼠标转动视角
     */
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void onTurnPlayer(CallbackInfo ci) {
        CinematicController ctrl = CinematicController.INSTANCE;
        if (CameraManager.INSTANCE.isActive() && ctrl.isBlockMouse()) {
            ci.cancel();
        }
    }

    /**
     * 拦截鼠标按键 — 电影模式下屏蔽左键/右键/中键
     */
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void onMouseButton(long windowPointer, int button, int action,
                               int modifiers, CallbackInfo ci) {
        CinematicController ctrl = CinematicController.INSTANCE;
        if (CameraManager.INSTANCE.isActive() && ctrl.isBlockMouse()) {
            ci.cancel();
        }
    }

    /**
     * 拦截鼠标滚轮 — 电影模式下屏蔽滚轮切换槽位
     */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void onScroll(long windowPointer, double xOffset, double yOffset,
                          CallbackInfo ci) {
        CinematicController ctrl = CinematicController.INSTANCE;
        if (CameraManager.INSTANCE.isActive() && ctrl.isBlockMouse()) {
            ci.cancel();
        }
    }
}
```

---

## 五、HUD 屏蔽系统（HudManager）

### 5.1 当前问题

[`HudOverlayHandler`](../src/main/java/com/immersivecinematics/immersive_cinematics/handler/HudOverlayHandler.java) 的白名单是**静态硬编码**的：

```java
private static final Set<ResourceLocation> ALLOWED_OVERLAYS = Set.of(
    new ResourceLocation(Immersive_cinematics.MODID, CinematicOverlay.OVERLAY_ID)
);
```

`blockChat`/`blockScoreboard`/`blockActionBar` 定义了但从未被使用。

### 5.2 动态白名单设计

白名单根据 `CinematicController` 的属性**动态构建**：

```java
public class HudOverlayHandler {

    // 始终放行的 overlay（我们的电影覆盖层）
    private static final Set<ResourceLocation> BASE_ALLOWED = Set.of(
            new ResourceLocation(Immersive_cinematics.MODID, CinematicOverlay.OVERLAY_ID)
    );

    // 可选放行的 overlay（根据脚本属性动态决定）
    private static final Map<ResourceLocation, java.util.function.Supplier<Boolean>> OPTIONAL_ALLOWED = Map.of(
            // 聊天框
            VanillaGuiOverlay.CHAT.id(),
            () -> !CinematicController.INSTANCE.isBlockChat(),
            // 侧边栏/计分板
            VanillaGuiOverlay.PLAYER_LIST.id(),
            () -> !CinematicController.INSTANCE.isBlockScoreboard(),
            // 动作条（ActionBar 文本）
            VanillaGuiOverlay.ACTION_BAR.id(),
            () -> !CinematicController.INSTANCE.isBlockActionBar()
    );

    public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (!CameraManager.INSTANCE.isActive()) return;

        CinematicController ctrl = CinematicController.INSTANCE;
        ResourceLocation overlayId = event.getOverlay().id();

        // 始终放行基础 overlay
        if (BASE_ALLOWED.contains(overlayId)) return;

        // hideHud=false 时不屏蔽任何 overlay
        if (!ctrl.isHideHud()) return;

        // 检查可选放行
        java.util.function.Supplier<Boolean> allowed = OPTIONAL_ALLOWED.get(overlayId);
        if (allowed != null && allowed.get()) return;  // 属性允许放行

        // 屏蔽
        event.setCanceled(true);
    }
}
```

### 5.3 属性语义

| 属性 | 默认值 | 语义 |
|------|--------|------|
| `hideHud` | true | 总开关：是否屏蔽 HUD |
| `blockChat` | false | 在 `hideHud=true` 时，是否额外屏蔽聊天框（false=保留聊天） |
| `blockScoreboard` | false | 在 `hideHud=true` 时，是否额外屏蔽侧边栏 |
| `blockActionBar` | false | 在 `hideHud=true` 时，是否额外屏蔽动作条 |

**注意**：`blockChat=false` 的语义是"不屏蔽聊天"（保留聊天），不是"屏蔽聊天"。这是**白名单逻辑**：`block*` 属性为 false 时放行对应 overlay。

### 5.4 典型配置

| 场景 | hideHud | blockChat | blockScoreboard | blockActionBar | 效果 |
|------|---------|-----------|-----------------|----------------|------|
| 标准过场 | true | false | false | false | 只保留聊天/侧边栏/动作条 |
| 沉浸过场 | true | true | true | true | 全部屏蔽 |
| 调试模式 | false | — | — | — | 不屏蔽任何 HUD |
| 直播模式 | true | false | true | true | 保留聊天，屏蔽其他 |

---

## 六、视觉效果控制（VisualManager）

### 6.1 控制项

| 属性 | 默认值 | 控制什么 | Mixin 拦截点 |
|------|--------|---------|-------------|
| `hideArm` | true | 隐藏第一人称手臂 + 手持物品 + 屏幕覆盖效果（水/岩浆边框） | `GameRendererMixin.onRenderItemInHand()` |
| `suppressBob` | true | 屏蔽受伤摇晃 + 行走摇晃 + 反胃/下界传送门扭曲 | `GameRendererMixin.onBobHurt()`/`onBobView()`/`redirectSpinningIntensity()` |
| `renderPlayerModel` | true | 电影模式下是否渲染玩家身体模型 | `CameraMixin.onIsDetached()` |

### 6.2 renderPlayerModel 修复

当前 [`CameraMixin.onIsDetached()`](../src/main/java/com/immersivecinematics/immersive_cinematics/mixin/CameraMixin.java) 无条件返回 true：

```java
// 当前（错误）
private void onIsDetached(CallbackInfoReturnable<Boolean> cir) {
    CameraManager mgr = CameraManager.INSTANCE;
    if (mgr.isActive()) {
        cir.setReturnValue(true);  // ← 永远返回 true，从不检查 renderPlayerModel
    }
}
```

修复后：

```java
// 修复后
private void onIsDetached(CallbackInfoReturnable<Boolean> cir) {
    CameraManager mgr = CameraManager.INSTANCE;
    if (mgr.isActive()) {
        cir.setReturnValue(CinematicController.INSTANCE.isRenderPlayerModel());
    }
}
```

### 6.3 GameRendererMixin 改用 CinematicController

```java
// 当前（直接读 ScriptProperties）
@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
private void onRenderItemInHand(CallbackInfo ci) {
    ScriptProperties props = CameraManager.INSTANCE.getCurrentProperties();
    if (props != null && props.isHideArm()) {
        ci.cancel();
    }
}

// 修复后（从 CinematicController 读取）
@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
private void onRenderItemInHand(CallbackInfo ci) {
    if (CameraManager.INSTANCE.isActive() && CinematicController.INSTANCE.isHideArm()) {
        ci.cancel();
    }
}
```

### 6.4 典型配置

| 场景 | hideArm | suppressBob | renderPlayerModel | 效果 |
|------|---------|-------------|-------------------|------|
| 标准过场 | true | true | true | 无手臂、无摇晃、有玩家身体 |
| 第一人称叙事 | false | true | false | 有手臂（手持物品叙事）、无摇晃、无身体模型 |
| 监控摄像头 | true | true | false | 纯旁观视角，无手臂无身体 |

---

## 七、按键绑定设计

### 7.1 按键定义

| 按键 | 用途 | 可自定义 | 默认绑定 |
|------|------|---------|---------|
| **常规跳过键** | P2: 长按退出可跳过的脚本 | ✅ 可在MC设置中更改 | `Esc` 长按 0.5 秒 |
| **强制退出键** | P5: 无条件退出 | ❌ 硬编码 | `Ctrl+P` |

### 7.2 CinematicKeyBindings 类

```java
package com.immersivecinematics.immersive_cinematics.control;

public class CinematicKeyBindings {

    // 常规跳过键 — 用户可在MC设置中自定义
    public static final KeyMapping SKIP_KEY = new KeyMapping(
        "key.immersive_cinematics.skip",
        GLFW.GLFW_KEY_ESCAPE,
        "key.categories.immersive_cinematics"
    );

    // 长按阈值（毫秒）
    private static final long SKIP_HOLD_THRESHOLD_MS = 500;
    private static long skipKeyDownSince = 0;

    /** 注册按键绑定 — 在 FMLClientSetupEvent 中调用 */
    public static void register() {
        ClientRegistry.registerKeyBinding(SKIP_KEY);
    }

    /** 每客户端 tick 检查 — 在 ClientTickEvent 中调用 */
    public static void onClientTick() {
        CameraManager mgr = CameraManager.INSTANCE;
        if (!mgr.isActive()) {
            skipKeyDownSince = 0;
            return;
        }

        // P2: 常规跳过 — 长按检测
        if (SKIP_KEY.isDown()) {
            if (skipKeyDownSince == 0) {
                skipKeyDownSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - skipKeyDownSince >= SKIP_HOLD_THRESHOLD_MS) {
                boolean ok = mgr.requestExit(ExitReason.USER_SKIP);
                if (ok) {
                    skipKeyDownSince = 0;
                }
            }
        } else {
            skipKeyDownSince = 0;
        }

        // P5: 强制退出 — Ctrl+P 组合键
        long window = Minecraft.getInstance().getWindow().getWindow();
        boolean ctrlDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                        || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean pDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_P);
        if (ctrlDown && pDown) {
            mgr.requestExit(ExitReason.FORCE_QUIT);
        }
    }
}
```

### 7.3 长按进度提示（可选增强）

用户长按跳过键时，在屏幕上显示圆形进度条（类似 GTA5 跳过过场动画）：

```
长按 Esc ──────▶ [⏳ 0.5s] ──────▶ 跳过！
                  ↑ 进度条可视化
```

### 7.4 skippable=false 时的反馈

- 短按 Esc：屏幕边缘闪红 + 显示"此过场不可跳过"
- 长按 Esc：进度条变红 + 显示"此过场不可跳过"

---

## 八、CameraManager 重构

### 8.1 需要修改的方法

| 方法 | 当前行为 | 重构后行为 |
|------|---------|----------|
| `playScript()` | 直接检查 `interruptible`，然后 `deactivateNow()` | 调用 `requestExit(INTERRUPTED)`，成功后启动新脚本 |
| `stopScript()` | 直接 `scriptPlayer.stop()` + `deactivateNow()` | 调用 `requestExit(SYSTEM_STOP)` |
| `onRenderFrame()` 自然结束 | 直接 `deactivateNow()` | 调用 `requestExit(NATURAL_END)` |

### 8.2 playScript() 重构

```java
public boolean playScript(CinematicScript script) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.level == null || mc.player == null) return false;

    // 脚本间抢占控制：通过统一退出入口
    if (active && scriptPlayer.isPlaying()) {
        boolean canInterrupt = requestExit(ExitReason.INTERRUPTED);
        if (!canInterrupt) {
            return false;  // 当前脚本不可打断
        }
        // 抢占成功，等待 fade-out 完成后启动新脚本
        pendingScript = script;
        return true;
    }

    // 无当前脚本，直接启动
    startScriptInternal(script);
    return true;
}
```

### 8.3 fade-out 期间的新脚本排队

```java
// CameraManager 新增字段
private CinematicScript pendingScript = null;

// deactivateNow() 中检查排队脚本
private void deactivateNow() {
    active = false;
    stopping = false;
    gameTimeSeconds = 0;
    lastRealNanos = 0;
    scriptPlayer.stop();
    CinematicController.INSTANCE.revert();  // ← 统一恢复
    reset();
    OverlayManager.INSTANCE.reset();

    // 检查是否有排队的新脚本
    if (pendingScript != null) {
        CinematicScript next = pendingScript;
        pendingScript = null;
        startScriptInternal(next);
    }
}
```

### 8.4 startScriptInternal() — 提取公共初始化

```java
/**
 * 内部启动脚本 — 统一初始化逻辑
 * <p>
 * 提取自 activate() 和 playScript() 的重复代码
 */
private void startScriptInternal(CinematicScript script) {
    Minecraft mc = Minecraft.getInstance();
    Vec3 playerPos = mc.player.position();

    activePath.setPositionDirect(playerPos);
    activeProperties.setYawDirect(mc.player.getYRot());
    activeProperties.setPitchDirect(mc.player.getXRot());

    stagedReady = false;
    active = true;
    stopping = false;

    // 启动脚本播放器
    scriptPlayer.start(script);

    // 统一配置所有控制子系统
    CinematicController.INSTANCE.apply(scriptPlayer.getCurrentProperties());
    pauseWhenGamePaused = CinematicController.INSTANCE.isPauseWhenGamePaused();
}
```

---

## 九、属性修正

### 9.1 删除 `blockParticles`

**原因**：粒子系统是全局的，服务端创建粒子、客户端只负责渲染。粒子池是共享的，无法区分"哪些粒子应该屏蔽"。Forge 也没有提供按 overlay 级别控制粒子渲染的接口。

**变更**：
- 从 `ScriptMeta.RuntimeBehavior` record 中删除 `blockParticles` 字段
- 从 `ScriptProperties` 中删除 `blockParticles` 字段和 getter
- 从 `ScriptParser` 中删除 `block_particles` 解析
- 从 `ScriptMeta.Builder` 中删除 `blockParticles()` 方法
- JSON 中 `block_particles` 字段变为忽略项（向后兼容）

### 9.2 `blockMobAi` 标记为待实现

保留定义，但添加 `@Deprecated /* 需要C/S架构，当前纯客户端无法实现 */` 注解。

### 9.3 `pauseWhenGamePaused` 去重

删除 `CameraManager` 中的 `pauseWhenGamePaused` 字段，统一从 `CinematicController` 读取：

```java
// 当前
private boolean pauseWhenGamePaused = true;
// ...
if (Minecraft.getInstance().isPaused() && pauseWhenGamePaused) { ... }

// 修复后
if (Minecraft.getInstance().isPaused() && CinematicController.INSTANCE.isPauseWhenGamePaused()) { ... }
```

### 9.4 修正后的有效属性清单（15→14）

| 属性 | 归属子系统 | 修正 |
|------|----------|------|
| `blockKeyboard` | InputManager | ✅ 新增 KeyboardHandler Mixin |
| `blockMouse` | InputManager | ✅ 新增 MouseHandler Mixin |
| `hideHud` | HudManager | ✅ 重构为动态白名单 |
| `blockChat` | HudManager | ✅ 动态白名单项 |
| `blockScoreboard` | HudManager | ✅ 动态白名单项 |
| `blockActionBar` | HudManager | ✅ 动态白名单项 |
| `hideArm` | VisualManager | ✅ 改从 CinematicController 读取 |
| `suppressBob` | VisualManager | ✅ 改从 CinematicController 读取 |
| `renderPlayerModel` | VisualManager | ✅ 修复 CameraMixin |
| `skippable` | ExitManager | ✅ exit_control_system.md 已设计 |
| `interruptible` | ExitManager | ✅ 移入 requestExit() |
| `holdAtEnd` | ExitManager | ✅ 移入 requestExit() |
| `pauseWhenGamePaused` | CinematicController | ✅ 去重，单一数据源 |
| `blockMobAi` | —（待实现） | ⏳ 保留定义，需 C/S 架构 |
| ~~`blockParticles`~~ | — | 🗑️ 删除 |

---

## 十、代码变更清单

### 10.1 新增文件

| 文件 | 内容 |
|------|------|
| `control/CinematicController.java` | 统一调控器 — apply/revert + 所有子系统状态 |
| `control/ExitReason.java` | 退出原因枚举 |
| `control/CompletionReason.java` | 完成原因枚举 |
| `control/CinematicKeyBindings.java` | 按键绑定 + 长按检测 + 强制退出组合键 |
| `mixin/KeyboardHandlerMixin.java` | 键盘输入拦截 |
| `mixin/MouseHandlerMixin.java` | 鼠标输入拦截 |

### 10.2 修改文件

| 文件 | 变更内容 |
|------|---------|
| `CameraManager.java` | 新增 `requestExit()` + `pendingScript` + `startScriptInternal()`；`playScript()`/`stopScript()`/`onRenderFrame()` 改用 `requestExit()`；`deactivateNow()` 调用 `CinematicController.revert()`；删除 `pauseWhenGamePaused` 字段 |
| `ScriptPlayer.java` | `stop()` 接收 `CompletionReason` 参数；删除 `currentProperties` 冗余引用 |
| `ScriptProperties.java` | 删除 `blockParticles` 字段和 getter |
| `ScriptMeta.java` | 删除 `RuntimeBehavior.blockParticles`；删除 `isBlockParticles()` |
| `ScriptParser.java` | 删除 `block_particles` 解析 |
| `Immersive_cinematics.java` | 注册 KeyMapping + 注册按键 tick 事件 |
| `CinematicCommand.java` | `stopScript` 改用 `requestExit(SYSTEM_STOP)` |
| `HudOverlayHandler.java` | 重构为动态白名单 |
| `GameRendererMixin.java` | 改从 `CinematicController` 读取 |
| `CameraMixin.java` | `onIsDetached()` 改从 `CinematicController` 读取 |
| `immersive_cinematics.mixins.json` | 添加 `KeyboardHandlerMixin` 和 `MouseHandlerMixin` |

### 10.3 不变的文件

| 文件 | 原因 |
|------|------|
| `OverlayManager.java` | 覆盖层管理器不受影响 |
| `LetterboxLayer.java` | 黑边层不受影响 |
| `CinematicOverlay.java` | Forge 注册入口不受影响 |
| `CameraProperties.java` | 相机属性不受影响 |
| `CameraPath.java` | 相机路径不受影响 |

---

## 十一、实施步骤

1. 新增 `CinematicController` 类（统一调控器）
2. 新增 `ExitReason` 和 `CompletionReason` 枚举
3. 在 `CameraManager` 中实现 `requestExit()` 统一退出入口
4. 重构 `playScript()`/`stopScript()`/`onRenderFrame()` 使用 `requestExit()`
5. 新增 `CinematicKeyBindings` 类，实现按键绑定和长按检测
6. 在 `Immersive_cinematics` 中注册 KeyMapping 和 tick 事件
7. 新增 `KeyboardHandlerMixin` 和 `MouseHandlerMixin`
8. 重构 `HudOverlayHandler` 为动态白名单
9. 修改 `GameRendererMixin` 和 `CameraMixin` 从 `CinematicController` 读取
10. 删除 `blockParticles`（ScriptProperties/ScriptMeta/ScriptParser）
11. 删除 `CameraManager.pauseWhenGamePaused` 字段，改从 Controller 读取
12. 实现 `pendingScript` 排队机制
13. 修改 `CinematicCommand.stopScript` 使用 `requestExit(SYSTEM_STOP)`
14. 更新 `immersive_cinematics.mixins.json`
15. 测试三种典型场景（固定机位/监控视角/强制过场）

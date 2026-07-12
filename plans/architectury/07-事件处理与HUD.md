# 阶段 7：事件处理与 HUD

## 目标

迁移服务器/客户端事件处理器、HUD 渲染系统和 ConfigScreen。这些模块负责连接前面迁移的各模块，使功能真正可运行。

## 涉及文件

### 旧文件 (old/src/main/java/)

| 文件 | Forge 依赖 | 迁移目标 | 说明 |
|------|-----------|---------|------|
| `handler/HudOverlayHandler.java` | `RenderGuiOverlayEvent` | common/ + platform | 改造 |
| `control/SkipHudRenderer.java` | `RenderGuiOverlayEvent` | common/ | 改造 |
| `client/ConfigScreen.java` | 无 | common/ | 保留 |
| `client/ClientModEvents.java` | `FMLClientSetupEvent`, `ConfigScreenHandler` | forge/ | 仅 Forge |
| `client/EditorBridgeImpl.java` | 无 | common/ | 直接迁移 |

### 主类中的事件处理（old ImmersiveCinematics.java）

旧主类中分散了大量事件处理代码，需要提取到独立的 `ServerEventHandler` 和 `ClientEventHandler` 中：

**服务端事件**（旧 `ServerForgeEvents` 内部类）：
| 事件 | 对应 Architectury API |
|------|----------------------|
| `ServerStartedEvent` | `LifecycleEvent.SERVER_STARTED` |
| `ServerStoppingEvent` | `LifecycleEvent.SERVER_STOPPING` |
| `PlayerLoggedInEvent` | `PlayerEvent.PLAYER_JOIN` |
| `PlayerLoggedOutEvent` | `PlayerEvent.PLAYER_QUIT` |
| `TickEvent.ServerTickEvent` | `TickEvent.SERVER_POST` |
| `AdvancementEvent.AdvancementEarnEvent` | 需检查 |
| `LivingDeathEvent` | `EntityEvent.LIVING_DEATH` |
| 各类 `PlayerInteractEvent` | `InteractionEvent` 系列 |
| `PlayerEvent.ItemCraftedEvent` | `PlayerEvent.CRAFT_ITEM` |
| `LivingEntityUseItemEvent.Finish` | 需 Mixin 或事件 |
| `PlayerChangedDimensionEvent` | `PlayerEvent.CHANGE_DIMENSION` |
| `PlayerEvent.SaveToFile` | 需检查 |

**客户端事件**（旧 `ClientTickEvents`、`ClientHudEvents`、`ClientCameraEvents`）：
| 事件 | 对应 Architectury API |
|------|----------------------|
| `TickEvent.ClientTickEvent` | `ClientTickEvent.CLIENT_POST` |
| `RenderGuiOverlayEvent.Pre` | `ClientGuiEvent.RENDER_HUD` |
| `ViewportEvent.ComputeCameraAngles` | Mixin（已在阶段 4 处理） |

## 迁移原则

1. **有 Architectury 等效的事件** → 在 `common/` 中用 `Event.register()` 注册
2. **无 Architectury 等效的事件**（如 `LivingEntityUseItemEvent.Finish`、`PlayerEvent.SaveToFile`）→ 参考 FTB-Quests 模式，在 `forge/` 模块中直接用 `MinecraftForge.EVENT_BUS` 注册，Fabric 端用 Mixin 等效替代
3. **客户端初始化** → 用 `EnvExecutor.runInEnv(Env.CLIENT, ...)` 在 common 中安全执行客户端专用注册（参考 `FTBQuests.java`）

## 迁移步骤

### Step 1: 创建通用事件处理类

在 `common/` 下创建：

```java
// common/ - handler/ServerEventHandler.java
public class ServerEventHandler {
    public static void register() {
        // 服务器生命周期
        LifecycleEvent.SERVER_STARTED.register(server -> {
            ScriptManager.INSTANCE.copyGlobalToWorld(server);
            ScriptManager.INSTANCE.loadAll(server);
            TriggerStateStore.INSTANCE.initialize(server);
            TriggerEngine.INSTANCE.initialize();
            ScriptManager.INSTANCE.registerAllTriggers();
        });

        LifecycleEvent.SERVER_STOPPING.register(server -> {
            TriggerStateStore.INSTANCE.saveAll();
        });

        // 玩家事件
        PlayerEvent.PLAYER_JOIN.register(player -> {
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            TriggerStateStore.INSTANCE.loadForPlayer(serverPlayer.getUUID());
            TriggerEngine.INSTANCE.onLogin(serverPlayer);
        });

        PlayerEvent.PLAYER_QUIT.register(player -> {
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            UUID uuid = serverPlayer.getUUID();
            TriggerStateStore.INSTANCE.unloadForPlayer(uuid);
            clearTrackers(uuid);
        });

        // 服务器 Tick
        TickEvent.SERVER_POST.register(server -> {
            TriggerEngine.INSTANCE.onServerTick(server);
            ScriptEventManager.INSTANCE.onServerTick(server);
        });

        // 更多事件...
    }
}
```

```java
// common/ - handler/ClientEventHandler.java
public class ClientEventHandler {
    public static void register() {
        // 客户端 Tick
        ClientTickEvent.CLIENT_POST.register(mc -> {
            CameraManager.INSTANCE.tick();
            CinematicKeyBindings.onClientTick();
        });

        // 命令注册
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            CinematicCommand.register(dispatcher);
        });
    }
}
```

### Step 2: HudOverlayHandler 改造

**当前状态**：使用 Forge `RenderGuiOverlayEvent.Pre` 事件取消特定 GUI 元素。

**改造方案**：使用 Architectury `ClientGuiEvent.RENDER_HUD`，在渲染前手动控制哪些元素显示。

```java
// common/ - handler/HudOverlayHandler.java
public class HudOverlayHandler {
    // 渲染 ID 列表
    // ... (与旧代码相同的 ID 集合)

    public static void onRenderHud(GuiGraphics graphics, DeltaTracker delta) {
        if (!CameraManager.INSTANCE.isActive()) return;

        CinematicController ctrl = CinematicController.INSTANCE;
        // 注意：ClientGuiEvent.RENDER_HUD 只提供在 HUD 上额外绘制的能力
        // 无法像 Forge 那样取消特定原版 HUD 元素
        // 因此需要用 Mixin 或 RenderSystem 方式替换
    }
}
```

> **注意**：`ClientGuiEvent.RENDER_HUD` 只能追加绘制，无法取消原版 HUD 元素。
> 原版 HUD 元素的显隐控制需要使用 **Mixin** 或在 `Gui` 渲染中注入。
>
> **替代方案**：在 `Gui.render()` 方法中注入 Mixin，控制各 HUD 组件的渲染条件。
> 在旧代码中，Forge 的 `RenderGuiOverlayEvent.Pre` 本质上也提供了取消特定 overlay 的能力。
>
> 更实际的方案是 MIXIN-Gui 类来控制各元素显隐。

### Step 3: SkipHudRenderer 改造

**当前状态**：使用 Forge `RenderGuiOverlayEvent.Post` 事件渲染跳过进度 HUD。

**改造方案**：使用 Architectury `ClientGuiEvent.RENDER_HUD` 注册渲染：

```java
// common/ - control/SkipHudRenderer.java
// 保留全部渲染逻辑，移除 @SubscribeEvent 注解
public class SkipHudRenderer {
    public static void render(GuiGraphics guiGraphics, DeltaTracker delta) {
        if (!Config.showSkipHud) return;
        // ... (保留全部渲染逻辑)
    }
}

// 注册位置：
// ClientGuiEvent.RENDER_HUD.register(SkipHudRenderer::render);
```

### Step 4: ConfigScreen 迁移

`ConfigScreen.java` 是纯 Vanilla Screen 类，直接复制到 `common/`。

配置屏幕的注册方式：
- **Fabric**：通过 Fabric API 的 `ModMenuIntegration`（需 Cloth Config 或 ModMenu API）
- **Forge**：通过 `ConfigScreenHandler.ConfigScreenFactory`

由于两个平台注册方式不同，ConfigScreen 本身在 common/，注册代码在各自的平台模块：

```java
// forge/ - 注册 ConfigScreen
ModLoadingContext.get().registerExtensionPoint(
    ConfigScreenHandler.ConfigScreenFactory.class,
    () -> new ConfigScreenHandler.ConfigScreenFactory(
        (mc, parent) -> new ConfigScreen(parent)));
```

```java
// fabric/ - 注册 ConfigScreen（需 ModMenu 兼容）
// 方式1：实现 ModMenuApi 接口
// 方式2：如果不用 ModMenu，暂时不注册
```

### Step 5: EditorBridgeImpl 迁移

`EditorBridgeImpl.java` 是纯 Vanilla 类，直接复制到 `common/`。

### Step 6: 服务端事件注册整合

在 `common/ImmersiveCinematics.java.init()` 中调用：

```java
public static void init() {
    // 配置
    Config.init(createConfigProvider());
    
    // 网络（必须早于触发器初始化）
    NetworkHandler.init();  // SimpleNetworkManager static 字段加载
    
    // 触发器类型注册
    TriggerRegistration.registerAll();
    
    // 服务端事件
    ServerEventHandler.register();
}
```

### Step 7: 客户端事件注册整合

使用 `EnvExecutor.runInEnv(Env.CLIENT, ...)` 在 common 代码中安全执行客户端注册（参考 FTB-Quests 模式）：

```java
// common/ImmersiveCinematics.java - init() 末尾
public static void init() {
    // ... 服务端注册 ...

    // 仅客户端注册
    EnvExecutor.runInEnv(Env.CLIENT, () -> ClientEventHandler::register);
}
```

```java
// common/ - handler/ClientEventHandler.java
public class ClientEventHandler {
    public static void register() {
        // 按键注册
        KeyMappingRegistry.register(CinematicKeyBindings.SKIP_KEY);
        if (ImmersiveCinematics.EDITOR_ENABLED && CinematicKeyBindings.EDITOR_KEY != null) {
            KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_KEY);
        }

        // 客户端 tick
        ClientTickEvent.CLIENT_POST.register(mc -> {
            CameraManager.INSTANCE.tick();
            CinematicKeyBindings.onClientTick();
        });

        // HUD 渲染 - 追加绘制（隐藏元素用 Mixin）
        ClientGuiEvent.RENDER_HUD.register(SkipHudRenderer::render);
        ClientGuiEvent.RENDER_HUD.register((graphics, delta) ->
            CinematicOverlay.render(graphics,
                Minecraft.getInstance().getWindow().getGuiScaledWidth(),
                Minecraft.getInstance().getWindow().getGuiScaledHeight()));
    }
}
```

```java
// fabric/ - ImmersiveCinematicsFabricClient.onInitializeClient()
public void onInitializeClient() {
    // fabric 端客户端额外注册（如果有）
    // 核心注册已在 common 的 ClientEventHandler 中完成
}

// forge/ - forge 端在客户端设置阶段
// 核心注册已在 common 的 ClientEventHandler 中完成
// Forge 特有的事件直接在 Forge 入口点注册（参考 FTBQuestsForge.java）
```

## 编译与测试

### 测试清单
- [ ] Fabric 客户端启动正常
- [ ] Forge 客户端启动正常
- [ ] ConfigScreen 可通过 Mod 菜单打开
- [ ] 服务器启动时自动加载脚本
- [ ] 玩家登录/登出时触发器状态正确保存
- [ ] 客户端 Tick 事件触发 CameraManager.tick()
- [ ] 电影播放时 HUD 正确隐藏
- [ ] 跳过提示 HUD 正确渲染
- [ ] 电影模式下键盘/鼠标输入被屏蔽
- [ ] 命令 `/immersive_cinematics` 可注册

### 注意

- 这个阶段是将前面的模块串联起来的关键阶段
- HUD 控制（隐藏部分 HUD 元素）可能需要 Mixin 辅助，因为 Architectury 的 `ClientGuiEvent.RENDER_HUD` 只能追加不能隐藏
- 如果 Mixin 辅助需要额外时间，可以分两步：先完成事件注册，再完成 HUD 精确控制

### 如果 Mixin 需要扩展

如果需要控制原版 HUD 元素显隐，在 `Gui` 类添加 Mixin：

```java
// mixin/GuiMixin.java
@Mixin(Gui.class)
public class GuiMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRender(CallbackInfo ci) {
        // 检查是否活跃并决定是否取消渲染
    }
}
```

这需要在 `mixins.json` 中添加该 Mixin 并重新编译。

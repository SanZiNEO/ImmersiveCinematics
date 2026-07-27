# ImmersiveCinematics Architectury 迁移 — 执行计划

## 总体策略

- **重排序**：阶段 3（脚本系统）移到阶段 6 之后，避免临时桩
- **纯 Vanilla 文件直接搬**，含 Forge 导入的文件逐一改造
- **每次迁移后编译验证**，确保可运行
- **需要看 MC 源码时**，用 jd-mcp-duo 反编译定位

---

## 阶段 1 — 配置与基础（8 个文件）

### 1.1 Config 跨平台化（核心）

**旧文件**：`old/.../Config.java`（ForgeConfigSpec 依赖）

**步骤**：

**1.1.1** 在 `common/.../` 创建 `Config.java`：
- 保留全部静态字段（`skipHoldThresholdMs`、`showSkipHud` 等 9 个字段）
- 删除 `ForgeConfigSpec`、`@Mod.EventBusSubscriber`、`@SubscribeEvent ModConfigEvent`
- 添加 `ConfigProvider` 内部接口：`load()` / `get(ConfigKey<T>)` / `set(ConfigKey<T>, T)`
- 添加 `ConfigKey<T>` 记录类（含 key 名、默认值、范围、注释）
- 添加 `init(ConfigProvider)` — 遍历所有 key 从 provider 加载
- 保留 `setSkipHoldThresholdMs(int)` 等 setter（ConfigScreen 用）
- 删除 `SPEC`、`BUILDER` 和所有 `ForgeConfigSpec.*Value` 字段

**1.1.2** 在 `forge/.../` 创建 `ForgeConfig.java`：
- 实现 `Config.ConfigProvider`
- 内部保留 `ForgeConfigSpec.Builder` + `ForgeConfigSpec`
- 平台特有：`ModLoadingContext.get().registerConfig()` 在构造函数中注册
- 添加 `init()` 方法，在 `ImmersiveCinematicsForge` 构造函数中调用

**1.1.3** 在 `fabric/.../` 创建 `FabricConfig.java`：
- 实现 `Config.ConfigProvider`
- 用 `Properties` + JSON 文件存储到 `config/immersive_cinematics.properties`
- 文件不存在时自动创建带默认值的文件

### 1.2 主类重构

**1.2.1** 更新 `common/.../ImmersiveCinematics.java`：
- 添加 `EDITOR_ENABLED = true` 常量
- `init()` 中添加 `Config.init(provider)` — provider 由平台传入
- 预留 `EnvExecutor.runInEnv(Env.CLIENT, () -> { })` 骨架

**1.2.2** 更新 `forge/.../ImmersiveCinematicsForge.java`：
- 构造函数中 `ForgeConfig.init()`
- 将 config provider 传入 `ImmersiveCinematics.init()`

**1.2.3** 更新 `fabric/.../ImmersiveCinematicsFabric.java`：
- `onInitialize()` 中 `FabricConfig.init()`
- 将 config provider 传入 `ImmersiveCinematics.init()`

### 1.3 资源文件迁移

**1.3.1** 复制语言文件：
- `old/.../lang/en_us.json` → `common/.../lang/en_us.json`

**1.3.2** 复制纹理：
- `old/.../textures/gui/skip_key.png` → `common/.../textures/gui/skip_key.png`

**1.3.3** 复制 `pack.mcmeta`：
- `old/.../pack.mcmeta` → `common/.../pack.mcmeta`
- 删除 `forge/src/main/resources/pack.mcmeta`（如果存在）

### 1.4 ConfigScreen 迁移

**1.4.1** 复制 `old/.../client/ConfigScreen.java` → `common/.../client/ConfigScreen.java`
- 纯 Vanilla Screen，直接迁移
- 保留所有 GUI 逻辑（slider、toggle、dropdown 等）

**1.4.2** ConfigScreen 平台注册：
- **forge**：在 `ImmersiveCinematicsForge` 中用 `ConfigScreenHandler.ConfigScreenFactory` 注册
- **fabric**：在 `ImmersiveCinematicsFabricClient` 中用 ModMenu API 注册（或暂时不注册，先保证可编译）

### 1.5 编译验证

```bash
./gradlew :common:compileJava :fabric:compileJava :forge:compileJava
```

**验证点**：
- [ ] common 编译通过，无 Forge 导入泄漏
- [ ] fabric 编译通过
- [ ] forge 编译通过
- [ ] Fabric 客户端启动无报错
- [ ] Forge 客户端启动无报错
- [ ] 配置文件自动生成
- [ ] ConfigScreen 可打开

---

## 阶段 2 — 相机与控制模块（11 个文件）

### 2.1 纯 Vanilla 文件迁移

直接复制 7 个文件到 `common/.../`：

| # | 旧路径 | 验证要点 |
|---|--------|---------|
| 1 | `camera/CameraManager.java` | 确认无 Forge 导入（文档说只有 getRoll()，事件处理在 old main class） |
| 2 | `camera/CameraPath.java` | 直接复制 |
| 3 | `camera/CameraProperties.java` | 直接复制 |
| 4 | `control/CinematicController.java` | 直接复制 |
| 5 | `control/CompletionReason.java` | 直接复制 |
| 6 | `control/ExitReason.java` | 直接复制 |
| 7 | `util/MathUtil.java` | 直接复制 |

**对 CameraManager.java 特别检查**：
- [ ] 是否引用了 `ViewportEvent.ComputeCameraAngles`？
- [ ] 是否引用了 `ServerLifecycleHooks`？
- 如果有，标记并在对应阶段处理

### 2.2 CinematicKeyBindings 改造

**旧文件**：`old/.../control/CinematicKeyBindings.java`
- 删掉 `RegisterKeyMappingsEvent` 导入
- 删掉 `register(RegisterKeyMappingsEvent)` 方法
- 保留所有 `KeyMapping` 定义和 `onClientTick()` 业务逻辑
- 对 `EditorScreen` 引用：用 `EDITOR_ENABLED` 条件保护（编译期条件）
- **注册方式**：在 `ClientEventHandler` 中用 `KeyMappingRegistry.register()`（阶段 7 实现）

**对 EditorScreen 引用的处理**：
```java
// onClientTick() 中对 EditorScreen 的引用
if (ImmersiveCinematics.EDITOR_ENABLED && Minecraft.getInstance().screen instanceof EditorScreen) {
    // ...
}
```
Works because `EditorScreen` 类在阶段 8 才存在。使用 `EDITOR_ENABLED` 做条件编译——如果阶段 2 编译失败（EditorScreen 不存在），用反射或 instanceof 绕过。
→ 实际措施：在 `onClientTick()` 中将 `EditorScreen` 引用用 `try-catch(NoClassDefFoundError)` 包裹，或用 `EDITOR_ENABLED` 常量做编译期条件判断。

### 2.3 编译验证

```bash
./gradlew :common:compileJava :fabric:compileJava :forge:compileJava
```

**验证点**：
- [ ] common 编译通过
- [ ] forge 编译通过
- [ ] fabric 编译通过
- [ ] 进游戏，按键绑定出现在 Controls 设置中（`C`=Skip, `F6`=Editor）
- [ ] 相机管理器单例可访问
- [ ] `Ctrl+P` 不崩溃

---

## 阶段 3 — Mixins + 覆盖层（原阶段 4）

### 3.1 5 个 Mixin 文件迁移

**旧文件** → `common/.../mixin/`：

| 文件 | 职责 |
|------|------|
| `CameraMixin.java` | 拦截相机位置/旋转 |
| `GameRendererMixin.java` | 拦截 FOV、手臂渲染、镜头摇晃 |
| `KeyboardHandlerMixin.java` | 电影模式屏蔽键盘输入 |
| `MouseHandlerMixin.java` | 电影模式屏蔽鼠标输入 |
| `LivingEntityMixin.java` | 电影模式禁用怪物攻击 |

均为纯 Mixin 代码，无平台依赖 → 直接复制。

### 3.2 更新 mixins.json

编辑 `common/src/main/resources/immersive_cinematics.mixins.json`：
- 添加 `"client"` 数组，包含 5 个 Mixin 类名
- 添加 `"overwrites": { "requireAnnotations": true }`

### 3.3 Overlay 纯 Vanilla 部分迁移

复制 3 个文件 → `common/.../overlay/`：

| 文件 | 说明 |
|------|------|
| `OverlayManager.java` | 覆盖层管理器 |
| `OverlayLayer.java` | 覆盖层基类 |
| `LetterboxLayer.java` | 黑边覆盖层实现 |

### 3.4 CinematicOverlay 改造

**旧文件**：`old/.../overlay/CinematicOverlay.java`

**改动**：
- 删除 `RegisterGuiOverlaysEvent`、`IGuiOverlay` 导入
- 删除 `onRegisterGuiOverlays()` 方法
- 将渲染逻辑抽出为 `render(GuiGraphics, int screenWidth, int screenHeight)` 静态方法
- 保留 `OVERLAY_ID` 常量和 `CINEMATIC_OVERLAY` 委托逻辑（改为直接调用）
- **注册方式**：在阶段 7 通过 `ClientGuiEvent.RENDER_HUD` 注册

### 3.5 Camera Roll 处理（GameRendererMixin 增强）

在 `GameRendererMixin` 中添加 Roll 处理：
- 旧 Forge 做法：`ViewportEvent.ComputeCameraAngles` → `event.setRoll()`
- 新做法：在 `renderLevel` 方法的 Mixin 中，在 PoseStack 上应用 Roll 旋转
- 需要看的 MC 源码（jd-mcp-duo）：`net.minecraft.client.renderer.GameRenderer.renderLevel()` 方法

**实现思路**：
```java
@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "net/minecraft/client/Camera.setup"))
private void onRenderLevel(...) {
    if (CameraManager.INSTANCE.isActive() && CameraManager.INSTANCE.hasActiveCameraClip()) {
        float roll = CameraManager.INSTANCE.getProperties().getRoll();
        // 在 PoseStack 上应用 roll 旋转
    }
}
```

### 3.6 编译验证

```bash
./gradlew :common:compileJava :fabric:compileJava :forge:compileJava
```

**验证点**：
- [ ] Fabric 客户端启动，mixin 应用无报错
- [ ] Forge 客户端启动，mixin 应用无报错
- [ ] 单人世界加载正常，视角可正常移动
- [ ] 键盘/鼠标输入正常
- [ ] Overlay 系统不崩溃（无电影播放时无副作用）

---

## 阶段 4 — 网络层（原阶段 5，7 个文件）

### 4.1 NetworkHandler 完全重写

**旧文件**：`old/.../trigger/network/NetworkHandler.java`（Forge SimpleChannel）

**新文件**：`common/.../trigger/network/NetworkHandler.java`

参考 FTB-Quests 的 `FTBQuestsNetHandler` 模式，用 `SimpleNetworkManager`：

```java
public interface NetworkHandler {
    SimpleNetworkManager NET = SimpleNetworkManager.create(ImmersiveCinematics.MOD_ID);

    MessageType PLAY_SCRIPT = NET.registerS2C("play_script", S2CPlayScriptPacket::new);
    MessageType STOP_SCRIPT = NET.registerS2C("stop_script", S2CStopScriptPacket::new);
    MessageType TRIGGER_STATE_SYNC = NET.registerS2C("trigger_state_sync", S2CTriggerStateSyncPacket::new);
    MessageType SKIP_VOTE_UPDATE = NET.registerS2C("skip_vote_update", S2CSkipVoteUpdatePacket::new);
    MessageType SCRIPT_FINISHED = NET.registerC2S("script_finished", C2SScriptFinishedPacket::new);
    MessageType PLAYBACK_STARTED = NET.registerC2S("playback_started", C2SPlaybackStartedPacket::new);

    static void init() { /* 触发 static 字段加载 */ }
}
```

### 4.2 6 个 Packet 类改造

每个包改造模式一致：

**S2C 包**（4 个）：
| 旧文件 | 新父类 | handle() 目标 |
|--------|--------|-------------|
| `S2CPlayScriptPacket` | `BaseS2CMessage` | `ClientScriptReceiver.handlePlayScript()` |
| `S2CStopScriptPacket` | `BaseS2CMessage` | `ClientScriptReceiver.handleStopScript()` |
| `S2CTriggerStateSyncPacket` | `BaseS2CMessage` | `ClientTriggerStateCache.handleSync()` |
| `S2CSkipVoteUpdatePacket` | `BaseS2CMessage` | `ClientScriptReceiver.handleSkipVoteUpdate()` |

**C2S 包**（2 个）：
| 旧文件 | 新父类 | handle() 目标 |
|--------|--------|-------------|
| `C2SScriptFinishedPacket` | `BaseC2SMessage` | `ScriptEventManager.onScriptFinished()` |
| `C2SPlaybackStartedPacket` | `BaseC2SMessage` | `ScriptEventManager.startPlayback()` |

每个包改造步骤：
1. 改继承 `BaseS2CMessage` / `BaseC2SMessage`
2. 添加 `getType()` → 返回 `NetworkHandler` 中的 `MessageType`
3. 保留 `FriendlyByteBuf` 编码/解码逻辑（纯 Vanilla，只传 String/int）
4. 添加 `write(FriendlyByteBuf)` 方法
5. `handle(NetworkManager.PacketContext)` 替代旧 Forge `handle(Supplier<NetworkEvent.Context>)`
6. 删除 `NetworkEvent.Context`、`DistExecutor`、`NetworkDirection` 等 Forge 导入

### 4.3 编译验证

```bash
./gradlew :common:compileJava
```

**验证点**：
- [ ] common 编译通过
- [ ] 6 个 MessageType 正常注册
- [ ] 双向通信可工作（需后续阶段连入事件触发）

---

## 阶段 5 — 触发器系统（原阶段 6，18 个文件）

### 5.1 纯 Vanilla 文件迁移（12 个）

直接复制以下文件到 `common/.../trigger/`：

| 目录 | 文件 |
|------|------|
| `server/` | `TriggerRegistry.java`、`TriggerRegistration.java`、`ListenStrategy.java` |
| `server/action/` | `TriggerAction.java`、`StartPlaybackAction.java`、`StopPlaybackAction.java`、`ExecuteCommandAction.java` |
| `server/evaluator/` | `Evaluators.java` |
| `server/store/` | `PlayerTriggerState.java`、`TriggerStateStore.java` |
| `client/` | `ClientScriptCache.java`、`ClientScriptNotifier.java`、`ClientScriptReceiver.java`、`ClientTriggerStateCache.java` |

注意：`Evaluators.java` 引用了多个 tracker（`KillTracker`、`InteractTracker` 等），它们不依赖 Forge，直接迁移即可。

### 5.2 PlaySoundAction 改造

**旧文件**：`old/.../trigger/server/action/PlaySoundAction.java`

**改动**：
- `ForgeRegistries.SOUND_EVENTS.getValue(soundId)` → `BuiltInRegistries.SOUND_EVENT.get(soundId)`

### 5.3 TriggerType 改造

**旧文件**：`old/.../trigger/server/TriggerType.java`

**改动**：
- 删除 `net.minecraftforge.eventbus.api.Event` 导入
- 删除 `Set<Class<? extends Event>> listenedEvents` 字段和相关构造参数
- `getListenedEvents()` 方法改为返回 `Set<String>`（事件类型字符串标识），或完全移除

**新设计**：
```java
public class TriggerType {
    private final String id;
    private final ListenStrategy strategy;
    private final int pollInterval;
    private final BiPredicate<ServerPlayer, JsonObject> evaluator;
    // 不再持有 Forge Event 类引用
}
```

### 5.4 TriggerEngine 改造（核心）

**旧文件**：`old/.../trigger/server/TriggerEngine.java`

**改动**：
- 删除 `net.minecraftforge.eventbus.api.Event` 导入
- 删除 `Map<Class<? extends Event>, List<TriggerRegistration>> eventIndex` — 不再用 Event 类索引
- 改为：`Map<String, List<TriggerRegistration>> eventIndex` — 用事件类型字符串索引
- `onGameEvent(Event, ServerPlayer)` → `onGameEvent(String eventType, ServerPlayer player)`
- 删除 `findSuperclassMatch(Class<T>)` — Forge Event 类层次匹配不再需要
- 保留所有轮询逻辑（`onServerTick`）、延迟触发、enter state 检查

### 5.5 ScriptEventManager 改造

**旧文件**：`old/.../trigger/server/ScriptEventManager.java`

**改动**：
- 删除 `ServerLifecycleHooks.getCurrentServer()` 引用
- 改为通过方法参数传入 `MinecraftServer server` 引用
- 在 `onServerStarted(MinecraftServer)` 时保存 server 引用

### 5.6 TriggerRegistration 重写

**旧文件**：`old/.../trigger/server/TriggerRegistration.java`

**改动**：
- 删除 `Set<Class<? extends Event>>`（Forge Event 类引用）
- `TriggerType` 构造签名变更反映（去掉事件类集合参数）

### 5.7 主类集成

在 `ImmersiveCinematics.init()` 中添加：
```java
TriggerRegistration.registerAll();
```

### 5.8 编译验证

```bash
./gradlew :common:compileJava
```

**验证点**：
- [ ] common 编译通过
- [ ] 16 种触发器类型全部注册
- [ ] TriggerEngine 初始化不报错

---

## 阶段 6 — 脚本系统（原阶段 3，27 个文件）

### 6.1 全部直接复制

`old/.../script/` 下 **27 个文件全部为纯 Vanilla**，直接复制到 `common/.../script/`：

```
script/ScriptManager.java
script/ScriptParser.java
script/ScriptMeta.java
script/CinematicScript.java
script/Timeline.java
script/TimelineTrack.java
script/TrackType.java
script/TrackPlayer.java
script/CameraClip.java
script/CameraKeyframe.java
script/CameraTrackPlayer.java
script/AudioClip.java
script/AudioTrackPlayer.java
script/LetterboxClip.java
script/LetterboxKeyframe.java
script/LetterboxTrackPlayer.java
script/EventClip.java
script/EventTrackPlayer.java
script/ModEventClip.java
script/ModEventTrackPlayer.java
script/PathStrategy.java
script/PathStrategies.java
script/BezierPathStrategy.java
script/BezierCurve.java
script/ArcLengthLUT.java
script/InterpolationType.java
script/KeyframeInterpolator.java
script/TransitionType.java
script/PositionData.java
script/TriggerDefinition.java
```

### 6.2 验证 ScriptManager 引用完整性

`ScriptManager.java` 引用了：
- `CameraManager` ✅ 阶段 2 已就绪
- `CinematicController` ✅ 阶段 2 已就绪
- `TriggerEngine.INSTANCE` ✅ 阶段 5 已就绪（不再需要桩）
- `ScriptEventManager.INSTANCE` ✅ 阶段 5 已就绪
- `TriggerStateStore.INSTANCE` ✅ 阶段 5 已就绪
- `NetworkHandler` ✅ 阶段 4 已就绪

**零桩，零修改**。

### 6.3 编译验证

```bash
./gradlew :common:compileJava
```

**验证点**：
- [ ] common 编译通过
- [ ] `ScriptParser` 可解析 `.cs` 脚本
- [ ] `ScriptManager` 单例可访问
- [ ] 所有 Clip/Track/Player 类可实例化

---

## 阶段 7 — 事件处理与 HUD（原阶段 7，8 个文件）

### 7.1 创建 ServerEventHandler

从旧 `ImmersiveCinematics.java` 的 `ServerForgeEvents` 内部类提取，用 Architectury 事件等价：

| 旧 Forge 事件 | Architectury 事件 |
|--------------|-----------------|
| `ServerStartedEvent` | `LifecycleEvent.SERVER_STARTED` |
| `ServerStoppingEvent` | `LifecycleEvent.SERVER_STOPPING` |
| `PlayerLoggedInEvent` | `PlayerEvent.PLAYER_JOIN` |
| `PlayerLoggedOutEvent` | `PlayerEvent.PLAYER_QUIT` |
| `TickEvent.ServerTickEvent` | `TickEvent.SERVER_POST` |
| `RegisterCommandsEvent` | `CommandRegistrationEvent.EVENT` |

**特殊事件处理**（无 Architectury 等效，需要 MC 源码研究）：

| 旧事件 | 替换方案 | MC 源码 |
|--------|---------|---------|
| `AdvancementEvent.AdvancementEarnEvent` | 检查 `PlayerEvent.ADVANCEMENT` 是否可用 | jd-mcp-duo 查 Architectury API |
| `LivingDeathEvent` | `EntityEvent.LIVING_DEATH` | ✅ |
| `PlayerInteractEvent.*` | `InteractionEvent.*` 系列 | ✅ |
| `PlayerEvent.ItemCraftedEvent` | `PlayerEvent.CRAFT_ITEM` | ✅ |
| `LivingEntityUseItemEvent.Finish` | **Mixin** `LivingEntity.completeUsingItem()` | 需 jd-mcp-duo 反编译 `LivingEntity` |
| `PlayerEvent.SaveToFile` | **Mixin** `PlayerList.save()` 或 Tick 末批量保存 | 需 jd-mcp-duo 反编译 `PlayerList` |
| `PlayerChangedDimensionEvent` | `PlayerEvent.CHANGE_DIMENSION` | ✅ |

### 7.2 创建 ClientEventHandler

从旧 `ClientTickEvents`、`ClientHudEvents`、`ClientCameraEvents` 内部类提取：

| 旧事件 | Architectury 事件 |
|--------|-----------------|
| `TickEvent.ClientTickEvent` | `ClientTickEvent.CLIENT_POST` |
| `RenderGuiOverlayEvent.Pre` | **Mixin** Gui.render()（不做，见 7.3） |
| 相机 Roll | ✅ 已在阶段 3 通过 Mixin 处理 |

### 7.3 HudOverlayHandler 改造

**旧文件**：`old/.../handler/HudOverlayHandler.java`
- Forge `RenderGuiOverlayEvent.Pre` → **Mixin 注入 `Gui.render()`**

这里需要用 jd-mcp-duo 看 MC 源码确定具体的 HUD 元素渲染方法，在 `Gui` 类的每个 HUD 元素渲染前注入判断。

**需要看的 MC 源码**（jd-mcp-duo）：
- `net.minecraft.client.gui.Gui.render()` — 了解 HUD 元素渲染结构
- 找到 hotbar、crosshair、scoreboard、chat、action bar 等元素的渲染方法
- 在每个元素前注入 `if (HudOverlayHandler.shouldSkip("element_id")) return;`

### 7.4 SkipHudRenderer 改造

**旧文件**：`old/.../control/SkipHudRenderer.java`

**改动**：
- 删除 `RenderGuiOverlayEvent.Post` 注册
- 改为 `ClientGuiEvent.RENDER_HUD` 注册
- 渲染逻辑不动

### 7.5 EditorBridgeImpl 迁移

**旧文件**：`old/.../client/EditorBridgeImpl.java`
- 纯 Vanilla → 直接复制到 `common/.../client/`

### 7.6 事件注册整合

在 `ImmersiveCinematics.init()` 中添加：

```java
ServerEventHandler.register();
EnvExecutor.runInEnv(Env.CLIENT, () -> ClientEventHandler::register);
```

在 `ClientEventHandler.register()` 中添加：
```java
KeyMappingRegistry.register(CinematicKeyBindings.SKIP_KEY);
KeyMappingRegistry.register(CinematicKeyBindings.EDITOR_KEY); // if enabled
ClientGuiEvent.RENDER_HUD.register(SkipHudRenderer::render);
ClientGuiEvent.RENDER_HUD.register(CinematicOverlay::render);
```

### 7.7 编译验证

```bash
./gradlew :common:compileJava :fabric:compileJava :forge:compileJava
```

**验证点**：
- [ ] 服务器启动时自动加载脚本
- [ ] 玩家登录/登出时触发器状态正确保存
- [ ] 客户端 Tick 触发 CameraManager.tick()
- [ ] 电影播放时 HUD 正确隐藏
- [ ] 跳过提示 HUD 正确渲染
- [ ] 命令 `/immersive_cinematics` 可注册
- [ ] ConfigScreen 可通过 Mod 菜单打开

---

## 阶段 8 — 编辑器（31 个文件）

### 8.1 主编辑器文件迁移（11 个）

直接复制 `old/.../editor/` → `common/.../editor/`：

```
EditorScreen.java  EditorDocument.java  EditorOperations.java
EditorOutput.java  EditorPlayback.java  EditorSelection.java
EditorBridge.java  PreviewCapture.java  Scale.java
```

### 8.2 Area 文件迁移（4 个）

`old/.../editor/area/` → `common/.../editor/area/`：

```
LeftPanelArea.java  MenuBarArea.java  PreviewArea.java  TimelineArea.java
```

### 8.3 Trigger 编辑器迁移（8 个）

`old/.../editor/trigger/` → `common/.../editor/trigger/`：

```
TriggerEditor.java  TriggerPanel.java  EntityKillEditor.java
InventoryEditor.java  LocationEditor.java  NoConditionEditor.java
SingleIdEditor.java  StructureEditor.java
```

### 8.4 Widget 组件迁移（10 个）

`old/.../editor/widget/` → `common/.../editor/widget/`：

```
UIComponent.java  UIContext.java  IFocusable.java  UIButton.java
UILabel.java  UITextInput.java  UIFloatInput.java  UIToggle.java
UIDropdown.java  UIAutoCompleteInput.java
```

### 8.5 CinematicCommand 迁移

`old/.../command/CinematicCommand.java` → `common/.../command/CinematicCommand.java`
- 纯 Vanilla Brigadier 命令，直接复制
- 注册通过 `CommandRegistrationEvent.EVENT`（阶段 7 已处理）

### 8.6 临时桩处理

如果 `EditorScreen.java` 引用了 `EditorLogger` 或 `RawInputLogger`（阶段 9 才迁移）：
- 创建临时桩在 `common/.../editor/debug/EditorLogger.java`
- 空方法体，阶段 9 删除

### 8.7 编译验证

```bash
./gradlew :common:compileJava
```

**验证点**：
- [ ] 按 F6 打开编辑器
- [ ] 编辑器界面正常渲染
- [ ] 新建/打开/保存脚本
- [ ] 播放控制正常
- [ ] 时间线导航正常
- [ ] 关键帧编辑正常
- [ ] 所有 widget 组件正常交互

---

## 阶段 9 — 调试与收尾（6 个文件 + 清理）

### 9.1 EditorLogger 迁移

`old/.../editor/debug/EditorLogger.java` → `common/.../editor/debug/EditorLogger.java`
- 纯 Vanilla → 直接复制
- 删除阶段 8 可能创建的临时桩

### 9.2 RawInputLogger 改造

**旧文件**：`old/.../editor/debug/RawInputLogger.java`

**改动**：
- 删除 `@EventBusSubscriber`、`InputEvent.Key`、`InputEvent.MouseButton` 等 Forge 注解
- 方法签名改为静态方法
- 注册方式：
  ```java
  ClientRawInputEvent.KEY_PRESSED.register((key, scanCode, action, modifiers) -> {
      RawInputLogger.onKeyPress(key, scanCode, action, modifiers);
      return EventResult.pass();
  });
  ClientRawInputEvent.MOUSE_CLICKED.register((button, action, modifiers) -> {
      RawInputLogger.onMouseButton(button, action, modifiers);
      return EventResult.pass();
  });
  ```

### 9.3 最终资源确认

- [ ] `pack.mcmeta` 在 common/ 下
- [ ] 语言文件在 common/ 下
- [ ] 纹理文件在 common/ 下
- [ ] 删除 `forge/src/main/resources/pack.mcmeta`（使用 common 版本）

### 9.4 最终主类整合

`common/ImmersiveCinematics.java` 的 `init()` 应包含：
```java
public static void init(Config.ConfigProvider provider) {
    Config.init(provider);
    NetworkHandler.init();
    TriggerRegistration.registerAll();
    ServerEventHandler.register();
    EnvExecutor.runInEnv(Env.CLIENT, () -> ClientEventHandler::register);
}
```

### 9.5 清理

- [ ] 删除 `old/` 目录的编译引用（从 `build.gradle` 移除）
- [ ] 删除所有临时桩
- [ ] 运行 `./gradlew :fabric:build` 成功
- [ ] 运行 `./gradlew :forge:build` 成功

### 9.6 完整功能测试

参见原计划文档 `09-调试与收尾.md` 的完整测试清单。

---

## 需要 MC 源码反编译的点（jd-mcp-duo）

| 阶段 | 目标类 | 方法 | 目的 |
|------|-------|------|------|
| 3 | `GameRenderer` | `renderLevel()` | 找到 PoseStack 注入点做 Camera Roll |
| 7 | `Gui` | `render()` | 找到各 HUD 元素渲染方法，注入显隐控制 |
| 7 | `LivingEntity` | `completeUsingItem()` / `triggerItemUseFinish()` | 找 `item_consume` 事件的注入点 |
| 7 | `PlayerList` | `save()` | 找玩家数据持久化的注入点 |
| 7 | `InteractionEvent` (Architectury) | API 签名 | 确认 `RIGHT_CLICK_BLOCK` / `LEFT_CLICK_BLOCK` 是否可用 |
| 7 | `PlayerEvent` (Architectury) | `ADVANCEMENT` | 确认 advancement 事件的 API |

---

## 编译命令速查

```bash
# 每次迁移后验证
./gradlew :common:compileJava

# 全平台编译
./gradlew :fabric:compileJava :forge:compileJava

# 运行客户端
./gradlew :fabric:runClient
./gradlew :forge:runClient
```

---

## 当前进度

### ✅ 已完成

- **阶段 1** — 配置与基础（Config跨平台化 + 主类重构 + 资源迁移）
- **阶段 2** — 相机与控制模块

### 📌 当前临时桩（将在对应阶段替换）

| 桩文件 | 替换阶段 | 说明 |
|--------|---------|------|
| `overlay/OverlayManager.java` | 阶段 3 — Mixins+覆盖层 | CameraManager 引用 |
| `script/ScriptPlayer.java` | 阶段 6 — 脚本系统 | CameraManager 引用 |
| `script/CinematicScript.java` | 阶段 6 — 脚本系统 | CameraManager 引用 |
| `script/ScriptMeta.java` | 阶段 6 — 脚本系统 | CameraManager + CinematicController 引用 |
| `script/ScriptParser.java` | 阶段 6 — 脚本系统 | CameraManager 引用 |
| `trigger/client/ClientScriptNotifier.java` | 阶段 5 — 触发器系统 | CameraManager 引用 |
| `trigger/client/ClientScriptReceiver.java` | 阶段 5 — 触发器系统 | CameraManager 引用 |

### 待执行

| 阶段 | 模块 | 文件数 | 桩替换 |
|------|------|--------|--------|
| 3 | Mixins + Overlay | 11 | 替换 OverlayManager 桩 |
| 4 | 网络层 | 7 | — |
| 5 | 触发器系统 | 18 | 替换 ClientScriptNotifier/Receiver 桩 |
| 6 | 脚本系统 | 27 | 替换全部 script 桩（4 个） |
| 7 | 事件处理 + HUD | 8 | — |
| 8 | 编辑器 | 31 | — |
| 9 | 调试 + 收尾 | 6 | 清理所有桩 |

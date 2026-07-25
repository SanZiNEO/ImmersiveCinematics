# Architectury 项目 API & Mixin 使用清单 — Minecraft 1.20.1

> 本文档对应 **1.20.1** 版本。后续多版本迁移时，每个版本独立一份。  
> 记录该版本下使用的全部跨平台 API、加载器专有 API 和 Mixin。

---

## 一、Common 模块（`common/`）—— Architectury API

### 事件

| Architectury 事件类 | 事件字段 | 用途 |
|-------------------|---------|------|
| `dev.architectury.event.events.common.LifecycleEvent` | `SERVER_STARTED` | 加载脚本、初始化触发器引擎 |
| | `SERVER_STOPPING` | 保存触发器状态 |
| | `SERVER_LEVEL_SAVE` | 兜底保存触发器状态 |
| `dev.architectury.event.events.common.PlayerEvent` | `PLAYER_JOIN` | 加载玩家触发器状态、触发 login 触发器 |
| | `PLAYER_QUIT` | 卸载玩家状态、清理追踪器 |
| | `CRAFT_ITEM` | 触发 item_craft 触发器 |
| | `CHANGE_DIMENSION` | 触发 dimension_change 触发器 |
| `dev.architectury.event.events.common.TickEvent` | `SERVER_POST` | 触发器引擎 tick、脚本事件 tick |
| `dev.architectury.event.events.common.EntityEvent` | `LIVING_DEATH` | 触发 entity_kill 触发器 |
| `dev.architectury.event.events.common.InteractionEvent` | `RIGHT_CLICK_BLOCK` | 触发 block_interact 触发器 |
| | `LEFT_CLICK_BLOCK` | 触发 block_interact 触发器 |
| | `INTERACT_ENTITY` | 触发 entity_interact 触发器 |
| | `RIGHT_CLICK_ITEM` | 触发 item_use 触发器 |
| `dev.architectury.event.events.common.CommandRegistrationEvent` | `EVENT` | 注册 `/cinematic` 命令 |
| `dev.architectury.event.events.client.ClientTickEvent` | `CLIENT_POST` | 相机 tick、跳过键 tick |
| `dev.architectury.event.events.client.ClientGuiEvent` | `RENDER_HUD` | 跳过提示 HUD、黑边覆盖层渲染 |
| `dev.architectury.event.EventResult` | `pass()` | InteractionEvent 返回值 |
| `dev.architectury.event.CompoundEventResult` | `pass()` | `RIGHT_CLICK_ITEM` 返回值 |

### 网络

| 类 | 用途 |
|---|------|
| `dev.architectury.networking.simple.SimpleNetworkManager` | 声明 6 个 MessageType（4 S2C + 2 C2S） |
| `dev.architectury.networking.simple.MessageType` | 每个包的类型标识 |
| `dev.architectury.networking.simple.BaseS2CMessage` | 4 个 S2C 包继承（服务器→客户端） |
| `dev.architectury.networking.simple.BaseC2SMessage` | 2 个 C2S 包继承（客户端→服务器） |
| `dev.architectury.networking.NetworkManager.PacketContext` | 包处理回调中的上下文 |

具体包：
- `S2CPlayScriptPacket` — 服务器发送脚本 JSON 给客户端播放
- `S2CStopScriptPacket` — 服务器通知客户端停止脚本
- `S2CTriggerStateSyncPacket` — 同步触发器状态
- `S2CSkipVoteUpdatePacket` — 更新跳过投票计数
- `C2SScriptFinishedPacket` — 客户端通知服务器脚本播放完毕
- `C2SPlaybackStartedPacket` — 客户端通知服务器脚本开始播放

### 注册

| 类 | 用途 |
|---|------|
| `dev.architectury.registry.client.keymappings.KeyMappingRegistry` | 注册跳过键(C)和编辑器键(F6) |

### 其他

| 类 | 用途 |
|---|------|
| `dev.architectury.utils.Env` | 区分 CLIENT/SERVER 环境 |
| `dev.architectury.utils.EnvExecutor` | `runInEnv(Env.CLIENT, ...)` 安全执行客户端初始化 |

---

## 二、Forge 模块（`forge/`）—— Forge 专有 API

| Forge API | 用途 | 文件 |
|-----------|------|------|
| `net.minecraftforge.common.ForgeConfigSpec` | 类型安全的 TOML 配置定义 | `ForgeConfig.java` |
| `net.minecraftforge.fml.event.config.ModConfigEvent` | 配置加载时刷新 Config 静态字段 | `ForgeConfig.java` |
| `net.minecraftforge.fml.common.Mod` | `@Mod` 注解声明模组入口 | `ImmersiveCinematicsForge.java` |
| `net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext` | 获取 MOD 事件总线 | `ImmersiveCinematicsForge.java` |
| `net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent` | 客户端设置阶段注册 ConfigScreen | `ImmersiveCinematicsForge.java` |
| `net.minecraftforge.client.ConfigScreenHandler` | ConfigScreen 扩展点注册 | `ImmersiveCinematicsForge.java` |
| `net.minecraftforge.fml.ModLoadingContext` | 注册 ForgeConfigSpec、扩展点 | `ImmersiveCinematicsForge.java` |
| `net.minecraftforge.fml.config.ModConfig` | `ModConfig.Type.COMMON` 配置类型 | `ImmersiveCinematicsForge.java` |
| `dev.architectury.platform.forge.EventBuses` | `registerModEventBus()` 提交事件总线给 Architectury | `ImmersiveCinematicsForge.java` |

Forge 模块总共 **2 个 Java 文件**，只做入口和配置，无业务逻辑。

---

## 三、Fabric 模块（`fabric/`）—— Fabric 专有 API

| Fabric API | 用途 | 文件 |
|-----------|------|------|
| `net.fabricmc.api.ModInitializer` | 模组入口 | `ImmersiveCinematicsFabric.java` |
| `net.fabricmc.api.ClientModInitializer` | 客户端入口 | `ImmersiveCinematicsFabricClient.java` |
| `net.fabricmc.loader.api.FabricLoader` | 获取配置目录路径 | `FabricConfig.java` |

Fabric 模块总共 **3 个 Java 文件**，同样只做入口和配置，无业务逻辑。

`fabric.mod.json` 依赖声明：
- `fabricloader >= 0.19.3`
- `architectury >= 9.2.14`
- `fabric-api: *`

---

## 四、Mixin 清单

| Mixin 类 | 目标类 | 注入点 | 方向 | 用途 |
|----------|--------|--------|------|------|
| `KeyboardHandlerMixin` | `Keyboar dHandler` | `@Inject HEAD cancellable keyPress` | common | 电影模式键鼠拦截，两层路由(接收→传输) |
| `MouseHandlerMixin` | `MouseHandler` | `@Inject HEAD cancellable onPress/onScroll/turnPlayer` | common | 鼠标事件路由 |
| `GuiMixin` | `Gui` | `@Inject HEAD cancellable renderHotbar/renderCrosshair/renderPlayerHealth/renderVehicleHealth/renderExperienceBar/renderJumpMeter/renderSelectedItemName/displayScoreboardSidebar` | common | HUD 元素显隐控制 |
| `ChatComponentMixin` | `ChatComponent` | `@Inject HEAD cancellable render` | common | 聊天栏显隐 |
| `PlayerTabOverlayMixin` | `PlayerTabOverlay` | `@Inject HEAD cancellable render` | common | 玩家列表显隐 |
| `SubtitleOverlayMixin` | `SubtitleOverlay` | `@Inject HEAD cancellable render` | common | 字幕显隐 |
| `BossHealthOverlayMixin` | `BossHealthOverlay` | `@Inject HEAD cancellable render` | common | Boss 血条显隐 |
| `CameraMixin` | `Camera` | `@Inject HEAD cancellable setup/getEntity/isDetached` | common | 替换相机位置/旋转 |
| `GameRendererMixin` | `GameRenderer` | `@Inject RETURN cancellable getFov` | common | 覆盖 FOV |
| | | `@Inject HEAD cancellable renderItemInHand` | common | 隐藏手臂 |
| | | `@Inject HEAD cancellable bobHurt/bobView` | common | 屏蔽视角摇晃 |
| | | `@Redirect Mth.lerp ordinal=0 renderLevel` | common | 屏蔽反胃旋转 |
| | | `@Inject INVOKE LevelRenderer.prepareCullFrustum BEFORE renderLevel` | common | 相机 Roll 角 |
| `LivingEntityMixin` | `LivingEntity` | `@Inject HEAD cancellable canAttack` | common | 阻止生物攻击 |

### 注入方向说明
- `common/` 中的 mixin → 两个加载器都生效（全部 Mixin 都在 common）
- `fabric/` 或 `forge/` 中无 Mixin

### Mixin 统计
- 共 **10 个 Mixin 类**
- **22 个注入点**
- 全部在 `common/` 模块，双向兼容

---

## 五、跨平台设计模式

| 模式 | 说明 | 使用场景 |
|------|------|---------|
| `ConfigProvider` 接口 | common 定义接口，forge/fabric 各自实现 | 配置系统 |
| `SimpleNetworkManager` | Architectury 提供跨平台网络 | 所有网络包 |
| `@Inject Mixin` | 直接修改 Vanilla 类，不依赖平台事件 | 键鼠拦截、HUD 控制、相机覆盖、Roll |
| Architectury 事件 | 跨平台事件总线 | 服务器/客户端事件处理 |
| `EnvExecutor.runInEnv` | 安全执行客户端专用代码 | 客户端初始化 |

## 六、不通过 Architectury API 实现的功能

以下功能没有 Architectury 等效 API，使用 Mixin 或平台专有 API 实现：

| 功能 | 方式 | 原因 |
|------|------|------|
| 键鼠拦截 | Mixin `KeyboardHandler`/`MouseHandler` | 无跨平台输入拦截 API |
| HUD 元素显隐 | Mixin `Gui` 和各组件 `render()` | Architectury 只能追加绘制，不能取消 |
| 相机 Roll | Mixin `GameRenderer.renderLevel()` | `ViewportEvent.ComputeCameraAngles` 是 Forge 独占 |
| 物品消耗完成事件 | 暂未实现(需 Mixin `LivingEntity`) | `LivingEntityUseItemEvent.Finish` 是 Forge 独占 |
| 配置屏幕注册 | Forge `ConfigScreenHandler` / Fabric 未实现 | 注册方式不同 |

## 七、阶段完成状态

- [x] Phase 1 — 配置与基础
- [x] Phase 2 — 相机与控制模块
- [x] Phase 3 — Mixins与覆盖层
- [x] Phase 4 — 网络层
- [x] Phase 5 — 触发器系统
- [x] Phase 6 — 脚本系统
- [x] Phase 7 — 事件处理与HUD
- [x] Phase 8 — 编辑器
- [x] Phase 9 — 调试与收尾

# 无 Architectury 等效的 Forge API 记录

记录自 2026-07-12 讨论。这部分 Forge API 没有 Architectury 跨平台等效，且 Fabric API（1.20.1）同样不提供等效方案。需要自行搭建（Mixin 注入 + 直接调用逻辑）。

> 更新于 2026-07-12：查阅了 FTB-Quests（参考项目）和 Fabric API 源码后确认，以下 6 项在 Architectury 和 Fabric API 中均无等效方案。

---

## 1. `ForgeConfigSpec` — 配置系统

**来源**：`Config.java`

**Forge 做了什么**：
- 用 `ForgeConfigSpec.Builder` 声明类型安全配置字段（IntValue/BooleanValue），带默认值、范围、注释
- 在 `@Mod` 构造函数中用 `ModLoadingContext.get().registerConfig()` 注册 SPEC
- Forge 自动在 `config/` 目录下生成 TOML 文件
- `@SubscribeEvent` 监听 `ModConfigEvent` 把值从 SPEC 读到静态缓存
- GUI 写入通过 `IntValue.set()` + `.save()` 写回

**涉及文件**：`Config.java`

**构建思路**：Forge 的 `ForgeConfigSpec` 本质是一个 Builder + SPEC 对象 + 自动 TOML 读写。我们自己搭就是"声明字段 → 序列化到 JSON/TOML → 运行时读写"的同等套路，不需要事件总线那层。

---

## 2. `RenderGuiOverlayEvent.Pre/Post` — HUD 元素显隐控制

**来源**：`handler/HudOverlayHandler.java`, `control/SkipHudRenderer.java`

**Forge 做了什么**：
- Forge 将每个 HUD 元素注册为独立具名 `GuiOverlay`（hotbar/chat/scoreboard/crosshair 等）
- 渲染时逐个派发 `RenderGuiOverlayEvent.Pre`，可在每个元素渲染前取消
- `HudOverlayHandler.onRenderGuiOverlayPre()` 通过 `event.getOverlay().id()` 识别当前元素，按 `CinematicController` 设置决定是否 `event.setCanceled(true)`
- `SkipHudRenderer` 用 `RenderGuiOverlayEvent.Post` 在所有 HUD 渲染完成后追加跳过进度环

**等效问题**：Architectury 的 `ClientGuiEvent.RENDER_HUD` 只等效于 Forge 的 `Post`（追加绘制），不能取消特定 HUD 元素。无法用事件方式控制元素显隐。

**涉及文件**：`HudOverlayHandler.java`, `SkipHudRenderer.java`, `ImmersiveCinematics.java`（ClientHudEvents）

**构建思路**：Forge 在 `Gui.render()` 中通过 Mixin 在每个 HUD 元素渲染前插入 hook。我们自己做就是在 `Gui.render()` 加 Mixin，在渲染每个元素前检查白名单 + 控制器状态，决定是否跳过渲染。HUD 元素列表可以从 `net.minecraft.client.gui.Gui` 类的成员字段和渲染方法中找到。

---

## 3. `RegisterGuiOverlaysEvent + IGuiOverlay` — 自定义 Overlay 注册

**来源**：`overlay/CinematicOverlay.java`

**Forge 做了什么**：
- 在 MOD 事件总线监听 `RegisterGuiOverlaysEvent`
- `event.registerAboveAll("cinematic_overlay", CINEMATIC_OVERLAY)` 将我们的 overlay 注册到 Forge HUD 渲染管线
- `IGuiOverlay` 是函数式接口，接收 `GuiGraphics` + screen 尺寸，在每帧 HUD 渲染时调用
- 注册后的 overlay 与所有原版 HUD 元素同级，可通过 `RenderGuiOverlayEvent` 被白名单放行

**等效问题**：Architectury/Fabric 没有 overlay 注册系统。所有 HUD 绘制只能通过 `ClientGuiEvent.RENDER_HUD` 追加。

**涉及文件**：`CinematicOverlay.java`, `ImmersiveCinematics.java`

**构建思路**：不需要注册成 overlay。直接用 `ClientGuiEvent.RENDER_HUD` 或 Mixin 在 `Gui.render()` 末尾追加绘制即可。我们的黑边/文字绘制不需要被"统一管理"，自己控制渲染时机就够了。

---

## 4. `LivingEntityUseItemEvent.Finish` — 物品消耗完成事件

**来源**：`ImmersiveCinematics.java` 的 `onItemConsumed()`

**Forge 做了什么**：
- Forge 在物品使用生命周期插入 Start/Tick/Stop/Finish 四个事件点
- `Finish` 在物品消耗完成时触发，携带消耗后的结果 `ItemStack`
- 用于 `item_consume` 触发器类型：`Evaluators.UseItemTracker.recordConsumed(player, event.getItem())` + `TriggerEngine.INSTANCE.onGameEvent(event, player)`

**等效问题**：Architectury 和 Fabric API 都没有此事件。Fabric 只有 `UseItemCallback`（使用前）。

**涉及文件**：`ImmersiveCinematics.java`（onItemConsumed）

**构建思路**：Forge 在 `LivingEntity.completeUsingItem()` 或 `LivingEntity.triggerItemUseFinish()` 中注入。我们在同样位置 Mixin，物品使用完成时调我们的逻辑。

---

## 5. `PlayerEvent.SaveToFile` — 玩家数据持久化

**来源**：`ImmersiveCinematics.java` 的 `onWorldSave()`

**Forge 做了什么**：
- Forge 在 `PlayerList.save()`/`Player.save()` 写盘流程中插入此事件
- `TriggerStateStore.INSTANCE.saveIfChanged(player.getUUID())` 检查玩家触发器状态是否有变更，有则持久化
- 配合 `ServerStoppingEvent` 做最终保存，确保玩家触发器状态不丢失

**等效问题**：Architectury 和 Fabric 都没有此事件。

**涉及文件**：`ImmersiveCinematics.java`（ServerForgeEvents）, `TriggerStateStore.java`

**构建思路**：Forge 在 `PlayerList.save()` 中注入。我们的 `TriggerStateStore` 可以在服务器 tick 结束时批量保存已变更的状态（用 `LifecycleEvent.SERVER_LEVEL_SAVE` 兜底），不需要逐玩家逐次持久化。

---

## 6. `ViewportEvent.ComputeCameraAngles` — 相机 Roll 角

**来源**：`ImmersiveCinematics.java` 的 `ClientCameraEvents.onComputeCameraAngles()`

**Forge 做了什么**：
- 原版 `Camera.setup()` 只设置 yaw/pitch，没有 roll
- Forge 在 `GameRenderer.renderLevel()` 中相机矩阵计算后、PoseStack 应用前插入此事件
- `event.setRoll(mgr.getProperties().getRoll())` 设置翻滚角
- Forge 内部将 roll 应用到 PoseStack 的旋转变换中

**等效问题**：Architectury 没有等效事件。已规划在阶段 4 用 Mixin 处理。

**涉及文件**：`ImmersiveCinematics.java`（ClientCameraEvents）

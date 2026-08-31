# mixin（注入层）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/mixin/`（声明于 `common/src/main/resources/immersive_cinematics.mixins.json`）

功能树：

- **相机接管与渲染**
  - ✅ `CameraMixin`（注入 `Camera`）：HEAD 拦截 `setup()` 并以帧回调驱动模式接管相机——先驱动 `CameraManager.onRenderFrame()` 计算精确位置/朝向，再直接写入（无 partialTick 插值）；手动设置 initialized/level/entity/detached 字段保证声音系统与渲染管线正常（`CameraMixin`）
  - ✅ `CameraMixin`：`getEntity()` 返回玩家实体防止渲染管线 NPE；`isDetached()` 按 `render_player_model` 标志返回，控制玩家身体模型是否渲染（`CameraMixin`）
  - ✅ `CameraMixin` 仅在相机激活且存在活跃 CAMERA clip 时接管，退场动画结束的当帧放弃接管避免白模闪烁（`CameraMixin`）
  - ✅ `GameRendererMixin`（注入 `GameRenderer`）：`getFov()` RETURN 时按关键帧 FOV/zoom 覆盖视场角；`renderItemInHand()` 按 hide_arm 隐藏手臂与手持物品；`bobHurt()`/`bobView()` 按 suppress_bob 抑制视角摆动（`GameRendererMixin`）
  - ✅ `GameRendererMixin`：在 `renderLevel()` 的 `prepareCullFrustum` 之前绕相机视线轴（getLookVector）施加 roll 旋转，任何朝向下 roll>0 均为屏幕顺时针（画面向右倒）（`GameRendererMixin`）
  - ✅ 画面扭曲抑制（反胃/传送门旋转）不在此包：由 `CinematicController` 在脚本 apply/revert 时临时修改原版 `screenEffectScale` 实现（`CinematicController`）
  - ✅ `LevelRendererMixin`（注入 `LevelRenderer`）：非预览过场激活时把 `setupRender` 中玩家坐标局部变量替换为相机坐标，使可见/待建渲染区块跟随相机（`LevelRendererMixin`）
- **HUD 白名单隐藏（Gui 私有方法级注入）**
  - ✅ `GuiMixin`（注入 `Gui`）：按 hide_hotbar/hide_crosshair/hide_scoreboard 及 hide_hud 总开关，HEAD 取消渲染 快捷栏/准心/血量饥饿护甲/载具血量/经验栏/跳跃蓄力条/选中物品名/计分板侧栏/保存提示；action bar 与标题用帧内清零/恢复时间（`GuiMixin`）
  - ✅ 隐藏判定统一：三态标志未设置时回落到 `hide_hud`，**仅判断当前是否处于电影播放状态，不要求存在活跃 CAMERA clip**（`GuiMixin`）
- **HUD 组件隐藏（独立 Mixin 类）**
  - ✅ `ChatComponentMixin`（注入 `ChatComponent`）：按 hide_chat 取消聊天渲染（`ChatComponentMixin`）
  - ✅ `BossHealthOverlayMixin`（注入 `BossHealthOverlay`）：按 hide_bossbar 取消 Boss 血条渲染（`BossHealthOverlayMixin`）
  - ✅ `PlayerTabOverlayMixin`（注入 `PlayerTabOverlay`）：按 hide_scoreboard 取消 Tab 列表/记分板渲染（`PlayerTabOverlayMixin`）
  - ✅ `SubtitleOverlayMixin`（注入 `SubtitleOverlay`）：按 hide_subtitles 取消字幕渲染（`SubtitleOverlayMixin`）
- **输入拦截与播放控制**
  - ✅ `KeyboardHandlerMixin`（注入 `KeyboardHandler`）：HEAD 拦截 `keyPress()`，经 `InputRouter` 路由——GAME 放行、SELF 仅更新跳过键状态、BLOCK 取消事件（`KeyboardHandlerMixin`）
  - ✅ `MouseHandlerMixin`（注入 `MouseHandler`）：HEAD 拦截 `onPress()`/`onScroll()`/`turnPlayer()`，按路由结果取消鼠标按键、滚轮与视角移动；暴露 `resetAccumulated()` 清空 `accumulatedDX/DY`（播放退出时清除视角累积量）（`MouseHandlerMixin`）
  - ✅ `LocalPlayerMixin`（注入 `LocalPlayer`）：脚本激活且 `PlayerMoveController` 有目标时，在 `serverAiStep` HEAD 注入假移动冲量，走原版 `travel()` 完整链路（`LocalPlayerMixin`、`PlayerMoveController`）
  - ✅ `LivingEntityMixin`（注入 `LivingEntity`）：`canAttack()` 在 block_mob_ai 开启且目标为玩家时返回 false，阻止生物攻击玩家（`LivingEntityMixin`）
  - ✅ `ItemUseMixin`（注入 `LivingEntity`）：`completeUsingItem`→item_consume；`releaseUsingItem` 按 UseAnim 分流 item_release（BOW/SPEAR/CROSSBOW/SPYGLASS）与 item_use_interrupt（其余，松手时记录当前手持物品）（`ItemUseMixin`、`Evaluators`）
- **区块预加载与相机区刷怪/实体**
  - ✅ `ChunkMapMixin`（注入 `ChunkMap`）：`anyPlayerCloseEnoughForSpawning` 在相机锚点附近返回 true，让原版刷怪判定认为相机区也有刷怪中心（`ChunkMapMixin`、`CameraAnchorManager`）
  - ✅ `NaturalSpawnerMixin`（注入 `NaturalSpawner`）：附近无真实玩家时，用相机锚点提供的纯坐标虚拟玩家引用计算刷怪距离（`NaturalSpawnerMixin`、`CameraAnchorManager`）
  - ✅ `ClientChunkCacheMixin`（注入 `ClientChunkCache`）：调试用日志——打印客户端区块包、缓存中心变化与被丢弃区块（`ClientChunkCacheMixin`）
- **音频听者跟随相机**
  - ✅ `SoundManagerMixin`（注入 `SoundManager`）：脚本 listener=player 时把原版 SoundEngine 的 listener 覆盖为玩家视角代理（`SoundManagerMixin`、`AudioListenerController`）
  - ✅ `BiomeAmbientSoundsHandlerMixin` / `UnderwaterAmbientSoundHandlerMixin` / `BubbleColumnAmbientSoundHandlerMixin`：听者=相机时，把群系环境音采样点、水下判定、气泡柱环境音判定改到相机位置（`BiomeAmbientSoundsHandlerMixin`、`UnderwaterAmbientSoundHandlerMixin`、`BubbleColumnAmbientSoundHandlerMixin`）
  - ✅ `MinecraftMixin`（注入 `Minecraft`）：听者=相机时，环境粒子/方块音采样中心从玩家改为相机（`MinecraftMixin`、`AudioListenerController`）

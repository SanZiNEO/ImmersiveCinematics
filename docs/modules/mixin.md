# mixin（注入层）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/mixin/`（声明于 `common/src/main/resources/immersive_cinematics.mixins.json`）

功能树：

- **相机接管**
  - ✅ `CameraMixin`（注入 `Camera`）：HEAD 拦截 `setup()` 并以帧回调驱动模式接管相机——先驱动 `CameraManager.onRenderFrame()` 计算精确位置/朝向，再直接写入（无 partialTick 插值）；手动设置 initialized/level/entity/detached 字段保证声音系统与渲染管线正常（`CameraMixin`）
  - ✅ `CameraMixin`：`getEntity()` 返回玩家实体防止渲染管线 NPE；`isDetached()` 按 `render_player_model` 标志返回，控制玩家身体模型是否渲染（`CameraMixin`）
  - ✅ `CameraMixin` 仅在相机激活且存在活跃 CAMERA clip 时接管，退场动画结束的当帧放弃接管避免白模闪烁（`CameraMixin`）
- **视角渲染**
  - ✅ `GameRendererMixin`（注入 `GameRenderer`）：`getFov()` RETURN 时按关键帧 FOV/zoom 覆盖视场角（`fov / zoom`）（`GameRendererMixin`）
  - ✅ `GameRendererMixin`：`renderItemInHand()` 按 hide_arm 隐藏手臂与手持物品；`bobHurt()`/`bobView()` 按 suppress_bob 抑制受击/行走视角摆动（`GameRendererMixin`）
  - ✅ `GameRendererMixin`：Redirect 屏蔽反胃/下界传送门旋转扭曲强度（suppress_bob 或 hide_hud 时归零）（`GameRendererMixin`）
  - ✅ `GameRendererMixin`：在 `renderLevel()` 的 `prepareCullFrustum` 之前绕相机视线轴（getLookVector）施加 roll 旋转，任何朝向下 roll>0 均为屏幕顺时针（画面向右倒）（`GameRendererMixin`）
- **HUD 白名单隐藏（Gui 私有方法级注入）**
  - ✅ `GuiMixin`（注入 `Gui`）：按 hide_hotbar/hide_crosshair/hide_scoreboard 及 hide_hud 总开关，HEAD 取消渲染 快捷栏/准心/血量饥饿护甲/载具血量/经验栏/跳跃蓄力条/选中物品名/计分板侧栏（`GuiMixin`）
  - ✅ 隐藏判定统一：三态标志未设置时回落到 `hide_hud`，活跃且有 CAMERA clip 时才生效（`GuiMixin`）
- **HUD 组件隐藏（独立 Mixin 类）**
  - ✅ `ChatComponentMixin`（注入 `ChatComponent`）：按 hide_chat 取消聊天渲染（`ChatComponentMixin`）
  - ✅ `BossHealthOverlayMixin`（注入 `BossHealthOverlay`）：按 hide_bossbar 取消 Boss 血条渲染（`BossHealthOverlayMixin`）
  - ✅ `PlayerTabOverlayMixin`（注入 `PlayerTabOverlay`）：按 hide_scoreboard 取消 Tab 列表/记分板渲染（`PlayerTabOverlayMixin`）
  - ✅ `SubtitleOverlayMixin`（注入 `SubtitleOverlay`）：按 hide_subtitles 取消字幕渲染（`SubtitleOverlayMixin`）
- **输入拦截**
  - ✅ `KeyboardHandlerMixin`（注入 `KeyboardHandler`）：HEAD 拦截 `keyPress()`，经 `InputRouter` 路由——GAME 放行、SELF 仅更新跳过键状态、BLOCK 取消事件（`KeyboardHandlerMixin`）
  - ✅ `MouseHandlerMixin`（注入 `MouseHandler`）：HEAD 拦截 `onPress()`/`onScroll()`/`turnPlayer()`，按路由结果取消鼠标按键、滚轮与视角移动（`MouseHandlerMixin`）
- **生物 AI 屏蔽**
  - ✅ `LivingEntityMixin`（注入 `LivingEntity`）：`canAttack()` 在 block_mob_ai 开启且目标为玩家时返回 false，阻止生物攻击玩家（`LivingEntityMixin`）

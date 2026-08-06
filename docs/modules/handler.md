# handler（事件处理）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/handler/`

功能树：

- **服务端事件注册（`ServerEventHandler`）**
  - ✅ 服务器启动（SERVER_STARTED）：全局脚本复制到世界存档 → 加载全部脚本 → 初始化触发器状态存储与引擎 → 批量注册触发器（`ServerEventHandler`）
  - ✅ 服务器停止（SERVER_STOPPING）与世界保存（SERVER_LEVEL_SAVE）：全量保存触发器状态（`ServerEventHandler`）
  - ✅ 玩家加入（PLAYER_JOIN）：加载该玩家触发状态，并触发 `login` 触发器（`ServerEventHandler`）
  - ✅ 玩家退出（PLAYER_QUIT）：保存并卸载玩家状态，清理全部内存追踪器（Kill/Interact/Craft/CustomEvent/UseItem/Inventory）（`ServerEventHandler`）
  - ✅ 服务器 tick（SERVER_POST）：驱动轮询触发器 `TriggerEngine.onServerTick()` 与脚本事件会话 `ScriptEventManager.onServerTick()`（`ServerEventHandler`）
  - ✅ 命令注册：集成/专用服务器环境下注册 `/icinematics` 命令树（`ServerEventHandler`、`CinematicCommand`）
  - ✅ 事件驱动触发器接线：`LIVING_DEATH`→entity_kill（记录击杀）、`RIGHT/LEFT_CLICK_BLOCK`→block_interact（记录方块与手持物品）、`INTERACT_ENTITY`→entity_interact（记录实体与手持物品）、`CRAFT_ITEM`→item_craft（记录合成物品）、`RIGHT_CLICK_ITEM`→item_use（记录使用物品）、`CHANGE_DIMENSION`→dimension_change（`ServerEventHandler`、`Evaluators`）
- **客户端事件注册（`ClientEventHandler`）**
  - ✅ 按键注册：跳过键（默认 C）与编辑器键（默认 F6，EDITOR_ENABLED 时）注册进 KeyMappingRegistry（`ClientEventHandler`、`CinematicKeyBindings`）
  - ✅ 客户端 tick（CLIENT_POST）：驱动 `CameraManager.tick()`（staged 缓冲插值）与 `CinematicKeyBindings.onClientTick()`（跳过/强退/编辑器键）（`ClientEventHandler`）
  - ✅ HUD 渲染（RENDER_HUD）：追加绘制跳过提示 HUD 与电影覆盖层（黑边等）（`ClientEventHandler`、`SkipHudRenderer`、`CinematicOverlay`）

## 已知问题

- 4 个事件驱动触发器注册了类型但没有任何事件源调用 `onGameEvent()`，永远不会触发：`advancement`（注释注明"需 Mixin 或替代 API"）、`item_consume`（注释注明"需 Mixin LivingEntity.completeUsingItem()"）、`item_on_interact`（无任何接线）、`custom`（`CustomEventTracker.fire()` 无调用方）（来源：`ServerEventHandler`、`ImmersiveCinematics.registerTriggerTypes`）

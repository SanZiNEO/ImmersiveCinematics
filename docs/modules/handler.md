# handler（事件处理）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/handler/`

功能树：

- **服务端事件注册（`ServerEventHandler`）**
  - ✅ 服务器启动（SERVER_STARTED）：全局脚本复制到世界存档 → 加载全部脚本 → 初始化触发器状态存储与引擎 → 批量注册触发器（`ServerEventHandler`）
  - ✅ 服务器停止（SERVER_STOPPING）与世界保存（SERVER_LEVEL_SAVE）：全量保存触发器状态（`ServerEventHandler`）
  - ✅ 玩家加入（PLAYER_JOIN）：加载该玩家触发状态并补发状态同步包，触发 `login` 触发器（`ServerEventHandler`）
  - ✅ 玩家退出（PLAYER_QUIT）：保存并卸载玩家状态，清理全部内存追踪器（Kill/Advancement/Interact/Craft/UseItem/PickupDrop/Inventory）（`ServerEventHandler`）
  - ✅ 服务器 tick（SERVER_POST）：驱动轮询触发器 `TriggerEngine.onServerTick()` 与脚本事件会话 `ScriptEventManager.onServerTick()`（`ServerEventHandler`）
  - ✅ 命令注册：集成/专用服务器环境下注册 `/icinematics` 命令树（`ServerEventHandler`、`CinematicCommand`）
  - ✅ 事件驱动触发器接线：`LIVING_DEATH`→entity_kill（记录击杀含场景数据）、`PLAYER_ADVANCEMENT`→advancement、`RIGHT_CLICK_BLOCK`→block_interact+item_on_interact、`INTERACT_ENTITY`→entity_interact+item_on_interact、`CRAFT_ITEM`→item_craft、`RIGHT_CLICK_ITEM`→item_use、`PICKUP_ITEM_POST`→item_pickup、`DROP_ITEM`→item_drop、`CHANGE_DIMENSION`→dimension_change、`EntityEvent.ADD`(投掷物)→item_instant_use（`ServerEventHandler`、`Evaluators`）
- **客户端事件注册（`ClientEventHandler`）**
  - ✅ 按键注册：跳过键（默认 C）与编辑器键（默认 F6，EDITOR_ENABLED 时）注册进 KeyMappingRegistry（`ClientEventHandler`、`CinematicKeyBindings`）
  - ✅ 客户端 tick（CLIENT_POST）：驱动 `CameraManager.tick()`（staged 缓冲插值）与 `CinematicKeyBindings.onClientTick()`（跳过/强退/编辑器键）（`ClientEventHandler`）
  - ✅ HUD 渲染（RENDER_HUD）：追加绘制跳过提示 HUD 与电影覆盖层（黑边等）（`ClientEventHandler`、`SkipHudRenderer`、`CinematicOverlay`）

## 已知问题

- `item_consume`/`item_release`/`item_use_interrupt` 依赖 `ItemUseMixin`（LivingEntity 注入），运行时需确认 Mixin 应用成功（defaultRequire=1，失败即启动崩溃）

# core（入口与配置）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/`

功能树：

- **模组入口**
  - ✅ `ImmersiveCinematics.init()` 完成初始化链：配置加载 → 网络层 `NetworkHandler.init()` → 注册 16 种触发器类型 → 服务端事件注册 → 客户端事件注册（环境区分）（`ImmersiveCinematics`）
  - ✅ 定义模组 id `immersive_cinematics` 与编辑器开关 `EDITOR_ENABLED`（`ImmersiveCinematics`）
  - ✅ `registerTriggerTypes()` 注册 16 种触发器：location/biome/inventory/structure/gamestage（轮询）+ advancement/entity_kill/entity_interact/dimension_change/login/item_craft/item_use/item_consume/block_interact/item_on_interact/custom（事件驱动），轮询间隔取自配置（`ImmersiveCinematics`、`TriggerRegistry`）
- **全局配置**
  - ✅ `Config` 静态字段集中管理运行时配置：跳过长按阈值 `skipHoldThresholdMs`（默认 3000ms）、跳过提示 HUD 开关 `showSkipHud`、跳过投票比例 `skipVoteRatio`（默认 100%）、调试日志 `debugLogging`（`Config`）
  - ✅ 5 个触发器轮询间隔配置：location/inventory/structure/gamestage（默认 20 tick）、biome（默认 40 tick）（`Config`）
  - ✅ 跨平台配置抽象：`ConfigProvider` 接口 + `ConfigValues` 记录，Forge 用 ConfigSpec、Fabric 用 JSON 文件实现，启动时 `init()` 加载并应用（`Config`）
  - ✅ 提供 `setSkipHoldThresholdMs`/`setShowSkipHud`/`setDebugLogging` 供 `ConfigScreen` 写入并持久化（`Config`）

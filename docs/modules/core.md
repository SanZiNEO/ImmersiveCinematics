# core（入口与配置）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/`

功能树：

- **模组入口**
  - ✅ `ImmersiveCinematics.init()` 完成初始化链：配置加载 → 网络层 `NetworkHandler.init()` → 注册 23 种触发器类型 → 注册内置前置条件（`BuiltinPrerequisites`）→ 服务端/客户端事件注册与平台桥接由各平台入口完成（`ImmersiveCinematics`）
  - ✅ 定义模组 id `immersive_cinematics` 与编辑器开关 `EDITOR_ENABLED`（`ImmersiveCinematics`）
  - ✅ `registerTriggerTypes()` 注册 23 种触发器：location/biome/inventory/structure/gamestage/xp/dimension/observation（轮询）+ advancement/entity_kill/entity_interact/dimension_change/login/item_craft/item_use/item_consume/item_release/item_instant_use/item_use_interrupt/block_interact/item_on_interact/item_pickup/item_drop（事件驱动），轮询间隔取自配置（`ImmersiveCinematics`、`TriggerRegistry`）
- **全局配置**
  - ✅ `Config` 静态字段集中管理运行时配置：跳过长按阈值 `skipHoldThresholdMs`（默认 3000ms）、跳过提示 HUD 开关 `showSkipHud`、跳过投票比例 `skipVoteRatio`（默认 100%）、调试日志 `debugLogging`、编辑器开关 `editorEnabled`（`Config`）
  - ✅ 5 个触发器轮询间隔配置：location/inventory/structure/gamestage（默认 20 tick）、biome（默认 40 tick）；xp/dimension 复用 location 间隔，observation 固定 5 tick（`Config`）
  - ✅ 区块预加载配置：`preloadEnabled`（总闸）、`preloadReportInterval`（默认 20 tick）、`preloadMaxBurstPerTick`（默认 20）、`preloadMaxRequestsPerTick`（默认 8）、`preloadRadiusCap`（默认 32）、`preloadForceRadius`/`preloadForceRadiusValue`、`preloadPrewarmLeadSeconds`（默认 2s）/`preloadPrewarmRadius`（默认 8）/`preloadPrewarmRequestsPerTick`（默认 6）（`Config`）
  - ✅ 跨平台配置抽象：`ConfigProvider` 接口 + `ConfigValues` 记录，Forge 用 ConfigSpec、Fabric 用 JSON 文件实现，启动时 `init()` 加载并应用（`Config`）
  - ✅ 提供各配置 setter（跳过/调试/编辑器/预加载）供平台持久化；`ConfigScreen` 当前只编辑 `showSkipHud`、`skipHoldThresholdMs`、`debugLogging`、`editorEnabled` 四项（`Config`）

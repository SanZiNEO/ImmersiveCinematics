# 0.3.5 预加载补全：下一片段预热 + 状态边界统一差集 + 结束释放差集复用

**状态**: ✅ 已实施，待游戏内回归
**目标**: 所有状态边界（镜头切换/空档进出/脚本结束）统一走 `desired - playerCovered - 已持有` 差集；无 far/near 距离判定；保留连续飞行边飞边加载；预览/预热与释放复用接在同一套集合运算上。

## 一、状态边界统一差集

- 客户端按“当前视口状态”上报：
  - 有活跃 CAMERA 片段 → 上报相机位置；
  - 空档/无活跃镜头 → 上报玩家位置；
  - 有/无镜头切换时立即上报，不等周期。
- 服务端 `ChunkPreloadManager` 在每个边界统一执行：
  ```
  待加 = desired - playerCovered - cameraZone
  待撤 = cameraZone - desired
  forget = sentCameraChunks - playerNeed
  补发 = playerNeed - reusable
  ```
- 适用于：
  - 相机A → 相机B；
  - 相机A → 空档（玩家）；
  - 空档 → 相机B；
  - 脚本结束释放。
- 无 CAMERA 轨道的脚本不进入预加载（客户端/服务端双重判断）。

## 二、下一片段预热（Prewarm）

- 客户端 `PreloadRequester` 读取 CAMERA 轨道，在 `nextClip.startTime - elapsed <= preloadPrewarmLeadSeconds` 时向服务端发 `MODE_PREWARM`，携带下一片段首帧世界坐标。
- 支持：absolute、relative(玩家基准)、relative_origin=coordinate；
- 暂不支持：结构/方块/实体/facing 相对目标（跳过并保留日志）。
- 服务端 `ChunkPreloadManager` 增加独立的 `prewarmZone` / `pendingPrewarmChunks`，只加 ticket、不补发、不切中心。
- 当前相机中心进入预热区时，把已持票的预热块直接晋级到 `cameraZone`（复用同一 ticket key，不重复加票）；未加票的从预热待加队列移除，交给相机差集正常处理。
- 释放/打断/跳过后清理预热 ticket。

## 三、结束释放差集复用（Release diff）

- `playerNeed = computePlayerCoveredSet(st)`（玩家圆形视距集合）
- `reusable = playerNeed ∩ sentCameraChunks`
- 释放时：
  - 只 forget `sentCameraChunks - playerNeed`；
  - 只补发 `playerNeed - reusable`（服务端已加载的缺失块）；
  - `playerNeed - reusable` 为空则不重发玩家区。
- 客户端不再强制 `allChanged()`；真正需要重发的玩家区由释放差集自动决定。

## 四、精简清理

- 删除旧 far/near 距离门控；
- 删除未使用配置：preloadWindowRadius / preloadMaxChunks / preloadMaxWorldgenChunks / preloadTimeoutGenerated / preloadTimeoutWorldgen / preloadPrewarm / preloadFarViewCenterThreshold / preloadPlayerZoneRadius / preloadRearRadius；
- 保留：preloadEnabled / preloadReportInterval / preloadMaxBurstPerTick / preloadMaxRequestsPerTick / preloadRadiusCap / preloadForceRadius / preloadForceRadiusValue / preloadPrewarmLeadSeconds / preloadPrewarmRadius / preloadPrewarmRequestsPerTick。

## 五、涉及文件

- `common/.../trigger/network/C2SPreloadRequestPacket.java`
- `common/.../trigger/client/PreloadRequester.java`
- `common/.../script/ScriptPlayer.java`
- `common/.../trigger/server/ChunkPreloadManager.java`
- `common/.../camera/CameraManager.java`
- `common/.../Config.java`
- `forge/.../ForgeConfig.java`
- `fabric/.../FabricConfig.java`

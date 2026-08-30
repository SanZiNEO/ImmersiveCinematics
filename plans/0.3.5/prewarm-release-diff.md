# 0.3.5 预加载补全：下一片段预热 + 结束释放差集复用

**状态**: ✅ 已实施，待游戏内回归
**目标**: 多片段跳转前提前加载目标区块；脚本结束时不无条件重发玩家区，改为相机已持有区块与玩家需要区块的差集复用。

## 一、下一片段预热（Prewarm）

- 客户端 `PreloadRequester` 读取 CAMERA 轨道，在 `nextClip.startTime - elapsed <= preloadPrewarmLeadSeconds` 时向服务端发 `MODE_PREWARM`，携带下一片段首帧世界坐标。
- 支持：absolute、relative(玩家基准)、relative_origin=coordinate；
- 暂不支持：结构/方块/实体/facing 相对目标（跳过并保留日志）。
- 服务端 `ChunkPreloadManager` 增加独立的 `prewarmZone` / `pendingPrewarmChunks`，只加 ticket、不补发、不切中心。
- 当前相机中心进入预热区时，把已持票的预热块直接晋级到 `cameraZone`（复用同一 ticket key，不重复加票）；未加票的从预热待加队列移除，交给相机差集正常处理。
- 释放/打断/跳过后清理预热 ticket。

## 二、结束释放差集复用（Release diff）

- `playerNeed = computePlayerCoveredSet(st)`（玩家圆形视距集合）
- `reusable = playerNeed ∩ sentCameraChunks`
- 释放时：
  - 只 forget `sentCameraChunks - playerNeed`；
  - 只补发 `playerNeed - reusable`（服务端已加载的缺失块）；
  - `playerNeed - reusable` 为空则不重发玩家区。
- 不重新引入 far/near 阈值；相机近则 overlap 大，自动少补/不补，相机远则 overlap 小自动走补发。

## 三、涉及文件

- `common/.../trigger/network/C2SPreloadRequestPacket.java`
- `common/.../trigger/client/PreloadRequester.java`
- `common/.../script/ScriptPlayer.java`
- `common/.../trigger/server/ChunkPreloadManager.java`
- `Config.java`（已存在 prewarm 配置字段，仅接线）

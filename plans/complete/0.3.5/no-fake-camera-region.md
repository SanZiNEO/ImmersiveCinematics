# 无假人相机区域方案（No-Fake Camera Region）

**状态**: ✅ 已实施（0.3.5 无假人方案落地，已归档至 plans/complete/0.3.5/）
**日期**: 2026-08-22
**目标**: 用“相机锚点实体 + 手动实体同步 + 刷怪虚拟存在”替代完整假人方案，绕开 PlayerList / Connection / Mohist 兼容问题。

---

## 背景

- 假人方案引入大量连锁问题：PlayerList 私有字段、Connection mixin、广播隐藏、Mohist 类加载失败。
- 历史非假人方案已能：
  - 区块加载：`ServerChunkCache.addRegionTicket` + scanChunk 分流 + 加载顺序
  - 渲染中心：`ClientboundSetChunkCacheCenterPacket` + 手动区块包补发
  - 声音：客户端相机 listener
- 缺两块：
  1. 相机附近自然刷怪/despawn
  2. 相机附近实体/生物包发给真实客户端

---

## 架构

```
CameraAnchorPlayer（ServerPlayer 子类，仅作为世界内 Player 实体）
  ├─ 不加入 PlayerList
  ├─ 无 Connection
  ├─ 不可见 / 无敌 / 无碰撞 / 不保存
  ├─ 加入 ServerLevel（level.addFreshEntity）
  └─ 作用：让 NaturalSpawner / Mob despawn 的 getNearestPlayer 能找到“玩家”

CameraAnchorManager
  ├─ 维护每个玩家的相机锚点实体
  ├─ 创建 / 移动 / 移除锚点实体
  └─ 提供查询：某区块附近是否有相机锚点

CameraEntitySyncManager
  ├─ 扫描相机半径内实体
  ├─ 为每个实体创建专用 ServerEntity（broadcast → 真实玩家 connection）
  ├─ 初始 sendPairingData + 每 tick sendChanges
  ├─ 离开/消失发 ClientboundRemoveEntitiesPacket
  └─ 真实玩家已跟踪范围外的实体才补发

ChunkPreloadManager（已有）
  └─ 继续负责 ticket 加载 + 区块包补发 + 渲染中心切换

ChunkMapMixin（新增）
  └─ anyPlayerCloseEnoughForSpawning：相机锚点附近也视为“有玩家可刷怪”
```

---

## 新增文件

- `common/.../trigger/server/CameraAnchorPlayer.java`
- `common/.../trigger/server/CameraAnchorManager.java`
- `common/.../trigger/server/CameraEntitySyncManager.java`
- `common/.../mixin/ChunkMapMixin.java`（common，服务端）

## 修改文件

- `common/.../trigger/server/ChunkPreloadManager.java`
  - 去掉 `CameraMobManager` 调用，改调 `CameraAnchorManager` / `CameraEntitySyncManager`
- `common/.../handler/ServerEventHandler.java`
  - tick 里加 `CameraAnchorManager.tick()` / `CameraEntitySyncManager.tick()`
- `common/src/main/resources/immersive_cinematics.mixins.json`
  - 新增 `ChunkMapMixin`
- Forge/Fabric 入口
  - 删除假人引导器设置（若已无引用）
- 删除假人相关：
  - `CameraFakePlayer`
  - `CameraFakeConnection`
  - `ForgeFakePlayerBootstrapper`
  - `FabricFakePlayerBootstrapper`
  - `FakePlayerBootstrapper`
  - `PlayerListMixin`
  - `ConnectionMixin` / `ConnectionAccessor`
  - `ServerEntityMixin`（若实体同步不再需要拦截）
  - `CameraMobManager`（替换为 CameraAnchorManager + CameraEntitySyncManager）

---

## 刷怪 mixin 细节

`ChunkMap.anyPlayerCloseEnoughForSpawning(ChunkPos)` 原版实现：

```java
if (!this.distanceManager.hasPlayersNearby(l)) return false;
for (ServerPlayer serverPlayer : this.playerMap.getPlayers(l)) {
   if (this.playerIsCloseEnoughForSpawning(serverPlayer, chunkPos)) return true;
}
return false;
```

Mixin 方案：

```java
@Inject(method = "anyPlayerCloseEnoughForSpawning", at = @At("HEAD"), cancellable = true)
private void ic$cameraAnchorCloseForSpawning(ChunkPos chunkPos, CallbackInfoReturnable<Boolean> cir) {
    if (CameraAnchorManager.INSTANCE.isAnyAnchorNear(this.level, chunkPos)) {
        cir.setReturnValue(true);
    }
}
```

`CameraAnchorManager.isAnyAnchorNear(level, chunkPos)` 直接用公开 API：
- 遍历 `level.getEntities(...)` 找 `CameraAnchorPlayer`
- 判断与 chunkPos 距离 < 128 格（原版 `playerIsCloseEnoughForSpawning` 同款）

NaturalSpawner 不需要改：因为 `getNearestPlayer` 会找到 `CameraAnchorPlayer`。

---

## 实体同步细节

```java
public final class CameraEntitySyncManager {
    private static final class AnchorSync {
        ServerPlayer real;
        ServerLevel level;
        ChunkPos center;
        int radius;
        final Map<Integer, ServerEntity> trackers = new HashMap<>();
    }

    void setAnchor(UUID player, ServerLevel level, ChunkPos center, int radius) { ... }
    void removeAnchor(UUID player) { ... send removes ... }
    void tick() {
        for (AnchorSync a : anchors.values()) {
            // 1. 扫描相机半径内实体
            // 2. 新实体：new ServerEntity(level, e, 1, false, a.real.connection::send)
            //          + sendPairingData(a.real, a.real.connection::send)
            // 3. 已有实体：tracker.sendChanges()
            // 4. 离开/消失：send ClientboundRemoveEntitiesPacket + remove tracker
        }
    }
}
```

注意：
- 跳过真实玩家自己
- 跳过真实玩家原版跟踪范围内的实体（`isNearPlayer` 同款判断），避免重复包
- 移除时若实体已进入原版跟踪范围，不补 Remove（原版会接管）

---

## 实施顺序

1. 写方案（本文件）
2. `CameraAnchorPlayer`
3. `CameraAnchorManager`
4. `CameraEntitySyncManager`
5. `ChunkMapMixin`
6. 接线 `ChunkPreloadManager` / `ServerEventHandler`
7. 删除假人代码
8. 编译 + 构建 Forge/Fabric jar 验证

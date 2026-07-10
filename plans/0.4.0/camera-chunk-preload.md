# 相机区块预加载与滑动窗口

**版本**: 0.4.0  
**类型**: 新功能  
**工作流**: 短期 (2-4h)  
**非侵入性**: 只调用 Forge/原版 API，不 Mixin 任何内部类

---

## 问题

运镜时相机可能飞到玩家 20+ 格外。此时 MC 以玩家为唯一处理中心，导致：
- 相机路径上的区块未加载（实体失踪、方块不渲染、树木消失）
- 环境音不触发（生物群系声音只看玩家位置）
- 只有音乐照常播放（不受位置影响）

## 方案

不创建第二处理中心，让原版区块加载系统自己覆盖相机区域。

SLIDE_WINDOW_SIZE = view-distance（服务端配置，默认 10，即 21×21 区块）

---

## 1. 预加载阶段

脚本触发后、播放前，提取全程需要的区块并提前加载：

```
CinematicController.triggerPreload(script):
  allChunks = new HashSet<ChunkPos>()

  for each CAMERA clip:
    for each keyframe:
      pos = keyframe.position (absolute 或 relative+player 解析后)
      chunk = ChunkPos(pos.x >> 4, pos.z >> 4)
      allChunks.add(chunk)
      // 每个关键帧位置加 SLIDE_WINDOW_SIZE×SLIDE_WINDOW_SIZE 窗口（取适量，比如 3×3）
      for dx in [-1, 0, 1]:
        for dz in [-1, 0, 1]:
          allChunks.add(ChunkPos(chunk.x + dx, chunk.z + dz))

  for each chunk in allChunks:
    level.getChunkSource().addRegionTicket(cameraTicket, chunk, 2, originPos)

  // 异步等待
  schedulePoll(server):
    allReady = allChunks.all(chunk -> level.getChunkSource().hasChunk(chunk.x, chunk.z))
    if allReady or timeout(15s):
      CameraManager.playScript(script)  // 开始播放
```

---

## 2. 滑动窗口维护

播放期间在 `CameraManager.onRenderFrame()` 中维护：

```
currentChunks = HashSet<ChunkPos>()  // 当前已 ticketed 的区块

onRenderFrame():
  cameraPos = scriptPlayer.getCurrentPosition()
  center = ChunkPos(cameraPos.x >> 4, cameraPos.z >> 4)

  newWindow = HashSet<ChunkPos>()
  r = SLIDE_WINDOW_SIZE / 2
  for x in [center.x - r .. center.x + r]:
    for z in [center.z - r .. center.z + r]:
      newWindow.add(ChunkPos(x, z))

  toRemove = currentChunks - newWindow
  toAdd = newWindow - currentChunks

  for chunk in toRemove:
    level.getChunkSource().removeRegionTicket(cameraTicket, chunk, 2, originPos)

  for chunk in toAdd:
    level.getChunkSource().addRegionTicket(cameraTicket, chunk, 2, originPos)

  currentChunks.removeAll(toRemove)
  currentChunks.addAll(toAdd)
```

---

## 3. 清理

脚本结束/退出时：

```
releaseAllTickets():
  for chunk in currentChunks:
    level.getChunkSource().removeRegionTicket(cameraTicket, chunk, 2, originPos)
  currentChunks.clear()
  preloadChunks.clear()
```

---

## 4. TicketType 定义

```java
private static final TicketType<BlockPos> CAMERA_TICKET =
    TicketType.create("immersive_cinematics", Comparator.comparing(BlockPos::compareTo));

private static final BlockPos TICKET_ORIGIN = BlockPos.ZERO;  // 固定 owner

// 用 BlockPos.ZERO 作为 owner，不依赖实体 UUID，
// 不持久化（不调用 ForgeChunkManager），脚本结束即清理
```

---

## 5. 多平台抽象（未来）

| 平台 | 实现 |
|------|------|
| Forge | `ForgeChunkManager`（持久化）或 `addRegionTicket`（不持久化，推荐用于滑动窗口） |
| NeoForge | 同 Forge |
| Fabric | `ServerChunkSource.addRegionTicket()` |

抽象 `IChunkTicketManager` 接口，platform-specific 实现。

---

## 6. 与 Voxy/DistantHorizons 兼容

Voxy/DH 使用 LOD 假地形，走独立渲染管线，不依赖真实区块加载。我们加 `addRegionTicket` 只影响实体/音效/粒子/真实地形，和 LOD 不冲突。已加载区块的 ticket 是空操作（ticket 计数 +1）。

---

## 7. 后续扩展

| 扩展 | 说明 |
|------|------|
| 实体生成 | 0.4.0 入：给 `NaturalSpawner` 注入相机位置为额外刷怪中心（需要 Mixin） |
| 环境音 | 区块加载后自动生效（biome ambient 走 Chunk→Biome 链），无需额外处理 |
| 跨维度 | 逐维管理 ticket，切换时清理旧维度、预加载新维度 |

# 0.3.5 区块预加载与相机区域统一设计（v5.5 假人完全接管版）

**状态**: 📋 原初设计（假人完全接管版）。实际代码最终采用“状态边界统一差集”，最终方案见 `prewarm-release-diff.md`，本文保留作设计追溯。
**关联**: 统一 `chunk-preload.md`（3.5 手动 ticket 版）与 `camera-region-mechanics-5.5.md`（5.5 假人接管版）；为 0.3.5 预加载 + 相机区域原初设计源
**决策**:
- 架构：**假人完全接管**，删除手动相机区 ticket / 手动区块补发
- Prewarm：**保留 ticket 预载**（轻量，只提前加载区块）
- 文档：本文统一 3.5 与 5.5，并纳入上一轮代码审查/已知问题

---

## 1. 背景：为什么 3.5 方案已经过时

3.5 的 `chunk-preload.md` 设计是“手动驱动原版加载”：

- `scanChunk` 读磁盘 Status，区分已生成/未生成；
- 对相机区手动 `addRegionTicket`；
- 就绪后手动构造 `ClientboundLevelChunkWithLightPacket` 补发给玩家；
- 自己维护滑动窗口 ticket 差集与已发送记账。

但 5.5 的“隐藏假人完全接管”路线证明：**与其手写原版加载/补发/实体逻辑，不如在服务端放一个 CameraFakePlayer，让原版把相机当成一个真实玩家来对待**。

当前代码已经部分迁移到该路线：

- `ChunkPreloadManager.requestTickets()` 已不再真正加票，只做状态记账；
- `ChunkPreloadManager.sendReady()` 已是空实现，注释明确“区块由 CameraFakeConnection 转发假人收到的原版区块流”；
- 相机区加载/刷怪/despawn/实体跟踪由 `CameraFakePlayer` / `CameraMobManager` 驱动；
- `scanChunk` 磁盘分流已无调用。

因此本文不是“再发明一套”，而是把已经发生的架构变更固化为正式设计，并清理 3.5 遗留代码与文档。

---

## 2. 目标架构

### 2.1 核心模型

```
真实玩家（正常原版玩家）
   │
   │  CameraFakePlayer（隐藏假人，位置钉在相机）
   │   ├─ 驱动原版：区块加载/生成、刷怪、AI、despawn、实体跟踪
   │   ├─ 不参与：脚本/触发器/Tab/玩家数据落盘/聊天
   │   └─ 连接 = CameraFakeConnection：把原版发给假人的包流
   │        “按原版顺序”转发给真实玩家
   │
   └─ ChunkPreloadManager（瘦身为生命周期/状态机）
        ├─ far/near 判定：相机离玩家超过视距 → 进入 far 模式
        ├─ 创建/移动/移除假人
        ├─ 客户端缓存中心切换：far → 相机块，near/结束 → 玩家块
        ├─ 玩家区小块票券：保证返回玩家时快速恢复、不卸载
        ├─ 返回玩家时玩家区对账补发（拆掉原版记账空洞）
        └─ 释放/断线清理
```

### 2.2 职责边界

| 模块 | 职责 | 不做 |
|---|---|---|
| `CameraFakePlayer` | 作为服务端相机锚点，驱动原版加载/刷怪/despawn/实体跟踪 | 不执行玩家移动/AI、不触发脚本/触发器、不出现在 Tab、不落盘 |
| `CameraFakeConnection` | 转发假人收到的原版区块包/实体包/移除包给真实玩家 | 不自己构造区块包，不重复转发玩家视距内已由原版发送的包 |
| `ChunkPreloadManager` | far 生命周期、假人创建/移动/移除、客户端中心切换、玩家区票券、返回对账、释放清理 | 不再手动加相机区 ticket、不再手动补发相机区区块、不再 scanChunk |
| `PreloadRequester`（客户端） | 上报相机位置、发起 PRELOAD/PREWARM/RELEASE、按 `meta.preload` 开关 | 不直接决定服务端加载策略 |
| 配置层 | 持久化全部预加载配置（ForgeConfigSpec / Fabric JSON） | 不用静态默认值代替持久化 |

---

## 3. 详细设计

### 3.1 相机锚点（CameraFakePlayer）

- 每个播放者维护一个隐藏假人，位置钉在相机锚点。
- 假人加入服务端玩家列表，由原版 `ChunkSource.move()` 驱动相机周围区块加载/生成。
- `camera_mob_spawn=false` → 假人设旁观模式，只加载区块不刷怪；
- `camera_mob_ai=false` → 相机区刷出的怪用原版 NoAI 冻结；
- `camera_mob_radius` 控制刷怪半径。
- 假人 tick 不调用 `super.tick()`，避免移动/坠落/玩家逻辑；位置由 `CameraMobManager` 定期钉住。
- 生命周期：进入 far 时创建，脚本结束/退出/断线时移除。

### 3.2 包流转发（CameraFakeConnection）

这是“假人完全接管”最关键的部分：

- 假人收到的原版区块包、实体生成包、实体移除包、Bundle 包等，**按原版到达顺序**转发给真实玩家；
- 这样区块和实体走同一条原版时序，避免“实体到了、区块没到”或顺序错乱；
- 相机靠近玩家/退出 far 时停止转发，避免与原版玩家跟踪重复；
- 需要维护已同步实体 ID 集合，退出时按“玩家视距内/外”决定是否发移除包，避免客户端残留幽灵实体。

### 3.3 ChunkPreloadManager 瘦身

只保留：

1. far/near 判定：
   - `|相机块 − 玩家块| > 视距` → far；
   - 回到玩家视距 → 退出 far。
2. 假人生命周期：
   - 进入 far → 创建/更新假人；
   - far 中滑动 → 移动假人到新相机块；
   - 退出/结束/断线 → 移除假人。
3. 客户端缓存中心：
   - far → `ClientboundSetChunkCacheCenterPacket` 指向相机块；
   - near/结束 → 指回玩家块。
4. 玩家区保护：
   - far 期间对玩家所在块挂小块票券，保证玩家区不卸载、返回时快速恢复。
5. 返回对账补发：
   - 退出 far 时手动重发玩家 ± 视距内已加载区块，拆掉原版记账脱节造成的空洞。
6. 释放清理：
   - 移除假人、撤玩家区票券、清理转发记账、中心回玩家。

### 3.4 Prewarm（保留 ticket 预载）

- 下一 clip 开始前，用轻量 `addRegionTicket` 提前加载目标区域区块；
- 只加载区块，不转发、不刷怪、不产生实体；
- 跳过去后由 far-view / 假人接管补发；
- 若未来需要“下一 clip 实体提前在场”，再扩展为第二假人预载，不在本期做。

### 3.5 配置平台持久化

必须把以下配置接入 Forge `ForgeConfigSpec` 和 Fabric JSON：

- `preloadEnabled` / `preloadWindowRadius` / `preloadMaxChunks` / `preloadMaxWorldgenChunks`
- `preloadTimeoutGenerated` / `preloadTimeoutWorldgen` / `preloadPrewarm`
- `preloadReportInterval` / `preloadFarViewCenterThreshold` / `preloadPlayerZoneRadius`
- `preloadMaxBurstPerTick` / `preloadMaxRequestsPerTick` / `preloadRearRadius`
- `preloadRadiusCap` / `preloadForceRadius` / `preloadForceRadiusValue`
- `preloadPrewarmLeadSeconds` / `preloadPrewarmRadius` / `preloadPrewarmRequestsPerTick`

✅ 已完成：`Config.ConfigValues` 已扩展全部字段，`ForgeConfigSpec` / Fabric JSON 均已接入读写（含 `setFloat`）。

---

## 4. 清理 / 废弃清单（按本文执行）

| 项 | 处理 |
|---|---|
| `scanChunk` 磁盘分流（已生成/未生成分级） | 废弃，不再实现；如需“未探索区域渐进加载”，由假人原版加载 + 预算控制承担 |
| 相机区手动 `addRegionTicket` | 删除；相机区由假人驱动 |
| 手动 `ClientboundLevelChunkWithLightPacket` 补发相机区 | 删除；由 `CameraFakeConnection` 转发原版包流 |
| `ChunkPreloadManager.requestTickets()` | ✅ 已删除 |
| `ChunkPreloadManager.sendReady()` | ✅ 已删除 |
| `desired/ticketed/sent` 状态 | ✅ 已删除 |
| `ChunkTicketPool` | 仅保留给 prewarm / 玩家区票券使用，删除相机区用途；注释仍待同步（见 code-review 3.2） |
| 配置静态默认值 | ✅ 已接入平台持久化 |
| 旧文档 `chunk-preload.md` 中的 3.5 方案 | 标记为历史方案，本文为唯一设计源 |

---

## 5. 上一轮问题与本设计的关系

### 5.1 必须随本设计解决的问题

| 问题 | 来源 | 处理方向 |
|---|---|---|
| Forge 实体数量偏少（服务端 110~120，客户端仅 23~25） | `known-issues.md` / 5.5 联调 | 重点查 `CameraFakeConnection` 转发链路：`ClientboundBundlePacket` 拆包、区块/实体同序、Forge 假人引导差异；必要时加 Forge 侧实体对账日志 |
| 预加载配置平台持久化未完成 | 3.5 轮 | ✅ 已完成，按 §3.5 接入 Forge/Fabric 配置 |
| `ChunkPreloadManager` 死代码 / 每秒全量扫描 | `code-review-round.md` | ✅ 死代码已清理；`countEntities()` 降频/性能优化仍待做 |
| `C2SPreloadRequestPacket` 协议扩展无版本保护 | `code-review-round.md` | 加协议版本或兼容读取，避免旧客户端/服务端错位 |
| 假人生命周期“不干净”风险（数据落盘、状态残留、重复实体） | `code-review-round.md` | 纳入假人生命周期验收：不落盘、退出清理 `syncedEntityIds` / anchors、断线释放 |

### 5.2 上一轮已处理，需回归确认

| 问题 | 状态 |
|---|---|
| 假人 `connection.teleport()` ACK 累积 | 已改为 `resetPosition()` + `ChunkSource.move()`，回归确认 |
| `BiomeAmbientSoundsHandlerMixin` lambda 方法名硬编码 | 已删除 lambda 硬编码，回归确认 |
| `PlayerListMixin` 用字符串判断假人 | 已改 `instanceof CameraFakePlayer`，回归确认 |
| `PreloadRequester.releaseIfNeeded()` 魔法值 | 已改常量，回归确认 |
| 脚本结束后输入未恢复 | 已修复 `blockKeyboard/blockMouse` 复位，回归确认 |

### 5.3 与本设计无关但仍是 0.3.5 已知问题

| 问题 | 说明 |
|---|---|
| Fabric/Forge 触发器持久化不一致 | Forge 用 `TriggerStateStore` 落盘，Fabric 只有内存；需单独决定补 Fabric 持久化或文档接受差异 |
| 第 6 轮文档/回归/发布未做 | CHANGELOG 仍停在 0.3.4，无 0.3.5 release |

---

## 6. 验收标准

1. 相机区区块由假人原版加载，不再有手动相机区 ticket / 手动区块补发代码。
2. 区块包与实体包同序到达，Fabric/Forge 客户端实体数量接近服务端 64 格范围内实体数。
3. far 模式：相机远时客户端能看到相机区画面与实体；返回玩家时玩家区快速恢复、无空洞、无幽灵实体。
4. 玩家区不因 far 模式被卸载。
5. Prewarm：下一 clip 区块提前加载，跳转后无缝。
6. 全部预加载配置可在 Forge/Fabric 配置中持久化。
7. 脚本结束/退出/断线：假人、票券、转发记账、客户端中心全部清理。
8. `ChunkPreloadManager` 无空实现/死代码；`scanChunk` 相关代码与文档均标记为历史方案。
9. `gradlew build` 全平台通过。

---

## 7. 实施顺序

1. 更新文档：本文定稿后，将 `chunk-preload.md` 标记为历史方案，`camera-region-mechanics-5.5.md` 与本文合并/互相引用，`1.md` 进度同步。
2. 清理代码：删除手动相机区 ticket / 手动补发 / scanChunk 残留；`ChunkPreloadManager` 瘦身。
3. 修复 Forge 实体数量偏少：先加转发日志定位，再修 Bundle/顺序/引导差异。
4. 配置持久化：`ConfigProvider/ConfigValues` 扩展全部预加载字段，Forge/Fabric 接入。
5. 协议版本保护：`C2SPreloadRequestPacket` 加版本字段或兼容读取。
6. 回归：far/near 切换、prewarm、多人同区域、断线清理、Forge/Fabric 实体数量、配置读写。

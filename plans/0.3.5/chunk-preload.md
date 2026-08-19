# 区块预加载（Chunk Preload）：利用原版机制节流的相机画面加载

> **v4 整体方案（2026-08-19 定稿，覆盖下文方案细节）**
> 目标：相机超视距也能加载画面；玩家区最小稳定加载不卸载；不拉大视距。
> - **门控**：仅当 `|相机块 − 玩家块| > farViewCenterThreshold` 才启用全套；近程零介入
> - **A. CameraWindow（已有）**：相机窗口 ticket + 就绪补发 + 释放
> - **B. PlayerZoneGuard（新增）**：对玩家所在块挂小块票券区（radius 4~8）→ 玩家服务端稳定加载、不卸载（流式半径收敛为可选优化）
> - **C. ViewCenterOverride（新增）**：客户端缓存中心由服务端 `ClientboundSetChunkCacheCenterPacket` 指定——相机远时中心=相机（补发包落进 inRange）、近/返回时回玩家块
> - **声音**：走客户端相机 listener 的声音包，不依赖区块在客户端缓存 → 远镜头玩家区声音照常在
> - **边界**：客户端单主渲染区；相机远时玩家区画面不渲染（被镜头替代），返回小范围快速重下（服务端未卸）
> - 配置：preloadEnabled / meta.preload / farViewCenterThreshold / playerZoneRadius / windowRadius / maxChunks / 配额 / 超时 / reportInterval / prewarm(待用)

**版本**: 0.3.5
**类型**: 新功能（2026-08-13 设计定稿）
**状态**: 📋 设计定稿，待实施
**关联**: `plans/0.4.0/camera-chunk-preload.md`（旧草案，本设计取代之）；与 `audio-listener-model.md`（声音线）构成 0.3.5 的"画面 + 声音"，互不依赖
**非侵入性**: 只调用原版公开 API（`ServerChunkCache.addRegionTicket/removeRegionTicket/hasChunk`、`ChunkScanAccess.scanChunk`、构造 `ClientboundLevelChunkWithLightPacket` 补发），不 Mixin、不持久化、不 forceload

---

## 一句话定义

运镜时相机可能飞出玩家已加载范围。本功能在播放开始/clip 切换前，**先扫描目标区块的磁盘记录分流**（内存命中 → 零成本；磁盘已生成 → 读盘快路径；未生成 → worldgen 预算受限），再按需用原版 ticket 加载；播放中滑动窗口跟随相机增量维护，就绪即补发区块包给玩家——**全程利用原版加载机制自身的节流特性**（磁盘记录、读盘/生成两路径、调度自节流、区块包共享），把加载开支降到最低，确保画面显示。

## 决策记录（2026-08-13）

| # | 决策 | 内容 |
|---|------|------|
| 1 | 最小实现 | 不做大改：预加载 + 滑动窗口 + clip 预热 + 磁盘分流节流 |
| 2 | 零开销优先 | 存量脚本（玩家已加载区内）完全不受影响——前置检测先行 |
| 3 | 极远距离 | 相机可距玩家几千区块——画面由滑动窗口跟随保证；未探索区域受预算/超时约束，渐进出现 |
| 4 | **节流 = 磁盘分流** | 核心机制：`scanChunk` 只读磁盘 Status 分流——已生成（读盘快）与未生成（worldgen 贵）分开处理，分别配额与超时 |
| 5 | 简化 | 砍掉朝向扇形采样、升级档 ticket（radius=1 一步到位）、forceTicks、实体 tick 档（radius=2，生物不做）；区块包补发构造一次、多玩家共享 |
| 6 | **开关分层（2026-08-19 用户确认）** | 两处开关：① 全局 `Config.preload_enabled`（服务端，默认 true，**服务端强制执行**——防客户端刷请求）；② 脚本级 `meta.preload: false`（可选，默认跟随全局，作者对"不出加载区/已预生成"脚本关掉省开销）。客户端 `PreloadRequester` 在任一开关关闭时根本不发请求（省流量），服务端再兜底强制 |

---

## 原版机制要点（1.20.1 源码确认）——本设计节流的基础

**1. 两条加载路径（成本差一个量级）**：

| 路径 | 流程 | 成本 |
|---|---|---|
| 读盘（已探索） | `scheduleChunkLoad`：异步 IO → 反序列化 | 几十 ms/块 |
| 生成（未探索） | `scheduleChunkGeneration`：worldgen 各状态 | 0.5~2s+/块，吃 CPU |

**2. 磁盘记录只读扫描**：`level.getChunkSource().chunkScanner().scanChunk(pos, collector)`——**不加载区块**，只读 NBT 的 Status 字段（`CollectFields`），毫秒级异步。已生成 = `"minecraft:full"`。**这是分流的关键工具**（Chunky 同款技巧）。

**3. ticket 分级**（`addRegionTicket(radius, level)`）：

| 参数 | level | 效果 | 用途 |
|---|---|---|---|
| radius=1 | 32 | 3×3 FULL + **BLOCK_TICKING（区块包下发时点）** | 画面所需（本设计唯一档位） |

**4. 区块包下发按玩家视距**：区块提升到 ticking 时，原版只给"视距内玩家"（`getPlayers` → playerMap）发 `ClientboundLevelChunkWithLightPacket`。玩家视距外的相机区域**必须主动补发**。补发照原版模式：**包对象构造一次（压缩 section+光照最贵），多玩家共享，各自 send**（`prepareTickingChunk` 的 `MutableObject` 缓存写法）。

**5. 调度自节流**：worldgen/光照/主线程独立 mailbox + 优先级队列——批量请求不会打爆，但会排队延后（所以未生成要配额限制 + 超时兜底）。

**6. 服务端加载全局共享**：任何来源的 ticket 维持区块加载；其他玩家在场 = 前置检测命中；其他玩家曾探索 = 磁盘记录命中（读盘）。多人天然节流。

**7. 1.20.1 `trackChunk` 无"已发送记录"**（只 `connection.send`）——补发需自己记账（已发集合、离开时 `ClientboundForgetLevelChunkPacket`）。

---

## 方案总览

```
预加载请求（播放前 / prewarm）
  → 阶段 1 扫描：内存命中跳过；batch scanChunk 读磁盘 Status 分流（异步，毫秒级）
       ├─ 内存已加载   → 跳过（零成本）
       ├─ 磁盘已生成   → 读盘快路径（全部请求，轻配额）
       └─ 未生成       → worldgen 慢路径（max_worldgen_chunks 硬配额，超出不请求）
  → 阶段 2 请求：addRegionTicket(radius=1) 按分类分级超时（已生成 2s / 未生成 15s）
  → 阶段 3 就绪：每 5 tick 轮询 3×3 hasChunk → 就绪 → 补发区块包（构造一次多玩家共享）
       超时 → 不放弃：降为低频轮询（20 tick），就绪了照样补发 → 画面渐进完整

播放中 → 客户端周期上报相机位置（C2S，20 tick）→ 服务端维护滑动窗口
  → 窗口差集：新进入区块先查扫描缓存/scanChunk 分类 → addRegionTicket
             离开窗口 → removeRegionTicket；离开相机区域 → 发 ForgetLevelChunk 给玩家

clip 切换（prewarm）→ 提前 prewarm 秒对 clip B 初始位置走同一套三阶段流程

结束/跳过/打断/退出 → C2S 释放 → removeRegionTicket 全部 + 清理补发记账
```

---

## 详细设计

### 1. 磁盘分流（核心节流）

- 工具：`scanChunk`（只读 Status）+ `CollectFields(new FieldSelector("Status", StringTag.TYPE))`
- 批量：目标区块集一次性并发发起（CompletableFuture），每块毫秒级
- **扫描缓存**：会话内 `(dimension, chunkPos) → 已生成/未生成` 结果缓存（磁盘状态会话内稳定）——同区域多人过场/滑动窗口反复查询只扫一次；上限（如 4096 条）LRU 淘汰
- 内存命中检查优先于 scanChunk（`hasChunk` 全绿直接零成本路径，不碰磁盘）
- 日志：`已生成 N 块（读盘）/ 未生成 M 块（worldgen）/ 跳过 K 块（已加载）`——作者可判断脚本路径是否被探索过

### 2. 分级请求与配额

| 分类 | 请求策略 | 超时 |
|---|---|---|
| 内存已加载 | 不请求（零成本） | — |
| 磁盘已生成 | 全部请求（读盘便宜；仍受 max_chunks 总量约束） | `timeout_generated`: 2s |
| 未生成 | 按 `max_worldgen_chunks` 配额（默认 64）请求，超出部分**不请求**（记录日志，滑动窗口/后续补） | `timeout_worldgen`: 15s |

- 总量约束：单次请求 ≤ `max_chunks`（默认 256），按"已生成优先、未生成配额内"排序截断
- 超时后**不放弃**：ticket 保留，降频轮询（每 20 tick），就绪后照样补发——画面渐进完整

### 3. 就绪判定与区块包补发

- 就绪 = 目标区块及其 3×3 邻域 `hasChunk` 全绿（BLOCK_TICKING 提升必然完成——源码确认层级链）
- 就绪后：构造 `ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null)`（**一个任务一个包对象，多玩家共享**）→ 对播放脚本的玩家 `connection.send`（照原版 trackChunk 语义）+ **自己记账**（已发集合）
- 离开相机区域/窗口收缩 → 已记账区块发 `ClientboundForgetLevelChunkPacket` + 记账移除（防止客户端内存堆积与过期数据）
- 轮询在主线程 tick 事件中进行，不阻塞；`getChunkFuture` 禁用（会 managedBlock 卡 tick）

### 4. 滑动窗口（播放中）

```
窗口中心 = 相机上报位置；窗口 = 中心 ± window_radius（radius=1 整圆，统一档位）
每 tick：差集 → toRemove（removeRegionTicket + forget 包）/ toAdd（扫描分类后 addRegionTicket）
```

- radius=1 统一：画面需要 BLOCK_TICKING（区块包下发），实体 tick 档（radius=2）不需要（生物不做）——**砍掉升级档与实体档，加载量最小**
- 上报间隔 20 tick（1s），位置滞后对窗口半径（默认 2 区块）容忍

### 5. 多人共享

- 区块包：同一区域多个过场玩家 → 包对象构造一次、各自 send（照原版 MutableObject 模式）
- 扫描缓存共享：同区域重复请求只扫一次
- 区块加载共享：其他玩家的 ticket / 磁盘记录自动命中前置检测
- 补发记账按玩家独立（各自已发集合）

### 6. 网络（2 个包，复用 AckTracker/NetworkGuard）

| 包 | 方向 | 内容 |
|---|---|---|
| C2S `PreloadRequestPacket` | 客户端→服务端 | 脚本 id + 位置（区块坐标）+ 模式（PRELOAD/PREWARM/RELEASE） |
| S2C `PreloadResultPacket` | 服务端→客户端 | 仅"无需加载"回执（日志用，不阻塞播放） |

位置上报复用同一通道（或独立小包，字段：位置+维度）。

### 7. 配置

| 配置项 | 默认 | 说明 |
|---|---|---|
| `preload_enabled` | true | **全局总闸（服务端强制）**；脚本可 `meta.preload: false` 单独关闭（默认跟随全局） |
| `window_radius` | 2 | 滑动窗口半径（区块） |
| `max_chunks` | 256 | 单次请求区块总量上限 |
| `max_worldgen_chunks` | 64 | 未生成区块硬配额（防卡服） |
| `timeout_generated` | 2 | 已生成区块就绪超时（秒） |
| `timeout_worldgen` | 15 | 未生成区块就绪超时（秒），超时降频轮询不放弃 |
| `prewarm` | 2.0 | clip 切换提前量（秒，脚本 meta 可覆盖） |
| `report_interval` | 20 | 相机位置上报间隔（tick） |

---

## 已知限制与建议

- **未探索区域**：worldgen 受 max_worldgen_chunks 配额约束，超出部分延迟出现（渐进）——长距离路径建议作者预先探索或用 Chunky 预生成（扫描日志可直接显示哪些区块未生成）
- **补发区块为静态快照**：方块/光照更新广播按玩家位置（playerMap），补发的视距外区块不接收增量更新——地形画面稳定，动态方块变化不显示（可接受）
- **实体/其他玩家不可见**：实体跟踪按玩家位置，镜头区域无实体渲染（生物加载不做，已知限制）
- **与 Chunky 等加载模组**：各自自定义 TicketType 隔离，天然共存；Chunky 预生成 = 我们的磁盘分流直接命中读盘
- **1.20.1 无区块已发送记录**：补发记账自维护（见 §3）

## 维度字段（clip 级 `dimension` 声明）— 2026-08-13 确认纳入 0.3.5

> 0.4.0 README 原计划"区块预加载 + 维度字段一起做"；本设计定稿时曾遗漏该待定项，现确认：**维度字段纳入 0.3.5**，与区块预加载一并落地。跨维度运镜（F 类，`plans/0.4.0/camera-queue-pip-dimension.md`）仍排 0.4.0，消费本字段做维度切换。

**脚本格式**（CAMERA clip 级，预留字段）：

```json
{ "start_time": 0, "duration": 10, "dimension": "minecraft:overworld" }
{ "start_time": 10, "duration": 12, "dimension": "minecraft:the_nether" }
```

**0.3.5 范围（基础处理）**：
- schema 支持 clip 级 `dimension`（缺省 = 玩家当前维度）
- 播放校验：脚本 CAMERA clip 声明的维度 ≠ 玩家当前维度时，日志提示（不做自动切换——切换是 0.4.0 F 类）
- 预加载按维度管理的基础：ticket 请求带维度（各 ServerLevel 独立 chunkSource，天然隔离）——本版本仅单维度生效

**0.4.0 范围（F 类）**：维度切换流程（黑幕 → 目标维度预加载 → 切换）、跨维度 ticket 管理。

---

## 实施顺序

1. 网络层：C2S 预加载请求/释放包 + 位置上报（复用 AckTracker/NetworkGuard 模式）
2. 服务端 ChunkPreloadManager：扫描分流（scanChunk + 缓存）→ ticket 管理 → 轮询就绪 → 区块包补发（核心）
3. 客户端 PreloadRequester：初始窗口请求 + 滑动窗口位置上报 + prewarm 时机
4. 配置接入（config + schema + 编辑器属性面板）
5. 冒烟测试：已加载区脚本（零开销路径）、磁盘已生成远距离（读盘 + 快速就绪 + 补发）、未探索区域（worldgen 配额 + 降频轮询渐进完整）、多人同区域（包共享 + 扫描缓存）、clip 切换预热

## 与旧文档关系

- `plans/0.4.0/camera-chunk-preload.md`：被本设计取代
- 本设计相对中间简化版的增量：**磁盘分流（scanChunk + 分级超时 + 未生成配额）恢复为核心机制**（用户要求利用磁盘记录节流）；砍掉实体 tick 档与升级档；补发记账/渐进完整明确化

## 执行前再看 / 具体方案

- **MC 源码**（已抽取到 `build/mc-sources/`）：
  - `server/level/ServerChunkCache.java`：`addRegionTicket/removeRegionTicket/hasChunk/chunkScanner`。
  - `world/level/chunk/storage/ChunkScanAccess.java`：`scanChunk(ChunkPos, StreamTagVisitor)` 返回 `CompletableFuture<Void>`。
  - `nbt/visitors/CollectFields.java` + `FieldSelector`：`new CollectFields(new FieldSelector(StringTag.TYPE, "Status"))` 后 `getResult()` 取 Status。
  - `server/level/ChunkMap.java`：`prepareTickingChunk`（3×3 FULL + `MutableObject<ClientboundLevelChunkWithLightPacket>` 多玩家共享）、`playerLoadedChunk`（`new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null)` + `trackChunk`）。
  - `network/protocol/game/ClientboundLevelChunkWithLightPacket.java`、`ClientboundForgetLevelChunkPacket`（客户端 `ClientPacketListener.handleForgetLevelChunk` → `level.getChunkSource().drop(pos)`）。
- **外部参考**：
  - `pop4959/Chunky`：`forge/fabric/neoforge` 三平台 `ForgeWorld/FabricWorld/NeoForgeWorld` 用 `scanChunk + CollectFields(Status)` 判断磁盘已生成/未生成——**同款技巧**。
  - `Moulberry/Flashback`：回放相机用自定义 `addRegionTicket` 加载区块并处理区块包。
- **项目文件**：`trigger/network/NetworkHandler.java`（注册新包）、`AckTracker.java`/`NetworkGuard.java`（可靠发送模式）、`command/CinematicCommand.java`（服务端推送入口）、`Config.java`（新配置项）。
- **执行时再看**：以上 MC 源码 + 项目网络层；先网络包，再服务端 ChunkPreloadManager，再客户端 PreloadRequester。

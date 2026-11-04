# H-3 轮项目地图与共享契约（主代理）

> 建立时间：2026-11-04。依据：本轮任务书 H-3（H10 + H1）+ `plans/0.3.6/H-other/entity-liveness.md` + 源码核对。
> 行号仅供定位，以类名 / 方法签名为准。`docs/` 已过时，只作线索；冲突以设计文档与源码为准。
> 前置轮次：F8 跨维度相机、I-3 相机 lane（均已完成）。不依赖 I-4。

---

## 〇、范围口径（本轮开工前确认）

| # | 项 | 口径 |
|---|---|---|
| 0.1 | H1 区域同步 | **用户 2026-11-04 决定暂不做**，`H-other/region-sync.md` 已删除；本轮只做 H10 |
| 0.2 | H12 / TrackType 注册表化 | **不在本轮**：其任务书明写「依赖 H-1 / H-2 / H-3 完成」；H-3 在本轮结束时才完成，H-4 归下一轮 |
| 0.3 | 服务端镜像注入点 | `ChunkMap$TrackedEntity.broadcast(Packet)` TAIL + `broadcastRemoved()` HEAD；不碰 `seenBy`、不调 `sendChanges()` |
| 0.4 | 实体流粒度 | 按 `(玩家 UUID, 目标维度)` 共享；视图只是订阅者；同一 `(玩家,维度,实体)` 只配对一次 |
| 0.5 | 初始配对 | 沿用 `ServerEntity.sendPairingData`；只在流内首次配对时调用，不持有第二个 `ServerEntity` |
| 0.6 | 客户端 tick | 只调目标 `ClientLevel.tickEntities()`，不调 `ClientLevel.tick()`；不 tick 区块 / 时间 / 天气 |
| 0.7 | tick 顺序 | 客户端包处理（`Minecraft.execute` 落在 `runAllTasks`）→ `END_CLIENT_TICK` 里的 `DimensionViews.tick` → 渲染 |
| 0.8 | 异常隔离 | 服务端镜像单包异常只丢本包；客户端单视图 tick 异常只跳该视图本 tick，连续失败停 tick |
| 0.9 | 不做 | 不做假玩家、不改 AI goal / `getNearestPlayer` 语义、不做真·连续时间、不重开 F8 语义 |
| 0.10 | 验证资产 | 探针放仓库外 `E:/tmp/h3/`；实机夹具临时放 `fabric/run/immersive_cinematics/scripts/`，测完还原 |

---

## 一、任务清单

| 编号 | 目标（一句话） | 验收 | 依赖 | 文件所有权 |
|---|---|---|---|---|
| **H3-1** | 服务端按 `(玩家,维度)` 镜像原版 `TrackedEntity.broadcast` 实体包，视图订阅 + 引用计数 + 移除 | `:common:compileJava` 通过；探针 `EntityStreamProbe` PASS（配对一次 / 多视图共享 / 最后一个订阅者移除 / 移除清理） | — | `CameraViewManager.java`、新建 `CameraEntityStream.java`、新建 `ChunkMapCameraEntityMirrorMixin.java`、`immersive_cinematics.mixins.json` |
| **H3-2** | 客户端对目标 `ClientLevel` 调 `tickEntities()`，逐视图异常隔离 | `:common:compileJava` 通过；探针 `ViewTickProbe`（失败三次停 tick / MAIN 跳过）；实机 `lerpSteps` 递减 | — | `DimensionView.java`、`DimensionViews.java`、`ClientEventHandler.java` |
| **H3-3** | 探针与 fixture：服务端 `tickCount` / `inEntityTickingRange` / `noActionTime`；客户端 `lerpSteps` / `tickCount`；跨维度 fixture | 探针全 PASS；fixture 可被 `/icinematics play` 加载 | H3-1, H3-2 | `E:/tmp/h3/*`（不入库）；`fabric/run/...`（临时） |
| **H3-4** | 实机：同维度 F6、跨维度 F8、末影龙、多视图、断线/重播 | 日志 + 截图；无冻结姿态；无幽灵实体 | H3-1..H3-3 | `H-other/entity-liveness.md` §六、`H-other/README.md`、`I-BACKLOG.md` |

### 依赖图

```
H3-1 ─┐
      ├─→ H3-3 ─→ H3-4
H3-2 ─┘
```

### 派单波次

| 波次 | 任务 | 说明 |
|---|---|---|
| 1 | H3-1、H3-2 | 并行；子代理不编译（避免抢 gradle 锁），主代理统一编译 |
| 2 | 主代理编译 + 修复 | `:common:compileJava`；失败按「补齐信息重派」 |
| 3 | H3-3 | 探针 + fixture；主代理独立复跑 |
| 4 | H3-4 | 实机 + 文档回写 + 移交落盘 |

---

## 二、现状与关键符号

### 2.1 服务端（`CameraViewManager`）

| 符号 | 位置 | 现状 |
|---|---|---|
| `ViewKey(UUID player, String viewId)` | `trigger/server/CameraViewManager.java:620` | 视图身份 |
| `ViewState` | 同文件 `:622-643` | `sentEntities: IntSet`、`level`、`dimension`、`pos`、`player` |
| `handle(C2SCameraViewPacket, ServerPlayer)` | `:94` | 建视图 / 换维度 / 释放 |
| `tick(MinecraftServer)` | `:158` | 超时 / 卸载 / 玩家进维度释放；`sendChunks` 每 5 tick、`sendEntities` 每 10 tick |
| `sendEntities(ViewState, long)` | `:406` | 只 `sendPairingData`，无持续同步 |
| `releaseStream(ViewState, String)` | `:241` | 补发 forget / remove；`otherViewHasEntity` 去重 |
| `sendEnvelope(ViewState, Packet<?>)` | `:504` | `private`；编码原版包进 `S2CCameraViewPacket` |
| `otherViewHas*` | `:466/:478/:490` | 仅删除去重；发送侧不去重 |

### 2.2 原版注入点（MC 1.20.1）

| 符号 | 事实 |
|---|---|
| `ChunkMap$TrackedEntity.broadcast(Packet<?>)` | `public`；只遍历 `seenBy` 的 `ServerPlayerConnection` |
| `ChunkMap$TrackedEntity.broadcastRemoved()` | `public`；对 `seenBy` 逐个 `removePairing` |
| `TrackedEntity.entity` | 包私有 `final Entity`；mixin 用 `@Shadow` |
| `ClientLevel.tickEntities()` | `public`；遍历 `tickingEntities` → `tickNonPassenger` → `tickBlockEntities` |
| `ClientLevel.tickNonPassenger(Entity)` | `public`；`setOldPosAndRot` / `tickCount++` / `tick()` |
| `tickingEntities` 填充入口 | `ClientLevel.onChunkLoaded` ← `ClientChunkCache.replaceWithPacketData` |

### 2.3 客户端

| 符号 | 位置 | 现状 |
|---|---|---|
| `DimensionViews.VIEWS` | `client/dimension/DimensionViews.java:54` | 维度 id → 唯一 `ClientLevel` |
| `DimensionView.level()` | `client/dimension/DimensionView.java:69` | 可空；MAIN 为 null |
| `DimensionViews.closeAll()` | `:282` | 仅世界退出 |
| `ClientEventHandler.onClientTick` | `handler/ClientEventHandler.java:76` | `CameraViewReporter.tick` 在 `:99`；无 `DimensionViews.tick` |
| `CameraViewPacketRouter.handle` | `trigger/client/CameraViewPacketRouter.java:48` | 客户端主线程；包进目标 `ClientLevel` |

### 2.4 探针与配置

| 符号 | 位置 | 用途 |
|---|---|---|
| `Config.debugLogging` | `Config.java:52` | 日志门控（运行目录当前 true） |
| `ErrorLog.logRateLimited` | `util/ErrorLog.java:42/55` | 限频日志 |
| `CameraCenterDiagnostics` | `trigger/server/CameraCenterDiagnostics.java` | 会话式探针样板（20 tick 打印） |
| `ClientEntityProbe` | `camera/ClientEntityProbe.java` | 客户端实体可见性探针样板 |
| 探针类路径模板 | `E:/tmp/h2/run-hud.sh` | `common/build/classes` + gradle 依赖 + MC merged jar |

---

## 三、缺口分类

### 3.1 本轮做

- 服务端实体包镜像（含初始配对去重、移除、多视图引用计数、按 `(玩家,维度)` 共享）。
- 客户端目标 `ClientLevel.tickEntities()` + 每视图异常隔离。
- 服务端 / 客户端探针与实机记录。
- 文档回写与移交落盘。

### 3.2 移交后续轮次

| 事项 | 归属 | 关闭条件 |
|---|---|---|
| H12 对外接口 + `TrackType` 注册表化 | H-4（下一轮） | 按其任务书验收；依赖本轮 H10 完成 |
| `noActionTime` 在 32–64 格停游走 | 未排期（原版 AI 口径） | 产品要求 64 格内持续游走时另立「相机观察者半径」 |
| 模组私有实体包镜像 | 未排期 | 模组绕过 `ServerEntity` 时提供扩展 API |
| 包合并 / 批量信封 | 性能数据触发 | 实测包量过高时再做 |

### 3.3 契约边界

- 只镜像原版 `ServerEntity` 标准链；模组私有包不支持。
- 不承诺方块 / 光照 / 声音 / 天气 / 粒子的完整同步。
- `FABULOUS` 仍拒绝目标视图。
- 同维度 F6 不走 `EntityStream`（原版跟踪 + 主 `ClientLevel` tick 已覆盖）。

---

## 四、验证约定

| 类别 | 约定 |
|---|---|
| 编译 | `sh gradlew :common:compileJava --offline --no-daemon`；平台侧改动追加 `:fabric:compileJava` / `:forge:compileJava` |
| 无头探针 | `E:/tmp/h3/*.java`，类路径模板抄 `E:/tmp/h2/run-hud.sh` |
| 实机 | `tools/devtools/.venv/Scripts/python.exe tools/devtools/devtools.py`：`client` / `cmd` / `tail` / `trace` / `shot`；启动前 `jps -l` 确认无残留 Minecraft |
| 提交 | `feat(0.3.6): H-3 相机视图实体活性`；不 push |

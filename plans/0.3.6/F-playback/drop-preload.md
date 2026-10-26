# 摘掉预加载与预热

> 状态：已实现（2026-10-25）
> 范围：客户端相机位置上报、服务端相机虚拟中心与锚点的驱动模块，以及它们在配置、协议、文档里的命名与残留
> 关联：[相机中心实体](camera-center-entities.md)、[并行播放](parallel-playback.md)、[多相机渲染](multi-camera-rendering.md)、`docs/modules/trigger.md`、`docs/modules/network.md`、`docs/modules/core.md`

## 一、摘要

项目里以「预加载 / 预热」命名的东西已经不加载任何区块。真正让区块跟着相机走的是原版 `ChunkMap` 的虚拟相机中心差集；这个模块只负责把相机位置上报给服务端、写入中心、结束时释放。本计划把这个概念整体摘掉：模块改名为相机中心同步，删除 10 个 `preload*` 配置键、预热模式、回执包与无用协议字段，同时改写文档与编辑器类型里的表述。区块加载仍然只有原版一条路径。

## 二、为什么做

### 2.1 问题

- **名字与行为不符**。模块叫预加载，实际不申请 ticket、不自己做差集、不决定加载半径；读代码的人会以为这里还有一套预取调度。
- **大部分配置是死的**。`preloadMaxBurstPerTick`、`preloadMaxRequestsPerTick`、`preloadRadiusCap`、`preloadForceRadius`、`preloadForceRadiusValue` 在虚拟中心方案里没有消费方；`preloadPrewarmLeadSeconds`、`preloadPrewarmRadius`、`preloadPrewarmRequestsPerTick` 只被一条服务端直接忽略的分支引用。
- **预热是空转**。客户端按提前量算下一片段首帧位置并发出预热请求，服务端收到后立刻返回，什么也不做。这段逻辑每 tick 都在跑，产出为零。
- **总闸会打断相机中心**。`preloadEnabled=false` 时客户端发释放、服务端拒绝一切位置上报，虚拟中心与锚点随之失效。它名义上是「关掉预加载」，实际是「关掉相机中心」。
- **协议有冗余**。回执包只打一行日志；请求里的半径、朝向、渲染距离服务端都不读。
- **文档与类型仍在把预加载当现行能力**。模块文档、脚本格式说明、编辑器 meta 类型都还留着 preload 字样。

### 2.2 目标

- 代码里不再出现 `preload` / `prewarm` / `预热` 命名，能力统一叫「相机中心同步」。
- 区块加载只有一条路径：原版 `ChunkMap` 按虚拟相机中心做差集。
- 配置面只剩一个上报间隔，其余 `preload*` 键全部删除；旧配置文件里的这些键加载时忽略。
- 协议只剩「开始 / 结束」与「位置」两条消息；预热模式与回执包删除。
- 相机中心没有总闸：有活跃相机就同步，没有就交还原版。
- 模块文档、脚本格式说明、编辑器类型同步改写。

### 2.3 非目标

- 不改原版 `ChunkMap` 的差集、ticket 与发送范围行为。
- 不引入任何预取、预测性调度或 lookahead。
- 不改相机锚点、实体同步、刷怪判定的口径，只改它们的驱动入口归属。
- 不改性能诊断的采样口径；那里丢弃前若干秒是测量需要，与预加载无关，仅顺手把命名从 warmup 改为 settle，避免混淆。

## 三、方案

对脚本作者没有任何变化。脚本里本来就没有预加载开关，镜头飞到哪里，区块就跟到哪里；同维度、跨视距的运镜照旧。跨维度相机是 F8 的设计基线，未实现，不在本篇的“区块跟随”承诺内。

对服主只有一处变化：配置里不再有 preload 那一组键，只剩一个「相机中心上报间隔」。旧的配置文件里写了这些键也不会报错，只是不再生效。

对调试者：日志前缀从 preload 改成 camera-center，不再出现「预加载全局已关闭」。相机中心是否生效只看一件事——当前维度有没有 CAMERA lane（目标维度预取景 lane 不算，它们由跨维度 view 流单独上报）。

边界仍然清晰：区块加载完全由原版负责；相机中心只替换「中心」这一个入口，不改加载半径、不预测下一个片段、不接管客户端区块缓存。

## 四、技术细节

### 4.1 核心模型

保留三件东西：

- **相机中心同步**（原预加载模块）：客户端 tick 上报位置，服务端写入中心与锚点，结束时释放。改名，职责不变。
- **虚拟中心状态**：原版差集与实体配对距离读取的中心，按玩家记录，切维度即失效。
- **相机锚点**：刷怪与消失判定查询用的纯坐标，不创建世界实体。

删除四件东西：

- 预热模式及其客户端的提前量计算、下一片段首帧坐标求值。
- 回执包（服务端→客户端，仅日志）。
- 10 个 `preload*` 配置键。
- 客户端「预载是否激活过」的查询入口（无调用方）。

### 4.2 契约

- 客户端在脚本播放、且脚本存在非空 CAMERA 轨道时开始上报；相机活跃时报相机位置，空档时报玩家位置；停止、结束、进预览、断线时释放。
- 服务端在收到开始时建立玩家状态并写入中心；收到位置时更新中心与锚点；收到结束或玩家断线时清理。
- 中心的写入时机与 `ChunkMap.move` 的调用点、线程归属不变，仍在服务端主线程。
- 释放无条件执行，不受任何配置门控；状态清理不能依赖结束包，断线与世界卸载同样要清。
- 没有总闸。删除总闸后，「关掉相机中心」不再是一个可配置状态。

### 4.3 数据形态

- 协议改名为相机中心请求与相机中心位置两条。请求只保留模式（开始 / 结束）、脚本 id、中心坐标、是否相机模式；位置只保留坐标与是否相机模式。半径、朝向、渲染距离、回执包一并删除。
- 配置只保留 `cameraCenterReportInterval`，默认 20 tick。旧键在加载时静默忽略，不迁移、不写回。
- 日志前缀统一为 `ImmersiveCinematics/CameraCenter`；调试日志仍受 `debugLogging` 控制。
- 脚本格式不变：`meta` 里没有、也不再有预加载字段。

### 4.4 边界

- 不碰 `ChunkMap` 的差集、ticket 与发送范围；不碰实体配对、刷怪、消失的判定。
- 不接管客户端区块缓存，不决定渲染距离。
- 不做跨维度预测，不提前加载目标维度。
- 与多相机渲染无关：那边没有预热代码，「重叠窗口」是时间轴现象，不是预取机制。
- 编辑器只改类型声明与文档表述，不改协议与界面。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 保留总闸，默认关闭 | 把 `preloadEnabled` 默认改成 false | 改动最小 | 关掉的是相机中心，不是预取；没有「只关预取、保留中心」的中间态 |
| 只删预热，其余不动 | 删 PREWARM 与提前量计算 | 局部改动 | 死配置、错误命名与冗余协议全部留下，读代码的人仍会被误导 |
| 改名为相机中心但保留总闸 | 命名对了，行为不变 | 文档改动小 | 总闸仍能打断中心，语义仍然错位 |
| 连相机中心一起删 | 回到「玩家不动、相机飞走」 | 彻底去掉这套模块 | 相机中心是已交付能力，删了会重新出现缺块与刷怪失效 |
| 保留回执包做诊断 | 继续用回执记录命中情况 | 少改一个包 | 回执只打日志，诊断价值由日志前缀替代 |

**本方案的缺点**：

- 改名同时动协议与配置键，旧客户端与旧配置文件不兼容。按版本原则可以接受，但两端必须同版本部署。
- 测试脚本与文档里的 preload 字样要一起改，改动面比「删几个键」大。
- 相机中心仍依赖客户端上报；客户端不发就退回原版。这是既有约束，本计划不解决。
- 删掉总闸后，出问题只能靠停播或断线来复位，少了一个应急开关。

## 六、实现结果

状态：已实现（2026-10-25）

**改动**

| 面 | 结果 |
|---|---|
| 命名 | `PreloadRequester` → `CameraCenterReporter`（客户端）；`ChunkPreloadManager` → `CameraCenterManager`（服务端）；日志前缀统一 `ImmersiveCinematics/CameraCenter` |
| 协议 | `C2SCameraCenterRequestPacket`（`camera_center_request`：模式 START / END、脚本 id、中心坐标、cameraMode）与 `C2SCameraCenterPositionPacket`（`camera_center_position`：中心坐标、cameraMode）；`S2CPreloadResultPacket` / `preload_result` 删除；请求里的半径 / 朝向 / 渲染距离字段删除。Forge / Fabric 同侧注册 ID 同步改名，无兼容层 |
| 配置 | 10 个 `preload*` 键与 `preloadEnabled` 总闸删除，只留 `cameraCenterReportInterval`（默认 20 tick）；旧 JSON 键加载时静默忽略，不迁移、不写回；`ConfigProvider.setFloat` 随唯一 float 配置一并删除 |
| 客户端 | 仅当脚本播放且存在非空 CAMERA 轨道时上报；当前维度有 CAMERA lane 时报该维度第一条（最底层 / 背景）lane 的相机位置，没有当前维度 lane 时报玩家位置并 `cameraMode=false`；目标维度预取景 lane 不参与（由 `CameraViewReporter` 按 view 上报）；按 `cameraCenterReportInterval` 上报，当前维度相机态翻转立即补发；停止 / 结束 / 进预览 / 断线释放。`tickPrewarm`（下一片段首帧坐标、提前量）与 `isPreloadActive()` 删除 |
| 服务端 | 无总闸；START 建状态 + 锚点 + 虚拟中心，POSITION 更新，END / 断线无条件释放；保留 `hasCameraTrack` 兜底与 F-1 的 `releaseOnFailure` 半状态清理 |
| 诊断 | `LaneDiagnostics` 的 `warmup` / `warmupSeconds` 改名 `settle` / `settleSeconds`（manifest 键 `settleSeconds`），采样口径不变 |
| 编辑器 | `editor/src/types.ts` 删除 `meta.preload` |
| 测试脚本 | `cinematics/0.3.6/tests/preload/` → `camera_center/`，三支脚本的 id / 名称 / 字幕 / 命令前缀与触发链 `requires` 同步改名 |

**验证（2026-10-25）**

- `sh gradlew :common:compileJava :fabric:compileJava :forge:compileJava --offline` 通过。
- 无头 validator（`cinematics/0.3.6/tests`）：166 支脚本，3 支已知问题（`test_camera_facing_origin_coordinate`、`test_camera_relative_axis` 两支历史问题 + `faults/bad_meta_int.json` 故障夹具），与改动前一致。
- 命名清理：模组功能命名在代码 / 配置 / 编辑器类型 / 测试内已清零（Electron 自身的 `preload.cjs` / `webPreferences.preload` 是框架 API，不属于本模块）。计划目录与历史记录里仍有旧名（`plans/complete/0.3.5/`、`_archive/`、`implementation-progress` 历史行、`docs/` 旧文档、CHANGELOG），按「历史记录不回写」保留；`camera-composition` / `multi-camera-rendering` 里的「叠化预热」是渲染时间轴概念，与本模块无关。

**未做（按设计边界）**：原版 `ChunkMap` 差集 / ticket / 发送范围、相机锚点与实体同步判定、多相机渲染的叠化窗口均未改动；旧客户端与旧配置文件不兼容，两端需同版本部署。

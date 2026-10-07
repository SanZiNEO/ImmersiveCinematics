# 0.3.6 区域同步 / 镜像传送：两张对应区域之间的坐标映射（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、公式、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [触发器类型](../../docs/TRIGGER_TYPES.md)
> - [脚本格式](../../docs/SCRIPT_FORMAT.md)
> - [反馈：on_enter 状态机问题](./feedback-0.3.5/05-on-enter-not-repeatable.md)

---

## 1. 定位（已确认）

**目标**：两张**一模一样**（或互为镜像 / 旋转）的区域，坐标一一对应；玩家在源区域（或越过“接缝”）时，按对应关系传送到目标区域的**对应坐标**——“站哪传哪”。

- 例：A 区的 9 块地板与 B 区的 9 块地板一一对应，站在哪块就传到哪块。
- 关键点：**相对位置保持**——传送后玩家在目标区域的相对位置、朝向、运动连贯，而不是被“拍”到一个固定点。

**用途**：循环楼梯（向上走 → 拐角后变成向下）、镜像空间、无限走廊、空间折叠（传送门式）等地图效果。

**不是**：通用传送门系统。不做实体 / 投掷物传送、跨维度、视角穿透（能看见对面空间）、非等比缩放。

---

## 2. 映射类型（要覆盖的所有情况）

| 类型 | 预期行为 | 备注 |
|---|---|---|
| 平移 | 目标 = 源 + 固定偏移；两区域同尺寸、同朝向 | 最简单，先支持 |
| 镜像（x / z / 任意竖直平面） | 坐标沿轴翻转；两区域同尺寸 | 需计算翻转坐标 |
| 旋转（90° / 180° / 270°，绕竖直轴；或绕区域中心） | 坐标绕轴 / 中心旋转 | 需计算旋转坐标 |
| 组合（平移 + 镜像 / 旋转） | 一般仿射变换 | 远期（先支持单一类型） |
| 高度差 | y 允许错位（上 / 下平移） | 并入平移 |
| 不同尺寸 / 缩放 | **不支持**——“一模一样”是前提，非等比缩放会破坏对应关系 | 不做 |
| 多区域链 | A→B、B→C、C→A 循环链（循环楼梯需要）；每段独立判定 | 需要 |
| 一对多 / 随机目标 | 待定（先按一对一） | 待定 |

---

## 3. 触发模式（要覆盖的所有情况）

- **进入即传送（一次性）**：进入源区域（或越过“接缝”）时映射一次；适合“拐角处藏传送”。
- **持续映射**：在源区域内每 tick 保持映射；适合传送门式手感（位置始终对应，没有“跳一下”）。
- **接缝精度**：映射发生得越接近接缝越不易被察觉；精度上限 = 服务端 tick（50ms）——这是“连贯感”的关键。
- **防乒乓**：传到目标后，不能被目标区域的规则立刻传回；需要“进入 / 离开”状态（状态机已修复，见相关文档 fb-05，0.3.6 已落地）。
- **循环链**：沿链走一圈回到起点应无缝；链中每段独立判定、互不干扰。

> **源码事实（2026-10-07 核查）**
> - MC 服务端 tick = 20 Hz / 50 ms（`MinecraftServer.runServer`：`this.nextTickTime += 50L`），所以 50 ms 是“位置能多快被服务端看到”的硬下限。
> - 客户端移动包每 tick 至多 1 个（`LocalPlayer.sendPosition`：位移 > 2e-4 或 `positionReminder >= 20` 才发包），服务端在服务器线程逐包处理（`ServerGamePacketListenerImpl.handleMovePlayer`，入口 `PacketUtils.ensureRunningOnSameThread`）。
> - 现有 location 触发器轮询默认 **20 tick = 1 s**（`Config.triggerPollIntervalLocation = 20`，`ImmersiveCinematics.registerTriggerTypes` 注册为 `ListenStrategy.POLLING`）；`TriggerEngine.onServerTick` 按 `tickCounter % interval` 分桶执行。即“每 tick 持续映射”无法复用 location 触发器轮询间隔，需独立于触发器轮询的每 tick 路径（`ServerEventHandler.onServerTick`）。
> - 传送期间服务端会**丢弃**客户端移动包：`ServerGamePacketListenerImpl.handleMovePlayer` 中 `if (this.awaitingPositionFromClient != null) { … return; }`（每 20 tick 重发一次传送），直到客户端回 `ServerboundAcceptTeleportationPacket`（`handleAcceptTeleportation`）才恢复。
> - 位置校验存在且与传送互相作用：`clampHorizontal`（±3.0E7）/`clampVertical`（±2.0E7）、`moved too quickly`（位移平方差 > 100·q，鞘翅 300·q，`q > 5` 的过频包被记日志并夹为 1）、`moved wrongly`（位移平方 > 0.0625）都会触发一次服务端 `teleport(...)` 拉回。

---

## 4. 传送后要保留什么（预期）

| 项目 | 平移 | 镜像 | 旋转 |
|---|---|---|---|
| 位置 | 整体平移 | 按轴翻转 | 绕轴 / 中心旋转 |
| 水平朝向（yaw） | 保持 | 可选：保持 / 同步镜像 | 可选：保持 / 同步旋转 |
| 俯仰（pitch） | 保持 | 保持 | 保持 |
| 运动惯性 | 保持（继续走） | 保持 | 保持 |

- 朝向两种策略都要能选：“同步变换”（连贯优先，空间整体翻过去）与“保持”（取景优先）。
- 默认建议（待实机验证）：平移 = 保持；镜像 / 旋转 = 同步变换。

> **源码事实（2026-10-07 核查）**：原版传送链路为 `ServerPlayer.teleportTo(...)` / `teleportRelative(...)` → `ServerGamePacketListenerImpl.teleport(x, y, z, yaw, pitch, Set<RelativeMovement>)`：服务端先 `player.absMoveTo(...)`，再下发 `ClientboundPlayerPositionPacket(x, y, z, yRot, xRot, relativeArguments, id)`，并置 `awaitingPositionFromClient`（客户端回 ACK 前服务端不处理移动包）。
> - 客户端 `ClientPacketListener.handleMovePlayer` 按 `relativeArguments` 决定语义：**相对 X/Y/Z** → 在当前位置上加增量并**保留 `deltaMovement`**（惯性保持）；**绝对** → `setDeltaMovement(0, 0, 0)`（惯性清零）。
> - **相对 Y_ROT / X_ROT** → `setYRot(getYRot() + delta)` / `setXRot(getXRot() + delta)`（相对旋转语义）；**绝对** → 直接设为包内值。
> - `ServerPlayer.teleportTo(x, y, z)` 传 `RelativeMovement.ROTATION` 且 yaw/pitch 取当前值 ⇒ 下发的旋转增量恒为 0 ⇒ 客户端朝向不变（“保持朝向”是原版 `teleportTo` 的既有结果）；`teleportRelative` 传 `RelativeMovement.ALL` ⇒ 位置与旋转都按相对量、惯性保留。要强制绝对朝向需传空集合（绝对 yaw/pitch）。
> - `teleportTo(ServerLevel, ...)` 会先 `stopRiding()`（下车）并 `setYHeadRot(yaw)`；`connection.teleport(...)` 本身**不校验落点安全性**（不做实心 / 悬空 / 液体检查）。

---

## 5. 边界与异常（预期行为）

- **落点不安全**（实心方块 / 悬空 / 液体）：默认照做（作者负责建图）；是否提供安全检查选项待定。
- **玩家下线 / 上线**：不做跨会话记忆；上线后按当前位置重新判定。
- **多人**：每个玩家独立映射，互不影响。
- **实体 / 投掷物 / 载具**：不做（只处理玩家）；载具中的玩家待定。
- **维度**：仅同维度；跨维度不做。
- **区域重叠 / 嵌套**：多条规则同时命中时的优先级待定（建议显式优先级，或“最具体者优先”）。
- **与相机脚本并存**：互不冲突——传送可以和镜头脚本、事件、触发器一起用。

> **源码事实（2026-10-07 核查）**
> - 现有触发器**已有跨会话持久化**：`TriggerStateStore` 按玩家写 `immersive_cinematics/trigger_state/<uuid>.snbt`（`initialize` / `loadForPlayer` / `saveIfChanged` / `unloadForPlayer` / `saveAll`），存的是“已触发 / 已开始 / 已播完”脚本状态；`ServerEventHandler.onPlayerJoin/onPlayerQuit` 负责加载 / 保存卸载。“不做跨会话记忆”是区域同步自身的取舍，不是现有体系的限制。
> - 防乒乓状态机（`TriggerEngine.enterStates`）是**内存态**：`Map<UUID, Map<脚本:触发器, Boolean>>`，`onPlayerQuit` 不清除（同一服务端会话内重进仍保留），只在 `TriggerEngine.clear()`（启动 / `/icinematics reload`）时清空。
> - 多人：`TriggerEngine.onServerTick` 对 `server.getPlayerList().getPlayers()` 逐个玩家求值，状态按 UUID 分键，天然独立。
> - 载具：`ServerGamePacketListenerImpl.handleMovePlayer` 中 `if (this.player.isPassenger()) { absMoveTo(当前 X/Y/Z, 包内 yaw, 包内 pitch); … return; }`——乘客位置不由玩家移动包决定（由 `handleMoveVehicle` 处理载具）；`ServerPlayer.teleportTo(ServerLevel, ...)` 会 `stopRiding()`，而 3 参 `teleportTo(x,y,z)` 走 `connection.teleport` 不下车。
> - 落点安全：`connection.teleport` 只做 `absMoveTo` + 发包，无任何碰撞 / 安全校验（见 §4 核查）。

---

## 6. 实现形态（方向）

1. **模组特性（主方向）**：区域同步作为“区域规则”接入现有触发器体系（源区域 + 目标区域 + 映射方式 + 模式）；服务端每 tick 直接映射位置，精度与连贯性最好。
2. **远期**：任意仿射映射、多区域图、落点安全策略、编辑器可视化。

> 复杂度可控的原因：原理只是坐标映射；先做单一映射类型 + 一次性触发，就能覆盖循环楼梯这个主场景，其余按需加。

> **接入点源码事实（2026-10-07 核查）**
> - 触发器注册项 `TriggerRegistration` 已具备的字段：scriptId / triggerId / type / conditions / exitConditions / actions / repeatable / delayMs / onEnter / exitBuffer / requires；动作接口为 `TriggerAction.execute(ServerPlayer player)`（`trigger/server/action/TriggerAction.java`），现有动作实现只有 `StartPlaybackAction` / `StopPlaybackAction` / `PlaySoundAction` / `ExecuteCommandAction`。
> - location 类型 = `new TriggerType("location", ListenStrategy.POLLING, Config.triggerPollIntervalLocation, Evaluators::evaluateLocation)`；区域判定 = `Evaluators.evaluateLocation` → `inBox(x,y,z,corner1,corner2)`（AABB，含边界）或 `inRadius(position, radius)`；`exit_buffer` 外扩 = `Evaluators.expandConditions(conditions, buffer)`，仅在 `on_enter && exit_buffer > 0` 时由 `ScriptManager.registerAllTriggers` 预生成（corner1 减 buffer / corner2 加 buffer，点写反会变缩小）。
> - 服务端每 tick 入口已存在：`ServerEventHandler.onServerTick` ← Forge `TickEvent.Phase.END`（`ForgeEvents`）/ Fabric `ServerTickEvents.END_SERVER_TICK`（`FabricEvents`），当前内部依次调 `TriggerEngine.onServerTick` / `ScriptEventManager.onServerTick` / `CameraAnchorManager.tick`。
> - 现状：common 层**没有任何传送调用**（`teleportTo` / `teleportRelative` / `connection.teleport` 在 `common/src` 无匹配），区域同步属全新实现，无现成动作可复用。

---

## 7. 已确认项 / 待定项

**已确认**

- 原理 = 坐标映射，“站哪传哪”。
- 平移是最简单的映射类型（先支持）；镜像 / 旋转需要坐标变换计算。
- 防乒乓依赖“进入 / 离开”状态机（已知问题已修复：fb-05，0.3.6 落地，阻塞解除）。
  - 核查（2026-10-07）：修复已提交 `f9ae675`（2026-09-29，“fix(trigger): 修复 on_enter 每局只能触发一次（离开复位分支不可达）”）；`TriggerEngine.onServerTick` 现在**每轮轮询都调用** `checkEnterState(player, reg, inRegion)`（含区域外复位），`on_enter` 的离开 / 复位判定走 `reg.getExitConditions()`（`exit_buffer` 外扩区域）。该状态机位于 `TriggerEngine.enterStates`（内存 `Map<UUID, Map<String, Boolean>>`，键 = `scriptId:triggerId`），**不在** `TriggerStateStore`——后者持久化的是脚本“已触发 / 已开始 / 已播完”状态。

**待定**

- 接缝 / 触发精度（每 tick vs 沿用触发器轮询间隔）。
- 镜像 / 旋转下的朝向策略细节。
- 落点安全检查是否做。
- 多规则重叠时的优先级。
- 载具中的玩家是否处理。

---

## 8. 验收标准（草案）

- [ ] 平移：玩家站在区域 A 的任意一格，传送后落在 B 的对应格（相对位置误差 ≤ 1 格）
- [ ] 镜像 / 旋转：对应关系正确，朝向策略可切换
- [ ] 循环链：A→B→C→A 走一圈无缝，无乒乓
- [ ] 传送后能继续正常行走（惯性 / 输入不被吞）
- [ ] 与相机脚本、事件轨道、其他触发器无冲突

---

## 事实核查（2026-10-07）

核查依据：MC 1.20.1 sources.jar（`minecraft-merged-d95c7b3016-…-sources.jar`，解出至 `build/mc-src-tmp/`）、本仓库 `common/src` 源码、`git log`。

### ① 核实为真的断言

| 断言（原文） | 证据 |
|---|---|
| §3 “精度上限 = 服务端 tick（50ms）” | `MinecraftServer.runServer`：`this.nextTickTime += 50L;`（过载告警同样按 `l / 50L` 折算 tick） |
| §3 位置由服务端在 tick 上接收 / 校验 | `ServerGamePacketListenerImpl.handleMovePlayer`（`PacketUtils.ensureRunningOnSameThread` 入口）→ `clampHorizontal`(±3.0E7)/`clampVertical`(±2.0E7)、`moved too quickly`（`p - o > r*q`，`r`=100，鞘翅 300；`q>5` 记 `sending move packets too frequently` 并夹为 1）、`moved wrongly`（`p > 0.0625`）；`ServerGamePacketListenerImpl.tick()` 每 tick 执行 `resetPosition()` 并 `knownMovePacketCount = receivedMovePacketCount` |
| §3/§4 传送走 `connection.teleport` + 下发 `ClientboundPlayerPositionPacket` | `ServerPlayer.teleportTo(double,double,double)` → `this.connection.teleport(d,e,f,getYRot(),getXRot(),RelativeMovement.ROTATION)`；`teleportRelative` → `RelativeMovement.ALL`；`ServerGamePacketListenerImpl.teleport(...)`：`awaitingPositionFromClient = new Vec3(...)`、`++awaitingTeleport`、`player.absMoveTo(...)`、`send(new ClientboundPlayerPositionPacket(d-i, e-j, f-k, g-l, h-m, set, awaitingTeleport))` |
| §4 “相对 yaw 旋转语义” | 服务端 `teleport(...)` 中 `float l = set.contains(RelativeMovement.Y_ROT) ? this.player.getYRot() : 0.0f;`（X_ROT 同理），即带相对标志时下发的是**增量**；客户端 `ClientPacketListener.handleMovePlayer`：`if (contains(Y_ROT)) { player.setYRot(player.getYRot() + j); player.yRotO += j; } else { player.setYRot(j); … }`。故 `teleportTo(x,y,z)`（ROTATION + 当前 yaw）增量恒为 0 → 客户端朝向不变 |
| §4 传送后“能继续正常行走（惯性 / 输入不被吞）”可行 | 客户端 `handleMovePlayer` 对相对 X/Y/Z 保留 `deltaMovement`（`d = vec3.x()` 等）且不发任何输入重置；`LocalPlayer.tick/sendPosition` 的输入与发包逻辑不受传送影响 |
| §7 “fb-05 已修复（0.3.6 已落地）” | commit `f9ae675`（2026-09-29，`fix(trigger): 修复 on_enter 每局只能触发一次（离开复位分支不可达）`）；`TriggerEngine.onServerTick` 现在每次轮询都调用 `checkEnterState(player, reg, inRegion)`，`checkEnterState` 注释与实现均说明“每次轮询都必须调用”；`TriggerStateStore`/`TriggerEngine` 工作区干净（`git status` 无改动） |
| §6 “接入现有触发器体系 / 服务端每 tick” 有现成挂点 | `TriggerAction.execute(ServerPlayer)`；`TriggerRegistration`（含 exitConditions/onEnter/exitBuffer）；`ServerEventHandler.onServerTick` ← Forge `ForgeEvents`（`TickEvent.Phase.END`）/ Fabric `FabricEvents`（`ServerTickEvents.END_SERVER_TICK`）；`TriggerEngine.onServerTick` 轮询分桶 |
| §5 “多人每个玩家独立映射” 可达成 | `TriggerEngine.onServerTick` 遍历 `server.getPlayerList().getPlayers()`；`enterStates` 按 UUID 分键；`TriggerStateStore` 按 UUID 存档 |

### ② 已修正 / 需澄清的断言

| 旧说法 | 新事实（证据） |
|---|---|
| §3 “持续映射：在源区域内每 tick 保持映射”暗示可复用现有触发器轮询 | 现有 location 触发器轮询**默认 20 tick = 1 s**（`Config.triggerPollIntervalLocation = 20`，`ImmersiveCinematics.registerTriggerTypes` 注册 `ListenStrategy.POLLING`，`TriggerEngine.rebuildIndex` 按 `type.getPollInterval()` 分桶）。每 tick 映射必须走独立的每 tick 路径，不能靠 location 轮询（§3 已补注） |
| §4 表格“运动惯性：保持”作为原版默认行为 | 只有**相对**传送保留惯性：客户端 `handleMovePlayer` 在绝对模式下 `player.setDeltaMovement(0, 0, 0)`。即 `teleportTo(x,y,z)` 会把客户端动量清零；要保惯性须用 `RelativeMovement`（如 `teleportRelative` 的 `ALL`）或在传送后重设 `deltaMovement` |
| §5 “落点不安全：默认照做（作者负责建图）”隐含需自行判断 | 原版 `connection.teleport` 本就无安全检查（仅 `absMoveTo` + 发包），默认行为与“照做”一致；安全检查若要做得额外实现 |
| §3 “防乒乓…（状态机已修复）”定位到 `TriggerStateStore` 相关机制 | on_enter / exit_buffer 状态机在 `TriggerEngine.enterStates`（内存 `Map<UUID, Map<String,Boolean>>`），**不在** `TriggerStateStore`。`TriggerStateStore` 只持久化 triggered/started/completed 脚本状态到 `immersive_cinematics/trigger_state/<uuid>.snbt`；`enterStates` 不持久化、`ServerEventHandler.onPlayerQuit` 也不清除，仅 `TriggerEngine.clear()`（启动 / reload）清空 |
| §3 “防乒乓…不能被目标区域的规则立刻传回” | 状态机复位发生在**下一次轮询**：默认 location 间隔 20 tick，故“离开即复位”实际延迟 ≤ 1 s（每 tick 路径下 ≤ 1 tick）。这是精度而非正确性问题 |

### ③ 补全的源码信息

- 传送窗口：`ServerGamePacketListenerImpl.handleMovePlayer` 开头 `if (this.awaitingPositionFromClient != null) { if (this.tickCount - this.awaitingTeleportTime > 20) { … teleport(...) } return; }` —— 客户端 ACK（`handleAcceptTeleportation`，按 `awaitingTeleport` id 匹配，成功后 `absMoveTo(awaitingPositionFromClient…)` 并清空）之前，服务端**丢弃**所有移动包，最多每 20 tick 重发传送；客户端 `ClientPacketListener.handleMovePlayer` 应用后立即回 `ServerboundAcceptTeleportationPacket` + 一条 `ServerboundMovePlayerPacket.PosRot`。
- `ClientboundPlayerPositionPacket` 字段：`x, y, z, yRot, xRot, Set<RelativeMovement> relativeArguments, id`（`RelativeMovement` = X/Y/Z/Y_ROT/X_ROT 位掩码；`ALL`、`ROTATION = {X_ROT, Y_ROT}`）。
- 客户端移动包频率：`LocalPlayer.sendPosition` 在 `Mth.lengthSquared(d,e,f) > 2.0E-4` 或 `positionReminder >= 20` 时发 Pos/PosRot/Rot —— 即 ≤ 1 包/tick，最迟每 20 tick 一包。
- `ServerPlayer.teleportTo(ServerLevel, d,e,f, Set, yaw, pitch)`：先 `addRegionTicket(TicketType.POST_TELEPORT, …)`、`stopRiding()`、必要时 `stopSleepInBed(true,true)`，同维度走 `connection.teleport(...,set)`，跨维度另走维度切换分支，最后 `setYHeadRot(yaw)`。
- 乘客：`handleMovePlayer` 中 `if (this.player.isPassenger()) { absMoveTo(当前 X/Y/Z, g, h); getChunkSource().move(player); return; }` —— 位置取服务端现值，只应用朝向；载具位置由 `handleMoveVehicle` 处理。
- `exit_buffer` 外扩生成点：`ScriptManager.registerAllTriggers`：`JsonObject exitConditions = td.isOnEnter() && td.getExitBuffer() > 0f ? Evaluators.expandConditions(conditions, td.getExitBuffer()) : null;`；`expandConditions` 对 `corner1` 各轴 −buffer、`corner2` 各轴 +buffer（radius 模式则 `radius + buffer`），无 corner/position 时返回 `null`。
- 现有动作实现（`trigger/server/action/`）：`StartPlaybackAction` / `StopPlaybackAction` / `PlaySoundAction` / `ExecuteCommandAction`，接口仅 `void execute(ServerPlayer)`；`ScriptManager` 目前只注册 `StartPlaybackAction`。
- 本仓库 common 层无任何传送调用：`common/src` 下 grep `teleportTo|teleportRelative|connection.teleport` 无匹配。

### ④ 无法核实的断言（未验证）

- §4 “默认建议：平移 = 保持；镜像 / 旋转 = 同步变换” —— 属设计取舍，无源码依据，文档已标“待实机验证”。
- §8 “相对位置误差 ≤ 1 格”“循环链无乒乓” —— 需实机 / 集成测试，静态核查无法证明。
- §3 “进入即传送”与“持续映射”两种模式的最终精度与手感 —— 取决于尚未实现的每 tick 映射路径，未验证。
- §2 “不同尺寸 / 缩放不支持”“多区域链”行为 —— 设计方向，无实现可核。

### 冲突裁决记录

未发现文档断言与代码 / 源码相互矛盾且需要按“晚修改者为准”裁决的条目（本文档最后修改 `2026-10-05T11:48:34+08:00`，`TriggerEngine.java` 最后修改 `2026-09-29T14:52:45+08:00`（commit `f9ae675`），`TriggerStateStore.java` 最后修改 `2026-08-30T19:25:04+08:00`，`feedback-0.3.5/05-on-enter-not-repeatable.md` 最后修改 `2026-10-05T11:48:34+08:00`）。唯一的“说法 vs 代码”偏差是上面 ② 中的定位问题（状态机位置），属于补全而非冲突。

# network（网络层）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/trigger/network/`

功能树：

- **网络层框架**
  - ✅ 平台无关：`CinematicPacket`/`CinematicC2SPacket`/`CinematicS2CPacket` 定义包协议，`NetworkBridge` 由 Forge/Fabric 平台入口注入实际发送实现；包 ID 常量供两端注册（`NetworkHandler`、`NetworkBridge`、`CinematicPacket`）
  - ✅ 包 ID 常量集中在 `NetworkHandler`，平台侧按常量注册：S2C 侧 `play_script` / `stop_script` / `skip_vote_update` / `script_pause_ack` / `script_seek_ack` / `script_reload` / `resolve_entity_selector_result` / `camera_view` / `dimension_info`；C2S 侧 `script_finished` / `skip_vote` / `playback_started` / `script_pause` / `script_seek` / `script_saved` / `resolve_entity_selector` / `camera_center_request` / `camera_center_position` / `audio_listener` / `rule_command` / `camera_view`（`camera_view` 双向共用同一 id）（`NetworkHandler`）
  - ✅ `NetworkGuard` 统一防护部分 C2S 发包：玩家断线/退出世界时 `sendToServer` 抛异常只记日志不崩溃（覆盖脚本结束通知/暂停握手/ACK 重发/回执）（`NetworkGuard`）
  - ✅ `init()` 为兼容入口；平台注册由 Fabric/Forge 侧在 common init 后注入 `NetworkBridge` 完成（`NetworkHandler`）
- **S2C 播放/停止链路**
  - ✅ `S2CPlayScriptPacket`（服务端→客户端，`play_script`）：携带脚本原始 JSON，客户端解析为 `CinematicScript` 并交给 `CameraManager.playCinematic()` 开始播放（`S2CPlayScriptPacket`、`ClientScriptReceiver`）
  - ✅ `S2CStopScriptPacket`（服务端→客户端，`stop_script`）：指定 scriptId（+ 播放实例 id）停止；scriptId 为空时强制停止全部脚本（`S2CStopScriptPacket`、`ClientScriptReceiver`）
  - ✅ 播放链路的发送方：触发器动作 `StartPlaybackAction`（服务端）；停止链路的发送方：触发器动作 `StopPlaybackAction`、跳过投票达标广播（`StartPlaybackAction`、`StopPlaybackAction`、`ScriptEventManager`）
- **C2S 播放状态回执链路**
  - ✅ `C2SPlaybackStartedPacket`（客户端→服务端，`playback_started`）：一个包承载两件可分离的事——① **传输层 ACK**（`AckTracker.ack(refId)`，只要包被处理就回执，**排队等待 / 被拒绝也回**，否则发送方 play 包超时重发会导致重复入队）；② **播放账本**（`TriggerEngine.onPlaybackStarted`，仅当 `started=true` 时记账）。发送方：`ClientScriptReceiver` 收到 play 包后一律回 ACK（`started=false`，不谎报已开始）；`CameraManager.reportPlaybackStarted()` 在脚本**真正开始播放**时单独上报 `started=true`（直接开始 / 结束接播两处；排队等待、被拒绝、编辑器预览都不上报）（`C2SPlaybackStartedPacket`、`ClientScriptReceiver`、`CameraManager`、`TriggerEngine`）
  - ✅ `C2SScriptFinishedPacket`（客户端→服务端，`script_finished`）：播放结束（含完成原因枚举 + 播放实例 id）时回执，服务端按 `(scriptId, instanceId)` 精确解析所属实例并移除观看者；完成原因为 `SKIPPED` 时只补记账与投票者标记，不做达标判定（`C2SScriptFinishedPacket`、`TriggerEngine`、`ScriptEventManager`）
  - ✅ 回执发送方：客户端 `ClientScriptNotifier.notifyScriptFinished()` 在相机管理器结束播放时调用（`ClientScriptNotifier`）
- **暂停链路（N1 双向握手）**
  - ✅ `C2SScriptPausePacket`（客户端→服务端，`script_pause`）：客户端暂停状态转换时发送（脚本 id + 播放实例 id + 暂停标志 + **内容相对位置**（片段起始秒 + 片段内偏移秒）+ refId），服务端据此按实例精确解析、暂停期间不处理 EVENT 关键帧，并把时间线基准对到上报位置（恢复从该位置续；未上报则保持基准不动，回落 tick 推算）（`C2SScriptPausePacket`、`ScriptEventManager.handlePause`）
  - ✅ `S2CScriptPauseAckPacket`（服务端→客户端，`script_pause_ack`）：服务端处理成功后的回执，客户端 `AckTracker` 超时 2s 重发、最多 3 次后放弃（`S2CScriptPauseAckPacket`、`AckTracker`）
  - ✅ 发送方：`FrameDriver.frame()` 检测到暂停 / 挂起状态转换时发送（位置取自 `ScriptPlayer.contentPosition`）（`FrameDriver`、`PlaybackLedger`）
- **脚本保存/重载链路**
  - ✅ `C2SScriptSavedPacket`（客户端→服务端，`script_saved`）：编辑器保存脚本后通知服务端，服务端指纹对比后向全体在线玩家广播 `S2CScriptReloadPacket`（`C2SScriptSavedPacket`、`S2CScriptReloadPacket`、`ScriptSyncState`）
- **跳过投票链路**
  - ✅ `C2SSkipVotePacket`（客户端→服务端，`skip_vote`）：长按跳过键时发送（脚本 id + 播放实例 id）；**只计票**，投票者本人继续播放（`C2SSkipVotePacket`、`ScriptEventManager.onSkipVote`）
  - ✅ `S2CSkipVoteUpdatePacket`（服务端→客户端，`skip_vote_update`）：携带 `scriptId` / `instanceId` / `voterCount` / `totalViewers` / `youVoted`，**逐人发送**（`youVoted` 因收包者而异），客户端按 `(scriptId, instanceId)` 缓存供 HUD 显示（`S2CSkipVoteUpdatePacket`、`ClientScriptReceiver`）
  - ✅ 服务端计票方：`ScriptEventManager.onSkipVote`（`ScriptEventManager`）
  - ✅ 客户端提供 `resetSkipVote()` 在脚本停止/结束时清空投票缓存（`ClientScriptReceiver`）
- **触发器状态同步（已移除）**
  - ⚠️ `trigger_state_sync` / `S2CTriggerStateSyncPacket` / `ClientTriggerStateCache` 在当前源码中已不存在（残留只出现在旧构建产物 `build/quad-old`）
- **区块预加载链路（已由相机中心链路取代）**
  - ⚠️ `preload_req` / `preload_pos` / `preload_result` 与 `ChunkPreloadManager` 在当前源码中已不存在；相机中心/视野链路现由 `CameraCenterManager`、`CameraViewManager` 承载，包为 `camera_center_request` / `camera_center_position` / `camera_view` / `dimension_info`
- **客户端脚本缓存**
  - ⚠️ `ClientScriptCache` 提供按 id 缓存/查询/清空 `CinematicScript` 的静态容器，但全工程无任何调用方，未接入任何链路（`ClientScriptCache`）

## 跳过投票

| 环节 | 行为 |
|---|---|
| 投票 | 客户端长按跳过键 → `C2SSkipVotePacket`（`scriptId` + `instanceId`）；**只计票**，投票者本人继续播放 |
| 分母 | 该实例**当前在场**观众数（`viewers` 不含已离开者）；按实例分流，不同实例的票互不累计 |
| 重复投票 | 同一实例已投过票的再次长按被直接吞掉：票数与 `youVoted` 不变，也不再广播 |
| 票数广播 | 未达标 → 逐人发 `S2CSkipVoteUpdatePacket`；`youVoted` 按收包者计算，客户端按 `(scriptId, instanceId)` 缓存 |
| 达标 | 票数 ≥ `neededVotes(viewers, ratio)` → 对该批**全部在场者**发 `S2CStopScriptPacket`，随后清账本；不产生 `SKIPPED` 完成原因 |

- 比例取脚本 `meta.skip_vote_ratio`，缺省回落 `Config.skipVoteRatio`；分母用服务端 `viewers` 的当前快照，玩家退出即从分母消失。
- 客户端 HUD 只显示服务端推送的票数，不在本地自增；脚本停止/结束时 `resetSkipVote()` 清缓存。

## 协议兼容

- Forge 侧 `displayTest` 默认按 mod 版本匹配：两端 mod 版本不一致时握手阶段即被拒，不会进入字段可能错位的联机状态。
- 若后续放开跨版本联机，必须 bump `ForgeNetwork.PROTOCOL_VERSION`，让不兼容版本在握手阶段被挡下；`skip_vote` 是新增 C2S，`skip_vote_update` 也新增了 `instanceId` / `youVoted`，都属于破坏性字段变更。

> 注：WebUI 编辑器的**本地** WebSocket 协议（19 个命令 + 二进制帧流）不属于本层，见 [editor.md](./editor.md)。
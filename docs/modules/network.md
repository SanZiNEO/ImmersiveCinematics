# network（网络层）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/trigger/network/`

功能树：

- **网络层框架**
  - ✅ 基于 Architectury `SimpleNetworkManager` 建立通道（mod id `immersive_cinematics`），共注册 7 个消息类型：4 个 S2C + 3 个 C2S（`NetworkHandler`）
  - ✅ `init()` 在模组入口触发 static 字段加载，由 SimpleNetworkManager 自动完成平台注册（Forge/Fabric 统一）（`NetworkHandler`）
- **S2C 播放/停止链路**
  - ✅ `S2CPlayScriptPacket`（服务端→客户端，`play_script`）：携带脚本原始 JSON，客户端解析为 `CinematicScript` 并交给 `CameraManager.playCinematic()` 开始播放（`S2CPlayScriptPacket`、`ClientScriptReceiver`）
  - ✅ `S2CStopScriptPacket`（服务端→客户端，`stop_script`）：指定 scriptId 停止；scriptId 为空时强制停止全部脚本（`S2CStopScriptPacket`、`ClientScriptReceiver`）
  - ✅ 播放链路的发送方：触发器动作 `StartPlaybackAction`（服务端）；停止链路的发送方：触发器动作 `StopPlaybackAction`、跳过投票达标广播（`StartPlaybackAction`、`StopPlaybackAction`、`ScriptEventManager`）
- **C2S 播放状态回执链路**
  - ✅ `C2SPlaybackStartedPacket`（客户端→服务端，`playback_started`）：客户端开始播放后回执，服务端登记播放会话（`C2SPlaybackStartedPacket`、`TriggerEngine`）
  - ✅ `C2SScriptFinishedPacket`（客户端→服务端，`script_finished`）：播放结束（含完成原因枚举）时回执，服务端移除观看者并参与跳过投票统计（`C2SScriptFinishedPacket`、`TriggerEngine`、`ScriptEventManager`）
  - ✅ 回执发送方：客户端 `ClientScriptNotifier.notifyScriptFinished()` 在相机管理器结束播放时调用（`ClientScriptNotifier`）
- **暂停链路**
  - ✅ `C2SScriptPausePacket`（客户端→服务端，`script_pause`）：客户端游戏暂停/恢复时发送（脚本 id + 暂停标志），服务端据此暂停 EVENT 关键帧推进（暂停 tick 累计，恢复后跳过暂停时段）（`C2SScriptPausePacket`、`ScriptEventManager.handlePause`）
  - ✅ 发送方：`CameraManager.onRenderFrame()` 检测到暂停状态转换时发送（`CameraManager`）
- **跳过投票链路**
  - ✅ `S2CSkipVoteUpdatePacket`（服务端→客户端，`skip_vote_update`）：票数未达标时广播当前投票数与观看者总数，客户端缓存供 HUD 显示（`S2CSkipVoteUpdatePacket`、`ClientScriptReceiver`）
  - ✅ 服务端发送方：`ScriptEventManager.onPlayerFinished()` 投票统计（`ScriptEventManager`）
  - ✅ 客户端提供 `resetSkipVote()` 在脚本停止/结束时清空投票缓存（`ClientScriptReceiver`）
- **触发器状态同步**
  - ✅ `S2CTriggerStateSyncPacket`（服务端→客户端，`trigger_state_sync`）：携带已触发触发器集合与已完成脚本集合，客户端整包替换本地缓存（`S2CTriggerStateSyncPacket`、`ClientTriggerStateCache`）
  - ⚠️ 同步链路未接线：服务端没有任何发送方调用 `S2CTriggerStateSyncPacket.send()`，客户端缓存也没有任何读取方（`ClientTriggerStateCache` 查询 API 无调用者），整条链路处于休眠状态（`S2CTriggerStateSyncPacket`、`ClientTriggerStateCache`）
- **客户端脚本缓存**
  - ⚠️ `ClientScriptCache` 提供按 id 缓存/查询/清空 `CinematicScript` 的静态容器，但全工程无任何调用方，未接入任何链路（`ClientScriptCache`）

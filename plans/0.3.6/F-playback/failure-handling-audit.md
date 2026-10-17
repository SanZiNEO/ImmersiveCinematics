# 运行期失败 → 崩溃路径审计（0.3.6 / F-playback）

- **状态**：只读审计完成，未改任何代码，未运行客户端 / 服务端
- **范围**：`common/`、`forge/`、`fabric/` 全部 Java 源（以当前工作区为准）
- **方法**：按「主循环入口 → 调用链 → 失败源」静态回溯；逐条核对现有 `try/catch` 的实际覆盖范围
- **关联**：
  - `plans/complete/0.3.5/error-handling-no-swallow.md`（报错可见原则；本审计不推翻，只补充「边界捕获 + 降级」口径）
  - 已知网络层缺口（另行安排）：`PreloadRequester` 直发、`NetworkHandler.sendToPlayer` 无防护、断线未清 viewer——本审计仍登记其崩溃面，但标注「已有安排」，避免重复施工

---

## 0. 结论（先读这里）

### 0.1 最危险的五个点

| 排名 | 位置 | 一句话 |
|---|---|---|
| 1 | `mixin/GameRendererMixin.java:55-70` | `GameRenderer.render` 的 HEAD 里串了帧驱动 + lane 注册，**整条播放/渲染链一个异常都没有边界**，直接崩渲染线程 |
| 2 | `handler/ServerEventHandler.java:74-78` | 服务端 tick 串了触发器 / 事件时间线 / 锚点三套逻辑，**任一异常崩整个服务端** |
| 3 | `trigger/server/TriggerEngine.java:248-251` | 触发器动作 `executeActions` 完全裸奔；`StartPlaybackAction` 还会走网络发送 |
| 4 | `client/lane/LaneRenderer.java:333-433` + `LaneCompositor.java:154` | lane 合成 / 描边 / sink 无 catch；GL 异常会穿透 `renderLevel` RETURN |
| 5 | `handler/ClientEventHandler.java:51-59` | 客户端 tick 串了五个子系统，无隔离；其中 `PreloadRequester` 还在直发网络包 |

### 0.2 登记数量

| 分区 | 条目 | 其中 P0 |
|---|---|---|
| A. 客户端渲染 / 帧循环 | 12 | 10 |
| B. 服务端主线程 | 8 | 6 |
| C. 网络收发边界 | 10 | 4 |
| D. 客户端 tick / 输入 / Mixin | 11 | 2 |
| E. 解析 / IO / 反射 / 配置 / WebUI | 19 | 1 |
| **合计** | **60** | **23** |

> 这里的「P0」= 高频路径（每帧 / 每 tick / 每个包）且当前无任何边界，异常即崩客户端或服务端。

### 0.3 三条硬口径（本次审计的判定标准）

1. **只在边界捕获**：tick / render 主循环入口、服务端线程入口、网络收发、命令入口、资源重载、平台事件回调、Mixin 注入回调。
   **内部调用不包**——播放、求值、插值、合成、状态机内部让异常自然上抛，由边界统一处理。
2. **捕获必须可定位**：日志要能回答「哪个边界、哪个包 / 脚本 / 实例 / 玩家、什么输入、什么阶段、失败后做了什么降级」；
   首次或每 N 秒带**完整堆栈**（不能只记 `getMessage()`）；限频 key 按失败点 + 实例，不能用一个全局 key 把别的失败藏掉。
3. **致命不吞**：OOM、StackOverflow、GL 上下文丢失、渲染线程断言、非可选路径的 `LinkageError`、持久化数据损坏——
   照常上抛或走明确终止路径，绝不用 `catch` 换「暂时不崩」。

---

## 1. 判定口径

### 1.1 边界 vs 内部

| 类别 | 典型位置 | 处理 |
|---|---|---|
| **边界** | `GameRendererMixin` / `LaneRendererMixin` 注入点、`ClientEventHandler.onClientTick/onRenderHud`、`ServerEventHandler.onServerTick`、平台包 `enqueueWork` / `execute`、命令 executor、资源 reload listener、`OverlayManager` 逐层循环 | 捕获 `Exception` → 上下文日志 → 明确降级（跳过本帧 / 停该实例 / 停该功能） |
| **内部** | `ScriptPlayer` 轨道分发、`RuleEngine.evaluate`、`KeyframeInterpolator`、`CameraCoverChain`、`LaneCompositor` 的数学、`Evaluators` 条件求值 | 不包 catch；让异常上抛到边界。已有「按实例隔离」的 catch 保留（见 §F） |
| **可选探测** | `Class.forName` 找 Iris / GameStages、反射读版本号、读系统属性 | 允许捕获，但必须注释「缺失是合法结果」，并**首次记一条**（INFO/WARN），不要永久静默 |

### 1.2 可恢复 vs 真正致命

| 类别 | 例子 | 判定 |
|---|---|---|
| **可恢复** | 脚本字段非法、shader 加载失败、单条 lane 渲染失败、单实例规则异常、包处理异常、玩家断线、配置损坏 | 捕获 + 限频日志 + 降级（跳过本次 / 保持上一状态 / 重置该单元） |
| **真正致命（禁止捕获）** | `OutOfMemoryError`（NativeImage / FBO / 纹理 / 大 JSON）、`StackOverflowError`、GL 上下文丢失、`RenderSystem` 线程断言（`Not called from the render thread`）、非可选路径的 `LinkageError` / `NoClassDefFoundError` | 直接上抛，让崩溃报告留下现场；捕获后继续只会让 GL / 状态更乱 |
| **数据损坏（不静默）** | `TriggerStateStore` SNBT 解析失败、配置文件 JSON 语法错误 | 记录 ERROR（文件路径 + 异常）→ **明确终止该单元**（重置该玩家状态 / 用默认配置），不能带着坏数据继续 |
| **线程契约违反** | 在非主线程调用 `CameraManager` / `OverlayManager` / GL | 记录 ERROR + 明确拒绝该调用（返回），不要「try 一下继续」 |

### 1.3 日志最低上下文（边界 catch 必须带）

- 边界名（`render-head` / `server-tick` / `packet:C2SPreloadRequest` / `command:play` / `hud` …）
- 方向与包名（网络）、玩家 UUID / 名字、脚本 id、播放实例 id、片段 / 轨道（能拿到就给）
- 阶段与关键状态：帧号 / tick、`elapsed`、lane 序号、实例是否 preview / stopping
- 异常本身 + **完整堆栈**（首次必带；之后按 5s 窗口限频，窗口内被抑制次数合并到下一次）

> ⚠️ 现状问题：`util/ErrorLog.java:69-83` 的 `log(category, message, t)` 控制台只打 `异常类名 + message`，
> **完整堆栈只进文件**。这与「作者控制台三分钟定位」相冲突。建议边界日志在**首次**（或每个限频窗口第一次）
> 直接 `LOGGER.error(msg, t)` 打全栈，文件通道保持现状。

### 1.4 空体 / 仅注释 catch 清单（需要先补日志再决定）

这些位置不一定会崩，但会制造「表面无报错」（下表共 16 个 catch 块）：

| 位置 | 现状 | 建议 |
|---|---|---|
| `handler/ClientEventHandler.java:31-33` | 建目录失败静默 | WARN（含路径）；编辑器保存失败才有迹可循 |
| `client/lane/LaneDiagnostics.java:434-435` | manifest 数字解析失败静默按开 | DEBUG/WARN（case id + 原值） |
| `client/lane/LaneDiagnostics.java:1205-1206, 1218-1219` | ModList 反射失败静默 | 保留（可选探测），首次 INFO |
| `client/lane/LaneRenderer.java:701-703, 715-717` | Iris 字段反射失败静默 | 保留，首次 INFO（「光影兼容降级」） |
| `script/AudioListenerController.java:114-116` | rawJson 解析失败静默回落 player | 每脚本一次 WARN（scriptId + raw 长度） |
| `util/ErrorLog.java:89-91, 106-108` | 日志文件写失败静默 | 保留（日志不能反过来崩），首次 stderr |
| `util/LangResources.java:198-200` | 语言管理器不可用静默 | 保留（无头环境合法） |
| `webui/WebEditorServer.java:116-117, 203-204, 207-208, 266-267` | 关闭 socket 失败静默 | 保留（清理路径），可 DEBUG |
| `webui/WebSocketSession.java:52-53, 71-72` | 读循环 / close 失败静默 | 保留（连接已断），可 DEBUG |

---

## 2. A. 客户端渲染 / 帧循环（P0 主战场）

> 调用链总览：
> `GameRenderer.render HEAD` → `CameraManager.onRenderFrame()` → `FrameDriver.frame()` → 逐实例 `ScriptPlayer.onRenderFrame()`；
> 同一 HEAD 内 `ScriptLaneDriver.tick()`；
> `GameRenderer.renderLevel RETURN` → `LaneRenderer.render()` → `renderLane()` → `Sink` → `LaneCompositor.compose()`；
> `GameRenderer.render` 的 `bindWrite` 处 → `ColorAdjustPass.render()`；
> HUD：`ClientEventHandler.onRenderHud()` → `CinematicOverlay.render()` → `OverlayManager.render()`。

| # | 位置（文件:行） | 线程 / 调用链 | 失败源 | 当前是否捕获 | 后果 | 判定 | 最小修法 | 优先级 |
|---|---|---|---|---|---|---|---|---|
| A1 | `common/.../mixin/GameRendererMixin.java:55-70` | 渲染线程；`GameRenderer.render` HEAD → `LaneDiagnostics.onFrameStart` → `CameraManager.onRenderFrame` → `ScriptLaneDriver.tick` | 帧驱动内任意 RuntimeException：时钟、轨道、覆盖层、规则、快照、lane 注册 | **无** | **客户端崩溃**（渲染线程） | 应该捕获（帧循环边界） | 整个 `renderLevel && level != null` 分支包 `catch (Exception e)`；`ErrorLog.logRateLimited("Render","frame-head",5000, 带帧号/实例数, e)`；本帧跳过（不改状态，下一帧重试）；`Error` 直接上抛 | **P0** |
| A2 | `common/.../camera/FrameDriver.java:63-194` | 渲染线程；逐实例 body | `OverlayManager.update:86`、`player.pauseAudio:113` / `resumeAudio:115` / `stopOneShotSounds:118`（SoundEngine）、`player.onRenderFrame:144`（`playerMovement.onRenderFrame` 在 `ScriptPlayer:616` 未捕获）、`lifecycle.deactivateNow:170`（`ScriptPlayer.stop` → 层 `close()` 的 GL 删除、`syncModifiers`）、`requestExit:184`、`refreshSnapshot.run:193`（覆盖链合成） | 仅 `ruleEngine().evaluate` 一处 `catch (Exception)`（`:148-151`） | 崩溃；或**一个实例异常拖垮整帧**（其他实例也不播） | 应该捕获（帧循环边界，**按实例隔离**） | 每个实例 body 包 `try/catch (Exception)`：日志带 `scriptId/instanceId/preview`；捕获后给该实例置内部错误原因并 `lifecycle.deactivateNow(instance)`（只退它，其他实例继续）；`refreshSnapshot` 单独 try/catch（失败=保持上一帧快照）；`Error` 上抛 | **P0** |
| A3 | `common/.../client/lane/ScriptLaneDriver.java:120-181` | 渲染线程；`GameRenderer.render` HEAD → `tick` | `player.collectCameraLanes()`（`ScriptPlayer:503` 采样调色）、`KeyframeInterpolator.computeInterpolation`、`LaneMask.sample`、`renderer.setLane` | **无** | 崩溃 | 应该捕获（帧边界，按实例隔离） | 每个实例的 collect 循环包 `try/catch (Exception)`；失败实例本帧不注册 lane（画面回落主画面），其他实例继续；日志带 `scriptId/instanceId` | **P0** |
| A4 | `common/.../client/lane/LaneRenderer.java:333-433`（入口 `mixin/LaneRendererMixin.java:36-39`） | 渲染线程；`renderLevel` RETURN | `main.bindWrite`、`mc.levelRenderer.doEntityOutline():361`（仅 finally）、`laneSink.laneRendered:398`（→ `ScriptLaneDriver.compose:314` → `LaneCompositor.compose:154`）、`offscreenTarget` / `adjustTarget` 创建 | `renderLane` 内 `catch (RuntimeException)`（`:543-546`，正确）；**外层与合成无 catch** | 崩溃；若异常发生在绑定/投影切换后，虽有 finally 还原大部分状态，仍可能留下半帧画面 | 应该捕获（渲染边界） | `render()` 外层加 `catch (Exception e)`：日志带 lane 数 / 当前 lane 序号 / captureId；**保留现有 finally**；失败本帧不再合成（主画面保持基画面）；GL 上下文丢失 / 线程断言重新抛出 | **P0** |
| A5 | `common/.../client/lane/LaneCompositor.java:154-270` | 渲染线程；被 A4 与 `ColorAdjustPass:273` 调用 | `BufferUploader.drawWithShader`、`builder.end()`、GL 状态/驱动错误；shader / uniform 缺失已有守门（返回 false） | try/finally，**无 catch** | 崩溃 | 应该捕获（渲染边界，降级=跳过该 lane） | `catch (RuntimeException e) { reportSkipped("compose 异常: " + e); return false; }`，放在 finally 之前；把 lane 标识（captureId 或序号）传进来一起记 | **P0** |
| A6 | `common/.../client/post/ColorAdjustPass.java:237-278` | 渲染线程；`GameRenderer.render` 的 `bindWrite` 处（`GameRendererMixin:125-130`） | `MasterColorAdjust.consume`、`swapTarget` 创建（FBO/纹理）、`LaneCompositor.compose:273`；`applyTo` 已捕获（`:330-333`） | 仅 `applyTo` 内 catch | 崩溃；**异常发生在 swap 绑定后时，主 framebuffer 可能仍绑着 swap → 后续 GUI 画进错缓冲（黑屏 / 花屏）** | 应该捕获（渲染边界）+ **必须恢复绑定** | `render` 外层 `try/catch/finally`：catch 记层数 / 失败层索引；finally 若当前绑定不是 main 则 `main.bindWrite(true)`；`Error` 上抛 | **P0** |
| A7 | `common/.../overlay/OverlayManager.java:46-52` | 渲染线程（HUD 阶段） | 各层 `render`：`ImageLayer:69`（`gif.update` → `DynamicTexture.upload` GL；`TextureLoader.getTextureSize`）、`SubtitleLayer:55`（字体/文本）、`FadeLayer:68` → `ColorMaskRenderer.render:139`（内部 finally 无 catch）、`LetterboxLayer:47` | **无** | **客户端崩溃**（HUD 阶段） | 应该捕获（渲染边界，**按层隔离**） | 逐层 `try/catch (Exception)`：日志带层类名 / owner / opacity / zIndex；失败层本帧跳过（下一帧重试）；连续 N 帧失败才移除并 ERROR | **P0** |
| A8 | `common/.../handler/ClientEventHandler.java:62-69` | 渲染线程；平台 `RenderGuiEvent.Post` / `HudRenderCallback` | `CinematicOverlay.render` + `SkipHudRenderer.render` | **无** | 崩溃 | 应该捕获（平台事件边界，最后一道） | `try/catch (Exception)` + 限频日志（「HUD 渲染失败，本帧跳过」）；A7 已逐层捕获时这里只兜底 | **P0** |
| A9 | `common/.../overlay/OverlayManager.java:105-109` | 渲染线程；`FrameDriver.frame:86` | `layer.tick`（当前仅 `FadeLayer.tick`，逻辑简单；未来层可能 IO/GL） | **无** | 崩溃 | 应该捕获（帧边界） | 同 A7 的逐层 catch，日志带层类名 | P1 |
| A10 | `common/.../script/ScriptPlayer.java:553-617` | 渲染线程；`FrameDriver:144` → `onRenderFrame` | `playerMovement.onRenderFrame:616`、`oneShotHost.pruneOneShots()`、`recordContentFacts`（有越界 guard） | TrackPlayer 循环有 catch（`:604-608`），尾部**无** | 崩溃（经 A2 边界）；或单轨异常被记录但日志缺上下文 | 由 A2 边界覆盖，**内部不再加层** | 把现有 catch 的日志补成 `scriptId + instanceId + TrackPlayer 类名 + elapsed`；A2 落地后本方法不需要额外 try | **P0**（日志补强） |
| A11 | `common/.../mixin/GameRendererMixin.java:85-91` | 渲染线程；`GameRenderer.render` RETURN | `LaneDebugCapture.onFrameEnd:205`（`download()` GL 读回、NativeImage 分配）、`LaneDiagnostics.onFrameEnd:75` | **无** | 崩溃（仅调试开关开启时） | 应该捕获（调试边界，失败=关闭调试） | `try/catch (Exception)`；捕获后关闭本次诊断会话（`LaneDiagnostics` / `LaneDebugCapture` 的开关），避免每帧重试 | P1 |
| A12 | `common/.../client/lane/ScriptLaneDriver.java:314-324`（compose 回调） | 渲染线程；`LaneRenderer` 的 sink | `LaneCompositor.compose` 抛异常；`LaneDebugCapture.onLaneComposited` 读回 | **无** | 崩溃（经 A4） | 应该捕获（渲染边界） | 在回调内 catch：日志带 lane 序号 / captureId；返回（该 lane 不上屏）；`Error` 上抛 | **P0**（与 A5 二选一即可，建议都加：A5 保证返回 false，A12 保证调试读回不拖垮渲染） |

---

## 3. B. 服务端主线程（P0）

> 调用链：平台 `END_SERVER_TICK` → `ServerEventHandler.onServerTick` → `TriggerEngine.onServerTick`（轮询 + 延迟触发）→ `ScriptEventManager.onServerTick`（EVENT 时间线）→ `CameraAnchorManager.tick`。
> 事件回调（join / death / interact / craft / pickup / dimension …）→ `ServerEventHandler.*` → `TriggerEngine.onGameEvent`。
> 包处理（C2S）→ 平台 `enqueueWork` / `server.execute` → `pkt.handle` → 上述逻辑。

| # | 位置（文件:行） | 线程 / 调用链 | 失败源 | 当前是否捕获 | 后果 | 判定 | 最小修法 | 优先级 |
|---|---|---|---|---|---|---|---|---|
| B1 | `common/.../handler/ServerEventHandler.java:74-78` | 服务端主线程；`END_SERVER_TICK` | `TriggerEngine.onServerTick`（全量触发器 × 全量玩家）、`ScriptEventManager.onServerTick`（事件时间线）、`CameraAnchorManager.tick` | **无** | **服务端崩溃（整服宕机）** | 应该捕获（服务端线程边界） | 三个子系统各自 `try/catch (Exception)`：日志带 tick / 子系统名 / 当前玩家；一个失败不影响另外两个；`Error` 上抛 | **P0** |
| B2 | `common/.../trigger/server/TriggerEngine.java:248-251` | 服务端主线程；`fireTrigger:219` / `processDelayedFires:145` → `executeActions` | `StartPlaybackAction.execute:44-77`（`ScriptStructureResolver` 已自捕获；`S2CPlayScriptPacket.send` 未捕获）、未来动作实现 | **无** | 服务端崩溃；或一个动作失败导致同触发器后续动作不执行 | 应该捕获（动作执行边界） | 每个 action `try/catch (Exception)`：日志带 `triggerId/scriptId/player/action 类`；继续下一个动作；`Error` 上抛 | **P0** |
| B3 | `common/.../trigger/server/TriggerEngine.java:83-99`（`onGameEvent`）/ `102-141`（`onServerTick` 轮询）/ `145-159`（延迟触发） | 服务端主线程；事件回调 / tick | `fireTrigger:219` → `markTriggered`（状态存储）、`S2CTriggerStateSyncPacket.send`（网络）、`executeActions` | 仅 `evaluateSafely:294-303` 捕获求值异常 | 单个触发器异常中断整轮事件处理 / 整 tick | 应该捕获（按 registration 隔离） | `fireTrigger` 调用点包 `try/catch (Exception)`：日志带 `triggerId/scriptId/player`；该触发器本轮跳过；延迟触发同样隔离 | **P0** |
| B4 | `common/.../trigger/server/ChunkPreloadManager.java:41-83`（handleRequest）/ `89-101`（handlePosition）/ `109-111`（onScriptFinished）/ `release` | 服务端主线程；`C2SPreloadRequestPacket.handle:62` / `C2SPreloadPositionPacket.handle:38` | `CameraAnchorManager.setAnchor`（`groundY` 高度图、虚拟玩家构造）、`cache.move(player)`（原版 chunk 差集）、`CameraVirtualCenterState` | **无** | **异常客户端包即可崩服务端** | 应该捕获（网络包处理边界） | 在包 handler 包 `try/catch (Exception)`（或统一 PacketGuard，见 C2）：日志带 `player UUID/mode/script/坐标`；失败时 `release(uuid, player)` 清理半更新状态；`Error` 上抛 | **P0** |
| B5 | `common/.../trigger/server/CameraAnchorManager.java:29-41`（setAnchor）/ `73-84`（getVirtualPlayer） | 服务端主线程；`getVirtualPlayer` 被 `NaturalSpawnerMixin:41` 在刷怪热路径调用 | `level.getHeight`（区块未加载 / 维度切换）、`a.virtual.getX()`（level 已卸载）、map 迭代 | **无** | 服务端崩溃（刷怪 / 包处理） | 应该捕获（Mixin 边界 + 包边界） | `NaturalSpawnerMixin` 的 redirect 包 `try/catch (Exception)`，失败返回 `real` + 限频日志；`setAnchor` 由 B4 边界覆盖；确保 quit 时 `removeAnchor` | **P0** |
| B6 | `common/.../command/CinematicCommand.java:129-192`（play）/ `194-254`（stop）/ `256-...`（reload / template） | 服务端主线程；Brigadier executor | `ScriptParser.parse` 的 RuntimeException（见 E1）、`AckTracker.expect` 回调、`S2CPlayScriptPacket.send`（网络）、`Files.readString`（已捕获） | 仅 `IOException` / `ScriptParseException` | **玩家执行命令 → 服务端崩溃** | 应该捕获（命令入口边界） | 每个 executor 外层 `try/catch (Exception)`：`source.sendFailure("§c内部错误: " + e.getMessage())` + `LOGGER.error(..., e)`（全栈）；`Error` 上抛 | **P0** |
| B7 | `common/.../trigger/server/ScriptEventManager.java:178-260`（onServerTick） | 服务端主线程；tick | `pb.viewers.removeIf` / `getPlayerList().getPlayer` / `keyframe.getString` / `pb.eventClips`；`executeCommand:303-313` 已逐条 catch（正确） | 事件命令有 catch；**遍历本身无** | 服务端崩溃（单条坏脚本 / 坏 keyframe） | 应该捕获（服务端 tick 边界，按 playback 隔离） | 每个 `ScriptPlayback` 处理包 `try/catch (Exception)`：日志带 `scriptId/instanceId/tick`；失败移除该 playback（避免每 tick 重试） | P1 |
| B8 | `common/.../trigger/server/store/TriggerStateStore.java:96-108`（load）/ `113-122`（saveAll）/ `126-146`（savePlayer） | 服务端主线程；join / quit / 停止 / `LevelEvent.Save` | `Files.readString` / `TagParser.parseTag`（已捕获 Exception）；`serialize` / `StringTagVisitor` 的 RuntimeException 在 `saveAll` 未捕获 | load / savePlayer 已捕获；`saveAll` 的 `serialize` 未捕获 | 服务端停止 / 保存时崩溃；或状态被静默重置（玩家进度丢失） | 应该捕获（IO 边界）+ 数据损坏明确记录 | `saveAll` 每个玩家 `try/catch (RuntimeException)`，日志带 UUID + 文件；`loadForPlayer` 补文件路径；损坏时 ERROR「该玩家状态已重置」，不静默 | P1 |

---

## 4. C. 网络收发边界（P1）

> 统一建议：**平台适配层是最佳边界**（Forge `enqueueWork` / Fabric `server.execute|client.execute`），
> 新增一个 `PacketGuard.handle(direction, packetName, player, Runnable)`，所有包走它。这样不必在每个 `handle` 里散落 try/catch，
> 也天然覆盖 B4 / C4 / C7 这类下游异常。

| # | 位置（文件:行） | 线程 / 调用链 | 失败源 | 当前是否捕获 | 后果 | 判定 | 最小修法 | 优先级 |
|---|---|---|---|---|---|---|---|---|
| C1 | `common/.../trigger/network/NetworkHandler.java:42-48` | 调用方线程（客户端 tick / 渲染线程 / 服务端主线程） | bridge 发送：断线 / 退出世界 / 通道未注册 / 序列化失败 | **无** | 调用线程崩溃（已知另行修复） | 应该捕获（网络发送边界） | `try/catch (Exception)`：日志带方向 + 包类名 + 玩家 + 脚本/实例；`bridge == null` 记 debug；`Error` 上抛 | **P0**（已有安排） |
| C2 | `forge/.../ForgeNetwork.java:33-85`（全部 `enqueueWork`）/ `96-105`（send） | 服务端 / 客户端主线程 | `pkt.handle` 内任意异常；`CHANNEL.send` 断线抛 | **无** | 崩溃（服务端 tick / 客户端 tick） | 应该捕获（平台适配层=最佳统一边界） | 所有 `ctx.get().enqueueWork(() -> pkt.handle(...))` 改走 `PacketGuard`；日志带方向 / 包名 / 玩家 UUID；限频；`Error` 上抛 | **P0** |
| C3 | `fabric/.../FabricNetwork.java:30-90`（receiver）/ `96-110`（send） | 服务端 / 客户端主线程 | 同上 | **无** | 崩溃 | 应该捕获（平台适配层） | 同 C2：`server.execute` / `client.execute` 内改走 `PacketGuard` | **P0** |
| C4 | `common/.../trigger/client/ClientScriptReceiver.java:71-102`（handleStopScript） | 客户端主线程；`S2CStopScriptPacket.handle:41` → `Minecraft.execute` | `forceDeactivate` / `stopScriptById` / `cancelQueued`（`deactivateNow` 的层 close、音频停止） | **无** | 客户端崩溃 | 应该捕获（网络回调边界） | 包 `try/catch (Exception)`：日志带 `scriptId/instanceId/refId`；失败仍 `resetSkipVote()`；ACK 已走 NetworkGuard | **P0** |
| C5 | `common/.../trigger/client/ClientScriptReceiver.java:47-68`（handlePlayScript） | 客户端主线程 | 解析 / `playCinematic` | 已 `catch (Exception)`（正确） | 静默风险低 | 保留捕获，补日志 | 日志补 `instanceId/refId/json 长度`；失败时仍回 ACK（已走 NetworkGuard） | P1 |
| C6 | `common/.../trigger/client/ClientScriptReceiver.java:105-128`（handleScriptReload） | 客户端主线程 | 文件 IO / 解析 | 已 `catch (Exception)`（正确） | 静默风险低 | 保留捕获，补日志 | 日志补 `fileName/指纹` | P2 |
| C7 | `common/.../trigger/client/ClientEntitySelectorCache.java:64-88`（request） | 客户端主线程 | `NetworkHandler.sendToServer`（断线 / 退出世界） | `catch (Exception e)` **空体**，只回滚 pending | 静默；下帧重试，可能持续失败 | 先补日志再决定 | `ErrorLog.logRateLimited("Net","selector-request",5000, key + selector, e)` | P1 |
| C8 | `common/.../trigger/server/ScriptSyncState.java:30-50` | 服务端主线程；`C2SScriptSavedPacket.handle:30` | `Files.readAllBytes`（已捕获）；广播 `sendToPlayer` 未捕获 | 部分（IO 有） | 广播时断线 → 崩溃 | 依赖 C1；补广播失败日志 | 广播循环包 try/catch 或等 C1 统一防护；日志带 fileName | P2 |
| C9 | `common/.../trigger/network/AckTracker.java:46-73` | 客户端 / 服务端 tick | 重发 `send` 断线抛 | 已 `catch (Exception)` + 放弃重试（正确） | 无 | 保留 | 日志补包描述（当前只有 refId） | P3 |
| C10 | `common/.../trigger/network/C2SResolveEntitySelectorPacket.java:51-65` | 服务端主线程 | 选择器解析 | 已 `catch (Exception)` 并回错误包（正确） | 无 | 保留 | 无 | P3 |

---

## 5. D. 客户端 tick / 输入 / Mixin 注入点（P1）

| # | 位置（文件:行） | 线程 / 调用链 | 失败源 | 当前是否捕获 | 后果 | 判定 | 最小修法 | 优先级 |
|---|---|---|---|---|---|---|---|---|
| D1 | `common/.../handler/ClientEventHandler.java:51-59`（onClientTick） | 客户端主线程；平台 `END_CLIENT_TICK` | `CameraManager.tick`（`ClientEntityProbe.tick:40` 的 `mc.level.getEntities`）、`CinematicKeyBindings.onClientTick`（`mc.setScreen(new WebPreviewScreen())`、`requestExit`）、`PreloadRequester.tick`（直发网络）、`AudioListenerReporter.tick`（NetworkGuard 已包）、`AckTracker.tick`（内部已 catch） | **无** | **客户端崩溃（tick 线程）** | 应该捕获（平台 tick 边界，逐子系统） | 每个调用单独 `try/catch (Exception)`：日志带子系统名；继续后续子系统；`Error` 上抛 | **P0** |
| D2 | `common/.../control/CinematicKeyBindings.java:37-83` | 客户端主线程；D1 内 | `mc.setScreen`（构造 WebPreviewScreen）、`requestExit`（→ `deactivate` → 覆盖层渐出）、`InputConstants.isKeyDown` | **无** | 崩溃（D1 修复后不崩） | 由 D1 边界覆盖；内部不再包 | 仅补 `WebPreviewScreen` 构造失败的日志（D1 捕获时带子系统名即可） | P2 |
| D3 | `common/.../trigger/client/PreloadRequester.java:57-181` | 客户端主线程；D1 内 | `NetworkHandler.sendToServer` 四处（`:102`、`:116`、`:139`、`:177`） | **无**（已知另行修复） | 断线 / 退出世界时崩溃 | 应该捕获（网络发送边界） | 全部改走 `NetworkGuard.sendToServer`；D1 兜底；发包失败仍更新 `lastScript/preloadActive`（避免每 tick 重试风暴），日志限频带 scriptId | **P0**（已有安排） |
| D4 | `common/.../trigger/client/AudioListenerReporter.java:32-64` | 客户端主线程；D1 内 | `AudioListenerController.getListenerPosition` 读 player（判空） | 发送已走 NetworkGuard；取值无 catch | 低（D1 兜底） | 保留 | 无 | P2 |
| D5 | `common/.../mixin/KeyboardHandlerMixin.java:45-73` | 主线程；GLFW 键盘回调 | `inputRouter.routeKeyboard`、`FlightModeManager.onKeyEvent` | **无** | 输入回调异常 → 客户端崩溃 | 应该捕获（Mixin 注入边界；失败=放行原版，不 cancel） | `try/catch (Exception)`：日志限频（key/action/target）；不 cancel；`Error` 上抛 | P1 |
| D6 | `common/.../mixin/MouseHandlerMixin.java:43-50`（onPress）/ `52-61`（onScroll）/ `63-105`（onMove）/ `107-114`（turnPlayer） | 主线程；GLFW 鼠标回调 | `inputRouter.route*`、`FlightModeManager.onMouseMove`、`GLFW.glfwSetCursorPos`、`setIgnoreFirstMove` | **无** | 客户端崩溃 | 应该捕获（Mixin 边界；失败=放行原版） | 每个注入点 `try/catch (Exception)`；`onMove` 失败时恢复 `icHasLastMouse` 状态并放行；日志限频 | P1 |
| D7 | `common/.../mixin/LocalPlayerMixin.java:22-30` | 主线程；`LocalPlayer.serverAiStep` HEAD | `PlayerMoveController.applyFakeInput`（写 `player.input` / `setYRot`） | **无** | 玩家 tick 崩溃 | 应该捕获（Mixin 边界；失败=本帧不驱动移动） | `try/catch (Exception)` + 限频日志（scriptId、目标坐标）；`Error` 上抛 | P1 |
| D8 | `common/.../mixin/SoundManagerMixin.java:28-37` | 主线程；`SoundManager.updateSource` | `AudioListenerController.cameraListener()`（`new Camera` + `CameraAccessor` 强转 + `state.position()`） | **无** | 音频更新崩溃 | 应该捕获（Mixin 边界；失败=返回原 camera） | `try/catch (Exception)` → 日志限频一次 → `return camera` | P1 |
| D9 | `common/.../mixin/LevelRendererVanillaMixin.java:85-109` | 渲染线程；`setupRender` HEAD/RETURN | HEAD 改写 `smartCull`；若 `setupRender` 在 HEAD 之后抛异常，RETURN 注入不执行 | 无（依赖 RETURN 配对） | **非崩溃**：`smartCull` 永久 false（全局状态泄漏，性能/遮挡异常） | 需要先补日志再决定 | 在下一帧 HEAD 检测「上次 toggled 但未还原」→ 先还原再决定是否改写（自愈）+ WARN 一次 | P2 |
| D10 | `common/.../mixin/LaneCullStateMixin.java:162-175`（await）/ `110-120`（push）/ `178-181`（mirrorCompiled） | 渲染线程；`mirrorCompiled` 在区块编译完成线程 | `pending.get()`（已捕获）；`beginPush` / `copyFields`（由 `renderLane` 的 catch 覆盖）；`LaneCullSlots.mirrorCompiled` 在非主线程抛异常 | await 已捕获；push 由 A4 覆盖；mirror **无** | mirror 异常被 ForkJoinPool 吞掉 → lane 候选集缺块（静默） | 先补日志 | `mirrorCompiled` 外层 `try/catch (RuntimeException)` + 限频 WARN（含 chunk 坐标） | P2 |
| D11 | `common/.../mixin/GameRendererMixin.java:132-142`（getFov）/ `LevelRendererMixin.java:51-124` / `CameraMixin.java:64-106` / `ParticleEngineMixin.java:26-33` | 渲染线程 | 只读静态标志 / lane 状态（`setLane` 保证非空） | 无 | 低 | 保留（内部状态已保证非空；由 A1/A4 兜底） | 无 | P3 |

---

## 6. E. 解析 / IO / 反射 / 配置 / WebUI（P2）

| # | 位置（文件:行） | 线程 / 调用链 | 失败源 | 当前是否捕获 | 后果 | 判定 | 最小修法 | 优先级 |
|---|---|---|---|---|---|---|---|---|
| E1 | `common/.../script/ScriptParser.java:1521-1531`（`optString/optInt/optBool`）、`:1486`（`requireString`）、`:1621-1623`（`optBoolMeta`） | 调用方线程（命令 / 客户端包 / 预览） | JSON 字段类型不符时 `getAsString/getAsInt/getAsBoolean` 抛 `UnsupportedOperationException` / `NumberFormatException`，**不是** `ScriptParseException` | **无**（`parse` 只把 `JsonParser.parseString` 的异常包装了） | `CinematicCommand.playScript` 只 catch `ScriptParseException` → **命令崩服务端**；`PreviewChannel.pushScript` 只 catch `ScriptParseException` → 主线程崩（若调用点不在 WebEditorApi 的顶层 catch 内） | 应该捕获（解析边界，统一异常类型） | 在 `parse()` 最外层 `catch (RuntimeException e)` → `throw new ScriptParseException("<root>", "字段类型错误: " + e.getMessage(), e)`；单点解决全部 helper | **P0**（命令崩溃路径） |
| E2 | `common/.../camera/PreviewChannel.java:119-146`（pushScript）/ `151-169`（setTime）/ `172-187`（resume） | 客户端主线程；WebUI `editor.pushScript` / `editor.seek` / `editor.play` | 只 catch `ScriptParseException`；`lifecycle.startScriptInternal:372` 内 `mc.player.position()` 未判空 | 部分 | 客户端崩溃（玩家为 null / 断线时） | 应该捕获（编辑器边界）+ 入参前置校验 | `pushScript` catch `Exception`（日志带 JSON 长度）；`startScriptInternal` 开头 `if (mc.player == null) { log; return null; }`（现有调用方已处理 null） | P1 |
| E3 | `fabric/.../FabricConfig.java:38-45`（load）/ `101-120`（getInt/getBoolean/getFloat） | 模组初始化线程 | `GSON.fromJson` 的 `JsonSyntaxException`（RuntimeException，未被 `catch (IOException)` 覆盖）；`getAsInt` 类型不符 | 仅 IOException | **启动崩溃**（配置损坏） | 应该捕获（配置读取边界 → 默认值 + WARN） | `load()` catch `Exception`：日志带配置文件路径；`get*` 先校验 `isNumber/isBoolean` | P1 |
| E4 | `common/.../command/CinematicCommand.java:149-160` | 服务端主线程；play | 只 catch `IOException` / `ScriptParseException` | 部分 | E1 修复后覆盖；仍建议 B6 兜底 | 由 B6 + E1 覆盖 | 无需单独改 | P2 |
| E5 | `common/.../client/lane/LaneRenderer.java:669-674`（Class.forName）/ `700-718`（反射 Iris 字段） | 渲染线程（首次 / 资源周期） | 类缺失 / 字段不存在 / 访问被拒 | 已 `catch (Throwable)` 静默 | 无崩溃；静默降级 | 先补日志（可选探测） | 首次 INFO（「Iris 深度重挂未接管，降级为原行为」）；静态 flag 只记一次 | P2 |
| E6 | `common/.../client/lane/LaneDiagnostics.java:434-435, 987, 1205-1206, 1218-1219, 1229-1232` | 渲染线程（F5 诊断）/ 可选探测 | 数字解析 / 系统属性 / ModList 反射 | 部分空体 | 静默 | 先补日志（`:434` 至少 DEBUG） | 见 §1.4 | P3 |
| E7 | `common/.../trigger/server/evaluator/Evaluators.java:451-458` | 服务端主线程；gamestage 条件求值 | `Class.forName("net.darkhax.gamestages.GameStageHelper")` + 反射 | 已 `catch (Exception)` 返回 false（正确） | 无崩溃；静默 | 保留捕获，首次 INFO | 首次探测失败记一条「GameStages 未安装 / 反射失败，gamestage 触发器不生效」 | P3 |
| E8 | `common/.../util/ErrorLog.java:88-108` | 任意线程 | 日志文件写失败 | 已 `catch (IOException)` 静默（控制台已 ERROR） | 无崩溃 | 保留（日志不能反过来崩游戏） | 首次 stderr 一条 | P3 |
| E9 | `common/.../handler/ClientEventHandler.java:30-33` | 客户端初始化 | 建 `scripts` 目录失败 | 空 catch | 静默（编辑器保存失败无迹可循） | 先补日志 | WARN（含路径） | P3 |
| E10 | `common/.../script/AudioListenerController.java:105-125`（listenerMode） | 客户端主线程（音频 / 渲染读取） | `rawJson` 解析失败 | `catch (RuntimeException)` 静默回落 player | 静默（listener 配置不生效） | 先补日志 | 每脚本一次 WARN（scriptId + raw 长度） | P3 |
| E11 | `common/.../client/post/ColorAdjustPass.java:386-399`（shader 加载）/ `common/.../client/lane/LaneCompositor.java:394-399`（blit shader 加载） | 渲染线程（首次使用 / 资源重载后） | `new ShaderInstance(...)` 在 JSON 语法错误 / uniform 不匹配时抛 **RuntimeException**；当前只 catch `IOException` | 仅 IOException | **崩溃**（渲染线程），且每帧重试 | 应该捕获（资源加载边界） | `catch (IOException \| RuntimeException e)`：日志带 shader 名 + 资源包；本资源周期禁用该功能（现有 generation 语义） | P1 |
| E12 | `common/.../overlay/ColorMaskRenderer.java:502-506` | 渲染线程 | shader 加载 | 已 `catch (IOException \| RuntimeException)`（正确） | 无 | 保留 | 无 | P3 |
| E13 | `common/.../util/TextureLoader.java:65-82, 86-150`；`util/GifAnimation.java:44-52` | 渲染线程（层渲染内） | 文件 IO / 图片解码（已 catch Exception）；`GifAnimation.update` 的 `copyFrom` + `upload` 未捕获 | 加载已捕获；update 无 | `update` 异常由 A7 覆盖；OOM 属致命 | 由 A7 覆盖 | 无需单独改（OOM 上抛） | P2 |
| E14 | `common/.../webui/WebPreviewScreen.java:74-...`（render） | 渲染线程；编辑器预览屏 | `WebFrameCapture.capture` + `WebFrameStreamer.onFrame`（`glReadPixels`） | **无** | 编辑器预览时崩溃 | 应该捕获（渲染边界；失败=停止本次捕获） | `try/catch (Exception)`：日志限频；捕获后关闭捕获直到重开编辑器 | P1 |
| E15 | `common/.../webui/WebFrameStreamer.java:55-90`（worker）/ `WebEditorServer.java:349-359`（broadcast） | `IC-WebFrameSender` worker 线程 | `sendRaw` 内 `broadcastBinary` → `WebSocketSession.sendFrame:76-98` 只 catch IOException；RuntimeException 会杀 worker | 部分 | 静默丢帧 / worker 死亡 | 应该捕获（线程边界） | `sendRaw` 外层 `catch (RuntimeException)` + 限频日志；`broadcastBinary` 逐 session catch | P2 |
| E16 | `common/.../webui/WebEditorApi.java:23-90` | 客户端主线程；`WebSocketSession` 把消息 `Minecraft.execute` 过来 | 任何消息处理异常 | 已 `catch (Exception)`（正确，但只 `getMessage`） | 无崩溃；定位差 | 保留捕获，补栈 | 控制台 `LOGGER.error("WebUI 消息处理失败 type=... id=...", e)` | P1 |
| E17 | `common/.../webui/WebRegistryService.java:103-115` | 客户端主线程 | 反射 `getDeclaredField("advancements")` | 已 `catch (Exception)` 返回空（正确） | 静默 | 保留，可 DEBUG | 可选 | P3 |
| E18 | `common/.../client/lane/LaneDebugCapture.java:205-230, 240-...` | 渲染线程（调试开关） | `download()` 的 `downloadTexture` / `flipY`；`NativeImage` 分配 | 写盘已 catch IOException；GL 读回无 | 崩溃（调试开关开启时） | 由 A11 边界覆盖 | A11 捕获后关闭调试 | P2 |
| E19 | `common/.../client/lane/LaneDiagnostics.java:445-476`（frameStart） | 渲染线程（F5 诊断） | `writeEnvironment`（内部 catch IO）、`checkpoints()`（读文件 / JSON）、`pickPerf()`（启动测试脚本） | 无外层 catch | 崩溃（诊断开关开启时） | 由 A1/A11 边界覆盖 | 同上 | P2 |

---

## 7. F. 已正确捕获（保留，不推翻）

这些是本轮审计确认「位置正确、不构成逃逸」的捕获；后续加固不要把它们改成静默或删掉：

| 位置 | 说明 |
|---|---|
| `trigger/server/TriggerEngine.java:294-303` `evaluateSafely` | 求值异常按触发器隔离 + 全栈 ERROR（正确） |
| `trigger/server/prereq/PrerequisiteRegistry.java:55-59` | 前置条件求值异常隔离（正确） |
| `camera/rule/RuleActions.java:78-102` | 动作异常限频 + 跳过（正确；建议补 scriptId/instanceId/clip 上下文） |
| `camera/FrameDriver.java:148-151` | 规则引擎异常按实例隔离（正确；建议补 scriptId/instanceId） |
| `client/lane/LaneRenderer.java:543-546` | lane 渲染异常判失败 + 限频（正确） |
| `client/post/ColorAdjustPass.java:328-333` | 调色 pass 异常判失败（正确） |
| `client/lane/LaneCompositor.java:156-219` + `reportSkipped:369-372` | 合成守门（正确；建议补 lane 标识） |
| `script/ScriptPlayer.java:604-608` | 单 TrackPlayer 异常不影响其他轨道（正确；补上下文） |
| `trigger/network/AckTracker.java:60-70` | 重发异常放弃重试（正确） |
| `trigger/network/NetworkGuard.java:24-29` | C2S 发送防护（正确） |
| `trigger/network/C2SResolveEntitySelectorPacket.java:51-65` | 选择器解析失败回错误包（正确） |
| `script/CubeLutLoader.java:89-93` | LUT 解析失败负缓存 + ErrorLog（正确） |
| `script/ScriptManager.java:78-87` | 单脚本加载失败不影响其他脚本（正确） |
| `trigger/server/store/TriggerStateStore.java:96-108, 126-146` | 状态 IO 失败记录 + 清理临时文件（基本正确，见 B8） |
| `mixin/LaneCullStateMixin.java:162-175` | `Future.get()` 异常降级（正确） |
| `client/lane/LaneDiagnostics.java:149-152` | 诊断初始化失败 → 关闭诊断（正确） |
| `webui/WebEditorServer.java:263-268` | 连接级异常兜底（正确） |
| `webui/WebEditorApi.java:23-90` | WebUI 消息顶层 catch（正确，补栈） |

---

## 8. 真正致命清单（禁止捕获 / 禁止「try 一下继续」）

| 类别 | 典型触发点 | 处理 |
|---|---|---|
| `OutOfMemoryError` | `NativeImage` 分配（`LaneDebugCapture` / `TextureLoader`）、FBO / 纹理创建（`LaneRenderer.offscreenTarget`、`ColorAdjustPass.swapTarget`）、大 JSON | **不捕获**；让崩溃报告记录现场 |
| `StackOverflowError` | 递归 JSON / 场景图 | 不捕获 |
| GL 上下文丢失 | `RenderSystem` / `GlStateManager` 抛 `IllegalStateException`、驱动错误、`GLFW` 上下文失效 | 不捕获；捕获后继续只会让 GL 状态更乱，且每帧重试 |
| 渲染线程断言 | `RenderSystem.assertOnRenderThread`（`Not called from the render thread`） | 不捕获；这是结构性错误，必须暴露 |
| 非可选路径 `LinkageError` / `NoClassDefFoundError` | 核心类加载失败 | 不捕获 |
| 持久化数据损坏 | `TriggerStateStore` SNBT、`FabricConfig` JSON | **不静默**：ERROR（路径 + 异常）→ 明确终止该单元（重置该玩家状态 / 默认配置），不带着坏数据继续 |
| 线程契约违反 | 非主线程调用 `CameraManager` / `OverlayManager` / GL | ERROR + 明确拒绝该调用（返回），不要「try 一下继续」 |

> 实现约束：边界统一 `catch (Exception)`，**不要** `catch (Throwable)`（调试工具 `LaneDiagnostics` 的 `catch (Throwable)` 是例外，因为它失败即关闭诊断）。
> `Error` 会自然穿透到平台/崩溃报告，这是期望行为。

---

## 9. 加固顺序（建议）

### 第 1 批（P0，先止血；每批独立可验证）

1. **A1 + A2 + A3**：帧首边界 + 逐实例隔离（渲染线程不再因单个脚本/实例崩）。
2. **A4 + A5 + A12**：lane 渲染 / 合成 / sink 的渲染边界（GL 异常降级为「本帧不合成」）。
3. **A6/A7**：调色与 HUD 边界（含 framebuffer 绑定恢复）。
4. **B1 + B2 + B3**：服务端 tick / 触发器动作 / 事件分发隔离。
5. **B4 + B6 + E1**：包处理边界、命令入口边界、解析器统一异常类型。
6. **C1 + C2 + C3 + C4**：网络发送与包处理统一 `PacketGuard`（同时覆盖已知 `PreloadRequester` / `sendToPlayer` 缺口）。
7. **D1**：客户端 tick 五子系统隔离（性价比最高的一处）。

### 第 2 批（P1）

- A8/A9/A11、B5/B7/B8、C5/C7、D5/D6/D7/D8、E2/E11/E14/E16。

### 第 3 批（P2/P3）

- 静默 catch 清理（§1.4）、日志上下文补强、调试路径隔离（A11/A12/E18/E19）、可选探测首次日志（E5/E7）。

### 验收标准

1. 任一 Mixin 注入点 / 平台回调 / 包处理抛 `RuntimeException`：客户端或服务端**不崩**；控制台至少一条 ERROR（首次带全栈），文件有完整栈；下一帧 / 下一 tick 继续。
2. 注入 `OutOfMemoryError` / GL 上下文丢失 / 线程断言：**仍然崩**（未被吞），崩溃报告可见。
3. 每条边界日志能回答：边界名、包名/方向或命令、玩家、脚本 id、实例 id、阶段、失败后的降级动作。
4. 全仓「空体 / 仅注释 catch」= 0（当前 §1.4 列出的 16 处）。
5. 内部调用不再新增 try/catch；新增的 catch 只出现在边界（§1.1 表）。

---

## 10. 附：扫描覆盖

- **客户端**：`CameraManager.onRenderFrame` / `FrameDriver` / `ScriptLaneDriver.tick` / `OverlayManager.update/render` / `ScriptPlayer` / `PreloadRequester` / `AudioListenerReporter` / `ClientEntitySelectorCache` / `RuleEngine` / `ColorAdjustPass` / `LaneRenderer` / `LaneCompositor` / 全部 mixin 注入点。
- **服务端**：`TriggerEngine.tick` / `ScriptEventManager.tick` / `ChunkPreloadManager` / `CameraAnchorManager` / `Evaluators` / `TriggerStateStore` IO / `ServerEventHandler` / `CinematicCommand`。
- **网络**：全部 C2S/S2C `handle`、Forge/Fabric 平台注册与 `enqueueWork` / `execute`、`NetworkGuard` / `AckTracker` / `NetworkHandler`。
- **其它**：命令执行、配置读写（Forge/Fabric）、资源重载（LUT / ColorMask invalidate）、JSON 解析（ScriptParser / Validator / StructureResolver / WebUI）、`CompletableFuture.get()`（`LaneCullStateMixin`）、反射 / `Class.forName`（Iris / GameStages / ModList / advancements）、GL / 文件 IO（LaneDebugCapture / LaneDiagnostics / TextureLoader / WebUI 帧流）。

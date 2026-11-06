# WAIT_POINT 等待点轨道（0.3.6 设计框架）

**状态**: ⚠️ **已被 `condition-system.md` 吸收（2026-10-10 用户拍板）**——不新开轨道，条件 = 片段元数据（`clip.rules`）；本篇的「等待点」= 阻塞规则 `{until, do}`、判定点 = 时间条件特例，用例与 TBD 移 `condition-system.md` §2/§3/§9 接续。以下正文保留作历史框架参考。
**定位**: 独立于 EVENT 轨道的新轨道；脚本播放到等待点后，按它的规则决定后续：暂停等事件 / 继续 / 结束 / 接播下一个脚本 / 分支。

---

## 1. 设计原则

- 这是一个**新的轨道类型**，不是 EVENT 轨道的扩展。
- EVENT 轨道保持原有职责：到点触发指令 / 调用其他模组事件。
- WAIT_POINT 负责**时间轴上的控制流**：暂停等事件、继续、结束、接播、分支。
- 它是面向交互/分支/过场等待的设计框架，具体事件源和动作语义允许后续扩展。

---

## 2. 轨道结构

保持现行轨道模型：

```text
TimelineTrack(WAIT_POINT)
  └── Clip
       └── Keyframe
```

- 每个关键帧定义一个等待点；
- 支持在同一轨道中出现多个等待点；
- 等待点串行推进：一个等待点结束后，继续到下一个。

---

## 3. 关键帧数据框架

```json
{
  "time": 5.0,
  "wait": {
    "type": "button",
    "params": { "button_id": "respawn" },
    "timeout": {
      "enabled": true,
      "seconds": 10.0,
      "on_timeout": "continue"
    }
  },
  "action": "continue"
}
```

### 3.1 关键帧字段

| 字段 | 说明 |
|---|---|
| `time` | 等待点所在时间 |
| `wait` | 触发条件，类型 + 参数 + 可选超时（**必有**——这个轨道的本质就是“停下来等一个回报”） |
| `action` | 动作：继续 / 结束 / 接播下一个脚本 / 跳转 / 分支（触发分支轨道、事件）——框架先保留，语义后续定 |

### 3.2 `action` 候选语义（先定框架，不锁死）

- `end`：结束当前脚本；
- `continue`：继续播放当前脚本剩余部分；
- `next_script`：当前脚本结束后插入/接播另一个脚本；
- `jump`：跳到指定的时间点/关键帧（后续可用）；
- `branch`：执行分支（触发分支轨道 / event）；
- 未来可扩展为“事件树分支”，即不同事件对应不同 action。

---

## 4. 触发条件框架

### 4.1 触发方式（先列框架，来源后续补全）

| type | 说明 |
|---|---|
| `button` | 点击某个按钮 / GUI 控件 |
| `screen_click` | 点击屏幕空间坐标 / 区域 |
| `text_input` | 输入文本 |
| `key` | 键盘按键 |
| `mod_event` | 其他模组事件 |
| `server_event` | 服务端命令 / 触发器 / 自定义事件 |
| `game_event` | 玩家死亡、重生、交互等游戏事件（后续可扩展） |

### 4.2 通用条件结构

```json
"wait": {
  "type": "screen_click",
  "params": {
    "x": 0.5,
    "y": 0.5,
    "radius": 0.05
  }
}
```

### 4.3 多条件 / 事件树

框架预留：

```json
"wait": {
  "type": "any",
  "conditions": [
    { "type": "button", "params": { "button_id": "respawn" } },
    { "type": "key", "params": { "key": "skip" } }
  ]
}
```

- `any`：任一条件满足即继续；
- `all_of`：全部满足才继续；
- `branch`：不同事件走不同 `action`（事件树，后续定）。

---

## 5. 播放器行为

### 5.1 到达等待点

- 冻结全局脚本时钟（现状**没有**逐脚本冻结机制：脚本时钟 = `CameraManager.gameTimeSeconds`，仅在游戏暂停 / 编辑器预览暂停时冻结；见事实核查）；
- 相机、Overlay、Letterbox 等保持在等待点当前状态；
- 后续关键帧不再推进；
- 等待触发条件。

### 5.2 等待期间

- 脚本处于等待状态（停在等待点），不是游戏暂停；
- 与 `pause_when_game_paused` 是两个独立状态；
- 音频按各自片段的托管模式决定是否继续。

### 5.3 触发后

- 等待点被事件触发；
- 按 `action` 执行：
  - 继续播放；
  - 结束；
  - 插入/接播下一个脚本；
  - 跳转（后续）；
- 如果支持事件树，则按具体事件走对应分支。

---

## 6. 音频托管模式

- 音频托管是 **AUDIO clip 级属性**，不是脚本级；
- AUDIO clip 本身有固定时长，托管模式下：
  - 等待期间，该音频片段若仍在时长内，继续播放；
  - 非托管音频随脚本暂停；
- 音频托管需要和“游戏暂停”区分：
  - 游戏暂停：由 `pause_when_game_paused` 控制；
  - 脚本等待点（等待期间）：由音频片段的托管模式控制。

字段框架：

```json
{
  "type": "AUDIO",
  "clips": [
    {
      "start_time": 0,
      "duration": 30,
      "pause_managed": true
    }
  ]
}
```

- `pause_managed` 名称/取值后续定，可以先保留为布尔或枚举：
  - `false`：随脚本暂停；
  - `true`：等待期间继续播放。

---

## 7. 事件系统框架

- WAIT_POINT 不实现事件内容，只负责控制流：**监听事件 / 决定后续 / 触发分支**；
- 事件来源可以是：
  - 客户端 UI（按钮、屏幕坐标、文本输入、键盘）；
  - 服务端命令 / 触发器；
  - 其他模组通过 API 发送；
  - 游戏内事件（死亡、重生等）。
- 事件消息框架：

```json
{
  "type": "screen_click",
  "params": { "x": 0.4, "y": 0.6 },
  "player": "uuid"
}
```

- 具体事件命名、权限、多人同步等后续定。

---

## 8. 与现有系统关系

- 新增 `TrackType.WAIT_POINT`（现枚举为 6 值：CAMERA/LETTERBOX/AUDIO/EVENT/MOD_EVENT/OVERLAY，无此值）；
- 新增对应 TrackPlayer（工厂 `TrackPlayer.create` 的 switch 需加 case，现 `default` 抛 `IllegalArgumentException`）；
- 修改 `ScriptPlayer` / `CameraManager` 以支持等待点状态（现有锚点：`ScriptPlayer.getElapsedSeconds()` / `isFinished()` / `onRenderFrame()`，帧驱动唯一入口 `CameraManager.onRenderFrame()`，见事实核查）；
- EVENT 轨道维持现状（客户端不为 EVENT 建 TrackPlayer；EVENT 由服务端 `ScriptEventManager.onServerTick` 处理）；
- 死亡/重生可以作为 WAIT_POINT 的第一个实际用例，但不是本框架的硬编码场景。

---

## 9. 示例：死亡 / 重生

逻辑：

- 玩家死亡触发一个（新增的）`player_death` 触发器——现有触发器类型表中**不存在**此类型，见事实核查；
- 触发器启动一个死亡/重生脚本；
- 脚本播放死亡画面：死亡镜头、黑场、字幕等；
- 脚本到达某个 `WAIT_POINT` 等待点；
- 到达后，整个脚本时间轴停在当前位置，等待事件；
- 等待的事件是“点击重生按钮”；
- 点击后等待点被触发，执行 `action: continue`；
- 脚本继续播放等待点之后的剩余片段，例如重生动画、字幕、音乐；
- 可选：音频片段标记为托管模式，等待期间背景音乐继续播放；
- 原版死亡界面保持显示，玩家点击“重生”按钮就是等待点的事件（链路：`ClientPacketListener.handlePlayerCombatKill` 显示 `DeathScreen` → 按钮 `LocalPlayer.respawn()` → `ServerboundClientCommandPacket(PERFORM_RESPAWN)` → 服务端 `ServerGamePacketListenerImpl.handleClientCommand` → `PlayerList.respawn` → `ClientboundRespawnPacket` → 客户端 `ClientPacketListener.handleRespawn` 关界面）；
- 点击后触发 `action: continue`，脚本继续播放；
- 实际重生由原版流程执行，脚本只负责“点击后继续播放”，不隐藏死亡界面。

---

## 10. 后续待定项（TBD）

1. `action` 的精确语义：`continue / end / next_script / jump / branch`；
2. 超时行为的完整定义；
3. `any / all_of / branch` 事件树的最终形式；
4. 多玩家 / 广播脚本下，等待点按玩家独立还是全局共享；
5. 按钮、屏幕坐标、文本输入等 UI 事件的具体渲染与交互方式；
6. 外部模组事件接入 API；
7. 等待点能否在等待期间允许其他轨道（非音频）继续；
8. 编辑器中的可视化与编辑方式。

---

## 事实核查（2026-10-07）

核查依据：MC 1.20.1 sources.jar（`minecraft-merged-...-sources.jar`，解出至 `build/mc-src-factcheck/`）、`C:\Users\yjsng\AppData\Local\Temp\mcsrc\net\minecraft\client\Minecraft.java`、本仓库 `common/src/main/java/...`。

### ① 核实为真的断言

| 断言 | 证据 |
|---|---|
| §2 轨道结构 `TimelineTrack → Clip → Keyframe` 成立 | `Clip`、`Keyframe` 都是与轨道类型无关的通用容器（`trackType` + `data` map + `time`），无各轨道独立子类 — `script/Clip.java`、`script/Keyframe.java` |
| §5.1 相机/Overlay/Letterbox 状态由轨道写入、可"停在当前状态" | 各轨道逻辑已抽到 TrackPlayer 实现（CameraTrackPlayer/LetterboxTrackPlayer/OverlayTrackPlayer），由 `ScriptPlayer.onRenderFrame` 调度 — `script/ScriptPlayer.java:344-` |
| §5.2 等待态与 `pause_when_game_paused` 是两个独立状态 | `pause_when_game_paused` 是脚本 meta 字段（`ScriptMeta.RuntimeBehavior.pauseWhenGamePaused`，默认 true），与等待点无任何现有耦合 — `script/ScriptParser.java:117`、`control/CinematicController.java:42`、`script/schema/MetaSchemas.java:42` |
| §8 `TrackType.WAIT_POINT` 尚不存在 | 枚举仅 6 值 CAMERA/LETTERBOX/AUDIO/EVENT/MOD_EVENT/OVERLAY — `script/TrackType.java:26-49` |
| §8 EVENT 轨道"维持现状"= 客户端不处理、服务端按 keyframe 命令执行 | 客户端 `buildTrackPlayers` 显式跳过 `TrackType.EVENT`（`script/ScriptPlayer.java:146-152`）；服务端 `ScriptEventManager.onServerTick` 遍历 EVENT clip 的 keyframe `command` 执行 — `trigger/server/ScriptEventManager.java` |
| §6 `pause_managed` 字段不存在 | 全仓 grep `pause_managed` 仅命中计划文档（本文件 + `example/jm/.../pause-point-track.md`），无任何 Java/JSON 引用 |
| §9 死亡界面由原版显示、点击重生按钮是真实事件 | 见 ③ 完整链路（sources.jar 实测） |
| 死亡界面期间游戏**不暂停**（关键事实） | `DeathScreen.isPauseScreen()` 返回 `false`（DeathScreen.java:133-136）；`Minecraft.isPaused()` 仅返回 `pause` 字段，而 `pause` 只在 `screen.isPauseScreen() && 单机 && 未发布` 时为 true — Minecraft.java:2176-2178、1063-1070。故死亡界面挂着时本模组的脚本时钟仍在推进 |

### ② 已修正的断言

| 旧说法 | 新事实 + 证据 |
|---|---|
| §9「玩家死亡触发 `player_death` 触发器」 | 现有触发器类型表（`TriggerSchemas.typeList()`，共 25 项：login/location/advancement/biome/entity_kill/entity_interact/block_interact/item_on_interact/dimension_change/item_craft/item_use/item_consume/item_release/item_instant_use/item_use_interrupt/item_pickup/item_drop/xp/dimension/observation/inventory/structure/gamestage/facing/all_of）**不含 `player_death`**（`script/schema/TriggerSchemas.java:20-78`）；`ServerEventHandler` 也无玩家死亡钩子（唯一 `onLivingDeath` 只记录击杀者，`handler/ServerEventHandler.java:88-93`）。故该触发器是**新增需求**，不是现状 → 正文已改为"（新增的）`player_death` 触发器" |
| §8「修改 `ScriptPlayer` / `CameraManager` 以支持等待点状态」过泛 | 已具体化到类/方法，见 ③「§8 接入点」 |

### ③ 补全的信息

**§9 死亡 / 重生链路（MC 1.20.1，sources.jar 实测类名+方法名）**

1. 服务端 `ServerPlayer.die(DamageSource)`（ServerPlayer.java:558）→ 发送 `ClientboundPlayerCombatKillPacket`（:563 或 :579）。
2. 客户端 `ClientPacketListener.handlePlayerCombatKill(ClientboundPlayerCombatKillPacket)`（ClientPacketListener.java:1466-1473）：若 `player.shouldShowDeathScreen()` → `setScreen(new DeathScreen(msg, hardcore))`；否则（`doImmediateRespawn` gamerule 为真）直接 `player.respawn()`。
3. `DeathScreen`（client/gui/screens/DeathScreen.java）：`init()` 建重生按钮（label `deathScreen.respawn`，硬核为 `deathScreen.spectate`），点击回调 = `this.minecraft.player.respawn()` + 按钮置灰（:44-51）；按钮显示后 20 tick（`delayTicker`，:139-145）才激活；`shouldCloseOnEsc()` 为 false（:59-61）；`isPauseScreen()` 为 false（:134-135）。
4. `LocalPlayer.respawn()`（client/player/LocalPlayer.java:297-300）→ 发 `ServerboundClientCommandPacket(Action.PERFORM_RESPAWN)`。
5. 服务端 `ServerGamePacketListenerImpl.handleClientCommand`（server/network/ServerGamePacketListenerImpl.java:1470-1489）case `PERFORM_RESPAWN` → `server.getPlayerList().respawn(player, false)`（:1485）。
6. `PlayerList.respawn(ServerPlayer, boolean)`（server/players/PlayerList.java:425-493）→ 重建/搬移玩家并发送 `ClientboundRespawnPacket`（:476）。
7. 客户端 `ClientPacketListener.handleRespawn(ClientboundRespawnPacket)`（ClientPacketListener.java:1021-1075）：若当前 screen 是 `DeathScreen` 或 `DeathScreen.TitleConfirmScreen` → `setScreen(null)`（:1071-1072）。

> 要点：**关闭死亡界面发生在服务端确认重生之后**（收到 `ClientboundRespawnPacket`），不是点击瞬间——WAIT_POINT 若以"点击重生"为触发条件，`action` 执行时界面可能仍在，界面消失要等 `handleRespawn`。

**`LocalPlayer.deathTime` / `isDeadOrDying()`**：`deathTime` 定义在 `LivingEntity.public int deathTime`（LivingEntity.java:194），`tickDeath()` 每 tick +1、≥20 且非客户端时移除实体（:531-536）；`LocalPlayer.resetPos()` 会回满血并把 `deathTime` 置 0（LocalPlayer.java:639-640）；`isDeadOrDying()` = `getHealth() <= 0`，定义在 `LivingEntity`（:979-981）。

**§5 时钟 / 结束判定现状**：`ScriptPlayer.getElapsedSeconds()` = `CameraManager.INSTANCE.getGameTimeSeconds() - startGameTimeSeconds`（ScriptPlayer.java:436-438）；`isFinished()` 由 `script.getTotalDuration()` + `hasActiveInfiniteLoopClip()` 判定（:283-296）；每帧由 `CameraManager.onRenderFrame()`（CameraManager.java:398）调用 `scriptPlayer.onRenderFrame(effectiveTime)`（:468）。现状**没有逐脚本冻结时钟**：`freezeTime = gamePaused || (previewMode && previewPaused)`（CameraManager.java:441），其中 `gamePaused = Minecraft.isPaused() && CinematicController.INSTANCE.isPauseWhenGamePaused()`（:404）。

**§5.2 / §6 音频暂停现状是全局的**：`CameraManager.onRenderFrame` 每帧 → `ScriptPlayer.pauseAudio()` → `AudioTrackPlayer.pauseAll()` → `Minecraft.getSoundManager().pause()`（AudioTrackPlayer.java:144-149；对应 `resumeAll` :152-157）。没有逐 clip 的托管判断。

**§8 接入点（具体到类 / 方法）**

- 枚举：`script/TrackType.java` 加值；
- 工厂：`TrackPlayer.create(...)` 的 `switch`（script/TrackPlayer.java:65-77）加 `case`，当前 `default -> throw new IllegalArgumentException`；
- 实例化：`ScriptPlayer.buildTrackPlayers(CinematicScript)`（ScriptPlayer.java:146-153）；
- 停表 / 短路：`ScriptPlayer.onRenderFrame(double)`（:344）与 `isFinished()`（:283）需感知等待态；`getElapsedSeconds()`（:436）是唯一时钟读取点；
- 帧驱动唯一入口：`CameraManager.onRenderFrame()`（CameraManager.java:398），`freezeTime` 分支（:441-446）是现成的"停表"位置；
- skip / 暂停链路（等待点需要与它们区分）：`CinematicKeyBindings`（长按 ≥ `Config.skipHoldThresholdMs`）→ `CameraManager.requestExit(ExitReason.USER_SKIP)`（CameraManager.java:113-121，受 `CinematicController.isSkippable()` 门控）→ `deactivate()`（渐出）→ `deactivateNow()` → `ScriptPlayer.stop(CompletionReason)`（ScriptPlayer.java:253）；
- 编辑器 schema：`TrackSchemas.all()`（script/schema/TrackSchemas.java:17-29）按 `TrackType` 登记；`ScriptParser.parseTrackType` 用 `TrackType.valueOf(value.toUpperCase())`，错误信息硬编码支持列表（ScriptParser.java:653-657）。
- §4.1 `mod_event` 事件源现状：`ModEventTrackPlayer` 是占位实现（`isActiveAt` 恒 false、`onRenderFrame`/`onStop` 空），即第三方模组事件轨道目前**无实现**（script/ModEventTrackPlayer.java）。

### ④ 无法核实 / 未验证

- §4.1 各触发方式（`button` / `screen_click` / `text_input` / `key` / `server_event` / `game_event`）在本仓库无任何对应实现（全仓 grep `WAIT_POINT`/`WaitPoint` 无 Java 命中）→ **未验证**（框架待定）。
- §3 / §4 的 `wait` / `action` / `timeout` / `any` / `all_of` JSON 字段在 `ScriptParser` 与 schema 中不存在 → 未实现（框架）。
- §9「脚本播放死亡画面（死亡镜头、黑场、字幕）」：CAMERA/OVERLAY 轨道本身存在，但"死亡期间由本模组继续控制相机并保持死亡界面"需运行时验证；本仓库无任何死亡/重生相关特判（`common/` 内 grep `DeathScreen`/`deathTime`/`isDeadOrDying`/`respawn` 全部无命中）→ 未验证。
- §9「超时后 `on_timeout: continue`」：无对应实现 → 未验证。

### 冲突裁决

- 文档 `plans/0.3.6/wait-point-track.md` 最后修改 `2026-10-05T17:42:28+08:00`；相关代码最后修改：`ScriptPlayer.java` `2026-08-30T22:56:16+08:00`、`CameraManager.java` `2026-09-09T10:42:54+08:00`、`TrackType.java` `2026-07-27T16:39:22+08:00`、`TriggerSchemas.java` `2026-09-29T14:12:03+08:00`、`TrackSchemas.java` `2026-09-17T13:38:01+08:00`。
- 逐条比对：文档对 `WAIT_POINT` / `pause_managed` 均以"新增 / 后续定"表述，与代码无实质冲突，无需裁决；唯一以现状口吻写出的错误（`player_death` 触发器已存在）已按代码修正——文档虽修改时间更晚，但该处属于"把待定写成现状"的笔误，按代码事实修正而非按时间裁决。
- 跨文档冲突：`example/jm/ImmersiveCinematics-main/.../plans/0.3.6/pause-point-track.md` 是本文件的旧副本（内容基本一致，仅 `pause_managed` 说明措辞不同），未在本任务范围内修改。

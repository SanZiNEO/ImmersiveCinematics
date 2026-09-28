# 04 触发器 → 命令的延迟构成（轮询 + 播放链路）

- **来源**：代码排查（2026-09-28，配合 loop_stairs 传送脚本）
- **状态**：部分已调（配置项）；需要改代码的项暂不做
- **相关**：`TriggerEngine.onServerTick`、`ScriptManager.registerAllTriggers`、`ScriptEventManager`、`ForgeConfig.triggerPollInterval_location`

## 延迟构成（按代码链路推导）

1. **轮询检测**：location 触发器每 `triggerPollInterval_location` tick 检测一次（默认 20 tick = 1 秒，可配 1~600）。最坏 1 秒、平均 0.5 秒。
2. **播放链路**（检测到之后的固定开销 ≈ 2 tick ≈ 0.1 秒）：
   触发 → `StartPlaybackAction` 发播包 → 客户端起播并回 `playback_started` → 服务端建事件时间线（`startTick` = 当前 tick）→ 同一 tick 命中 t=0 的 EVENT 关键帧 → 执行命令。
3. 合计（默认配置）：最坏 ≈ 1.1 秒，平均 ≈ 0.6 秒。

## 已做（配置，非代码）

- 用户侧 `config/immersive_cinematics-common.toml`：`triggerPollInterval_location` 20 → **2**（0.1 秒检测一次）。
- 调整后总延迟 ≈ 0.1~0.2 秒。**重启游戏生效**（Forge 配置在 `ModConfigEvent` 时加载）。

## 待改进（需改代码，暂不做）

1. **触发器不能直接执行命令**：脚本格式的 trigger 固定只有"启动本脚本"一种动作（`registerAllTriggers` 里写死 `List.of(new StartPlaybackAction(meta.getId()))`）；`ExecuteCommandAction` 类存在但全工程无构造点（死代码）。若允许触发器直接执行服务端命令，可省掉"客户端往返"（≈0.1 秒），也省掉"为一个 1 秒脚本建文件"的样板。
2. **轮询间隔是按类型全局的**（所有 location 触发器共用一个值），不支持按触发器/按脚本单独设置；对少量触发器影响不大。
3. （不建议）事件时间线目前等客户端 ack 才启动；乐观启动可再省 ~1 tick，但会破坏暂停同步。

## 附

- 轮询下限 1 tick（0.05 秒）；链路本身 ≈ 0.1 秒——再往下优化收益很小。

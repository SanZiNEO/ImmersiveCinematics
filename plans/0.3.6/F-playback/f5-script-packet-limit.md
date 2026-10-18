# F5 大脚本传输上限：`play` 报「意外错误」

日期：2026-10-18
范围：F 轮收尾遗留项（F5 性能矩阵）

## 现象

`/icinematics play fround:ic_f_perf_l25` 在聊天栏只返回：

```
试图执行该命令时出现意外错误
```

没有 `perf_start`，也没有性能文件。l25 的 FULL / WORLD_ONLY 两档因此一直缺测。
同一批次的 `ic_f_perf_l16` 可以正常播放并完成 600 帧。

## 证据

- 会话：`fabric/run/immersive_cinematics/diagnostics/sessions/20261018-165515-f5-acceptance`
- 日志同一时刻：

```
[17:07:20] [Render thread/WARN] (AckTracker) ACK 重发失败(可能已断线),放弃: 59237519
  - String too big (was 46944 characters, max 32767)
```

- 脚本体积：
  - `ic_f_perf_l25.json`：47,060 字节 / 46,944 字符 → **超限**
  - `ic_f_perf_l16.json`：30,410 字符 → 未超限，能播
- 启动日志里 l25 能正常加载（`Loaded script: ... (id=ic_f_perf_l25)`），说明解析 / 注册没问题；失败发生在 `play` 把脚本发给客户端时。

## 根因

服务端把**整份脚本 JSON 当成一个字符串**发给客户端。Minecraft 的字符串字段上限是 **32,767 字符**，超过后发送失败，命令层只看到异常，于是统一显示「试图执行该命令时出现意外错误」。

不是 `meta` 写多了：meta 只有几十个字符。体积来自 25 条 CAMERA 轨 × 每条 2 个关键帧，以及每个关键帧里大量与默认值相同的字段（`roll` / `fov` / `zoom` / `opacity` / 全屏 `dest` / 全屏 `source` 等）。

## 影响

- 任何序列化后超过 32,767 字符的脚本都无法通过 `/icinematics play` 播放；不是 l25 测试脚本独有的问题。
- F5 的 l25 两档被阻塞，F5 矩阵无法整体关单。
- l16 的 30,410 字符已经接近上限，继续加轨 / 关键帧就会踩到同一条线。

## 修复（2026-10-18）

**方案 = 压缩后按字节发送 + 长度前缀 + 双向上限 + 命令入口预检**（对应上文建议 2 + 3；分片传输本轮不做）。

`S2CPlayScriptPacket` 的载荷从「整份 JSON 当字符串」改为「gzip 字节数组 + `writeVarInt` 长度前缀」：

| 项 | 值 |
|---|---|
| 字段 | `byte[] scriptGzip` + `refId` + `instanceId` |
| 线格式 | `writeVarInt(len)` → `writeBytes(gzip)` → `writeUtf(refId)` → `writeUtf(instanceId)` |
| 解压后上限 | 4 MiB（`MAX_SCRIPT_JSON_BYTES`） |
| 压缩后上限 | 1 MiB（`MAX_COMPRESSED_BYTES`，读侧同样校验，防畸形包） |
| 构造 / 解码 | `encode` / `decode` 抛 `IllegalArgumentException`，消息带具体字节数 |
| 发送 | `send(...)` 返回 `boolean`；超限只记 ERROR 日志、不抛（触发器路径不崩服务端） |
| 命令入口 | `CinematicCommand.playScript` 在 `ScriptStructureResolver` 之后、`AckTracker.expect` 之前调 `validate`，超限 `sendFailure("§c脚本 JSON 过大：…")`、记一条 `WARN 拒绝播放脚本 <path>：…` 并 `return 0`——不再落到「意外错误」，也不登记 ACK 触发重发 |
| 触发器入口 | `StartPlaybackAction.sendTo` 发送失败只 `warn`，按玩家跳过 |

协议是同一版本客户端/服务端的线格式变更，不做旧版本兼容（项目既定版本策略）。

### 验证

- **原始脚本直接播放**：`ic_f_perf_l25.json` 原始 47,757 字节，只去掉长驻 `OVERLAY` 字幕轨后 47,061 字节（仍 > 32,767），未做任何默认值压缩 → 客户端日志 `Playing script from server: ic_f_perf_l25`，无 `String too big`、无 `ACK 重发失败`。
- **性能矩阵补齐**（会话 `diagnostics/sessions/20261018-173824-f5-acceptance`，Fabric / Iris 1.7.6 + Sodium + ComplementaryUnbound，vsync 关 / maxFps 260 / 视距 12 / 600 帧）：

| 档 | valid | lane rows | lane failure frames | game_render p50 / p95 | lane_total p50 / p95 | composite p50 / p95 | frame_interval p50 / p95 |
|---|---|---|---|---|---|---|---|
| `perf_l25_full`（run 1） | 600/600 COMPLETE | 15000 | 0 | 27.26 / 39.43 ms | 22.87 / 33.24 ms | 0.47 / 0.81 ms | 169.31 / 174.68 ms |
| `perf_l25_world_only`（run 2） | 600/600 COMPLETE | 15000 | 0 | 18.88 / 21.74 ms | 15.07 / 17.30 ms | 0.39 / 0.48 ms | 161.48 / 166.13 ms |

- **负例**：`too_big.json`（4,400,160 字节，合法 JSON，`meta.description` 为超长字符串）执行 `/icinematics play fround:too_big` → 服务端 `WARN (ImmersiveCinematics/Command) 拒绝播放脚本 …too_big.json：脚本 JSON 过大：4400160 字节，超过 4194304 字节上限`，无异常堆栈，客户端无 `Playing script from server: too_big`（脚本未进入播放）。玩家直接执行时同一文本经 `sendFailure` 进聊天栏；EVENT 轨命令源（`SuccessOnlySource`）按设计吞掉失败反馈，故该路径以 WARN 日志为准。
- 构建：`sh gradlew compileJava --offline`、`sh gradlew :fabric:build --offline` 均 `BUILD SUCCESSFUL`。

## 状态

- 已修复（2026-10-18）：gzip 字节传输 + 双向上限 + 命令入口预检已落地。
- F5 矩阵**完整**：l0 / l1 / l4 / l16 / l25 的 FULL 与 WORLD_ONLY 均 600/600。
- 验收侧按默认值压缩的临时脚本（24,869 字符）已不需要，未提交。
- 失败处理审计（`failure-handling-audit.md`）的 P0 批次**仍开放**，不属本条。
- 关联：`f-round-issues.md` §1.1 / §5、`implementation-progress.md` F5 行。

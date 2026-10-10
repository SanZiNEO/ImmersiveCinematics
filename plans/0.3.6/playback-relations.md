# 播放关系与队列（0.3.6 · 定稿 2026-10-10）

> 状态：核心模型已由用户拍板（三态关系枚举 + 声明于脚本 meta）；§2 语义轴（在播侧）与 §4 组合规则为按用户口径推导的定稿建议，§7 标「待拍板」处需用户复核。
> 实现批次 = **C 批**（用户 2026-10-10 指定：新系统归 C，不进 B 批）。
> 关系文档：`parallel-playback.md`（实例/lane/叠加秩序，本篇不重复）；`next-phase-batches.md` F2（队列按脚本匹配接播，本篇 §3 覆盖并扩展）；`scene-transition.md`（转场与打断交互，本篇 §6.3）；条件系统见 `condition-system.md`（播放中干预，与本篇正交）。

## 1. 现状与问题（2026-10-10 调查实证）

现状 = **跨脚本全并行 + 同脚本单实例**（可打断→硬切替换 / 否→入队），播放 = 实例（`PlaybackInstance`），帧驱动 `CameraManager.onRenderFrame` 按启动顺序逐实例、后写覆盖（顶层胜出）。三个 bug 级缺口：

| # | 问题 | 证据 |
|---|---|---|
| 1 | **队列形同虚设**：所有 `deactivateNow` 路径在 `poll` 前已 `clear`（非空分支直接 clear，空分支经 `reset()`）→ 不可打断脚本的第二份请求入队后永久丢失 | `camera/PlaybackLifecycle.java`（playScript→offer→deactivateNow→clear） |
| 2 | **`ExitReason.INTERRUPTED` 不可达**：`requestExit` 的 INTERRUPTED case 全仓无调用者 | `PlaybackLifecycle.requestExit` |
| 3 | **渐出是全局的**：`deactivate` 走 `OverlayManager.INSTANCE.startFadeOut()`，会把**其他实例**的 overlay 一起淡掉 | `PlaybackLifecycle` deactivate 路径 |

其他现状事实（保持，不在本篇改）：相机全局写缓冲后来者居上；音频 per-instance 各响各的；overlay 全局 zIndex 混排；输入/HUD 全实例并集；听者/预加载/跳过提示按顶层实例。完整 30 行语义空间穷举存 `.planning/036-orch/` 调查记录，本篇只收裁决。

## 2. 播放模式（定稿：三态枚举 @ 脚本 meta）

`meta.play_mode`，三值，声明**该脚本在播期间与其它脚本的关系**（在播侧语义；到达行为由在播者集合决定，见 §4）：

| 取值 | 中文 | 在播侧语义 | 缺省 |
|---|---|---|---|
| `parallel` | 并行 | 不阻塞任何新来者（直接并行加入）；也不被跨脚本打断 | ✅（缺省 = 0.3.6 现状） |
| `exclusive` | 独占 | 我播时其他脚本的启动请求一律**排队**，我播完按序接播 | — |
| `interruptible` | 可打断 | 我可被新来者**打断**（顶掉，实例销毁） | — |

- 取值域外/缺失 = `parallel`（旧脚本零回归）。
- 在播侧为轴：「只能他在播放、其他的都要排队」= `exclusive`；「其他的会直接把它打断」= `interruptible`；「并行播放多个脚本」= 双方 `parallel`。
- 与既有 `meta.interruptible`（bool）的关系：旧字段管**同脚本**再触发的替换语义 + 退出原因门控（requestExit 按 skippable/interruptible/hold_at_end 门控），保留不动；`play_mode` 管**跨脚本**关系。两者同名值 `interruptible` 语义同向（都是「可被打断」），文档与 schema 注释写明分工；是否合并字段 = §7 待拍板。
- `meta.priority` 保留（队列内排序）；既有口径「优先级不产生打断」不变——优先级只决定排队次序，不产生抢占。

## 3. 队列语义（定稿；覆盖并扩展 F2）

| 项 | 定稿 | 说明 |
|---|---|---|
| 队列键 | 脚本 id | 同脚本第二次请求**替换**自己的排队位（不再占一格） |
| 跨实例存活 | **队列不随在播实例结束而清空**（修 §1-1 bug） | 接播按队列持续滚动；仅世界退出/forceDeactivate 清空 |
| 出队条件 | 阻塞它的 `exclusive` 实例全部结束 | 无 exclusive 在播 = 不排队（parallel 不阻塞） |
| 排序 | `meta.priority` 降序 + FIFO | 现 `ScriptQueue`（PriorityQueue，容量 8）不变 |
| 满容量 | 拒绝新请求（现状返回码不变） | 「替换最旧」= §7 待拍板 |
| 接播形态 | 硬切起步（现状）；转场起步归 scene-transition | — |

`ScriptQueue` 需补「按脚本匹配」API（F2 原口径）：同键替换排队位、按脚本取消（E3）。

## 4. 到达组合规则（新来者 B 到达时，按在播集合 S 裁决）

| S 的构成 | B 的去向 |
|---|---|
| 空 | 直接开播 |
| 仅 `parallel` | 直接并行加入（现状） |
| 含 `exclusive`（无论其它） | B **入队**（S 全部结束才接播） |
| 含 `interruptible` 且无 `exclusive` | B **打断**该 interruptible 实例（只顶它，S 里的 parallel 实例继续）后开播 |

- B 开播后，B 自己的 `play_mode` 对后续到达者生效。
- 同脚本再触发（单实例口径不变）：既有 `meta.interruptible` 路径（true→替换 / false→入队）保留；与 §3 队列键规则一致（false 时同键替换排队位）。
- 打断形态：默认**硬切**（现状）；渐出接 = `play_mode` 附加字段 `on_interrupt: "cut" | "fade"`（§7 待拍板）。
- 「等 A 播完再播 B」的串行意图用触发器 `requires`（script_played/started/completed，现状已有）表达，**不**新增队列原语。

## 5. 跨脚本控制原语（本期做/不做）

| 原语 | 裁决 | 归属 |
|---|---|---|
| 排队候播 / 同键替换排队位 | ✅ 做 | §3 |
| 按脚本 id 取消排队项（E3） | ✅ 做 | ScriptQueue API + 控制入口 |
| 按实例/脚本精确停止非顶层（E9，现状 stop 只对顶层生效） | ✅ 做（修缺陷） | `ClientScriptReceiver.handleStopScript` |
| 插队（提到队首） | ⛔ 不做（priority 已表达） | — |
| 组与标签整组进出（E6） | ⛔ 不做（维持 parallel-playback §3.6「互斥组不做」） | — |
| 跨脚本抢占（无视 interruptible 硬抢） | ⛔ 不做（forceDeactivate 已是管理端出口） | — |
| 被打断者可恢复续播（C4） | ⛔ 不做（停即销毁；恢复归未来「挂起/恢复」议题） | — |

## 6. 冲突裁决与异常（保持现状 + 两处修复）

1. 共享资源裁决维持现状表（§1）；「主相机声明」（F1）不做。
2. **渐出 per-instance**（修 §1-3）：`deactivate` 只淡出本实例的层；全局 `startFadeOut` 仅保留给全停场景。
3. 异常生命周期维持现状：退世界/断线 = `emergencyStop` 清全部；`pause_when_game_paused` 并集暂停；`hold_at_end` 驻留实例**算**「在播」（参与 exclusive 阻塞判定与并集）；跨维度运镜（0.4.0 F 类）与本篇正交。
4. 玩家死亡处置归 C2（death 触发器），本篇不定义。

## 7. 待拍板清单

| # | 问题 | 候选 |
|---|---|---|
| 1 | 语义轴确认：在播侧（本篇 §2）还是新来者侧（新来者声明我要并行/排队/打断）？ | 本篇按**在播侧**（贴用户原话）；若要新来者侧，仅换 §2 表头语义，字段不变 |
| 2 | `meta.interruptible` 与 `play_mode` 是否合并为单字段 | 本篇保守：共存分工；合并需迁移同脚本语义 |
| 3 | 打断形态硬切/渐出 | 本篇缺省硬切；要不要 `on_interrupt` 字段 |
| 4 | 队列满时策略 | 本篇保持拒绝；备选替换最旧 |
| 5 | interrupt 到达时队列处置 | 本篇：队列不动、打断者播完继续出队；备选：打断者插到队首 |

## 8. 实现清单（C 批任务切分建议）

1. 队列可达性修复 + 按脚本匹配 API（§3，含 §1-1 bug）。
2. `meta.play_mode` 解析/校验/schema/文档 + 到达组合规则（§2/§4）。
3. 打断与渐出 per-instance（§1-2/§1-3/§6-2）。
4. 控制原语补齐（§5：取消排队、按 id 停非顶层）。
5. 实机验收：三态各一组对照脚本 + 队列接播 + 打断场景；缺省 `parallel` 行为 = 现状基线（等值类对拍口径，见进度表「版本策略」）。

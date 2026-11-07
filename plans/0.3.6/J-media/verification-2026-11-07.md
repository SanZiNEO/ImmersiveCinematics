# J-1 实机验收记录（2026-11-07）

> 范围：J-1 遗留收口（A–I 轮 8 项欠账）的实机证据。
> 环境：Fabric 1.20.1 dev 客户端（`ic client`，world `新的世界`，`pauseOnLostFocus=false`，`enableVsync=false`）；Forge 1.20.1 dev 客户端（`:forge:Client` + 临时 `--quickPlaySingleplayer`，测完已回滚）。
> 取证钩子：临时类 `debug/J1Trace`（`ICINEMATICS_J1TRACE=1` 门控，逐帧打印 `frame / lane / global`）——**已移除**，不入库（归档 `E:/tmp/j1-trace-archive/J1Trace.java`）。
> 日志：`fabric/run/logs/latest.log`、`forge/run/logs/latest.log`（均含历史多轮，按时间段切分）。
> 截图：`E:/tmp/j1-shots/`。

## 一、内联片段默认出口（T1）

夹具：`cinematics/0.3.6/tests/rules/test_standalone_camera_branch.json`（改为 `intro.exit = {on_complete:"goto", target:"cam_standalone", entry_time:0}`，删 rules/rule_bindings）。

| 判据 | 实测 |
|---|---|
| 2s 前 | 只有 OVERLAY `intro` 活跃；`pos=0.000/1.171`（无相机 lane） |
| t=2s | `intro` 完成 → `cam_standalone` 分支启动（lane 出现：`clip=cam_standalone local=0.002 p=(0.003,64.000,0.003)`） |
| 2s→12s | 相机按 (0,64,0)→(20,64,20)、yaw 0→90 线性推进；t=12 处 `local=9.998 p=(19.995,64.000,19.995) yaw=89.979` |
| 结束 | `elapsed=12.003 finished=true`，`[camera-lease] release ... active=0`（自然结束） |

结论：内联片段 `exit.goto` 真的启动独立 CAMERA 片段，无需规则。

探针（仓库外 `E:/tmp/j1-exit/`）：**28/28 PASS**，覆盖普通片段 goto / end_script / continue、链式两段、入口覆盖优先、seek 不合成完成事件、`endBranch` 后不重复派发。

## 二、B18 ④ 过渡「打断」（T4）

夹具：`test_camera_transition_b18_b.json`（c2_1→c2_2 交界 6s，c2_2 声明 `interrupt=snap`；c2_4→c2_5 交界 29s，c2_5 声明 `interrupt=blend`）。
逐帧六参数（global 快照，`t` 与脚本 elapsed 同源）。

| 窗口 | max\|Δ\|/帧 | 结论 |
|---|---|---|
| ④ snap @6（5.0–7.5） | px **15.002**、yaw **90.015**（t 5.998→6.002 单帧），其余 0 | 一次硬切到 B 侧常值（px 159.7→174.8、yaw 29.5→120.0），**无双重混合** |
| snap 窗 [6,18) | yaw 恒 **120.000**（1948 帧） | 窗口内恒取 B |
| ④ blend @29（28.0–31.5） | px **0.012**、yaw **0.090** | **零跳变**（连续） |
| blend 窗时长 | yaw 209.957→300.000，[29,41) 共 **12s** | 从打断时刻重新起算、**不居中**（= 声明 maxDuration 12） |

与 `snap` 的可见差异：snap 单帧 90° / 15 格硬切 vs blend 每帧 0.09° / 0.012 格。

## 三、B18 ⑤ 过渡「链式」（T5）

同一次装置、同一夹具：c2_7(43s, 4s 窗) → c2_8(48s, 4s 窗) 两段首尾相接。

| 判据 | 实测 |
|---|---|
| 衔接点 t=48 连续性 | px **0.025**、yaw **0.155**（47.702→47.715 附近最大值）→ **无跳变** |
| 第二段起点 | 从第一段实时混合态续接（47.5–48.0 期间 yaw 368.8→374.8 单调、p 209.78→210.75 连续，48 后不重置） |
| 对照（c2_6 `cut` @43） | px **8.000**、yaw **50.000** 单帧硬切 —— 预期（声明 cut） |
| yaw 374.9→15.0（t=50） | 数值回绕（同角 mod 360），非画面跳变 |

## 四、B19 迟滞四象限（T6）

夹具：`cinematics/0.3.6/tests/camera/test_camera_hysteresis_quadrant.json`（4 条 CAMERA 轨、`dest` 四象限、同一方波轨迹：t=2 位置 x 0→10 / yaw 0→90，t=6 回 0；2.0→2.001 为 1ms 线性近似 step）。

逐帧 yaw（每 lane 自己的 state）：

| t | 左上 对照 | 右上 exponential_smooth | 左下 spring | 右下 inertia |
|---|---|---|---|---|
| 2.05 | 90.00 | 27.17 | 1.00 | 74.44 |
| 2.10 | 90.00 | 45.08 | 3.53 | 77.82 |
| 2.31 | 90.00 | 78.59 | 24.79 | 87.51 |
| 2.50 | 90.00 | 86.97 | 52.03 | 92.02 |
| 2.80 | 90.00 | 89.57 | 85.67 | **93.63（过冲）** |
| 3.21 | 90.00 | 89.97 | **104.34（过冲）** | 92.22 |
| 4.00 | 90.00 | 90.00 | 91.97 | 90.00 |
| 5.90 | 90.00 | 90.00 | 90.39 | 90.00 |

人眼结论（逐型一句话）：
- **对照（无迟滞）**：阶跃当帧到位，跟手无滞后、无过冲。
- **exponential_smooth**：明显滞后（+0.1s 仅 45°），无过冲，约 1.5s 收敛——最「稳」但最不跟手。
- **spring**：起步最慢、过冲最大（峰值 104.3°，超目标 14.3°）并带振荡回落，约 3s 收敛——最「弹」。
- **inertia**：起步几乎与对照同帧、轻过冲（93.6°）、最快收敛（~2s）、稳态零滞后——最接近「无迟滞但顺滑」。

帧率档位：60 / 120 / 解锁（260）三档各同屏看过（截图 `E:/tmp/j1-shots/b19_60fps_*.png` 等）；四型的滞后/过冲关系不随档位变化，仅采样粒度变化。

## 五、C-pause 复验（T7）

夹具：`cinematics/0.3.6/tests/rules/test_cpause_event.json`（CAMERA 20s + EVENT `say J1_EV_4/8/12`；`r_pause` time@6 → `pause`；`r_resume` 不可达 location + `timeout=3.5` → `on_timeout: resume`，gate 5.5）。

| 判据 | 实测 |
|---|---|
| ① 内容位置不变 | `suspended=true` 期间 666 帧 `pos` 恒为 `0.000/6.004`（elapsed 恒 6.004） |
| ② EVENT 不重不漏 | 服务端日志 `[Dev] J1_EV_4` / `J1_EV_8` / `J1_EV_12` 各**恰好一次**（`13:07:28 / 13:07:35 / 13:07:39`）；恢复后 8s、12s 事件正常触发，4s 事件未重放 |
| ③ 恢复后继续推进 | 恢复后 elapsed 6.008 → 20.002，`finished=true` |
| ④ clip3 不卡死 | 见下（修复后） |

### clip3 卡死缺陷与修复

首轮实机复现：`test_rules_actions` 在 content 18.003（clip3 local 2.003）永久挂起（50s+ 无推进）。
根因：`RuleRuntime.releasePauseWithoutReleaser` 用**静态**判据（规则集合里写了 `resume` 就算可释放），而 clip3 的 `resume` 规则（`r_c3_3_2`）已超时终结 → `pause` 永远等不到释放。
修复：判据改为**本执行内仍可触发的 resume**（绑定含 `resume` 且该执行激活未 `finished` / 非 `once` 已成功；带非立即 `schedule` 仍算活释放者）。
修复后实测：content 推进到 21.995，日志 `[Rules] 规则动作 pause 已无释放者（本执行内没有仍可触发的 resume）——自动释放`，随后 clip3 local 6 的 `exit` 声明式退出，**不再卡死**。

## 六、失败注入冒烟（T2 验收）

临时夹具（只放运行目录，不入库）：`j1/inj_bad_command.json`、`j1/inj_bad_layer.json`、`j1/inj_bad_sound.json`（各 8s，CAMERA + 一条坏事件/坏图层/坏声音）。

| 注入 | 日志（可定位） | 进程 / 其余内容 |
|---|---|---|
| 坏命令（EVENT `ic_no_such_command_xyz`） | `[Event] EVENT 命令执行失败（script=inj_bad_command, instance=…, player=Dev, 命令长度=22）` | 存活；脚本 8.003s 自然结束 |
| 坏图层（image `no_such_image_j1.png`） | `Texture file not found: …/resource/no_such_image_j1.png（该图层/图片将按缺失处理）`（每文件一次） | 存活；8.004s 自然结束 |
| 坏声音（AUDIO `no_such_sound_j1.ogg`） | `AUDIO clip failed to start: no_such_sound_j1.ogg（音频文件不存在: …）` | 存活；8.004s 自然结束 |

说明：非有限关键帧 / 越界关键帧按设计在**解析 / 校验期**处理（M5 / M12），不进入运行期；运行期可注入的真实失败面即上表三类。

## 七、Forge h11 复测（T9）

夹具：`cinematics/0.3.6/tests/hud/test_h11_hard_hide.json`（`hide_hud=true` + `screen_layer_mode=allowlist` + `screen_layers={}`）。
Forge dev 客户端（`:forge:Client`，临时 `--quickPlaySingleplayer`，测后已回滚并重编译）。

| 判据 | 实测 |
|---|---|
| 接管（清空） | 血量 / 饥饿 / 经验 / 快捷栏 / 聊天**全部不可见**；脚本自身 OVERLAY 字幕保留（不属于 screen layer） |
| 重画（白名单） | 临时变体 `screen_layers={"hud.hotbar": true}` → **只有快捷栏重画**，其余仍隐藏（截图 `h11_redraw_hotbar2.png`） |

## 八、迁移等值（T8，替代像素对比）

方法：旧树 worktree `E:/tmp/j1-old`（`42c0ef4`，同进程含旧 `keyframes` 采样器与新 `objects` 采样器）+ 旧格式夹具（`e55caef` 导出）。逐通道、每 clip 41 点对拍。

| 夹具 | 覆盖通道 | maxAbsDiff |
|---|---|---|
| `test_camera_loop_repeat` | 8/8 | **0.000000000** |
| `test_camera_loop_pingpong` | 8/8 | **0.000000000** |
| `test_exit_loop_count` | 8/8 | **0.000000000** |
| `test_exit_infinite` | 8/8 | **0.000000000** |
| `test_objects_multitrack` | 6/8（roll/zoom 两侧均未声明） | **0.000000000** |

补测（合成夹具）：角度环绕 `0→200` / `170→-170`（原始差 360、**mod 360 差 0**，同一朝向）、`zoom 1→4` 几何插值（0）、`pitch -60→60`（0）、多关键帧非 0 起始（**9.54e-7**，float 舍入）。
探针自检：篡改末值可检出 1.0 / 5.0 并 FAIL，非恒真。合计 **10/10 PASS**。

已知表示层差异（非语义差异）：
1. 角度：旧 `lerpAngle` 不归一化结果（可落到 (180,190]），新 `Interp.ANGLE` 归一到 (-180,180] → 原始值差 360、朝向等价；新侧有界更规范。
2. 多关键帧分段：旧格式一份关键帧必须带两通道节点并集，新格式每属性各自节点 → 分段切法不同带来 ≤1e-6 级 float 舍入。

语义差异（设计变更，已由 I 轮记录）：morph 两侧各取完整实际状态、路径条目化——不是数值等值能覆盖的部分，见 `I-script-core/migration-report.md`。

# H-6 实机验证记录

> 日期：2026-11-04。环境：Windows 11，Fabric 1.20.1 dev 客户端（`fabric/run`）/ Fabric 独立服务端（`E:/tmp/h6-rig/server`）/ Forge 47.4.16 dev 客户端（`forge/run`）。
> 方法：`tools/devtools` 驱动（Win32 定向消息，不抢焦点）；截图一律走**游戏内 F2**（`ic shot`）。
> 探针与夹具在仓库外（`E:/tmp/h6-probe`、`E:/tmp/h6-rig`），入库前已清理。

---

## 一、R3 lane 症状 B（倾斜裁切矩形）

### 1.1 为什么换了判据区

原配方 `tilt_repro`（`E:/tmp/b3c/tilt.json`）在**超平坦世界** `新的世界` 里跑：主画面只有平地与村庄，缺块在画面上几乎不可辨（B3-c 当时靠「地平线像素下移」间接量化）。
本轮按用户要求改成**有远景几何的判据**：用 `/fill` 在玩家西侧 60–130 格造 6 座高低不同的石塔，主画面朝西（yaw 90），塔群直接作为「远景是否被误剔」的像素判据。

### 1.2 复现条件（与旧缺陷同一机制）

- 两条 CAMERA 轨：
  - `surface`：地上俯视（左上 1/4 屏）；
  - `dig`：**相机沉到地层内**（y = 傀儡 y − 4.5 ≈ −63.5，地表 −60），固定朝向 `look_at: none, yaw 0, pitch −20`（朝南偏上），与主画面（朝西）视锥**方向不同**。
- 旧缺陷的机制正是：共享可见集合被 `dig` pass 的视锥过滤，主画面远景被整片剔掉。
- 铁傀儡按用户要求改为地面生成（`summon ... ~ ~1 ~3`），不再埋进石头；`macro_loop` 关闭、片尾 `kill` 删除，傀儡全程在场。

### 1.3 结果（5 轮窗口帧，右半屏 = 主画面）

| 轮次 | 塔群灰色像素（地平线以上） | 地平线中位（px） | 天空占比 |
|---|---|---|---|
| r2 | 8197 | 537.0 | 35.69% |
| r3 | 8208 | 537.0 | 35.70% |
| r4 | 8212 | 537.0 | 35.70% |
| r5 | 8236 | 537.0 | 35.70% |

- 跳过首帧（世界仍在加载）：塔群像素中位 **8210**，最小 8197，相对偏差 **0.16%**；地平线四轮恒定 537.0。
- 判定：**主画面远景未缺块**，倾斜矩形未复现。

### 1.4 机制证据（每 lane 独立可见集合）

```
[camera-client] pass=main ... perLane=[tilt_repro_cam0_clip0#0:27/24/0/6; tilt_repro_cam1_clip0#1:27/23/0/8]
[camera-client] diff pass=tilt_repro_cam0_clip0#0 mainOnly=0 laneOnly=14 both=3
[camera-client] diff pass=tilt_repro_cam1_clip0#1 mainOnly=2 laneOnly=14 both=1
```

- 两条 lane 的可见计数不同（24 / 23）→ 每个 pass 各自一份可见集合，不再共享单份被单 pass 播种/过滤。
- `diff` 行显示 main 与各 lane 的集合差异（`mainOnly` / `laneOnly` / `both`），可直接观察「主画面有、lane 没有」的集合不被 lane pass 覆盖。

### 1.5 结论

**关闭**：机制层（per-lane 可见集合，`LaneCullSlots` 分槽 + 每 pass 独立 `smartCull`）与像素层（塔群像素 0.16% 偏差、地平线恒定）双向证据成立，配方下不再产生倾斜裁切矩形。

### 1.6 复现步骤

```sh
# 1) 世界：新的世界（flat）；关刷怪与云，避免噪声
ic cmd "gamerule doMobSpawning false"
# options.txt: renderClouds:"false"
# 2) 造远景地标塔（玩家西侧 60–130 格）
ic cmd "fill 19 -60 139 21 -41 141 minecraft:stone"   # 共 6 座，见 §1.2
# 3) 夹具：tilt_repro.json（铁傀儡 ~ ~1 ~3、dig 相机 dy -4.5 / look_at none）
# 4) 启动（采集开关）
ICINEMATICS_CAPTURE=1 sh launch.sh client <fabric/run> --quickPlaySingleplayer 新的世界
# 5) 分析
uv run --with pillow --with numpy python analyze_b3c.py tilt fabric/run/lane-captures
uv run --with pillow --with numpy python tower_count.py fabric/run/lane-captures
```

---

## 二、R2 光影顺序与性能（Fabric + Iris）

### 2.1 环境

- Iris `1.7.6+mc1.20.1`，光影包 `ComplementaryUnbound_r5.9.3.zip`（`enableShaders=true`）。
- 启动日志确认管线：`[shader-compat] pipeline=IrisRenderingPipeline historyTargets=[2] shadowTargets=true`；关光影后为 `pipeline=VanillaRenderingPipeline`。
- lane 深度重挂已接管：`[lane] Iris 深度重挂已接管：lane pass 内 depthtex0/1/2 / gdepthtex / centerDepthSampler 采样`。

### 2.2 顺序（结构性 + 像素）

**结构**：master 调色注入在 `GameRenderer.render` 内、原版后处理 `postEffect.process(f)` 之后那一句 `getMainRenderTarget().bindWrite(true)`（`GameRendererMixin.onWorldPostProcessed`）；Iris 的 `finalizeGameRendering()`（色彩空间）挂在 `GameRenderer.renderLevel` 的 TAIL，`finalizeLevelRendering()`（合成）在 `LevelRenderer.renderLevel` 尾部 —— **都早于** master 调色。因此顺序为：光影合成 → 色彩空间 → 原版后处理 → **master 调色** → GUI。

**像素**：同一 LUT 夹具（`test_adjust_lut_demo`，0–6 s 原画 / 6–12 s 套 `Teal and Orange.cube`）：

| 模式 | 原画均值 RGB | 套 LUT 均值 RGB | 差值均值 RGB | absMean | 变化像素 |
|---|---|---|---|---|---|
| 开光影 | (145.5, 164.8, 122.2) | (179.4, 171.6, 106.0) | (+33.9, +6.8, −16.2) | 37.27 | 98.4% |
| 关光影 | (98.0, 123.7, 109.9) | (103.1, 120.2, 90.6) | (+5.1, −3.5, −19.3) | 32.42 | 98.39% |

- 两种模式下 LUT 都作用到 **98.4%** 的像素，量级相当（absMean 32.4 / 37.3）→ 调色在光影输出之上生效，没有被光影 tonemap 洗掉、也没有双重应用。
- 差值方向不同是基图不同（光影整体提亮）导致，属预期。

### 2.3 性能（诊断会话，600 有效帧，CPU 提交区间）

| 用例 | game_render p50 | lane_total p50 | composite p50 | frame_interval p50 | 备注 |
|---|---|---|---|---|---|
| `tilt_repro`（2 lane，光影开） | 8.94 ms | 5.05 ms | 0.084 ms | 20.54 ms（≈49 fps） | `lane_render` 1200 行，p50 2.52 ms/lane |
| `test_adjust_lut_demo`（1 全屏 lane + LUT，光影开） | 7.21 ms | 2.58 ms | 0.061 ms | 14.43 ms（≈69 fps） | 调色 pass 计入 game_render |

- 会话目录：`fabric/run/immersive_cinematics/diagnostics/sessions/20261104-221603-h6-r2-perf`、`20261104-221958-h6-r2-perf`；`ic perf` 可复读。
- 口径：CPU 提交区间，不是 GPU 时间、不含 vsync。

### 2.4 结论

**关闭**：顺序由注入点结构性确定并有像素证据支撑；性能有 2 lane / 1 lane 两组诊断数据。

---

## 三、R1 外部 overlay（第三方模组）

**关闭**：功能已实现并开放接口 —— `screen_layers` / `screen_layer_mode` 的接管与按分类重画由 `api/HudLayerRegistry` 对外暴露，第三方模组按接口接入；接管判定路径已由 H-5 的「注册 overlay + Pre 自绘红方块」像素对照验证（`allowlist` 清空、`normal` 保留）。

---

## 四、R4 双端联机票数行

### 4.1 装置

- Fabric 独立服务端（`E:/tmp/h6-rig/server`，RCON 25575）+ 两个 Fabric dev 客户端（`fabric/run` = DevA、`E:/tmp/h6-rig/runB` = DevB）。
- **用户名必须显式固定**：dev 客户端默认随机取名，两次启动曾撞成同名（`Player160`），后登录的把先登录的顶下线（客户端提示「链接已丢失，你已在异地登陆」）。启动加 `--username DevA` / `--username DevB` 后稳定。
- 投票驱动：`E:/tmp/h6-rig/hold_key.py`（复用 devtools 的 Win32 定向消息，KEYDOWN → sleep 3.5 s → KEYUP），不抢焦点。

### 4.2 流程与结果

| 步骤 | 观测 |
|---|---|
| 服务端 `icinematics play test_h9_skip_hud @a` | 两端同时进入播放，长按提示出现 |
| DevA 长按 `C` 3.5 s | **未退出播放**（客户端日志无 `deactivateNow: reason=SKIPPED`），票数行出现 |
| DevB 长按 `C` 3.5 s | 达到 2/2，两端同时收到停止包并结束 |
| 已投票后再长按 | 不再触发投票（本地已投标记 + 服务端幂等） |

- 用户实机确认：投票行为、票数行位置与显示条件均正常。
- 票数行文案：单批 `已投票跳过: a/b`；多批时每行带脚本名（`<name>: 已投票跳过: a/b`）。

### 4.3 结论

**关闭**：投票只计票、不退出；达比例统一跳过；已投票不再响应长按；票数按实例（批次）分流。

---

## 五、R5 H10 断线 / 维度卸载 / 服务端停止

夹具：`h6_liveness_long`（由 `test_h10_entity_liveness` 派生，相机时长拉到 600 s，保证操作在播放中途）。

### 5.1 维度卸载（玩家自己进入目标维度）

| 步骤 | 观测 |
|---|---|
| 服务端播放 `h6_liveness_long @a` | `streams=4 / views=4`（两玩家 × 末地+下界） |
| `execute in minecraft:the_end run tp DevA 0 72 0`（主岛中心上方） | `[camera-view] 释放流 视图=lease0g9@minecraft:the_end 原因=player-in-dimension`；`释放视图 玩家=DevA ... 原因=player-in-dimension 剩余=3`；下一窗 `streams=3 / views=3` |

### 5.2 断线（直接杀客户端进程，模拟拔线）

| 步骤 | 观测 |
|---|---|
| 播放中杀 DevA 进程 | `[camera-view] 释放视图 玩家=DevA 视图=lease1g7@minecraft:the_nether 原因=quit 剩余=2` |
| 同 tick 服务端 | `DevA lost connection: Disconnected` / `DevA left the game` |

- 释放走 `onPlayerQuit`：按视图退订 + 兜底清掉该玩家残留空流（`streams.keySet().removeIf(...)`）。
- 全程无 `mirror` / `tick` 失败日志，无幽灵实体（视图退订时对无其他订阅者的实体发 RemoveEntities）。

### 5.3 服务端停止

| 步骤 | 观测 |
|---|---|
| 播放 `h6_liveness_long`，等视图活跃 | `[camera-view] stats ... streams=2 views=2 entities=88`（末地 + 下界） |
| RCON `stop` | `[Rcon: Stopping the server]` → `[camera-view] 服务端停止，清空 2 个视图 / 2 条实体流` → `Stopping server` |
| 停服后 | 服务端进程退出；装置清理后 **0 个残留 `KnotClient`/`KnotServer` 进程** |

- 清理走 `CameraViewManager.clearAll()`：清 `views` / `streams`，不碰 chunk source、不发包。
- **前提**：该行与 `[camera-view]` 其余诊断一样受 `debugLogging` 门控；服务端配置须为 `true`（rig 的 `up(debug=true)` 现在会同时写服务端与客户端）。
- 复现：`rig.up(debug=true)` → `input.cmd(target=server, "icinematics play h6_liveness_long @a")` → 等 10 s → `input.cmd(target=server, "stop")` → 查 `<serverDir>/logs/latest.log`。

### 5.4 结论

三条路径全部 **关闭**：维度卸载（`player-in-dimension`）、断线（`quit`）、停服（`服务端停止，清空 2 个视图 / 2 条实体流`）均有现场日志，事后无残留进程。

---

## 六、R6 H10 同目标维度多 lane / 多玩家

夹具：`h6_two_end_lanes`（两条 CAMERA 轨都看末地，左右半屏）、`h6_two_end_lanes_staggered`（`end_a` 10 s、`end_b` 60 s）。

### 6.1 共享实体流（同一玩家两条 lane 看同一目标维度）

| 时刻 | stats |
|---|---|
| 两 lane 都在 | `streams=2 views=4 envelopes=2410 bytes=17984 entities=98` |
| 两 lane 都在（另一窗） | `streams=2 views=4 envelopes=826 bytes=4692 entities=134` |

- `streams=2` = 两名玩家各一条 `(玩家, 末地)` 流；`views=4` = 每人 2 个视图。
- 同一玩家的两条 lane 共享同一条流：`entities` 是**两条流的 interests 之和**，不是「视图数 × 实体数」。
- 每个视图各有自己的实体计数（`view=lease1g1@the_end count=63`、`view=lease0g4@the_end count=54`），说明视图只是订阅者。

### 6.2 释放一条 lane 不误删另一条

| 时刻 | stats / 日志 |
|---|---|
| 8 s（两 lane 都在） | `streams=2 views=4` |
| 20 s（`end_a` 已结束） | `streams=2 views=2`；`释放流 视图=lease0g5@the_end 原因=client-end`；`释放视图 玩家=DevA ... 剩余=2` |

- 流**没有被删**（`streams` 仍 2）：`CameraEntityStream.releaseAllViews` 只把「该实体已无任何订阅视图」的 id 放进 RemoveEntities；`unsubscribeView` 仅在 `!stream.hasSubscribers()` 时 `streams.remove(key)`。
- 另一条 lane 的实体因此保留，未被误删。

### 6.3 多玩家互不串包

- 视图日志逐条带玩家名（`玩家=DevA` / `玩家=DevB`），流键是 `(玩家 UUID, 维度)`，`streams=2` 即两条独立流。
- 玩家各自释放只影响自己的流：DevA 断线后 `剩余=2`、DevB 的视图继续。

### 6.4 用户实机确认

- 实体可见、数量多、多视角实体显示与连续移动正常。

### 6.5 结论

**关闭**：同目标维度双 lane 共享一条流、只配对一次；释放一条不误删另一条；双玩家按 UUID 隔离。

---

## 七、R7 H10 包量与客户端 tick 耗时

### 7.1 服务端（`[camera-view] stats`，每 100 tick 一窗）

| 场景 | streams / views | entities（各流 interests 之和） | envelopes / 100 tick | bytes / 100 tick |
|---|---|---|---|---|
| 2 lane（`h6_two_end_lanes`，稳态） | 2 / 4 | 134 | 826 | 4 692 |
| 3 lane（`h6_three_end_lanes`，稳态） | 2 / 6 | 78 | 574 | 3 188 |
| 3 lane（实体较多） | 2 / 6 | 152 | 1 302 | 9 320 |
| 3 lane（实体更多） | 2 / 6 | 213 | 2 230 | 16 553 |

- 首窗（初始配对 + 区块下发）显著更高：`envelopes 6 921–8 458`、`bytes 7.4 MB–19.4 MB`，**由区块包主导**，不是实体包。
- 稳态折算：`0.06–0.22 信封/tick`、`32–166 B/tick`（两流合计）。

### 7.2 客户端（`[camera-view] client-tick`，每 100 tick 一窗）

| 场景 | 视图可见实体数 | avg | p50 | max |
|---|---|---|---|---|
| 3 lane（实体 39/视图） | 39 | 190 µs | 168 µs | 466 µs |
| 3 lane（实体 67–76/视图） | 67–76 | 283–304 µs | 232–248 µs | 753–1 025 µs |

- 口径：CPU 提交区间（`System.nanoTime()` 前后差），不是 GPU 时间；只含目标视图的 `ClientLevel.tickEntities()`。

### 7.3 结论

**不引入批量信封**：稳态信封量在 0.06–0.22 封/tick（两流合计），远低于需要合批的量级；突发集中在区块下发（与实体流批处理无关，且已有 1 MiB 信封上限保护）。客户端 `tickEntities` p50 在 168–248 µs，随视图实体数线性增长，不构成瓶颈。

---

## 八、环境备注

- `fabric/run/options.txt` 的 `renderClouds` 在 R3 期间置 `false`（消云噪声），测完恢复。
- `新的世界` 的 gamerule `doMobSpawning` 在 R3 期间置 `false`，测完恢复 `true`。
- 联机装置：Fabric 独立服务端 `E:/tmp/h6-rig/server`（RCON 25575），两个客户端 `fabric/run` + `E:/tmp/h6-rig/runB`；启动脚本 `E:/tmp/h6-rig/launch.sh` 直接复用 loom 的 classpath，绕开「同项目并发 gradle 抢锁」。

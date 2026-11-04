# camera_center 测试说明

## 末地刷怪 / 相机中心（C2 / C3）

- **远机位（示例）**：`minecraft:the_end` 相机 `(0, 90, -60)`，`yaw=-90` 朝东看向玩家 `(200, 100, 0)` 附近；`(79, 60, -26)` 是主岛刷怪点，只作为观察目标，不是相机位置。
- **玩家位置**：任意，与相机水平距离 >128 即可。脚本在 `t=0` 用 `execute at @s run fill ~-3 ~-1 ~-3 ~3 ~-1 ~3 minecraft:obsidian` 在玩家脚下铺平台，不需要保持飞行，也不会掉进虚空。
- **准备**：
  - `gamemode creative`（**不要用 spectator**：旁观模式不产生刷怪尝试，`attempts=0`）；
  - `gamerule doMobSpawning true`；
  - `difficulty normal`；
  - **不要清场**：保留玩家已经刷出的生物；脚本不再执行 `kill`。
- **脚本**：`ic play camera/center/test_camera_center_end_spawn`（部署到 `immersive_cinematics/scripts/camera/center/` 后）。
- **断言**：
  - 服务端 `state` 行：`level=minecraft:the_end`、`cam=(0.000,90.000,-60.000)`、`anchorY=90.000`；
  - `spawn` 行：`gateHit>0`、`attempts>0`、`spawned>0`，`localCapAllow` 随 `attempts` 增长；
  - 客户端 `[camera-client]` 的相机 lane `r128` 与 `pairSeen` 对账，差值应落在 `distOut / uncompiled / frustumOut`。
  - 客户端 `[camera-client] diff pass=...` 每秒最多 1 行汇总；`mainOnly` = 主视角 visible、相机 lane 非 visible，明细最多 4 条（含 `type/pos/d2main/d2lane/分类`）。
- **不要贴岛面取景**：`(0, 70, 0)` / 脚位高度会被地形和柱体遮挡；用远机位 `(0, 90, -60)` 俯看主岛与玩家。

## 2026-10-25 实测结果

- **镜像 A/B**（`test_camera_center_mirror`：相机 = 玩家眼睛 `200.5,75.314,0.5`，yaw 104 / pitch 9.2）：
  - main 与 lane 完全一致：`total=74 r128=74 visible=72 distOut=2 uncompiled=0 frustumOut=0`；
  - `[camera-client] diff` 0 行 → 同位置同朝向下没有实体剔除差异。
- **反向 A/B**（`test_camera_center_opposite`：同位置，lane yaw 284，主视角 yaw 104）：
  - main `visible=72`，lane `visible=0 frustumOut=72`；
  - `[camera-client] diff mainOnly=72 laneOnly=0 both=0`，明细每行形如
    `mainOnly id=1401 type=minecraft:enderman pos=(120.5,57.0,-25.5) d2main=7411.4 d2lane=7411.4 main=visible lane=frustumOut`；
  - 说明日志能把「主视角可见、相机视锥外」逐只对出来，反向视角不渲染是视锥正确行为。
- **远机位**（`test_camera_center_end_spawn`：相机 `0,90,-60`，yaw -90）：
  - 服务端 `anchorY=90.000`；
  - main `r128=55 visible=47`，lane `r128=78 visible=54 frustumOut=16`；
  - 两边实体集合只部分重叠，公共实体无分类差异；数量差来自视角/视锥覆盖，不是 lane 剔除丢失。
- 以上脚本均不清场；`kill @e[type=!player]` 已从 fixture 移除。

## 跨维度画面（per-view 渲染）

> 旧的 A 方案（服务端在片段起点把玩家传送到目标维度）已删除（`8354731` 移除传送路径）。现行做法：**玩家留在原维度**，客户端为每条声明了 `dimension` 的 CAMERA 片段单独解析一个 `DimensionView`（该维度独立的 `ClientLevel` + `LevelRenderer` + `LightTexture` + `RenderBuffers`），在 lane pass 内临时切换渲染源、`finally` 还原；服务端按 `(玩家, 视图 id)` 维护 ticket / 区块 / 实体流（`CameraViewManager`）。设计见 `plans/0.3.6/F-playback/cross-dimension-camera.md`。

### 三画面 fixture：`test_camera_center_crossdim_view`

- **布局**：总时长 20s，三条 CAMERA 片段同时活跃。
  - 左上 `dest=(0,0,0.5,0.5)` → `minecraft:the_end`，相机 `(79, 61.6, -26)`，yaw 0 / pitch -10；
  - 右上 `dest=(0.5,0,0.5,0.5)` → `minecraft:the_nether`，相机 `(0, 70, 0)`，yaw 0 / pitch -10；
  - 第三条无 `dimension`、全屏、`opacity=0`：不画任何东西，露出主画面的玩家原视角。
- **场景准备（脚本 t=0 自动执行）**：先 `execute in minecraft:overworld run tp @s 0 80 0` 把玩家送回主世界（测试存档可能停在末地）；`gamemode creative`、`gamerule doMobSpawning true`、`difficulty normal`；清掉两个维度的旧末影人 / 僵尸后，在末地 `(79, 61.6, -21)`（相机正前 5 格）召唤 `NoAI` 末影人，在下界 `(0, 70, 5)`（相机正前 5 格）召唤 `NoAI` 僵尸。**不铺平台**：测试世界是超平坦，地表在 `y=-60`，玩家会自然落到地表。**不要用 spectator**（旁观不产生刷怪尝试）。
- **手动播放**：把 fixture 部署到 runtime 的 `immersive_cinematics/scripts/camera/center/`，`reload` 后执行
  `ic play camera/center/test_camera_center_crossdim_view`
  （测试期间把其它会自动触发的旧脚本移到 `scripts_disabled/`，避免干扰）。
- **观察点**：
  - **玩家维度不变**：整段播放里 F3 维度仍是主世界，玩家没有被传送；主画面（除两个角）保持玩家原视角。
  - **服务端**（`debugLogging` 开启）：`[camera-view] 新建视图 玩家=… 视图=lease<槽位>g<代际>@minecraft:the_end 维度=minecraft:the_end`，以及 `…@minecraft:the_nether` 一条（视图 id = 相机租约键：槽位 + 代际 + 维度；三条片段同属一条 CAMERA 轨，但每个片段各有租约，只有声明了 `dimension` 的两条会产生视图）；结束时 `[camera-view] 释放视图 … 原因=client-end`。
  - **客户端**：`[camera-view] 上报 视图=lease<槽位>g<代际>@<维度> 维度=… 位置=(…) active=true 半径=…`，位移 ≥1 格或每 5 tick 一条；半径取客户端有效渲染距离（服务端夹到 2–12 后再封顶 8）。左上画面能看到末影人、右上能看到僵尸，各自地形是本维度地形。调试捕获文件名里的 `<scriptId>_cam<轨序号>_clip<片段序号>` 是 captureId 标签，不是视图 id。
  - **不应出现**：`跨维度视图不可用` / `[camera-view] route-fail` / `oversize` 的持续刷屏；出现即说明目标视图解析失败或单包超限。
- **已知限制**：
  - 实体持续同步已于 H-3（H10）补齐：服务端按 `(玩家, 维度)` 实体流镜像原版 `TrackedEntity.broadcast`（移动 / 旋转 / metadata / 装备 / 乘客等），客户端对目标 `ClientLevel` 调 `tickEntities()`；新进入范围的实体仍按 `ENTITY_INTERVAL=10` tick 补配对。观察 `[camera-view] entity`（服务端 `tick` / `ticking`）与 `[camera-view] client-tick`（客户端 `tick` / `moved`）可判断同步是否推进；实现见 [H10 entity-liveness](../../../../plans/0.3.6/H-other/entity-liveness.md) §七。
  - 区块首包带光照（`ClientboundLevelChunkWithLightPacket`），但**没有方块 / 光照增量更新**：目标维度里发生的方块变化不会反映到画面。
  - 区块半径上限 `MAX_CHUNK_RADIUS = 8`，实体半径 `ENTITY_RANGE = 64` 格；超出即缺块 / 缺实体。
  - **FABULOUS 画质不支持**（`Minecraft.useShaderTransparency()` 为真时 `DimensionViews.acquire` 返回 `null`），该 lane 本帧不上屏；改回 Fancy / Fast 再测。
  - 自定义通道单包上限 `MAX_ENVELOPE_PAYLOAD = 1_000_000` 字节，超长区块包被丢弃（客户端该区块留空，其余流继续）。
  - 玩家已在目标维度时该视图会被释放（交还原版追踪），此时不应期待 `[camera-view]` 流。

### H10 实体活性 fixture（H-3，2026-11-04）

三个 fixture 均放在本目录，需手动部署到 runtime 的 `immersive_cinematics/scripts/camera/center/` 后 `reload` + `/icinematics play`；无 `meta.triggers`，不会自动播放。

| fixture | 场景 | 观察点 |
|---|---|---|
| `test_h10_entity_liveness.json` | 跨维度双画面：End 左上 / Nether 右上，各 4 只 `NoAI:0` 的猪，20s 固定机位 | 两个 `[camera-view] entity` 流 `tick` 递增、`ticking=true`；两个 `[camera-view] client-tick` 的 `tick` 递增、`moved=true`；画面里猪连续游走 |
| `test_h10_ender_dragon.json` | 末地单画面 `(0,80,-45)` 看 `(0,80,0)` 召唤的末影龙，20s | 龙本体与部件连续飞行；无 `ender_dragon` 特判代码 |
| `test_h10_same_dim.json` | 同维度 F6 回归：主世界 `(0,80,0)` 看 4 只 AI 猪，20s | 猪连续游走；此场景走原版跟踪 + 主 `ClientLevel` tick，不产生 `[camera-view]` 流日志 |

实机记录（2026-11-04，Fabric 开发客户端，`debugLogging=true`）：
- 跨维度：服务端 End `tick=274→294`、Nether `tick=237→257`；客户端 End 实体 `tick=14→174`，末影人 / 下界 zombified piglin 多项 `moved=true`；截图 `rt_A/rt_B`。
- 末影龙：两帧间龙从主岛上方移动到石柱之间，翅膀姿态展开；截图 `dg2_A/dg2_B`。
- 同维度：两帧间两只猪走到镜头右侧近处；截图 `sd_A/sd_B`。
- 无 `route-fail` / `oversize` / 镜像失败 / 实体配对失败。
- 截图用的 60s、`block_keyboard=false` 运行时副本测完已删除，未入库（仓库 fixture 保持 20s，`block_keyboard=true`）。

### 历史参考（旧传送路径）

- `test_camera_center_crossdim_clip.json`：A 方案首版脚本（片段 `t=2` 声明 `dimension=minecraft:the_end`，服务端在片段起点前 0.5s 把玩家传送到 `(79,61.6,-26)`）。**旧传送路径已删除，仅作历史参考**，现行跨维度请用 `test_camera_center_crossdim_view`。
- `test_camera_center_crossdim.json`：反向探针（相机在末地 `(0,90,-60)`、2s 后把玩家传回主世界）。同样依赖已删除的传送路径，**仅作历史参考**。
- 旧路径实测存档（2026-10-25，creative，`test_camera_center_crossdim_clip`）：`[camera-center] state level=minecraft:the_end cam=(79.000,61.600,-26.000)`；`spawn` 行 `gateHit=3980 gateChunks=199 attempts≈20000 pairSeen=75`；客户端 lane `total=75 r128=75 visible=42 uncompiled=25 frustumOut=8`；截图 `lane-captures/f3-crossdim-clip-creative.png`。这是在「服务端传送玩家」路径上得到的，不再代表当前行为，只作对照。

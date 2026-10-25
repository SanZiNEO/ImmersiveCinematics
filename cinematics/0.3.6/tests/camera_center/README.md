# camera_center 测试说明

## 末地刷怪 / 相机中心（C2 / C3）

- **远机位（示例）**：`minecraft:the_end` 相机 `(0, 90, -60)`，`yaw=-90` 朝东看向玩家 `(200, 100, 0)` 附近；`(79, 60, -26)` 是主岛刷怪点，只作为观察目标，不是相机位置。
- **玩家位置**：任意，与相机水平距离 >128 即可。脚本在 `t=0` 用 `execute at @s run fill ~-3 ~-1 ~-3 ~3 ~-1 ~3 minecraft:obsidian` 在玩家脚下铺平台，不需要保持飞行，也不会掉进虚空。
- **准备**：
  - `gamemode creative`（**不要用 spectator**：旁观模式不产生刷怪尝试，`attempts=0`）；
  - `gamerule doMobSpawning true`；
  - `difficulty normal`；
  - **不要清场**：保留玩家已经刷出的生物；脚本不再执行 `kill`。
- **脚本**：`ic play camera:center/test_camera_center_end_spawn`（部署到 `immersive_cinematics/scripts/camera/center/` 后）。
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

## 跨维度片段切换（A 方案首版）

- **脚本**：`ic play camera:center/test_camera_center_crossdim_clip`（部署到 `immersive_cinematics/scripts/camera/center/` 后）。
- **前置**：玩家在主世界任意位置启动；脚本在 `t=0` 自动 `gamemode creative && gamerule doMobSpawning true && difficulty normal`；**不要用 spectator**（旁观模式不产生刷怪尝试，`attempts=0`）。不需要飞行 / 铺平台 / 清场。
- **手动触发**：测试时把 runtime `immersive_cinematics/scripts/` 里除本 fixture 外的旧脚本移到 `scripts_disabled/`，`reload` 后手动 `play`；不要让旧脚本 auto-trigger 干扰（尤其 `setup_flat_rules` 的 login 触发）。
- **行为**：CAMERA 片段 `start_time=2` 声明 `dimension: "minecraft:the_end"`，首关键帧绝对位置 `(79, 61.6, -26)`、yaw 0 / pitch 0。服务端在片段起点前 0.5s（`t≈1.5`）把观看者传送到末地该坐标；客户端相机脚本不中断，继续播放到 22s 结束。
- **预期**：
  - 服务端 `[camera-center] state level=minecraft:the_end cam=(79.000,61.600,-26.000)`；
  - 相机画面为末地主岛（相机位置在主岛刷怪点附近），能看到末影人；
  - `t=2` 的 EVENT `say` 在传送之后执行；整个播放期间维度切换只发生一次。
- **2026-10-25 实测（creative）**：`state level=minecraft:the_end cam=(79.000,61.600,-26.000)`；`spawn` 行 `gateHit=3980 gateChunks=199 attempts≈20000 pairSeen=75`；客户端 lane `total=75 r128=75 visible=42 uncompiled=25 frustumOut=8`；截图 `lane-captures/f3-crossdim-clip-creative.png` 显示主岛末影人。
- **限制**：维度切换的位置只支持绝对坐标与相对玩家激活位置（世界轴 `dx/dy/dz` 或基准坐标系 `fwd/up/right`）；结构 / 方块 / 实体选择器点源无法在服务端等价求值，会告警并跳过该次切换（不传送到猜测位置）。暂停期间不切换。0.5s 是提前量而非同步；无黑幕 / 预载 / 失败回滚。
- 旧的 `test_camera_center_crossdim.json` 是反向探针（相机在末地、玩家回主世界），与本脚本无关，保持原样。

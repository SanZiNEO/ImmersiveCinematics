# B3-c 实测归档：per-lane 可见集合（新）vs 整帧缓解回落（= B3-b 前行为）

> 实测 2026-10-11（B3-c）。原始数据：同目录 `b3c-culling-cost.csv` / `b3c-memory.csv` / `b3c-pixel-evidence.csv`；
> 捕获产物与证据图在仓库外 `E:/tmp/b3c/evidence/`（测量脚本/一次性工具按惯例不入库）。
> 两态 = `ICINEMATICS_PER_LANE_CULL` 缺省（per-lane 可见集合，B3-b 落地）vs `=0`（回落：整帧 `smartCull=false` +
> 每 pass 强制 `applyFrustum` —— 即 `multi-camera-rendering.md` §12.9.3 保留的 B3-b 前路径，B3-d 删除）。
> 本文只写现行数字；每条标 [实测] / [估算]。生产代码零改动（本任务纯测量）。

## 1. 口径与环境

- 世界：`新的世界`（用户 UI 建，超平坦 + 作弊开；`setup_flat_rules.json` login 自动跑：无自然生成 / 不下雨 / 正午冻结）。
- 客户端选项（测量期临时改，跑完已还原）：`enableVsync:false`、`maxFps:260`（=解锁）、`pauseOnLostFocus:false`、视距 12。
- 场景脚本：`tilt_repro`（仓库外 `E:/tmp/b3c/tilt-long.json`，经项目 validator 零 issue）：2 条 CAMERA 轨
  —— `surface`（地上俯视，dest 左上 0.5×0.5）+ `dig`（`follow`/`look_at` 追踪刷在玩家脚边地下的铁傀儡，dest 左下 0.5×0.5）；
  右半屏 = 主画面（玩家相机，`tp @p ~ ~ ~ 90 5` 朝向开阔地）。傀儡 `~ ~-3 ~` 嵌入地层（y=-63），t=1.5 s 再下移 1 格到 y=-64（基岩），
  dig 相机随之下沉到 y≈-62.5（实心方块内）；t=6 s 起两相机静止到 t=25 s（缺块判据窗）。
- 帧成本工具：原版客户端 metrics 剖析（与 F3+L 同一入口 `Minecraft.debugClientMetricsStart`，经仓库外 attach 工具
  `E:/tmp/b3c/agent`（agent jar + attach 程序）触发），10 s 窗口 → `fabric/run/debug/profiling/*.zip` 内 `client/profiling.txt`。
  剖析 root 即整帧（`Minecraft.run` 的 `startTick/endTick` 包 `runTick`），故 `帧时间 = 1000 / fps`，
  段 ms = 该段 globalPercentage% × 帧时间。lane pass 的段挂在 `gameRenderer` 下（`renderLevel` RETURN 处），主 pass 的段在 `gameRenderer.level` 下。
- 内存工具：`jcmd GC.class_histogram`（先 `GC.run`；仓库外 `E:/tmp/b3c/agent/JCmd.java` 经 attach API 执行，`jcmd` 直连在本机被拒）。
- 像素工具：`E:/tmp/b3c/analyze_b3c.py`（天空判据 / 逐列最深天空行 / 合成区 vs 自身 raw 的 tint 与残差）、`ab_diff.py`（两态逐像素差）。

## 2. 结论（先看这个）

| 指标 | per-lane（新） | 回落（B3-b 前） | 备注 |
|---|---|---|---|
| 帧率（同场景同 10 s） | **444.8 fps** | **168.2 fps** | [实测] 2.64× |
| 帧时间 | **2.25 ms** | **5.94 ms** | [实测] |
| `terrain_setup` | **0.008 ms** | **0.639 ms** | [实测] 80×；回落侧 0.618 ms 是 `update`（BFS/视锥刷新），其中含每帧 4 次 `apply_frustum` 行 |
| 回落专属的强制 `apply_frustum` | 0 | **0.348 ms** | [实测] per-lane 路径不需要（槽自带门闩） |
| `terrain` 绘制 | 0.436 ms | 1.118 ms | [实测] 未剪枝 → 画得更多 |
| `clouds` | 0.700 ms | 1.181 ms | [实测] 同上（云也逐 pass 重画） |
| 主画面缺块（像素） | 稳定（地平线 619 px 不动） | **r4 854 px / r5 1014 px** | [实测] 落差 = 主画面远景整片消失 |
| 两态窗口帧差异 | — | 主画面区 **65.5%** 像素变化 / lane 区 4.0% | [实测] |
| 候选集条目（本场景） | vanilla 4 015 + lane 自建 +551 | 共享 1 份 **7 256** | [实测] 回落不建槽、不剪枝 → 单份更大 |
| 每 lane 固定结构 | `renderInfoMap` 数组 60 KB/份 + 槽对象 | 0 | [实测] |
| 单 lane 内存上界（普通地形 R=12） | ≤ 1.02 MB | — | [估算] 15 004 条 × (24+40) B + 60 KB；8 lane ≈ 8.2 MB，对照 §12.9.5 的 0.78 MB / 6.3 MB 同量级 |

**一句话**：B3-b 的 per-lane 可见集合在本场景把「共享可见集合被别的 pass 覆盖」的缺块根因消掉（主画面地平线稳定），
并把回落态每帧必付的 `update`/`apply_frustum`（0.62 + 0.35 ms）换成「各 lane 门闩驱动、静止时零成本」；代价是每 lane 一份候选集（本场景 ≈ 0.1–1 MB 量级）。

## 3. 帧成本（表见 `b3c-culling-cost.csv`）

10 s 窗口、同脚本、同一静态段；两态各自从剖析 zip 的 `client/profiling.txt` 取值：

| 段（ms/帧） | per-lane | 回落 |
|---|---|---|
| 帧时间（=1000/fps） | 2.248 | 5.945 |
| `terrain_setup` | 0.008 | 0.639 |
| ├ `update`（含 BFS 调度/`apply_frustum`） | 0.001 | 0.618 |
| └ 强制 `apply_frustum`（回落专属，不在 `terrain_setup` 内） | 0.000 | 0.348 |
| `terrain`（solid/cutout_mipped/cutout） | 0.436 | 1.118 |
| `clouds` | 0.700 | 1.181 |
| `entities` | 0.265 | 0.607 |
| `compilechunks` | 0.029 | 0.096 |
| `level`（主 pass 合计） | 0.936 | 2.112 |
| `gameRenderer`（含 lane pass） | 1.852 | 5.191 |

- [实测] 回落态每帧都触发全量 BFS 重建 + 视锥刷新：共享 `prevCamX/Y/Z` 门闩被 main / surface / dig 三个相机轮流改写，
  下一次 pass 必然看到「相机已移动 ≥8 格」→ 反复提交/消费（`full_update_schedule` 986 次/1682 帧），
  且 `apply_frustum` 每帧多次全量遍历候选集；per-lane 态各槽自带 `prevCam*`/门闩，静止段零重建。
- [实测] `clouds`/`terrain` 的绝对成本差 = 「未剪枝可见集合（`smartCull=false` 整帧）」多画的区块层 + 每个 lane pass 重画天空/云。
- 换算口径：剖析百分比是 **root（整帧）** 的全局占比；本表按 `1000/fps` 折算成 ms/帧。

## 4. 内存（表见 `b3c-memory.csv`）

同 JVM 内两态 + 「无 lane」对照（均先 `GC.run` 再直方图）：

| 状态 | `RenderChunkInfo` 实例 | `renderInfoMap` 数组 | `LaneCullSlot` 实例 |
|---|---|---|---|
| 无 lane（播放结束后） | 4 015（96 360 B） | 1 份（60 016 B） | 3（池内复用） |
| per-lane，2 lane 活跃 | 4 566（109 584 B） | 2 份（120 032 B） | 3 |
| 回落，2 lane 活跃 | 7 256（174 144 B） | 1 份（60 016 B） | 0 |

- [实测] 单实例字节：`RenderChunkInfo` 24 B、`LinkedHashMap$Entry` 40 B、`renderInfoMap` 数组 60 016 B（15 004 槽 × 4 B，= (2×12+1)² × 24 视高段）。
- [实测] 本场景（超平坦）单份候选集 ≈ 1.5k 条（空段不进候选集）；回落态因整帧关剪枝 → 单份涨到 7 256 条。
- [实测] per-lane 态 2 lane 只比无 lane 多 551 条 + 1 份数组（60 KB）：lane 槽首次播种**复用** vanilla 候选集对象，
  只有该 lane 自己的 BFS 真跑过才持有独立集合（本场景 lane 相机静止，故多数未重建）。
- [估算] 上界（普通地形，各段有几何）：15 004 × (24+40) B + 60 KB ≈ **1.02 MB/lane → 8 lane ≈ 8.2 MB**；
  对照 `multi-camera-rendering.md` §12.9.5 的 0.78 MB/lane、8 lane 6.3 MB —— 同量级，实测单实例字节比估算略小。

## 5. 像素实证

### 5.1 quadrant 四象限回归（`新的世界` + `quadrant_demo`，4 lane，per-lane 态）

- [实测] 稳态（r2–r5）四象限与四条 lane raw 的「逐列最深天空行」离群量（p95 − 中位）≤ **8 px**（图高 1392）；
  首帧（r1，区块尚在编译）21–45 px，属 warm-up。→ **无缺块**。
- [实测] 象限 ↔ 相机映射由脚本调色通道验签：Q_TL 红通道随 `camera_north` 关键帧递减（ΔR −27.6 → −65.3）、
  Q_TR 绿（−34.3 → −81.5）、Q_BL 蓝（−31.6 → −73.5）；Q_BR（`camera_west`，脚本不写调色）差值来自
  `opacity 0.5` + `source` 数字变焦（合成区 vs 自身 raw 的对比在裁剪/半透明下不适用，非污染）。→ **无交叉污染**。
- 各 lane 的 `composited` 区与自身 `raw` 帧（降采样后）残差 RMS：0.43（cam0/cam1/cam2 未调色轮次）——结构一致。

### 5.2 倾斜矩形复现配方 A/B（`新的世界` + `tilt_repro`，2 lane）

判据区 = 右半屏主画面（玩家相机，未被 lane 覆盖）：

| 轮次（帧号 0/200/400/600/800） | per-lane 主画面 | 回落主画面 |
|---|---|---|
| r1（首帧） | 天空 39.4%，地平线 564 px | 39.7%，569 px |
| r2 / r3 | 44.5%，**619 px** | 44.5%，619 px |
| r4 | 44.5%，619 px | **61.5%，854 px** |
| r5 | 44.5%，619 px | **72.9%，1014 px** |

- [实测] 回落态从 r4 起主画面**远景整片消失**（地平线自 619 px 下移到 854 / 1014 px；天空占比 44.5% → 72.9%），
  边界为一条直线（截图为略倾斜的直线，落在区块边界距离上）；lane 区域与 lane 自身画面两态**逐帧一致**（cam0 raw 天空 14.0%/地平线 191 px；cam1 raw ≈89%）。
- [实测] 两态 r5 窗口帧逐像素差：主画面区 **65.48%** 像素变化（meanAbsDiff 70.9），lane 区 4.01%（meanAbsDiff 4.0）。
- 机制核对：回落态共享可见集合最后被 **dig lane pass** 的视锥过滤（`LaneRenderer` 每 lane pass 前的强制 `applyFrustum`）；
  主画面（朝西）落在该视锥（朝南、相机在地层内、水平 ±52°）之外 → 整片缺失。这正是 §12.8-B「共享可见集合被单 pass 播种/过滤替换」的根因，
  也是用户 2026-10-10 报告「屏幕部分区域沿区块网格斜切的缺块 / 半屏好半屏坏」的等价现象（本场景为超平坦 + 水平视线，边界呈现为直线而非阶梯）。
- B3-b 后（per-lane）：主画面地平线五轮不动（619 px），lane 自身画面不变 → **配方下不再产生**。

## 6. 复现步骤（逐字）

```sh
# 0) 备份（跑前）
mv fabric/run/lane-captures fabric/run/lane-captures.pre-b3c
cp fabric/run/options.txt E:/tmp/b3c/options.txt.bak      # 原始 maxFps:60 / enableVsync:true
#    改 options.txt：enableVsync:false、maxFps:260

# 1) 脚本（仓库外；validator 零 issue）
cp E:/tmp/b3c/tilt-long.json fabric/run/immersive_cinematics/scripts/tilt.json

# 2) 两态各跑一次（fabric；捕获 5 轮 + 剖析 + 内存）
ICINEMATICS_CAPTURE=1 sh gradlew -Dnet.minecraftforge.gradle.check.certs=false :fabric:runClient \
  --args='--quickPlaySingleplayer 新的世界'
ICINEMATICS_CAPTURE=1 ICINEMATICS_PER_LANE_CULL=0 sh gradlew -Dnet.minecraftforge.gradle.check.certs=false \
  :fabric:runClient --args='--quickPlaySingleplayer 新的世界'

# 3) 剖析（跑中，世界加载后 ~20 s；10 s 窗口自动收尾落盘 fabric/run/debug/profiling/*.zip）
"C:/Program Files/Java/jdk-17/bin/java.exe" -cp E:/tmp/b3c/agent/out Attach <pid> E:/tmp/b3c/agent/b3c-prof2.jar
#    <pid> = jps -l 里 net.fabricmc.devlaunchinjector.Main

# 4) 内存（跑中；先 GC.run 再直方图）
"C:/Program Files/Java/jdk-17/bin/java.exe" --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED \
  -cp E:/tmp/b3c/agent/out JCmd <pid> GC.class_histogram

# 5) 像素分析（产物目录见 b3c-pixel-evidence.csv 头）
uv run --with pillow --with numpy python E:/tmp/b3c/analyze_b3c.py tilt <capture-dir>
uv run --with pillow --with numpy python E:/tmp/b3c/ab_diff.py <perlane-frame-r5> <fallback-frame-r5> out.png

# 6) quadrant 回归（per-lane 态；capture 5 轮）
cp E:/tmp/b3c/quadrant.json fabric/run/immersive_cinematics/scripts/quadrant.json
ICINEMATICS_CAPTURE=1 sh gradlew -Dnet.minecraftforge.gradle.check.certs=false :fabric:runClient \
  --args='--quickPlaySingleplayer 新的世界'
uv run --with pillow --with numpy python E:/tmp/b3c/analyze_b3c.py quadrant <capture-dir>

# 7) 还原（已执行）：scripts/ 清场（删 tilt.json/quadrant.json）、lane-captures 由 pre-b3c 还原、
#    options.txt 还原原始值、删 fabric/run/debug（剖析 zip 为本次新增）。
```

## 7. 捕获产物与日志证据（仓库外）

| 路径 | 内容 |
|---|---|
| `E:/tmp/b3c/evidence/run1-quadrant/` | quadrant_demo 4 lane 5 轮 raw/composited/window 帧（85 文件）+ `run1-latest.log` |
| `E:/tmp/b3c/evidence/run2-tilt-perlane/`、`run2b-tilt-perlane-long/` | tilt_repro per-lane 态 5 轮（41 文件）+ `run2b-latest.log` |
| `E:/tmp/b3c/evidence/run3-tilt-fallback/` | tilt_repro 回落态 5 轮（41 文件）+ `run3-latest.log` |
| `E:/tmp/b3c/evidence/run3-horizon-crop.png`、`run2b-horizon-crop.png`、`ab-diff-frame-r5.png`、`run3-boundary-zoom.png` | 证据图（地平线对照 / 两态差分 / 边界放大） |
| `E:/tmp/b3c/prof/run2b/`、`E:/tmp/b3c/prof/run3/` | 剖析 zip 解包（`client/profiling.txt` + metrics CSV + server 侧） |
| `E:/tmp/b3c/hist-0lane-gc.txt`、`hist-perlane-2lane-gc.txt`、`hist-fallback-2lane-gc.txt` | 直方图原文 |

日志摘录（两态模式行，见上表两份 `*-latest.log`）：

```text
[per-lane] (CinematicOcclusion) [lane] 切换活跃 lane：per-lane 可见集合生效（逐 lane 独立候选集 / 可见集合 / 门闩，遮挡剪枝按各 pass 的相机判定）
[per-lane] (CinematicOcclusion) [lane] 无活跃 lane：遮挡剔除交回原版判定（smartCull 恢复，可见集合 / 视图中心回退玩家）
[fallback] (CinematicOcclusion) [lane] 切换活跃 lane：整帧关闭遮挡剔除（smartCull=false）——可见集合退化为视距内全部区块（无遮挡剪枝），代价是绘制 / 区块编译量上升；停播后自动恢复原版判定
```

- [实测] 两份运行日志中与 lane/剔除相关的 **WARN/ERROR 为零**（`LaneCullStateMixin` 的 `needsUpdate`/`mirrorCompiled` 钩子、
  `LaneRenderer` 的 lane 渲染失败路径都没有任何告警/异常输出）；日志中的 `ERROR/WARN` 只有开发环境既有噪音
  （离线 401 认证、`item.goat_horn` 缺音效、`rendertype_entity_translucent_emissive` 采样器告警）。

## 8. 证据边界 / 不确定

1. **世界**：任务书要求倾斜矩形配方在 `QuadrantTest`（地形语境）复现，但本环境 `fabric/run/saves/` 下**只有 `新的世界`**
   （`QuadrantTest` 不存在；`build/quad-old/.../saves/QuadrantTest` 有一份 2026-10-07 的旧副本，未使用）。
   故配方改在 `新的世界`（超平坦）执行：傀儡嵌在 y=-63 的泥土层、相机下沉进实心方块 —— 缺块**机制**（共享可见集合被 lane pass 覆盖）
   与地形起伏无关，但**边界形状**与地形有关（超平坦 + 水平视线 → 直线边界；用户报告的地形语境下为「沿区块网格斜切」）。
2. **「前」对照 = 回落态**：B3-b 前的行为即回落路径（§12.9.3），故未额外用 `HEAD~1` 复跑；若要求「同一提交前后的二进制对照」，需另建 worktree 复跑（未做）。
3. **两态像素轮次不同步**：per-lane 帧率高（444.8 vs 168.2 fps），同一帧号对应脚本时间不同（r5 ≈ 脚本 3 s vs 6 s）；
   但静态段（t≥6 s 起相机静止）内场景不变，且回落态**自身** r2/r3（与 per-lane 一致）→ r4/r5（缺块）已构成自对照。
4. **代码树**：跑图时工作树含同批其他任务（B19）的未提交改动；剔除相关文件（`LaneCullStateMixin` / `LevelRendererMixin` /
   `CinematicOcclusion`）与 HEAD 逐字节一致，`LaneRenderer`/`ScriptLaneDriver` 各 1 行差异（非剔除路径）。两态两次跑图之间无新改动。
5. **内存口径**：直方图是全 JVM 类计数（`LinkedHashMap$Entry` 行含全 JVM 该类总量，非仅候选集）；`RenderChunkInfo` 计数可用于
   候选集规模判定。lane 槽复用 vanilla 候选集对象（首次播种），故「每 lane 独立集合」的**上界**只能按公式 [估算]，
   实测值随场景（相机是否移动 ≥8 格触发本 lane BFS）浮动。
6. **回落态内存**比 per-lane 大（7 256 vs 4 566 条）只说明「单份未剪枝集合 > 剪枝后的 3 份」在本场景成立；不是通用结论。
7. **世界副作用**：脚本命令（`tp @p` / `summon` / `kill @e[type=iron_golem]`）会改玩家朝向（`新的世界` 玩家 yaw/pitch 现为 90/5）
   并清掉**已加载区块内**的既有铁傀儡；方块未改动。跑后已 `kill` 本脚本傀儡；`entities/` 区另存有 3 个含 `iron_golem` 的
   既有区块（(-17,2) / (13,-11) / (8,16)，均非本脚本所在区块 = 早前会话遗留，未动）。

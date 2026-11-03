# H-2 轮项目地图与共享契约（主代理）

> 建立时间：2026-11-03。依据：本轮任务书（H-2 画面层与 HUD：H9 + H11 + H5/H6）+ `plans/0.3.6/H-other/skip-vote-and-hud.md` + `hud-layer-api.md` + `screen-layers.md` + `_archive/implementation-audit-2026-10-07.md` §三/§四 + 源码核对。
> 行号仅供定位，以类名 / 方法签名为准。`docs/` 已过时，只作线索；冲突以设计文档与源码为准。
> 前置轮次：H-1（已完成，`ROUND-H-1-MAP.md`）。本轮不碰 H-1 的事实层与连续语义。

---

## 〇、本轮实现口径（按设计文档推导，非用户待确认项）

| # | 项 | 口径 |
|---|---|---|
| 0.1 | 投票分母 | 单一分母 = `viewers.size() + voteCount(scriptId, instanceId)`；强停与广播共用同一函数 `neededVotes` |
| 0.2 | 阈值函数 | `max(1, ceil(total × ratio / 100))`，对齐 `SleepStatus.sleepersNeeded`；`total=0` 得 1，不误判 |
| 0.3 | 广播节流 | 对齐 `SleepStatus.update()` 的「变化才广播」：新增 `lastBroadcastVotes` / `lastBroadcastTotal`，与本次相同则不发 |
| 0.4 | 判定/广播顺序 | 先判定是否达标；达标 → 强停剩余观众 + 清账本，**不发普通更新包**；未达标 → 广播 |
| 0.5 | 观众清空 | `viewers.isEmpty()` 仍提前收尾（清账本），但它前面已无「先广播再判定」的分支；空集合下不调用阈值函数 |
| 0.6 | 比例非法 | `skip_vote_ratio` 非整数 / 越界（<10 或 >100）→ **解析期 `ScriptParseException`**（不静默回落）；校验器同步报 issue。范围仍 10~100，不允许 0 |
| 0.7 | HUD 布局 | 长按块与票数文本各自按「元素中心」的屏幕百分比定位；默认 `skipHud=(95,90)`、`voteHud=(50,90)`（同一行 = 同 y 缺省值） |
| 0.8 | 配置面 | 新增 4 个全局 int 键 `skipHudX/Y`、`voteHudX/Y`（0~100），**不加 ConfigScreen 控件**（与既有 `skipVoteRatio` 同款：文件/配置面可改，UI 不暴露） |
| 0.9 | 显示条件 | 票数行条件抄 MC：`!mc.isLocalServer() \|\| (mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isPublished())`；单人未发布只画长按块 |
| 0.10 | 注册门面位置 | `common/.../api/HudLayerRegistry`（与 `CameraApi` 同包）；内部持有唯一注册表，平台侧只做「分类解析优先级 + 平台回调」 |
| 0.11 | Forge 解析优先级 | `ForgeHudLayerRegistry` 自带原版表 → `HudLayerRegistry`（第三方）→ `overlayId.toString()` |
| 0.12 | Fabric 边界 | `FabricHudLayerRegistry` 自带表 → `HudLayerRegistry` → `layerId` 原串；**无 hard 模式**，注册只服务 `FabricHudVisibility.isHidden`；首次注册打一次边界日志 |
| 0.13 | `hud_layers` 语义 | 只覆盖**非固定分类**（default 分支）；11 个固定分类只读各自 `hide_*`，`hud_layers` 不覆盖。`health` / `status` 是 Forge 分类名、走 default 分支（可被 `hud_layers` 覆盖）——文档必须写清这个差别 |
| 0.14 | H5 范围 | 审计 §三 12 条逐条核对；只改「仍滞后」的条目，已清条目在收口表里记「已清 + 证据」；`editor/src/demo.ts` 不在本仓（`git ls-files editor` 为空），记契约边界 |
| 0.15 | H6 范围 | 审计 §四 7 条逐条跑；不可复现 / 无环境的两条（§四-5 间歇像素、§四-6 编辑器 UI）如实记「未复现 / 无环境」，不伪造 |
| 0.16 | 探针位置 | 仓库外 `E:/tmp/h2/`；临时脚本与日志不入库；实机夹具临时放 `fabric/run/immersive_cinematics/scripts/`，测完还原 |

---

## 一、任务清单

| 编号 | 目标（一句话） | 验收 | 依赖 | 对应切片 |
|---|---|---|---|---|
| **H2-1** | 投票阈值单一化：`neededVotes` + 广播同分母 + 达标先强停 + 变化才广播 | 同包无头探针 `VoteTallyProbe` 全 PASS（2人/1票、3人/50%、3人/10%、total=0、重复投票、节流） | — | 切片 1 |
| **H2-2** | `skip_vote_ratio` 非法值改为解析期报错 | 探针 `RatioParseProbe`：`0` / `5` / `101` / `"abc"` 全部 `ScriptParseException`；缺省仍 `null`；`10`/`100` 通过 | — | 切片 1 |
| **H2-3** | 跳过 HUD 单行 + 百分比中心坐标 + LAN 发布显示条件 + 4 个配置键 | 编译过；实机三组坐标截图（默认 / 50,50 / 0,0）；LAN 发布后票数行出现 | — | 切片 1 |
| **H2-4** | `api/HudLayerRegistry` 门面 + Forge 转调 / Fabric 边界 + 对外文档 | 探针 `HudLayerProbe` PASS；Forge 实机 hard 模式按分类重画 / 未注册被砍 | — | 切片 2 |
| **H2-5** | H5：审计 §三 12 条逐条核对 + 收口表 | 收口表 12 行齐全；`parallel-playback.md` 听者状态改 ✅（代码已对齐）；无新增滞后 | — | 切片 3 |
| **H2-6** | H6：审计 §四 7 条实机验证 + 记录 | 7 行记录（含证据路径 / 结论 / 未复现原因） | H2-1..H2-5 | 切片 4 |
| **H2-7** | 专篇回写 + 移交落盘 + 提交 | 三份专篇 §实现结果 更新；`I-BACKLOG.md` 新增 H-2 小节；`git commit`（不 push） | 全部 | 收尾 |

### 依赖图

```
H2-1 ─┐
H2-2 ─┼─→ H2-6 ─→ H2-7
H2-3 ─┤
H2-4 ─┤
H2-5 ─┘
```

### 派单波次（文件所有权隔离）

| 波次 | 并行任务 | 文件所有权 |
|---|---|---|
| 1 | H2-1、H2-2、H2-3、H2-4、H2-5 | H2-1：`trigger/server/ScriptEventManager.java`；H2-2：`script/ScriptParser.java`、`script/ScriptValidator.java`、`script/schema/MetaSchemas.java`；H2-3：`control/SkipHudRenderer.java`、`Config.java`、`forge/ForgeConfig.java`、`fabric/FabricConfig.java`、`H-other/skip-vote-and-hud.md`、`docs/modules/control.md`；H2-4：`api/HudLayerRegistry.java`（新建）、`forge/hud/ForgeHudLayerRegistry.java`、`fabric/hud/FabricHudLayerRegistry.java`、`forge/ForgeClientEvents.java`、`fabric/FabricEvents.java`、`docs/SCRIPT_FORMAT.md`、`H-other/screen-layers.md`、`H-other/hud-layer-api.md`；H2-5：`F-playback/parallel-playback.md`、`H-other/doc-lag-2026-11-03.md`（新建） |
| 2 | 主代理统一编译 + 探针复跑 + H2-6 实机 | — |
| 3 | H2-7 收尾 | `H-other/verification-2026-11-03.md`（新建）、`H-other/README.md`、`I-BACKLOG.md` |

> 波次 1 的子代理**不编译**（并发 gradle 会抢构建锁）；主代理在波次 2 统一跑模块级增量编译，失败按「补齐信息重派」处理。

---

## 二、现状与关键符号

### 2.1 跳过投票（服务端）

| 符号 | 位置 | 现状 |
|---|---|---|
| `ScriptEventManager` | `common/src/main/java/com/immersivecinematics/immersive_cinematics/trigger/server/ScriptEventManager.java` | 单例；账本 `scriptPlaybacks: Map<scriptId, Map<instanceId, ScriptPlayback>>` |
| `ScriptEventManager.onPlayerFinished(ServerPlayer, String scriptId, String instanceId, CompletionReason)` | 同上 :200-233 | 先 `viewers.remove`、跳过票入 `FactLedger`、`viewers.isEmpty()` 提前清账本返回；然后 `broadcastSkipVote`；再判定强停 |
| `ScriptEventManager.broadcastSkipVote(ScriptPlayback)` | 同上 :236-252 | **分母 = `viewers.size()`**（错）；无返回值、无节流；`pb.server` 已由构造器赋值（H-1 修） |
| `ScriptEventManager.ScriptPlayback` | 同上 :587-641 | 字段 `scriptId / instanceId / viewers / skipVoteRatio / server / ...`；无 `neededVotes`、无广播节流字段 |
| `FactLedger.INSTANCE.voteCount(scriptId, instanceId)` | `trigger/server/fact/FactLedger.java` | 当前票数来源（`skipVoters` 集合已删） |
| `S2CSkipVoteUpdatePacket.send(ServerPlayer, scriptId, voterCount, totalViewers)` | `trigger/network/S2CSkipVoteUpdatePacket.java` | 包体 `scriptId / voterCount / totalViewers` |
| `ClientScriptReceiver.handleSkipVoteUpdate` / `getSkipVoterCount` / `getSkipTotalViewers` | `trigger/client/ClientScriptReceiver.java` | 两个 int 全量覆盖；`resetSkipVote()` 清零 |

### 2.2 跳过 HUD（客户端）

| 符号 | 位置 | 现状 |
|---|---|---|
| `SkipHudRenderer.render(GuiGraphics)` | `common/.../control/SkipHudRenderer.java:30-44` | 门控：`Config.showSkipHud` → `mc.level != null` → `CameraManager.isScriptMode()` → `isTopInstanceSkippable()` → `!Boolean.TRUE.equals(isHideSkipHud())` |
| `SkipHudRenderer.renderInternal` | 同上 :46-95 | 长按块固定右下（`MARGIN_RIGHT=2` / `MARGIN_BOTTOM=4`）；票数固定 `(sw - voteW) / 2, sh - 30`，条件 `!mc.isLocalServer()` |
| 常量 | 同上 :20-27 | `ICON_SIZE=16`、`RING_RADIUS=12`、`RING_WIDTH=4`、`GAP_TEXT_RING=4`、`RING_SEGMENTS=80` |
| `CinematicKeyBindings.getSkipHoldProgress()` | `control/CinematicKeyBindings.java:115` | 0~1 进度 |
| `Config.showSkipHud` / `skipHoldThresholdMs` / `skipVoteRatio` | `Config.java` | 见 2.3 |
| `ClientEventHandler.onRenderHud(GuiGraphics)` | `handler/ClientEventHandler.java:152-158` | `CinematicOverlay.render` + `SkipHudRenderer.render`；Forge `RenderGuiEvent.Post` / Fabric `HudRenderCallback` 调用 |

### 2.3 配置面

| 层 | 位置 | 现状 |
|---|---|---|
| 静态字段 | `Config.java` | `skipHoldThresholdMs` / `showSkipHud` / `skipVoteRatio` / `debugLogging` / 5 个 trigger 轮询 / `cameraCenterReportInterval` / `ruleCommandPermissionLevel` |
| 值容器 | `Config.ConfigValues`（record，11 字段）+ `defaults()` + `apply()` | 新增 4 个 int 需同步 4 处 |
| Forge | `forge/.../ForgeConfig.java` | `ForgeConfigSpec` 静态定义 + `load()` 组装 + `setInt`/`setBoolean` 写回 |
| Fabric | `fabric/.../FabricConfig.java` | JSON `config/immersive_cinematics.json`；`load()` 读键 + `saveDefaults()` 写缺省 |
| UI | `client/ConfigScreen.java` | 只有 `showSkipHud` / `skipHoldThresholdMs` / `debugLogging` 三个控件；`skipVoteRatio` 无控件（先例） |

### 2.4 HUD 层判定与注册

| 符号 | 位置 | 现状 |
|---|---|---|
| `CinematicController.isLayerHidden(String)` | `common/.../control/CinematicController.java:330-347` | 11 个固定分类（`hotbar` `crosshair` `chat` `scoreboard` `bossbar` `subtitles` `action_bar` `title` `arm` `skip_hud` `bob`）→ `resolveLayer(hide*)`；default → `hudLayers.get(layer) ?? hideHud` |
| `CinematicController.computeUnion(List<BehaviorEntry>)` / `applyUnion(Union)` / `recomputeUnion` | 同上 :112-235 | 纯函数 + 写入；`hud_layers` 键并集按各实例 `hide_hud` 解析后 OR |
| `ForgeHudLayerRegistry` | `forge/.../forge/hud/ForgeHudLayerRegistry.java` | `LAYER_CATEGORIES` + `WORLD_OVERLAYS`；`register(ResourceLocation,String)` / `categoryOf` / `isWorldOverlay`；静态块注册原版 overlay 分类 |
| `FabricHudLayerRegistry` | `fabric/.../fabric/hud/FabricHudLayerRegistry.java` | `Map<String,String>`；`register(String,String)` / `categoryOf(String)` |
| `FabricHudVisibility.isHidden(String)` | `fabric/.../fabric/hud/FabricHudVisibility.java` | `CameraManager.isActive()` + `isLayerHidden(categoryOf(layerId))` |
| Forge normal 消费 | `forge/.../ForgeClientEvents.java:164-172` | `RenderGuiOverlayEvent.Pre`：世界层豁免 → `categoryOf` → `isLayerHidden` → cancel |
| Forge hard 消费 | 同上 :124-161 | `RenderGuiEvent.Pre` HIGHEST cancel + `renderHardHide`（复位高度 → 逐层重画 → 补画本模组 overlay） |
| `api` 包 | `common/.../api/{CameraApi,CameraState,BaseCameraProvider}.java` | 对外稳定面；`api` 只增不删 |

### 2.5 平台初始化入口

| 平台 | 位置 | 用途 |
|---|---|---|
| Forge | `forge/.../forge/ImmersiveCinematicsForge`（主入口）+ `ForgeClientEvents.onClientSetup(FMLClientSetupEvent)` | 客户端专属初始化点 |
| Fabric | `fabric/.../fabric/client/ImmersiveCinematicsFabricClient` → `FabricEvents.registerClient()` | 客户端专属初始化点 |

### 2.6 文档（本轮要动的）

| 文件 | 现状 |
|---|---|
| `plans/0.3.6/H-other/skip-vote-and-hud.md` | §六「实现结果」表：9 行目标，6 行「未实施」；两处口径风险段落 |
| `plans/0.3.6/H-other/hud-layer-api.md` | §二 现状表 + §三 目标 + §六 验收清单；状态「设计稿（未实现）」 |
| `plans/0.3.6/H-other/screen-layers.md` | §九 实现结果：`hard_hide_hud` 已实现、统一语义未实现；缺 API 段落 |
| `docs/SCRIPT_FORMAT.md:95-96,114` | `hud_layers` / `hard_hide_hud` 各一行；未写固定分类优先级、未写「未走 overlay 无口子」 |
| `docs/modules/control.md:20,31` | `hard_hide_hud` 与跳过 HUD 各一行 |
| `plans/0.3.6/F-playback/parallel-playback.md:186,242,248-249` | 听者口径写「未对齐」（代码已按顶层实例对齐）；「跳过投票 UI」仍开放 |
| `plans/0.3.6/H-other/README.md` | H5/H6/H9/H11 行状态 |

### 2.7 H5 审计 §三 12 条核对结果（本轮开工前实测）

| # | 条目 | 现状 | 结论 |
|---|---|---|---|
| 1 | `parallel-playback.md` 末行三项状态 | 听者口径**代码已对齐**（`AudioListenerController` 用 `CameraManager.topInstanceHasActiveCameraClip()`），文档仍写 🟡 未对齐 | **仍滞后 → 本轮改** |
| 2 | `CameraManager` javadoc 挂点 `LaneRendererMixin` | `grep LaneRendererMixin` 在 `CameraManager.java` 零命中；:134 已是 `GameRendererMixin` | 已清 |
| 3 | `plans/0.3.6/README.md` 索引 `editor-script-graph` 🔵 / feedback 03 | `grep editor-script-graph` 零命中 | 已清 |
| 4 | `feedback-0.3.5/README.md` 04 行 | 已写「#1 ✅ 已结案（H4）… #2 未实现（归 C3）」 | 已清 |
| 5 | `docs/SCRIPT_FORMAT.md:59-63` / `AI_SCRIPTING_GUIDE.md:125` 仍写 `meta.preload` | 两处已改成「0.3.6 起移除 `meta.preload` 与 `camera_mob_*`」 | 已清 |
| 6 | `docs/modules/editor.md:34`「`enterFlightMode` 忽略 x/y/z」 | 该节现为「已知问题 - 无」 | 已清 |
| 7 | `scene-transition.md:47` letterbox「无 alpha 字段」 | 现 :167 写「`opacity` 已接线（D10）」 | 已清 |
| 8 | `editor-webui-migration.md` §1.2/§2/§3 | 文件已不在 `plans/`（`plans/complete/0.3.5/webui-*` 为旧版）；审计当时条目由 `1f60c9d` 回写 | 已清 |
| 9 | `temporal-interpolation.md` 已知缺陷两条 | `plans/complete/0.3.6/B-camera/temporal-interpolation.md:227-257` 已逐条标「已修复/已过时」 | 已清 |
| 10 | `hysteresis.md` clip 级 `interpolation` | `plans/complete/0.3.6/B-camera/hysteresis.md:251-253` 已写「0.3.6 起整体移除」 | 已清 |
| 11 | `multi-camera-rendering.md` §12.1 `LaneDebugDriver` | :499 / :524 已写「已删除」「只作历史记录」 | 已清 |
| 12 | `editor/src/demo.ts` 离线 schema 陈旧 | `git ls-files editor` = 0；仓内 `editor/` 只有 `dist/ node_modules/ release/` | **文件不在本仓 → 契约边界** |

### 2.8 H6 审计 §四 7 条环境评估

| # | 条目 | 环境需求 | 可行性 |
|---|---|---|---|
| 1 | hud-hard-hide：注册表 overlay 的 Pre 拦截 | Forge 客户端 + 第三方 overlay 模组 | 部分：Forge dev 客户端可跑；第三方模组不装，用**原版未注册 overlay**（`minecraft:hotbar_separator` / `minecraft:experience_level`）覆盖同一判定路径 |
| 2 | 叠化/黑场/白场/合成参数上屏 | Fabric 客户端 | 可：`cinematics/0.3.6/tests/transition/*.json` |
| 3 | screen-color-adjust 上屏 + Iris 先后顺序 + 性能 | Fabric + Iris + 光影包 | 可：`fabric/run/shaderpacks/` 有 4 个包 |
| 4 | 脚本 i18n 多语言切换 | Fabric 客户端 + `options.txt` 语言 | 可 |
| 5 | lane 缺陷症状 B 像素实证 | 间歇复现 | 不可控：前 5 轮未采到现场 → 如实记「未复现」 |
| 6 | 编辑器 UI 交互 | 编辑器源码 | 无环境：编辑器源码不在仓 → 如实记 |
| 7 | template 命令 + release 脚本游戏内实跑 | 服务端（单人集成服即可） | 可：`/icinematics template list` / `template <id>` / `validate` |

---

## 三、本轮缺口分类

### 3.1 本轮做

- 投票阈值/分母/节流/顺序（H9 §六 前 4 行）。
- `skip_vote_ratio` 解析期报错（H9 §六「非法比例」行）。
- HUD 单行 + 4 坐标键 + LAN 发布显示条件（H9 §六 后 2 行）。
- `api/HudLayerRegistry` + 平台边界 + `hud_layers` 语义对外文档（H11 全部）。
- H5 剩余 1 条 + 12 条收口表。
- H6 可跑的 5 条 + 2 条如实记录。

### 3.2 移交后续轮次

| 事项 | 归属 | 关闭条件 |
|---|---|---|
| 多实例跳过投票 UI（同时可跳过时投的是哪个） | 后续轮次（未排期，随「实例 id 网络协议形态」一起） | 多实例同时可跳过时 HUD 能区分实例并呈现各自票数 |
| `S2CSkipVoteUpdatePacket` 按 `scriptId` 分流缓存 | 同上 | 客户端缓存按脚本隔离；包内 `scriptId` 被消费 |
| Fabric `hard_hide_hud` 等价能力 | 未排期（`screen-layers.md` §5.5 边界） | Fabric 有可取消的整段 HUD 回调或等价挂点 |
| 编辑器离线 schema（`editor/src/demo.ts`） | 编辑器阶段 | 编辑器源码回到仓内后按 `SchemaExporter` 同步 |
| 第三方 overlay 真机复测（TACZ / SecurityCraft） | 后续实机批次 | 装上对应模组后在 hard 模式下验证 Pre 自绘被砍 |

### 3.3 契约边界（不排期，但必须写进文档）

- 固定分类 11 项只读各自 `hide_*`，`hud_layers` 不覆盖。
- `health` / `status` 不是固定分类（Forge 分类名，走 default 分支）。
- `hard_hide_hud` 仅 Forge；Fabric 字段被解析但不生效。
- 不走 Forge overlay 注册表、也不在 `RenderGuiEvent.Pre` 自绘的第三方 HUD 没有挂点。
- 白名单模式不补发 overlay 事件。
- 坐标不做越界保护（元素中心可落在屏幕外）。
- 比例不允许 0（10~100）。

---

## 四、验证与记录约定

| 类别 | 约定 |
|---|---|
| 编译 | `sh gradlew :common:compileJava --offline --no-daemon`；涉及平台侧时追加 `:fabric:compileJava` / `:forge:compileJava`（模块级，禁止全量 `build`） |
| 无头探针 | `E:/tmp/h2/*.java`，编译到 `E:/tmp/h2/out-*/`，类路径 = `common/build/classes/java/main` + gradle 缓存依赖 + MC merged jar（模板见 `E:/tmp/h1/run-fact.sh`）；探针可声明与目标相同的包以访问包私有成员 |
| 实机 | `tools/devtools/.venv/Scripts/python.exe tools/devtools/devtools.py`：`client` / `cmd` / `shot` / `tail` / `trace` / `where`；启动前先确认无残留 Minecraft 进程 |
| 夹具 | 临时脚本放 `fabric/run/immersive_cinematics/scripts/`（不入库），测完删除；正式夹具才进 `cinematics/0.3.6/tests/` |
| 记录 | H6 记录写 `plans/0.3.6/H-other/verification-2026-11-03.md`；H5 收口表写 `plans/0.3.6/H-other/doc-lag-2026-11-03.md` |
| 提交 | `feat(0.3.6): H-2 跳过投票与 HUD 层注册 API`（或按切片拆分）；**不 push** |

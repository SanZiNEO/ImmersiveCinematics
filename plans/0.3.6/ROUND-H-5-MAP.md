# H-5 轮项目地图与共享契约（主代理）

> 建立时间：2026-11-04。依据：本轮任务书（H-5 画面层统一语义）+ `plans/0.3.6/H-other/screen-layers.md`（主文档）+ 源码核对。
> 行号仅供定位，以类名 / 方法签名为准。`docs/` 已过时，只作线索；冲突以设计文档与源码为准。
> 前置轮次：H-2（`api/HudLayerRegistry` 注册门面，已完成）、D 轮转场/覆盖层（已完成）、G 轮 `hud_layers` 并集（已完成）。

---

## 〇、本轮实现口径（用户裁决 + 由裁决推导的细节）

| # | 项 | 口径 | 来源 |
|---|---|---|---|
| 0.1 | `screen_layer_mode` 缺省 | **新字段路径缺省 `allowlist`**；完全不带 `screen_layers` / `screen_layer_mode` / `hard_hide_hud` 的旧脚本仍走今天的三态（缺省 normal） | 用户裁决 A |
| 0.2 | `hud_layers` 关系 | **`screen_layers` 完全取代 `hud_layers`**：解析期归一（极性翻转）、旧键继续可用、文档只讲新字段、`hud_layers` 标 deprecated | 用户裁决 B |
| 0.3 | `hard_hide_hud` 关系 | 解析期归一为模式：`true` → `allowlist`，`false` → `normal`；`screen_layer_mode` 显式声明优先（冲突时解析期告警）；`hard_hide_hud` 在 schema / 文档标 deprecated 别名。**不存在第二套判定逻辑** | 任务书「写入口径只能有一个」 |
| 0.4 | 模式并集 | 任一活跃实例 `allowlist` → 整段 `allowlist`（强模式优先）；无实例 → 复位 | 设计 §3.2「任一活跃实例声明即生效」 |
| 0.5 | 层判定顺序 | `screen_layers[规范层名]` → `screen_layers[调用方给的原始 category 键]` → 该层旧字段回落 → `hide_hud` | 本轮契约，见 §2.3 |
| 0.6 | 接管条件 | 有活跃实例 + 生效模式 = allowlist + `anyScreenLayerHidden()`；旧 `hard_hide_hud:true` 在无 `screen_layers` 时与旧条件（`hide_hud` 生效）等价 | 推导，见 §2.3 |
| 0.7 | 平台能力 | `HudLayerRegistry.Platform#supportsScreenLayerTakeover()`（default `false`）；Forge 装 `true`，Fabric 保持 `false` → 生效模式降级 `normal` | 任务书 §3.5 重新定性 |
| 0.8 | 编辑器播放 | 已与普通播放共用 `computeUnion`（`PlaybackRegistry.instanceBehaviors` 带 `editorDriven`）；`screen_layers` 走同一并集，**不加额外过滤** | 源码核对 |
| 0.9 | `screen_layers` 极性 | `true` = **显示**；并集按隐藏方向 OR（任一实例判隐藏即隐藏） | 设计 §3.3 |
| 0.10 | `screen_layers` 键域 | 15 个规范层名 + 11 个旧固定分类别名 + 注册分类名 + 未注册 overlay 的完整资源 ID | 设计 §3.1 / 任务书验收 |
| 0.11 | 固定分类优先级保持 | `hud_layers`（旧入口）写固定分类名仍是 no-op（今天如此）；解析期改为**丢弃 + 告警**，不改变行为 | 零回归 |
| 0.12 | 旧键动态语义保持 | 旧 `hud_layers` 的 `health` / `status` / 未注册 ID 等键仍按**原始键**参与判定（`health`/`status` 是 Forge 分类名，不映射为规范层名） | 零回归（见 §2.4 说明） |
| 0.13 | 探针位置 | 仓库外 `E:/tmp/h5-probe/`；不入库；测完删除 | 任务书约定 |
| 0.14 | 实机夹具 | 正式夹具进 `cinematics/0.3.6/tests/hud/`；临时 overlay 代码测完删除（沿用 H-2 做法） | H-2 先例 |

### 0.15 已知有意对齐（必须写进文档，不算静默改口径）

- **无**。本轮所有旧入口行为保持；`hud_layers` 固定分类键从「静默 no-op」改为「丢弃 + 解析期告警」，判定结果不变。
- `hud_layers` 的 `health` / `status` 键在 Fabric 上仍不生效（与今天一致）——这两个键是 Forge 分类名，只经 Forge overlay 消费链命中。

---

## 一、任务清单

| 编号 | 目标（一句话） | 验收 | 依赖 |
|---|---|---|---|
| **H5-1** | 层清单 + 并集/回落：`ScreenLayer` 表、`screen_layers` 并集、模式归一与平台降级、`isLayerHidden` 双参判定 | 无头探针 `ScreenLayerProbe` 全 PASS（15 层表、别名、回落、并集 OR、模式归一、平台降级、编辑器实例参与） | — |
| **H5-2** | `screen_layers` / `screen_layer_mode` 解析、`hud_layers` 归一、校验、schema 导出 | 探针 `ScreenLayerParseProbe`：合法/非法字段、极性翻转、固定分类键丢弃告警、`SchemaExporter` 含两字段 | H5-1 契约（字段名固定） |
| **H5-3** | `allowlist` 接管与重画接线（合并现有 `renderHardHide`，不并存两套） | 编译通过 + Forge 实机：allowlist 清空 + 白名单重画 + 本模组覆盖层 | H5-1、H5-2 |
| **H5-4** | `normal` 路径对齐 + 平台降级 + 15 层消费点（mixin）改用规范层名 | 编译通过 + Fabric 实机：normal 行为与今天一致 | H5-1、H5-3（能力位） |
| **H5-5** | 夹具 + 文档回写 + 实机（Fabric normal / Forge allowlist）+ 移交落盘 + 提交 | 两份夹具；`docs/SCRIPT_FORMAT.md` 与 `screen-layers.md` §九 回写；两条实机记录；`I-BACKLOG.md` 新增小节 | H5-1..H5-4 |

### 依赖图 / 波次

```
波次1（并行，文件所有权互斥）
  H5-1 ─┬─→ H5-3 ─┐
  H5-2 ─┘         ├─→ 波次2 主代理统一编译 + 探针 → H5-5
  H5-4 ───────────┘
```

| 波次 | 任务 | 文件所有权（禁止越界） |
|---|---|---|
| 1 | H5-1 | `common/.../script/ScreenLayer.java`（新建）、`common/.../script/ScreenLayerMode.java`（新建）、`common/.../control/CinematicController.java`、`common/.../script/ScriptMeta.java`、`E:/tmp/h5-probe/ScreenLayerProbe.java`（仓库外） |
| 1 | H5-2 | `common/.../script/ScriptParser.java`、`common/.../script/ScriptValidator.java`、`common/.../script/schema/MetaSchemas.java`、`E:/tmp/h5-probe/ScreenLayerParseProbe.java`（仓库外） |
| 1 | H5-3 | `common/.../api/HudLayerRegistry.java`、`forge/.../forge/hud/ForgeHudLayerRegistry.java`、`forge/.../forge/ForgeClientEvents.java` |
| 1 | H5-4 | `common/.../mixin/{GuiMixin,ChatComponentMixin,BossHealthOverlayMixin,PlayerTabOverlayMixin,SubtitleOverlayMixin,GameRendererMixin,CameraMixin}.java`、`fabric/.../fabric/FabricEvents.java` |
| 2 | 主代理 | 模块级增量编译（`:common` → `:fabric` / `:forge`）+ 探针复跑；失败补齐信息重派 |
| 3 | H5-5 | `cinematics/0.3.6/tests/hud/*.json`（新建）、`docs/SCRIPT_FORMAT.md`、`plans/0.3.6/H-other/screen-layers.md`、`plans/0.3.6/H-other/README.md`、`plans/0.3.6/implementation-progress.md`、`plans/0.3.6/I-BACKLOG.md` |

> 波次 1 的子代理**不跑 gradle、不提交**；主代理在波次 2 统一跑模块级增量编译。

---

## 二、现状与关键符号

### 2.1 现有实现（零回归基线）

| 符号 | 位置 | 现状 |
|---|---|---|
| `CinematicController.isLayerHidden(String)` | `common/.../control/CinematicController.java:330-347` | 11 个固定分类（`hotbar` `crosshair` `chat` `scoreboard` `bossbar` `subtitles` `action_bar` `title` `arm` `skip_hud` `bob`）→ `resolveLayer(hide*)`；default → `hudLayers.get(layer) ?? hideHud` |
| `computeUnion(List<BehaviorEntry>)` | 同上 :108-190 | 纯函数；`hud_layers` 键并集 = 各实例按自身 `hide_hud` 解析后 OR；`hardHideHud` 走 `effectiveHardHide`（仅显式 `true`） |
| `applyUnion` / `recomputeUnion` / `resetBehaviorToggles` | 同上 :192-280 | 写入字段 + `updateScreenEffectScale()`；复位含 `hardHideHud=null`、`hudLayers.clear()` |
| `effective` / `effectiveDistortion` / `effectiveHardHide` | 同上 :228-243 | 三态回落：`suppress_distortion → suppress_bob → hide_hud`；`hard_hide_hud` 不回落 |
| `shouldSuppressDistortion` / `updateScreenEffectScale` | 同上 :374-410 | `screenEffectScale` 保存/恢复；`Minecraft.getInstance()` 未判空 |
| `ScriptMeta.RuntimeBehavior` | `common/.../script/ScriptMeta.java:150-260` | record（27 组件）+ Builder + `DEFAULT`；含 `hardHideHud`、`hudLayers` |
| `ScriptParser.parseMeta` | `common/.../script/ScriptParser.java:258-339` | 读 `hard_hide_hud`（:297）、`hud_layers`（:326 → `parseHudLayers:2075`） |
| `parseHudLayers` | 同上 :2074-2086 | 只收布尔值；`true` = 隐藏 |
| `MetaSchemas.all()` | `common/.../script/schema/MetaSchemas.java:47-57` | `hud_layers` / `hard_hide_hud` 各一行；`SchemaExporter.exportAll()` 直接转发，无特判 |
| `ScriptValidator` | `common/.../script/ScriptValidator.java` | **对 `hud_layers` / `hard_hide_hud` 零校验**（grep 确认） |
| `ForgeClientEvents.onRenderGuiPre` | `forge/.../forge/ForgeClientEvents.java:92-120` | HIGHEST；`isHardHideActive()` → cancel + `renderHardHide` |
| `ForgeClientEvents.renderHardHide` | 同上 :122-161 | 复位左右高度 → 遍历 `GuiOverlayManager.getOverlays()`（世界层豁免）→ `isLayerHidden(categoryOf(id))` → 重画 → 补画 `ClientEventHandler.onRenderHud` |
| `ForgeClientEvents.onRenderGui` (Post) | 同上 :104-110 | allowlist 生效时早退（每帧只画一次） |
| `ForgeClientEvents.onRenderGuiOverlayPre` | 同上 :164-172 | normal：世界层豁免 → `categoryOf` → `isLayerHidden` → cancel |
| `ForgeHudLayerRegistry` | `forge/.../forge/hud/ForgeHudLayerRegistry.java` | 内置原版 overlay → 分类表（`health` / `status` 等）+ `WORLD_OVERLAYS`；`categoryOf` = 内置 → 门面 → `overlayId.toString()` |
| `FabricHudLayerRegistry` / `FabricHudVisibility` | `fabric/.../fabric/hud/` | 自有表 → 门面 → 原始层 ID；`isHidden` = 激活 + `isLayerHidden(categoryOf(layerId))` |
| `HudLayerRegistry` | `common/.../api/HudLayerRegistry.java` | `install(Platform)` / `register(id[,cat])` / `categoryOf`；`Platform` 只有 `onRegistered` |
| 原版 HUD 逐层抑制 | `common/.../mixin/GuiMixin.java` + `ChatComponentMixin` / `BossHealthOverlayMixin` / `PlayerTabOverlayMixin` / `SubtitleOverlayMixin` | `GuiMixin` 用 `shouldHide(hide*)`；聊天 / Boss 血条 / 玩家列表 / 字幕各自查单项开关 |
| 手臂 / 摇晃 / 玩家模型 | `GameRendererMixin:138-160`、`CameraMixin:96` | 读 `isHideArm()` / `isSuppressBob()` / `isRenderPlayerModel()` |
| 编辑器播放 | `PlaybackRegistry.instanceBehaviors:155-161` → `PlaybackLifecycle:331/406` | 编辑器实例以 `editorDriven=true` 参与内容行为并集 |

### 2.2 15 个规范层名与旧字段映射（本轮定稿）

| # | 规范层名 | 旧字段 | 旧固定分类别名 | 空间 | 回落口径 |
|---|---|---|---|---|---|
| 1 | `hud.hotbar` | `hide_hotbar` | `hotbar` | HUD | `hide_hotbar ?? hide_hud` |
| 2 | `hud.crosshair` | `hide_crosshair` | `crosshair` | HUD | 同上 |
| 3 | `hud.health` | `hide_hud` | —（`health` 是 Forge 分类名，不作别名） | HUD | `hide_hud` |
| 4 | `hud.experience` | `hide_hud` | —（`status` 是 Forge 分类名，不作别名） | HUD | `hide_hud` |
| 5 | `hud.bossbar` | `hide_bossbar` | `bossbar` | HUD | `hide_bossbar ?? hide_hud` |
| 6 | `hud.chat` | `hide_chat` | `chat` | HUD | 同上 |
| 7 | `hud.scoreboard` | `hide_scoreboard` | `scoreboard` | HUD | 同上 |
| 8 | `hud.action_bar` | `hide_action_bar` | `action_bar` | HUD | 同上 |
| 9 | `hud.title` | `hide_title` | `title` | HUD | 同上 |
| 10 | `hud.subtitle` | `hide_subtitles` | `subtitles` | HUD | 同上 |
| 11 | `hud.skip_prompt` | `hide_skip_hud` | `skip_hud` | HUD | **`hide_skip_hud == true`**（不回落 `hide_hud`） |
| 12 | `viewmodel` | `hide_arm` | `arm` | WORLD | `hide_arm ?? hide_hud` |
| 13 | `view_bobbing` | `suppress_bob` | `bob` | VIEW | `suppress_bob ?? hide_hud` |
| 14 | `screen_effects` | `suppress_distortion` | — | POST | `suppress_distortion ?? suppress_bob ?? hide_hud` |
| 15 | `player_model` | `render_player_model`（true=显示） | — | WORLD | `!render_player_model`（不回落 `hide_hud`） |

### 2.3 判定与并集契约（实现必须逐条对齐）

**单实例层判定** `perInstanceHidden(b, key)`（`b` = `RuntimeBehavior`）：

1. `b.screenLayers().get(canonicalize(key))` 命中 → `!value`；
2. 已知层且 `b.screenLayers().get(key)` 命中（旧 `hud_layers` 归一的原始键）→ `!value`；
3. 已知层 → `ScreenLayer.legacyHidden(layer, b)`（上表回落口径）；
4. 未知名 → `b.hideHud()`。

**并集**：键集合 = 全部实例 `screenLayers` 的键（已知层归一到规范名，其余保留原始键）；每个键的值 = 全部实例 `perInstanceHidden` 的 OR。

**运行时查询** `CinematicController.isLayerHidden(layerName, categoryKey)`：

1. `canonical = ScreenLayer.canonicalize(layerName)`；
2. `screenLayers.get(canonical)` 命中 → 返回；
3. `categoryKey != null && !categoryKey.equals(canonical)` 且 `screenLayers.get(categoryKey)` 命中 → 返回；
4. `ScreenLayer.byId(canonical) != null` → 控制器字段按上表回落；
5. 返回 `hideHud`。

`isLayerHidden(String)` = `isLayerHidden(layer, layer)`。

**模式归一**（解析期，`ScreenLayerMode` 非 null 落库）：

```
declared screen_layer_mode
  ?? (hard_hide_hud == true → allowlist)
  ?? (hard_hide_hud == false → normal)     // 显式退出接管
  ?? (screen_layers 字段存在 → allowlist)   // 新字段路径缺省
  ?? normal                                 // 旧脚本零回归
```

**生效模式**：`isAllowlistMode()` = `screenLayerMode() == ALLOWLIST && HudLayerRegistry.supportsScreenLayerTakeover()`。

**接管条件**：`CameraManager.isActive() && isAllowlistMode() && anyScreenLayerHidden()`。
`anyScreenLayerHidden()` = 15 个规范层任一 `isLayerHidden(id)` 为真，或并集表里任一动态键 `isLayerHidden(k, k)` 为真。

**旧条件等价性**（必须由探针证明）：无 `screen_layers` 的 `hard_hide_hud:true` 脚本，`anyScreenLayerHidden()` 为真 ⟺ `isHideHud()` 为真（15 层回落全部由 `hide_hud` 决定，`hud.skip_prompt` 回落 false、`player_model` 回落 `!renderPlayerModel` 且默认显示）。

> `hud.skip_prompt` 默认显示、`player_model` 默认显示：两者在 `hide_hud=true` 时仍为「显示」，因此「任一旧层隐藏」在 `hide_hud=true` 时恒真（其余 13 层里 `hud.health` 等回落 `hide_hud`）✓。

### 2.4 `hud_layers` 归一规则（解析期）

- 键经 `ScreenLayer.canonicalize` 命中已知层（含 11 个别名）→ **丢弃 + 解析期告警**（今天就是 no-op，行为不变）。
- 其余键（`health` / `status` / 未注册 ID / 任意字符串）→ 保留**原始键**，值极性翻转（`hide` → `show`），与 `screen_layers` 同表。
- 同键冲突（脚本同时写了 `screen_layers` 与 `hud_layers`）：`screen_layers` 优先（规范名先查）。
- 文档口径：`hud_layers` 为 deprecated 入口，语义已被 `screen_layers` 吸收。

### 2.5 消费点改造（规范层名）

| 消费点 | 现状 | 目标 |
|---|---|---|
| `GuiMixin.renderHotbar` | `isHideHotbar()` 三态 | `isLayerHidden("hud.hotbar")` |
| `GuiMixin.renderCrosshair` | `isHideCrosshair()` | `isLayerHidden("hud.crosshair")` |
| `GuiMixin.renderPlayerHealth` / `renderVehicleHealth` | `shouldHide(null)` → `hideHud` | `isLayerHidden("hud.health")` |
| `GuiMixin.renderExperienceBar` | `shouldHide(null)` | `isLayerHidden("hud.experience")` |
| `GuiMixin` action bar / title | `isHideActionBar()` / `isHideTitle()` | `isLayerHidden("hud.action_bar")` / `isLayerHidden("hud.title")` |
| `GuiMixin.displayScoreboardSidebar` | `isHideScoreboard()` | `isLayerHidden("hud.scoreboard")` |
| `GuiMixin` 跳跃条 / 选中物品名 / 保存提示 | `shouldHide(null)` | **保持 `shouldHide(null)`**（不在 15 层内，零回归） |
| `ChatComponentMixin` | `isHideChat()` | `isLayerHidden("hud.chat")` |
| `BossHealthOverlayMixin` | `isHideBossbar()` | `isLayerHidden("hud.bossbar")` |
| `PlayerTabOverlayMixin` | `isHideScoreboard()` | `isLayerHidden("hud.scoreboard")` |
| `SubtitleOverlayMixin` | `isHideSubtitles()` | `isLayerHidden("hud.subtitle")` |
| `GameRendererMixin` 手臂 / 摇晃 | `isHideArm()` / `isSuppressBob()` 三态 | `isLayerHidden("viewmodel")` / `isLayerHidden("view_bobbing")` |
| `CameraMixin` 玩家模型 | `isRenderPlayerModel()` | `!isLayerHidden("player_model")` |
| `ForgeClientEvents.onRenderGuiOverlayPre` | `isLayerHidden(categoryOf(id))` | `isLayerHidden(layerOf(id), categoryOf(id))` |
| `ForgeClientEvents.renderHardHide` | 同上 | 同上（合并为 `renderAllowlist`） |
| `FabricHudVisibility.isHidden` | `isLayerHidden(categoryOf(layerId))` | 不变（单参 = 原始键） |
| `SkipHudRenderer` | `isHideSkipHud()` | 不变（等价 `!isLayerHidden("hud.skip_prompt")`） |

---

## 三、缺口分类

### 3.1 本轮做

- 15 层清单 + `ScreenLayer` 表 + 别名/回落/并集（H5-1）。
- `screen_layers` / `screen_layer_mode` 解析、`hud_layers` 归一、`hard_hide_hud` 归一、校验、schema（H5-2）。
- `allowlist` 接管与重画合并（H5-3）。
- `normal` 路径按规范层名对齐 + 平台能力降级（H5-4）。
- 夹具、文档回写、实机两条、移交落盘（H5-5）。

### 3.2 移交后续轮次

| 事项 | 归属 | 关闭条件 |
|---|---|---|
| 真实第三方 overlay 模组（TACZ / SecurityCraft 类）复测 | 后续实机批次（承接 H2-R4） | 装上对应模组后，在 `screen_layer_mode:allowlist` 下确认 Pre 自绘与 overlay 均被接管/重画 |
| 编辑器 UI 暴露 `screen_layers` / `screen_layer_mode` 表单 | Z 轮 | 编辑器源码回仓后按 `SchemaExporter.meta` 生成控件 |
| Fabric 具备可取消的整段 HUD 回调后启用 allowlist | 未排期 | Fabric 出现等价挂点后实现并实机 |
| `docs/modules/control.md` / `mixin.md` 同步 | 后续文档批次 | 两处按 `screen_layers` 口径改写；`grep` 无 `hud_layers` 旧口径 |

### 3.3 契约边界（写进文档，不排期）

- 世界内容（实体 / 粒子 / 天气 / 地形）不受控。
- 脚本覆盖层（黑边 / 字幕 / 图片 / GIF）不参与白名单，始终按脚本绘制。
- 不补发 overlay 事件；依赖 `RenderGuiOverlayEvent` 的第三方逻辑在 allowlist 下收不到事件。
- 取消只影响排在接管监听器之后的订阅者；同优先级更早注册的监听器仍会执行。
- 直接 mixin 进 `Gui.render` 或绕过平台入口绘制的 HUD 没有稳定挂点。
- Fabric 注册表只影响普通层过滤；`allowlist` 在 Fabric 降级为 `normal`。
- `hud.health` / `hud.experience` 共用 `hide_hud`：旧字段无法分别控制，只有 `screen_layers` 能拆开。

---

## 四、验证与记录约定

| 类别 | 约定 |
|---|---|
| 编译 | `sh gradlew :common:compileJava --offline --no-daemon` → `:fabric:compileJava` → `:forge:compileJava`（模块级，禁止全量 `build`） |
| 无头探针 | `E:/tmp/h5-probe/*.java`，类路径模板见 `E:/tmp/h1/run-fact.sh`；探针打印 `PASS`/`FAIL` + 汇总，失败退出码非 0 |
| 实机 | `tools/devtools/.venv/Scripts/python.exe tools/devtools/devtools.py`：`client` / `cmd` / `shot` / `tail`；Forge 用 `:forge:Client` 且 `IC_RUN_DIR=forge/run`；启动前确认无残留 Minecraft 进程 |
| 截图 | 一律 `ic shot`（游戏内 F2），不抓屏幕区域 |
| 临时 overlay | 仅用于实机取证，测完删除（沿用 H-2 做法） |
| 提交 | `feat(0.3.6): H-5 画面层统一语义`；**不 push** |

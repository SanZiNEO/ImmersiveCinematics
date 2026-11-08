# ROUND J-1 地图（旧模型残留清零 + Fabric allowlist 接管）

> 依据：任务书（2026-11-07）+ `misc/old-dead-code.md` / `misc/old-new-shared-code.md` / `misc/obsolete-old-methods.md` / `misc/fabric-hud-takeover.md`。
> 基线：`HEAD 59a5fb4`，工作区干净（六个设计文档未跟踪）。
> 口径：**只删旧、不加新能力**；唯一例外是交付物 11（Fabric allowlist 接管，平台能力补齐）。

## 一、任务清单（11 项，全部本轮做完）

| # | 目标 | 验收 |
|---|---|---|
| 1 | 位置/朝向旧模型清零：`PointKinds`/`PointKind`/`PointFrame`/`PointOffset`/`EntityPoint` 落地；删 `PositionData`、`camera/source/PointSource`；`CameraKeyframeEvaluator`→`CameraFrameEvaluator`；删 `facing_origin` 归一与 `snapshot/restoreLockState` 转发；`ObjectCameraEvaluator.evaluate` 去 `laneRank` | `PositionData\|camera\.source\.PointSource\|facing_origin\|CameraKeyframeEvaluator` 在 common/fabric/forge → 0；`:common:compileJava` |
| 2 | `Clip` 显式 `loop/loopCount/loopMode`；删 `data`/`getData`/`declare` 的 data 分支/5 个泛型读取口/17 个旧访问器；消费方改 `ClipValueView`/`SampledClip` | `Map<String, Object> data`/`getData()`/`.getString(` 等（clip 相关）→ 0；`:common:compileJava` |
| 3 | schema/导出清零：删七轨 `clips`/`kfs` 表、`TrackTypeSchema` 两字段与 2 参构造、`SchemaExporter` 两段、`SchemaLoader` 整类、`SchemaRegistry` 9 个死方法、`MetaSchemas.hud_layers/hard_hide_hud`、`CompositePaths`、`SelectorSchema.CALLPOINTS/isCallpoint/callpointField` | 对应符号 → 0；`schema.get` 只含 meta/objects/playControl/triggers/models/curveTypes/script_structure；`:common:compileJava` |
| 4 | 删 `script/template/` 10 文件 + `/icinematics template` 子树；保留 `ScriptTemplate`/`script.new`；删旧路径解码 `replace('_','/')` | 目录不存在；`TemplateRegistry\|TemplateScriptAssembler\|ClipTemplate` → 0；命令树无 `template` |
| 5 | 规则协议收缩 `(bindingId, actionIndex)`；bump `PROTOCOL_VERSION`；删 `RuleCommandAudit` 旧分支 | `clipIndex\|ruleIndex` 在 trigger/camera.rule → 0；探针按二元键命中 |
| 6 | 删 `LegacyStateMigration`、`FactLedger` v1 分支与 `legacyTriggers` | `LegacyStateMigration\|legacyTriggers\|version == 1` → 0 |
| 7 | 画面层：一次性迁移脚本（E:/tmp）物化 15 层 + 删旧字段/别名/回落/`LegacyFlags`；`GuiMixin` 改按 `screen_layers`；文档口径 | `hide_*\|suppress_*\|render_player_model\|legacyHidden\|legacyAlias\|LegacyFlags` → 0；夹具旧键 0；抽查 10 支同层同值 |
| 8 | 零散死代码 + 旧注释 + `UsageIndex`/`usageBuilder` + imports + `math/path` 死类/死方法 | 任务书检索矩阵 → 0；`:common:compileJava` |
| 9 | 文档同步：`docs/SCRIPT_FORMAT.md`、`docs/modules/*`、`plans/0.3.6/**` 现行正文；旧字段只留「已移除」表 | `facing_origin\|hud_layers\|hard_hide_hud\|legacyHidden\|旧插值器` 仅命中已移除表/历史 |
| 10 | 补充旧代码面：删 `meta.version`/`hud_layers`/`hard_hide_hud`/`exit_on_duration_overrun`/`total_duration` 显式拒绝（**改为白名单报错**）、`hasObjects()` 改名、补充死代码、旧注释 | 追加 grep → 0；`bad_removed_*` 5 支仍失败（白名单文案） |
| 11 | Fabric allowlist 接管：能力位、`FabricGuiTakeoverMixin`、自定义层表、暂停菜单配置入口 | `:fabric:compileJava`；实机 §六 1–9；Forge 不变 |

## 二、依赖顺序（波次）

1. **波次 A（并行，文件集互斥）**：T1 位置朝向；T2 template+命令；T3 规则协议；T4 旧存档；T5 schema/导出。
2. **波次 B（并行）**：T6 `Clip`+`ScriptParser`(片段/白名单)+`ScriptValidator`；T7 画面层**迁移脚本+夹具**（只改 JSON，主代理执行）；T8 零散死代码（避开 T6/T9 文件）。
3. **波次 C**：T9 画面层代码删除（`MetaSchemas`/`ScriptMeta`/`ScreenLayer`/`CinematicController`/`PlaybackInstance`/`GuiMixin`/`HudLayerRegistry`/`ScriptParser.parseMeta`）+ T10 文档同步。
4. **波次 D**：T11 Fabric allowlist 接管（在 T9 后，避免抢 `CinematicController`）。
5. 主代理：编译 → 探针 → 全量 grep → 提交。

## 三、裁决记录（任务书未写或与源码冲突，本轮按此执行）

| # | 事项 | 裁决 | 理由 |
|---|---|---|---|
| R1 | 任务书要求新建 `PointSpace`，但 `script/object/position/PositionSpace` 已存在（WORLD/BASE），被 5 个文件使用 | **不新建 `PointSpace`**；`PointOffset.space` 用现有 `PositionSpace`；删 `PointSource.Space` | 单一入口，避免两个等价枚举（项目原则：无重复判定） |
| R2 | `PointKinds` 与 `PointKind` 两套词汇 | `PointKinds` 保留旧 **int** 词汇（`PLAYER=0`/`COORDINATE=1`/`STRUCTURE=2`/`BLOCK=3`/`SELECTOR=4`/`NO_KIND=-1`/`DEFAULT_BLOCK_RADIUS=16`）+ `kindOf(String)` + `parseBlock(String)→BlockRef`；`PointKind` 枚举新增 `fromOrigin(String)`，内部只调 `PointKinds.kindOf` | `ObjectValidator` 依赖 int 常量；枚举判据必须单一 |
| R3 | 任务书要求删 `meta.version` 等显式拒绝、改由「字段白名单/未知键报错」，但当前 `parseMeta` **没有** meta 未知键白名单 | **本轮新建**：`ScriptParser`/`ScriptValidator` 对 meta 键做白名单校验，白名单 = `SchemaRegistry.getMetaFields().keySet()` ∪ `{triggers, trigger_poll_interval}`；timeline 白名单 = `{tracks, loop_start, loop_end}` | 否则删掉显式拒绝会变成静默忽略旧字段，违反 `bad_removed_*` 仍须失败 |
| R4 | 迁移脚本写入 `screen_layers` 会让 `resolveScreenLayerMode` 隐含 `ALLOWLIST`（行为漂移） | 迁移脚本必须同时写 `screen_layer_mode: normal`；仅旧 `hard_hide_hud: true` → `allowlist` | 等价迁移；allowlist 是平台能力补齐，不能顺手给全部脚本切换模式 |
| R5 | `Clip.hasObjects()` 语义 | 改名 `hasObjectProperties()`，语义 = 「有对象属性」；`ObjectCameraEvaluator.isClipUsable`/`PathCollectionParser`/`ScriptPlayer` 同步 | 任务书交付物 10：不再把空 `objects` 当旧格式 |
| R6 | `TrackTypeSchema` 收敛 | 只留 `(Map<String,ObjectDef> objects, Map<String,FieldDef> playControl)`；`TrackRegistry:117` 与 `EMPTY` 同步 | 交付物 3 |
| R7 | 六个未跟踪文档（四份 misc 设计文档 + 两份 K-optimization） | **不删除、不提交**；本轮只消费其清单，清理由用户手动处理 | 用户指示：不要删东西 |
| R8 | Fabric 实机验收 | 若实机不可用，报告明确写「未验证」，不冒充通过 | 不编造验证 |
| R9 | `old-new-shared-code.md` §七 要求 `CameraLane` 改为 `(state, sample, composite)`，但 `CameraLeasePool.acquire(owner, Clip, dimension)` 与 `CameraTrackPlayer.indexOfClip(Clip)` 都需要 `Clip` 身份 | **保留 `clip`**，删 `clipLocalTime`，新增 `sample`（`SampledClip`）；`ScriptPlayer` 用 `lane.sample()` 不再重采样 | 文档方案漏了这两个消费点；删 `clip` 会破坏租约对账 |
| R10 | 第六份文档 `K-optimization/code-quality-review-2026-11-06.md` 是活跃代码的结构/性能审查（巨型文件、`script↔camera` 耦合、每帧分配、WebUI 帧流），不是旧模型残留 | **本轮不施工**，整表移交 K 轮，写入 `I-BACKLOG.md` §N-2（含 P0–P3 与关闭条件） | 任务书「不做」排除改播放/相机语义；结构重构不属「旧代码清零」 |
| R11 | 画面层是否保留 `normal` / `allowlist` 双模式 | **用户裁决（2026-11-08）：只留一个模式**——强制清除整帧 + 白名单放行，默认生效，删 `meta.screen_layer_mode` 字段、`ScreenLayerMode` 枚举、平台能力位、`normal` 逐层抑制整条路径（`GuiMixin` / 4 个 HUD 组件 mixin / Forge `RenderGuiOverlayEvent.Pre` / Fabric TAIL） | 「屏蔽只留效果最好的那个模式」；双模式是无用的旧逻辑 |
| R12 | 第三方模组验证 | **不验证**：只提供 `FabricHudLayerRegistry.register` / `HudLayerRegistry.register` 接口，由第三方自行适配 | 用户指示 |

## 四、缺口分类

- **本轮做**：上述 11 项全部；`E:/tmp/icv` 与 `E:/tmp/i5/probe/I5Probe.java` 的旧断言同步改写（探针不入库）。
- **移交后续轮次**：无（0.3.6 收口轮，Z 轮前不留清理项）。
- **契约边界**：
  - 0.3.6 不再读旧脚本 / 旧 v1 存档（旧事实转换只提供 `E:/tmp` 一次性工具，不入库）。
  - `FabricHudLayerRegistry.register` 是 Fabric 平台专属接口；跨平台第三方用 `HudLayerRegistry`。
  - `parseBlock` 接受带或不带 `block:` 前缀（统一 `OrientationEvaluator`/`ObjectCameraEvaluator` 两处旧行为）。

## 五、前置轮次边界核对

- I-5 已关单：`screen_layers` / `screen_layer_mode` / `LegacyFlags` 回落是 I-5 产物，本轮只删不重建。
- 旧 J-1 收口（`59a5fb4`）已完成单入口/失败处理；本轮不重做那些项。
- `fabric-hud-takeover.md` 是方向稿（§八「未实现」），本轮实现其 §四/§六；其中「双模式」部分被 R11 裁决覆盖。

## 六、验收记录（2026-11-08 实测）

| 验收项 | 命令 / 方式 | 结果 |
|---|---|---|
| 编译 | `sh gradlew :common:compileJava :forge:compileJava :fabric:compileJava --offline --no-daemon` | BUILD SUCCESSFUL（仅既有 MC API deprecation 警告） |
| 检索矩阵（任务书全部 grep，含 R11 追加的 `ScreenLayerMode\|screen_layer_mode\|isAllowlistMode\|supportsScreenLayerTakeover`、5 个已删 mixin 名） | grep common/fabric/forge | 全部 0 命中 |
| `I5Probe` | 无头探针（classpath 按 `E:/tmp/icv/run.sh` 模板） | **24/24 PASS**；260 支夹具 252 正常 + 8 负例全部按预期拒绝 |
| `E:/tmp/icv` 全量基线 | `sh run.sh <cinematics>` | 7/260 FAIL，全部为 `tests/faults/bad_*` 负例（基线口径：正常脚本 0 失败；demo / mod_showcase 从基线 FAIL 变 OK） |
| `bad_removed_*` 负例 | 同上 | 5 支全部失败（文案 = 白名单「未知字段」口径） |
| 夹具旧键 | grep cinematics | `hide_*` / `suppress_*` / `render_player_model` / `hud_layers` / `hard_hide_hud` / `screen_layer_mode` 仅存在于 `tests/faults/**` 负例 |
| Fabric 实机（allowlist 白名单） | rig（Sodium 0.5.13 在场）+ `icinematics play` + 游戏内截图 | hotbar / 跳过提示 / 玩家模型 / 本模组字幕显示；准星 / 血条 / 经验 / Boss 条 / 聊天 / 记分板 / 动作栏 / 标题 / 字幕隐藏；世界内容照常 |
| Fabric 实机（F1） | 播放中按 F1 + 截图 | HUD 层全部不画；世界效果层照常（Fabric 遵循原版 `!hideGui` 块，比 Forge 更严格，见 fabric-hud-takeover §5.2 有意偏差） |
| Fabric 实机（退出恢复） | `icinematics stop` + 截图 | 准星 / hotbar / 手臂全部恢复，无残留清空 |
| Fabric 实机（单层隐藏） | `screen_layers` 只写 `hud.chat:false` + 截图 | 接管触发，chat 隐藏、其余层放行（等价旧 normal 逐层抑制的视觉） |
| Fabric 启动 | 服务端 + 客户端日志 | 无 mixin / `@Shadow` 报错；修复了 `FabricEvents` 引用 client-only 类导致专用服务器启动崩溃的回归（client 事件移入 `FabricClientEvents`） |
| 第三方 HUD（`HudRenderCallback` 全灭 / `FabricHudLayerRegistry.register` 层显示） | — | **未验证**（无第三方测试模组；按用户指示不构造，接口已提供） |

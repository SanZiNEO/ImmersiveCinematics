# HUD 层注册 API（H11）

> 状态：已实现（H-2，2026-11-03）
> 范围：面向第三方模组/整合包的 HUD 层注册入口、`hud_layers` 与固定分类的优先级、Fabric/Forge 平台边界、验收
> 关联：[`screen-layers.md`](screen-layers.md)（画面层统一语义）、[`../G-script-model/editor-playback.md`](../G-script-model/editor-playback.md)（编辑器播放的行为并集）、`forge/.../ForgeHudLayerRegistry.java`、`common/.../control/CinematicController.java`

> **后续（J-1）**：Fabric 侧已实现整帧接管 + 自定义层表；`hud_layers` / `hard_hide_hud` / `screen_layer_mode` 旧字段已删除，画面层现为唯一模式（强制清除 + 白名单放行），现行语义见 [`screen-layers.md`](screen-layers.md)。下文 §二 / §三 保留 H11 当时的记录，最终形态以 §八 为准。

## 一、摘要

G 轮完成后，`hud_layers` 的数据层（并集、回落）与消费层（整帧接管 + 按白名单重画）都已实现；但第三方只有两个“内部口子”：

1. `ForgeHudLayerRegistry.register(overlayId, category)`——public 但属内部实现类，第三方要调用必须依赖本模组 jar，且没有文档/稳定契约；
2. 直接把 Forge overlay 的 `ResourceLocation` 字符串写进 `hud_layers`（未注册分类时按 id 匹配）。

本任务把口子做成正式 API：一个与加载器无关的注册门面，Forge 实现转调现有注册表，Fabric 明确边界；同时把 `hud_layers` 的语义与固定分类优先级写进对外文档。

## 二、现状（G 轮结束时的代码事实）

| 项 | 现状 |
|---|---|
| `hud_layers` 语义 | `{层名: true=隐藏 / false=显示}`；未声明回落 `hide_hud` |
| 固定分类 | `hotbar` / `crosshair` / `chat` / `scoreboard` / `bossbar` / `subtitles` / `action_bar` / `title` / `arm` / `skip_hud` / `bob` 只读各自 `hide_*`，`hud_layers` 不覆盖 |
| 未分类层 | `isLayerHidden` 的 default 分支查 `hudLayers`；Forge overlay 未注册分类时按 `overlayId.toString()` 匹配 |
| 接管消费 | `RenderGuiEvent.Pre` 取消整帧 + 按 `isLayerHidden` 白名单重画 |
| 第三方注册 | `ForgeHudLayerRegistry.register` public，但无 API 包、无文档、无服务加载器 |
| Fabric | 整帧接管（`FabricGuiTakeoverMixin` + `FabricHudTakeover`）；自定义层表登记参与重画的层 |

## 三、目标

1. **正式注册入口**：在 `common` 的 `api` 包新增 `HudLayerRegistry` 门面：
   - `register(ResourceLocation overlayId, String category)`：把某个 overlay 归入分类（脚本可用 `hide_*` / `hud_layers` 控制）；
   - `register(ResourceLocation overlayId)`：等价于分类 = `overlayId.toString()`（只用 `hud_layers` 控制）。
2. **平台实现**：
   - Forge：门面转调 `ForgeHudLayerRegistry.register`，接管重画消费链复用同一分类解析；
   - Fabric：门面保留；平台侧已实现整帧接管（`FabricGuiTakeoverMixin` + `FabricHudTakeover`）与自定义层表（`FabricHudLayerRegistry.register(layerId, category, renderer)` + `renderCustom`），注册的层在接管重画时参与白名单。
3. **文档**：对外写清 `hud_layers` 语义、固定分类优先级、未走 Forge overlay 的 HUD 无口子。
4. **验收**：
   - 第三方 mod 调 `HudLayerRegistry.register("modid:overlay", "chat")` 后，脚本 `hud_layers: {"chat": true}` / `hide_chat` 对该 overlay 生效；
   - 不注册、直接写 `hud_layers: {"modid:overlay": true}` 同样生效（隐式口子保留）；
   - 接管时未注册 overlay 不在白名单 → 被砍掉且不重画；注册后按分类决定是否重画；
   - Fabric 端不崩、注册不生效时日志明确。

## 四、非目标

- 不接管不走 Forge overlay 注册表、也不在 `RenderGuiEvent.Pre` 自绘的第三方 HUD（没有稳定挂点）。
- 不在 Fabric 复刻 Forge 的 `RenderGuiEvent` 钩子形态；Fabric 侧改为 mixin 取消整帧 + 自定义层表重画（J-1 已实现）。
- 不把 `hud_layers` 扩展成白名单 DSL；它仍是“层名 → 隐藏/显示”的覆盖表。

## 五、为什么不选别的

| 备选 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 只保留 `ForgeHudLayerRegistry.register` | 第三方直接依赖内部类 | 无稳定契约；重构即断 | 门面薄、迁移成本低 |
| 服务加载器自动发现 | 第三方实现 `ServiceLoader` 接口 | 增加一次加载期扫描；接口版本化复杂 | 直接静态注册更简单 |
| 把固定分类也交给 `hud_layers` | 统一一张表 | 改变既有脚本语义（`hide_hotbar` 失效） | 破坏兼容；固定分类字段是作者面契约 |
| Fabric 用 mixin 模拟逐层抑制 | 逐 HUD 方法 cancel | 第三方自绘仍拦不住；维护面大 | 收益低，改为整帧接管 + 自定义层表 |

**本方案的缺点**：

- 门面只是薄转发，真正能力仍取决于平台是否有 overlay 消费点；Fabric 端注册可能“登记了但没人读”。
- 白名单只覆盖注册表 overlay 与注册进自定义层表的层，第三方 Pre 自绘 HUD 只能被整帧清除，不能参与更细的层控制。

## 六、验收清单

| # | 用例 | 期望 |
|---|---|---|
| 1 | Forge：第三方注册 `modid:overlay → "chat"` | `hide_chat=true` 时该 overlay 在接管重画中被跳过；`hud_layers.chat=false` 时显示 |
| 2 | Forge：不注册，直接 `hud_layers: {"modid:overlay": true}` | 该 overlay 被隐藏（default 分支按 id 匹配） |
| 3 | Forge：接管 + 未注册 overlay | overlay 被砍；不重画 |
| 4 | Forge：接管 + 注册到 `chat` 且 `hide_chat=false` | overlay 被重画 |
| 5 | Fabric：调用 `HudLayerRegistry.register` | 不崩；日志说明 Fabric 边界；注册进自定义层表的层参与接管重画 |
| 6 | 既有脚本 | `hide_*` / `hud_layers` 语义与 G 轮一致，无回归 |

## 七、实施顺序

1. `api/HudLayerRegistry` + 平台适配（Forge 转调，Fabric 记录 + WARN）；
2. `ForgeHudLayerRegistry.register` 注释标为内部实现，门面为唯一对外入口；
3. 文档：`editor-playback.md` §3.2 已写 `hud_layers` 语义；补 `screen-layers.md` 的 API 段落；
4. 实机：BMC4 / Fabric 各一条注册 + 两条脚本用例；
5. 验收回写。

## 八、实现结果

| 项 | 结果 | 落点 |
|---|---|---|
| 门面 | 已实现 | `common/.../api/HudLayerRegistry.java`：`final` + 私有构造；`Platform.onRegistered` 回调；唯一 `ConcurrentHashMap<ResourceLocation, String>`；`install` / `register(id, category)` / `register(id)` / `categoryOf`。null 参数忽略整次调用，未注册返回 `null`，分类为空串按未指定处理 |
| Forge 转调 | 已实现 | `ForgeHudLayerRegistry.register(id, category)` 转调门面（javadoc 标注内部实现）；`categoryOf` = 内置原版表 → 门面表 → `overlayId.toString()`；静态块 `reg(...)` 仍写内置表，未改 |
| Fabric 边界 | 已实现（J-1 补齐） | `FabricHudLayerRegistry.register(String, String, Renderer)` 三参注册自定义层（`renderer` 收 `GuiGraphics` + `partialTick`）；`categoryOf` = 自有表 → 门面表（`ResourceLocation.tryParse`）→ 原始层 ID；`FabricHudVisibility.isHidden` 判显隐，`FabricHudTakeover` 整帧取消后按白名单重画 |
| 平台安装 | 已实现 | Forge 不安装回调（消费链直接读表，注册写表即生效）；Fabric `FabricEvents.registerClient()` 安装边界日志回调，`AtomicBoolean` 保证只打一次 |
| 文档 | 已实现 | `docs/SCRIPT_FORMAT.md` 新增「1b-1. HUD 层控制：`hud_layers` 与 `hard_hide_hud`」并改写 `hud_layers` / `hard_hide_hud` 两行表格；`screen-layers.md` 新增 §3.6、更新 §九 |
| 验收 | 待主代理统一执行 | `HudLayerProbe`：注册 → 分类、单参注册 → 资源 ID、未注册 → `null`、null 参数不抛；Forge 实机：接管时未注册 overlay 被砍、注册后可重画 |

相对 §三 目标的口径调整：

- **转发方向反过来**：门面是唯一第三方注册表，`ForgeHudLayerRegistry` 转调门面，而不是门面转调 Forge 表——否则会出现两张第三方表。
- **Fabric 首次注册用 `INFO` 而非 `WARN`**：注册只写表不触发绘制，属正常路径。
- **未做 `ServiceLoader` 自动发现**：§四 已列为非目标。

未覆盖（沿用 §四 非目标）：不走 overlay 管线、也不注册进自定义层表的第三方 HUD（接管时随整帧取消而不重画）、`hud_layers` 的白名单 DSL。Fabric 的整帧接管已在 J-1 补齐（`FabricGuiTakeoverMixin` + `FabricHudTakeover` + 三参 `register`）。

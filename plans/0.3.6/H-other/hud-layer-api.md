# HUD 层注册 API（H11）

> 状态：设计稿（未实现）
> 范围：面向第三方模组/整合包的 HUD 层注册入口、`hud_layers` 与固定分类的优先级、Fabric/Forge 平台边界、验收
> 关联：[`screen-layers.md`](screen-layers.md)（画面层统一语义）、[`../G-script-model/editor-playback.md`](../G-script-model/editor-playback.md)（编辑器播放的行为并集）、`forge/.../ForgeHudLayerRegistry.java`、`common/.../control/CinematicController.java`

## 一、摘要

G 轮完成后，`hud_layers` 的数据层（并集、回落）与消费层（normal 的 overlay 过滤、`hard_hide_hud` 的白名单重画）都已实现；但第三方只有两个“内部口子”：

1. `ForgeHudLayerRegistry.register(overlayId, category)`——public 但属内部实现类，第三方要调用必须依赖本模组 jar，且没有文档/稳定契约；
2. 直接把 Forge overlay 的 `ResourceLocation` 字符串写进 `hud_layers`（未注册分类时按 id 匹配）。

本任务把口子做成正式 API：一个与加载器无关的注册门面，Forge 实现转调现有注册表，Fabric 明确边界；同时把 `hud_layers` 的语义与固定分类优先级写进对外文档。

## 二、现状（G 轮结束时的代码事实）

| 项 | 现状 |
|---|---|
| `hud_layers` 语义 | `{层名: true=隐藏 / false=显示}`；未声明回落 `hide_hud` |
| 固定分类 | `hotbar` / `crosshair` / `chat` / `scoreboard` / `bossbar` / `subtitles` / `action_bar` / `title` / `arm` / `skip_hud` / `bob` 只读各自 `hide_*`，`hud_layers` 不覆盖 |
| 未分类层 | `isLayerHidden` 的 default 分支查 `hudLayers`；Forge overlay 未注册分类时按 `overlayId.toString()` 匹配 |
| normal 消费 | `ForgeClientEvents.onRenderGuiOverlayPre` 按 `categoryOf` 过滤注册表 overlay |
| hard 消费 | `RenderGuiEvent.Pre` 取消 + `renderHardHide` 按 `isLayerHidden` 白名单重画（仅 Forge） |
| 第三方注册 | `ForgeHudLayerRegistry.register` public，但无 API 包、无文档、无服务加载器 |
| Fabric | 无 hard 模式；固定分类走 `GuiMixin`；未分类层没有 overlay 注册表可挂 |

## 三、目标

1. **正式注册入口**：在 `common` 的 `api` 包新增 `HudLayerRegistry` 门面：
   - `register(ResourceLocation overlayId, String category)`：把某个 overlay 归入分类（脚本可用 `hide_*` / `hud_layers` 控制）；
   - `register(ResourceLocation overlayId)`：等价于分类 = `overlayId.toString()`（只用 `hud_layers` 控制）。
2. **平台实现**：
   - Forge：门面转调 `ForgeHudLayerRegistry.register`，normal/hard 两条消费链原样复用；
   - Fabric：门面保留但明确“仅普通层过滤、无 hard 重画”，注册表只服务 `isLayerHidden`（若 Fabric 侧没有 overlay 消费点，注册只作记录并打一次 WARN）。
3. **文档**：对外写清 `hud_layers` 语义、固定分类优先级、`hard_hide_hud` 仅 Forge、未走 Forge overlay 的 HUD 无口子。
4. **验收**：
   - 第三方 mod 调 `HudLayerRegistry.register("modid:overlay", "chat")` 后，脚本 `hud_layers: {"chat": true}` / `hide_chat` 对该 overlay 生效；
   - 不注册、直接写 `hud_layers: {"modid:overlay": true}` 同样生效（隐式口子保留）；
   - hard 模式下未注册 overlay 不在白名单 → 被砍掉且不重画；注册后按分类决定是否重画；
   - Fabric 端不崩、注册不生效时日志明确。

## 四、非目标

- 不接管不走 Forge overlay 注册表、也不在 `RenderGuiEvent.Pre` 自绘的第三方 HUD（没有稳定挂点）。
- 不在 Fabric 实现 `hard_hide_hud` 的等价能力（缺 Forge 的 `RenderGuiEvent` 钩子；需要单独设计）。
- 不把 `hud_layers` 扩展成白名单 DSL；它仍是“层名 → 隐藏/显示”的覆盖表。

## 五、为什么不选别的

| 备选 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 只保留 `ForgeHudLayerRegistry.register` | 第三方直接依赖内部类 | 无稳定契约；重构即断 | 门面薄、迁移成本低 |
| 服务加载器自动发现 | 第三方实现 `ServiceLoader` 接口 | 增加一次加载期扫描；接口版本化复杂 | 直接静态注册更简单 |
| 把固定分类也交给 `hud_layers` | 统一一张表 | 改变既有脚本语义（`hide_hotbar` 失效） | 破坏兼容；固定分类字段是作者面契约 |
| Fabric 用 mixin 模拟 hard | 逐 HUD 方法 cancel | 第三方自绘仍拦不住；维护面大 | 收益低，标注边界更诚实 |

**本方案的缺点**：

- 门面只是薄转发，真正能力仍取决于平台是否有 overlay 消费点；Fabric 端注册可能“登记了但没人读”。
- `hard_hide_hud` 的白名单只覆盖注册表 overlay，第三方 Pre 自绘 HUD 注册后也只能被砍/重画，不能参与更细的层控制。

## 六、验收清单

| # | 用例 | 期望 |
|---|---|---|
| 1 | Forge：第三方注册 `modid:overlay → "chat"` | `hide_chat=true` 时该 overlay 被 normal/hard 隐藏；`hud_layers.chat=false` 时显示 |
| 2 | Forge：不注册，直接 `hud_layers: {"modid:overlay": true}` | 该 overlay 被隐藏（default 分支按 id 匹配） |
| 3 | Forge：`hard_hide_hud=true` + 未注册 overlay | overlay 被砍；不重画 |
| 4 | Forge：`hard_hide_hud=true` + 注册到 `chat` 且 `hide_chat=false` | overlay 被重画 |
| 5 | Fabric：调用 `HudLayerRegistry.register` | 不崩；日志说明 Fabric 无 hard/overlay 消费点；固定分类行为不变 |
| 6 | 既有脚本 | `hide_*` / `hud_layers` 语义与 G 轮一致，无回归 |

## 七、实施顺序

1. `api/HudLayerRegistry` + 平台适配（Forge 转调，Fabric 记录 + WARN）；
2. `ForgeHudLayerRegistry.register` 注释标为内部实现，门面为唯一对外入口；
3. 文档：`editor-playback.md` §3.2 已写 `hud_layers` 语义；补 `screen-layers.md` 的 API 段落；
4. 实机：BMC4 / Fabric 各一条注册 + 两条脚本用例；
5. 验收回写。

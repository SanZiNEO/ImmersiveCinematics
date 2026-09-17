# 0.3.6 HUD 强硬隐藏模式：Pre 拦截 + 白名单重画（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、公式、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [脚本格式](../../docs/SCRIPT_FORMAT.md)
> - [Overlay 颜色遮罩](./overlay-color-mask.md)
> - [相机状态与覆盖链](./camera-state-plan.md)

---

## 1. 定位（已确认）

**目标**：让“不受现有白名单控制”的第三方 HUD 也能被隐藏——特指**不走 Forge overlay 注册表、直接在 `RenderGuiEvent.Pre` 里自绘**的 HUD。

实例：鬼灭之刃（`kimetsunoyaiba`）的 `OverlayCooldownTimeOverlay`——`@EventBusSubscriber(Dist.CLIENT)` + `@SubscribeEvent RenderGuiEvent.Pre`，在事件里自己 `blit` 图标 + `drawString` 画冷却格子。

**形态**：一个**可选的、更强硬的**隐藏模式。默认不开启，保持现有行为不变。

**不是**：替换现有白名单；也不是“按订阅者白名单”（见 §2，那条路不成立）。

---

## 2. 现状与缺口

现有三条路（都在用）：

| 路 | 机制 | 覆盖对象 |
|---|---|---|
| 原版 HUD | `GuiMixin` 逐方法 `cancel` | 快捷栏/准心/血条/经验/计分板/聊天… |
| 注册表 overlay | `RenderGuiOverlayEvent.Pre` + `ForgeHudLayerRegistry` 分类 + `hud_layers` 覆盖 | 原版 overlay + 任何 `RegisterGuiOverlaysEvent` 注册的第三方 overlay |
| 主动查询（Fabric） | `FabricHudVisibility.isHidden(layerId)` | 在 Fabric 上自绘、且愿意调用我们 API 的模组 |

**缺口**：`RenderGuiEvent.Pre` 的订阅者不在注册表里、也没有 ID，上面三条都碰不到。

**为什么不能“按订阅者做白名单”（已核实，不靠反射做不到）**：

- `IEventBus`（eventbus 6.0.5）没有枚举监听器的 API：只有 `register` / `addListener` / `addGenericListener` / `unregister(Object)` / `post(Event)` / `post(Event, IEventBusInvokeDispatcher)` / `shutdown` / `start`。
- 唯一的逐监听器钩子是 `post(event, dispatcher)`，但回调拿到的 `IEventListener` 公开面只有 `invoke()` 和 `listenerName()`；`ASMEventHandler`（`@SubscribeEvent` 用的包装类）**没有覆盖 `listenerName()`**，返回的是包装类名，不含目标类/模组。带名字的 `NamedEventListener` 只在 `-Deventbus.namelisteners=true` 时启用，且只包装 lambda 监听器。
- 即便接管分发，Pre 阶段**画出来的东西没有身份**（注册表 overlay 有 `ResourceLocation`，Pre 订阅者什么都没有）——白名单没有键可用。
- 靠反射（读监听器内部字段）或 `toString()` 猜身份都属同一类脆弱做法，**明确不做**（与 `ImmersiveCinematicsMixinPlugin` 的“不使用 Java 反射”一致）。

**结论**：Pre 阶段只有三种状态——全放（现状）/ 全砍 / 给单个模组手写身份（针对性 mixin）。本文档做**全砍 + 按现有白名单重画**这条通用路。

---

## 3. 方案：强硬模式（方向）

Forge 1.20.1 的 GUI 管线（`ForgeGui.render`，已核实）：

```java
if (!MinecraftForge.EVENT_BUS.post(new RenderGuiEvent.Pre(window, guiGraphics, partialTick))) {  // @Cancelable
    GuiOverlayManager.getOverlays().forEach(entry -> {           // 原版 HUD + 所有注册 overlay
        if (this.pre(entry, guiGraphics)) return;                // 转发 RenderGuiOverlayEvent.Pre
        entry.overlay().render(this, guiGraphics, partialTick, w, h);
        this.post(entry, guiGraphics);                           // 转发 RenderGuiOverlayEvent.Post
    });
    MinecraftForge.EVENT_BUS.post(new RenderGuiEvent.Post(...)); // ← 我们自己的 overlay 画在这里
}
```

**强硬模式 = IC 用最高优先级订阅 `RenderGuiEvent.Pre`，接管整段渲染**：

1. 判定“该隐藏”（`hide_hud` 生效）→ `event.setCanceled(true)`
   → Pre 的其他订阅者（鬼灭那种自绘 HUD）、原版 HUD、全部注册 overlay、以及 `Post`（含其他模组在 Post 里的自绘）**全部跳过**；
2. 复位 `ForgeGui.leftHeight / rightHeight`（两者是**公开字段**）；
3. 遍历 `GuiOverlayManager.getOverlays()`（**公开 API**，`NamedGuiOverlay.id()` / `.overlay()`），对**白名单判定为“显示”**的层自己调用 `overlay.render(gui, graphics, partialTick, w, h)`；
4. 画我们自己的 overlay（黑边 / 字幕 / GIF / 跳过提示）——原本在 `Post`，此处必须自己补画。

> 关键点：白名单判定**完全复用现有机制**（`CinematicController.isLayerHidden(category)`、`hud_layers`、各单项 `hide_*` 开关），只是把“谁来画”从 Forge 换成我们。

---

## 4. 与现有机制的关系

- **白名单语义不变**：`ForgeHudLayerRegistry.categoryOf(overlayId)` 继续给每个 overlay 定类；未注册的层仍以自身 ID 为层名。
- **`GuiMixin` 保留**：normal 模式继续用它逐元素取消；hard 模式下它仍然一致（白名单判为隐藏的原版元素本来也不会被重画）。
- **我们自己的 overlay**：绘制点按模式分流——normal 走 `RenderGuiEvent.Post`（现状），hard 在接管处第 3 步之后自己画。
- **Fabric 侧**：无 overlay 注册表、无 `RenderGuiEvent`，hard 模式**没有对应语义**（只能“我们少画”，拦不到别人自绘）。执行时明确：Fabric 上此模式要么不做，要么退化为 normal。

---

## 5. 模式开关（方向）

- **不强制开启**：默认 `normal`，行为与现在完全一致。
- 候选形态（执行时定）：脚本 meta 字段（三态，缺省跟随全局）+ 全局配置默认值；与现有 `hide_arm` / `suppress_bob` 的三态回落风格一致。
- 粒度：整段播放级（不做逐帧切换）。
- 是否允许“normal 但单独给某个模组上 mixin 兜底”，见 §7。

---

## 6. 改动面（预估）

| 文件 | 改动 |
|---|---|
| `forge/.../forge/ForgeClientEvents.java` | 新增最高优先级 `RenderGuiEvent.Pre` 处理器（hard 模式接管）；`onRenderGui(Post)` 仅在 normal 模式生效 |
| `common/.../control/CinematicController.java` | 模式字段 + 查询接口 |
| `common/.../script/ScriptMeta.java`、`ScriptParser.java`、`schema/MetaSchemas.java` | 模式字段（若走脚本级） |
| `forge/.../hud/ForgeHudLayerRegistry.java` | 无改动（复用） |
| `docs/SCRIPT_FORMAT.md`、`docs/modules/control.md`、`docs/modules/mixin.md` | 文档 |

---

## 7. 风险与可能的问题

- **其他模组在 `RenderGuiEvent.Pre` 里的非绘制副作用会被一并跳过**（状态准备、缓存刷新等）——hard 模式的固有代价，需要接受并在文档里写明。
- **重画原版 overlay 的记账**：`leftHeight/rightHeight` 是公开字段可复位；overlay 内部调用的是 `ForgeGui` 实例方法（实例仍是 `ForgeGui`），但要确认每个原版 overlay 在“非 ForgeGui 循环内”调用时行为一致。
- **`RenderGuiOverlayEvent.Pre/Post` 是否补发**：构造器公开但标注 `@Internal`。补发更忠实（其他模组的取消/改写逻辑照常生效），不补发则更少依赖内部标注——取舍待定。
- **渲染顺序**：重画的层与我们自己的 overlay 的叠放顺序（原本注册表 overlay 在 Post 之前）要逐项对齐。
- **编辑器预览 / 暂停 / 退场动画**：预览模式下 GUI 多被编辑器屏幕替代，需确认无副作用。
- **兼容**：Sodium / Embeddium 不接管 GUI 管线，风险低，执行时确认。
- **兜底**：hard 模式也拦不住的（例如 mixin 进 `Gui.render` 直接画的模组）——是否保留“针对性 mixin”作为最后手段。

---

## 8. 待定

- 模式字段名与归属（脚本级 / 全局 / 两者结合）
- 是否补发 `RenderGuiOverlayEvent`
- 是否顺带规范 `hud_layers` 的键（现在是“分类名 + 未注册 overlay 完整 ID”混用）
- Fabric 是否做等价能力（或明确不做）
- 是否保留针对性 mixin 兜底
- hard 模式与 `hide_skip_hud`、letterbox、字幕层的叠放细节

---

## 9. 验收标准

- [ ] 默认（normal）行为与现状完全一致（回归无变化）
- [ ] hard 模式下：`RenderGuiEvent.Pre` 自绘的第三方 HUD（如鬼灭冷却条）被隐藏
- [ ] hard 模式下：`hud_layers` 判为“显示”的层（如 BetterEvE 格斗血条）仍然正常显示
- [ ] hard 模式下：我们自己的 overlay（黑边 / 字幕 / GIF / 跳过提示）正常显示
- [ ] 切换模式不需要重启游戏

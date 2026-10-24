# HUD 强硬隐藏模式

> 状态：已实现（仅 Forge）
> 范围：Forge 客户端的整帧 HUD 接管与白名单重画；脚本级模式开关。
> 关联：`docs/SCRIPT_FORMAT.md`、`docs/modules/control.md`、`docs/modules/mixin.md`

## 一、摘要

一种可选的、更强硬的 HUD 隐藏模式：脚本声明后，客户端接管整帧 HUD 渲染，把不走 overlay 注册表、直接在 GUI 前置事件里自绘的第三方 HUD 一并隐藏，再按现有白名单把应当保留的层重画回来。默认不启用，普通模式行为不变。当前实现只在 Forge 上生效，真实客户端尚未实测。

## 二、为什么做

### 2.1 问题

现有隐藏路径有三条：原版 HUD 由逐元素 mixin 取消；注册表 overlay 由前置事件按分类拦截；Fabric 侧提供主动查询接口。三者都要求对象可枚举、有层 ID 或愿意调用接口。第三方直接在 GUI 前置事件里自绘的 HUD 不在注册表、没有 ID，三条路都碰不到——过场播放时这类 HUD 仍然留在画面上。

直接取消整个前置事件又会把白名单判为“显示”的层和本模组自己的覆盖层一起砍掉，变成全有或全无。

### 2.2 目标

- 未声明该模式的脚本，HUD 行为与之前完全一致。
- 声明后，前置事件里自绘的第三方 HUD 在播放期间不出现。
- 白名单判为“显示”的注册表 overlay 仍然显示。
- 本模组的黑边、字幕、图片/GIF、跳过提示仍然显示。
- 切换模式不需要重启。

### 2.3 非目标

- 不替换现有白名单与分类机制。
- 不做“按订阅者白名单”（不枚举监听器）。
- 不为单个模组写针对性 mixin。
- 不补发 overlay 事件。
- 不做 Fabric 等价能力。
- 不改变普通模式的绘制路径。
- 不处理通过 mixin 直接画进 GUI 的 HUD。

## 三、方案

脚本在元数据里声明一个可选的模式开关：不写或 false 是普通模式，true 是强硬模式。强硬模式只在该脚本确实要隐藏 HUD 时生效；脚本声明不隐藏 HUD 时不会接管，以免把作者明确要保留的第三方自绘 HUD 砍掉。

生效期间，客户端在整帧 HUD 渲染的最前端拦下原版流程：原版 HUD、注册表 overlay、以及后续订阅者的自绘内容都不再执行。随后按现有白名单逐层重画——判为隐藏的层跳过，其余层照常绘制；最后补画本模组的覆盖层。普通模式仍走原来的绘制点。两种模式互斥，每帧只画一次。

粒度是整段播放：任一活跃实例声明 true 即生效，退出后复位。Fabric 没有 overlay 注册表，也没有可取消的 HUD 回调，因此该模式只在 Forge 上生效；Fabric 上字段即使被解析，行为也等同普通模式。

## 四、技术细节

### 4.1 核心模型

- 模式开关：脚本运行时行为里的三态字段，缺省由全局配置给默认值。
- 控制器：汇总全部活跃实例的模式声明，任一显式 true 即生效；退出后复位。
- 接管器：Forge 侧最高优先级监听 GUI 前置事件，取消后按白名单重画。
- 层注册表：overlay 到分类的映射；未登记层以自身 ID 为层名；世界效果层单独豁免。

### 4.2 契约

- 模式值随脚本行为快照存在；实例增删时重算并集；无实例时复位。
- 接管器无状态，每帧读控制器与相机状态，不做缓存。
- 重画职责归接管器；普通模式的绘制职责归后置事件监听器；强硬模式生效时后者早退。
- 层注册表由模组或第三方注册，脚本只读分类结果，不写注册表。

### 4.3 数据形态

- 脚本字段 `hard_hide_hud`：boolean 或 null；null 表示未声明，取全局默认值。
- 全局配置键：强硬模式默认开关（boolean，缺省 false）。
- 并集语义：任一活跃实例显式 true 即生效；false 与 null 都视为不声明。
- 层键：已登记 overlay 用分类名；未登记 overlay 用完整资源 ID 作层名；世界效果层单独成集。
- 不持久化、不走网络包：客户端读已下发的脚本元数据与本地配置。

### 4.4 边界

- 仅 Forge；Fabric 没有 overlay 注册表，也没有可取消的 HUD 回调，退化为普通模式。
- 仅当隐藏 HUD 生效时接管；隐藏关闭时不接管。
- 取消只影响排在本监听器之后的订阅者；同优先级且注册更早的监听器仍会执行，已经画上去的内容不会被清除。
- 不补发 overlay 事件，依赖该事件的第三方逻辑在强硬模式下收不到事件。
- 世界效果层必须豁免，否则暗角、传送门、药水图标等会被误判为隐藏。
- 与普通模式的 mixin 隐藏并存：被判为隐藏的原版元素本来也不会被重画。
- 只覆盖 GUI overlay 管线；通过 mixin 直接绘制的内容不受影响。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 按订阅者白名单 | 枚举前置事件的监听器，按模组放行 | 事件总线没有枚举监听器的 API；监听器没有稳定身份 | 做不到，反射方案也脆弱 |
| 针对性 mixin | 每个第三方模组写一个注入 | 每加一个模组就要维护，版本或混淆变化即失效 | 不可扩展，只留作最后手段 |
| 全屏遮罩 | 在最上层画不透明层盖住 HUD | 连白名单层和本模组覆盖层一起盖住 | 与“保留白名单”目标冲突 |
| 替换白名单 | 把隐藏 HUD 直接变成全砍 | 丢失单项开关与层覆盖的粒度 | 破坏现有脚本语义 |
| 补发 overlay 事件 | 重画时补发前置/后置事件 | 依赖内部 API，逐帧双倍派发，第三方处理器参与接管 | 不选 |
| Fabric 等价实现 | 在 HUD 回调里做同样的事 | 回调不可取消、无 ID、无注册表 | 语义不存在 |

**本方案的缺点**：

- 前置事件订阅者的非绘制副作用（状态准备、缓存刷新）会被一并跳过。
- 声明接收取消事件的监听器完全收不到事件，依赖它的模组行为失效。
- 同优先级更早注册的监听器仍会绘制，可能残留。
- 通过 mixin 直接画的 HUD 不受影响。
- 仅 Forge，跨加载器行为不一致。
- 需要真实客户端与对应第三方模组才能验证效果。

## 六、实现结果

状态：已实现（2026-10-07，仅 Forge）。

模式开关、并集、Forge 接管与白名单重画均已在代码中；Fabric 无消费者；真实客户端未实测。本次重写只做静态核对，未运行构建或测试。

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 脚本级三态 + 全局配置默认值 | 仅脚本级三态；无全局配置键；null 直接视为普通模式 | 最简做法：缺省即普通模式，避免默认脚本凭空获得强硬行为 |
| 接管后复位左右高度为必需步骤 | 保留复位，但定位为防御性操作 | 原流程在事件之前已复位；更早的监听器可能改过公开字段 |
| 重画只按白名单判定 | 额外豁免世界效果层 | 否则暗角、传送门、药水图标等会被误判为隐藏 |
| 是否补发 overlay 事件（待定） | 不补发 | 构造器为内部 API，且隐藏效果已由重画过滤复现 |
| Fabric 做等价能力或退化为普通模式 | 不做；字段被解析但没有消费者 | Fabric 无可取消的 HUD 回调与 overlay 注册表 |
| 针对性 mixin 兜底（待定） | 不做 | 留作将来的最后手段 |
| `hud_layers` 键规范（待定） | 维持分类名与完整 ID 混用 | 不在本条范围 |
| 验收项需真实客户端实测 | 仅静态可核，未实测 | 需要图形环境与对应第三方模组 |

## 附录：重写前原文

### 0.3.6 HUD 强硬隐藏模式：Pre 拦截 + 白名单重画（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、公式、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [脚本格式](../../../docs/SCRIPT_FORMAT.md)
> - [Overlay 颜色遮罩](../D-transition/overlay-color-mask.md)
> - [相机状态与覆盖链](../../complete/0.3.6/B-camera/camera-state-plan.md)
>
> **状态：已实现（仅 Forge）。执行时定下的取舍见下方「落地标注」，§8 待定项已全部结清。**

---

#### 落地标注（2026-10-07 实现）

##### 模式开关（§5 / §8-1）

- 脚本级**三态**字段 `meta.hard_hide_hud`（`boolean` / `null`），与 `hide_*` 同款三态风格：`true` = hard，`null`（缺省）/ `false` = normal。
- **null 不回落 `hide_hud`**：该字段是"模式"而非"显隐"，缺省必须等于 normal，否则所有默认脚本凭空获得强硬行为（`CinematicController.effectiveHardHide`）。
- **不新增全局配置键**：F5 已核实 `ForgeConfig` / `FabricConfig` 没有任何 `hide_*` / `hud_layers` 键（"全局默认值"是新增项）——本版本最简做法 = 缺省即 normal；将来要加全局键，只需在 `effectiveHardHide` 里读配置。
- 并集：`CinematicController.recomputeUnion` 按**任一活跃实例显式 `true` 即生效**（OR，与 `plans/0.3.6/F-playback/parallel-playback.md` §3.2 同口径）；`revert()` / 无实例时复位为 null。
- 粒度：整段播放级（不做逐帧切换）。
- 改动面：`ScriptMeta.RuntimeBehavior.hardHideHud`（+ `Builder` + `DEFAULT`）、`ScriptParser`（`optNullableBool`）、`MetaSchemas`（`bool` 默认 null → WebUI 动态表单按三态渲染）、`CinematicController`（字段 + `isHardHideHud()`）。

##### 接管与重画（§3 / §4）

**接管条件**（`ForgeClientEvents.Forge.isHardHideActive`）：`CameraManager.isActive()` ∧ `isHardHideHud()` ∧ `isHideHud()`。
第三条必需：`hide_hud=false` 时无物可藏，若仍接管会把"白名单判为显示"的第三方自绘 HUD 一并砍掉，违背脚本作者的显式意图。

**`ForgeClientEvents.Forge.onRenderGuiPre`**（`@SubscribeEvent(priority = EventPriority.HIGHEST)`）：

1. `event.setCanceled(true)` → Forge 跳过原版 HUD、全部注册表 overlay、`RenderGuiEvent.Post`，以及**排在本监听器之后**的其他订阅者（C3：同为 HIGHEST 且注册更早的仍会执行，它们已经画上去的内容也不会被清除——取消只影响其后的订阅者）；
2. `renderHardHide`：复位 `gui.leftHeight / rightHeight = 39` → 遍历 `GuiOverlayManager.getOverlays()`，**`isWorldOverlay(id)` 豁免（F3）或 `!isLayerHidden(categoryOf(id))` 才重画**（逐层异常隔离，与 ForgeGui 同款）→ `RenderSystem.setShaderColor(1,1,1,1)`（对齐 ForgeGui 循环后的复位）→ `ClientEventHandler.onRenderHud`（黑边 / 字幕 / GIF / 跳过提示）；
3. 我们自己的 overlay **绘制点按模式分流**：normal 仍走 `RenderGuiEvent.Post`；hard 在接管处补画，且 `onRenderGui(Post)` 在 hard 生效时早退（Pre 被取消后 Forge 不会再发 Post，此早退只是把"每帧只画一次"写成显式不变式）。

**与 `ForgeGui.render` 的有意差异**：不设 `screenWidth / screenHeight`（ForgeGui 在 Pre **之前**已设）、不重新播种 `Gui.random`（protected，跨包不可访问 → 影响仅限食物图标抖动）、不刷新 `ForgeGui.font`（正常渲染路径早已赋值；hard 只在有活跃播放实例时接管，故非空）。

> **C4（本次补正）**：§3 把"复位 leftHeight/rightHeight"列为接管后的第 2 步，但 `ForgeGui.render` 实际在**发 Pre 之前**就复位（ForgeGui.java:109-110 → :112-115）。接管处的复位因此是**防御性**的（更早的 HIGHEST 订阅者可能改过这两个公开字段），非必需但无害。

##### 不补发 RenderGuiOverlayEvent（§7 / §8-2）

**不补发**。理由：

1. `RenderGuiOverlayEvent.Pre/Post` 构造器标注 `@ApiStatus.Internal`（F2）——补发等于把内部 API 当稳定契约依赖，且 `NamedGuiOverlay` 只能复用 `entry` 本身、无法自建；
2. 仓库内该事件**唯一**消费者就是我们自己的 `onRenderGuiOverlayPre`，其效果（隐藏白名单判为隐藏的层）已由重画过滤逐层复现；
3. 补发会逐帧额外派发两轮事件、让第三方 Pre 处理器参与"接管"过程，与强硬接管的语义相悖。

**代价**（接受并写明）：依赖 `RenderGuiOverlayEvent` 的第三方逻辑在 hard 模式下收不到事件——`receiveCanceled = true` 的监听器（如 TACZ）与其自身的条件取消/改写（如 `colorful-hearts` 读 `isCanceled()`）一律不生效。对这类模组，脚本作者可选择不用 hard 模式。

##### Fabric（§4 / §8-4）

**不做**。Fabric 走 `HudRenderCallback`（不可取消、无 ID、无注册表），hard 无对应语义 → 字段在 Fabric 上被正常解析但没有消费者，行为等价 normal。

##### 本版本明确不做

- 针对性 mixin 兜底（§7 末条 / §8-5）：留作将来的最后手段。
- `hud_layers` 键规范（§8-3）：维持现状（分类名 + 未注册 overlay 完整 ID 混用）。

##### 验证（2026-10-07）

- `sh gradlew compileJava`（common / fabric / forge 三模块）**BUILD SUCCESSFUL**；
- 一次性冒烟 harness（未入库）13 项全过 → `SMOKE OK`：默认骨架不写该字段、`true` / `false` / 缺省三种解析结果、并集 OR（null+true / true+false / null+false）、空实例复位、`hide_hud` 与 `hide_skip_hud` 旧语义未变；
- `javap` 核对编译产物：`onRenderGuiPre` 带 `@SubscribeEvent(priority = EventPriority.HIGHEST)`；接管字节码含 `setCanceled(true)` → `leftHeight / rightHeight = 39` → `isWorldOverlay` 豁免 + `isLayerHidden` 过滤 → `IGuiOverlay.render` → 逐层 catch；
- **未在真实客户端跑过**（需图形环境）：§9 第 2/3/4 条只有静态可核性，实测需要真实客户端 + 对应模组。

##### §9 验收对照

| 验收条 | 状态 |
|---|---|
| 默认（normal）行为与现状完全一致 | 静态可核：`isHardHideActive()` 要求显式 `hard_hide_hud: true`，缺省路径不进接管；`onRenderGui(Post)` 在 normal 下与改造前逐字相同 |
| hard 下 `RenderGuiEvent.Pre` 自绘的第三方 HUD 被隐藏 | 由 `setCanceled(true)` 保证（边界见 C3：同优先级且更早注册的监听器仍执行）；**未实测** |
| hard 下白名单判为"显示"的层仍显示 | 重画过滤 = `isWorldOverlay` 豁免 + `!isLayerHidden(categoryOf(id))`，与 normal 判定同源；**未实测**（U2 的例子不可用，需 TACZ / SecurityCraft 一类注册表 overlay） |
| hard 下本模组 overlay 正常显示 | `renderHardHide` 末尾补画 `ClientEventHandler.onRenderHud`；**未实测** |
| 切换模式不需要重启 | 字段来自脚本 meta，逐帧读 `CinematicController.INSTANCE`，无缓存 |

---

#### 1. 定位（已确认）

**目标**：让“不受现有白名单控制”的第三方 HUD 也能被隐藏——特指**不走 Forge overlay 注册表、直接在 `RenderGuiEvent.Pre` 里自绘**的 HUD。

实例：鬼灭之刃（`kimetsunoyaiba`）的 `OverlayCooldownTimeOverlay`——`@EventBusSubscriber(Dist.CLIENT)` + `@SubscribeEvent RenderGuiEvent.Pre`，在事件里自己 `blit` 图标 + `drawString` 画冷却格子。

**形态**：一个**可选的、更强硬的**隐藏模式。默认不开启，保持现有行为不变。

**不是**：替换现有白名单；也不是“按订阅者白名单”（见 §2，那条路不成立）。

---

#### 2. 现状与缺口

现有三条路（都在用）：

| 路 | 机制 | 覆盖对象 |
|---|---|---|
| 原版 HUD | `GuiMixin` + 同族 mixin 逐方法 `cancel` | 快捷栏/准心/经验/计分板（`GuiMixin`）、聊天（`ChatComponentMixin`）、Boss 血条（`BossHealthOverlayMixin`）、玩家列表（`PlayerTabOverlayMixin`）、字幕（`SubtitleOverlayMixin`） |
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

#### 3. 方案：强硬模式（方向）

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

#### 4. 与现有机制的关系

- **白名单语义不变**：`ForgeHudLayerRegistry.categoryOf(overlayId)` 继续给每个 overlay 定类；未注册的层仍以自身 ID 为层名。
- **`GuiMixin` 保留**：normal 模式继续用它逐元素取消；hard 模式下它仍然一致（白名单判为隐藏的原版元素本来也不会被重画）。
- **我们自己的 overlay**：绘制点按模式分流——normal 走 `RenderGuiEvent.Post`（现状），hard 在接管处第 3 步之后自己画。
- **Fabric 侧**：无 overlay 注册表、无 `RenderGuiEvent`，hard 模式**没有对应语义**（只能“我们少画”，拦不到别人自绘）。执行时明确：Fabric 上此模式要么不做，要么退化为 normal。

---

#### 5. 模式开关（方向）

- **不强制开启**：默认 `normal`，行为与现在完全一致。
- 候选形态（执行时定）：脚本 meta 字段（三态，缺省跟随全局）+ 全局配置默认值；与现有 `hide_arm` / `suppress_bob` 的三态回落风格一致。
- 粒度：整段播放级（不做逐帧切换）。
- 是否允许“normal 但单独给某个模组上 mixin 兜底”，见 §7。

---

#### 6. 改动面（预估）

| 文件 | 改动 |
|---|---|
| `forge/.../forge/ForgeClientEvents.java` | 新增最高优先级 `RenderGuiEvent.Pre` 处理器（hard 模式接管）；`onRenderGui(Post)` 仅在 normal 模式生效 |
| `common/.../control/CinematicController.java` | 模式字段 + 查询接口 |
| `common/.../script/ScriptMeta.java`、`ScriptParser.java`、`schema/MetaSchemas.java` | 模式字段（若走脚本级） |
| `forge/.../hud/ForgeHudLayerRegistry.java` | 无改动（复用） |
| `docs/SCRIPT_FORMAT.md`、`docs/modules/control.md`、`docs/modules/mixin.md` | 文档 |

---

#### 7. 风险与可能的问题

- **其他模组在 `RenderGuiEvent.Pre` 里的非绘制副作用会被一并跳过**（状态准备、缓存刷新等）——hard 模式的固有代价，需要接受并在文档里写明。
- **重画原版 overlay 的记账**：`leftHeight/rightHeight` 是公开字段可复位；overlay 内部调用的是 `ForgeGui` 实例方法（实例仍是 `ForgeGui`），但要确认每个原版 overlay 在“非 ForgeGui 循环内”调用时行为一致。
- **`RenderGuiOverlayEvent.Pre/Post` 不补发**：构造器标注 `@ApiStatus.Internal`；我们的隐藏效果已由重画过滤逐层复现，补发会逐帧多派发两轮事件。代价 = 依赖该事件的第三方逻辑在 hard 模式下收不到事件（详见「不补发 RenderGuiOverlayEvent」节）。
- **渲染顺序**：重画的层与我们自己的 overlay 的叠放顺序（原本注册表 overlay 在 Post 之前）要逐项对齐。
- **编辑器预览 / 暂停 / 退场动画**：预览模式下 GUI 多被编辑器屏幕替代，需确认无副作用。
- **兼容**：Sodium / Embeddium 对 HUD 类只做点状注入（`@Mixin(Gui.class)` 的 `@Redirect` 改 fancy-graphics 判定；Embeddium 另有 `@Mixin(ForgeGui.class)` 注入 `renderHUDText`），不接管整段 HUD 渲染、也不订阅 `RenderGuiEvent`，风险低，执行时确认（见文末核查 C2）。
- **兜底**：hard 模式也拦不住的（例如 mixin 进 `Gui.render` 直接画的模组）——是否保留“针对性 mixin”作为最后手段。

---

#### 8. 待定

- 模式字段名与归属（脚本级 / 全局 / 两者结合）
- 是否补发 `RenderGuiOverlayEvent`
- 是否顺带规范 `hud_layers` 的键（现在是“分类名 + 未注册 overlay 完整 ID”混用）
- Fabric 是否做等价能力（或明确不做）
- 是否保留针对性 mixin 兜底
- hard 模式与 `hide_skip_hud`、letterbox、字幕层的叠放细节

> 以上 6 项已于 2026-10-07 结清，结论见文首「落地标注」。

---

#### 9. 验收标准

- [ ] 默认（normal）行为与现状完全一致（回归无变化）
- [ ] hard 模式下：`RenderGuiEvent.Pre` 自绘的第三方 HUD（如鬼灭冷却条）被隐藏
- [ ] hard 模式下：`hud_layers` 判为“显示”的层（如 BetterEvE 格斗血条）仍然正常显示
- [ ] hard 模式下：我们自己的 overlay（黑边 / 字幕 / GIF / 跳过提示）正常显示
- [ ] 切换模式不需要重启游戏

---

#### 事实核查（2026-10-07）

> 依据：Forge `1.20.1-47.4.16_mapped_official_1.20.1-sources.jar`（official 名）、eventbus `6.0.5-sources.jar`、fabric-api `fabric-rendering-v1 3.0.9-sources.jar`、`example/` 参考源码、本仓库源码。行号为上述 sources jar 解出物的行号；时间戳用 `git log -1 --format=%cI -- <路径>` 在仓库根取得。

##### ① 核实为真（含证据）

1. **§3 管线骨架属实**（`net.minecraftforge.client.gui.overlay.ForgeGui#render(GuiGraphics, float)`，ForgeGui.java:104-137）：方法头复位 `rightHeight = 39; leftHeight = 39;`（:109-110）→ `if (MinecraftForge.EVENT_BUS.post(new RenderGuiEvent.Pre(...))) { return; }`（:112-115）→ `GuiOverlayManager.getOverlays().forEach(...)`（:121），循环体 `entry.overlay()`（:124）→ `if (pre(entry, guiGraphics)) return;`（:125）→ `overlay.render(this, guiGraphics, partialTick, screenWidth, screenHeight)`（:126）→ `post(entry, guiGraphics)`（:127），单层异常 try/catch 记日志（:122-131）→ `RenderSystem.setShaderColor(1,1,1,1)`（:134）→ `post(new RenderGuiEvent.Post(...))`（:136）。文档代码块写 `if (!post(...)) { … }`，与源码 `if (post(...)) return;` **语义等价**（`post` 返回"是否被取消"），非错误；源码多出的 try/catch 与 setShaderColor 文档块未体现，不影响结论。
2. **`RenderGuiEvent.Pre` 是 `@Cancelable`**，javadoc 明写"If this event is cancelled, then the overlay will not be rendered, and the corresponding `Post` event will not be fired."→ §3 步骤 1"含 Post 全部跳过"属实。
3. **`leftHeight`/`rightHeight` 为公开字段**：`public int leftHeight = 39; public int rightHeight = 39;`（ForgeGui.java:60-61）→ §3 步骤 2、§7 属实。
4. **API 面属实**：`NamedGuiOverlay` 是 `public record NamedGuiOverlay(ResourceLocation id, IGuiOverlay overlay)`（访问器 `id()`/`overlay()` 公开，仅规范构造器标 `@ApiStatus.Internal`）；`GuiOverlayManager.getOverlays()` 为 `public static ImmutableList<NamedGuiOverlay>`；`IGuiOverlay.render(ForgeGui, GuiGraphics, float, int, int)` → §3 步骤 3 属实。
5. **管线唯一入口 + 实例仍是 ForgeGui**：`GameRenderer` 在 `if (!this.minecraft.options.hideGui || this.minecraft.screen != null)` 内调用 `this.minecraft.gui.render(guigraphics, partialTick)`（GameRenderer.java:943-945）；`Minecraft.gui` 构造为 `new net.minecraftforge.client.gui.overlay.ForgeGui(this)`（Minecraft.java:521）→ §7 属实。
6. **eventbus 6.0.5 无枚举监听器 API**：`IEventBus` 全部方法 = `register(Object)` / `addListener`(4 重载) / `addGenericListener`(4 重载) / `unregister(Object)` / `post(Event)` / `post(Event, IEventBusInvokeDispatcher)` / `shutdown()` / `start()` → §2 属实。
7. **`IEventListener` 公开面** = `invoke(Event)` + `default String listenerName()`（默认 `getClass().getName()`）→ §2 属实。
8. **`ASMEventHandler` 未覆盖 `listenerName()`**（只实现 `invoke`、`getPriority`、`toString`）→ §2 属实。
9. **`NamedEventListener` 门控与范围**：`DEBUG = Boolean.parseBoolean(System.getProperty("eventbus.namelisteners", "false"))`（即 `-Deventbus.namelisteners=true`）；`namedWrapper` 仅在 lambda 路径调用（`EventBus#addListener(priority, filter, eventClass, consumer)` → `addToListeners(consumer, eventClass, NamedEventListener.namedWrapper(...), priority)`），`@SubscribeEvent` 的 ASM 路径直接 `addToListeners(target, eventType, asm, asm.getPriority())` 不经包装 → §2 属实。
10. **`EventPriority.HIGHEST` 存在且最先执行**（枚举顺序 HIGHEST→LOWEST，注释 "First to execute"）→ §3"最高优先级订阅"可行。
11. **本仓 Forge 现状**：`ForgeClientEvents.Forge.onRenderGui(RenderGuiEvent.Post)` → `ClientEventHandler.onRenderHud(event.getGuiGraphics())` → `CinematicOverlay.render(...)` + `SkipHudRenderer.render(...)`（ForgeClientEvents.java:55-57、ClientEventHandler.java:82-88）→ §3 步骤 4"原本在 Post"、§6 表属实。
12. **白名单复用**：`CinematicController.isLayerHidden(String)` 公开（:140-157），固定分类走单项 `hide*`，其余走 `hudLayers`，`resolveLayer(null)` 回落 `hideHud`（:160-163）；`ForgeHudLayerRegistry.categoryOf` 未注册时返回 `overlayId.toString()`（ForgeHudLayerRegistry.java:60-63）→ §3 关键点、§4、§8"分类名 + 完整 ID 混用"属实。
13. **`FabricHudVisibility.isHidden(String)`** 为公开静态，非激活返回 false → §2 表第 3 行属实。
14. **§7"构造器公开但标注 `@Internal`"属实**：`RenderGuiOverlayEvent.Pre/Post` 均为 `@ApiStatus.Internal public Pre/Post(Window, GuiGraphics, float, NamedGuiOverlay)`。
15. **§3 步骤 4 的清单有对应类**：黑边 = `LetterboxLayer`（`OverlayManager` 构造时内置注册）、字幕 = `SubtitleLayer`、图片/GIF = `ImageLayer`（两者由 `OverlayTrackPlayer` 播放时 `addLayer`）、跳过提示 = `SkipHudRenderer`。
16. **example/ 存在本地先例**：`example/ImmersiveCinematics-main/src/main/java/com/example/immersive_cinematics/handler/ClientForgeEvents.java:72-76` 用 `@SubscribeEvent(priority = EventPriority.HIGHEST)` + `event.setCanceled(true)` 拦 `RenderGuiEvent.Pre`（只取消、不重画）→ §3"最高优先级订阅"有可参照实现。
17. **Fabric 侧确实无等价语义**：本仓 Fabric 走 fabric-api `HudRenderCallback`（FabricEvents.java:72），该回调由 `fabric-rendering-v1 3.0.9` 的 `InGameHudMixin` 以 `@Inject(method="render", at=@At("TAIL"), slice=…PlayerListHud.render…)` 触发——非可取消、无 ID、无注册表 → §4 Fabric 结论成立。
18. **注册表路径有第三方实证**：`example/TACZ-1.20.1`（`ClientSetupEvent.onRegisterGuiOverlays` → `registerAboveAll`）、`example/SecurityCraft-1.20.1`（`registerAboveAll`）、`example/SuperbWarfare`（`registerBelowAll`）→ §2 表第 2 行属实。
19. **`hide_*` 三态回落属实**：`Boolean` 可空字段 + `resolveLayer(null) → hideHud`（CinematicController.java:31-34、160-163），解析端 `ScriptParser.optNullableBool`（ScriptParser.java:104-115）→ §5"与 `hide_arm` / `suppress_bob` 的三态回落风格一致"成立（范围限脚本 meta，见 ③-F5）。

##### ② 已修正的断言

- **C1（§2 表第 1 行，已改）**：旧说法"原版 HUD → `GuiMixin` 逐方法 `cancel`，覆盖…计分板/聊天"；新事实：聊天走 `ChatComponentMixin`，Boss 血条走 `BossHealthOverlayMixin`，玩家列表走 `PlayerTabOverlayMixin`，字幕走 `SubtitleOverlayMixin`（`GuiMixin` 自身 javadoc 即写明"非 Gui 私有方法的组件（ChatComponent、BossHealthOverlay、PlayerTabOverlay、SubtitleOverlay）在独立的 Mixin 类中处理"）。**裁决**：文档 `2026-09-17T14:13:08+08:00`（48624a7）晚于代码 `2026-08-30T20:22:20+08:00`（7bc5648）——即不是"代码更新导致文档过时"，而是文档概括不精确；文档描述的对象就是该版代码，且被代码自身注释直接否证，故按代码为准修正（行号不变、方向不变）。
- **C2（§7 兼容条目，已改）**：旧说法"Sodium / Embeddium 不接管 GUI 管线"；新事实：两者都注入 HUD 类——embeddium `@Mixin(Gui.class) InGameHudMixin` 的 `@Redirect(method="render", target="Minecraft;useFancyGraphics()Z")`，另有 `@Mixin(ForgeGui.class) ForgeGuiMixin` 注入 `renderHUDText` 中 `IEventBus.post` 调用处（并用 `ObfuscationReflectionHelper` 读 `ForgeGui` 私有字段 `debugOverlay`）；sodium `@Mixin(InGameHud.class) InGameHudMixin` 同款 redirect 且登记于 `sodium.mixins.json`。**裁决**：`example/` 被 `.gitignore:36`（`/example/`）忽略，`git log` 无提交时间，无法按时间戳裁决；按任务规定以 example 源码为事实依据 → 措辞改为"只做点状注入、不接管整段 HUD 渲染、不订阅 `RenderGuiEvent`"，风险结论（低）不变。
- **C3（§3 步骤 1，未改正文，此处更正口径）**：旧说法"Pre 的其他订阅者…全部跳过"；精确事实：**只跳过排在 IC 监听器之后**的订阅者——同为 `EventPriority.HIGHEST` 且注册更早的监听器仍会执行（`ListenerList.ListenerListInst.register` 按优先级分桶 append，`buildCache` 按 `EventPriority.values()` 顺序展开，`EventBus#post` 顺序遍历该数组）。

##### ③ 补全的信息

- **F1（§6 表）**：`forge/.../forge/ForgeClientEvents.java` 现有成员——外层类 `@Mod.EventBusSubscriber(modid, value = Dist.CLIENT, bus = Bus.MOD)`：`onClientSetup`、`onRegisterKeyMappings`；内嵌 `Forge`（`bus = Bus.FORGE`）：`onClientTick`、`onRenderGui(RenderGuiEvent.Post)`、**`onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre)`**。§6 表未列出后者：其逻辑为 `CameraManager.INSTANCE.isActive()` 早退 → `ForgeHudLayerRegistry.isWorldOverlay(id)` 放行 → `categoryOf(id)` → `isLayerHidden(category)` → `setCanceled(true)`。hard 模式下 ForgeGui 的 overlay 循环整体跳过，该处理器自然不再被调用；若选择补发 `RenderGuiOverlayEvent`，它会重新被触发。
- **F2（§3 步骤 3 / §7 待定项）**：`ForgeGui.pre` / `ForgeGui.post` 是 **private**（ForgeGui.java:641-649），外部无法借用；要补发 `RenderGuiOverlayEvent.Pre/Post` 只能自己 `new` 事件并 `MinecraftForge.EVENT_BUS.post(...)`（构造器 public 但 `@ApiStatus.Internal`）。补发时 `NamedGuiOverlay` 也不能自建（规范构造器 `@Internal`），必须复用 `entry` 本身。
- **F3（§3 步骤 3，事实性后果）**：normal 模式对**世界效果 overlay 有显式豁免**——`ForgeHudLayerRegistry.WORLD_OVERLAYS` = VIGNETTE / SPYGLASS / HELMET / FROSTBITE / PORTAL / SLEEP_FADE / POTION_ICONS / DEBUG_TEXT / FPS_GRAPH（ForgeHudLayerRegistry.java:22-30），`onRenderGuiOverlayPre` 遇到即早退、永不隐藏。这些 ID 未注册分类，`categoryOf` 返回完整 ID，`isLayerHidden` 落到 `hudLayers` 缺省 `hide_hud`。→ 若 hard 模式的重画判定只用 `isLayerHidden(categoryOf(id))`，会把这些世界效果层误判为"隐藏"；重画判定需保留同一豁免。
- **F4（§2 表 / §4 / §7 兜底条）**：Forge 上原版 HUD **不经过 `Gui.render`**——`ForgeGui.render` 覆写该方法且全方法**不调用 `super.render`**（ForgeGui.java:104-137）。因此只被 `Gui.render` 体内调用的 `GuiMixin` 注入点在 Forge 上不生效：`render` 的 HEAD/RETURN（action bar/title 时间清零）、`renderPlayerHealth`、`renderVehicleHealth`、`renderSavingIndicator`（Gui.java:193/196/333）。Forge 上实际生效的是被 overlay lambda 调用的那几个：`renderHotbar`、`renderCrosshair`、`renderJumpMeter`、`renderExperienceBar`（经 `ForgeGui.renderExperience` → `super.renderExperienceBar`，ForgeGui.java:392）、`renderSelectedItemName`、`displayScoreboardSidebar`；"health"/"mount_health" 在 Forge 上由 `RenderGuiOverlayEvent.Pre` 分类 `health` 拦截（VanillaGuiOverlay.PLAYER_HEALTH → `gui.renderHealth(...)`、MOUNT_HEALTH → `gui.renderHealthMount(...)`），不靠 mixin。Fabric 无 `ForgeGui`，mixin 路径照旧。同理，§7 兜底条设想的"mixin 进 `Gui.render` 直接画"的模组在 Forge 上本身就不会被调用（真实落点会是 `ForgeGui.render` 或 overlay 循环）。
- **F5（§5）**：三态回落只存在于**脚本 meta**；`ForgeConfig` / `FabricConfig` 目前**没有任何 `hide_*` / `hud_layers` 键**（现有键为 showSkipHud、skipHoldThresholdMs、skipVoteRatio、debugLogging、preload* 等——原列的 `editorEnabled` 已于 0.3.6 随游戏内编辑器退役删除，见 `editor-webui-migration.md` §4）→"全局配置默认值"是新增项，不是复用现有键。
- **F6（§6 表脚本三件套 / §8）**：`hud_layers` 的值必须是 JSON boolean，非 boolean 项被静默忽略（ScriptParser.parseHudLayers:814-823）；`MetaSchemas` 中 `hide_hud` 默认 `true`、`hud_layers` 类型 `object`（MetaSchemas.java:28、46）。§6 表列的 `common/.../script/ScriptMeta.java`、`ScriptParser.java`、`schema/MetaSchemas.java` 三个路径准确。
- **F7（§7 待定项"是否补发"的现成证据）**：example 中存在依赖 `RenderGuiOverlayEvent.Pre` 取消态/改写逻辑的模组——`colorful-hearts` 的 `RenderEventHandler.renderHearts` 首行即判 `event.isCanceled()`；TACZ 的 `RenderCrosshairEvent.onRenderOverlay` 用 `@SubscribeEvent(receiveCanceled = true)`。→"不补发"会让这类逻辑完全收不到事件（不只是收不到取消态）。
- **F8（§4）**：`ForgeHudLayerRegistry.register(ResourceLocation, String)` 与 `FabricHudLayerRegistry.register` 在仓库内**无调用方**（全仓 grep 仅定义处）→ 现状是所有第三方 overlay 都走"未注册 → 以完整 ID 为层名"的默认分支。
- **F9（§6 表 `CinematicController` 行）**：现有字段/方法清单（CinematicController.java）——`private boolean hideHud = true`（:16）；可空三态 `hideChat` / `hideScoreboard` / `hideActionBar` / `hideTitle` / `hideSubtitles` / `hideHotbar` / `hideCrosshair` / `hideBossbar` / `hideSkipHud` / `hideArm` / `suppressBob` / `suppressDistortion`（:17-33）；`private java.util.Map<String, Boolean> hudLayers = new LinkedHashMap<>()`（:35）；写入由 `apply(ScriptMeta.RuntimeBehavior)`（:39-63）/ `revert()`（:65-90）成对完成；查询接口 `isLayerHidden(String)`（:140）、`isHideHud()` / `isHideChat()` / … / `isSuppressBob()`（:165-178）。新增"模式字段 + 查询接口"照此模式即可（字段 + apply/revert 成对 + getter）。

##### ④ 无法核实（标"未验证"）

- **U1（§1 实例）**：鬼灭之刃（`kimetsunoyaiba`）`OverlayCooldownTimeOverlay` 的 `@EventBusSubscriber(Dist.CLIENT)` + `RenderGuiEvent.Pre` 内 `blit` + `drawString` —— **未验证**：`example/` 下无 kimetsunoyaiba，全仓 grep `kimetsunoyaiba|OverlayCooldownTime` 仅命中本文档。该实例不参与其余断言；§2-§9 的结论不依赖它（"Pre 订阅者不在注册表、无 ID、现有三路碰不到"已由 ①6/①17/①18 独立核实）。
- **U2（§9 验收第 3 条的例子）**：`BetterEvE` 格斗血条作为受 `hud_layers` 控制的注册表层 —— **未验证**：`example/BetterEvE` 无 `RenderGuiEvent` / `RenderGuiOverlayEvent` / `RegisterGuiOverlaysEvent` / `HudRenderCallback` / `IGuiOverlay` 任何引用，其血条实现为 `render/SimpleHealthBarRenderer` + `EntityRenderHandler` + `LevelRendererMixin`（世界空间绘制，不在 GUI overlay 管线内）；仓库内也无 `ForgeHudLayerRegistry.register` 调用（见 ③-F8）。即便 hard 模式生效，世界空间血条也不受 GUI 管线影响——该验收条需要一个可核实的注册表 overlay 实例（如 TACZ / SecurityCraft / SuperbWarfare 的 `RegisterGuiOverlaysEvent` 层）。

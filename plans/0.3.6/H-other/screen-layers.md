# 画面层（Screen Layers）

> 状态：已实现（2026-11-04，H-5）
> 范围：播放期间画面层的可见性控制、15 个规范层名、`screen_layers` / `screen_layer_mode`、普通模式与白名单模式、接管与重画、层注册接口、平台差异
> 关联：`../I-script-core/editor-playback.md`、`docs/SCRIPT_FORMAT.md`、`docs/modules/control.md`、`docs/modules/mixin.md`

## 一、摘要

画面层是播放期间叠加在游戏画面上的原版与第三方呈现内容：HUD、聊天、记分板、字幕、第一人称手臂、视角摇晃、屏幕效果、玩家模型，以及第三方注册或自绘的界面元素。系统决定播放时哪些层出现、哪些不出现；脚本、整合包与模组通过同一套层名接口声明。

两种模式：

- **普通模式（`normal`）**：逐层抑制原版 HUD 与注册表 overlay。
- **白名单模式（`allowlist`）**：接管整帧画面层，先清空，再按白名单逐层重画。

声明了 `screen_layers` / `screen_layer_mode` 的脚本缺省 `allowlist`；完全不带新字段的旧脚本保持 `normal`（零回归）。普通模式同时是无接管能力平台（Fabric）的降级路径。

## 二、为什么做

### 2.1 问题

- 现有字段叫 `hide_hud`，但同一套控制还覆盖手臂、视角摇晃、屏幕效果与玩家模型；这些不经过 HUD 管线，名字与实际范围不符。
- 逐层抑制的约束力不足：不走 overlay 注册表、直接在 GUI 前置事件里自绘的第三方界面，逐层路径碰不到。
- 层清单分散在多个字段里（`hide_hud`、旧 `hud_layers`（deprecated）、`hide_arm`、`suppress_bob`、`suppress_distortion`、`render_player_model`），没有统一的名字与配置入口。
- 整合包与模组需要能注册自己的层并参与白名单，目前只有分类注册表，没有面向作者的统一接口。

### 2.2 目标

- 一套层名覆盖 HUD、手臂、摇晃、屏幕效果、玩家模型与第三方界面。
- 两种模式：普通模式逐层抑制；白名单模式先清空整帧画面层，再按白名单放行。
- 新字段路径缺省白名单模式（`screen_layers` 存在即 `allowlist`）；完全不带新字段的旧脚本保持普通模式（零回归），普通模式同时是无接管能力平台的降级路径。
- 脚本、整合包、模组用同一套层名声明；并集语义明确。
- 编辑器播放与普通播放使用同一套画面层规则，编辑器不额外过滤。

### 2.3 非目标

- 不控制世界内容（地形、实体、粒子、天气）；这些由游戏状态决定。
- 不控制脚本自己的覆盖层（黑边、字幕、图片、GIF）；它们由脚本轨道决定，始终按脚本显示。
- 不定义屏幕效果的具体算法与资源格式；本轮只定义显隐。
- 不做“按订阅者白名单”（不枚举 GUI 前置事件监听器）。
- 不为单个模组写针对性 mixin；不补发 overlay 事件。

## 三、方案

### 3.1 层清单

| 层名 | 内容 | 现有字段 | 空间 |
|---|---|---|---|
| `hud.hotbar` | 快捷栏 | `hide_hotbar` | 屏幕 |
| `hud.crosshair` | 准星 | `hide_crosshair` | 屏幕 |
| `hud.health` | 血量、护甲、载具生命 | `hide_hud` | 屏幕 |
| `hud.experience` | 经验条、等级 | `hide_hud` | 屏幕 |
| `hud.bossbar` | Boss 血条 | `hide_bossbar` | 屏幕 |
| `hud.chat` | 聊天记录与输入 | `hide_chat` | 屏幕 |
| `hud.scoreboard` | 记分板、玩家列表 | `hide_scoreboard` | 屏幕 |
| `hud.action_bar` | 动作栏 | `hide_action_bar` | 屏幕 |
| `hud.title` | 标题 | `hide_title` | 屏幕 |
| `hud.subtitle` | 字幕 | `hide_subtitles` | 屏幕 |
| `hud.skip_prompt` | 本模组的跳过提示 | `hide_skip_hud` | 屏幕 |
| `viewmodel` | 第一人称手臂 | `hide_arm` | 世界（仅玩家可见） |
| `view_bobbing` | 视角摇晃 | `suppress_bob` | 视角 |
| `screen_effects` | 暗角、传送门、冻结、火焰、扭曲、遮罩 | `suppress_distortion` | 屏幕/后处理 |
| `player_model` | 玩家模型 | `render_player_model` | 世界 |

第三方界面：

- 走 overlay 注册表的层，按注册分类名参与白名单；未注册层以自身资源 ID 作层名。
- 直接在 GUI 前置事件里自绘的界面没有 ID，只能被白名单模式整体清空；普通模式碰不到。

### 3.2 两种模式

| 模式 | 行为 | 约束力 |
|---|---|---|
| `normal` | 逐层抑制原版 HUD 与注册表 overlay；前置事件自绘漏 | 弱 |
| `allowlist` | 接管整帧画面层：先清空，再按白名单逐层重画 | 强 |

- 缺省：**新字段路径缺省 `allowlist`**（写了 `screen_layers` 即按白名单接管）；**完全不带新字段的旧脚本保持 `normal`（零回归）**。
- 归一顺序（解析期）：显式 `screen_layer_mode` > `hard_hide_hud`（deprecated 别名，`true`→`allowlist` / `false`→`normal`）> `screen_layers` 字段存在 → `allowlist` > `normal`。
- 模式并集：**任一活跃实例 `allowlist` → 整段 `allowlist`**；无活跃实例时复位。
- `normal` 保留为降级路径：没有可取消的 HUD 回调的平台只能走逐层抑制。
- 两种模式互斥，每帧只画一次。
- 粒度：整段播放。

### 3.3 白名单接口

- 脚本：`screen_layers` 对象，层名 → boolean；**`true` = 显示（放行）**、`false` = 隐藏——与 deprecated 的 `hud_layers` 极性相反。
- 键域：15 个规范层名 + 11 个旧固定分类别名（`hotbar` `crosshair` `chat` `scoreboard` `bossbar` `subtitles` `action_bar` `title` `arm` `skip_hud` `bob`）+ 注册分类名 + 未注册 overlay 的完整资源 ID。
- 整合包/模组：注册层 ID 与分类；白名单按分类或完整 ID 匹配。
- 并集：层集合 = 全部活跃实例 `screen_layers` 的键并集（已知层归一到规范名，其余保留原始键）；每层按**隐藏方向 OR**——任一实例判隐藏即隐藏，显示 = 所有实例都放行。
- 未声明的层回落该实例的旧字段（`hide_*` / `suppress_*` / `render_player_model`），再回落 `hide_hud`；判定顺序 = `screen_layers[规范层名]` → `screen_layers[原始分类键]` → 旧字段 → `hide_hud`。
- 本模组自己的覆盖层不参与白名单，始终按脚本绘制。

> `hud_layers` 已 **deprecated**：解析期归一为 `screen_layers`（极性翻转，旧 `true`=隐藏 → 新 `false`）；键为 15 个规范层名或 11 个固定分类别名时**丢弃 + 解析期告警**（现状本就是 no-op，行为不变）；其余键（`health` / `status` / 未注册 overlay ID）保留**原始键**继续生效。新脚本一律写 `screen_layers`。

### 3.4 接管与重画

白名单模式在**具备接管能力**的平台上接管整帧画面层。接管条件 = 有活跃实例 **且** 生效模式 = `allowlist` **且** 任一层判隐藏（`anyScreenLayerHidden()`）；`hide_hud=false` 且所有层都放行时不接管（无物可藏，接管会砍掉白名单判为显示的内容）。

1. 判定该层需要控制时，取消 GUI 前置事件：原版 HUD、注册表 overlay、后续订阅者自绘、后置事件一并跳过。
2. 复位 HUD 布局字段（快捷栏左右高度）。
3. 遍历注册表 overlay，世界效果层豁免；其余按白名单判定，判为显示的逐层重画。判定按**规范层名优先、原始分类键兜底**：`screen_layers[规范层名]` → `screen_layers[原始分类键]` → 旧字段 → `hide_hud`。
4. 补画本模组覆盖层（黑边、字幕、图片/GIF、跳过提示）。
5. 普通模式的绘制点在后置事件；白名单模式在接管处补画，后置事件早退，保证每帧只画一次。

### 3.5 平台差异

- **Forge**：具备可取消的整段 HUD 回调（`RenderGuiEvent.Pre`）与 overlay 注册表 → `supportsScreenLayerTakeover()` = `true`，生效模式可以是 `allowlist`。
- **Fabric**：HUD 回调**不可取消**、且没有 overlay 注册表（无可取消的整段 HUD 回调、无可枚举的 overlay 清单）→ `supportsScreenLayerTakeover()` = `false`，生效模式**降级 `normal`**。`screen_layers` 仍被解析，逐层过滤照常生效；注册表在 Fabric 只影响**普通层过滤**（`FabricHudVisibility.isHidden` 是唯一消费点），不参与任何重画。

### 3.6 层注册接口（H11）

对外入口是 `common` 的 `api.HudLayerRegistry`（与 `CameraApi` 同包的稳定面，只增不删）。它是第三方注册的**唯一**注册表；平台侧只负责"分类解析优先级 + 平台回调"。

| 成员 | 语义 |
|---|---|
| `install(Platform)` | 安装平台回调；只应在客户端初始化期调用一次，后装者覆盖 |
| `register(ResourceLocation overlayId, String category)` | 把 overlay 归入分类；`overlayId` / `category` 为 null 时忽略整次调用，`category` 为空串 = 未指定（等价于单参重载） |
| `register(ResourceLocation overlayId)` | 分类 = 资源 ID 字符串（把隐式口子写成显式契约） |
| `categoryOf(ResourceLocation overlayId)` | 查询分类；未注册返回 `null`，由平台自行回落 |

平台边界：

- **Forge**：`ForgeHudLayerRegistry.categoryOf` = 内置原版表 → 门面表 → `overlayId.toString()`；normal（`RenderGuiOverlayEvent.Pre` 过滤）与 allowlist（`RenderGuiEvent.Pre` 接管 + 白名单重画）两条消费链共用这一条解析。门面回调留空——消费链直接读表，注册写表即生效。
- **Fabric**：`FabricHudLayerRegistry.categoryOf` = 自有表 → 门面表（层名按资源 ID 语法解析后查）→ 原始层 ID；唯一消费点是 `FabricHudVisibility.isHidden`。Fabric 无 overlay 注册表、无 allowlist 能力，注册只影响普通层过滤，分类名仅作记录（首次注册打一条 `INFO` 说明边界）。

注册时机：客户端初始化期完成安装与首次注册（Forge `FMLClientSetupEvent`、Fabric `ClientModInitializer`）；注册项进程级存在，不随脚本销毁。加载器不保证模组初始化顺序：若第三方在本模组安装回调之前注册，那条边界日志不会出现，但注册本身仍写入门面表并照常参与判定。

失败隔离：注册表本身没有异常路径——null 参数忽略、未注册查询返回 `null` 而不是抛；平台回调约定不得抛异常（异常会外溢到第三方注册调用）。`screen_layers` 的规范层名优先于注册分类名；注册不会改变 15 个规范层名的判定。

## 四、技术细节

### 4.1 核心模型

| 对象 | 职责 |
|---|---|
| 层注册表 | 层 ID/分类 → 可见性判定；未注册层以完整 ID 为层名 |
| 画面层控制器 | 汇总活跃实例的层声明，计算并集与模式 |
| 接管器 | 白名单模式下接管整帧画面层，清空后按白名单重画 |
| 逐层抑制器 | 普通模式下按层取消原版 HUD 与注册表 overlay |

### 4.2 契约

- **创建**：层注册表在客户端初始化时建立；模组/整合包可追加分类。
- **销毁**：不随脚本销毁；注册项进程级存在。
- **状态归谁**：层声明随播放实例的行为快照；并集由控制器持有；接管器无状态，每帧读控制器。
- **模式并集**：模式随实例进入并集——任一活跃实例 `allowlist` → 整段 `allowlist`；无活跃实例时复位 `normal`。
- **平台降级**：生效模式 = 声明并集 **且** `HudLayerRegistry.Platform#supportsScreenLayerTakeover()`；能力位为 `false`（Fabric）时按 `normal` 执行，声明本身仍保留。
- **模式切换**：逐帧读控制器，不重启；无实例时复位。
- **重画职责**：白名单模式下由接管器重画；普通模式的绘制职责归后置事件监听器；两种模式互斥。

### 4.3 数据形态

- 脚本字段：`screen_layers`（对象，层名 → bool，**`true` = 显示**）、`screen_layer_mode`（`normal` / `allowlist`）。写了 `screen_layers` 而未写模式时缺省 `allowlist`；两者都不写的旧脚本为 `normal`。
- 层键：15 个规范层名、11 个旧固定分类别名、注册分类名、未注册 overlay 的完整资源 ID。
- 并集：布尔 OR（隐藏方向）；未声明项回落该实例旧字段（`hide_*` / `suppress_*` / `render_player_model`），再回落 `hide_hud`。
- 归一：`hud_layers`（deprecated）解析期翻转为 `screen_layers`；`hard_hide_hud`（deprecated）解析期映射为模式。两者都不进入运行时判定。
- 不持久化、不走网络包；客户端读已下发的脚本元数据与本地注册表。

### 4.4 边界

- 世界内容不受控：实体、粒子、天气、地形。
- 脚本覆盖层不受控：黑边、字幕、图片、GIF 始终按脚本显示。
- 不替换层注册表与分类机制；只统一命名与接口。
- 不定义 `screen_effects` 的算法与资源格式；扭曲、遮罩作为该层的扩展另行定义。
- 取消只影响排在接管监听器之后的订阅者；同优先级且注册更早的监听器仍会执行，已经画上去的内容不会被清除。
- 依赖 overlay 事件的第三方逻辑在白名单模式下收不到事件。

## 五、现行实现

画面层语义已按本方案落地（H-5，2026-11-04）。对外入口只有一套：`screen_layers` + `screen_layer_mode`；`hard_hide_hud` 与 `hud_layers` 保留为 **deprecated** 别名，解析期归一，不参与运行时判定。

### 5.1 入口与并集

- 脚本级 `screen_layers`（对象，层名 → bool，**`true` = 显示**）与 `screen_layer_mode`（`normal` / `allowlist`）。
- 模式归一（解析期）：显式 `screen_layer_mode` > `hard_hide_hud`（deprecated：`true`→`allowlist` / `false`→`normal`）> `screen_layers` 字段存在 → `allowlist` > `normal`。
- `hud_layers`（deprecated）解析期翻转为 `screen_layers`：15 个规范层名或 11 个固定分类别名作键 → 丢弃 + 告警；其余键保留**原始键**继续生效。
- 并集：任一活跃实例 `allowlist` → 整段 `allowlist`；层按隐藏方向 OR（任一实例判隐藏即隐藏）；无实例复位。
- 粒度：整段播放级。

层清单与回落：

| 规范层名 | 空间 | 旧字段 | 未声明时回落 |
|---|---|---|---|
| `hud.hotbar` | 屏幕 | `hide_hotbar` | `hide_hotbar ?? hide_hud` |
| `hud.crosshair` | 屏幕 | `hide_crosshair` | `hide_crosshair ?? hide_hud` |
| `hud.health` | 屏幕 | `hide_hud` | `hide_hud` |
| `hud.experience` | 屏幕 | `hide_hud` | `hide_hud` |
| `hud.bossbar` | 屏幕 | `hide_bossbar` | `hide_bossbar ?? hide_hud` |
| `hud.chat` | 屏幕 | `hide_chat` | `hide_chat ?? hide_hud` |
| `hud.scoreboard` | 屏幕 | `hide_scoreboard` | `hide_scoreboard ?? hide_hud` |
| `hud.action_bar` | 屏幕 | `hide_action_bar` | `hide_action_bar ?? hide_hud` |
| `hud.title` | 屏幕 | `hide_title` | `hide_title ?? hide_hud` |
| `hud.subtitle` | 屏幕 | `hide_subtitles` | `hide_subtitles ?? hide_hud` |
| `hud.skip_prompt` | 屏幕 | `hide_skip_hud` | `hide_skip_hud == true`（不回落 `hide_hud`） |
| `viewmodel` | 世界 | `hide_arm` | `hide_arm ?? hide_hud` |
| `view_bobbing` | 视角 | `suppress_bob` | `suppress_bob ?? hide_hud` |
| `screen_effects` | 屏幕/后处理 | `suppress_distortion` | `suppress_distortion ?? suppress_bob ?? hide_hud` |
| `player_model` | 世界 | `render_player_model` | `!render_player_model`（不回落 `hide_hud`） |

判定顺序：`screen_layers[规范层名]` → `screen_layers[原始分类键]` → 该层旧字段回落 → `hide_hud`。

### 5.2 接管条件

接管只在同时满足时生效：

- 有活跃播放实例；
- 生效模式 = `allowlist`（声明并集为 `allowlist` **且** 平台具备接管能力）；
- 任一层判隐藏（`anyScreenLayerHidden()`）。

任一层都不隐藏时不接管：无物可藏，接管会把白名单判为显示的第三方自绘内容一并砍掉。旧写法 `hard_hide_hud:true` 且不带 `screen_layers` 时与此等价——15 层回落全部由 `hide_hud` 决定。

### 5.3 接管与重画（Forge）

- 最高优先级监听 GUI 前置事件，取消整段 HUD 渲染（原版 HUD、注册表 overlay、后续订阅者自绘、后置事件一并跳过）。
- 复位快捷栏左右高度；遍历注册表 overlay，世界效果层豁免，其余按白名单判定重画——**规范层名优先、原始分类键兜底**。
- 末尾补画本模组覆盖层（黑边、字幕、图片/GIF、跳过提示）。
- 普通模式仍走后置事件；`allowlist` 在接管处补画，后置事件早退，保证每帧只画一次。

### 5.4 不补发 overlay 事件

不补发，理由：

- 事件构造器标注内部 API，补发等于依赖不稳定契约；注册表条目不能自建。
- 本仓该事件唯一消费者是我们自己的隐藏判定，效果已由重画过滤复现。
- 补发会逐帧额外派发两轮事件，让第三方前置处理器参与接管。

代价：依赖该事件的第三方逻辑在 `allowlist` 下收不到事件（含 `receiveCanceled=true` 的监听器与读取取消态的模组）。

### 5.5 平台

- **Forge**：`supportsScreenLayerTakeover()` = `true`，`allowlist` 完整生效（接管 + 白名单重画）。
- **Fabric**：`supportsScreenLayerTakeover()` = `false`（HUD 回调不可取消、无 overlay 注册表）→ 生效模式**降级 `normal`**；`screen_layers` 仍解析并参与逐层过滤，注册表只影响普通层过滤。

### 5.6 已知偏差与契约边界

- `hud.health` / `hud.experience` 共用 `hide_hud`：旧字段无法分别控制，只有 `screen_layers` 能拆开。
- `hud.skip_prompt` 仅在 `hide_skip_hud == true` 时隐藏、`player_model` 取 `render_player_model` 的反面——两者都不回落 `hide_hud`。
- 重画额外豁免世界效果层（暗角、传送门、药水图标等），否则会被误判为隐藏。
- 快捷栏高度复位是防御性操作：正常流程在事件之前已复位，更早的监听器可能改过公开字段。
- 不补发 `RenderGuiOverlayEvent.Pre/Post`：依赖这些事件的第三方逻辑在 `allowlist` 下收不到事件。
- 取消只影响排在接管监听器之后的订阅者；同优先级且注册更早的监听器仍会执行，已经画上去的内容不会被清除。
- 不控制世界内容（实体、粒子、天气、地形）。
- 脚本覆盖层（黑边、字幕、图片、GIF）不参与白名单，始终按脚本绘制。
- 直接 mixin 进 `Gui.render` 或绕过平台入口绘制的 HUD 没有挂点；不做针对性 mixin。
- Fabric 注册表只影响普通层过滤；`allowlist` 在 Fabric 降级为 `normal`。
- 旧 `hard_hide_hud` 没有全局配置键；缺省即 `normal`。

## 六、调查与修正

### 6.1 管线事实

- GUI 前置事件可取消；取消后原版 HUD、注册表 overlay、后置事件与后续订阅者一并跳过。
- 原版 HUD 在部分加载器上不经过 `Gui.render`，逐元素 mixin 的注入点覆盖不同；Forge 上部分元素由 overlay 分类拦截。
- 注册表 overlay 有稳定 ID；前置事件自绘内容没有 ID，无法按模组放行。
- 事件总线没有枚举监听器的 API；监听器没有稳定身份。按订阅者白名单做不到。

### 6.2 补充事实

- 原版 HUD 的隐藏不是单一 mixin：聊天、Boss 血条、玩家列表、字幕各有独立注入点。
- Sodium / Embeddium 只做点状注入，不接管整段 HUD 渲染、不订阅前置事件；兼容风险低。
- 取消只跳过排在接管监听器之后的订阅者；同优先级且注册更早的仍执行。
- Fabric 的 HUD 回调不可取消、无 ID、无注册表，白名单模式没有对应语义。

## 七、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 继续叫 `hide_hud` | 只改文档不统一层名 | 手臂、摇晃、模型仍散落在各自字段；名字继续误导 | 统一为画面层 |
| 只做逐层抑制 | 不接管整帧 | 前置事件自绘的第三方界面漏网 | 新字段路径缺省白名单模式，普通模式降级 |
| 按订阅者白名单 | 枚举前置事件监听器，按模组放行 | 事件总线无枚举 API；监听器无稳定身份 | 做不到，且脆弱 |
| 针对性 mixin | 每个第三方模组写一个注入 | 每加一个模组就要维护，版本或混淆变化即失效 | 不可扩展，只留作最后手段 |
| 全屏遮罩 | 在最上层盖不透明层 | 连白名单层与脚本覆盖层一起盖住 | 与“按白名单放行”冲突 |
| 补发 overlay 事件 | 重画时补发前置/后置事件 | 依赖内部 API，逐帧双倍派发 | 不选 |
| 把脚本覆盖层也纳入白名单 | 统一控制所有画面内容 | 黑边/字幕/图片由脚本轨道决定，纳入会重复控制 | 脚本覆盖层不参与 |

**本方案的缺点**：

- 一次性改名涉及脚本字段、schema、编辑器、文档与迁移器。
- 白名单模式依赖可取消的 HUD 回调，平台支持不一致。
- 前置事件自绘的第三方界面只能整体清空，无法按模组放行。
- 接管会跳过前置事件订阅者的非绘制副作用（状态准备、缓存刷新）。
- 依赖 overlay 事件的第三方逻辑在 `allowlist` 下收不到事件。
- 同优先级更早注册的监听器仍可能残留绘制。
- 通过 mixin 直接画的界面不受影响。
- 层清单会随原版与第三方变化而增长，注册表需要维护。

## 八、验收

- 白名单模式（`allowlist`，含新字段路径缺省）：前置事件自绘的第三方界面被清空；白名单判为显示的层仍显示；本模组覆盖层正常显示。
- 普通模式：行为与现有逐层抑制一致；切换模式不重启。
- 层接口：`screen_layers` 的层名与注册分类/完整 ID 都能参与判定；并集与回落符合定义。
- 平台：可接管平台走白名单；不可接管平台降级普通模式且不崩。
- 编辑器播放：脚本声明什么就呈现什么；编辑器不额外过滤。
- 真实客户端：需图形环境与对应第三方模组，静态可核不算通过。

## 九、实现结果

状态：**已实现（H-5，2026-11-04）**。层清单、`screen_layers` / `screen_layer_mode`、两种模式、接管与重画、平台能力位与编辑器同规则全部落地。

已实现清单：

- **层清单与 `ScreenLayer` 表**：15 个规范层名 + 11 个旧固定分类别名 + 逐层回落口径集中在一张表；`hud.health` / `hud.experience` 共用 `hide_hud`，`hud.skip_prompt` 与 `player_model` 不回落 `hide_hud`。
- **`screen_layers` / `screen_layer_mode` 解析与归一**：解析期完成模式归一（显式 > `hard_hide_hud` 别名 > 字段存在 → `allowlist` > `normal`）；键归一与极性（`true` = 显示）；校验与 schema 同步导出两字段。
- **`hud_layers` deprecated 归一**：解析期翻转为 `screen_layers`；15 个规范层名 / 11 个固定分类别名作键 → 丢弃 + 告警（原为 no-op，行为不变）；其余键保留原始键继续生效。
- **`hard_hide_hud` 别名**：解析期映射为模式（`true`→`allowlist` / `false`→`normal`），与显式 `screen_layer_mode` 冲突时显式字段优先并告警；不保留第二套判定逻辑。
- **并集与判定**：并集汇总模式与层（隐藏方向 OR，模式 `allowlist` 优先）；单层判定按「规范层名 → 原始分类键 → 旧字段 → `hide_hud`」。
- **`allowlist` 接管与重画**：合并旧的强制隐藏重画路径为单一实现；接管条件 = 有活跃实例 + 生效模式 `allowlist` + 任一层判隐藏；重画按规范层名优先、原始分类键兜底，世界效果层豁免，末尾补画本模组覆盖层。
- **`normal` 消费点改用规范层名**：原版 HUD 各元素、聊天、Boss 血条、玩家列表、字幕、手臂、摇晃、玩家模型统一走规范层名判定；不在 15 层内的元素（跳跃条、选中物品名、保存提示）保持原判定。
- **平台能力位与 Fabric 降级**：`HudLayerRegistry.Platform#supportsScreenLayerTakeover()`（缺省 `false`）；Forge 装 `true`，Fabric 保持 `false` → 生效模式降级 `normal`，注册表只影响普通层过滤。
- **编辑器同规则**：编辑器播放与普通播放共用同一并集（`editorDriven` 只影响键鼠屏蔽），画面层不额外过滤。

验证：

- 无头探针 46 + 26 + 4 断言全 PASS（`E:/tmp/h5-probe/`，仓库外）。
- **实机（Fabric 1.20.1 dev 客户端）**：临时夹具 `test_h5_fabric_degrade`（`screen_layer_mode: allowlist` + `hide_hud: true` + `hide_hotbar: true` + `hide_chat: false` + `screen_layers {"hud.hotbar": true, "hud.chat": false}`）完整播放 30s，无异常。截图 `E:/tmp/h5-probe/fabric-normal.png`：快捷栏深色像素 75750（显示——`screen_layers` 覆盖 `hide_hotbar`）、聊天区 1（无文字）、本模组字幕亮像素 8056。即 Fabric 上 `allowlist` 按能力位降级 `normal`，`screen_layers` 逐层过滤照常。
- **实机（Forge 47.4.16 dev 客户端）**：临时第三方 Pre 自绘（160×160 红方块，默认优先级，取证后已删除）作对照。同一夹具：
  - 空闲基线：红块 102400 px（确认自绘生效）。
  - `allowlist`：红块 **0 px**（接管清空前置事件自绘）、快捷栏 112805 px（白名单重画）、聊天文字 0（发过消息的对照为 1504 px）、本模组字幕 1004 px。
  - `normal` 对照：红块 102400 px（未接管，第三方自绘保留）、快捷栏 112805 px、聊天文字 0、字幕 618 px。
  - 两轮脚本均完整播放 40s 并正常释放，无异常。
- 夹具：`cinematics/0.3.6/tests/hud/test_h12_screen_layers_allowlist.json` / `test_h12_screen_layers_normal.json`，`ScriptValidator` 0 issue，解析结果与声明一致。

实现边界：

- 注册接口：只对走平台 overlay 管线的层有效；不走 Forge overlay 注册表、也不在 `RenderGuiEvent.Pre` 自绘的 HUD（含 mixin 直绘）注册了也不受控；Fabric 侧第三方须自行调用 `FabricHudVisibility.isHidden`。
- 其余契约边界见 §5.6。

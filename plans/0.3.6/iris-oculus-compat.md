# 0.3.6 光影（Iris / Oculus）兼容调研计划

**状态**: 📋 **调研计划**——只定义「已经知道什么、还要查什么、怎么查、结论怎么用」；**本文不含结论**（用户点名：光影兼容「现在还不知道有什么问题，而且没有调查，这个写个计划」）。**例外**：§2.7 是 2026-10-08 用户裁决后的**已定结论**（只覆盖主画面 master 调色挂点），其余仍为待调查项。
**目标版本**: 0.3.6
**关联**: [multi-camera-rendering](./multi-camera-rendering.md)（§7 / §7.1 / §12.5）、[render-routes](./render-routes.md)（§4）、[render-second-pass-cost](./render-second-pass-cost.md)（§7）、[screen-color-adjust](./screen-color-adjust.md)（§4 光影挂点段 / §5-1）

> **术语**：**光影** = Iris（Fabric）/ Oculus（Forge 移植，`modId=oculus`、`provides=["iris"]`）；**优化模组** = Sodium（Fabric）/ Embeddium、Rubidium（Forge）。
> **范围**：本文只管「光影」这一条兼容分支。优化模组的适配是另一条线（现状 = 检测到即禁用副画面 + Mixin 插件跳过 `LevelRendererMixin`），只在**交叉项**（§3-④⑤）里涉及。
> **写法**：**已确认的写「已确认」**——读码核实过的另附文件 + 方法 / 行号；**不确定的写「未验证」+ 方向与可能的问题**（含调查方法）。不写伪代码——只写要查什么、怎么查、产出什么。

---

## 1. 定位与背景

### 1.1 议题

多相机 lane 渲染（`client/lane/LaneRenderer`，0.3.6 已落地）走的是**原版渲染 API 的第二遍世界渲染**：每 lane 一次 `LevelRenderer.renderLevel` + `doEntityOutline`，渲染进共用离屏 FBO，再由合成层贴回主画面；master 调色（`ColorAdjustPass`）在主画面合成之后、原版后处理链之后（2026-10-08 后移，见 §2.7）。

光影模组把「一遍世界渲染」整体改写成自己的管线（gbuffer → composite 链 → final pass → 色彩空间转换），并且**每帧自己还会额外渲染一遍**（阴影 pass）。两者都改同一批入口（`LevelRenderer.renderLevel` / `GameRenderer.renderLevel`），因此「我们的第二遍在光影下是什么语义、能不能跑对、跑起来多贵」完全未知。

### 1.2 为什么现在写计划

- 渲染底层与合成已经落地并在实机验证（[multi-camera-rendering](./multi-camera-rendering.md) §12），**唯一没做适配、也没调查**的兼容面就是光影；
- 光影是 MC 生态里客户端最常见的「渲染改写型」模组，用户的机器上大概率装着；「装了光影就看不到多相机画面」与「装了光影画面错乱」是两种完全不同的产品后果，必须先知道是哪种；
- 现在的行为是**未经检验的默认路径**：检测列表里**没有** Iris / Oculus（见 §2.1），也就是说光影下 lane 会照常走第二遍——是「能用」「画面错」还是「崩」，目前**没有任何实测数据**。

### 1.3 与既有文档的关系（各文档的现状断言，此处复述不改变其结论）

| 文档 | 现有断言 | 本文的动作 |
|---|---|---|
| `multi-camera-rendering.md` §7 | 光影「自己会做多次渲染，说明多次渲染可行；但第三方二次调用可能绕过光影的合成，或触发重复特效；**需要专门调研：能否在光影环境下拿到『已处理后的画面』**」 | 把这三句拆成 §3 的可调查项（① 先后 / ② 可行性 / ⑧ 合成归属） |
| 同文档 §7.1 / §12.5 | 策略「副画面走原版 API + 运行时检测 Sodium / Embeddium / Oculus → 存在时副画面自动禁用 + warn」；✅ 已落地（`LaneRenderer.isUnavailable()`）；明确写「**不做 Iris 级完整适配**」；「Oculus / Iris 的『取已处理画面』仍未验证」；§12.5「光影（Iris / Oculus）：未做适配（§7 第 3 点仍待调研）」 | 本文就是那条「待调研」的**计划**；检测口径与策略选项在 §3-⑦ / §4 展开 |
| `render-routes.md` §4 | 兼容分支两条：光影 =「能否拿『已处理后的画面』」；优化模组 =「检测到 → 禁用 + warn；或走它们的 Camera / Frustum 管线（`CameraMixin` 路线）」 | 沿用该二分；本文只做光影一侧 |
| `render-second-pass-cost.md` §7 | 未决问题清单里三条与本文直接相关：「副画面在 **Iris/Oculus** 光影环境下的可用性」；「两遍/帧在 **Sodium/Embeddium** 下 `frame++` 语义是否会导致区块渲染错误」；「副画面是否需要**独立光照**」；§6.3 步骤 5 已写了一个可选佐证方案（装 Iris + 带阴影的光影包，用阴影开 / 关的 FPS 差估「每帧一遍世界渲染」的占比，**只作量级参照**） | ④⑥ 直接继承；§6.3 步骤 5 升级为本文 §5 的正式步骤（并明确它只回答量级，不回答正确性） |
| `screen-color-adjust.md` §4（光影挂点段）/ §5-1 | 「Iris / Oculus 在 `GameRenderer.renderLevel` 的 `TAIL` 调 `finalizeGameRendering()`；Iris 的最终合成在 `LevelRenderer.renderLevel` 尾部 `finalizeLevelRendering()` → `compositeRenderer.renderAll()` + `finalPassRenderer.renderFinalPass()`；`finalizeGameRendering()` 做色彩空间转换 → **都早于原版 `postEffect` 那一步**；无光影包时 `VanillaRenderingPipeline` 两个方法都是空实现；**取舍仍待定**」 | §2.3 / §2.5 逐条核实；**§2.6 给出两处口径澄清**（「都早于原版那一步」对合成成立，对色彩空间转换不成立——它与我们的**旧**挂点同点）；**挂点取舍已于 2026-10-08 由用户裁决落定：调色挂点后移到原版 RPOST 之后、GUI 之前，与色彩空间转换解耦（§2.7）** |

---

## 2. 已知道的（读码核实）

> 核实口径（2026-10-08）：本仓 `common/` 代码 + `example/Iris-1.20.1`、`example/Oculus-1.20.1-new` 源码。`example/` 被 `.gitignore:36` 忽略、无 git 历史，只能用文件内容；行号为该副本内的行号。

### 2.1 现状策略：优化模组 → 副画面自动禁用 + warn；**光影不在检测列表里**（已核实）

| 事实 | 证据 |
|---|---|
| 检测走**标记类存在性**（不引入硬依赖），只列 Sodium 家族两个入口类：`net.caffeinemc.mods.sodium.client.SodiumClientMod`（Fabric Sodium）/ `me.jellysquid.mods.sodium.client.SodiumClientMod`（Embeddium / Rubidium） | `client/lane/LaneRenderer.java` `RENDER_OPTIMIZER_MARKERS`（:107-110）+ `findRenderOptimizer()`（:464-473） |
| 命中即**不渲染**（不分配、不切状态；脚本 lane 照常注册，只是渲染被禁用，调试捕获也拿不到 lane 画面）并 **warn 一次**（结果缓存） | 同文件 `isUnavailable()`（:452-461）、`render(...)` 开头的 `if (isUnavailable()) return;`（:276-278）；日志文案「检测到渲染优化模组（…）：多 lane 副画面渲染已禁用（它们 @Overwrite 原版区块渲染与 setupRender）」 |
| **光影（Iris / Oculus）没有任何检测**：`common/src/main/java` 全树搜 `iris` / `oculus` / `shaderpack`（不分大小写）**零命中** | 全树检索结果为空 |
| 加载器侧另有一层：Mixin 配置插件在检测到 `sodium` / `rubidium` / `embeddium` 时**跳过 `LevelRendererMixin`**（理由是它们 `@Overwrite` 原版 `setupRender`，我们的注入无法应用；且它们基于 Camera/Frustum 的渲染中心会跟随 `CameraMixin` 的虚拟相机） | `fabric/src/main/java/.../mixin/ImmersiveCinematicsMixinPlugin.java`、`forge/src/main/java/.../mixin/ImmersiveCinematicsMixinPlugin.java`（两者同构，`shouldApplyMixin`）；插件挂在 `common/src/main/resources/immersive_cinematics.mixins.json` 的 `plugin` 字段 |
| 我们的 Mixin 配置是 `"defaultRequire": 1`——注入点若被第三方改写而找不到，**启动期直接失败**（不是静默降级） | `immersive_cinematics.mixins.json`（`injectors.defaultRequire`） |

**推论（未验证）**：装了光影但**没有**装 Sodium 家族（例如 Fabric Iris 单装、或 Forge Oculus 单装）时，`isUnavailable()` 不命中 → lane 会照常走第二遍原版 `LevelRenderer.renderLevel`，而这一次调用**同时**会触发光影自己的全部注入。这正是 §3 要查的现状路径。

### 2.2 我们的挂点位置（本仓代码，已核实）

| 环节 | 挂点 / 行为 | 证据 |
|---|---|---|
| 帧首（世界渲染之前） | `GameRenderer.render` 的 **HEAD**：`CameraManager.onRenderFrame()` + 脚本 lane 注册（`ScriptLaneDriver`）+ `CinematicOcclusion.beginFrame`（顺序固定） | `mixin/GameRendererMixin.java` `onRenderFrameStart`（`@Inject(method = "render", at = @At("HEAD"))`） |
| **lane 渲染 + 合成** | `GameRenderer.renderLevel` 的 **RETURN**：`LaneRenderer.INSTANCE.render(...)`（MCOMP；master 调色已不在本注入，见下一行） | `mixin/LaneRendererMixin.java` `ic$worldRendered`（`@Inject(method = "renderLevel", at = @At("RETURN"))`）；类 javadoc 写明「只做 MCOMP」 |
| **master 调色** | `GameRenderer.render` 内、原版后处理链（RPOST）之后、GUI 之前：`ColorAdjustPass.render(mc)`（注入目标 = `RenderTarget.bindWrite(Z)V` 的调用，即 `postEffect.process(f)` 之后那一句 `getMainRenderTarget().bindWrite(true)`） | `mixin/GameRendererMixin.java` `onWorldPostProcessed`（`@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V"))`）；2026-10-08 后移，见 §2.7 |
| 每 lane 一遍 | `mainRenderTarget` 临时指向 lane FBO → `fbo.bindWrite(true)` → 该 lane 的相机 / 投影 / `prepareCullFrustum` → `mc.levelRenderer.renderLevel(...)` → `doEntityOutline()` → 还原投影；lane 块结束回绑主画面 | `client/lane/LaneRenderer.java` `render(...)`（:269-331）与 `renderLane(...)`（:334-400）；主画面指向用 `MinecraftAccessor` 的 `ic$setMainRenderTarget` |
| 主画面描边 | lane 块开头先为主画面贴一次 `doEntityOutline()`（原版那次整屏调用在有活跃 lane 时被屏蔽） | 同文件 `render(...)` 的 `mainOutlinePass` 分支；屏蔽判定在 `mixin/LevelRendererMixin.java` |
| 遮挡剔除 | 有活跃 lane 的帧**整帧 `smartCull=false`**：`LevelRendererMixin` 在 `setupRender` 的 HEAD / RETURN 包夹；判定输入 = `LaneRenderer.hasActiveLanes()`；起 / 停播强制 `LevelRenderer.needsUpdate()` | `camera/CinematicOcclusion.java`（`isOcclusionOffThisFrame`）、`mixin/LevelRendererMixin.java`（:106-119） |
| master 调色的 pass 形态 | 两个全屏 pass：主画面 → 中转缓冲（`ic_color_adjust`）→ 主画面（整屏 blit，复用合成层着色器）；lane 级只有一次 pass | `client/post/ColorAdjustPass.java` 类 javadoc「两个 pass（master 路径）」 |

**要点**：我们的 lane pass 是在 `GameRenderer.renderLevel` 的**返回点**执行的，此时主 `LevelRenderer.renderLevel` 已经返回——我们是在「一帧之外」再开 N 次 `LevelRenderer.renderLevel`。

### 2.3 光影的挂点位置（example 源码，已核实）

| 光影动作 | 挂点 | 证据 |
|---|---|---|
| 管线准备 | `LevelRenderer.renderLevel` 的 **HEAD**：`pipeline = preparePipeline(...)`、可能替换 `cullingFrustum` 为 `NonCullingFrustum`、**`Minecraft.smartCull = !pipeline.shouldDisableOcclusionCulling()`** | Iris `mixin/MixinLevelRenderer.java` `iris$setupPipeline`（`@Inject` :75；`smartCull` 赋值 :96）；Oculus 同构（`@Inject` :66、`smartCull` :87） |
| 世界渲染开始 | `LevelRenderer.renderLevel` 内 `RenderSystem.clear` 之后：`pipeline.beginLevelRendering()` | 同文件 `iris$beginLevelRender`（`@Inject` :107，`at = @At(value = "INVOKE", target = CLEAR, shift = AFTER)`；Oculus :98） |
| **阴影 pass（每帧一次完整第二遍世界渲染）** | `LevelRenderer.renderLevel` 内、`renderSky` 调用之前：`pipeline.renderShadows((LevelRendererAccessor) this, camera)` | Iris `mixin/MixinLevelRenderer.java:140-143`；Oculus `:125-128`；实现 `shadows/ShadowRenderer.java`（Iris 730 行 / Oculus 729 行） |
| **最终合成（光影后处理链）** | `LevelRenderer.renderLevel` 的 **RETURN（`shift = BEFORE`）**：`pipeline.finalizeLevelRendering()` → `compositeRenderer.renderAll()` + `finalPassRenderer.renderFinalPass()`，随后 `pipeline = null` | Iris `mixin/MixinLevelRenderer.java:118-123`；Oculus `:109-114`；实现 Iris `pipeline/IrisRenderingPipeline.java:1083-1087` |
| **色彩空间转换** | `GameRenderer.renderLevel` 的 **TAIL**：`finalizeGameRendering()` → `colorSpaceConverter.process(Minecraft.getInstance().getMainRenderTarget().getColorTextureId())` | Iris `mixin/MixinGameRenderer.java:461-464`；Oculus `:462-464`；实现 `IrisRenderingPipeline.java:1090-1092`；转换器 `pathways/colorspace/ColorSpaceFragmentConverter.process(int)`（`ColorSpace.SRGB` 时直接 return；否则渲染到 swap 纹理再 `copyTexSubImage2D` **回写主画面颜色纹理**） |
| 无光影包时 | `VanillaRenderingPipeline.finalizeLevelRendering()` / `finalizeGameRendering()` 都是空实现（`// stub: nothing to do here`） | `pipeline/VanillaRenderingPipeline.java:106-114` |

**关键对照（本文的核心事实）**：

- 光影的「最终合成」在 `LevelRenderer.renderLevel` 尾部 —— 我们的 lane 渲染与 master 调色在**它之后**（不同方法、不同层级）；
- 光影的「色彩空间转换」在 `GameRenderer.renderLevel` 的 **TAIL** —— 与我们的挂点**在同一个返回点**（我们 `@At("RETURN")`，它 `@At("TAIL")`，同一处 RETURN 指令）。**两者的执行先后不由我们控制**（两边都没写显式 `priority`），**未实测** → §3-①/③。

### 2.4 光影的帧缓冲 / 管线结构（example 源码能读到的程度）

| 结构 | 事实 | 证据 |
|---|---|---|
| 管线对象 | 每帧 `LevelRenderer.renderLevel` HEAD 取管线、RETURN 前置空（`pipeline = null`）；取管线走 `PipelineManager.preparePipeline(NamespacedId)` | `mixin/MixinLevelRenderer.java`（:118-123）、`pipeline/PipelineManager.java:27` |
| 合成链 | `CompositeRenderer.renderAll()`：按 pack 的 composite pass 数组顺序逐 pass 渲染（含 compute），**没有「无事可做就早退」**；结尾 `Minecraft.getInstance().getMainRenderTarget().bindWrite(true)` + 解绑全部纹理单元 | `pipeline/CompositeRenderer.java`（:230 起，结尾 :289） |
| final pass | `FinalPassRenderer.renderFinalPass()`：有 final pass 就走着色器全屏 quad；**没有** final pass 时用 `copyTexSubImage2D` 把 `colortex0` 拷进**当前 `getMainRenderTarget()` 的颜色纹理**；缓存 `lastColorTextureId` / 颜色缓冲版本号（变了就重挂 attachment）；结尾 `main.bindWrite(true)` | `pipeline/FinalPassRenderer.java`（:119-122 缓存、:198-201 取 main、:243-270 两条分支（`copyTexSubImage2D` 在 :269）、:296 结尾） |
| 主画面归属判定 | `RenderTarget.bindWrite(Z)` 的 RETURN 会判定「绑的是不是 `getMainRenderTarget()`」→ `pipeline.getRenderTargetStateListener().setIsMainBound(...)`；而 `shouldOverrideShaders()` = `isRenderingWorld && isMainBound`（即**只有主画面绑定时才接管着色器**） | `mixin/state_tracking/MixinRenderTarget.java:41-48`；`pipeline/IrisRenderingPipeline.java:1237-1239` / `setIsMainBound`（:1266-1268） |
| 深度 | final pass 注释明确：光影**复用主 framebuffer 的深度缓冲**（不另建、避免与用模板缓冲的模组冲突） | `pipeline/FinalPassRenderer.java:204-218`（注释） |
| 色彩空间 | 只在 pack 声明 `supportsColorCorrection()` 时安装转换器；转换器把结果 `copyTexSubImage2D` 写回主画面颜色纹理（**原地改写**） | `IrisRenderingPipeline.java:524-543`；`pathways/colorspace/ColorSpaceFragmentConverter.java`（`process`） |
| Sodium 兼容层 | 光影自带的 Sodium 兼容层规模：`src/sodiumCompatibility` 子树 Iris 178 个条目 / Oculus 179 个条目（`ls -R` 计数，含目录）；[multi-camera-rendering](./multi-camera-rendering.md) §7.1 记的是 Iris 侧 83 个 Java 文件 / 4788 行 | `ls -R example/{Iris-1.20.1,Oculus-1.20.1-new}/src/sodiumCompatibility` |

### 2.5 两条「已核实、不构成问题」的边界

1. **无光影包时（装了 Iris/Oculus 但没启用包）**：`VanillaRenderingPipeline` 的 `finalizeLevelRendering()` / `finalizeGameRendering()` 都是空实现（`pipeline/VanillaRenderingPipeline.java:106-114`）→ 我们的路径应当与「没装光影」一致。**（未实测：只是源码事实，未在游戏内验证过「装了 Iris 但未启用包」这一档。）**
2. **光影不改我们的注入目标方法**：Iris / Oculus 对 `GameRenderer.renderLevel`、`GameRenderer.render`、`LevelRenderer.setupRender` 用的是 `@Inject` / `@ModifyVariable` / `@ModifyArg` / `@Redirect`（如 `MixinLevelRenderer` 的 `ModifyVariable(method="renderSky")`、`MixinGameRenderer` 的一批 `getXxxShader` `@Inject`），**没有对这几个方法的 `@Overwrite`** → 我们的 `defaultRequire: 1` 注入点不会被它们抹掉。**（仅核实了光影自身；第三方组合未验证。）**

### 2.6 现状断言的文档口径澄清（两处）

1. `screen-color-adjust.md` §4 写「Iris / Oculus 的 `finalizeGameRendering()` 挂在 `GameRenderer.renderLevel` 的 `TAIL`；Iris 的最终合成在 `LevelRenderer.renderLevel` 尾部 —— **都早于原版那一步**」。
   - **对「最终合成」成立**（`LevelRenderer.renderLevel` 尾部早于 `GameRenderer.renderLevel` 的返回点，也早于原版 `postEffect`）；
   - **对「色彩空间转换」不成立**：它与我们**旧**的挂点（`GameRenderer.renderLevel` 的 RETURN）**在同一处返回指令**，先后未定（§3-①）。→ **已解决（2026-10-08）**：调色挂点已后移出 `renderLevel`（原版 RPOST 之后、GUI 之前），与色彩空间转换分处不同阶段、顺序可控；见 §2.7。
2. `multi-camera-rendering.md` §7.1 把 Iris / Oculus 与 Sodium / Embeddium 并列在「检测到就禁用」的策略里，但**已落地的检测列表只有 Sodium 家族**（§2.1）——即「策略」与「实现」目前不一致：光影下副画面**没有**被禁用。本文 §4 的选项 A/C 正是要在这条线上做决定。

### 2.7 主画面挂点兼容性结论（2026-10-08）

> 本节只结清**主画面 master 调色挂点**这一件事（它此前是 §2.6-1 / §3-① / §3-③ 的未决项）。本文其余部分（光影下**副画面**能否渲染、成本、§4 策略选项）仍是待调查项，本节结论不覆盖它们。

2026-10-08 用户裁决把 master 调色挂点从「`GameRenderer.renderLevel` 的 RETURN（合成后立即）」后移到「**原版后处理链之后、GUI 之前**」（`mixin/GameRendererMixin.onWorldPostProcessed`，注入目标 = `RenderTarget.bindWrite(Z)V`；见 [screen-color-adjust](./screen-color-adjust.md) §4「渲染挂点」）。后移后与光影的关系是确定的：

| 结论 | 依据 |
|---|---|
| **读到的是光影处理后的画面，不被跳过** | Iris 的最终合成 `finalizeLevelRendering()`（`compositeRenderer.renderAll()` + `finalPassRenderer.renderFinalPass()`）挂在 `LevelRenderer.renderLevel` 的 **RETURN**（§2.3）——比本挂点（`GameRenderer.render` 内、`GameRenderer.renderLevel` 返回**之后**）更内层、更早；本 pass 的输入已是光影管线输出的画面。 |
| **不双重应用、顺序可控** | 本挂点每帧只跑一次，且**已与 Iris 的色彩空间转换完全解耦**：`finalizeGameRendering()` 挂在 `GameRenderer.renderLevel` 的 TAIL（§2.3）——旧挂点与它在**同一处返回指令**上、先后未定（§2.6-1）；后移后两者分处不同阶段（`GameRenderer.renderLevel` 内 vs `GameRenderer.render` 内），先后由原版语句顺序显式保证。默认 `ColorSpace.SRGB` 下该转换器直接 return（no-op，§2.3），更不构成影响。 |
| **优化模组无干涉** | Sodium / Embeddium / Starlight / Lithium 都不改 `GameRenderer.render` 的「后处理链 → GUI」这一段（Sodium 家族改的是区块渲染与 `setupRender`，见 §2.1 的 Mixin 插件跳过规则）——本挂点不受它们影响。 |
| **检测口径** | `IrisApi.getInstance().isShaderPackInUse()`（`net.irisshaders.iris.api.v0.IrisApi`，API v0.0；Oculus 同 API，`provides=["iris"]`）。它已覆盖「装了光影但未启用包 / 包编译失败」这一档（无管线 → `false`）→ 无光影包时走原版路径，无需额外分支。**接入方式**（软依赖：标记类存在性检测 + 反射，避免硬依赖）见 §5.2；当前仓库尚未接这条检测（§2.1）。 |

---

## 3. 不知道的（待调查问题清单）

> 每条：**问题** → **为什么重要** → **调查方法** → **预期产出**。方法里的「挂点实测」= 加日志 / 用现成的 `ICINEMATICS_CAPTURE` 调试捕获（`LaneDebugCapture`，输出到 `<gameDir>/lane-captures/`，见 [multi-camera-rendering](./multi-camera-rendering.md) §12.6）出图对比，**不是**改设计。

### ① 我们的挂点（lane 渲染）与光影各阶段的精确先后

- **问题**：在同一帧里，光影的 `finalizeLevelRendering()`（合成）、`finalizeGameRendering()`（色彩空间）、我们 `GameRenderer.renderLevel` RETURN 上的「lane 渲染 + 合成」，实际执行顺序是什么？特别是**同一返回点上的两个注入**（我们 `@At("RETURN")`、光影 `@At("TAIL")`）谁先。**（master 调色已不在这个返回点上——2026-10-08 后移到 RPOST 之后、GUI 之前，与色彩空间转换的顺序已确定、可控，见 §2.7；本节余下的问题只剩 lane 渲染与光影各阶段的先后。）**
- **为什么重要**：顺序决定 lane 渲染看到的是「光影处理后的主画面」还是「原版主画面」。顺序不定，「兼容」就无从定义。（master 调色与色彩空间转换的先后已确定，见 §2.7。）
- **调查方法**：① 读码已给出候选顺序（§2.3，合成早于我们、色彩空间同点）；② 挂点实测——在 `LaneRendererMixin.ic$worldRendered` 与 Iris/Oculus 的注入各打一行带帧号 / 时间戳的日志（光影侧可用一个临时附属模组或 `Mixin` 覆盖，不改光影本体），跑同一帧比对顺序；③ 直接看注入后的字节码（`javap -c` 反汇编 `GameRenderer.renderLevel`）确认两个回调的调用次序。
- **预期产出**：「挂点先后实测报告」——一张表：{光影阶段 × 我们的阶段} 的确定顺序，含「同一返回点谁先」的结论与证据（日志 + 反汇编）。

### ② 光影下 `renderLevel` 内做第二遍渲染是否可行（帧缓冲绑定、管线状态、深度 / 模板）

- **问题**：在光影启用的帧里，我们每 lane 再调一次 `LevelRenderer.renderLevel`，会发生什么？具体怀疑点（均已核实为「光影的注入会随每一次 `LevelRenderer.renderLevel` 触发」）：
  - 每次调用都会重新走 `iris$setupPipeline`（HEAD，重取管线 / 改 `cullingFrustum` / 改 `smartCull`）→ `beginLevelRendering()` → **阴影 pass**（每 lane 一次完整阴影渲染）→ `finalizeLevelRendering()`（每 lane 一次完整 composite 链 + final pass）；
  - 我们的 lane pass 期间 `Minecraft.mainRenderTarget` **被指向 lane FBO**，而光影多处取 `Minecraft.getInstance().getMainRenderTarget()` 当「主画面」（`CompositeRenderer.renderAll` 结尾 :289、`FinalPassRenderer.renderFinalPass` :198-201 / :296、`MixinRenderTarget` 的 `isMainBound` 判定）→ 光影可能把 lane FBO 当成主画面（**也可能因为时序判定不成立而不接管**，两种都可能，必须实测）；
  - 深度 / 模板：光影的 final pass 明确**复用主 framebuffer 的深度缓冲**（`FinalPassRenderer` :204-218 注释），而 lane FBO 是自带 depth 的 `TextureTarget`（`LaneRenderer.offscreenTarget`）→ 语义不同；`entityTarget`（描边）也是共享的，我们已在 lane 块里显式补主画面描边（§2.2）。
- **为什么重要**：这是「能跑对 / 画面错 / 崩」的判定题，也是 §4 选项 A 与 B 的分水岭。
- **调查方法**：① 实机（`sh gradlew :fabric:runClient --args='--quickPlaySingleplayer <世界名>'` + Iris + 一个轻量光影包），跑四象限脚本（`cinematics/release/quadrant.json`）并开 `ICINEMATICS_CAPTURE` 出图：看 lane raw / composited / 窗口终帧是否合理（是否缺块、是否黑、是否重复特效、是否只有一条 lane 正常）；② 观察日志与崩溃报告（光影对状态有断言 / 抛异常时会在日志里出现）；③ 分档定位：先关阴影（换无阴影 pack）、再关 composite、最后全开，确定哪一段开始出错；④ 若「每 lane 一次光影全链」成立，用 profiler 段名（`iris_final` 等）确认它确实被调用了 N 次。
- **预期产出**：「可行性结论」——在 {光影开 / 关 × pack 特性档} 下，lane 渲染是「可用 / 有条件可用 / 不可用」的结论 + 出错时的最小复现与根因定位（哪一段光影代码）。

### ③ 色彩空间（光影的色彩空间转换与我们的调色 pass 的相互影响）

- **问题**：Iris 的 `colorSpaceConverter.process(...)` 会把主画面颜色纹理**原地**改写（`copyTexSubImage2D` 回写，`ColorSpaceFragmentConverter`）；我们的 master 调色（`ColorAdjustPass`：主画面 → 中转 → 主画面）在它之前还是之后？若在其之前，我们的输出会被再做一次线性↔sRGB 变换（参数语义被改）；若在其之后，我们的输出不经过该变换（画面的「色彩空间位置」与其它像素不一致）。
- **为什么重要**：调色的所有参数（亮度 / 对比度 / 饱和度 / 曲线）都是**对某个色彩空间成立**的；顺序错 → 参数效果整体偏移（同一个 saturation 在两种顺序下视觉结果不同），并且 lane 画面与主画面可能处在不同空间。
- **调查方法**：① 依赖 ① 的顺序结论；② 数值实测——造一个恒等但可辨识的调色参数（例如只把 R 增益设成明显值），在光影开 / 关两种情况下用 `ICINEMATICS_CAPTURE` 读回同一帧的窗口终帧，比较同一像素的数值；③ 读码确认 `ColorSpace.SRGB` 时转换器直接 return（该分支下这条问题消失），并确认哪些 pack 会 `supportsColorCorrection()`。
- **预期产出**：「调色在光影下的语义定义」——明确 master 调色作用于哪个空间、需要什么顺序保证（或需要在光影下禁用 / 换成对光影输出的后置 pass）。

### ④ Sodium / Embeddium 下两遍 / 帧的 `frame++` 语义

- **问题**：`SodiumWorldRenderer.setupTerrain(..., frame++, ...)` 的 `frame` 形参每调用一次自增，Sodium 内部用它判断「本帧是否已见过」；一帧两次 `setupTerrain`（主 pass + lane pass）会让计数语义变化——**是否引起区块渲染错误未验证**（[render-second-pass-cost](./render-second-pass-cost.md) §3.2 已标未验证；Embeddium 侧该形参已标 `@Deprecated(forRemoval = true)`，只传给 `renderSectionManager.update`）。
- **为什么重要**：这条决定「优化模组 + 光影」组合下能不能解禁副画面；也决定我们是否要在 `LaneRenderer.isUnavailable()` 上放宽（目前是**直接禁用**，所以这条现在是「被规避」而非「已解决」）。
- **调查方法**：① 实机对照（Sodium / Embeddium 开 + 多 lane）：看是否有缺块 / 闪块 / 区块不更新（与我们在原版下遇到过的「倾斜裁切矩形」区分开）；② 反汇编 `WorldRendererMixin` 与 `SodiumWorldRenderer.setupTerrain` 的 `frame` 实际用途（`example/embeddium-20.1-forge`、`example/sodium-1.20.1-stable`）；③ 若确认有问题，评估「每帧只增一次」的绕法（在 lane pass 期间给 Sodium 一个固定的 frame 值）。
- **预期产出**：「优化模组 + 多遍渲染的 `frame` 语义结论」+ 是否需要保持/放宽 `isUnavailable()` 的判定。

### ⑤ Rubidium 兼容

- **问题**：Rubidium（Forge 侧 Sodium 系）在我们的检测里只按**类名标记**（`me.jellysquid.mods.sodium.client.SodiumClientMod`）覆盖，且 `example/` 下**没有 Rubidium 源码**——它的 `@Overwrite` 面、`setupTerrain` 签名、与 Embeddium 的差异都**未验证**。
- **为什么重要**：Forge 1.20.1 玩家群大量使用 Rubidium + Oculus 组合；若 Rubidium 的标记类名 / 包名不同，检测会漏（漏了就是「不 warn 且可能画面错」）。
- **调查方法**：① 取 Rubidium 的发行 jar，用 `read` 直接看 jar 内类名与 mixin 配置（`META-INF/mods.toml` / `*.mixins.json`），核对标记类是否存在、`renderChunkLayer` / `setupRender` 的 `@Overwrite` 是否与 Embeddium 一致；② 实机跑一次（Rubidium + Oculus）看日志里是否出现我们的 warn、画面是否正常。
- **预期产出**：「Rubidium 检测与行为结论」+ 检测标记列表是否需要补类名。

### ⑥ 性能影响（光影下副画面成本）

- **问题**：光影下每 lane 一遍世界渲染的实际成本是多少？若 §3-② 的怀疑成立（每 lane 触发一次光影全链：阴影 + composite + final），成本量级会从「+0.56 ms/lane（无光影轻场景，[render-second-pass-cost](./render-second-pass-cost.md) §4.2）」跳到「一 lane ≈ 一整个光影帧」。
- **为什么重要**：`multi-camera-rendering.md` §6 的推荐值（不设上限、只给推荐）建立在无光影的实测曲线上；光影下若成本爆炸，推荐值 / 档位口径要另立一档（或直接落回 §4 选项 A）。
- **调查方法**：① 复用 `render-second-pass-cost.md` §6.3 的测量方案（profiler 段 + `System.nanoTime()`，固定位姿 / 世界 / 视距，≥600 帧、丢预热）；② 矩阵 = {光影关 / 光影开} × {lane 0 / 1 / 4} × {内容档 WORLD_ONLY / FULL}；③ 光影开时额外记录 profiler 里 `iris_final` / 阴影段是否随 lane 数线性增长；④ 外部佐证（可选）：用「阴影开 / 关」的 FPS 差估「每帧一遍世界渲染」的经验占比——**只作量级参照**，不作正确性证据。
- **预期产出**：「光影下成本数据表 + 推荐值修订建议」。

### ⑦ Oculus 与 Iris 的差异面

- **问题**：Oculus 是 Iris 的 Forge 移植（同构），但差异面必须列清：加载器（`javafml` vs Fabric）、`modId` / `provides`、mixin 配置文件命名（`mixins.oculus*.json` vs `mixins.iris*.json`）、额外的兼容 mixin（Oculus 多 `compat.dh` / `compat.pixelmon`）、**对 Embeddium 的强制配置**（`[mods."sodium:options"]` 关掉 sky / entity / gui.font 三个 mixin 特性 + 可选依赖 `embeddium [0.3.1,)`）、以及**行级差异**（`MixinLevelRenderer` 285 vs 269 行、`IrisRenderingPipeline` 1323 vs 1309 行、`ShadowRenderer` 730 vs 729 行）。
- **为什么重要**：我们的检测 / 适配如果按 Fabric 侧（Iris）设计，可能在 Forge 侧（Oculus + Embeddium）组合上失效；反过来 Oculus 的 Embeddium 配置会改变 Embeddium 的行为（例如 sky/entity 渲染不走 Embeddium 的 mixin），影响 §3-②④ 的结论在两侧的可移植性。
- **调查方法**：① 逐文件对照两侧同构文件（`mixin/MixinGameRenderer.java`、`mixin/MixinLevelRenderer.java`、`pipeline/IrisRenderingPipeline.java`、`shadows/ShadowRenderer.java`）并记录差异；② 核对两侧 `META-INF` / mixin 配置 / `mods.toml` 的声明差异；③ 实机各跑一次（Iris+Fabric / Oculus+Forge）做同一条用例，比对日志与出图。
- **预期产出**：「Iris / Oculus 差异清单」+ 结论（一套适配能否覆盖两侧；不能的话差异点在哪）。

### 读码新发现的补充项（本次核实后新增，非用户点名但会影响结论）

**⑧ 遮挡剔除决策互相覆盖**：光影在 `LevelRenderer.renderLevel` HEAD 每 pass 赋 `Minecraft.smartCull = !pipeline.shouldDisableOcclusionCulling()`（`MixinLevelRenderer.java:96`），而我们在 `setupRender` HEAD / RETURN 包夹强制 `smartCull=false`（`CinematicOcclusion` + `LevelRendererMixin`）。两者作用区间重叠 → 光影下「倾斜裁切矩形」是否回归？**调查方法**：实机对比（光影开，多 lane）看副画面是否出现沿区块网格的斜切缺块；**产出**：光影下遮挡剔除归属结论（我们的包夹是否仍需保留 / 是否要与光影的赋值合并）。

**⑨ `isMainBound` 与主画面指向交换**：我们每 lane 把 `mainRenderTarget` 指向 lane FBO（`MinecraftAccessor.ic$setMainRenderTarget`），而光影的 `shouldOverrideShaders()` 依赖 `isMainBound`（由 `RenderTarget.bindWrite` 的 RETURN 判定「是不是 `getMainRenderTarget()`」）。交换期间 `fbo.bindWrite(true)` 会让光影判定「主画面已绑定」→ 可能接管着色器；而 `entityTarget.clear()` 等其它 target 绑定又会让它判定「不是主画面」→ 关闭着色器。**调查方法**：在 lane pass 内读 `shouldOverrideShaders` 的等效状态（日志），并观察 lane 画面是否被光影着色器改写（对比 raw 出图与无光影时的 raw 出图）；**产出**：lane pass 内光影接管与否的确定结论（这直接决定 lane 画面是「光影画面」还是「原版画面」）。

**⑩ `defaultRequire: 1` 的注入点存活**：我们的注入点是 `GameRenderer.renderLevel` / `GameRenderer.render` / `LevelRenderer.setupRender` / `LevelRenderer.renderLevel`（Accessor / Invoker）；`defaultRequire: 1` 下任何一处被第三方 `@Overwrite` 都会在**启动期**失败。光影自身不改这些方法（§2.5-2），但**其它渲染类模组**（未穷举）未验证。**调查方法**：启动期日志 + 一份「常见渲染模组 × 是否 @Overwrite 我们的目标方法」清单（只读 jar / 源码核对）；**产出**：注入点风险清单（是否需要给关键注入点降级为 `require = 0` + 运行期自检）。**顺带已核实**：光影自己会用显式 `priority` 与 `@Group` 解决同类冲突（`mixin/shadows/MixinPreventRebuildNearInShadowPass.java:26`，`@Mixin(value = LevelRenderer.class, priority = 1010)`，javadoc 明说「用 `@Group` 避免脆弱的 Mixin 插件」）——即「用 `priority` 钉顺序」在光影侧是**既有做法**，我们的 §3-① 顺序问题可以走同一条路；该文件在当前副本里类体为空（javadoc 提到的注入不在这份源码里），故不构成与我们的注入冲突。

---

## 4. 策略选项（供调研结论落地）

> 四个选项**不是互斥**——A 是现状、C 是 A 与 B 之间的档位、D 是调研中可能发现的其它路径（例如「按 pack 特性分档」「把 lane 交给光影管线渲染」）。选哪个由 §5 的步骤 6 决策，**现在不选**。

| 选项 | 内容 | 代价 | 风险 | 工作量（粗） | 需要的调研结论 |
|---|---|---|---|---|---|
| **A. 保持现状**（光影下副画面禁用 + warn，文档化限制） | 把光影也纳入 `LaneRenderer.isUnavailable()` 的检测（按模组存在性或「光影包启用」），warn 文案与文档同步 | 光影用户完全失去多相机画面（功能损失最大） | 低（行为确定、与优化模组策略一致）；**但需要先确认「光影下确实是坏的」**——否则是白丢功能 | **S**（检测 + warn + 文档；检测口径见 §5.2 待定项） | ②「不可用」结论，或「可用但画面/语义错」 |
| **B. 完整适配**（光影下副画面可用） | 让 lane 在光影下产出正确画面：明确挂点先后、必要时调整 lane pass 的调用方式（避免每 lane 触发整套光影链）、状态保存/恢复补齐（帧缓冲、深度、管线状态）、色彩空间语义对齐 | 高：光影状态机复杂，且要随 pack 特性分档；每 lane 一遍光影链的成本可能不可接受 | 高：光影版本演进会持续打破适配；`example/` 只有 1.20.1 的两份源码，多版本成本更高 | **L**（多轮实机 + 状态层适配；可能跨版本） | ①②③⑥⑧⑨ 全部有结论，且②的结论是「可用」 |
| **C. 部分适配** | 两种形态（可组合）：**C1** = 只保证 **master 调色**在光影下正确（lane 仍禁用，但「播放时画面颜色对」）；**C2** = 只保证**主画面路径**（不依赖副画面，例如未来单相机运镜）在光影下正确，副画面禁用 | 中：把「调色」与「副画面」两条线拆开，各自定策略 | 中：C1 需要 ③ 的结论；C2 只是 A 的一个子集（收益 = 明确承诺，不增加功能） | **M**（C1：一个后置 pass 的挂点 / 语义修正；C2：检测 + 文档） | ③（C1 必需）、②（决定副画面是否一律禁用） |
| **D. 其他（调研中可能发现）** | 候选形态（待调研证实/证伪）：**D1** 按 **pack 特性分档**（有 / 无 composite、有 / 无 shadow、是否 color correction → 分别给「可用 / 禁用」）；**D2** 区分「装了光影」与「启用了光影包」（`VanillaRenderingPipeline` 空实现那一档天然等价无光影，见 §2.5-1）；**D3** 让 lane **走光影管线**渲染（每 lane 一次完整光影帧——若②的实测证明它「正确但贵」，可用档位 / 只给编辑器预览）；**D4** 只在光影下把副画面降级为「原版画面上屏」（不追求光影一致性） | 取决于形态；D2 近乎零成本，D1 需要按 pack 枚举，D3 成本高 | D1 的档位组合可能爆炸（结论要收敛成有限档）；D3 依赖光影内部状态可安全重入 | **S–L**（按形态） | ②（分档依据）、③（D1 的 color correction 档）、⑥（D3 的成本） |

**共同前置**：无论选哪个，都要先有 ①②③ 的结论——A 需要「确实坏」的证据，B 需要「怎么才不坏」，C/D 需要「坏在哪一段」。

---

## 5. 调研的落地步骤

> 每步一个可交付物；步骤顺序 = 依赖顺序（①/② 最先，因为 §4 的所有选项都等它们）。

| # | 步骤 | 交付物 | 依赖 |
|---|---|---|---|
| 1 | **挂点先后实测**：日志 / 反汇编确认「光影各阶段 × 我们的挂点」顺序（§3-①），并顺带确认 lane pass 内光影是否接管着色器（§3-⑨） | 「挂点先后实测报告」（表 + 证据：日志片段、`javap` 反汇编） | — |
| 2 | **可行性实测**：光影开 / 关 × pack 特性档（无阴影 / 无 composite / 全开），跑四象限脚本 + `ICINEMATICS_CAPTURE` 出图，分档定位出错点（§3-②） | 「可行性结论」（可用 / 有条件可用 / 不可用 + 最小复现 + 根因定位） | 步骤 1 |
| 3 | **色彩空间与调色语义**：确定 master 调色在光影下的作用空间与顺序（§3-③），必要时定「光影下的调色挂点修正」 | 「调色在光影下的语义定义」（含顺序要求与验证出图） | 步骤 1 |
| 4 | **性能测量**：{光影开 / 关} × {lane 0/1/4} × {内容档} 的成本表（§3-⑥），并确认 `iris_final` / 阴影段是否随 lane 数增长 | 「光影下成本数据表 + 推荐值修订建议」 | 步骤 2 |
| 5 | **交叉项收尾**：优化模组的 `frame` 语义（§3-④）、Rubidium 检测（§3-⑤）、Iris/Oculus 差异清单（§3-⑦）、遮挡剔除归属（§3-⑧）、注入点风险（§3-⑩） | 「组合矩阵结论 + 检测规则修订草案」（含 Rubidium 标记类名核对结果） | 步骤 2（可并行） |
| 6 | **策略决策**：按 §4 选 A / B / C / D（可组合），并把结论写回各文档 | 「策略决策记录」+ 本文件 §4 定稿 + 关联文档（`multi-camera-rendering` §7.1/§12.5、`render-routes` §4、`screen-color-adjust` §5、`render-second-pass-cost` §7）同步更新 | 步骤 2–5 |
| 7 | **（若选 B / C / D 的非「仅文档」形态）实现与验证**：挂点调整 / 状态保存恢复补齐 / 检测与开关 / 性能档位 | 「实现 + 实机验证记录」（含回归：无光影下行为不变——默认零差异） | 步骤 6 |

### 5.1 可能的问题（风险）

- **每 lane 一次光影全链**：若 §3-② 证实「每 lane 触发阴影 + composite + final」，成本量级是「一 lane ≈ 一个光影帧」，B 与 D3 都可能因此不划算（结论要么降档要么禁用）。
- **光影内部的「主画面」判定被我们的交换影响**（§3-⑨）：可能出现「lane 被光影接管（画面是光影画面但成本翻倍）」或「光影对 lane 半接管（状态错乱）」两种，都不好；需要确定后显式处理（例如 lane pass 期间临时把 `getMainRenderTarget()` 的可见性 / 或改用别的方式指向 lane 输出）。
- **色彩空间顺序不定**（§3-①③）：我们与光影在同一返回点注入，且两边都没有显式优先级；**不能靠「约定」解决**，只能实测 + 必要时用 `priority` 或换挂点把顺序钉死。
- **深度 / 模板语义差异**：光影 final pass 复用主 framebuffer 深度（`FinalPassRenderer` 注释），lane FBO 自带 depth；描边（`entityTarget` 是共享的）在 lane 块里已被我们显式处理（§2.2），光影下是否仍成立未验证。
- **pack 特性发散**：composite / final / shadow / color correction 的有无会分叉出多个行为档，结论容易变成「看 pack」——需要收敛成有限档位（这是 §4-D1 的主要风险）。
- **版本漂移**：`example/` 只有 1.20.1 的 Iris / Oculus 源码；结论对新版本光影不自动成立（多版本支持是目标，见 README「版本原则」）。
- **优化模组组合**：Oculus 会强制关掉 Embeddium 的 sky / entity / gui.font mixin（`mods.toml` 的 `[mods."sodium:options"]`），Forge 侧的组合行为与 Fabric 侧（Iris + Sodium）不同 → 两侧结论要分别验证。
- **`defaultRequire: 1`**：任何第三方改写我们的注入目标方法 → 启动期失败（不是静默降级）；光影自身不触发（§2.5-2），组合未穷举。

### 5.2 待定项

- **策略选择**（A / B / C / D 及其组合）——步骤 6 定；本文件现在不选。
- **检测口径**：按「光影模组存在」还是按「光影包已启用」？（§2.5-1 表明后者的一档天然等价无光影；但「装了但没启用」的判定要读光影 API，可能引入硬依赖——`IrisApi` / `net.irisshaders.iris.Iris` 的存在性检测 vs 反射调用。）**→ 主画面挂点一侧已定（2026-10-08）：用 `IrisApi.getInstance().isShaderPackInUse()`，见 §2.7；本条剩下的只是「副画面策略」是否复用同一口径。**
- **是否给用户配置开关**（例如「光影下强制启用副画面（自担风险）」）——与「不设硬上限、只给推荐」的既有口径是否一致，待定。
- **lane 画面在光影下的目标形态**：要「与主画面一致的光影画面」（贵、语义复杂）还是「原版画面」（便宜、但副画面与主画面美术不一致）？这是产品问题，调研结论只提供可行性边界。
- **Rubidium 结论来源**：本仓无源码，只能靠 jar 核对 + 实机（§3-⑤）；是否需要专门环境。
- **性能档位是否按光影另立**（`multi-camera-rendering.md` §6 的推荐值目前是无光影口径）。
- **是否要把「光影」也并入 `isUnavailable()` 的同一套机制**，还是单开一条「光影下副画面降级」路径（与优化模组的「@Overwrite 导致不可靠」是**不同原因**，文案与检测位置可能要分开）。

---

## 6. 证据索引

| 断言 | 出处 |
|---|---|
| 检测只列 Sodium 家族两个标记类；命中即不渲染 + warn 一次（缓存） | `common/.../client/lane/LaneRenderer.java`：`RENDER_OPTIMIZER_MARKERS`（:107-110）、`findRenderOptimizer()`（:464-473）、`isUnavailable()`（:452-461）、`render(...)` 早退（:276-278） |
| 全树无 Iris / Oculus / shaderpack 检测 | `common/src/main/java` 全树检索（`iris\|oculus\|shaderpack\|Iris`，不分大小写）零命中 |
| 加载器侧跳过 `LevelRendererMixin`（Sodium / Rubidium / Embeddium） | `fabric/.../mixin/ImmersiveCinematicsMixinPlugin.java`、`forge/.../mixin/ImmersiveCinematicsMixinPlugin.java`（`shouldApplyMixin` + `RENDER_OPTIMIZER_MOD_IDS`）；`common/src/main/resources/immersive_cinematics.mixins.json`（`plugin`、`injectors.defaultRequire`） |
| 我们的挂点：帧首 / lane 渲染 + 合成（MCOMP）/ master 调色（RADJ，2026-10-08 后移）/ 每 lane 一遍 / 主画面描边 | `mixin/GameRendererMixin.java`（`onRenderFrameStart`、`onWorldPostProcessed`）、`mixin/LaneRendererMixin.java`（`ic$worldRendered`）、`client/lane/LaneRenderer.java`（`render` :269-331、`renderLane` :334-400、`offscreenTarget`）、`client/post/ColorAdjustPass.java`（类 javadoc） |
| 遮挡剔除整帧包夹 | `camera/CinematicOcclusion.java`、`mixin/LevelRendererMixin.java`（:106-119） |
| 光影挂点：管线准备 / 世界开始 / 阴影 pass / 最终合成 / 色彩空间 | Iris `mixin/MixinLevelRenderer.java`（HEAD :75-96、CLEAR 后 :107-113、RETURN :118-123、renderSky 前 :140-143）、`mixin/MixinGameRenderer.java:461-464`；Oculus 对应 `:66-87`、`:98-104`、`:109-114`、`:125-128`、`:462-464` |
| 合成链 / final pass 无早退、取 main 的多个点、复用主深度、色彩空间原地回写 | `pipeline/CompositeRenderer.java`（:230 起、:289）、`pipeline/FinalPassRenderer.java`（:119-122、:198-218、:243-270、:296）、`pipeline/IrisRenderingPipeline.java`（:524-543、:1083-1087、:1090-1092）、`pathways/colorspace/ColorSpaceFragmentConverter.java`（`process`） |
| `isMainBound` 判定与 `shouldOverrideShaders` | `mixin/state_tracking/MixinRenderTarget.java:41-48`、`pipeline/IrisRenderingPipeline.java:1237-1239`、`:1266-1268` |
| 无光影包时空实现 | `pipeline/VanillaRenderingPipeline.java:106-114` |
| 光影不改我们的注入目标方法（只 `@Inject` / `@ModifyVariable` / `@ModifyArg` / `@Redirect`） | Iris `mixin/MixinLevelRenderer.java`、`mixin/MixinGameRenderer.java` 全文（无 `@Overwrite`） |
| Oculus 差异面：Forge / `modId=oculus` / `provides=["iris"]` / Embeddium 可选依赖与 `[mods."sodium:options"]` | `example/Oculus-1.20.1-new/src/main/resources/META-INF/mods.toml`；mixin 配置文件名对照（`mixins.oculus*.json` 含 `compat.dh` / `compat.pixelmon`） |
| Iris / Oculus 行级规模 | `wc -l`：`MixinGameRenderer` 474 / 474、`MixinLevelRenderer` 285 / 269、`ShadowRenderer` 730 / 729、`IrisRenderingPipeline` 1323 / 1309、`CompositeRenderer` 467 / 467；`src/sodiumCompatibility` 子树条目 178 / 179（`ls -R`） |
| 副画面成本与 `frame++` 未验证项（继承） | `plans/0.3.6/render-second-pass-cost.md` §3.2、§4.2、§6.3、§7 |
| 光影兼容的两条既有策略口径（继承） | `plans/0.3.6/multi-camera-rendering.md` §7 / §7.1 / §12.5；`plans/0.3.6/render-routes.md` §4；`plans/0.3.6/screen-color-adjust.md` §4（光影挂点段）/ §5-1 |

# 0.3.6 多相机渲染（PIP / 分屏 / 叠化）方案

**状态**: 🚧 进行中 —— **渲染底层已正式化**（2026-10-07：`client/lane/LaneRenderer`，每 lane 独立相机姿态 → 整尺寸渲染进共用离屏 FBO、顺序复用、默认零差异，见 §12）。**待开工**：上屏合成（取材 / 目标区域 / 不透明度 → [画面合成](./camera-composition.md)）、脚本 / lane 注册表接入（→ [并行播放](./parallel-playback.md)）。原型代码（`proto/QuadrantProto` + `QuadrantProtoMixin`）已按版本原则删除，只保留调试入口 `ICINEMATICS_QUADRANT=1|4|16|25…`
**目标版本**: 0.3.6

> 本文讨论“同一时刻渲染多个相机画面”以及“叠化（dissolve）”功能。
> 0.3.5 不做此功能；0.3.6 专门攻克。
>
> **分工**：本文只负责**渲染底层**——怎么把多个相机画面渲染成纹理（渲染管线、FBO、性能、模组兼容）。
> - 多张画面怎么铺到屏幕（取材 / 目标区域 / 不透明度的关键帧化）→ [画面合成](./camera-composition.md)
> - 相机从哪来（多脚本实例并行、lane 概念）→ [并行播放](./parallel-playback.md)

---

## 1. 需求定义

### 1.1 PIP / 分屏

- 不是“带边框的小窗 UI”
- 而是：同一帧内渲染多个相机画面并合成到屏幕
- 例如：左侧显示相机 1，右侧显示相机 2
- 类似监视器墙 / 分屏监控

### 1.2 叠化（Dissolve / Crossfade）

- 不是 morph（相机状态平滑过渡）
- 而是：过渡期内两张画面互相淡化叠加
- 上一个画面 alpha 从 1 → 0，下一个画面 alpha 从 0 → 1
- 剪辑软件里的“叠化”效果

---

## 2. 底层能力

两者共用同一个底层能力：

> **多相机同时渲染到多个 framebuffer，再合成到屏幕。**

- PIP / 分屏 = 每帧渲染 N 个相机，按布局合成
- 叠化 = 过渡期渲染 2 个相机，按 alpha 合成

---

## 3. 技术路线

### 3.1 不是自己搭渲染器

- 复用原版世界渲染管线：`GameRenderer.renderLevel` / `LevelRenderer.renderLevel`
- 用第二个 `Camera` 再调用一次原版世界渲染
- 输出到独立的 `RenderTarget`（FBO）
- 最后把多张纹理合成到屏幕

### 3.2 每帧流程（双相机）

```text
1. 准备相机 A、相机 B（位置/朝向来自脚本路径）
2. 渲染 A → FBO_A
3. 渲染 B → FBO_B
4. 恢复主 framebuffer
5. 合成：
   - 分屏：左右各画一张
   - 叠化：A 画完，B 按 alpha 叠加
6. 继续渲染 GUI / 主画面
```

### 3.3 叠化提前量（预热）

问题：
- 叠化开始那一刻，A 和 B 的画面必须同时存在
- B 不能到叠化开始才第一次渲染，否则第一帧没准备好

解决：
- 在叠化开始前提前渲染 B（预热）
- 预热开始时间 = 叠化开始时间 - 预热时长
- 因为脚本时间线已知，可以精确计算

注意：
- 不是渲染“未来的世界”——世界是动态的，未来帧不存在
- 是提前让 B 的 framebuffer 进入有效状态（warm-up）

**时间轴允许重叠后的结论（2026-10-07）**：**预热 = 重叠窗口本身**——B 从自己 clip 的开头（opacity 0 段）就在渲染，无需独立的预热调度机制；“预热时长”退化为作者的编排（B 开头延长多少），不再是框架参数（见 `camera-composition.md` §4）。

---

## 4. 关键难点

| 难点 | 说明 |
|---|---|
| 全局状态切换/恢复 | 当前 framebuffer（`RenderTarget.bindWrite`）、Camera（`GameRenderer.renderLevel` 里 `camera.setup`）、投影/视图矩阵（`RenderSystem.setProjectionMatrix` / `setInverseViewRotationMatrix`）、视口（`RenderSystem.viewport`）、雾（`FogRenderer.setupColor` / `levelFogColor` / `setupFog(FogMode.FOG_TERRAIN)`——调用点在 `LevelRenderer.renderLevel` 的 `clear` / `sky` / `fog` 三段）、着色器（`RenderSystem.setShader`）、深度缓冲、frustum（`LevelRenderer.prepareCullFrustum`）。逐项清单 + Iris 的对照做法见 `render-second-pass-cost.md` §1.3 |
| 第二个 Camera | 需要独立 Camera 实例，位置/朝向来自我们的路径（原型：每 lane 一个 `new Camera()`，见 `proto/QuadrantProto.ProtoCamera`） |
| 第二个 Frustum | 副相机要有自己的视锥体，否则裁剪错误——原版入口是 `LevelRenderer.prepareCullFrustum(PoseStack, Vec3, Matrix4f)`：每次调用**重建** `cullingFrustum`（`new Frustum(pose, projection)`）并按传入的相机位置 `Frustum.prepare(x,y,z)` |
| 副画面内容 | 通常只要世界，不要第一人称手/HUD——原版里手在 `GameRenderer.renderLevel` 的 `hand` 段、HUD/屏幕在 `GameRenderer.render` 的 `gui` 段，**都在 `LevelRenderer.renderLevel` 之外**，所以副画面只调 `LevelRenderer.renderLevel` 天然不含手/HUD |
| 渲染顺序 | 第二遍在主世界渲染之后、GUI 之前，还是先渲染第二遍再合成，需要定（原型实测挂点：`GameRenderer.renderLevel` 的 RETURN，即主世界渲染之后、`doEntityOutline` / `postEffect` / GUI 之前；见 `QuadrantProtoMixin`） |
| 光影/模组兼容 | 很多模组假设每帧只调一次 `renderLevel`，二次调用可能冲突（对照：Iris 每帧插一次完整第二遍——`ShadowRenderer.renderShadows`，注入点 `mixin/MixinLevelRenderer.java:140-143`，在 `renderSky` 之前） |
| 剔除 / 视锥 | 曾经遇到的 `Frustum.offsetToFullyIncludeCameraCube` 病态膨胀（jstack 实证挂死）来自**视锥与相机姿态不一致**（方块永远进不去）；实测只要 `prepareCullFrustum` 用**该 lane 自己的 pose + 相机位置**，四象限全部机位 3~5 步收敛——**不需要自建剔除方案**（JOML 语义离线模拟 + 实机验证，见 `quadrant-prototype-results.md` §3.4）。调用点：`LevelRenderer.setupRender` 内 `applyFrustum(new Frustum(frustum).offsetToFullyIncludeCameraCube(8))`（`LevelRenderer.java:831`） |
| 剔除状态是**共享**的 | 可见集合 / 遮挡剔除（`LevelRenderer.renderChunkStorage` + `renderChunksInFrustum`，BFS = `updateRenderChunks` 的队列扩张）在 `LevelRenderer` 上是单份共享状态；多 lane 若各自用不同 `smartCull` 会互相重建 → 画面来回闪。原型用"整帧统一决定"过渡（`camera/CinematicOcclusion.beginFrame` + `LevelRendererMixin` 包夹 `setupRender`）；正式实现要**每 lane 独立维护**（见 `quadrant-prototype-results.md` §3.5） |
| lane 自包含 | lane 的 `renderLevel` **加上所有 lane 级后处理**都必须在 lane 的 FBO 内完成：发光描边（`OutlineBufferSource`（实体段）→ 共享 `entityTarget` → `entity_outline` 后处理链 `entityEffect.process` → `LevelRenderer.doEntityOutline()` → `entityTarget.blitToScreen(窗口宽, 窗口高, false)`）必须在每 lane 渲染后立刻贴进该 lane 的画面，并屏蔽原版在 `renderLevel` 之后那次整屏调用（原版调用点在 `GameRenderer.render`，紧跟 `renderLevel` 之后）；同类：`postEffect`（见 `quadrant-prototype-results.md` §3.2） |
| lane 期间的主画面指向 | 渲染期间把 `Minecraft.mainRenderTarget` 指向该 lane 的 FBO——原版 `renderLevel` 内部（实体段 `entityTarget.clear()`；`RenderTarget.clear()` 内部调 `bindWrite(true)`）会把 **GL viewport 重置成该 target 的 view 尺寸**（`RenderTarget._bindWrite(bl)` → `GlStateManager._viewport(0, 0, viewWidth, viewHeight)`；`entityTarget` 是窗口尺寸），之后回绑的也是 `getMainRenderTarget()`（`bindWrite(false)`，**不回滚 viewport**）（见 `quadrant-prototype-results.md` §3.1） |
| 全局投影副作用 | `doEntityOutline() → RenderTarget.blitToScreen()`（内部 `_blitToScreen`）会把**全局投影矩阵**改成正交（`RenderSystem.setProjectionMatrix(ortho(0, i, j, 0, 1000, 3000), VertexSorting.ORTHOGRAPHIC_Z)`），lane 内调用后必须还原该 lane 的投影（原型还原为 `VertexSorting.DISTANCE_TO_ORIGIN`），否则下一个 lane 里走全局矩阵的绘制（实体 / 粒子 / 方块实体）会坏（见 `quadrant-prototype-results.md` §3.3） |
| 性能 | 两次世界渲染 ≈ 成本翻倍（定性上限）；**正式合成不降分辨率**（低分辨率只用于编辑器预览传输）。**原型实测（重场景：地表 + 20 多实体 + 发光描边，1.0x 同分辨率）**：单画面 ≈3.4–3.6 ms（结论表口径；CSV 每画面明细 3.2–4.7 ms），主画面 2–3 ms；1/4/16/25 画面稳态帧间隔 = 8 / 17 / 61 / 98 ms，**线性、主画面耗时不受影响**（见 `quadrant-perf/summary.md`） |

---

## 5. 脚本 / 数据模型

> **已被取代**：相机来源（多脚本实例 × 每实例多 lane）见[并行播放](./parallel-playback.md)；
> 上屏布局（分屏 / 叠化 / PIP 统一为“取材区域 / 目标区域 / 不透明度”的关键帧参数）见[画面合成](./camera-composition.md)。
> 本文不再维护数据模型草案，只保留渲染底层。

---

## 6. 性能策略

- **原则：不设硬上限，只给推荐**——lane 数由作者决定；我们给成本模型与实测数据（推荐值），帧率不做保证，压力由作者自行评估
- **实测（轻场景 1.0x 上限口径；探针分支已按"测完即删"删除，数字留档在 `render-second-pass-cost.md` §4.2）**：每多一个 lane ≈ **+0.56ms** 世界渲染（≈ 0.85× 主 `renderLevel`）；N=5 时世界渲染 ≈ 5.5×、帧间隔 1.2→4.0ms——线性，无超线性偏离
- **推荐（不是限制）**：可用 lane 数 ≈ (帧预算 − 基线帧时间) ÷ 单 lane 成本。轻场景单 lane ≈ +0.56ms（60fps 预算 16.6ms 下很宽裕）；重场景单 lane ≈ **3.4–3.7 ms**（原型实测：单画面 3.4/3.5/3.6 ms vs 主画面 2–3 ms；**0.85× 是轻场景探针口径，不是重场景**）。按 0.85× 口径外推：主渲染 5ms 时 ≈ +4.3ms/lane，2–3 个 lane 就吃掉 8–13ms——**超过推荐值不禁止，帧率自负**
- **分辨率：正式合成走原分辨率**（与主画面同分辨率，靠取材区域裁切再铺屏）；**低分辨率只用于编辑器预览传输**（省带宽）
- 叠化只在过渡期开双渲染，平时单渲染
- 分屏模式长期双渲染，必须做内容 / 视距档位（**不降分辨率**）
- 可选项：副画面跳过实体渲染 / 降低视距 / 关闭粒子

### 6.1 降耗设计（借鉴 Iris `ShadowRenderer` 第二视角渲染模式，自 0.4.0 G2 并入）

| 机制 | 说明 |
|------|------|
| 独立视锥 | 副相机矩阵 → 独立 frustum setup——窄视锥天然剔除（对照 Iris：`createShadowFrustum(...)` 建独立 frustum，再用副相机位置 `getFrustum().prepare(cameraX, cameraY, cameraZ)`；`ShadowRenderer.java:270` / `:393` / `:405`） |
| 只渲染区块层 | 只走 chunk layer（solid / cutout / cutoutMipped / translucent），不整段 renderLevel（对照 Iris：`invokeSetupRender` + `invokeRenderChunkLayer(solid/cutout/cutoutMipped)`；`ShadowRenderer.java:425` / `:460-464`，translucent 另在 `:535-537`） |
| 内容开关 | 副画面实体渲染可选（默认关）——跳实体 / 粒子 / 天空 / 天气（对照 Iris 的 pack 级开关：`shouldRenderTerrain` / `shouldRenderTranslucent` / `shouldRenderEntities` / `shouldRenderPlayer` / `shouldRenderBlockEntities`；`ShadowRenderer.java:75-80`） |
| 编辑器预览传输 | 帧推流用低分辨率（省带宽）；**正式合成不降分辨率** |
| 独立渲染缓冲 | 独立 RenderBuffers + 结束后恢复——不污染主渲染（对照 Iris：构造时 `new RenderBuffers()`，副 pass 期间 `levelRenderer.setRenderBuffers(buffers)`、结束 `setRenderBuffers(playerBuffers)`；`ShadowRenderer.java:142` / `:375-376` / `:570`。注：原版 `LevelRenderer.renderBuffers` 是 `private final`，需要自己的 accessor——Iris 的做法见 `mixin/LevelRendererAccessor.java`） |
| 缓冲复用 | 合成是**顺序**的（渲染一张 → 贴到屏幕 → 复用缓冲）→ **显存不随画面数增长**；只有叠化预热需要同时保留 2 张（原型实测：1 张共用缓冲跑完 25 个画面——`QuadrantProto.target(w,h)` 单例，见 `proto/QuadrantProto.java`） |
| 状态保存/恢复 | 剔除缓存与云纹理状态 save / restore（对照 Iris：`CullingDataCache.saveState()` / `restoreState()`，`ShadowRenderer.java:387-389` / `:564-566`；云重建标志 `generateClouds` 存取 `:420-422`，其注释注明漏掉这条修复时**最多吃掉 10% 帧时间**） |

Iris 阴影是**每帧**第二遍渲染且可接受——同量级证明副画面渲染可行。
框架不设相机数上限（见[并行播放](./parallel-playback.md)）；以上档位是给作者控制渲染压力的手段。

---

## 7. 兼容性风险

- 光影（Iris/Oculus）：
  - 它们自己会做多次渲染，说明多次渲染可行
  - 但第三方二次调用可能绕过光影的合成，或触发重复特效
  - 需要专门调研：能否在光影环境下拿到“已处理后的画面”
- 性能模组（Sodium）：
  - 渲染状态管理更严格，二次调用前必须确认状态保存/恢复方式（具体：`RenderDevice.enterManagedCode()` / `exitManagedCode()` 包住 `drawChunkLayer` / `setupTerrain`；`setupRender` 里的 `frame++` 每调一次自增；`SodiumWorldRenderer.setupTerrain` 用相机位姿判 `dirty` → 每帧两次 `markGraphDirty()` 让渲染列表缓存失效——见 `render-second-pass-cost.md` §3.1–3.2）

### 7.1 渲染优化模组兼容策略（自 0.4.0 G2 并入）

- **事实**：Sodium `@Overwrite` 原版区块渲染（Fabric Sodium：`renderLayer` → `SodiumWorldRenderer.drawChunkLayer`，`WorldRendererMixin.java:100-108`；Embeddium 端方法名是 `renderChunkLayer`，`WorldRendererMixin.java:128-142`）与 `setupRender`（`((ViewportProvider) frustum).sodium$createViewport()` 是**硬转换**——副相机的 Frustum 必须实现该接口，否则 CCE：Fabric `:115-124`、Embeddium `:148-164`，接口实现见 `mixin/core/render/frustum/FrustumMixin.java`；整段调用还必须用 `RenderDevice.enterManagedCode()` / `exitManagedCode()` 包住 = 文档里说的"渲染上下文"）；Oculus = Iris 的 Forge 移植（同构，包名同样是 `net.irisshaders.iris`），Forge 端对应物为 Embeddium。副画面第二遍裸调原版区块 API 在 Sodium 下**不可靠**（视锥接口不匹配 / 缺渲染上下文 / 可见性图被两遍打散）；Iris 为此维护了**一整套** Sodium 兼容层——`example/Iris-1.20.1/src/sodiumCompatibility` 共 **83 个 Java 文件 / 4788 行**，其中阴影 pass 专属的 `mixin/shadow_map/*` + `impl/shadow_map/*` 是 **10 个文件 / 321 行**（例：`MixinRenderSectionManager` 为 shadow pass 另存一份 `shadowRenderLists` 并重定向 `renderLists` 字段；`MixinSodiumWorldRenderer` 在 shadow pass 里强制 `RenderSectionManager.needsUpdate() == true`），第二遍本体 `shadows/ShadowRenderer.java` **730 行**（Oculus 版 729 行）。
- **策略（推荐）**：副画面渲染走原版 API + **运行时检测 Sodium / Embeddium / Oculus** → 存在时副画面自动禁用 + warn 日志（提示关闭对应模组以使用多相机画面）；无优化模组时正常。不做 Iris 级完整适配（成本高）；需求提升后再评估升级。**✅ 已落地（2026-10-07）**：`LaneRenderer.isUnavailable()` 按标记类存在性运行时检测（`net.caffeinemc.mods.sodium.client.SodiumClientMod` / `me.jellysquid.mods.sodium.client.SodiumClientMod`，不引入硬依赖），命中即不渲染 + warn 一次；Oculus / Iris 的"取已处理画面"仍未验证（§7 第 3 点）。
- 另见 §11：Sodium / Embeddium 的渲染中心来自 `Camera` / `Frustum`，`CameraMixin` 路线已验证，可作为适配时的另一条参考路径。

---

## 8. 实施步骤（0.3.6）

1. 调研：原版 `GameRenderer.renderLevel` 可重入性、状态保存/恢复
2. 原型：单机双 RenderTarget + 二次渲染，先不做光影兼容
3. 多 lane 渲染：N 个相机各渲染到独立 RenderTarget（上屏布局见[画面合成](./camera-composition.md)）
4. 叠化预热：叠化前提前渲染下一 lane，保证首帧有效（预热调度见[画面合成](./camera-composition.md)）
5. 兼容：Iris/Oculus/Sodium 实测
6. 性能：原分辨率副画面 + 内容档位 + 帧耗时统计
7. 文档：SCRIPT_FORMAT / AI_SCRIPTING_GUIDE
8. **实机缺陷缓解（2026-10-07，见 §12.8）**：lane 透底（合成层改专用不透明 blit `ic_lane_blit`）与倾斜裁切矩形（lane 活跃期间整帧关闭遮挡剪枝 + 每 lane pass 强制重刷可见集合 + 帧驱动移到世界渲染之前）

---

## 9. 开放问题

- ~~多相机是否走“多条 CAMERA 轨道”还是“单轨道多 camera_id”？~~
  **已回答**：多脚本实例 × 每实例多 lane，不设上限——见[并行播放](./parallel-playback.md)。
- ~~叠化是否只支持两相机，还是 N 相机？~~ / ~~分屏是否要支持任意布局？~~
  **已回答**：分屏 / 叠化 / PIP 统一为合成参数（取材区域 / 目标区域 / 不透明度）的特例，lane 数不设限——见[画面合成](./camera-composition.md)。
- 副画面是否需要支持 look_at / tangent 朝向？
- 是否允许叠化期间主相机继续移动？
- ~~预热时长是固定值还是脚本可配？~~ **已解答（2026-10-07）**：都不是——预热 = 重叠窗口本身（作者编排 B 的开头延长量），见 §3.3 与 `camera-composition.md` §4。

---

## 10. 结论

- 0.3.5 不做
- 0.3.6 作为核心功能攻克
- 先做原型验证二次渲染，再谈上屏合成与编辑器 UI

---

## 11. 参考：Sodium / Rubidium / Embeddium 源码

0.3.5 已验证一个重要事实：

- Sodium / Rubidium / Embeddium 的渲染中心来自 `Camera` / `Frustum`。
- 我们通过 `CameraMixin` 把虚拟相机写进 `Camera`，它们的 `Viewport` / `setupTerrain` 就会自动以虚拟相机为中心渲染。
- 因此 0.3.6 做 PIP / 分屏 / 叠化时，可以优先考虑“复用它们的 Camera/Frustum 管线”，而不是自己再硬改 `LevelRenderer.setupRender`。
  （补充：**原版 `LevelRenderer.setupRender` 路径本身也已实测可用**——原型按 lane 相机调 `setupRender` + `prepareCullFrustum`，4/16/25 画面全部正常，见 `quadrant-prototype-results.md`；上面这条是兼容优化模组时的备选路线。）

源码依据（本次核实）：`SodiumWorldRenderer.setupTerrain` 的位姿全部取自 `Camera`（`camera.getPosition()` / `getXRot()` / `getYRot()`），视口取自 Frustum——`setupRender` 里 `((ViewportProvider) frustum).sodium$createViewport()`，而 `FrustumMixin` 用 Frustum 的 `camX/camY/camZ` 构造 `Viewport`；`LevelRenderer.prepareCullFrustum` 已按传入相机位置 `prepare(x,y,z)`。所以"虚拟相机写进 `Camera` + 自己的 Frustum"这条链是通的。

参考源码位置（已核实存在，行数为本次统计）：

- `example/embeddium-20.1-forge/src/main/java/me/jellysquid/mods/sodium/mixin/core/render/world/WorldRendererMixin.java`（248 行；`:128-142` `renderChunkLayer` → `drawChunkLayer`，`:148-164` `setupRender` → `setupTerrain`）
- `example/embeddium-20.1-forge/src/main/java/me/jellysquid/mods/sodium/client/render/viewport/Viewport.java`（76 行）
- `example/embeddium-20.1-forge/src/main/java/me/jellysquid/mods/sodium/mixin/core/render/frustum/FrustumMixin.java`（32 行；`implements ViewportProvider` → `sodium$createViewport()`）
- `example/embeddium-20.1-forge/src/main/java/me/jellysquid/mods/sodium/client/render/SodiumWorldRenderer.java`（672 行；`:167-226` `setupTerrain`，`:251` `drawChunkLayer`）

> 注：`example/` 下**没有** Rubidium 源码，本节"Rubidium"一条**未验证**（Rubidium 与 Sodium/Embeddium 同源，但本仓库无其代码可核）。

后续做副相机 / 多 RenderTarget / 光影兼容调研时，这些实现可以作为重要参考。

---

## 已知缺陷（2026-10-06 代码复查）

> 只读代码审查发现，未在游戏内复现；不影响当前设计，记录备查。

- ~~**MODE=1 数组越界**：`CinematicOcclusion.beginFrame` 固定按 `i<4` 遍历，而 mode=1 时 `CAMERAS` 长度只有 1 → `CAMERAS[1]` 越界（`proto/QuadrantProto.camera(int)` 无边界检查）。~~ **✅ 已消除（2026-10-07）**：原型代码整体删除；正式实现里 `CinematicOcclusion.beginFrame` 改走 `LaneRenderer.isAnyCameraInsideSolidBlock`（只遍历**活跃** lane），不存在越界路径。

---

## 事实核查（2026-10-07）

> 依据：MC 1.20.1 解出源码（`.gradle/loom-cache/minecraftMaven/...-sources.jar` 与 `%TEMP%\mcsrc\`——同一份 CFR 解出物，行号一致；`GameRenderer` / `LevelRenderer` / `RenderTarget` / `Frustum` 四文件已用 sources.jar 逐段交叉核对）、本仓库 `common/...` 代码、`example/` 参考模组源码、`plans/0.3.6/quadrant-perf/*.csv`。只补事实、不改设计方向；§3.3 / §8 / §9 的设计类开放问题保持待定。

### ① 核实为真（附证据）

**§4 关键难点**

1. **发光描边链路** = `OutlineBufferSource` → 共享 `entityTarget` → `entity_outline` 后处理链 → `LevelRenderer.doEntityOutline()` → `entityTarget.blitToScreen(窗口宽, 窗口高, false)`：`LevelRenderer.java` 实体段取 `this.renderBuffers.outlineBufferSource()`（:1078）、`entityTarget.clear()`（:1062）、`entityEffect.process(f)`（:1142）、`doEntityOutline()`（:528-535，`shouldShowEntityOutlines()` 守卫）、shader `shaders/post/entity_outline.json`（:460）；该 json 的 pass 链 = `entity_outline` → `blur`(H) → `blur`(V) → `blit`（targets 为 `swap`/`final`，`assets/minecraft/shaders/post/entity_outline.json`）；原版那次整屏调用在 `GameRenderer.render` 里紧跟 `renderLevel`（:884-886）。✓
2. **`renderLevel` 之后原版的整屏步骤**（§4"渲染顺序 / lane 自包含"的事实边界）：`GameRenderer.render` 中 `renderLevel(f,l,new PoseStack())` → `tryTakeScreenshotIfNeeded()` → `levelRenderer.doEntityOutline()` → `if (postEffect != null && effectActive) postEffect.process(f)` → `getMainRenderTarget().bindWrite(true)`（:884-893）；帧末 `Minecraft.runTick` 里 `profiler.push("blit")` → `mainRenderTarget.unbindWrite()` → `mainRenderTarget.blitToScreen(窗口宽, 高)`（`Minecraft.java:1043-1045`）。✓
3. **`RenderTarget.blitToScreen` 改全局投影**：`_blitToScreen(int,int,boolean)` 内 `RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, i, j, 0, 1000, 3000), VertexSorting.ORTHOGRAPHIC_Z)`（`RenderTarget.java:221-234`，全局、不还原）。原型在 lane 内还原为 `VertexSorting.DISTANCE_TO_ORIGIN`（`QuadrantProtoMixin.java`）。✓
4. **lane 期间主画面指向的必要性**：`RenderTarget._bindWrite(bl)` 在 `bl=true` 时 `GlStateManager._viewport(0, 0, viewWidth, viewHeight)`（`RenderTarget.java:185-191`）；`RenderTarget.clear()` 内部即 `bindWrite(true)`（:259-269）。所以 `LevelRenderer.renderLevel` 里 `entityTarget.clear()` 会把 viewport 重置成 **entityTarget 的 view 尺寸**（= 窗口尺寸：`initOutline()` 用 `entityEffect.resize(窗口宽, 高)` 建链，`PostChain.resize` 取 `screenTarget.width/height`，即主画面尺寸——`LevelRenderer.java:460-464`、`PostChain.java:276-279`），随后 `getMainRenderTarget().bindWrite(false)` **不回滚 viewport**（:1063）。✓ 与 `quadrant-prototype-results.md` §3.1 一致。
5. **`Frustum.offsetToFullyIncludeCameraCube` 语义**：调用点 `LevelRenderer.setupRender` 内 `applyFrustum(new Frustum(frustum).offsetToFullyIncludeCameraCube(8))`（`LevelRenderer.java:831`；触发条件 `needsFrustumUpdate.compareAndSet(true,false) || 相机 rot 变化`，:830）。实现（`Frustum.java:37-49`）：方块坐标（`floor/ceil(cam/i)*i`）在循环外算一次；循环体只把**参考点**沿 **−`viewVector`** 每次移 `OFFSET_STEP = 4` 格（`camX -= viewVector.x * 4`），直到 `intersectAab(...) == INSIDE`；JOML 常量 `INTERSECT=-1 / INSIDE=-2 / OUTSIDE=-3`（已从 `joml-1.10.5-sources.jar` 核实）。✓ 与 `quadrant-prototype-results.md` §3.4 一致（补充：移动方向是 **−viewVector**）。
6. **可见集合 / 遮挡剔除是 `LevelRenderer` 单份共享状态**：`private final AtomicReference<RenderChunkStorage> renderChunkStorage`（:195）+ `private final ObjectArrayList<RenderChunkInfo> renderChunksInFrustum`（:196）；BFS = `updateRenderChunks(...)`（:881）沿 6 个方向扩张的 `ArrayDeque` 队列。✓
7. **第二遍挂点可行**：原型挂在 `GameRenderer.renderLevel` 的 RETURN（`QuadrantProtoMixin` `@Inject(method = "renderLevel", at = @At("RETURN"))`），即主世界渲染之后、`doEntityOutline` / `postEffect` / GUI 之前；实机跑通 4/16/25 画面（CSV）。✓
8. **手 / HUD 不在 `LevelRenderer.renderLevel` 内**：手在 `GameRenderer.renderLevel` 的 `hand` 段（`renderItemInHand`，:1075-1079），HUD / 屏幕在 `GameRenderer.render` 的 `gui` 段（:907 起）。✓
9. **雾的调用点**：`FogRenderer.setupColor` + `levelFogColor`（`LevelRenderer.java:1027-1028`，`clear` 段）、`FOG_SKY` 以 lambda 传给 `renderSky`（:1034）、`FOG_TERRAIN`（:1036）。✓
10. **`RenderBuffers` 替换点**：原版 `LevelRenderer.renderBuffers` 是 `private final`（:191）、无公开 getter/setter → 需要自己的 accessor；Iris 的做法是 `mixin/LevelRendererAccessor.java` 的 `@Accessor("renderBuffers") getRenderBuffers()/setRenderBuffers()`，并在 `ShadowRenderer.renderShadows` 换入自己的 `new RenderBuffers()`（`ShadowRenderer.java:142` 创建、`:375-376` 换入、`:570` 恢复）。✓
11. **§6.1 借用的机制在 Iris 里都有对应实现**：独立 frustum（`createShadowFrustum` :270 / `getFrustum().prepare(cameraX,cameraY,cameraZ)` :405）、只走区块层（:460-464，translucent :535-537）、内容开关（:75-80）、`CullingDataCache.saveState()/restoreState()`（:387-389 / :564-566）、云重建标志 `generateClouds` 存取（:420-422，注释称漏掉该修复**最多吃掉 10% 帧时间**）、`copyPreTranslucentDepth`（:528）。✓

**§6 性能**

12. 单画面 3.4 / 3.5 / 3.6 ms、1/4/16/25 稳态帧间隔 8 / 17 / 61 / 98 ms、主画面 2–3 ms、线性：与 `quadrant-perf/summary.md` 结论表逐项一致；CSV 原始数据支撑（mode4 稳态帧间隔 16–19 ms、mode16 54–68 ms、mode25 78–93 ms；每画面 avg：mode4 3441–3945 us、mode25 3229–4665 us）。✓
13. 轻场景探针：每多一遍 ≈ **+0.56 ms**、≈ **0.85×** 主 `renderLevel`、N=5 ≈ 5.5×、帧间隔 1.16→4.00 ms、无超线性偏离：与 `render-second-pass-cost.md` §4.2 的表与拟合（`total ≈ 0.555·N − 0.030`，R²=0.98）一致。✓

**§7 / §7.1 兼容**

14. Fabric Sodium `@Overwrite renderLayer` → `SodiumWorldRenderer.drawChunkLayer`（`example/sodium-1.20.1-stable/.../WorldRendererMixin.java:100-108`）、`@Overwrite setupTerrain` → `((ViewportProvider) frustum).sodium$createViewport()`（:115-124）；Embeddium 对应 `renderChunkLayer`（:128-142）/ `setupRender`（:148-164，硬转换 + `RenderDevice.enterManagedCode()`）；`FrustumMixin implements ViewportProvider`（32 行）。✓
15. **Oculus = Iris 的 Forge 移植（同构）**：包名同为 `net.irisshaders.iris`，`src/sodiumCompatibility` 目录结构一致（含 `mixin/shadow_map/*`），`ShadowRenderer.java` 730 vs 729 行。✓
16. **Iris 每帧一次完整第二遍**：`pipeline.renderShadows((LevelRendererAccessor) this, camera)` 注入在 `renderSky` 之前（`mixin/MixinLevelRenderer.java:140-143`）。✓

**§11**

17. Sodium / Embeddium 渲染中心来自 `Camera` / `Frustum`：`SodiumWorldRenderer.setupTerrain` 取 `camera.getPosition()` / `getXRot()` / `getYRot()`（:167-210），`Viewport` 由 `FrustumMixin` 用 Frustum 的 `camX/camY/camZ` 构造；`LevelRenderer.prepareCullFrustum` 已按传入相机位置 `prepare(x,y,z)`（`LevelRenderer.java:984-992`）。✓
18. §11 列的四个 embeddium 参考路径**全部存在**（行数已就地标注）。✓
19. **"原型代码已并入 main、默认关"**：merge 提交 `e41ccb1`（2026-10-06T19:53:57+08:00，"merge: 多相机原型代码合入主线（默认关、零差异…）"）；`QuadrantProto.MODE = parseMode()` 在未设 `-Dicinematics.quadrant` / `ICINEMATICS_QUADRANT` 时返回 0（`isEnabled() = MODE > 0`）；`immersive_cinematics.mixins.json` 的 `client` 段含 `QuadrantProtoMixin` / `MinecraftAccessor`；`proto/QuadrantProto.java`、`proto/QuadrantProtoMixin.java`、`camera/CinematicOcclusion.java` 均在 main。✓

**已知缺陷**

20. **MODE=1 数组越界 ✓**：`CAMERAS = new ProtoCamera[Math.max(VIEWS, 1)]`，mode=1 时 `VIEWS = 0` → 长度 1；`CinematicOcclusion.beginFrame` 的 `for (int i = 0; i < 4 && !inside; i++) inside = isInsideSolidBlock(mc, QuadrantProto.camera(i).camera());` 会在 `i=1` 越界（`QuadrantProto.camera(int)` 直接 `return CAMERAS[i]`，无边界检查）。触发条件：`ICINEMATICS_QUADRANT=1` + 已进世界 + `camera(0)` **不在**实心方块内（若在，`inside=true` 提前结束循环）；挂点 `GameRendererMixin` 的 `render` HEAD → `CinematicOcclusion.beginFrame(...)`。

### ② 已修正

1. **§6「重场景单 lane ≈ 0.85 × 主 `renderLevel`」→ 重场景实测 ≈ 3.4–3.7 ms**（0.85× 是**轻场景探针**口径）。
   证据：0.85× 出自 `render-second-pass-cost.md` §4.2（轻场景：新世界 / 视距 12 / 无光影模组）；`quadrant-perf/summary.md` 的重场景（地表 + 20 多实体 + 发光描边）单画面 3.4/3.5/3.6 ms、主画面 2–3 ms。
   冲突裁决：本文档 `git log -1 --format=%cI` = **2026-10-06T21:16:28+08:00**；`render-second-pass-cost.md` = **2026-10-05T23:52:04+08:00**、`quadrant-perf/summary.md` = **2026-10-05T23:47:11+08:00**（数字对应的 CSV 生成于 2026-10-05 23:47）。文档虽晚于数据，但本文档 §6 第一条 bullet 自己就把 0.85× 标为"轻场景"，属**同文档内部自相矛盾**；按"原始测量数据优先"取实测口径修正（0.85× 的表述在文档内保留，但明确其适用范围）。
2. **§4「单画面 ≈3.4–3.6 ms ≈ 1.2–1.4× 主画面」→ 去掉不可复现的比值**，保留 3.4–3.6 ms 与主画面 2–3 ms。
   证据：CSV `view` 明细 mode4 = 3441–3945 us、mode25 = 3229–4665 us，主画面稳态 = 2 ms → 比值 ≈1.6–2.0×；`quadrant-perf/summary.md` 自身的"≈1.2–1.4 × 主画面"与同表 3.4–3.6 ms vs 2–3 ms 也对不上（该比值口径无法复现）。**跨文档冲突**：`quadrant-perf/summary.md` 中该句按裁决规则应改（其最后修改 2026-10-05T23:47:11 早于本文档），但不在本文职责内——**未改动**，留待该文档负责人。
3. **§7.1「Iris 为此维护了约 610 行的管线适配」→ 实测规模**：`example/Iris-1.20.1/src/sodiumCompatibility` = **83 个 Java 文件 / 4788 行**；阴影 pass 专属 `mixin/shadow_map/*` + `impl/shadow_map/*` = **10 个文件 / 321 行**；`shadows/ShadowRenderer.java` = **730 行**（Oculus 729）；`pipeline/SodiumTerrainPipeline.java` = 651 行。"610 行"在本仓库 Iris/Oculus 源码中**对不上任何单一文件或子集**。
   冲突裁决：本文档 2026-10-06T21:16:28+08:00 vs `example/Iris-1.20.1/...` 文件 mtime 2025-06-27 11:29:13（`example/` 被 `.gitignore:36` 忽略、无 git 历史，只能用 mtime）；文档晚于源码副本落地 → 按源码实测修正。
4. **细节精度修正**：`renderLevel` 内的 viewport 重置是"重置成**该 target 的 view 尺寸**"（`entityTarget` 恰好是主画面/窗口尺寸），不是无条件的"整窗"；`offsetToFullyIncludeCameraCube` 的循环移动方向是 **−viewVector**；Embeddium 端被 `@Overwrite` 的方法名是 `renderChunkLayer`（Fabric Sodium 是 `renderLayer`）。

### ③ 补全

- **§4 表格**：为"全局状态切换/恢复 / 第二个 Camera / 第二个 Frustum / 副画面内容 / 渲染顺序 / 光影兼容 / 剔除视锥 / 剔除状态 / lane 自包含 / 主画面指向 / 全局投影 / 性能"各行补上具体类名+方法名（`RenderTarget.bindWrite`、`LevelRenderer.prepareCullFrustum`、`FogRenderer.setupFog(FOG_TERRAIN)`、`RenderSystem.setProjectionMatrix`、`LevelRenderer.renderChunkStorage` 等）。
- **§4 新增事实**（回答 §9"渲染顺序"的**事实边界**，不代替设计决定）：`renderLevel` 之后的整屏步骤 = `tryTakeScreenshotIfNeeded` → `doEntityOutline` → `postEffect` → `getMainRenderTarget().bindWrite(true)`，帧末另有 `Minecraft.runTick` 的 `blitToScreen`。
- **§6.1 表格**：7 行机制全部补上 Iris 对应实现与行号（见 ①.11）。
- **§7**：Sodium 状态项补三个具体点——`RenderDevice.enterManagedCode()/exitManagedCode()`、`setupRender` 的 `frame++` 每调一次自增、相机位姿变化触发的 `markGraphDirty()`。
- **§11**：补四个参考文件的行数与关键行号；补 `setupTerrain` / `Viewport` 的源码依据；就地标注 Rubidium 未验证。

### ④ 未验证

- **Rubidium**：`example/` 下没有 Rubidium 源码 → §11 关于它的一条**未验证**（已就地标注）。
- **"光影环境下能否拿到已处理后的画面"**（§7 第三点）：只核实了 Iris 第二遍的存在与状态处理，未验证第三方能否安全取用 Iris 合成后的画面——保持"需要专门调研"。
- **Sodium 下两遍/帧的 `frame++` 语义变化是否引发错误行为**：`render-second-pass-cost.md` §3.2 已标"未验证"，本次亦无实机验证（该形参在 Embeddium 里标了 `@Deprecated(forRemoval = true)`，仅传给 `renderSectionManager.update`）。
- **叠化预热时长 / 叠化期主相机是否可动**（§9）：设计类开放问题，源码无法回答，**保持待定**。

---

## 12. 渲染底层实现（2026-10-07 落地）

> 本节是**已实现**的渲染底层（代码为准）；§3 技术路线 / §4 关键难点 的结论全部体现在这里。
> 上屏合成（取材 / 目标区域 / 不透明度 / 叠放顺序）与脚本 / lane 注册表接入**不在本节**。

### 12.1 组件

| 文件 | 职责 |
|---|---|
| `common/.../client/lane/LaneRenderer.java` | 生产渲染器：lane 注册表 + 每帧驱动 + 每 lane 整尺寸渲染进共用离屏 FBO + 状态保存/恢复 + Sodium/Embeddium 检测 |
| `common/.../client/lane/ScriptLaneDriver.java` | 脚本 lane 驱动：每帧把脚本播放实例的 lane 注册进 `LaneRenderer`（相机 id = `<scriptId>_cam<相机轨序号>`）并按 clip 关键帧提供合成参数 |
| `common/.../client/lane/LaneDebugCapture.java` | 调试捕获（`ICINEMATICS_CAPTURE` 门控，默认关、零差异）：按相机 id 产出每相机 raw（保真 alpha）/ 每相机 composited（按 dest 裁剪、保真 alpha）/ 窗口终帧三类 PNG，目录 `<gameDir>/lane-captures/` |
| `mixin/LaneRendererMixin.java` | 挂点：`GameRenderer.renderLevel` RETURN → `LaneRenderer.render(...)`（逐 lane 渲染 + 合成）+ `ColorAdjustPass.render`（RADJ）。帧驱动 / lane 注册 2026-10-07 迁到 `GameRendererMixin`（见 §12.8-B） |
| `mixin/GameRendererMixin.java` | 帧首（`GameRenderer.render` HEAD，世界渲染之前）：`CameraManager.onRenderFrame()` + 脚本 lane 注册（`ScriptLaneDriver`）+ `CinematicOcclusion.beginFrame`（顺序固定，见 §12.8-B③）+ lane 的 getFov 分支；帧尾（`render` RETURN）：调试捕获的窗口终帧读回 |
| `mixin/GameRendererAccessor.java` | `@Invoker`：`getProjectionMatrix(double)` / `getFov(Camera,float,boolean)`（复刻原版投影与光学参数） |
| `mixin/LevelRendererAccessor.java` | `@Invoker applyFrustum(Frustum)`：lane pass 绕开门闩强制重刷可见集合（§12.8-B②） |
| `mixin/MinecraftAccessor.java` | `@Accessor @Mutable mainRenderTarget`（lane 期间指向 lane FBO） |
| `mixin/CameraAccessor.java` | `@Invoker setPosition/setRotation` + `@Accessor initialized`（听者相机代理：listener=camera 时把原版听者搬到镜头位置） |
| `mixin/CameraMixin.java` | lane 分支：把 lane 的 `CameraState`（position/yaw/pitch）写进该 lane 的独立 `Camera` 实例（`getEntity` / `isDetached` 同样按 lane 判定）。主相机分支已于 2026-10-07 退役删除 |
| `mixin/LevelRendererMixin.java` | lane 视图中心（`setupRender` 的 `ModifyVariable` ×3 用本帧**最上层 lane** 的相机位置做整帧单一中心；无 lane 时用玩家坐标——单份网格不能逐 pass 换中心，见该文件 javadoc）+ 遮挡剔除整帧包夹（`CinematicOcclusion`，lane 活跃时 `smartCull=false`，§12.8-B）+ lane pass 清屏 alpha（§12.8-A）+ 原版整屏描边屏蔽（有活跃 lane 时）+ 内容开关（`renderSky` / `renderClouds` / `renderSnowAndRain` / `renderEntity`） |
| `mixin/ParticleEngineMixin.java` | 内容开关：粒子 |
| `camera/CinematicOcclusion.java` | 遮挡剔除整帧统一决策：判定输入 = **有活跃 lane**（`LaneRenderer.hasActiveLanes()`）→ 整帧 `smartCull=false`（可见集合退化为视距内全部区块）；起 / 停播强制一次 `needsUpdate()`。代价与长期解见 §12.8-B |

### 12.2 lane 状态与接入点

- **lane 状态 = `CameraState`（六参数快照）+ `LaneContent`（内容开关）**；
  `LaneContent.WORLD_ONLY`（默认，全关）/ `LaneContent.FULL`（调试、压测）。
- 接入点（后续任务用）：
  - `LaneRenderer.INSTANCE.setLane(index, state, content, adjust, captureId)` —— 激活 / 更新一条 lane（`state == null` 停用；`captureId` = 调试捕获用的相机 id，可为 null）；
  - `LaneRenderer.INSTANCE.clear()` —— 生产者本帧没有 lane 时调用；
  - `LaneRenderer.INSTANCE.setSink((index, target) -> …)` —— **合成接缝**：每条 lane 渲染完、离屏缓冲还热时回调（共用缓冲 → 必须当帧消费）。
- **默认零差异**：没有活跃 lane 时 `render(...)` 第一行即返回（不渲染、不分配、不切状态）；调试捕获未开（`ICINEMATICS_CAPTURE`）时 `LaneDebugCapture` 的每个钩子第一行即返回。

### 12.3 每条 lane 的渲染流程

```text
mainRenderTarget 临时指向 lane FBO → fbo.bindWrite(true)（视口 = FBO 全尺寸）
  → camera.setup(...)（CameraMixin lane 分支写该 lane 的 CameraState）
  → 视图 PoseStack（XP=xRot、YP=yRot+180）→ roll（绕视线轴）→ setInverseViewRotationMatrix
  → getFov（GameRendererMixin lane 分支）→ 投影 / 剔除投影
  → prepareCullFrustum(该 lane 的 pose + 相机位置)      ← 视锥与姿态一致，原版膨胀剔除 3~5 步收敛
  → renderLevel(..., bl=false /* 不画选中框 */, camera, …)
  → doEntityOutline()（lane 自包含：描边贴进本 lane 的画面）
  → 还原全局投影为该 lane 的投影（DISTANCE_TO_ORIGIN）
  → 恢复 mainRenderTarget → sink(index, fbo)
```

- 离屏缓冲**共用一张**（按主画面尺寸创建 / 跟随窗口重建）→ 显存不随 lane 数增长；顺序复用即 §6.1 的"缓冲复用"。
- 副画面天然不含第一人称手 / HUD（它们在 `LevelRenderer.renderLevel` 之外），不额外处理。

### 12.4 状态保存 / 恢复

- lane 块入口保存、出口还原：**全局投影矩阵 + VertexSorting**、**全局逆视图旋转矩阵**、**mainRenderTarget**、**当前 framebuffer + 视口**；
- 雾 / 深度 / 混合状态由 `renderLevel` 自己收敛到"渲染结束"态（与原版主画面同态，末尾 `setupNoFog` / `depthMask(true)` / `disableBlend`）；
- 视锥每帧由各 pass 重建（`GameRenderer.renderLevel` 每帧调 `prepareCullFrustum`），无需还原；
- **主画面的发光描边**：有活跃 lane 时原版那次整屏 `doEntityOutline()` 被屏蔽（否则会把最后一条 lane 的描边 1:1 盖满全屏），改为在 lane 块开头先为**主画面**贴一次（主画面描边不丢）。

### 12.5 兼容

- **Sodium / Embeddium（Rubidium）**：`LaneRenderer.isUnavailable()` 按标记类存在性运行时检测（`net.caffeinemc.mods.sodium.client.SodiumClientMod` / `me.jellysquid.mods.sodium.client.SodiumClientMod`）→ 命中即**不渲染 + warn 一次**，不引入硬依赖。检测结果缓存；脚本 lane 照常注册，但渲染被禁用（此时调试捕获也拿不到 lane 画面）。
- 光影（Iris / Oculus）：未做适配（§7 第 3 点仍待调研）。

### 12.6 调试入口

**四象限演示 = 脚本，不是代码级驱动**：`cinematics/release/quadrant.json`（脚本 id `quadrant_demo`，单脚本 4 条 CAMERA 轨、`dest` 各占半屏；每格调色直接写在各自相机片段的关键帧上）。把该文件放进 `<游戏目录>/immersive_cinematics/scripts/`，进世界由 login 触发器自动播一次：

```sh
sh gradlew :fabric:runClient --args='--quickPlaySingleplayer <世界名>'
```

> 旧的代码级调试驱动 `LaneDebugDriver`（四方向来回运镜、n×n 网格临时上屏、开关 `ICINEMATICS_QUADRANT=1|4|16|25…`）**已删除**——四象限演示改由上面的脚本承担，上屏走正式合成层。

**调试捕获（看画面用）**：开关 = 环境变量 `ICINEMATICS_CAPTURE` / 系统属性 `-Dicinematics.capture`（`1` / `true` 即开，默认关、零差异）。产出目录 `<gameDir>/lane-captures/`，每轮三类：

| 文件 | 内容 | 读回口径 |
|---|---|---|
| `<id>-raw[-r{k}].png` + `-alpha.png` | 该相机的 lane 离屏 FBO 原始画面（lane 级调色之前） | `downloadTexture(0, false)`：**保真 alpha**；`-alpha.png` = alpha 灰度可视化 |
| `<id>-composited[-r{k}].png` + `-alpha.png` | 该相机合成进主画面之后、按它的 `dest` 矩形裁出的那块 | `downloadTexture(0, false)`：**保真 alpha** |
| `frame[-r{k}].png` | 窗口终帧（世界 → 合成 → master 调色 → GUI 之后的整帧，= 玩家看到的窗口画面） | `downloadTexture(0, true)`：alpha 强制 255（与 F2 截图同口径） |

- `<id>` = `<scriptId>_cam<相机轨序号>`（如 `quadrant_demo_cam0`）；无 id 的 lane 回退 `lane<序号>`；文件名安全化：`[A-Za-z0-9_.-]` 之外的字符换成 `_`。
- 节奏：首帧抓一轮，此后每 200 帧一轮，最多 5 轮；第 2 轮起文件名带 `-r{k}`（k≥2）。一轮 = 每条 lane 2 个 raw + 2 个 composited，加 1 张 `frame`。
- 日志：每次读回一行 `[lane-capture] ...`，含文件名与 alpha 统计（整体 0/255/中间值占比 + 自上而下 10 条横带的 alpha=0 占比）。
- 位置：raw 读回在 `LaneRenderer.renderLane` 内（lane 级调色之前），composited 读回在 `ScriptLaneDriver.compose` 里（紧随 `LaneCompositor.compose`），窗口终帧在 `GameRendererMixin` 的 `GameRenderer.render` RETURN（原版 `blitToScreen` 在 `Minecraft.runTick` 里、`render` 返回之后才调用，内容与窗口一致）。
- 命中优化模组（`LaneRenderer.isUnavailable()`）时 lane 不渲染 → raw / composited 没有产出，`frame` 仍会出（那是原版画面）。

### 12.7 原型处置（版本原则：不留旧代码）

- 删除：`proto/QuadrantProto.java`（渲染逻辑 + 压测统计 / CSV / 出图）、`mixin/QuadrantProtoMixin.java`、`proto` 包；
- 保留并适配：`mixin/MinecraftAccessor.java`（lane 期间主画面指向）、`camera/CinematicOcclusion.java`（整帧统一遮挡决策）、`CameraMixin` / `GameRendererMixin` / `LevelRendererMixin` 的接管分支（原型分支 → lane 分支）；
- 压测数据留在 `quadrant-perf/`（存档），压测/统计代码随原型删除——渲染底层再次需要成本曲线时按新结构重测。

### 12.8 实机缺陷与缓解（2026-10-07 多相机 lane 实机验证）

> 现象 / 根因 / 处置都是**实机 + 代码**口径（探针：`ICINEMATICS_QUADRANT=4`，逐帧读回 lane 帧与合成帧做像素级对比）。
> **注（2026-10-07 清理）**：当时的探针走已删的 `LaneDebugDriver`；现在的捕获入口是 `ICINEMATICS_CAPTURE`（见 §12.6），本节只作历史记录。

**A. 隐约透底（lane 纹理 alpha 漏进合成）**

- **现象**：全屏 lane（opacity=1）下，合成帧与 lane 帧在 lane alpha < 255 的像素上有偏差（这类像素占 0.15%~1.77%，偏差在这些像素上放大 3.8~4.9 倍）——主画面（玩家视角）从这些像素透出来。
- **根因**：lane FBO 里写下的 alpha 不干净、也无人清理（原版从不显示它：`RenderTarget._colorMask(true,true,true,false)`）——星星 a=127（`blendFuncSeparate(SRC_ALPHA,ONE,ONE,ZERO)`）、方块图集 mipmap 边缘 1px 暗缝 a=146~254、cutout 植被等；而合成层原先用原版 `position_tex`（`color * ColorModulator` + srcalpha 混合）上屏，混合权重被乘成 `a × opacity` ⇒ 透底。
- **处置（✅ 已落地）**：新建合成层专用着色器 `assets/minecraft/shaders/core/ic_lane_blit.{json,vsh,fsh}` ——
  `fragColor = vec4(texture(Sampler0, uv).rgb, 1.0) * ColorModulator`，**忽略 lane 纹理 alpha**，
  不透明度只由 `ColorModulator.a`（合成层 opacity）承担；`LaneCompositor` 改用它
  （着色器加载失败时退回 `position_tex` 并记一次错误——画面仍可见，只是重新引入透底）。
  `LevelRendererMixin` 的 `laneClearAlpha`（lane pass 清屏 alpha 抬到 1）**保留但不再承担合成正确性**：
  只保证 FBO 数据里"未覆盖区 = 实心雾色"的语义（调试读回 / 未来可能的 alpha 消费方）。
- **契约**：lane 内画面 = 完整 100% 不透明；透明度只由合成层 opacity 作用（`plans/0.3.6/README.md` 画面完整性原则）。

**B. 倾斜裁切矩形（共享可见集合 + 单相机播种的遮挡剪枝）**

- **现象**：副画面里出现沿区块网格斜切的缺块边界，随「哪个 pass 当播种相机」间歇出现。
- **根因**：`renderChunkStorage` 由**某个** pass 的相机经 smartCull BFS 遮挡剪枝重建并整体替换
  （`LevelRenderer.setupRender` / `updateRenderChunks`），`renderChunksInFrustum` 每个 pass 只
  clear + 按本 pass 视锥过滤（`applyFrustum`）、从不补充 ⇒ 相对播种相机被剪掉的 16 格区块在**所有** lane 缺失。
- **处置（✅ 已落地，低成本缓解，不引入每 lane 独立可见集合）**：
  1. **lane 活跃时整帧 `smartCull=false`**：判定口径 = `LaneRenderer.hasActiveLanes()`
     （`camera/CinematicOcclusion.beginFrame`，每帧一次，挂在 `GameRenderer.render` HEAD），
     `LevelRendererMixin` 在 `setupRender` HEAD / RETURN 包夹 `smartCull` ⇒ BFS 退化为
     "视距内全部区块、无遮挡剪枝"，各 pass 仍各自 `applyFrustum` 视锥过滤；
     起 / 停播各强制一次 `LevelRenderer.needsUpdate()`，让集合立即按新口径重建。
  2. **每条 lane pass 强制重刷可见集合**：原版 `applyFrustum` 有朝向门闩
     （`needsFrustumUpdate` 或 `floor(xRot/2)` / `floor(yRot/2)` 变化才触发），**同朝向桶的 lane
     会沿用上一 pass 的集合**；`LaneRenderer.renderLane` 在 `renderLevel` 之前按本 lane 视锥调一次
     （`mixin/LevelRendererAccessor` 的 `@Invoker applyFrustum`；视锥 = `prepareCullFrustum` 同源
     + `offsetToFullyIncludeCameraCube(8)`，与原版 `setupRender` 内那次逐点同口径）。
  3. **帧驱动 / lane 注册移到世界渲染之前**：`CameraManager.onRenderFrame()` + `ScriptLaneDriver` /
     `LaneDebugDriver` 注册从 `LaneRendererMixin`（`renderLevel` RETURN）迁到 `GameRendererMixin`
     （`GameRenderer.render` HEAD，`renderLevel` 参数为真且已有世界时）——视图中心
     （`setupRender` 改写玩家坐标）与遮挡决策都读 lane 表，原挂点会让两者都滞后一帧。
     调用条件与原来等价（原版调用 `renderLevel` 的条件就是 `renderLevel && level != null`）。
     （当时并存的调试驱动已删，现在这条路径上只有脚本 lane 注册——见 §12.6。）
- **代价**：lane 活跃期间没有遮挡剪枝，视距内全部区块都进可见集合 → 绘制 / 区块编译量上升
  （`smartCull=false` 也是 Iris 阴影 pass 的做法，见 `render-second-pass-cost.md` §2.3）。
  **只在 lane 活跃的帧生效**；停播后下一帧起 `smartCull` 交回原版判定（原版对玩家相机的
  「旁观者在实心方块里 → 关掉遮挡剔除」判定不受影响）。日志可确认：
  `[lane] 有活跃 lane：整帧关闭遮挡剔除（smartCull=false）…` / `[lane] 无活跃 lane：遮挡剔除交回原版判定`。
- **长期解（未做）**：每 lane 独立可见集合 / 剔除状态（各自的 `renderChunkStorage` + BFS + frustum）——
  那时各 lane 才允许有自己的遮挡行为，也才能拿回这部分性能
  （见 `quadrant-prototype-results.md` §3.5、`render-second-pass-cost.md` §5）。

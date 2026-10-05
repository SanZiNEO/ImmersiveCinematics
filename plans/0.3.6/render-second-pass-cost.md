# 副画面「多一遍渲染」开销调查（0.3.6）

**状态**: 🔍 调查结论（只读调查，未改任何模组源码）
**目标版本**: 0.3.6
**关联**: [multi-camera-rendering](./multi-camera-rendering.md)、[camera-composition](./camera-composition.md)、[render-routes](./render-routes.md)（临时草稿）

> 本文回答一个问题：**在同一帧里把世界渲染再走一遍（第二个 `Camera` → 独立 FBO）要花多少代价、花在哪、怎么省、现在能不能测。**
> 每条结论标注证据来源；无证据处明确写「未验证」。

---

## 0. 结论摘要（TL;DR）

| 问题 | 结论 | 证据强度 |
|---|---|---|
| 本项目现在有第二遍吗？ | **没有**。当前是「单相机替换」：把虚拟相机写进唯一的 `Camera`，整帧仍只渲染一次。 | 强（源码） |
| 第二遍成本结构 | 与主渲染**同构**：一遍完整 `LevelRenderer.renderLevel`。可省掉天空 / 云 / 天气 / 粒子 / 调试 / 破坏进度等；**区块层（terrain）和地形剔除（terrain_setup）省不掉**，是主要成本。 | 强（原版字节码 + Iris 实现） |
| 量级 | **正式播放的 lane 必须与主画面同分辨率**（低分辨率 FBO 只用于编辑器预览推流）。**实测（探针）**：每多一遍完整世界渲染 ≈ **+0.56 ms**（≈ **0.85×** 主 `renderLevel`），N 遍基本线性（`total ≈ 0.555·N − 0.030 ms`，R²=0.98；见 §4.2）。定性上限仍是「接近成本翻倍」（`multi-camera-rendering.md:93`）。降耗只能靠**内容开关 / 视距·视锥 / 状态复用**等，**不能靠降分辨率**；CPU 的 `terrain_setup` 与 draw call 提交本来就与分辨率无关。**原型实测（重场景：地表 + 20 多实体 + 发光描边，1.0x 同分辨率）**：单画面 ≈ **3.4–3.6 ms ≈ 1.2–1.4× 主画面**；1/4/16/25 画面稳态帧间隔 = **8 / 17 / 61 / 98 ms**（125 / 59 / 16 / 10 fps），**线性、主画面耗时不受影响**（见 `quadrant-perf/summary.md`）。 | 实测（探针轻场景 + 原型重场景，斜率随场景放大） |
| 可行性 | 每帧第二遍**是可行的**：Iris 阴影 pass 就是每帧一次完整第二遍世界渲染，且被公认为「着色器包里最贵的一环」——同量级证明。 | 强（Iris 源码 + 公开资料） |
| 实测可行性 | **本机可跑客户端**（有历史运行证据、JDK、gradle 缓存、`runClient` 任务）。模组本体当时无法直接测（第二遍未实现），但**探针分支 `perf/second-pass-probe` 已用一次性手段测得 §4.2 数字**（该分支已按"测完即删"删除，数字留档）；后续**多相机渲染原型**已直接实测（见 §0 量级行与 `quadrant-perf/summary.md`）。 | 强（证据见 §6） |

---

## 1. 「一遍世界渲染」在本项目包含什么

### 1.1 现状：单遍 + 相机替换（不是第二遍）

- `mixin/CameraMixin.java`：`@Inject(method = "setup", at = @At("HEAD"), cancellable = true)`，直接把虚拟相机的位置 / yaw / pitch 写进原版 `Camera`，并置 `initialized = true`。**这是「让主渲染用另一个相机」，不是「再渲染一遍」。**
- `mixin/GameRendererMixin.java`：`getFov` 覆写（虚拟相机 FOV）、`renderLevel` 内在 `prepareCullFrustum` 之前施加 roll。
- `mixin/LevelRendererMixin.java`：`setupRender` 内把 ViewArea 中心从玩家坐标改成相机坐标（`ModifyVariable` ×3）。
- `editor/PreviewCapture.java`、`webui/WebFrameCapture.java`：只做「主 framebuffer → 小 FBO → `glReadPixels` 读回」，是**读回**，不是世界再渲染。

→ 结论：仓库里**不存在**第二遍世界渲染的实现。`multi-camera-rendering.md` 也自标「📋 方案，未实现」。

### 1.2 原版一遍 `renderLevel` 的成本项清单

以下取自本机 loom 缓存中 remap 后的 `LevelRenderer.class` 反汇编（`javap -p -c`），官方名：

- 类：`net/minecraft/client/renderer/LevelRenderer.class`
- 来源 jar：`C:/Users/yjsng/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.20.1-loom.mappings.1_20_1.layered+hash.40545-v2/minecraft-merged-...jar`
- 方法：`public void renderLevel(PoseStack, float, long, boolean, Camera, GameRenderer, LightTexture, Matrix4f)`（反汇编行 3617–4737）

调用方：`GameRenderer.renderLevel(float,long,PoseStack)` 内只调用 `LevelRenderer.renderLevel(...)` **一次**（反汇编行 3065）。

按原版 profiler 段名（`popPush("...")` 的字符串，按出现顺序）拆分：

| profiler 段 | 内容 | 第二遍可否省 |
|---|---|---|
| `light_update_queue` / `light_updates` | `pollLightUpdates` + `runLightUpdates` | 通常可省（世界光照状态两遍共用，第二遍不必再跑；除非副相机需要独立光照——**未验证**） |
| `culling` / `captureFrustum` | 构造 `Frustum` + `captureFrustum` | **不可省**，且必须为副相机**独立一份** |
| `clear` | `RenderSystem.clear` | 不可省（副 FBO 要清） |
| `sky` | `renderSky`（太阳 / 月亮 / 星空 / 天空盒 / 日出日落） | 可省（Iris 阴影 pass 就不渲染天空） |
| `fog` | `FogRenderer.setupFog` / `setupColor` / `levelFogColor` | 可省，但需正确 setup/restore，否则主画面雾错乱 |
| `terrain_setup` | `setupRender(camera, frustum, hasForcedFrustum, spectator)`：ViewArea 重定位、`applyFrustum`、可见区块 BFS、遮挡剔除（`Minecraft.smartCull`） | **不可省**，是 CPU 大头之一。**注意（原型实测）**：相机在实心方块里时，原版对旁观者会关掉遮挡剔除（`smartCull=false`）以获得"稳定透视"；我们的相机等价旁观者、需套同一条件，且多 lane 下必须**按帧统一**（可见集合是共享状态），长期要**每 lane 独立**（见 `quadrant-prototype-results.md` §3.5） |
| `compilechunks` | `compileChunks`：上传已重建的区块网格 | 可省（同帧内主 pass 已做） |
| `terrain` | `renderChunkLayer(solid)` / `(cutoutMipped)` / `(cutout)` | **不可省**，是 GPU 大头 |
| `entities` | 遍历 `level.entitiesForRendering()` → `shouldRender(frustum)` → `renderEntity` + 多种 `endBatch` | 可省（默认关） |
| `blockentities` | 可渲染方块实体 + 全局方块实体 + 大量 `endBatch` | 可省（默认关） |
| `destroyProgress` / `outline` | 破坏进度贴片、方块选中线框 | 可省 |
| `translucent` | `copyDepthFrom(mainTarget)` + `renderChunkLayer(translucent)` + `tripwire` | 可省（默认关；开了需要深度拷贝） |
| `particles` | `ParticleEngine.render(...)`（出现两次：半透明前 / 后） | 可省（默认关） |
| `clouds` | `renderClouds(...)`（两次） | 可省 |
| `weather` | `renderSnowAndRain` + `renderWorldBorder` | 可省 |
| （`DebugRenderer.render`、`renderDebug`、`renderItemInHand`） | 调试 / 手 | 可省（本来就不该进副画面） |

### 1.3 第二遍必须处理的**状态保存 / 恢复**清单

一遍 `renderLevel` 会改动的全局 / 单例状态（第二遍前后必须成对保存-恢复）：

| 状态 | 位置 | 备注 |
|---|---|---|
| 当前 framebuffer | `RenderTarget.bindWrite` | 副画面结束后要 bind 回主 framebuffer |
| 视口 viewport | `RenderSystem.viewport` | Iris 在副 pass 设成 shadow 分辨率，结束后恢复（`ShadowRenderer.setupShadowViewport` + 末尾 `RenderSystem.viewport(0,0,w,h)`） |
| 投影矩阵 | `RenderSystem.setProjectionMatrix` | |
| 视图矩阵 / PoseStack | `RenderSystem.getModelViewStack` / `applyModelViewMatrix` | |
| 雾 | `FogRenderer.setupFog` / `setupNoFog` | |
| 着色器 | `RenderSystem.setShader` | |
| 深度 / 混合 / 面剔除 | `RenderSystem.depthMask` / `enableBlend` / `disableCull` | Iris 副 pass 里 `disableCull()`，结束 `enableCull()` |
| 光照纹理矩阵 | `Lighting.setupLevel` / `setupNetherLevel` | |
| `RenderBuffers` | `LevelRenderer.renderBuffers` | Iris 用**独立** `new RenderBuffers()` 并在副 pass 期间 `setRenderBuffers` 换掉、结束换回（注释：否则「very weird things will happen」） |
| 区块剔除状态 / 遮挡图 | `CullingDataCache.saveState()` / `restoreState()` | Iris 用接口对 `LevelRenderer` 存取（`example/Iris-1.20.1/.../shadows/CullingDataCache.java`）；我们的过渡做法：整帧统一 `smartCull`（`CinematicOcclusion.beginFrame`），长期方向：每 lane 独立可见集合 / 剔除状态（见 `quadrant-prototype-results.md` §3.5） |
| 云纹理 / 重建标志 | `shouldRegenerateClouds()` / `needsUpdate()` | Iris 专门保存并恢复，见 §3.2 |
| 独立 Frustum | `new Frustum(proj, modelview)` + `prepare(x,y,z)` | 每帧新建 / 复用 |

---

## 2. 可对照的真实实现：Iris 阴影 pass（每帧一次完整第二遍世界渲染）

这是本仓 `example/` 里**最直接的对照物**。

### 2.1 注入点：主渲染帧内、天空之前

`example/Iris-1.20.1/src/main/java/net/irisshaders/iris/mixin/MixinLevelRenderer.java:141`

```java
@Inject(method = RENDER, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSky(...)V"))
private void iris$renderTerrainShadows(...) {
    pipeline.renderShadows((LevelRendererAccessor) this, camera);
}
```

即：**每帧、主世界渲染流程中、`renderSky` 之前**，插入一次完整的世界渲染（`ShadowRenderer.renderShadows`）。这直接回答「原版 `renderLevel` 能不能被重入 / 每帧多调一次」——**能，Iris 已经在这么做**。

### 2.2 一遍 shadow pass 做了什么（`ShadowRenderer.renderShadows`）

`example/Iris-1.20.1/src/main/java/net/irisshaders/iris/shadows/ShadowRenderer.java:355`

1. `setupShadowViewport()` → `RenderSystem.viewport(0,0,resolution,resolution)`
2. `createShadowModelView(...)` → 独立视图矩阵（太阳角度），`MODELVIEW` / `PROJECTION` 单独维护
3. 独立 frustum：`createShadowFrustum(...)`（`AdvancedShadowCullingFrustum` / `BoxCullingFrustum` / `NonCullingFrustum`）
4. **换 `RenderBuffers`**：`playerBuffers = levelRenderer.getRenderBuffers(); levelRenderer.setRenderBuffers(buffers);`（用的是构造时 `new RenderBuffers()`）
5. `CullingDataCache.saveState()`
6. **关闭遮挡剔除**：`boolean wasChunkCullingEnabled = client.smartCull; client.smartCull = false;`
7. 处理云重建标志：`regenerateClouds = shouldRegenerateClouds(); needsUpdate(); setShouldRegenerateClouds(regenerateClouds);`
8. **再调一次原版地形 setup**：`levelRenderer.invokeSetupRender(playerCamera, terrainFrustumHolder.getFrustum(), false, false);`
9. `disableCull()`，然后 `invokeRenderChunkLayer(solid/cutout/cutoutMipped, ...)`
10. 实体：独立 `MultiBufferSource`（`buffers.bufferSource()`）+ `shouldRender` 剔除 + `renderEntity`
11. 方块实体：`renderBlockEntities(...)`
12. `copyPreTranslucentDepth(...)`；可选 `renderChunkLayer(translucent)`
13. 恢复：`enableCull()` → `mainRenderTarget.bindWrite(false)` → `RenderSystem.viewport(0,0,mainW,mainH)` → `restorePlayerProjection()` → `CullingDataCache.restoreState()` → `setRenderBuffers(playerBuffers)`

**这一段就是「第二遍渲染 + 状态保存/恢复」的完整参考实现**，可以直接映射到本项目的成本项与降耗项。

### 2.3 三条来自 Iris 源码的性能/坑位注释

- **云重建**（`ShadowRenderer.java:419` 注释、`:420-422` 代码）：
  > "We have to ensure that we don't regenerate clouds every frame, since that's what needsUpdate ends up doing. **This took up to 10% of the frame time before we applied this fix! That's really bad!**"
  → 第二遍里任何「每帧触发的重建 / 重算」都可能是 10% 级开销，必须显式屏蔽。
- **遮挡剔除被关掉**（`ShadowRenderer.java:413-414`，恢复于 `:432`）：
  > "Disable chunk occlusion culling - it's a bit complex to get this properly working with shadow rendering as-is, however in the future it will be good to work on restoring it for a nice performance boost."
  → 原版的遮挡剔除图（section occlusion graph）**与单一 frustum 绑定**；第二遍换个相机后不能直接复用，Iris 的选择是**关掉**（代价：多画可见区块）。
  → 与我们实测的动机一致：换相机后遮挡剔除图不能直接复用；原型还遇到"相机在实心方块里 → 可见集合塌缩 / 闪烁"（见 `quadrant-prototype-results.md` §3.5）。
- **区块重建队列**（`MixinPreventRebuildNearInShadowPass.java`）：Iris 还专门阻止在 shadow pass 里做近处区块重建，避免第二遍拖慢重建。

### 2.4 Iris 阴影贴图：与窗口无关的固定低分辨率

`example/Iris-1.20.1/.../shadows/ShadowRenderTargets.java:53-54`：

```java
this.mainDepth = new DepthTexture("shadowtex0", resolution, resolution, ...);
this.noTranslucents = new DepthTexture("shadowtex1", resolution, resolution, ...);
```

`resolution` 来自 shader pack 的 `shadowMapResolution`（实践 512–8192，默认 1024）。**Iris 的「第二遍」用一个远小于窗口、且与窗口分辨率无关的固定方形 FBO**。

> ⚠️ 这是**阴影贴图**的特性（阴影只存深度、允许低分辨率），**不能照搬到正式副画面**：正式播放的 lane 输出必须与主画面**同分辨率**，靠「取材区域裁切 + 铺屏」做局部取景；低分辨率 FBO 只用于**编辑器预览推流**（省带宽）。见 §4 / §5。

### 2.5 公开资料对量级的佐证

- shaderLABS 官方阴影教程：shadow pass「renders the world from the perspective of the sun」，「**Performance Impact: Shadow rendering requires rendering the scene twice, impacting framerates.**」 <https://github.com/shaderLABS/Shadow-Tutorial>
- DeepWiki（Iris 阴影实现综述）：shadow pass 是完整额外几何 pass，且被列为多数着色器包最贵的一环。<https://deepwiki.com/IrisShaders/Iris/4.4-shadow-rendering>
- 「Simply Shaded」包在解释为何直接不做阴影时写：「**Shadow maps are usually the single most expensive part of a shaderpack.**」<https://skinmc.net/project/simply-shaded>
- FISM（Faster Iris Shadow Mapper，Iris 1.11.2 起内置）在 shadow pass 中跳过「不改变阴影轮廓」的几何（告示牌文字 / 旗帜图案 / 附魔光效），作者自报：空场景 +10% FPS，告示牌/旗帜密集场景平均 +50%、0.05% low +1000%。**这是自报单机数据，只作量级参考。** <https://www.curseforge.com/minecraft/mc-mods/faster-iris-shadow-mapper>

> 注意：上述「最贵的一环」是在**开了光影 + 阴影贴图**语境下的结论。本项目的副画面是**普通透视相机**（可能比太阳的正交 / 窄视锥覆盖更大），且正式播放**不能降分辨率**，只能砍内容、砍视距 / 视锥。

---

## 3. 性能模组（Sodium / Embeddium）下的状态与代价

### 3.1 好消息：第二遍走的原版 API 会被它们接管

- Embeddium 用 `@Overwrite` 接管 `LevelRenderer.setupRender` 与 `renderChunkLayer`：
  - `example/embeddium-20.1-forge/src/main/java/me/jellysquid/mods/sodium/mixin/core/render/world/WorldRendererMixin.java:148`（`setupRender` → `renderer.setupTerrain(camera, viewport, frame++, ...)`）
  - 同文件 `:128-133`（`renderChunkLayer` → `renderer.drawChunkLayer(...)`）
  - 同文件 `:224`（`renderLevel` 内 `globalBlockEntities` 前的方块实体注入）
- 也就是说：**只要第二遍调用的是原版 `setupRender` + `renderChunkLayer`（同一个 `LevelRenderer` 实例），Sodium 就会接管它**，不需要额外适配。`Viewport` 由 `Frustum` 经 `ViewportProvider` 得到（`FrustumMixin` 实现该接口）——所以**副相机只要有自己的 `Frustum`，Sodium 的渲染中心就跟着副相机走**（与 `multi-camera-rendering.md` §11 的判断一致）。

### 3.2 坏消息：两遍/帧会让 Sodium 的缓存抖动

`example/embeddium-20.1-forge/.../SodiumWorldRenderer.java:167` `setupTerrain(...)`：

```java
boolean dirty = pos.x != lastCameraX || pos.y != lastCameraY || pos.z != lastCameraZ ||
                pitch != lastCameraPitch || yaw != lastCameraYaw || fogDistance != lastFogDistance;
if (dirty) { this.renderSectionManager.markGraphDirty(); }
...
this.lastCameraX = pos.x; ... this.currentViewport = viewport;
```

- 每帧用相机 A 调一次、再用相机 B 调一次 → 两次都判定 `dirty` → **每帧两次 `markGraphDirty` + 两次 render-list 更新**。
- `currentViewport` 被第二次覆盖；`drawChunkLayer` 用的是 `renderSectionManager` 内部状态，因此**必须 setup 完一个相机立刻画完它的层，再做下一个相机**（不能先 setup A、setup B，再画 A）。
- `frame++` 每调一次自增；Sodium 的遍历用 frame id 判断「本帧是否已见过」——两遍/帧会让计数语义变化。**是否引起错误行为未验证**（需要实测）。
- 结论：Sodium 下第二遍**能跑**，但 CPU 侧 `setupTerrain` 成本近似翻倍，且遮挡/渲染列表缓存基本失效（与 §2.3 中 Iris 直接关掉遮挡剔除相互印证）。

---

## 4. 开销量级判断

> 分两层：**§4.1 定性推导（未实测，标 [推断]）**；**§4.2 实测（探针）**。两者分开陈述，不互相替代。

### 4.1 定性推导（未实测）

**成本模型**（第二遍 = 一遍 `renderLevel` 的裁剪版；**正式播放与原画面同分辨率**）：

```
第二遍增量 ≈  terrain_setup（CPU，随视锥覆盖区块数 / 视距增长；与分辨率无关）
           + terrain 层 draw（GPU：draw call + 顶点 + 填充）
           + culling / frustum（CPU，小）
           + 状态保存/恢复（CPU，小）
           + [可选] entities / blockentities / particles / translucent / clouds / weather
```

- **前提（已确认）**：正式播放的 lane 必须与主画面**同分辨率**——低分辨率 FBO **不是**正式播放的降耗手段，它只服务**编辑器预览推流**（省带宽）。局部取景靠「取材区域裁切 + 铺屏」，不是降分辨率。
- **上限**：内容、视锥、分辨率与主渲染完全相同 → 接近**主渲染成本翻倍**（`multi-camera-rendering.md:93` 亦如此写）。[推断]
- **只区块层的裁剪版**（无实体/粒子/天空/天气/云、视距可降，但**分辨率不变**）：
  - GPU 填充：只画不透明层 + 副相机视锥 / 视距受限时，约为主渲染填充的一部分；**但填充不会因为「想省」而下降**（分辨率固定）。[推断]
  - **GPU 几何 / draw call 与分辨率无关**：可见区块数只由视锥 × 视距决定。[推断]
  - **CPU `terrain_setup` 与分辨率无关**，且（Sodium 下）缓存失效 → 近似按遍数线性增长。[推断，有 §3.2 代码支撑]
- **结论**：降分辨率省不到东西；省成本只能从三处来——**内容（画什么）+ 视距 / 视锥（画多少区块）+ 状态复用（省 CPU 抖动）**。[推断]
- **Iris 对照的意义**：Iris 的 shadow pass 用**更窄的视锥 + 固定低分辨率**（阴影贴图允许低分辨率），仍被公认为最贵一环 → 说明「每帧第二遍」本身在工程上可接受，但**不能低估**；本项目的副相机若用普通广角 + 全视距 + **原分辨率**，单遍成本会**高于** shadow pass。[推断]
- **不设上限的 N lane**：成本近似按 lane 数线性叠加（每 lane 一遍），这与 `parallel-playback.md`「不设相机数上限、由作者自控 + 档位」的定位一致。 **原型实测（重场景）**：每 lane ≈ 3.4–3.6 ms ≈ 1.2–1.4× 主画面，1 / 4 / 16 / 25 画面线性、无拐点；另有一次性**预热**（区块按视角编译，前 1–4 秒明显更重、画面越多越长）——见 `quadrant-perf/summary.md`。

### 4.2 实测（探针）

> 来源：探针分支 `perf/second-pass-probe`（一次性代码，非模组本体；**该分支已按"测完即删"删除，本节数字为留档**）。数字均为 **实测**，与 §4.1 的定性推导区分。

**口径**：1.0x 原分辨率、**上限口径**（完整 `renderLevel` 递归，实体 / 粒子 / 天气全在内）、`quickPlay` 进《新的世界》、视距 12、脚本目录已清空、每档 20–60s 稳态、后台未锁帧。N = 副画面遍数。

| N | main (ms) | pass_avg (ms) | total (ms) | interval (ms) | ratio |
|---|---|---|---|---|---|
| 0 | 0.72 | — | — | 1.16 | — |
| 1 | 0.53 | 0.48 | 0.48 | 1.37 | 0.90 |
| 2 | 0.63 | 0.56 | 1.12 | 2.19 | 1.79 |
| 3 | 0.65 | 0.58 | 1.73 | 2.87 | 2.66 |
| 4 | 0.55 | 0.48 | 1.92 | 3.04 | 3.48 |
| 5 | 0.65 | 0.58 | 2.90 | 4.00 | 4.46 |

**拟合**：

- `total ≈ 0.555·N − 0.030 ms`（R²=0.98）
- `interval ≈ 0.568·N + 1.018 ms`（R²=0.97）

**实测结论**：

- 曲线基本**线性**：每多一遍完整世界渲染 ≈ **+0.56 ms**，≈ **0.85×** 主 `renderLevel`（`pass_avg / main`）。
- N=5 无超线性偏离：拟合 2.75 ms / 5×斜率 2.78 ms / 实测 2.90 ms。
- N=5 时世界渲染成本 ≈ **5.5×** 单遍。
- **口径说明**：本机为轻场景（新世界 / 视距 12 / 无光影模组 / 后台未锁帧），**绝对值偏小**；斜率随场景（视距、区块数、模组）放大。

**实测发现的风险（新增，重要）**：

- 旋转视图矩阵后若**重建视锥**，会卡死在 `Frustum.offsetToFullyIncludeCameraCube` 的病态膨胀循环（jstack 实证）。
- 探针各遍**沿用主视锥**（过包含剔除，属上限口径）；**正式实现必须自带剔除方案**，不能直接复用主视锥，也不能天真地「按副相机重建视锥」。
- 这条风险与 §5「独立窄视锥 / 降视距」手段直接相关：窄视锥是降耗手段，但**视锥的构造方式**本身有踩坑点，需专门实现与验证。

---

## 5. 降耗手段（按收益 / 成本排序）

> **分辨率不是本节的选项**：正式播放 lane 与主画面同分辨率。下表只列「内容 / 视距·视锥 / 状态 / 时间摊薄」几类手段。

| 手段 | 省什么 | 代价 / 风险 | 证据 |
|---|---|---|---|
| **只走区块层**（solid/cutout/cutoutMipped[/translucent]），不整段 `renderLevel` | 省掉 sky/clouds/weather/particles/entities/BE/outline/debug/手 | 需自己维护一整套状态保存恢复 | Iris `renderShadows` 就是这条路 |
| **内容开关**（实体 / 粒子 / 天空 / 天气 / 云 默认关） | 对应 pass 的 CPU+GPU | 副画面可能「空」；看需求 | Iris `shouldRender*` 开关 |
| **独立窄视锥 / 降视距** | 可见区块数 → terrain_setup + draw call | 画面裁切 / 视距不一致 | Iris `createShadowFrustum` 的 distance 上限；`renderDistanceMultiplier` |
| **档位**（叠化仅过渡期双渲染；分屏长期双渲染设档） | 时间维度摊薄 | — | `multi-camera-rendering.md` §6 |
| **独立 `RenderBuffers` + 显式 save/restore** | 避免污染主渲染 / 状态错乱 | 实现复杂度 | Iris（否则「very weird things」） |
| **屏蔽每帧重建 / 重算**（云、区块重建队列） | 可避免 10% 级尖峰 | 需找到所有触发点 | Iris 注释（10%）；`MixinPreventRebuildNearInShadowPass` |
| **复用而非重建**：Frustum / RenderTarget / 矩阵对象按 lane 复用 | GC / 分配抖动 | 需生命周期管理 | 通用工程实践 [推断] |
| **检测 Sodium/Embeddium 后禁用副画面** | 规避 §3.2 的缓存抖动 | 功能损失 | `multi-camera-rendering.md` §7.1 推荐策略 |

> 遮挡剔除：原版 `Minecraft.smartCull` + section occlusion graph 与单 frustum 绑定。Iris 在第二遍**关掉**它。本项目若想要遮挡收益，需要**按 lane 维护独立剔除图**——`multi-camera-rendering.md` §11 提到的「复用 Sodium 的 Camera/Frustum 管线」是另一条路，但 §3.2 表明 Sodium 的图缓存同样会被两遍/帧打散。

---

## 6. 实测可行性（本机）

### 6.1 能做的部分：客户端**跑得起来**

- 有历史运行证据：`fabric/run/`（`options.txt`、`logs/latest.log`、`saves/`、`screenshots/`、`crash-reports/`、`hs_err_pid*.log`）。`latest.log` 显示正常进入客户端（LWJGL 3.3.2、`Setting user`、资源重载完成）。
- 机器：i7-12700H / 63G / Windows 11（见 `fabric/run/hs_err_pid49904.log` 头部 Host）。
- 工具链齐全：JDK 21（`~/.gradle/jdks/eclipse_adoptium-21-amd64-windows/jdk-21.0.12+8`）、loom/forge 缓存热、`common|fabric|forge/build/libs` 已有 0.3.5 产物。
- `runClient` 任务存在：`fabric/build.gradle`（`runs.client`，runDir `run`）、`forge/build.gradle`（`runs.client`，runDir `run`）。
- 历史崩溃是 **OpenAL.dll 原生崩溃**（`CinematicAudioInstance.<init>` → `alBufferData`），与 GL / 渲染无关，不构成渲染测量障碍。
- 参考配置：`renderDistance:12`、`graphicsMode:1`（Fancy）、`particles:0`、`enableVsync:true`、`maxFps:120`（`fabric/run/options.txt`）。

### 6.2 模组本体为何仍无法直接测（探针如何绕过）

> 探针（`perf/second-pass-probe`，**分支已删**）已用**一次性分支**绕过下列障碍，产出 §4.2 数字；模组本体在正式实现前仍无法直接测量。

| 障碍（针对模组本体） | 说明 / 探针如何绕过 |
|---|---|
| **没有被测对象** | 模组内第二遍渲染功能**尚未实现**（§1.1）。探针在独立分支里补了一个完整 `renderLevel` 递归 pass 作为被测对象。 |
| 无 headless GL | 客户端是 GUI 程序；本环境无 headless / offscreen 方案，需真实窗口。探针走真实窗口 + `quickPlay` 自动进世界。 |
| 无输入自动化 / 场景脚本 | 需自动「建/进世界 → 稳定采样」。探针用 `quickPlay` 进《新的世界》+ 清空脚本目录，避免人工操作。 |
| 无帧耗时遥测 | 模组内只有 `System.nanoTime()` 做时间基（`CameraManager`/`FlightController`），**没有帧耗时统计 / 导出**。探针自带计时。 |
| 只读契约 | 本次调查不得改模组源码；探针因此放在**独立分支**，不回灌主线。 |

### 6.3 测量方案（探针已执行；正式实现后按此复测）

> 探针（`perf/second-pass-probe`，**分支已删**）已按此思路执行并产出 §4.2；下列方案保留给**正式 lane 实现**后的复测。

1. **埋点**（实现原型时）：在第二遍代码路径用 `ProfilerFiller.push("second_pass")` 包住；另用 `System.nanoTime()` 累计「整帧 ms」与「second_pass ms」，每 N 帧输出 p50/p95；提供**运行期开关**（第二遍 on/off、内容开关、视距档）以做 A/B。
2. **控制变量**：固定相机位姿（静态脚本）、固定世界与视距、`enableVsync:false`、固定 `maxFps`；每档采样 ≥ 600 帧，丢弃前 ~2s 预热。
3. **指标**：整帧 ms、second_pass ms，以及 profiler 细分（`terrain_setup` / `terrain` / `entities`）。
4. **对照矩阵**（分辨率固定 = 主画面分辨率）：{第二遍 off, 只区块层, 只区块层+实体, 全内容} × {视距 12, 8}。低分辨率只用于**编辑器预览推流**的带宽评估，不作为正式播放档位。
5. **外部佐证（可选）**：装 Iris + 一个带阴影的 shader pack，用「阴影开 / 关」的 FPS 差估算「每帧一遍世界渲染」的经验占比——但这是**另一种第二遍**（太阳视锥 + 固定低分辨率），只能作量级参照，且需额外装环境。
6. **风险提示**：`Sodium/Embeddium` 存在时须先按 §3.2 验证两遍/帧不触发错误（先做「能跑对」再做「跑得快」）。

---

## 7. 未决问题

- 副画面是否需要**独立光照 / 光照更新**？（若需要，`light_updates` 段不可省，成本上升）——未验证。
- **视锥构造风险（实测）**：旋转视图矩阵后重建视锥会卡死在 `Frustum.offsetToFullyIncludeCameraCube` 的病态膨胀循环（jstack 实证，见 §4.2）；正式实现必须自带剔除方案，不能直接复用主视锥、也不能天真地「按副相机重建视锥」——待设计。
- 两遍/帧在 **Sodium/Embeddium** 下 `frame++` 语义是否会导致区块渲染错误——**需实测**。
- 副画面在 **Iris/Oculus** 光影环境下的可用性（能否拿到「已处理画面」）——`multi-camera-rendering.md` §7 已列为待调研，本次未触及。
- 「N lane」的成本曲线（线性 / 次线性）与档位默认值——**探针给出轻场景线性结果（§4.2）**，重场景 / 加模组后待复测。
- 遮挡剔除能否按 lane 独立维护（拿回 Iris 放弃的那部分收益）——未验证。

---

## 8. 证据索引

| 结论 | 出处 |
|---|---|
| 原版 `renderLevel` 调用序列 + profiler 段名 | `javap -p -c` 反汇编 `LevelRenderer.class`（loom 缓存 jar，见 §1.2 路径），方法行 3617–4737 |
| 单帧只调一次 `LevelRenderer.renderLevel` | 同 jar `GameRenderer.class` 反汇编，行 3065 |
| 现状为单相机替换 | `common/src/main/java/com/immersivecinematics/immersive_cinematics/mixin/CameraMixin.java`、`GameRendererMixin.java`、`LevelRendererMixin.java` |
| 每帧第二遍世界渲染的完整实现 | `example/Iris-1.20.1/src/main/java/net/irisshaders/iris/shadows/ShadowRenderer.java`（`renderShadows` 起 355 行）、`.../mixin/MixinLevelRenderer.java:141` |
| 副 pass 状态保存 / 恢复 | `ShadowRenderer.java`（viewport / RenderBuffers / smartCull / 云标志 / bindWrite / restorePlayerProjection）、`shadows/CullingDataCache.java` |
| 云重建 10% 帧耗时注释 | `ShadowRenderer.java:419` |
| Iris 阴影贴图固定低分辨率（仅阴影贴图适用，**不适用于正式 lane 输出**） | `shadows/ShadowRenderTargets.java:53-54` |
| Sodium/Embeddium 接管原版 setup/render 层 | `example/embeddium-20.1-forge/.../mixin/core/render/world/WorldRendererMixin.java:128,148,224` |
| Sodium `setupTerrain` 的相机缓存 / graph dirty | `example/embeddium-20.1-forge/.../client/render/SodiumWorldRenderer.java:167` |
| 第二遍可行、成本量级 | shaderLABS Shadow-Tutorial；DeepWiki Iris 4.4；Simply Shaded；FISM（链接见 §2.5） |
| **第二遍实测数字**（+0.56 ms/遍，线性；`total ≈ 0.555·N − 0.030 ms`, R²=0.98） | 探针分支 `perf/second-pass-probe`（§4.2；1.0x 原分辨率、上限口径、轻场景；**分支已删，数字留档**） |
| 视锥重建病态循环 | 探针 jstack（`Frustum.offsetToFullyIncludeCameraCube`，见 §4.2） |
| 本机可跑客户端 | `fabric/run/{options.txt,logs/latest.log,hs_err_pid49904.log}`；`fabric/build.gradle`、`forge/build.gradle` 的 `runs.client` |

# 跨维度相机的目标光照贴图绑定

> 状态：调查完成（2026-10-26）；`GameRenderer.lightTexture` 字段换入已落地，待统一实机验收。
> 范围：F8 跨维度 lane 的客户端目标 `LightTexture` 绑定。服务端区块 / 实体流、相机租约池、光影 per-pipeline 历史不在本文。
> 关联：[跨维度相机](cross-dimension-camera.md)、[多相机渲染](multi-camera-rendering.md)、[光影兼容](iris-oculus-compat.md)、[相机租约池](camera-pool.md)。

## 一、结论

目标维度的 `LightTexture` 已经创建、已经更新，但**从未被选中**。

`LaneRenderer` 把 `view.lightTexture()` 作为 `LevelRenderer.renderLevel(..., lightTexture, ...)` 的参数传进去；这个参数在原版只用于天气和粒子，**地形 / 实体 / 方块实体不走它**。地形和实体走 `RenderStateShard.LightmapStateShard`，最终调用：

```java
Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
```

Sodium / Embeddium 的地形 shader 也不读 `renderLevel` 的参数，而是读 `RenderSystem.getShaderTexture(2)`。

因此当前实际绑定的是主世界的 `GameRenderer.lightTexture`，或者上一段 pass 留下的 0；目标 view 自己的 lightmap 没有进入 texture unit 2。**“发暗”就是目标 lightmap 没有被采样；主世界是不是白天，与目标画面没有关系。**

## 二、现象与复现口径

- 玩家留在主世界（白天），跨维度 lane 指向末地 / 下界；主画面正常，两个目标画面发暗。
- Iris 光影关闭、Sodium 0.5.13 仍在（见 `fabric/run/logs/latest.log` 的 mod 列表）。**“无光影”不等于“无 Sodium”**。
- 用户反馈“原版下也有”，需要区分两条路径：
  - 无 Sodium：原版 `LightmapStateShard` 会绑**主世界** lightmap；目标用的不是自己的 lightmap，但不一定黑（除非主 lightmap 未更新 / 被关闭）。
  - 有 Sodium / Embeddium：地形 shader 读 unit 2；目标路径没有写入目标 lightmap 时，unit 2 可能是 0，地形采样黑。这与“发暗”一致。
- 末地 / 下界本身比主世界白天暗，这是维度光照的正常差异。验收必须和**同一目标维度的正常画面**比较，不能拿主世界白天的亮度当目标画面的正确值。

## 三、调查证据

### 3.1 当前 lane 只更新、只传参，没有换绑定源

`common/src/main/java/com/immersivecinematics/immersive_cinematics/client/lane/LaneRenderer.java`：

```java
LightTexture lightTexture = crossDimension ? view.lightTexture() : gameRenderer.lightTexture();
...
view.lightTexture().tick();
view.lightTexture().updateLightTexture(partialTick);
...
renderer.renderLevel(poseStack, partialTick, nanoTime, false, camera, gameRenderer,
        lightTexture, projection);
```

- `view.lightTexture()` 的数据被算出来了；
- 它被作为参数传给 `renderer.renderLevel`；
- 但没有任何代码把 `GameRenderer.lightTexture` 指向它，也没有在 lane pass 里建立“当前 view 的 lightmap”这一绑定。

### 3.2 `LevelRenderer.renderLevel` 的 `lightTexture` 参数不覆盖地形

`LevelRenderer.renderLevel` 中该参数只出现在：

- `renderSnowAndRain(lightTexture, ...)`（天气）；
- `particleEngine.render(..., lightTexture, ...)`（粒子）。

地形走 `renderChunkLayer(RenderType.solid()/cutoutMipped()/cutout()/translucent(), ...)`。这些 `RenderType` 的 `CompositeState` 都包含 `LIGHTMAP`；`renderChunkLayer` 开头调用 `renderType.setupRenderState()`，由 `RenderStateShard.LightmapStateShard` 绑定 lightmap：

```java
// RenderStateShard.LightmapStateShard
Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
```

也就是说，**绑定源是 `GameRenderer.lightTexture` 字段，不是 `renderLevel` 的参数**。实体、方块实体、天气之外的大部分世界绘制也走同一套 `RenderType` 状态。

### 3.3 `GameRenderer.lightTexture` 是 private final，当前没有写入口

`GameRenderer` 中：

```java
private final LightTexture lightTexture;
...
this.lightTexture = new LightTexture(this, minecraft);
...
public LightTexture lightTexture() { return this.lightTexture; }
```

`GameRendererAccessor` 目前只接入了 `getProjectionMatrix` / `getFov` / `renderHand`，注释还写着“光照纹理走公开的 lightTexture()，不需要接入”。这个判断是错的：读取公开 getter 只能拿到**当前字段**，不能把字段换成目标 view 的 lightmap。

### 3.4 Sodium / Embeddium 路径

Sodium 0.5.13（Fabric）和 Embeddium（Forge）的地形渲染：

```java
// ShaderChunkRenderer.begin
pass.startDrawing();
...
this.activeProgram.bind();
this.activeProgram.getInterface().setupState();

// TerrainRenderPass.startDrawing
this.layer.startDrawing();   // -> RenderType.startDrawing() -> LightmapStateShard

// ChunkShaderInterface.setupState
this.bindTexture(ChunkShaderTextureSlot.BLOCK, TextureUtil.getBlockTextureId());
this.bindTexture(ChunkShaderTextureSlot.LIGHT, TextureUtil.getLightTextureId());

// TextureUtil.getLightTextureId
return RenderSystem.getShaderTexture(2);
```

- `TerrainRenderPass.startDrawing()` 会走原版 `RenderType` 的状态设置，因此最终仍读 `GameRenderer.lightTexture`；
- `ChunkShaderInterface` 只读 `RenderSystem.getShaderTexture(2)`，不知道 `view.lightTexture()`；
- 如果目标 pass 没有走到 `startDrawing`（空区块 / 优化路径跳过），unit 2 会保留上一段的值，可能是 0；
- 两条路径都没有“把目标 `LightTexture` 写进 unit 2”的代码。

### 3.5 IP 的做法

`example/ImmersivePortalsMod-1.20.1` 的 `MyGameRenderer.renderWorldNew`：

1. 保存 `oldLightmap = client.gameRenderer.lightTexture()`；
2. `ieGameRenderer.setLightmapTextureManager(helper.lightmapTexture)` 把字段换成目标维度；
3. 目标维度需要时 `helper.lightmapTexture.updateLightTexture(0)`；
4. 调 `client.gameRenderer.renderLevel(...)`；
5. 最后 `ieGameRenderer.setLightmapTextureManager(oldLightmap)` 还原。

`MixinGameRenderer` 用 `@Shadow @Final @Mutable private LightTexture lightTexture;` 提供 setter；`DimensionRenderHelper` 每个维度一份 `LightTexture`。

IP 证明的是：**目标维度的 lightmap 必须通过 `GameRenderer.lightTexture` 字段进入原版绑定链**；只创建一份 `LightTexture` 并作为参数传给 `LevelRenderer` 不够。

## 四、根因

1. `DimensionView` 的目标 `LightTexture` 数据层是完整的：创建、tick、`updateLightTexture` 都有。
2. 原版地形 / 实体的 lightmap 绑定源是 `GameRenderer.lightTexture` 字段（经 `LightmapStateShard`），Sodium / Embeddium 地形 shader 则读该字段最终写入的 `RenderSystem` unit 2。
3. `LaneRenderer` 没有在 lane pass 内换这个字段，只把目标 `LightTexture` 当参数传给 `LevelRenderer.renderLevel`。
4. 所以目标 view 的 lightmap 没有进入 unit 2：实际拿到的是主世界 lightmap 或 0；发暗是后者的直接结果。

## 五、修复方案

最小改动，不动渲染架构：

1. **`GameRendererAccessor` 增加可写入口**：

   ```java
   @Accessor("lightTexture")
   @Mutable
   void ic$setLightTexture(LightTexture lightTexture);
   ```

2. **`LaneRenderer.renderLane` 在跨维度 lane 内换字段**：
   - 进入目标 pass、更新目标 `LightTexture` 后，保存 `gameRenderer.lightTexture()`；
   - 把 `GameRenderer.lightTexture` 设为 `view.lightTexture()`；
   - 调 `renderer.renderLevel(...)`（参数仍传 `view.lightTexture()`，供天气 / 粒子使用）；
   - 在 `finally` 还原原字段，异常路径同样还原；
   - 主世界 / 同维度 lane 不换字段，保持零差异。

3. **不要只调 `view.lightTexture().turnOnLightLayer()`**：后续 `RenderType.setupRenderState()` / 天气会再次绑定，显式绑定会被覆盖；字段换入才是所有路径的共同源。

4. **不需要改 `LevelRenderer.renderLevel` 的参数语义**：它只覆盖天气 / 粒子，改它不能修地形。

5. **Sodium / Embeddium 不需要单独 mixin**：字段换入后，`TerrainRenderPass.startDrawing()` 会绑目标 lightmap，`ChunkShaderInterface` 从 unit 2 读到的就是目标。

6. **Iris / Oculus**：字段换入后，`CustomTextureManager` 的 lightmap custom texture / fallback shader 也会看到目标字段；per-pipeline 历史与 shadow 仍是独立问题，见 `iris-oculus-compat.md` / `cross-dimension-camera.md` §4.5。

## 六、验收

代码阶段只做编译；实机按 F 轮统一测试执行。

1. **目标 lightmap 可见**：`test_camera_center_crossdim_view` 中末地 / 下界两条 lane 能看到本维度地形与附近实体；画面与“正常进入该维度”的观感一致，不要求与主世界白天同亮。
2. **可区分用例**：增加或复用一条目标为**白天主世界**、玩家在末地 / 下界的 lane（或主世界夜晚 + 目标白天），确认目标亮度和主世界 lightmap 不同，排除“只是绑了主世界”。
3. **主画面零回归**：主世界画面亮度、天气、粒子、手部渲染不变；主 `GameRenderer.lightTexture` 在 lane 结束后恢复。
4. **同维度 lane**：不换字段，行为与修复前一致。
5. **异常路径**：目标渲染抛异常 / 强停 / 断线 / 世界卸载时字段仍还原，不把目标 lightmap 留给主画面。
6. **渲染器矩阵**：Sodium 开 / 关；Iris 光影关 / 开；Forge Embeddium；FABULOUS 仍按现有策略拒绝目标视图。
7. **重播 / 多 lane**：多条跨维度 lane 顺序执行，每条都换入自己的 lightmap，结束后主世界字段正确。

## 七、边界

- 本文只修“目标 lightmap 没有进入绑定链”。目标维度自身的光照数据、时间 / 天空、方块 / 光照增量仍由现有数据流决定；数据没到，画面仍可能缺光。
- 目标 `LightTexture.updateLightTexture` 仍使用主玩家的 darkness / night vision 等玩家状态；per-view 玩家状态隔离不在本文。
- 光影 per-pipeline / shadow / 历史隔离不在本文；字段换入只解决 lightmap 采样源。
- FABULOUS 下目标视图仍被 `DimensionViews` 拒绝，不在本文范围。
- 服务端相机中心 / 刷怪 / 租约池不改。

## 八、证据索引

- `common/src/main/java/com/immersivecinematics/immersive_cinematics/client/lane/LaneRenderer.java`：目标 `LightTexture` 的 tick / update / 传参，无字段换入。
- `common/src/main/java/com/immersivecinematics/immersive_cinematics/mixin/GameRendererAccessor.java`：当前只接入投影 / FOV / renderHand。
- MC 1.20.1 `LevelRenderer.java`：`renderLevel` 的 `lightTexture` 参数用于天气 / 粒子；`renderChunkLayer` 调 `RenderType.setupRenderState()`。
- MC 1.20.1 `RenderStateShard.java`：`LightmapStateShard` 调 `Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer()`。
- MC 1.20.1 `GameRenderer.java`：`lightTexture` 是 private final，公开 getter 只读。
- Sodium 0.5.13 `ShaderChunkRenderer.java` / `TerrainRenderPass.java` / `ChunkShaderInterface.java` / `TextureUtil.java`：地形 shader 读 `RenderSystem.getShaderTexture(2)`。
- Embeddium `ChunkShaderInterface.java` / `TextureUtil.java`：同构路径。
- IP `MyGameRenderer.java` / `MixinGameRenderer.java` / `DimensionRenderHelper.java`：字段换入 / 还原的参考实现。

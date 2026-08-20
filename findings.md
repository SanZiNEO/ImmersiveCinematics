# Findings（调查记录）

## 待提交改动盘点（阶段1）
- 修改（tracked）：
  - `handler/ServerEventHandler.java`
  - `script/ScriptMeta.java` / `ScriptParser.java` / `schema/MetaSchemas.java`
  - `trigger/client/PreloadRequester.java`
  - `trigger/network/C2SPreloadRequestPacket.java`
  - `trigger/server/ChunkPreloadManager.java`
  - `trigger/server/ChunkTicketPool.java`
  - `resources/immersive_cinematics.mixins.json`
  - `mixin/SoundEngineMixin.java`（本次修复）
  - `forge/build.gradle`
  - `plans/0.3.5/camera-region-mechanics-5.5.md`
- 新增（untracked）：
  - `cinematics/test_camera_fixed_village.json`
  - `mixin/BiomeAmbientSoundsHandlerMixin.java`
  - `mixin/BubbleColumnAmbientSoundHandlerMixin.java`
  - `mixin/MinecraftMixin.java`
  - `mixin/UnderwaterAmbientSoundHandlerMixin.java`
  - `trigger/server/CameraFakeConnection.java`
  - `trigger/server/CameraFakePlayer.java`
  - `trigger/server/CameraMobManager.java`

## Mixin 配置现状
- `client` 列表：BiomeAmbientSoundsHandlerMixin, BossHealthOverlayMixin, BubbleColumnAmbientSoundHandlerMixin, CameraMixin, ChatComponentMixin, GameRendererMixin, GuiMixin, KeyboardHandlerMixin, LevelRendererMixin, LivingEntityMixin, LocalPlayerMixin, MinecraftMixin, MouseHandlerMixin, MouseHandlerAccessor, PlayerTabOverlayMixin, SoundEngineMixin, SoundManagerMixin, SubtitleOverlayMixin, UnderwaterAmbientSoundHandlerMixin
- `mixins` 列表：ItemUseMixin
- `defaultRequire: 1`

## 已确认事实（字节码/源码证据）

### 1. MinecraftMixin
- `ClientLevel.animateTick(int,int,int)` 在 `Minecraft.tick()` 中调用，Fabric/Forge 一致。

### 2. UnderwaterAmbientSoundHandlerMixin
- 目标：`LocalPlayer.isUnderWater()Z`，当前正确。

### 3. BubbleColumnAmbientSoundHandlerMixin
- 目标：`LocalPlayer.getBoundingBox()Lnet/minecraft/world/phys/AABB;`，当前正确。

### 4. BiomeAmbientSoundsHandlerMixin（Fabric 崩溃根因，已修）
- `tick()` 直接调用 `LocalPlayer.getX()/getY()/getZ()`。
- `getEyeY()` 在 lambda 合成方法中：
  - Fabric：`method_26271(AmbientMoodSettings)`
  - Forge：`lambda$tick$3(AmbientMoodSettings)`
- 已改为：
  - `tick` 内保留 getX/getY/getZ 重定向
  - `method_26271` 与 `lambda$tick$3` 各加 getX/getY/getZ/getEyeY 重定向，均 `require=0` 跨平台兼容
- 不写 try/catch。

### 5. SoundEngineMixin（Forge 崩溃根因，已修）
- Fabric `SoundEngine.play()` 调 `SoundBufferLibrary.getStream(ResourceLocation, boolean)`。
- Forge `SoundEngine.play()` 调 `SoundInstance.getStream(SoundBufferLibrary, Sound, boolean)`。
- 已改为：
  - 原 Fabric 目标加 `require=0`
  - 新增 Forge 目标 `SoundInstance.getStream(...)`，`require=0`
- 不写 try/catch。

### 6. ChunkPreloadManager / ChunkTicketPool 逻辑问题（未修）
- `requestTickets()` 不再为 far 加实际 ticket，只标记 `st.ticketed`。
- `enterFar()` 合并 prewarm 时：
  ```java
  st.ticketed.addAll(st.prewarm.ticketed);
  st.prewarm = null;
  ```
  没有释放 prewarm 在 `ChunkTicketPool` 里的实际 region ticket 引用。
- 之后 `clearCameraArea()` / `release()` 不会释放这些 ticket -> ticket 泄漏。

### 7. 假人运行时风险（未修）
- `CameraFakePlayer.tick()` 不调用 `super.tick()`，每 10 tick `connection.resetPosition()` + `chunkSource.move(this)`。
- `CameraMobManager.updateFakePlayerPosition()` 每 10 tick 调 `connection.teleport(...)`；假连接不回 ACK，`awaitingTeleport` 可能累积。
- `CameraFakeConnection` 无 Netty channel；`Connection` 的 `setListener/setReadOnly/tick` 对 null channel 有保护，理论上可工作。
- `PlayerList.remove()` 会保存假人玩家数据，可能产生玩家数据文件副作用。

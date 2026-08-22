# 0.3.5 最后一轮问题记录（Final Round）

**状态**: 记录中（部分已处理）
**日期**: 2026-08-22

---

## 1. Forge 不应使用 Fabric 导向的通用实现：SoundEngineMixin 与 MixinExtras

### 背景

- 0.3.5 音频改走原版 `SoundEngine` 时，`SoundEngineMixin` 被写成了 common 通用 Mixin。
- 为了兼容 Fabric 上已有模组对同一个 `getStream` 调用点的 `@Redirect`，使用了 MixinExtras 的 `@WrapOperation`。
- 当时主要在 Fabric 平台测试，Forge 平台没有充分验证。
- 结果：Forge 生产环境普通 jar 不包含 MixinExtras，`SoundEngineMixin` 一加载就崩，必须用 `-all.jar` 内置 MixinExtras。

### 根因

- 这是“两端共用非公共功能代码”导致的问题。
- Forge 并不需要 `@WrapOperation`；这个写法是为了迁就 Fabric 的冲突才引入的。
- 既然已经移除 Architectury，就不应该再把这类平台相关逻辑强行写成 common。

### 处理状态

✅ 已处理（2026-08-22）

- Forge `SoundEngineMixin` 已拆分为平台专属实现：Forge 用原版 `@Redirect`，不依赖 MixinExtras。
- Fabric `SoundEngineMixin` 保留 `@WrapOperation`。
- Forge 普通 jar 不再需要 MixinExtras，不再依赖 `-all.jar`。

---

## 2. 渲染中心：LevelRendererMixin 与 Embeddium/Sodium 冲突

### 处理状态

✅ 已处理（2026-08-22，开发环境 + 生产环境验证）

- 新增 `ImmersiveCinematicsMixinPlugin`，检测 `sodium` / `rubidium` / `embeddium`。
- 检测到这类渲染优化模组时跳过 `LevelRendererMixin`。
- 由它们的 Camera/Frustum 管线接管渲染中心；`CameraMixin` 仍驱动虚拟相机。
- 实测远端画面能正常渲染，功能未丢失。

---

## 3. Forge 打包：MixinExtras 依赖混乱

### 处理状态

✅ 已处理（2026-08-22）

- Forge 不再内置 MixinExtras。
- 普通 jar `ImmersiveCinematics-forge-1.20.1-0.3.5.jar` 已验证不含 MixinExtras。
- 不再需要 `-all.jar`。

---

## 4. MixinConfigPlugin 使用 Java 反射不稳定

### 问题

- `ImmersiveCinematicsMixinPlugin` 曾用 `Class.forName` + 反射调用 Forge/Fabric 加载器 API 来检测 mod。
- 反射在早期加载阶段不稳定，曾导致 `ModList.get()` 为 null 时 NPE。
- 用户明确要求：**不要用反射**。

### 处理状态

✅ 已处理（2026-08-22）

- 删除 common 里的反射插件类。
- Forge 侧新增同名插件类，直接使用 `FMLLoader` / `ModList` API。
- Fabric 侧新增同名插件类，直接使用 `FabricLoader` API。
- common 不再包含平台加载器反射逻辑。

---

## 5. @Redirect 安全化（待处理）

### 问题

- `@Redirect` 会直接改写原版方法调用点，容易成为其他脆弱模组的“导火索”。
- 已确认：`GameRendererMixin` 的 `@Redirect` 在 `Mth.lerp` 上导致 SecurityCraft `@ModifyVariable` 失败。

### 处理方向

- 能不用 `@Redirect` 就不用。
- 优先替换为：
  - `@Inject`（HEAD/RETURN，可取消）
  - `@ModifyArg`（只改参数）
  - `@ModifyVariable`（改局部变量）
  - `@WrapOperation`（Fabric 可用；Forge 因不想带 MixinExtras，需平台专属方案）
- 远离模组热点方法（如 `GameRenderer.renderLevel`、`LevelRenderer.setupRender`、`SoundEngine.play`）。
- 对已知脆弱模组做定向退让（扩展 MixinConfigPlugin，按模组跳过/替换具体注入点）。

### 当前高风险 @Redirect 清单

- `GameRendererMixin` → `renderLevel` 的 `Mth.lerp`（暂保留，待实测）
- `LevelRendererMixin` → `setupRender` 的 `SectionPos.posToSectionCoord` / `ViewArea.repositionCamera`（✅ 已改为 `@ModifyVariable`）
- `PlayerListMixin` → 多处 `@Redirect`（保留，服务端低风险）
- `SoundManagerMixin` → listener 更新相关 `@Redirect`（✅ 已改为 `@ModifyArg`）
- `BiomeAmbientSoundsHandlerMixin` / `BubbleColumnAmbientSoundHandlerMixin` / `UnderwaterAmbientSoundHandlerMixin` → 玩家位置/状态相关 `@Redirect`（BubbleColumn ✅ 已改为 `@ModifyArg`；Biome / Underwater 保留，源码结构不支持温和替代）
- Forge `SoundEngineMixin` → `SoundInstance.getStream` 的 `@Redirect`（保留，返回值链式调用无法 `@ModifyVariable`）

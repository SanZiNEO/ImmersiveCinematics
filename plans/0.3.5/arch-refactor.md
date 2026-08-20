# 0.3.5 第 7 轮：去除 Architectury 架构重构

**状态**: 📋 方案已定，Spike 已验证通过
**分支**: `refactor/remove-arch`
**排期**: 第 6 轮（文档/回归/发布）之前，先于 5.5 或与 5.5 并行（以实际进度为准）

---

## 目标

- 移除 `dev.architectury.*` 全部 import 与运行时依赖
- 不依赖第三方前置（Architectury API）
- 为 0.4.0 多版本（每 MC 版本一个分支）铺路

---

## 当前 Arch 使用面（已统计）

| 模块 | 使用内容 | 影响 |
|---|---|---|
| 网络 | `SimpleNetworkManager` / `BaseC2SMessage` / `BaseS2CMessage` / `MessageType`（15+ 包类） | 大 |
| 服务端事件 | `CommandRegistrationEvent` / `EntityEvent` / `InteractionEvent` / `LifecycleEvent` / `PlayerEvent` / `TickEvent` | 中 |
| 客户端事件 | `ClientGuiEvent` / `ClientTickEvent` | 小 |
| 键位注册 | `KeyMappingRegistry` | 小 |
| 环境 | `Env` / `EnvExecutor` | 小 |
| Forge 入口 | `EventBuses.registerModEventBus` | 小 |

---

## 迁移策略

### 第一步：Spike（验证构建骨架）

- 用 `multiloader-common` + `multiloader-loader` + LegacyForge 搭最小骨架
- 验证项：
  - 1.20.1 Forge + Fabric 能否同时编译
  - common 能否消费 MC 类
  - mixin 能否在 Forge/Fabric 正常加载
  - 产物 jar 能启动
- 若 Spike 失败 → 降级为“保留 Architectury Loom 构建，仅去 Arch API 运行时依赖”

### 第二步：网络层迁移

- common 定义 `CinematicNetwork` 接口
- Fabric：`ServerPlayNetworking` / `ClientPlayNetworking`
- Forge：`NetworkChannel` / `SimpleChannel`
- 逐个迁移 15+ 包类，保持包结构/语义不变

### 第三步：事件层迁移

- common 只留业务逻辑
- Fabric：Fabric API 事件
- Forge：Forge 总线事件
- `ServerEventHandler` / `ClientEventHandler` 拆成平台注册 + common 逻辑

### 第四步：键位 / 环境 / 入口

- 键位：Fabric `KeyBindingHelper`，Forge `RegisterKeyMappingsEvent`
- 环境：平台入口直接传 Side，去掉 `EnvExecutor`
- Forge 入口：去掉 `EventBuses.registerModEventBus`

### 第五步：构建收尾

- 移除 `architectury-plugin` / Arch API 依赖
- `gradlew build` 全平台通过
- 体积对比记录

---

## 迁移进度（当前）

- ✅ 构建系统已切换为 Java17 MultiLoader（ForgeGradle + VanillaGradle + Fabric Loom）
- ✅ 移除全部 `dev.architectury.*` Java import
- ✅ 移除 fabric.mod.json / mods.toml 的 architectury 依赖
- ✅ 网络层改为自研 `CinematicPacket` / `NetworkBridge`，Fabric/Forge 各自注册
- ✅ 事件层改为 common 纯逻辑 + 平台转发
- ✅ `:common:compileJava` / `:fabric:compileJava` / `:forge:compileJava` 全部通过

### Fabric 事件补齐（已通过 Mixin 完成）

- ✅ `advancement` 成就：`PlayerAdvancementsMixin`
- ✅ `item_craft` 合成：`ResultSlotMixin`
- ✅ `item_pickup` 拾取：`ItemEntityMixin`
- ✅ `item_drop` 丢弃：`PlayerMixin`
- ✅ `dimension_change` 维度：`ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD`
- ✅ 存档保存：`MinecraftServerMixin`（saveEverything）
- 这些 Mixin 放在 `fabric` 模块，不影响 Forge

---

## Spike 验证结果

- 使用 `MultiLoader-Template` 的 **1.20.1 分支 Java 17 版本**（ForgeGradle 6 + VanillaGradle + Fabric Loom 1.6.x）
- 在本机 Java 17 / Gradle 8.8 下验证：
  - `:common:compileJava` ✅
  - `:fabric:compileJava` ✅
  - `:forge:compileJava` ✅
- 结论：**不需要 Java 21，也不依赖 Architectury**，可以用这套模板做实际迁移
- 注意：模板 1.20.1 分支后来切到 ModDevGradle/LegacyForge 2.0.77 需要 Java 21；我们应使用切换前的 ForgeGradle 版本

---

## 风险与回退

- Spike 失败 → 回退方案 A（保留 Loom，只去 Arch API）
- 每一步保持可编译、可回滚
- 迁移期间不新增功能，只做等价替换

---

## 参考

- `Jaredlll08/MultiLoader-Template`：当前模板为 Fabric + NeoForge，需确认 LegacyForge 插件形态
- `VazkiiMods/Neat`：Xplat + Fabric + NeoForge，结构可参考
- 1.20.1 Forge 需要 `net.neoforged.moddev.legacyforge`（待验证）

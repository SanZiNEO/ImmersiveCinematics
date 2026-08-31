# 0.3.5 收尾问题记录（Round 6）

**状态**: 历史记录（0.3.5 已关闭）
**日期**: 2026-08-22

---

## 1. Forge 打包：MixinExtras 依赖方式混乱

### 背景

- 0.3.5 的 Forge 侧 `SoundEngineMixin` 使用 MixinExtras 的 `@WrapOperation`。
- 生产环境（非开发环境）下，**不带 MixinExtras 的普通 jar 会直接崩溃**：
  - 报错类似 `ClassMetadataNotFoundException: com.llamalad7.mixinextras.injector.wrapoperation.Operation`。
- 因此 Forge 分发必须使用 `ImmersiveCinematics-forge-1.20.1-0.3.5-all.jar`，它通过 jar-in-jar 内置了 `mixinextras-forge-0.3.6.jar`。

### 当前问题

- `-all.jar` 内置的 MixinExtras 0.3.6 与整合包中其他模组自带的 MixinExtras 版本重复。
- 本次用户整合包日志里出现多个 MixinExtras 版本：
  - `0.5.0`
  - `0.4.1`
  - `0.3.6`
  - `0.2.0-beta.6`
- Forge 的 `UniqueModListBuilder` 会按版本号选择最高版本（最终选 `0.5.0`），所以当前不是崩溃直接原因，但：
  - 打包内容重复、版本混乱；
  - 不同模组对 MixinExtras 的 API 兼容面不一致，后续可能出现难以排查的隐蔽问题。

### 待处理方向（未定）

- 保持现状：继续用 `-all.jar` 内置 MixinExtras 0.3.6，靠 Forge 去重。
  - 优点：用户不需要手动装 MixinExtras，普通 jar 不会崩。
  - 缺点：只要整合包里已有 MixinExtras，就必然出现重复。
- 升级内置版本：把 jar-in-jar 的 MixinExtras 升到与主流一致的版本（如 0.5.0），减少版本差。
  - 仍会重复，但至少 API 更一致。
- 不内置、要求用户自行安装 MixinExtras：
  - 可以避免重复，但会破坏“放入 mods 即可用”的体验，且普通 jar 又会崩。
- 暂不建议 relocation/shade MixinExtras：MixinExtras 的 ServiceLoader/类名机制依赖原始包名，重定位风险高。

### 结论

- 这个问题先记录，不在 0.3.5 内强行改。
- 后续单独设计 Forge 依赖打包方案，再决定是否升级版本或改用其他分发方式。

---

## 2. （待补充）渲染优化模组兼容

- 已发现 `LevelRendererMixin` 与 Embeddium/Sodium 系 `WorldRendererMixin` 冲突。
- 等拿到渲染优化模组源码后再补充到本文档。

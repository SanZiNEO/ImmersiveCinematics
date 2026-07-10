# 修复计划：隐藏电影镜头下的手臂和HUD ✅ 已完成

> 所有变更已实施并验证。本文档为压缩归档版。

## 问题

P 键切换到电影镜头时，玩家手臂、物品栏和状态栏仍然显示。

## 根因

- **手臂渲染**：`GameRenderer.renderItemInHand()` 检查的是 `CameraType.isFirstPerson()`，而非 `Camera.isDetached()`。因此 `isDetached=true` 不影响手臂渲染。
- **HUD**：原 GuiMixin 逻辑正确，但 HUD 改为使用 Forge 事件 `RenderGuiOverlayEvent.Pre` + 白名单机制（更灵活且兼容模组）。

## 已实施的修复

| # | 文件 | 修改 |
|---|------|------|
| 1 | `GameRendererMixin.java` | 添加 `onRenderItemInHand` 拦截（同时跳过 ScreenEffectRenderer） |
| 2 | `CameraMixin.java` | 修正 `isDetached` 注释（移除"隐藏手臂"的错误说明） |
| 3 | `HudOverlayHandler.java` | 白名单机制替代暴力取消，逐 overlay 过滤 |

## 超出原计划的改进

- 额外屏蔽 `bobHurt`（受伤摇晃）
- 额外屏蔽 `bobView`（跑动摇晃）
- 额外屏蔽 `spinningEffectIntensity`（反胃/下界传送门扭曲，通过 @Redirect 实现）

# Phase 2 — 修复与完善计划

> 基于冒烟测试结果（2026-05-02）制定的下一阶段修复计划。
> 优先级按 P0（阻塞级）→ P1（重要）→ P2（优化）排列。

---

## P0 — 验证发现已失效的功能

### 0-1. 跳过键 C 长按 3s 失效

**症状**：修改跳过键从 Esc→C、长按时间从 500ms→3000ms 后，按 C 不触发跳过。

**日志**：无相关错误日志。

**可能原因**：
- `CinematicKeyBindings.onClientTick()` 未收到 `TickEvent.ClientTickEvent`（event bus 注册问题）
- `SKIP_KEY.isDown()` 始终返回 false（KeyMapping 状态未更新）
- 参数文件冲突（旧 key 绑定缓存）

**验证步骤**：
1. 确认 `Immersive_cinematics.ClientTickEvents` 中 `CinematicKeyBindings.onClientTick()` 被调用
2. 确认 `CinematicKeyBindings.SKIP_KEY` 注册为 `GLFW.GLFW_KEY_C`（67）
3. 确认 `KeyboardHandlerMixin` 对 C 键放行（`SKIP_KEY.matches` 检查）

---

### 0-2. interruptible=false 的脚本在排队播放时完全阻塞

**症状**：运行 1.json（interruptible=false）时再 play 2.json，日志显示"脚本不可打断"，排队未生效。

**日志**：
```
[19:36:12] [Render thread/DEBUG] [ImmersiveCinematics/CameraManager/]: 脚本不可打断(interruptible=false)，拒绝抢占
```

**分析**：当前行为正确但不符合用户预期。用户期望：不可打断的脚本被"排队"（pendingScript），等当前脚本自然结束后自动开始下一个。但当前 `playScript()` 在 `interruptible=false` 时直接返回 false，并未记录 `pendingScript`。

**修复方案**：当 `interruptible=false` 时不拒绝，改为存入 `pendingScript` 并返回成功消息："脚本已排队，当前脚本结束后自动播放"。

---

### 0-3. morph 过渡已实现

**现状**：`transition: "morph"` 和 `transition_duration` 已完整实现。
- 在 `transition_duration` 内从上一片段的末帧线性飞向下一片段的首帧（位置 + 角度）
- 属性级插值使用 lerp/blendAngle 混合

**关于 dissolve crossfade**：另见 `plans/script_system.md` §crossfade — 需要离屏渲染覆盖层实现真正的 alpha 溶解。

---

## P1 — 重要功能修正

### 1-1. block_mob_ai 不能清除已有目标

**症状**：设为 true 后，新生物不会锁定玩家，但已锁定的生物继续攻击。

**根因**：`MobMixin` 只拦截了 `Mob.setTarget()` 的后续调用，没有在脚本激活时清空已有的 target。

**修复方案**：在 `CinematicController.apply()` 或 `CameraManager.startScriptInternal()` 中，遍历世界中所有 Mob，如果其 target 是玩家则调用 `mob.setTarget(null)`。

**注意**：此操作需要服务端支持（单机可用），联机需 C/S 架构。

---

### 1-2. HUD 子控制与 hide_hud 的层级关系

**症状**：`hide_hud=true` 时，`block_chat=false` / `block_scoreboard=false` / `block_action_bar=false` 无效（全隐藏）。只有 `hide_hud=false` 时子控制才生效。

**用户期望**：子控制应独立于 `hide_hud`。即 `hide_hud=true` 时也能单独放行 chat/scoreboard/action_bar。

**修复方案**：修改 `HudOverlayHandler` 逻辑——只要脚本激活，子控制就独立生效，不依赖 `hide_hud` 的开关。

---

### 1-3. 键鼠屏蔽解除后输入状态恢复

**症状**（用户描述）："脚本结束后还要接着之前的操作进行，比如我是一直按着w和鼠标右键，我在往前走的时候触发了动画，那动画执行被我们退出，这期间我们一直在游戏侧有着重复输入的可能。"

**排查**：当 keyboard/mouse 被屏蔽后，  不会收到 key release 事件。脚本结束时 `blockKeyboard`/`blockMouse` 恢复为 false，但如果用户在脚本期间松开了 W，KeyMapping 没有收到 release，系统认为 W 仍然按下，导致脚本结束后玩家自动往前走。

**修复方案**：在 `CinematicController.revert()` 或 `deactivateNow()` 中重置所有 KeyMapping 状态（`KeyMapping.setAll()`），清除脚本期间累积的"幽灵按键"。

---

### 1-4. pendingScript 排队：interruptible=true 的场景

**症状**：`interruptible=true` 的脚本在被打断时未触发排队、退场、自动播放流程。

**排查方向**：
- 确认 `requestExit(INTERRUPTED)` → `deactivate()` → fade-out 完成后 → `deactivateNow()` → `pendingScript` 分支是否正确触发
- 确认 `deactivateNow()` 中 `pendingScript != null` 时能启动下一个脚本

---

## P2 — 优化与补全

### 2-1. 片段级 speed 已删除

**决定**：`CameraClip.speed` 字段及整个 `SpeedCurve` 速度曲线引擎已从代码中删除。关键帧之间为匀速直线运动，`s = t`。要调速请插更多关键帧。

---

### 2-2. ArcLengthLUT 缓存

**现状**：每次 `interpolatePosition` 调用都 new `ArcLengthLUT`。一个 clip 每帧重建一次 LUT，bezier 段过多时有性能风险。

**优化方案**：在 `KeyframeInterpolator` 中缓存 LUT，key 为 `(segmentFromIndex, clipHashCode)`。

---

### 2-3. Letterbox 动画平滑度

**症状**（用户描述）："letterbox的平滑度有待提升"。

**排查方向**：`LetterboxLayer.tick()` 的缓动函数是否是线性？可考虑加上 ease-in-out 缓动。

---

## 实施顺序建议

```
Phase 2 实施顺序（建议）：
  1. P0-1 (跳过键修复)          ← 当前最影响用户体验
  2. P0-3 (morph 实现)          ← 功能缺失（已实现）
  3. P1-3 (输入状态恢复)        ← 影响用户操作手感
  4. P1-2 (HUD 层级修复)        ← 逻辑错误
  5. P1-1 (block_mob_ai 增强)   ← 部分有效
  6. P1-4 (排队验证)            ← 需要测试确认
  7. P0-2 (不可打断排队)        ← 新功能
  ---
   8. P2-1 (已删除/无需实现) / P2-2 / P2-3         ← 优化
```

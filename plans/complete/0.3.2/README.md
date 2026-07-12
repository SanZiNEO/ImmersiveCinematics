# 0.3.2 优化与 Bug 修复计划

**目标版本**: 0.3.2  
**创建**: 2026-06-04  
**版本历史**: 0.2.0 (2026-04) → 0.3.0 (2026-05 完全重构) → 0.3.1 (2026-05 修复) → **0.3.2 (当前)**

---

## 概览

共 10 项，分 3 个优先级。标 ★ 为阻塞项。

```
P0 (先行修复)        P1 (核心重构)        P2 (基础设施)
─────────────        ─────────────        ─────────────
① 跨包依赖泄漏       ④ Letterbox KF ★    ⑥ 布局位置文档
② Widget 焦点接口 ──→ ⑤ 坐标树事件 ★    ⑩ 布局自适应
③ 相机条件拦截       ⑧ 过渡系统重构
⑦ 指令玩家选择器
⑨ 物品交互触发器
```

P1 中，② 是 ⑤ 的前置依赖（事件树重构需要焦点接口已就位）。④ 和 ⑤ 都改 LeftPanelArea，需要协调顺序。⑧ 改 CameraTrackPlayer + EditorOperations + TimelineArea。⑩ 在 ⑤ 完成后修改 init() 布局公式。

---

## P0 — 快速修复 (3 项)

### ① EditorScreen 跨包依赖泄漏

**文件**: `editor-cross-module-leak.md`

- `EditorScreen` 直接 import `EditorBridgeImpl` 和 `CinematicKeyBindings`
- 改动: 删除 2 个 import，`bridge.stop()` 替代 `EditorBridgeImpl.INSTANCE.stop()`，移除 `CinematicKeyBindings.notifyEditorClosed()`
- 文件: `EditorScreen.java`

### ② Widget IFocusable 接口统一 (前置依赖)

**文件**: `widget-focus-interface.md`

- 当前 4 处 instanceof 链各 3 行重复
- 改动: 新增 `IFocusable` 接口，UITextInput/UIFloatInput/UIAutoCompleteInput 实现之
- 4 处分发点从 3 行缩为 1 行
- 文件: 新增 `IFocusable.java`，改 `UITextInput/UIFloatInput/UIAutoCompleteInput/EditorScreen/LeftPanelArea`

### ③ 相机 Mixin 条件拦截

**文件**: `camera-mixin-conditional-intercept.md`

- CAMERA gap 期间相机冻结；纯黑边脚本视角被锁
- 改动: Mixin 条件从 `isActive()` 改为 `isActive() && hasActiveCameraClip()`
- `GameRendererMixin` 4 处注入点同步修改
- 文件: `CameraManager/ScriptPlayer/CameraMixin/GameRendererMixin`

### ⑦ 指令玩家选择器

**文件**: `command-player-selector.md`

- `/icinematics play` 用了 `Minecraft.getInstance()` 纯客户端类，独立服务端崩溃
- 只对发送者播放，无法向其他玩家推送
- 改动: 添加 `EntityArgument.players()` 参数；服务端解析脚本后走 `S2CPlayScriptPacket` 分发；`stop` 同理
- 文件: `CinematicCommand.java`

### ⑨ 物品交互触发器

**文件**: `item-on-interact-trigger.md`

- 新增 `item_on_interact` 触发器，同时检查手持物品和交互目标（方块/实体）
- Author 可设 "carrot on iron_block" 触发 boss 出场动画
- 改动: `Evaluators.InteractTracker` 加物品追踪 + `Evaluators.evaluateItemOnInteract` + 注册 + 事件处理器记录手持物品
- 文件: `Evaluators.java` + `ImmersiveCinematics.java`

---

## P1 — 核心重构 (2 项)

### ④ Letterbox 关键帧驱动

**文件**: `letterbox-keyframe-refactor.md`

- `fade_in/fade_out/enabled` 删除，`aspect_ratio` 移入关键帧属性
- Letterbox clip 结构对齐 camera clip：`start_time/duration/keyframes`
- Editor 中 LETTERBOX 的 trackType 分支删除
- `LetterboxTrackPlayer` 重写为关键帧插值
- 文件: `LetterboxClip/LetterboxKeyframe(新)/ScriptParser/LetterboxTrackPlayer/LetterboxLayer/EditorScreen/EditorOperations/LeftPanelArea`

> 和 ⑤ 都改 LeftPanelArea，建议 ④ 先于 ⑤ 执行，④ 删除的 LETTERBOX 特殊分支正好是 ⑤ 要处理的目标。

### ⑤ 坐标统一 + 事件树 + Overlay 层

**文件**: `editor-tree-refactor.md`

- 统一为父元素相对偏移坐标（`absX()/absY()` 替代屏幕绝对坐标）
- EditorScreen 构建 UIComponent 树，事件改为 `root.mouseClicked(ctx)` 一笔替代 4 个手动调用
- Overlay 层解决跨区组件事件截断
- 前置依赖: ②（IFocusable 接口）
- 文件: `UIComponent/UIContext/EditorScreen` + 4 个 Area

---

## P2 — 参考文档 (1 项)

### ⑥ Editor 布局位置文档

**文件**: `editor-layout-coordinates.md`

- 纯位置描述，不改代码。为后续重构提供布局参考。
- 需在 ⑤ + ⑩ 完成后更新（坐标体系 + 布局公式均变更）

### ⑩ 布局自适应参考分辨率

**文件**: `layout-responsive.md`

- 当前百分比布局受 MC GUI Scale 设置影响，不同缩放值下布局不一致
- 改为参考分辨率 960×540（1080p + 默认 guiScale=2），等比缩放 + 钳制极值
- 前置依赖: ⑤（树重构完成后改 init() 公式）
- 文件: `EditorScreen.java`

---

## 推荐执行顺序

```
① 跨包依赖泄漏        (P0, 独立, 5min)
② Widget 焦点接口     (P0, 独立, 30min)
③ 相机条件拦截        (P0, 独立, 20min)
⑦ 指令玩家选择器      (P0, 独立, 1-2h)
⑨ 物品交互触发器      (P0, 独立, 30min)
④ Letterbox KF       (P1, 独立, 2-4h)     ← 先于 ⑤
⑧ 过渡系统重构        (P1, 独立, 1-2h)     ← 和④同改 EditorOperations
⑤ 坐标树事件          (P1, 依赖 ②, 4-8h)  ← ④⑧ 先于 ⑤
⑩ 布局自适应          (P1, 依赖 ⑤, 30min)  ← 树重构完成后改 init()
⑥ 布局文档            (P2, ⑤⑩ 完成后更新)
```

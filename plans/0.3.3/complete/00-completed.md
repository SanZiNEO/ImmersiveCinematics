# 已完成

## P0 — 旧 Forge 版本修复（原 0.3.2）

**commit**: `8ba3785`

| 编号 | 项 | 文件 |
|------|----|------|
| S1 | `/icinematics play` 路径遍历 + OP 权限 | `command/CinematicCommand.java` |
| E1 | 中文输入被拦截 | `editor/widget/UITextInput.java`, `UIAutoCompleteInput.java` |
| B1 | 工具栏按钮点击区域缩放不一致 | `editor/area/TimelineArea.java` |
| R1 | 新建脚本引导块重复三份 → `bootstrapNewScript()` | `editor/EditorScreen.java` |
| R3 | `EditorOperations.recalc()` 空转 | `editor/EditorOperations.java` |
| R4 | `LeftPanelArea` 两个无调用死方法 | `editor/area/LeftPanelArea.java` |
| R5 | `TimelineArea.LEFT_W` + `verticalScroll` 死代码 | `editor/area/TimelineArea.java` |
| A5 | EditorLogger `autoFlush=true` → `false` | `editor/debug/EditorLogger.java` |
| R6 | LeftPanelArea 未缩放像素字面量 | `editor/area/LeftPanelArea.java` |
| C3 | 废弃 FBO 回调机制 | `EditorBridge.java`, `EditorBridgeImpl.java`, `PreviewArea.java` |
| F1 | `GL_CLAMP` → `GL_CLAMP_TO_EDGE` | `editor/PreviewCapture.java` |
| R1 | RawInputLogger 文件句柄不关闭 | `editor/debug/RawInputLogger.java` |

**附赠修复**: init() else 分支补 scriptFileNames、toggleScriptList() 先 refresh、`UIContext.shiftY()`、LeftPanelArea 四操作改 shiftY、抑制每帧循环日志。

---

## 迁移中完成（原 01 — 迁移前修复）

以下 01 项在 Architectury 迁移过程中顺手做掉了：

### C2 — CAMERA 关键帧缺 position 导致逐帧 NPE

`ScriptParser.parseCameraKeyframe()` 强制 position 必填，缺则抛 `ScriptParseException`。  
**文件**: `script/ScriptParser.java`

### P4 — hasActiveCameraClip 每帧重复扫描

`CameraManager` 添加 `cachedHasActiveCameraClip` 字段，每帧只算一次，9 个 Mixin 入口读缓存。  
**文件**: `camera/CameraManager.java`

### C1 — Scale 封装

新增 `editor/Scale.java` 提供静态 `sx/sy` + `update()`，5 个 Area 使用统一缩放。  
**文件**: `editor/Scale.java` + 5 个 Area 文件

### 全部键鼠拦截

原 Forge-only `InputEvent.Key` / `MouseButton` → 跨平台 Mixin `KeyboardHandler` / `MouseHandler` + `InputRouter` 两层架构。  
**文件**: `mixin/KeyboardHandlerMixin.java`, `mixin/MouseHandlerMixin.java`, `control/InputRouter.java`, `control/InputTarget.java`

### 全部 HUD 白名单

原 Forge `RenderGuiOverlayEvent.Pre` → 跨平台 Mixin `Gui` / `ChatComponent` / `BossHealthOverlay` / `PlayerTabOverlay` / `SubtitleOverlay`。  
**文件**: `mixin/GuiMixin.java`, `ChatComponentMixin.java`, `BossHealthOverlayMixin.java`, `PlayerTabOverlayMixin.java`, `SubtitleOverlayMixin.java`

### 网络层

原 Forge `SimpleChannel` → Architectury `SimpleNetworkManager`。  
**文件**: `trigger/network/` 全部 7 个文件

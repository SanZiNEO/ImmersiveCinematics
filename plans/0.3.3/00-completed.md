# P0 — 已完成

**commit**: `8ba3785`

---

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

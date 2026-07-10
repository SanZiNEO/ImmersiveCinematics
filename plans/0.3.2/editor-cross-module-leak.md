# ① EditorScreen 跨包依赖泄漏 — 逐行修复指南

**版本**: 0.3.2  
**类型**: 代码卫生  
**执行方式**: 直接照做，无视解

---

## 步骤 1: EditorScreen.java — 存储 bridge 字段

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/EditorScreen.java`

**1a)** 在第 44 行（`private String renderPhase = "idle";` 之后）添加一个新字段:

```
private final EditorBridge bridge;
```

**1b)** 找到第 50-56 行的构造函数，改:

在第 52 行 `this.scriptsDir = scriptsDir;` 之后加一行:

```
this.bridge = bridge;
```

---

## 步骤 2: EditorScreen.java — 删除第 6 行 import

删除第 6 行:
```
import com.immersivecinematics.immersive_cinematics.client.EditorBridgeImpl;
```

---

## 步骤 3: EditorScreen.java — 修改 onClose()

找到第 777-785 行的 `onClose()` 方法。

把第 779 行删除:
```java
CinematicKeyBindings.notifyEditorClosed();
```

把第 781 行从:
```java
EditorBridgeImpl.INSTANCE.stop();
```
改为:
```java
bridge.stop();
```

---

## 步骤 4: EditorScreen.java — 删除第 7 行 import

删除第 7 行:
```
import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
```

---

## 步骤 5: CinematicKeyBindings.java — 自闭环 notifyEditorClosed

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/control/CinematicKeyBindings.java`

**5a)** 在第 30 行 `private static boolean editorKeyWasDown = false;` 之后，加一个新静态字段:

```java
private static boolean wasEditorOpen = false;
```

**5b)** 删除第 7 行 import:
```
import com.immersivecinematics.immersive_cinematics.editor.EditorScreen;
```
(注: 因为第 57/60/67 行仍引用 `EditorScreen` 用于 `instanceof` 检查和构造，**不要删除此 import**。此步骤作废。)

Actually，第 57/60/67 行还在用 `EditorScreen`，所以 import 不能删。`notifyEditorClosed` 不删也行——把 notifyEditorClosed 的调用从 EditorScreen 移到 CinematicKeyBindings 自己内部。

**5a 修正)** 在第 55 行 `if (ImmersiveCinematics.EDITOR_ENABLED && EDITOR_KEY != null) {` 之前，加:

```java
boolean currentlyEditorOpen = mc.screen instanceof EditorScreen;
if (wasEditorOpen && !currentlyEditorOpen) {
    notifyEditorClosed();
}
wasEditorOpen = currentlyEditorOpen;
```

这样 CinematicKeyBindings 自己监控 editor screen 的关闭，不需要 EditorScreen 来通知它。

**5b)** 删除 `notifyEditorClosed()` 方法的注释中 "Call from EditorScreen.onClose()" 这段（第 99 行），改为:

```java
/** Called automatically when editor screen closes. Prevents immediate reopen. */
```

---

## 完成检查清单

- [ ] EditorScreen.java 第 6 行（EditorBridgeImpl import）已删除
- [ ] EditorScreen.java 第 7 行（CinematicKeyBindings import）已删除
- [ ] EditorScreen.java 构造函数存了 `this.bridge = bridge;`
- [ ] EditorScreen.java onClose() 第 779 行已删除
- [ ] EditorScreen.java onClose() 第 781 行改为 `bridge.stop()`
- [ ] CinematicKeyBindings.java clientTick() 加了 wasEditorOpen 追踪
- [ ] CinematicKeyBindings.java 注释行已更新
- [ ] 编译通过，启动游戏后 F6 打开/关闭编辑器无异常

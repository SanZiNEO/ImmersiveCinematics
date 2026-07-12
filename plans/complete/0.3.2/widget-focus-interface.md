# ② Widget IFocusable 接口统一 — 逐行修复指南

**版本**: 0.3.2  
**类型**: 重构  
**执行方式**: 直接照做，无视解

---

## 步骤 1: 创建 IFocusable.java

新建文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/widget/IFocusable.java`

```java
package com.immersivecinematics.immersive_cinematics.editor.widget;

public interface IFocusable {
    boolean isFocused();
    void clearFocus();
    boolean keyPressed(int keyCode, int scanCode, int modifiers);
    boolean charTyped(char codePoint);
}
```

---

## 步骤 2: UITextInput.java — 实现接口

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/widget/UITextInput.java`

**2a)** 第 6 行，改 class 声明:

```diff
- public class UITextInput extends UIComponent {
+ public class UITextInput extends UIComponent implements IFocusable {
```

---

## 步骤 3: UIFloatInput.java — 实现接口

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/widget/UIFloatInput.java`

**3a)** 第 6 行，改 class 声明:

```diff
- public class UIFloatInput extends UIComponent {
+ public class UIFloatInput extends UIComponent implements IFocusable {
```

---

## 步骤 4: UIAutoCompleteInput.java — 实现接口

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/widget/UIAutoCompleteInput.java`

**4a)** 第 12 行，改 class 声明:

```diff
- public class UIAutoCompleteInput extends UIComponent {
+ public class UIAutoCompleteInput extends UIComponent implements IFocusable {
```

---

## 步骤 5: EditorScreen.java — 简化 keyPressed()

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/EditorScreen.java`

**5a)** 第 698-703 行，把 6 行改成 2 行:

```diff
  UIComponent focused = leftPanel.getFocusedInput();
  if (focused != null) {
-     if (focused instanceof UITextInput ti && ti.keyPressed(keyCode, scanCode, modifiers)) return true;
-     if (focused instanceof UIFloatInput fi && fi.keyPressed(keyCode, scanCode, modifiers)) return true;
-     if (focused instanceof UIAutoCompleteInput ai && ai.keyPressed(keyCode, scanCode, modifiers)) return true;
+     if (focused instanceof IFocusable f && f.keyPressed(keyCode, scanCode, modifiers)) return true;
  }
```

---

## 步骤 6: EditorScreen.java — 简化 charTyped()

**6a)** 第 720-735 行，把 16 行改成 6 行:

```diff
  UIComponent focused = leftPanel.getFocusedInput();
- if (focused instanceof UITextInput ti) {
-     EditorLogger.keyPress(EditorLogger.SCREEN, "charTyped", (int) codePoint,
-             "char=" + (codePoint > 32 ? String.valueOf(codePoint) : "CTRL"));
-     ti.charTyped(codePoint); return true;
- }
- if (focused instanceof UIFloatInput fi) {
-     EditorLogger.keyPress(EditorLogger.SCREEN, "charTyped", (int) codePoint,
-             "char=" + (codePoint > 32 ? String.valueOf(codePoint) : "CTRL"));
-     fi.charTyped(codePoint); return true;
- }
- if (focused instanceof UIAutoCompleteInput ai) {
-     EditorLogger.keyPress(EditorLogger.SCREEN, "charTyped", (int) codePoint,
-             "char=" + (codePoint > 32 ? String.valueOf(codePoint) : "CTRL"));
-     ai.charTyped(codePoint); return true;
- }
+ if (focused instanceof IFocusable f) {
+     EditorLogger.keyPress(EditorLogger.SCREEN, "charTyped", (int) codePoint,
+             "char=" + (codePoint > 32 ? String.valueOf(codePoint) : "CTRL"));
+     f.charTyped(codePoint);
+     return true;
+ }
```

**6b)** 在 EditorScreen.java 的 import 区域加一行（在约第 11 行 `import...widget.*` 附近）:

```java
import com.immersivecinematics.immersive_cinematics.editor.widget.IFocusable;
```

---

## 步骤 7: LeftPanelArea.java — 简化 getFocusedInput()

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/area/LeftPanelArea.java`

**7a)** 第 530-534 行，把 findFocusedInput 中的 3 行改成 1 行:

```diff
  private static UIComponent findFocusedInput(List<UIComponent> list) {
      for (UIComponent c : list) {
-         if (c instanceof UITextInput ti && ti.isFocused()) return c;
-         if (c instanceof UIFloatInput fi && fi.isFocused()) return c;
-         if (c instanceof UIAutoCompleteInput ai && ai.isFocused()) return c;
+         if (c instanceof IFocusable f && f.isFocused()) return c;
          List<UIComponent> sub = c.getChildren();
```

---

## 步骤 8: LeftPanelArea.java — 简化 clearTextFocus()

**8a)** 第 548-552 行，把 clearTextFocus 中的 3 行改成 1 行:

```diff
  private static void clearTextFocus(List<UIComponent> list) {
      for (UIComponent c : list) {
-         if (c instanceof UITextInput ti) ti.clearFocus();
-         if (c instanceof UIFloatInput fi) fi.clearFocus();
-         if (c instanceof UIAutoCompleteInput ai) ai.clearFocus();
+         if (c instanceof IFocusable f) f.clearFocus();
          List<UIComponent> sub = c.getChildren();
```

**8b)** 在 LeftPanelArea.java 的 import 区域加一行:

```java
import com.immersivecinematics.immersive_cinematics.editor.widget.IFocusable;
```

---

## 完成检查清单

- [ ] `widget/IFocusable.java` 文件已创建
- [ ] `UITextInput` 第 6 行加了 `implements IFocusable`
- [ ] `UIFloatInput` 第 6 行加了 `implements IFocusable`
- [ ] `UIAutoCompleteInput` 第 12 行加了 `implements IFocusable`
- [ ] `EditorScreen.java` keyPressed 3 行 instanceof 缩为 1 行
- [ ] `EditorScreen.java` charTyped 16 行 instanceof 缩为 6 行
- [ ] `EditorScreen.java` import IFocusable
- [ ] `LeftPanelArea.java` findFocusedInput 3 行 instanceod 缩为 1 行
- [ ] `LeftPanelArea.java` clearTextFocus 3 行 instanceod 缩为 1 行
- [ ] `LeftPanelArea.java` import IFocusable
- [ ] 编译通过，编辑器中文本框输入、焦点切换正常

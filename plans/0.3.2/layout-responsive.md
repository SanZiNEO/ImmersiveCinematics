# ⑩ 全元素布局参考分辨率缩放 — 免疫 GUI Scale

**版本**: 0.3.2  
**类型**: 修复  
**前置依赖**: ⑤ 坐标树事件（先完成树重构，再改布局）  
**文件数**: 6（EditorScreen + 4 个 Area + 1 个常量）  
**执行方式**: 按文件顺序，每步照做

---

## 问题

guiScale>2 时 `Screen.width/height` 变小，当前硬编码像素 + 百分比混合布局导致 UI 元素溢出窗口 — 按钮被截断、时间轴不可拖拽、左面板 Widget 挤不下。

## 方案

以 Screen 坐标 960×540 为设计参考，所有元素（Area 外框 + 内部子组件 + 按钮 + 文字间距 + Widget）全部按 `sx = width/960, sy = height/540` 等比缩放。

---

## 步骤 1 — EditorScreen.java — 新增缩放常量 + 计算

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/EditorScreen.java`

**①** 类体顶部新增（第 40 行前）:

```java
public static final float REF_W = 960f;
public static final float REF_H = 540f;

public static float sx = 1f;
public static float sy = 1f;
```

> `sx/sy` 是 public static，Area 内部可以直接引用，不需要传参。

**②** 找到 `init()` 方法（约第 78 行），开头加:

```java
sx = (float)width / REF_W;
sy = (float)height / REF_H;
```

**③** 把第 79-89 行的布局计算改为:

```java
int menuH = clamp((int)(24 * sy), 20, 28);
int leftW = clamp((int)(260 * sx), 180, (int)(360 * sx));
int timelineH = clamp((int)(220 * sy), 150, (int)(280 * sy));
int previewH = height - menuH - timelineH;

menuBar   = new MenuBarArea(0, 0, width, menuH);
leftPanel = new LeftPanelArea(0, menuH, leftW, previewH);
preview   = new PreviewArea(leftW, menuH, width - leftW, previewH);
timeline  = new TimelineArea(0, menuH + previewH, width, timelineH);
```

**④** 在 `reinit()` 做同样的 sx/sy 更新。

**⑤** 类末尾加 `clamp`:

```java
private static int clamp(int val, int min, int max) {
    return Math.max(min, Math.min(max, val));
}
```

---

## 步骤 2 — MenuBarArea.java — 按钮位置 + 尺寸缩放

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/area/MenuBarArea.java`

当前构造函数中按钮硬编码 `x+4, y+2, 70, h-4` 等。

**全部改为乘 sx**: （位置 x方向乘 sx, y方向乘 sy, 宽度乘 sx）

找到:

```java
// 原: new UIButton(x+4, y+2, 70, h-4)
// 改为:
int px = (int)(4 * EditorScreen.sx);
int py = (int)(2 * EditorScreen.sy);
int bw = (int)(70 * EditorScreen.sx);
int bh = h - (int)(4 * EditorScreen.sy);
listBtn = new UIButton(x + px, y + py, bw, bh);
```

同理改 `newBtn` (x+76→x+(int)(76*sx), w=44→(int)(44*sx)) 和 `saveBtn` (x+124→...)。

---

## 步骤 3 — PreviewArea.java — 按钮栏缩放

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/area/PreviewArea.java`

当前构造函数中:

```java
barW = 160; barH = 24;
barX = x + (w - barW) / 2;
barY = y + h - barH - 8;
```

**改为**:

```java
int barW = (int)(160 * EditorScreen.sx);
int barH = (int)(24 * EditorScreen.sy);
int barX = x + (w - barW) / 2;
int barY = y + h - barH - (int)(8 * EditorScreen.sy);
```

按钮尺寸:

```java
int btnW = (int)(36 * EditorScreen.sx);
int btnH = barH;
int btnGap = (int)(40 * EditorScreen.sx);

playBtn  = new UIButton(barX, barY, btnW, btnH);
pauseBtn = new UIButton(barX + btnGap, barY, btnW, btnH);
stopBtn  = new UIButton(barX + btnGap * 2, barY, btnW, btnH);
```

时间标签:

```java
int labelX = barX + btnGap * 3 + (int)(8 * EditorScreen.sx);
int labelY = barY + (int)(7 * EditorScreen.sy);
int labelW = barW - btnGap * 3 - (int)(8 * EditorScreen.sx);
int labelH = (int)(10 * EditorScreen.sy);
timeLabel = new UILabel(labelX, labelY, labelW, labelH, String.format("%.1fs", 0f));
```

---

## 步骤 4 — TimelineArea.java — 内部常量缩放

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/area/TimelineArea.java`

当前类体顶部硬编码:

```java
private static final int TOOLBAR_W = 22;
private static final int LABEL_W   = 58;
private static final int HEADER_H  = 20;
private static final int TRACK_H   = 28;
private static final int BTN       = 16;
private static final int BTN_GAP   = 2;
private static final int RESIZE_MARGIN = 4;
```

**改为动态获取**（通过方法，inline sx/sy）:

```java
private int toolbarW() { return (int)(22 * EditorScreen.sx); }
private int labelW()   { return (int)(58 * EditorScreen.sx); }
private int headerH()  { return (int)(20 * EditorScreen.sy); }
private int trackH()   { return (int)(28 * EditorScreen.sy); }
private int btn()      { return (int)(16 * EditorScreen.sy); }
private int btnGap()   { return (int)(2 * EditorScreen.sy); }
private int resizeMargin() { return (int)(4 * EditorScreen.sx); }
```

**搜索替换**: 全文查找 `TOOLBAR_W` → `toolbarW()`、`LABEL_W` → `labelW()`、`HEADER_H` → `headerH()`、`TRACK_H` → `trackH()`、`BTN` → `btn()`、`BTN_GAP` → `btnGap()`、`RESIZE_MARGIN` → `resizeMargin()`。

**LEFT_W** (line 108: `LEFT_W = TOOLBAR_W + LABEL_W`) → 删除常量，改用 `toolbarW() + labelW()`。

**工具栏按钮位置** (bx = x + 3) → 改为 `bx = x + (int)(3 * EditorScreen.sx)`。

**拖动逻辑**: 所有坐标判断改为用 `resizeMargin()` 替代 `RESIZE_MARGIN`。

**名称替换**: 代码中用到 `TRACK_H` 的地方也替换为 `trackH()`（比如填充、命中检测、轨道行高循环）。

**名称替换**: `BTN_GAP` 替换为 `btnGap()`。

**名称替换**: `HEADER_H` 替换为 `headerH()`。

**名称替换**: `RESIZE_MARGIN` 替换为 `resizeMargin()`。

---

## 步骤 5 — LeftPanelArea.java — Widget 行高 + 间距

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/area/LeftPanelArea.java`

Widge 构建位置 `layoutY += h + gap` 中，h 和 gap 是硬编码的。

**改为**：

```java
int widgetH = (int)(20 * EditorScreen.sy);
int widgetGap = (int)(2 * EditorScreen.sy);
int labelH = (int)(14 * EditorScreen.sy);
```

搜索文件中所有硬编码像素值（20、2、14、4 等和布局相关的），改为乘 sx/sy。

**特别说明**:
- y 方向用 `sy`
- x 方向用 `sx`
- widget 宽度: 用 areaW 自适应（已成立）
- 滚动条宽度 4px: `(int)(4 * sx)`

---

## 完成检查清单

- [ ] EditorScreen 加 REF_W/REF_H/sx/sy 常量
- [ ] init() 和 reinit() 更新 sx/sy + Area 尺寸公式
- [ ] MenuBarArea 按钮位置/尺寸乘 sx/sy
- [ ] PreviewArea 按钮栏乘 sx/sy
- [ ] TimelineArea TOOLBAR_W/LABEL_W/HEADER_H/TRACK_H/BTN/BTN_GAP/RESIZE_MARGIN 全部改成方法调用 ×sx/sy
- [ ] LeftPanelArea Widget 行高/间距/标签高乘 sx/sy
- [ ] 编译通过
- [ ] guiScale=1: 布局正常，元素分布合理（sx=2.0，元素都放大了2倍）
- [ ] guiScale=4: 布局正常，元素不溢出（sx=0.5，元素都缩小到一半）
- [ ] 默认 auto: 布局和改前视觉一致（sx≈1.0）

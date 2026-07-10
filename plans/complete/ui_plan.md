# 编辑器 UI 规划

**版本**: 5.1（实现回顾 — 独立脚本编辑器）
**日期**: 2026/5/8

---

## UI 布局

```
┌──────────────────────────────────────┐
│         MenuBar (5%)                 │  ← 脚本标题 / 状态/操作日志 / [保存]
├─────────┬────────────────────────────┤
│         │                            │
│  属性    │    📺 预览区域              │
│  面板    │   (主 RT 捕获 blit)         │
│  (20%)  │                            │
│         ├────────────────────────────┤
│         │  播放控制 (30px)            │
├─────────┴────────────────────────────┤
│  时间轴: 标尺 + 轨道 + 播放头          │  ← 22%
└──────────────────────────────────────┘
```

**原生 Screen 坐标**，无虚拟分辨率。

---

## 四大区域设计

### 1. 顶部菜单栏 (MenuBarArea)

```
┌──────────────────────────────────────────────────────────────┐
│ [脚本列表 ▼]        脚本名称 (居中)         [日志] [状态] [新建] [保存] │
└──────────────────────────────────────────────────────────────┘
```

| 控件 | 行为 |
|------|------|
| `[脚本列表 ▼]` | 切换 LeftPanel 到脚本列表浏览模式 |
| 脚本名称 (Label) | 居中显示当前打开的脚本名称 |
| 活动日志 (Label) | 最近操作文本，5 秒后自动消失 |
| 状态 (Label) | 持久状态，如 "▶ Playing"、"Editing Script" |
| `[新建]` | 创建空白脚本 |
| `[保存]` | 将当前脚本写入文件 |

**脚本列表交互**（显示在 LeftPanel 区域）：
- 条目列表，单击选中，双击打开
- 选中状态下出现 `[删除]` → 变 `[✓]` `[✗]` 确认

### 2. 左侧面板区 (LeftPanelArea)

四种内容模式互斥切换，支持垂直滚动：

| 模式 | 触发条件 | 展示内容 |
|------|----------|----------|
| 脚本列表 | 点击 MenuBar [脚本列表] | 脚本文件条目列表 + "+ New Script" 按钮 |
| 脚本属性 | 未选中 clip/keyframe | Triggers → Script Info → Runtime → Duration |
| Clip 属性 | 选中 clip | 全部 clip 字段（自动填充缺失字段默认值） |
| 关键帧属性 | 选中关键帧 | 全部关键帧字段（yaw/pitch/roll/fov/zoom/dof/position） |

**滚动机制**：
- 内容高度 > 面板高度 80% 时自动启用滚动
- `maxScroll = contentHeight - panelHeight` 可滚到底
- 滚动条在面板右边缘
- 点击面板其他区域自动清除文本输入框焦点

**字段显示**：

| 类型 | 控件 | 说明 |
|------|------|------|
| 布尔 | `UIToggle` | 开/关 |
| 数值 | `UIFloatInput` | 滚轮增减 + 键盘数字输入 |
| 字符串 | `UITextInput` | 自由文本 |
| 三态 | `UIButton` 循环点击 | `hide_chat` 等 7 个字段：`—`(null) → `✓`(true) → `✗`(false) |
| 枚举 | `UIButton` 循环点击 | `transition`: cut↔morph, `interpolation`: linear↔smooth, `position_mode`: relative↔absolute |
| 触发器 | `TriggerPanel` | 触发器列表 + 8 种编辑器 |

**默认值填充**：新建/打开脚本时，缺失字段自动补默认值（通过 `fillMetaDefaults`/`fillClipDefaults`/`fillKeyframeDefaults`）。`position_mode` 切换时自动转换所有关键帧的 position 结构（`{dx,dy,dz}` ↔ `{x,y,z}`）。

**文件命名**：保存文件名自动取 meta 中 `id` 字段，编辑 `id` 时同步更新。

### 3. 右侧预览区 (PreviewArea + PlaybackControls)

```
┌───────────────────────────────────────────┐
│                                           │
│              📺 预览画面                   │
│           (主 RT 捕获 blit)                │
│           16:9 比例                        │
│                                           │
│         ┌─────────────────────┐            │
│         │  ▶  ⏸  ⏹  0.0s    │            │  ← 半透明叠加，居中底部
│         └─────────────────────┘            │
│                                           │
└───────────────────────────────────────────┘
```

**FBO 方案**（2026/5/8 重构）：
- `PreviewCapture` 在 `EditorScreen.render()` 入口，用 `glBlitFramebuffer` 直接把主 `RenderTarget` 的颜色缓冲区复制到自有 FBO
- `PreviewArea.render()` 用 `GameRenderer.getPositionTexShader` 和 `POSITION_TEX` 格式绘制纹理四边形
- 预览包含世界 + HUD + Overlay（不含编辑器 UI）
- 关闭编辑器时自动清理 GL 资源

不再需要 `setFboCallback` 外部注入。

### 4. 底部时间轴区 (TimelineArea)

```
┌──────┬──────┬────────────────────────────────────────┐
│ 工具  │ 轨道  │                                       │
│ 按钮  │ 名称  │  刻度标尺 (TimeRuler)                 │
│ 列    │ 列    ├────────────────────────────────────────┤
│ ≤3%  │ 7%   │  [■ Clip 1 ■■■]  [■ Clip 2 ■]         │
│      │      │  播放头贯穿右侧画布                      │
│      │      │  (未来: 多轨道)                          │
└──────┴──────┴────────────────────────────────────────┘
```

左侧工具按钮列 (≤3%) + 轨道名称列 (~7%) 左右并列，合计 ≤10%。右侧 ~90% 为画布区。

| 子组件 | 职责 |
|--------|------|
| 工具列 + 轨道名称 | 左侧 ≤10%，工具按钮 + 轨道名 |
| TimeRuler | 秒数刻度线，可点击跳转 |
| ClipBlock | 每个 clip 的色块块，支持拖拽移动 + 左右边缘缩放 |
| Playhead | 红色竖线，标记当前时间 |
| 关键帧标记 | clip 块内部的菱形标记 |

额外能力：Ctrl+滚轮缩放、Shift+滚轮水平滚动、垂直滚动（预留字段）。

---

## 外部依赖边界（EditorBridge）

编辑器是**独立的脚本编辑器**，不和模组内部模块直接通信。

1. 编辑器不 import 任何 camera / script / control 包的类
2. 编辑器持有自己的纯数据模型（`JsonObject` 树，不定义独立 POJO 类）
3. 编辑器只通过 `EditorBridge` 接口与外部通信

```
播放头拖动 / 播放中  →  bridge.setTime(t)
  └─ CameraManager.INSTANCE.setTime(t) → 自己算插值

播放控制  →  bridge.play() / pause() / stop()
  └─ CameraManager.INSTANCE.resume() / pause() / stop()

编辑了脚本结构  →  编辑器推完整 JSON  →  bridge.pushScript(json)
  └─ CameraManager.INSTANCE.pushScript(jsonContent) → 重新解析
```

### EditorBridge 接口（当前实现）

```java
public interface EditorBridge {
    void setTime(float seconds);
    void pushScript(String jsonContent);
    void play();
    void pause();
    void stop();
}
```

### EditorBridgeImpl

`client/EditorBridgeImpl.java` 单例，直接转发给 `CameraManager.INSTANCE`。

### 编辑器数据模型

编辑器内部直接操作 `JsonObject`（通过 `EditorDocument` 持有），不定义独立 POJO 类。字段结构完全对应 `SCRIPT_FORMAT.md` 中的脚本格式。

新建脚本时通过 `EditorDocument.reset()` 生成含全部字段默认值的根对象。打开已有脚本时通过 `fill*Defaults()` 补缺失字段。

---

## 跨区域通信

```
MenuBarArea [脚本列表]  ──→ Screen ──→ LeftPanelArea 切换到 SCRIPT_LIST 模式
MenuBarArea [新建]      ──→ Screen ──→ EditorDocument.reset() → 四个区域刷新
MenuBarArea [保存]      ──→ Screen ──→ 序列化 JSON → 写文件 → output.pushScript()
LeftPanelArea 属性编辑  ──→ Screen ──→ 标记 dirty → 同步 fileName
TimelineArea 选中 clip  ──→ Screen ──→ LeftPanelArea 切换到 CLIP_PROPERTIES 模式
TimelineArea 拖 clip    ──→ Screen ──→ EditorOperations 修改模型 → 刷新
TimelineArea 播放头拖动 ──→ Screen ──→ output.setTime(t)
PreviewArea 播放按钮    ──→ Screen ──→ output.play/pause/stop + menuBar.setStatus
MenuBarArea 活动日志    ──→ Screen ──→ menuBar.setAction("Opened xxx") / setStatus("▶ Playing")
```

四个区域互不直接引用，全部通过 Screen 的 Mediator 方法转发。

---

## 事件传播

**常规模式**：直接在 `mouseDragged` 里处理连续交互（拖拽 clip 块 / 播放头 / 关键帧），不在 render() 里查标志。

三个原则：
1. 未被消费的事件必须 return false
2. mouseReleased 清除交互标志
3. 焦点管理：点击任何区域时 `leftPanel.clearTextFocus()` 清除旧焦点

---

## 当前代码结构（30 个文件）

```
editor/
├── EditorBridge.java          接口：setTime/pushScript/play/pause/stop
├── EditorDocument.java        数据模型持有者，reset/loadFromJson/toJson
├── EditorOperations.java      全静态方法：addClip/moveClip/addKeyframe/deleteClip/snap
├── EditorOutput.java          pending 命令队列，桥接 EditorBridge
├── EditorPlayback.java        播放循环：play/pause/stop/tick
├── EditorScreen.java          Screen 子类，四大区域初始化 + 输入分发 + 渲染循环
├── EditorSelection.java       选中状态管理（clip + keyframe）
├── PreviewCapture.java        FBO 捕获主 RT 帧，供 PreviewArea 使用

├── area/
│   ├── LeftPanelArea.java     4 模式面板 + 字段反射 + 滚动 + 焦点管理
│   ├── MenuBarArea.java       按钮 + 脚本名 + 状态 + 活动日志
│   ├── PreviewArea.java       16:9 预览 + 播放控制按钮 + 纹理 blit
│   └── TimelineArea.java      标尺/轨道/clip块/关键帧/播放头/缩放/滚轮

├── widget/
│   ├── UIComponent.java       抽象基类（hover/render/mouse 事件/子控件遍历）
│   ├── UIContext.java         渲染上下文（graphics/font/鼠标坐标/修饰键）
│   ├── UIButton.java          按钮（悬停/点击颜色，回调）
│   ├── UILabel.java          文本标签（居中模式，setText）
│   ├── UIToggle.java          开关（on/off 切换）
│   ├── UITextInput.java       文本输入（退格删除，ASCII 输入，焦点）
│   ├── UIFloatInput.java      数值输入（数字/负号/小数点，滚轮增减，回车提交）
│   └── UIDropdown.java        下拉选择（展开列表/滚动/右键删除）

├── trigger/
│   ├── TriggerPanel.java      触发器面板容器（类型列表 + 构建入口）
│   ├── TriggerEditor.java     抽象编辑器基类 + 工厂方法 create(type)
│   ├── NoConditionEditor.java   无条件（login/command）
│   ├── SingleIdEditor.java      单 ID 字段（advancement/biome/dimension 等）
│   ├── LocationEditor.java      位置编辑器（维度/模式/坐标）
│   ├── InventoryEditor.java     物品编辑器（items/mode/change）
│   ├── EntityKillEditor.java    实体击杀编辑器（entity ID/mode）
│   └── StructureEditor.java     结构编辑器（structure name/radius）

└── debug/
    ├── EditorLogger.java      分层日志（SCREEN/TIMELINE/LEFT/PREVIEW/MENU）
    └── RawInputLogger.java    GLFW 原生输入事件日志 + 鼠标轮询
```

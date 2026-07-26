# 编辑器交互优化计划

> 参考项目：Olive (C++/Qt NLE)、LosslessCut (JS/Electron)
> 目标：0.3.3 发布前将编辑器体验提升到专业剪辑软件水准

---

## 一、缩放系统（当前问题与参照）

### Olive 的做法
- **滚轮缩放**：默认水平滚动。按住 `Ctrl` 时变为以鼠标指针为中心的缩放
- **缩放公式**：`scale *= 2.0`（放大）/ `scale *= 0.5`（缩小），步进固定为 2×
- **缩放锚点**：始终以鼠标指针所在时间点为中心缩放，缩放后自动调整滚动位置
- **缩放工具**：有独立的 Zoom Tool，框选区域后缩放到该区域

### LosslessCut 的做法
- 滚轮默认缩放时间轴（水平方向）
- 提供 `wheelSensitivity` 配置项控制缩放灵敏度

### 我们的问题
旧的实现是 `Shift+滚轮` 缩放、`Ctrl+滚轮` 水平滚动。与行业惯例（Ctrl=缩放，滚轮=滚动）相反。

### 建议方案
| 操作 | 效果 | 参照 |
|------|------|------|
| `滚轮` | 水平滚动时间轴 | 行业标准 |
| `Ctrl+滚轮` | 以鼠标位置为中心缩放 | Olive / 大多数 NLE |
| `Ctrl+0` | 重置缩放为 1:1 | 通用 |
| `Ctrl+中键框选` | 缩放到选区范围 | Olive Zoom Tool |

---

## 二、播放头与时间轴交互

### Olive 的做法
- 播放头是一条纵贯所有轨道的竖线
- 点击标尺（ruler）区域 → 跳转到该时间点
- 拖拽播放头 → 实时移动
- 播放头有三角形头部指示器

### 我们的现状
- 有播放头，基本正确
- 但点击时间轴空白处不一定会移动播放头

### 建议改进
| 操作 | 效果 |
|------|------|
| 点击时间轴空白处 | 播放头跳转到点击位置 |
| 拖拽播放头 | 实时移动 |
| 播放头渲染 | 纵贯所有轨道的彩色竖线 + 三角形头部 |

---

## 三、Clip 拖拽与编辑

### Olive 的做法
- **移动**：拖拽 clip 中部 → 水平移动（改变 start_time）
- **左边缘 trim**：拖拽 clip 左边缘 → 改变 start_time
- **右边缘 trim**：拖拽 clip 右边缘 → 改变 duration
- **Ghost 预览**：拖拽时显示半透明 ghost，松手才执行实际移动
- **吸附 (Snap)**：拖拽时 clip 边缘自动吸附到播放头/其他 clip 边缘
- **多选**：`Ctrl+点击` 多选，`Shift+点击` 范围选
- **框选**：在轨道空白处拖拽 → 矩形框选多个 clip

### 我们的现状
- 有基本的拖拽和 trim
- 吸附逻辑可能有但需要确认
- 没有框选、没有多选
- 没有实时 ghost 预览

### 建议改进
| 操作 | 效果 |
|------|------|
| 拖拽 clip 中部 | 实时移动（半透明 ghost） |
| 拖拽 clip 边缘 | trim 起始/结束时间 |
| 吸附 | clip 边缘/播放头 8px 阈值自动吸附 |
| `Ctrl+点击` | 多选 clip |
| 空白处拖拽 | 框选多个 clip |
| `Delete` | 删除选中 clip |
| `Ctrl+D` | 复制选中 clip |

---

## 四、多轨道支持

### 行业标准做法
- 每条轨道独立一行，左侧显示轨道名称/类型图标
- 轨道按类型着色（CAMERA=蓝, LETTERBOX=绿, AUDIO=黄, EVENT=红）
- 空轨道显示为灰色空白行
- 轨道高度可调（拖拽轨道间分隔线）

### 我们的现状
- 数据已经是多轨道，但 `TimelineArea` 只渲染第一条 CAMERA 轨道
- `LeftPanelArea` 的轨道列表只有基本显示

### 建议改进
| 改动 | 说明 |
|------|------|
| 轨道遍历渲染 | `TimelineArea.drawClips()` 改为遍历 `tracks` 数组 |
| 轨道颜色 | 每种 TrackType 对应颜色 |
| 轨道标签 | 左侧显示轨道类型名称 + 颜色标记 |
| 空轨道 | 没有 clip 时显示提示 |

---

## 五、快捷键总表

| 快捷键 | 功能 | 参照来源 |
|--------|------|---------|
| `Space` | 播放/暂停 | 通用 |
| `Enter` | 播放选中 clip | Olive |
| `←` / `→` | 逐帧移动播放头 | 通用 |
| `Shift+←` / `Shift+→` | 快进/快退 5 秒 | LosslessCut |
| `Ctrl+滚轮` | 缩放（以鼠标位置为中心）| Olive, LosslessCut |
| `滚轮` | 水平滚动 | 通用 |
| `Shift+滚轮` | 垂直滚动 | 通用 |
| `Delete` | 删除选中 clip | 通用 |
| `Ctrl+D` | 复制/粘贴 clip | 通用 |
| `Ctrl+Z` | 撤销 | 通用 |
| `Ctrl+C/V` | 复制/粘贴 | 通用 |
| `Ctrl+A` | 全选 | 通用 |
| `Ctrl+0` | 重置缩放 | 通用 |
| `F` | 缩放至全部 clip 可见 | Olive `Frame All` |
| `Esc` | 关闭编辑器 | 已有 |
| `C` | 长按跳过（已有） | 已有 |

---

## 六、三区布局调整

现有布局基本正确：
```
┌──────────────────────────────────────┐
│ MenuBar (新建/保存/列表)              │
├──────────┬───────────────────────────┤
│ LeftPanel│ PreviewArea               │
│ (属性/   │ (实时预览 + 播放控制)      │
│  脚本列表)│                           │
├──────────┴───────────────────────────┤
│ TimelineArea (时间轴 + 多轨道)        │
└──────────────────────────────────────┘
```

**建议微调：**
- LeftPanel 顶部新增轨道类型选择器（显示/隐藏特定轨道）
- Timeline 左侧增加窄的轨道标签列（类型名称 + 颜色）
- PreviewArea 底部的时间码显示改为 `MM:SS.mmm` 格式

---

## 七、实现顺序

1. **缩放系统** — 重构 `TimelineArea` 的 `pixelsPerSecond` 缩放逻辑，改为 `Ctrl+滚轮`
2. **播放头交互** — 点击空白处跳转，渲染改进
3. **多轨道渲染** — 遍历所有轨道绘制 clip
4. **拖拽改进** — ghost 预览 + 吸附
5. **快捷键表** — 逐步加入
6. **框选/多选** — 批量操作

---

## 八、属性面板改造（LeftPanelArea）

### 现状
当前有 4 种模式，通过按钮/点击切换，切换路径依赖交互操作：
```
SCRIPT_LIST ↔ SCRIPT_PROPERTIES ↔ CLIP_PROPERTIES ↔ KEYFRAME_PROPERTIES
```

### 参照：剪辑软件的面板设计

**Olive**：每个面板（项目媒体、特效、历史、节点）都是独立 DockWidget，可自由排列/隐藏。每个面板顶部有标题栏和操作按钮。

**Premiere/DaVinci**：左侧有多个标签页（效果、媒体、色彩、调音台），点击标签一键切换。

**LosslessCut**：左侧是单一的设置/属性面板，没有标签——功能少所以不需要。

### 建议方案：顶部标签栏

在 LeftPanelArea 顶部加一行标签按钮，始终可见：

```
┌──────────────────────────────┐
│ [📋 脚本列表] [⚙ 脚本属性] [🎬 Clip属性] [◆ 关键帧] [📺 轨道] │
├──────────────────────────────┤
│                              │
│  (当前选中标签的内容区域)      │
│                              │
└──────────────────────────────┘
```

### 各标签的内容

| 标签 | 对应模式 | 内容 |
|------|---------|------|
| 📋 脚本列表 | `SCRIPT_LIST` | 当前文件夹的所有 .json 脚本，点击打开 |
| ⚙ 脚本属性 | `SCRIPT_PROPERTIES` | meta 字段编辑（id/name/author + 所有 hide_*/block_* 开关） |
| 🎬 Clip 属性 | `CLIP_PROPERTIES` | 选中 clip 的字段（start_time/duration/transition/position_mode 等） |
| ◆ 关键帧 | `KEYFRAME_PROPERTIES` | 选中关键帧的字段（time/yaw/pitch/roll/fov/zoom/dof/position） |
| 📺 轨道 | `TRACK_LIST`（新增） | 显示所有轨道类型及其 clip 数量，可切换显隐 |

### 轨道标签（新增）

`TRACK_LIST` 是一个新视图，显示当前脚本的所有轨道：

```
┌──────────────────────────────┐
│ 🎬 CAMERA        3 clips  👁 │
│ 📽 LETTERBOX     0 clips  👁 │
│ 🔊 AUDIO         0 clips  👁 │
│ ⚡ EVENT          1 clip   👁 │
│ 🔧 MOD_EVENT     0 clips  👁 │
└──────────────────────────────┘
```

每条轨道显示：类型图标 + 类型名称 + clip 数量 + 显隐开关 👁

### 实现方式

标签栏本身是一个 `UIComponent`，放在 LeftPanelArea 顶部（高度 ~20px）。
每个标签是一个 `UIButton`，点击时调用 `setMode()` 切换到对应面板。
选中标签高亮。

```java
// LeftPanelArea 新增
private void buildTabBar() {
    int tabY = y;
    int tabH = 20;
    // 遍历所有 PanelMode 绘制标签按钮
    for (PanelMode m : PanelMode.values()) {
        UIButton tab = new UIButton(tabX, tabY, tabW, tabH, getTabLabel(m), btn -> setMode(m));
        tab.highlighted = (m == mode);
        children.add(tab);
        tabX += tabW + 2;
    }
    // 内容区域从 tabY + tabH + 2 开始
}
```

### 标签图标（可选）
当前用 emoji 代替图标。未来可替换为纹理图标（16×16 png）。

---

## 九、视觉设计——直接从 Olive 抄

### Clip 渲染（当前：纯色矩形 → 改为）

| 要素 | Olive 的做法 | 我们抄 |
|------|-------------|-------|
| 填充色 | `block->brush()` 按轨道类型着色 | 每种 TrackType 一个颜色 |
| 边框 | 左上白线 + 右下深色 → 3D 凸起效果 | 同样画 4 条边框线 |
| 标签 | 内边距 4px 的文本，颜色用 `ColorCoding` | 白色文字 + clip 名称 |
| 选中态 | clip 周围虚线/亮色边框 | 亮色 2px 边框 |
| 最小值 | `MINIMUM_RECT_WIDTH = 2px` | 同样实现 |

### 轨道渲染（当前：无轨道背景 → 改为）

```
旧：直接在 TimelineArea 上画 clip
新：
┌─────────────────────────────────────┐
│ 🎥 CAMERA                 [高度可调]│ ← 淡蓝背景
│ ┌───┐  ┌──────────┐                │
│ │C1 │  │    C2    │                │
│ └───┘  └──────────┘                │
├─────────────────────────────────────┤ ← 1px 分隔线
│ 📽 LETTERBOX              [高度可调]│ ← 淡绿背景
│ (空)                                 │
└─────────────────────────────────────┘
```

### 轨道颜色方案

| 轨道类型 | 背景色 | Clip 色 | 用途 |
|---------|--------|---------|------|
| CAMERA | `#1a2744` | `#3a6db5` | 蓝色系 |
| LETTERBOX | `#1a2e1a` | `#3a8a3a` | 绿色系 |
| AUDIO | `#2e2e1a` | `#8a8a3a` | 黄色系 |
| EVENT | `#2e1a1a` | `#8a3a3a` | 红色系 |
| MOD_EVENT | `#2e1a2e` | `#8a3a8a` | 紫色系 |

### 播放头渲染（当前：有 → 改进）

```
旧：简单竖线
新：纵贯全部轨道的 2px 亮色竖线 + 顶部三角形指示器 + 标尺上对应位置标记
```

### 标尺（Ruler）改进

| 要素 | 做法 |
|------|------|
| 背景 | 深灰色条 |
| 刻度 | 每秒钟一条主刻度线 + 0.5s 一条次刻度线 |
| 时间码 | 每 5 秒显示时间数字（格式 `MM:SS`） |
| 播放头 | 标尺上对应位置画红色三角形 |

### Ghost 拖拽预览

拖拽 clip 时，在目标位置画一个 50% 透明度的 clip 副本（Olive 的 `painter->setOpacity(0.5)`）
松手时 ghost 消失，clip 实际移动。

```java
// TimelineArea.dragClip()
// 在 paint 阶段画 ghost
if (draggingClip != null) {
    int ghostX = timeToX(dragTargetTime);
    ctx.graphics.fill(ghostX, trackY, ghostW, trackH, 0x80_3A6DB5); // 50% 透明度
    ctx.graphics.renderOutline(ghostX, trackY, ghostW, trackH, 0xFF_5A8DD5);
}
```

### 吸附指示器

当 clip 边缘吸附到播放头/其他 clip 时，在吸附位置画一条短暂闪烁的亮线（颜色 `#FFD700` 金色）。

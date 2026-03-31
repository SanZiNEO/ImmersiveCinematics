# 沉浸式电影摄影模组 - UI规划 v2.0（完全重构版）

**版本**: 2.0（基于重建文档完全重新规划）
**日期**: 2026/3/31  
**状态**: 草案 - 基于全新架构设计

---

## 🔄 完全重新规划：基于重建文档的理解

我仔细阅读了 `rebuild_updated.md` 文档，现在我完全理解了项目的**全新架构**。我需要**完全摈弃旧代码思路**，重新规划UI设计。

### **关键新理解**：

1. **编辑器是游戏内功能**：编辑器作为模组内置功能，在游戏内运行
2. **播放器和时间轴联动**：像剪辑软件那样，播放时时间轴有竖线指示当前时间
3. **使用新的相机系统**：调用新相机（CameraEntity）在播放器区域渲染
4. **动态布局**：根据窗口大小动态适配
5. **完全重构**：旧代码太复杂，需要完全重做

### **基于重建文档的新架构**：

#### **播放器区域原理**：
1. **编辑器模式**：调用新的相机实体（`CinematicCameraEntity`）
2. **实时渲染**：在播放器区域显示相机视角
3. **时间联动**：播放时时间轴显示当前时间竖线

#### **时间轴系统**：
1. **多轨道布局**：类似剪辑软件
2. **无限滚动**：根据内容动态变化
3. **左侧冻结区域**：显示轨道信息
4. **播放联动**：与播放器区域时间同步

---

## 🎨 重新设计的UI规划（基于javaui.ui布局参考）

### **UI整体架构**：

```
┌─────────────────────────────────┐
│           菜单栏                 │  # 文件、编辑、帮助
├─────────────┬───────────────────┤
│             │                   │
│    左侧     │       右侧         │  # 中间主区域
│   控制区    │     播放区         │  # 播放区域显示相机渲染
│             │                   │
├─────────────┴───────────────────┤
│          底部时间轴区            │  # 多轨道，左侧冻结区域
└─────────────────────────────────┘
```

### **动态布局参数（基于javaui.ui参考）**：
- **窗口最小尺寸**：1200×900像素
- **菜单栏高度**：30像素（固定）
- **左侧控制区宽度**：240像素（固定或百分比）
- **播放区域宽度**：960像素（剩余宽度）
- **时间轴高度**：285像素（固定或百分比）

### **百分比布局规则**：
- 菜单栏：固定高度30px（~3.3%）
- 左侧控制区：宽度20%（可根据需求调整）
- 时间轴：高度30%（可根据需求调整）
- 播放区域：剩余空间（66.7%高度，80%宽度）

---

## 🏗️ 各区域功能规划

### **1. 顶部菜单栏 (MenuBar)**
| 组件 | 功能 | 状态 |
|------|------|------|
| 文件菜单 | 新建、打开、保存、导出 | 待设计 |
| 编辑菜单 | 撤销、重做、设置 | 待设计 |
| 帮助菜单 | 教程、文档、关于 | 待设计 |

**布局要求**：
- 固定高度或百分比高度
- 随窗口宽度变化
- 包含下拉菜单

### **2. 左侧控制区 (widget_left_controls)**
| 组件 | 功能 | 状态 |
|------|------|------|
| 属性面板 | 显示选中对象的属性 | 待设计 |
| 功能按钮 | 添加、删除、复制等 | 待设计 |
| 参数调节 | 数值输入、滑块 | 待设计 |

**动态行为**：
- 当选择时间轴上的镜头行时，显示对应属性
- 无关键帧：显示基础属性（总时长等）
- 有关键帧：显示关键帧属性（位置、镜头属性等）
- 支持两个关键帧间的属性平滑

**布局要求**：
- 固定宽度或百分比宽度
- 垂直滚动（如需要）

### **3. 右侧播放区 (widget_right_player)**
| 组件 | 功能 | 状态 |
|------|------|------|
| 实时预览 | 显示镜头视角 | **稍后提供原理** |
| 播放控制 | 播放、暂停、停止 | 待设计 |
| 状态显示 | 当前时间、帧率等 | 待设计 |

**特殊说明**：
- 实时显示功能原理**稍后由您详细描述**
- 需要集成Minecraft渲染系统

**布局要求**：
- 剩余宽度（减去左侧控制区）
- 可随窗口大小调整
- 保持宽高比（如需要）

### **4. 底部时间轴区 (widget_bottom)**
| 组件 | 功能 | 状态 |
|------|------|------|
| 冻结区域 | 左侧固定，显示每行代表什么 | 待设计 |
| 时间轴 | 无限向右和上下滚动的多时间轴布局 | 待设计 |

**复杂需求**：
1. **无限滚动**：可向右和上下无限滚动
2. **动态滚动范围**：根据内容动态变化
3. **冻结区域**：
   - 左侧固定区域
   - 左右滚动时：一直显示在最左侧
   - 上下滚动时：随行一起滚动
4. **多时间轴布局**：类似剪辑软件

**滚动逻辑**：
- **向右滚动**：根据时间轴内容动态变化
- **上下滚动**：根据行数动态变化
- **动态范围**：防止出错，根据实际内容计算

---

## 🔧 技术实现架构

### **核心架构**
```java
// 主编辑器屏幕 (CameraEditorScreen.java)
public class CameraEditorScreen extends Screen {
    private MenuBarWidget menuBar;
    private ControlPanelWidget leftControls;
    private PlayerPanelWidget rightPlayer;
    private TimelinePanelWidget bottomTimeline;
    
    @Override
    protected void init() {
        // 动态计算布局
        int windowWidth = this.width;
        int windowHeight = this.height;
        
        // 使用百分比动态设置bounds
        int menuHeight = (int)(windowHeight * 0.05); // 5%
        int bottomHeight = (int)(windowHeight * 0.3); // 30%
        int leftWidth = (int)(windowWidth * 0.2); // 20%
        int mainHeight = windowHeight - menuHeight - bottomHeight;
        
        // 初始化组件
        menuBar = new MenuBarWidget(0, 0, windowWidth, menuHeight);
        leftControls = new ControlPanelWidget(0, menuHeight, leftWidth, mainHeight);
        rightPlayer = new PlayerPanelWidget(leftWidth, menuHeight, 
            windowWidth - leftWidth, mainHeight);
        bottomTimeline = new TimelinePanelWidget(0, menuHeight + mainHeight,
            windowWidth, bottomHeight);
        
        // 添加组件
        addRenderableWidget(menuBar);
        addRenderableWidget(leftControls);
        addRenderableWidget(rightPlayer);
        addRenderableWidget(bottomTimeline);
    }
}
```

## 🔧 技术实现方案（完全重构）

### **1. 播放器区域实现**

```java
public class PlayerPanelWidget extends ContainerWidget {
    private CameraPreviewRenderer previewRenderer;
    private PlaybackControlsWidget controls;
    private double currentTime = 0.0;
    
    public PlayerPanelWidget(int x, int y, int width, int height) {
        super(x, y, width, height);
        
        // 1. 创建相机预览渲染器
        previewRenderer = new CameraPreviewRenderer();
        
        // 2. 创建播放控制
        controls = new PlaybackControlsWidget();
        controls.setOnTimeUpdate(this::onTimeUpdated);
        
        // 3. 创建时间显示
        timeDisplay = new TimeDisplayWidget();
    }
    
    // 时间更新回调
    private void onTimeUpdated(double newTime) {
        this.currentTime = newTime;
        
        // 1. 更新自己的时间显示
        timeDisplay.setTime(newTime);
        
        // 2. 通知时间轴更新竖线位置
        timelinePanel.setCurrentTime(newTime);
        
        // 3. 更新相机状态
        updateCameraAtTime(newTime);
    }
    
    // 根据时间更新相机
    private void updateCameraAtTime(double time) {
        // 调用新的相机系统
        CameraStateData state = timeline.getCameraStateAt(time);
        CameraTransformData transform = timeline.getCameraTransformAt(time);
        
        // 应用相机状态
        cinematicCamera.setCameraState(state);
        cinematicCamera.setTransform(transform);
    }
    
    // 渲染方法
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        
        // 渲染相机预览
        previewRenderer.render(graphics, getX(), getY(), width, height);
    }
}
```

### **2. 时间轴区域实现**

```java
public class TimelinePanelWidget extends ContainerWidget {
    private FrozenHeaderWidget frozenHeader;  // 左侧冻结区域
    private TimelineCanvasWidget timelineCanvas; // 时间轴画布
    private TimeIndicatorWidget timeIndicator; // 当前时间竖线
    
    // 无限滚动参数
    private double maxHorizontalScroll; // 动态计算
    private int maxVerticalScroll;      // 动态计算
    
    public TimelinePanelWidget(int x, int y, int width, int height) {
        super(x, y, width, height);
        
        // 1. 创建冻结区域（左侧）
        frozenHeader = new FrozenHeaderWidget(0, 0, FROZEN_WIDTH, height);
        
        // 2. 创建时间轴画布（可滚动区域）
        timelineCanvas = new TimelineCanvasWidget(FROZEN_WIDTH, 0, 
            width - FROZEN_WIDTH, height);
        
        // 3. 创建时间指示器
        timeIndicator = new TimeIndicatorWidget();
        
        // 4. 设置滚动监听
        timelineCanvas.setOnScroll(this::onScroll);
    }
    
    // 设置当前时间（来自播放器区域）
    public void setCurrentTime(double time) {
        // 1. 更新时间指示器位置
        timeIndicator.setTime(time);
        
        // 2. 如果需要，自动滚动到可见区域
        ensureTimeVisible(time);
    }
    
    // 确保时间在可见区域内
    private void ensureTimeVisible(double time) {
        double timeX = timeToXPosition(time);
        double visibleStart = getScrollX();
        double visibleEnd = visibleStart + getVisibleWidth();
        
        if (timeX < visibleStart || timeX > visibleEnd) {
            // 滚动到使时间在中间位置
            double targetX = timeX - getVisibleWidth() / 2;
            setScrollX(targetX);
        }
    }
    
    // 动态计算滚动范围
    private void updateScrollLimits() {
        // 水平：根据最大时间
        this.maxHorizontalScroll = timeline.getDuration() * PIXELS_PER_SECOND;
        
        // 垂直：根据轨道数量
        this.maxVerticalScroll = timeline.getTrackCount() * TRACK_HEIGHT;
        
        // 防止出错：设置合理上限
        this.maxHorizontalScroll = Math.min(maxHorizontalScroll, MAX_HORIZONTAL);
        this.maxVerticalScroll = Math.min(maxVerticalScroll, MAX_VERTICAL);
    }
    
    // 处理滚动
    private void onScroll(double deltaX, double deltaY) {
        // 水平滚动：时间轴滚动
        double newScrollX = getScrollX() + deltaX;
        newScrollX = Math.max(0, Math.min(newScrollX, maxHorizontalScroll));
        setScrollX(newScrollX);
        
        // 垂直滚动：轨道滚动，冻结区域也滚动
        double newScrollY = getScrollY() + deltaY;
        newScrollY = Math.max(0, Math.min(newScrollY, maxVerticalScroll));
        setScrollY(newScrollY);
        
        // 更新冻结区域位置
        frozenHeader.setScrollY(newScrollY);
    }
}
```

### **3. 联动系统设计**

```java
public class EditorSyncSystem {
    private final PlayerPanelWidget playerPanel;
    private final TimelinePanelWidget timelinePanel;
    private final ControlPanelWidget controlPanel;
    
    // 时间同步
    public void syncTime(double time) {
        // 1. 播放器区域更新
        playerPanel.setCurrentTime(time);
        
        // 2. 时间轴区域更新
        timelinePanel.setCurrentTime(time);
        
        // 3. 控制面板更新
        TimelineSelection selection = timelinePanel.getSelectionAtTime(time);
        controlPanel.onSelectionChanged(selection);
    }
    
    // 选择同步
    public void syncSelection(TimelineSelection selection) {
        // 1. 控制面板显示属性
        controlPanel.onSelectionChanged(selection);
        
        // 2. 如果选择的是关键帧，播放器预览该帧
        if (selection.hasKeyframes()) {
            playerPanel.previewKeyframe(selection.getKeyframes().get(0));
        }
    }
}
```

### **4. 动态布局系统**

```java
public class DynamicLayoutManager {
    public static void layoutComponents(CameraEditorScreen screen) {
        int windowWidth = screen.width;
        int windowHeight = screen.height;
        
        // 使用百分比而非固定像素
        int menuHeight = (int)(windowHeight * 0.05);  // 5%
        int bottomHeight = (int)(windowHeight * 0.3); // 30%
        int leftWidth = (int)(windowWidth * 0.2);     // 20%
        int mainHeight = windowHeight - menuHeight - bottomHeight;
        
        // 设置组件bounds
        screen.menuBar.setBounds(0, 0, windowWidth, menuHeight);
        screen.leftControls.setBounds(0, menuHeight, leftWidth, mainHeight);
        screen.rightPlayer.setBounds(leftWidth, menuHeight, 
            windowWidth - leftWidth, mainHeight);
        screen.bottomTimeline.setBounds(0, menuHeight + mainHeight,
            windowWidth, bottomHeight);
    }
}

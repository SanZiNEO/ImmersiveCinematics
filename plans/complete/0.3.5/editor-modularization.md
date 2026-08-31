# 编辑器模块化与面板重构（Editor Modularization & Panel Refactor）

**版本**: 0.3.5
**类型**: 重构 / 稳定性
**状态**: ✅ 已完成（PanelTabBar / ScrollablePanel / panel / fields 拆分已落地）
**关联**: `editor-interaction-improvements.md`（飞行取景）、`presets.md`（预设库 UI 接入）、`schema-java-metadata.md`（字段元数据迁移）

---

## 一句话定义

把越来越臃肿的编辑器代码按模块拆开：保留 `UIComponent` 父子树作为主要骨架，把 Tab 栏、滚动内容、各属性面板、预设库、字段反射等职责拆到独立 Java 文件中；同时修复“Tab 随面板滚动”和“触发器组件点击无反应”的问题。

## 背景 / 问题

- `EditorScreen` 已 1000+ 行，承担布局、菜单、时间轴回调、左面板回调、文件 IO、播放同步等大量职责。
- `LeftPanelArea` 875 行，Tab 栏 + 滚动内容 + 脚本列表 + 属性反射 + 轨道列表 + 触发器面板全在一个类里。
- Tab 栏虽然在渲染上做了“不随滚动平移”的特殊处理，但结构上仍嵌套在可滚动面板内部，视觉/命中容易乱。
- `TriggerPanel` 构造时 `h = 1`，且 `rebuild()` 没有把内容高度写回 `this.h`，导致命中区只有 1px，子组件点不到。
- `UIComponent.mouseInsideSelf` 用 `absY()` 未扣除滚动偏移，滚动容器里的内容命中坐标与渲染坐标不一致。
- 后续预设库、更多 Tab、飞行取景等会继续增加复杂度，必须先拆结构。

## 目标

1. 编辑器整体按模块拆分，保留清晰的父子/树形关系。
2. Tab 栏与内容区变成两个平级组件，不再嵌套。
3. 修复触发器面板点击失效与滚动命中错位。
4. 预设库作为独立模块，并集成到面板中。
5. 为后续“一个预设一个文件”的扩展方式打好结构基础。

## 拆分结构（草案）

```
editor/
├── EditorScreen.java            # 总协调/装配：布局、回调、文件 IO 入口
├── area/
│   ├── PanelTabBar.java         # 🆕 独立 Tab 栏组件（面板上方）
│   ├── ScrollablePanel.java     # 🆕 通用可滚动面板容器（只负责滚动/裁剪）
│   ├── LeftPanelArea.java       # 瘦身为“面板容器”：TabBar + ScrollablePanel
│   └── PreviewArea.java / TimelineArea.java / MenuBarArea.java
├── panel/                       # 🆕 各 mode 内容面板（从 LeftPanelArea 拆出）
│   ├── ScriptListPanel.java
│   ├── ScriptPropertiesPanel.java
│   ├── ClipPropertiesPanel.java
│   ├── KeyframePropertiesPanel.java
│   ├── TrackListPanel.java
│   └── TriggerPanel.java        # 修复 h=1 / 滚动命中
├── fields/                      # 🆕 字段反射/渲染工具（从 LeftPanelArea 拆出）
│   ├── FieldReflector.java
│   └── FieldWidgetFactory.java
├── preset/                      # 🆕 预设库（与 presets.md 配合）
│   ├── Preset.java
│   ├── PresetRegistry.java
│   └── PresetPanel.java         # 面板内预设表单/生成入口
└── widget/                      # 现有 UI 组件
```

## 面板树形关系（目标）

```
EditorScreen
├── MenuBarArea
├── PanelTabBar          ← 独立组件，固定在左上方
├── LeftPanelArea        ← 可滚动内容区，与 TabBar 平级
│   ├── ScriptListPanel / ScriptPropertiesPanel / ...
│   └── PresetPanel（新增）
├── PreviewArea
└── TimelineArea
```

- `PanelTabBar` 不参与 `LeftPanelArea` 的滚动/裁剪。
- `LeftPanelArea` 只负责内容滚动，不再负责 Tab 渲染与命中。

## 关键修复点

1. **TriggerPanel 点击失效**：
   - `rebuild()` 末尾把 `this.h` 更新为实际内容高度。
   - 或让 `TriggerPanel` 改为“内容高度自适应”并让父滚动容器按子组件 bottom 计算。
2. **滚动命中错位**：
   - `UIComponent.mouseInsideSelf` 应使用 `hitY()`（`absY() - scrollCompensation()`）而不是 `absY()`。
   - 或统一让滚动容器在分发命中前用 hit 坐标。
3. **Tab 滚动问题**：
   - 把 `tabButtons` 从 `LeftPanelArea` 中彻底移除，改为 `PanelTabBar` 兄弟组件。
   - `PanelTabBar` 切换模式时通知 `LeftPanelArea`/`EditorScreen` 重建内容面板。

## 实施顺序

1. 修复 TriggerPanel 高度与 UIComponent 滚动命中（小步、独立）。
2. 抽出 `PanelTabBar`，`LeftPanelArea` 变为纯滚动容器。
3. 按 mode 拆分内容面板到 `panel/` 包。
4. 拆分字段反射到 `fields/` 包。
5. 拆分 `EditorScreen` 的非协调职责（文件 IO、播放控制等）。
6. 接入 `PresetPanel`（预设库 UI）。

## 验收

- 面板滚动时 Tab 栏完全不动。
- 触发器面板所有子组件可点击、可编辑。
- 滚动画板后，内容命中位置与视觉一致。
- 编辑器各职责不在单个文件里继续膨胀。
- 预设面板能展示预设参数表单并生成脚本。

## 参考文件

- `common/src/main/java/com/immersivecinematics/immersive_cinematics/editor/EditorScreen.java`
- `common/src/main/java/com/immersivecinematics/immersive_cinematics/editor/area/LeftPanelArea.java`
- `common/src/main/java/com/immersivecinematics/immersive_cinematics/editor/trigger/TriggerPanel.java`
- `common/src/main/java/com/immersivecinematics/immersive_cinematics/editor/widget/UIComponent.java`
- `example/ImmersiveCinematics-main` 旧版 Path 类（预设数学来源）

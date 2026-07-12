# 阶段 0 — 安全 + Bug + 死代码

**目标版本**: 0.3.3  
**状态**: 待实施  
**依赖**: 无（所有项独立，不改行为）

---

## S1 — `/icinematics play` 路径遍历

**文件**: `command/CinematicCommand.java`

### 问题

`play` 命令通过 `StringArgumentType.getString(context, "file")` 接受用户输入的脚本文件名，在 `findScriptFile()` 中按以下顺序搜索：全局目录 → 世界目录 → 直接解析为绝对路径（`Path.of(filePath)`）。

第 3 个分支（第 229 行）是路径遍历入口。攻击者传入 `../../etc/passwd` 会被 `Path.of()` 直接解析，读取服务器任意文件。且读取内容通过 `S2CPlayScriptPacket.send(player, json)` 广播给所有目标玩家。

此外，`play` 命令没有权限限制（`reload` 有 `.requires(s -> s.hasPermission(2))`，`play` 没有）。

### 架构背景

当前模组已经实现了完整的分发流程：
1. **服务启动**：`ScriptManager.copyGlobalToWorld()` 将全局目录脚本复制到世界存档目录
2. **重载**：`/reload` 命令覆盖同步全局到世界目录
3. **编辑器保存**：直接写入世界存档目录

因此 **`play` 命令只需要从世界存档目录读取**即可，不需要搜索全局目录，更不需要绝对路径。

### 修复方向

1. 给 `play` 命令注册加上 `.requires(s -> s.hasPermission(2))`，限制为 OP 2 级
2. 重写 `findScriptFile()`：只搜索世界存档目录（`server.getWorldPath(WORLD_SCRIPT_DIR)`），删除全局目录搜索和 `Path.of(filePath)` 绝对路径分支
3. 输入应为脚本文件名（含或不含 `.json` 后缀），等同于 `worldDir.resolve(fileName)`，无路径穿越可能
4. 文件大小上限作为额外的安全保护

---

## E1 — 中文输入被拦截

**文件**: `editor/widget/UITextInput.java`、`editor/widget/UIAutoCompleteInput.java`

### 问题

两个输入组件的 `charTyped(char c)` 方法都用 `c >= 32 && c < 127` 判断是否接收输入。`c < 127` 过滤了所有非 ASCII 字符，包括中文、日文、韩文等 Unicode 字符。编辑器脚本的 `name`、`author`、`description` 字段无法输入中文。

### 修复方向

将 `c >= 32 && c < 127` 改为 `!Character.isISOControl(c)`。`Character.isISOControl()` 覆盖的范围（0x00-0x1F 和 0x7F-0x9F）恰好是原代码 `c >= 32` 想去掉的控制字符，且不会拦截 Unicode 中的合法字符。

---

## B1 — 工具栏按钮点击区域与渲染区域不一致

**文件**: `editor/area/TimelineArea.java`

### 问题

`drawToolbar()` 第 151 行用 `(int)(3 * EditorScreen.sx)` 计算工具栏起始 X 坐标（带缩放），而 `clickToolbar()` 第 276 行用 `x + 3`（无缩放）。当 GUI Scale 不是 100%（即 `sx != 1`）时，按钮的渲染位置和点击命中区域发生偏移——渲染已缩放，但点击还是检测原位置，导致按钮点不到。

`by` 坐标同理：第 152 行 `(int)(4 * sy)` vs 第 277 行 `+ 4`，不过因为 `headerH()` 本身是缩放方法，偏差较小。

### 修复方向

将 `clickToolbar()` 第 276 行的 `bx = x + 3` 改为 `bx = x + (int)(3 * EditorScreen.sx)`，第 277 行的 `by = y + headerH() + 4` 改为 `by = y + headerH() + (int)(4 * EditorScreen.sy)`。

---

## R1 — 新建脚本引导块重复三份

**文件**: `editor/EditorScreen.java`

### 问题

`init()` 的 `firstInit` 分支（第 168-198 行）、`wireMenu().setOnNewScript()`（第 215-246 行）、`wireLeftPanel().setOnNewScript()`（第 403-434 行）三段代码几乎逐字相同：调用 `EditorOperations.addClip()` 创建 CAMERA clip，设置六条 clip 属性，遍历关键帧设置 position/yaw/pitch/roll/fov/zoom/dof，再手动构建 LETTERBOX clip 并添加到第 2 条轨道，最后选中 clip。三段代码总行数约 90 行。

差异仅为日志字符串（"from menu" vs "from left panel" vs firstInit 无日志）和 firstInit 多出的初始化逻辑（第 160-165 行的 `refreshScriptList`/`doc.reset`/菜单状态设置）。

### 修复方向

抽取出 `bootstrapNewScript()` 方法，包含核心创建逻辑：创建 CAMERA clip、设置默认属性、填充默认关键帧值、创建 LETTERBOX clip。三处调用点分别处理各不相同的前置和后置逻辑（日志、`menuBar`/`sel` 更新）。firstInit 的额外初始化保留在原处，不走新方法。

---

## R3 — `EditorOperations.recalc()` 空转

**文件**: `editor/EditorOperations.java`

### 问题

`recalc()` 方法（第 245-256 行）遍历所有轨道和 clip，计算 `maxEnd`，然后直接丢弃计算结果（只有注释"total_duration is in timeline, not accessible from here; caller handles it"）。该方法无任何调用方（grep 结果为空）。

但 `addClip()` 第 78 行调用了 `recalc(tracks)`——这行是无用的空调用，因为结果未被使用。`recalcDuration()` 方法功能相同但返回结果，是正确的方法。

### 修复方向

删除 `recalc()` 方法，将 `addClip()` 第 78 行的 `recalc(tracks)` 改为 `recalcDuration(tracks)`（如果返回值需要被消费），或直接删除该行。

---

## R4 — `LeftPanelArea.reflectFloatField()` 和 `addField()` 无调用方

**文件**: `editor/area/LeftPanelArea.java`

### 问题

`reflectFloatField()`（第 488 行）和 `addField()`（第 509 行）两个方法定义了但没有被任何代码调用。属于 0.3.2 重构过程中遗留的废弃方法。

### 修复方向

删除这两个方法及其相关的 `addDefault` 辅助调用，约 30 行。

---

## R5 — `TimelineArea.LEFT_W` 未用、`verticalScroll` 只写不读

**文件**: `editor/area/TimelineArea.java`

### 问题

- `LEFT_W` 常量（第 51 行）值为 `-1`，注释写着"placeholder, use toolbarW() + labelW()"，是个占位常量，未在任何实际逻辑中使用
- `verticalScroll` 字段（第 23 行）在 `mouseScrolled()` 第 461 行被写入（`+= scroll * 20`），但从未被任何代码读取——垂直滚动没有实际效果

### 修复方向

删除 `LEFT_W` 常量。删除 `verticalScroll` 字段及其在 `mouseScrolled()` 中的写入，或者确认是否需要实现垂直滚动后再保留（从编辑器布局看，时间轴通常不需要垂直滚动，轨道按需可见）。

---

## A5 — EditorLogger 每次 println 都磁盘同步

**文件**: `editor/debug/EditorLogger.java`

### 问题

`getWriter()` 第 49 行创建 `PrintWriter` 时传递了 `autoFlush=true` 参数。这意味着每次 `println()` 后都触发一次 flush()，文件系统写入。在编辑器拖拽操作（每次鼠标事件都触发日志）时，每帧可能产生多次磁盘 I/O，影响编辑器响应流畅度。

### 修复方向

将 `autoFlush=true` 移除，改为手动在关键点（如 `close()` 时）调用 `flush()`。日志写入只在缓存满时自动刷出。

---

## R6 — LeftPanelArea 缩放字面量

**文件**: `editor/area/LeftPanelArea.java`

### 问题

布局代码中多处用硬编码像素值做 Y 轴间距，没有乘以 `sy` 缩放因子。例如第 98 行 `cy += 16`、第 158 行 `cy += 16`、第 188 行 `cy += 16`、第 130 行 `cy += tp.h + 6` 中的 `6` 等。当 GUI Scale 改变时，这些间距不缩，导致组件分布不均匀。

面板中已有大量正确缩放的代码（如 `(int)(16 * EditorScreen.sy)` 等），R6 涉及的这几处是遗留的硬编码。

### 修复方向

搜索 `LeftPanelArea.java` 中所有不与 `EditorScreen.sx/sy` 相乘的布局像素值，替换为相应的缩放计算。Y 轴方向用 `sy`，X 轴方向用 `sx`。只改布局相关像素，不改逻辑判断中的阈值。

---

## C3 — 死掉的 FBO 回调机制

**文件**: `editor/EditorBridge.java`、`client/EditorBridgeImpl.java`、`editor/area/PreviewArea.java`

### 问题

`EditorBridge` 接口中定义了 `setFboCallback(IntConsumer)`，`EditorBridgeImpl` 实现了它和 `notifyFbo(int)`，`PreviewArea` 中有 `setFboTexture(int)` 和 `setPlaying(boolean)`。这些方法均无任何调用方——FBO 回调机制在编辑器重构过程中被废弃，但接口和实现未清理。

### 修复方向

- 从 `EditorBridge` 接口删除 `setFboCallback` 方法声明
- 从 `EditorBridgeImpl` 删除 `setFboCallback` 和 `notifyFbo` 的实现
- 从 `PreviewArea` 删除 `setFboTexture` 和 `setPlaying` 方法及其关联的 `fboTextureId`/`playing` 字段

---

## F1 — PreviewCapture 使用废弃的 `GL_CLAMP`

**文件**: `editor/PreviewCapture.java`

### 问题

`initFbo()` 第 43-44 行设置纹理环绕模式时使用了 `GL11.GL_CLAMP`。该常量在 OpenGL 3.0 起已废弃，其行为在不同实现中有歧义。现代 OpenGL 使用 `GL_CLAMP_TO_EDGE`（`GL12.GL_CLAMP_TO_EDGE`）代替。

### 修复方向

将 `GL11.GL_CLAMP` 替换为 `GL12.GL_CLAMP_TO_EDGE`，同时增加 `import org.lwjgl.opengl.GL12`。

---

## R1 (RawInputLogger) — 文件句柄不关闭

**文件**: `editor/debug/RawInputLogger.java`

### 问题

`disable()` 方法（第 43 行）只设置 `enabled = false` 并写入最后一条日志，但从未关闭 `PrintWriter writer`。编辑器从打开到关闭的过程中，文件句柄始终保持打开状态。`EditorLogger` 有关闭方法（`close()`），但 `RawInputLogger` 没有对应的清理。

### 修复方向

在 `RawInputLogger` 中新增或修改关闭方法：调用 `writer.close()` 并将 `writer` 置为 null。在 `EditorScreen.onClose()` 中（已调 `RawInputLogger.disable()`）确保 writer 被关闭。

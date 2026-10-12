# 01 游戏内编辑器「触发器」：区域模式（A→B 方体）用不了

- **来源**：0.3.5 实际使用反馈
- **状态**：⛔ 不修——随游戏内编辑器退役作废（0.3.6 删除 `editor/` 包，见 `../editor-webui-migration.md` §4）；WebUI 编辑器的触发器编辑已按 schema 重做
- **相关**：`docs/TRIGGER_TYPES.md`（location 的三种检测方式）、运行时 `Evaluators.evaluateLocation`

## 使用反馈（原文）

> 反正就是操作很难，我都不知道怎么使用区域模式，它现在就是编辑器里只有中心点+范围模式。

- 想配的是"从 A 坐标到 B 坐标的区域触发"（`location` 触发器的 box 模式，`corner1` + `corner2`）。
- 该触发器最终未保存（用户确认）——**不是保存链路问题**，是 UI 用不了。

## 现象（用户视角）

1. 触发器 tab 新建 `location` 触发器后，条件区只有：维度、[一行显示原始 key 的下拉]、X / Y / Z、半径。
2. 想找"区域模式"入口 → 找不到；看起来"编辑器只有中心点 + 范围模式"。
3. 那行 `editor.trigger.submode.point+radius` 是**未翻译的原始 i18n key**，看不出它是"检测方式"切换器。
4. 即使点开它选「区域」→ **界面没有任何变化**（下拉仍显示原始 key，字段区仍是 X/Y/Z/半径），无法进入区域模式。

## 根因（代码定位）

### A. 切换器无标签 + key 拼写不一致（显示原始 key）

- `editor/trigger/LocationEditor.java`：
  - 子模式下拉裸放，无标签；
  - key 拼写不一致：代码拼 `"editor.trigger.submode." + "point+radius"`，而 lang（`zh_cn.json` / `en_us.json`）定义的是 `editor.trigger.submode.point_radius`（下划线）→ 找不到翻译，原样返回 key。`box` 一条能对上（"区域"），但用户到不了这一步。

### B. 切换子模式后面板不重建（关键：切不过去）

- `LocationEditor.build()` 里 `boolean isBox = conditions.has("corner1")` 是 **build 时局部变量**；下拉当前项 getter 捕获它，重建前永远不变。
- 下拉回调只调用 `onDirty.run()`；`TriggerPanel` 传给条件编辑器的 `onDirty = ctx.onDirty`（→ `EditorScreen` 的 dirty 回调：撤销快照 + markDirty + 推送脚本），**不含面板重建**。
- 编辑器里"改变字段结构"的控件惯例是 `markDirty(); requestRebuild();`（`EditorPanel` → `LeftPanelArea.scheduleBuild()` 150ms 防抖）；触发器条件编辑器拿不到 requestRebuild，`TriggerPanel` 自己的类型下拉靠显式 `build()` 兜。
- 结果：选「区域」后 conditions 数据层面已切换（position/radius → corner1/corner2），但 UI 不刷新 → 用户看到"没反应"，结论"只有中心点+范围模式"。字段区旧控件已与 conditions 脱钩/半脱钩，继续输入不会正确写回。
- **临时绕过**（不是修复）：切到别的 tab 再切回来，面板重建后 corner1/corner2 字段才会出现。

### C. 附带：类型下拉无标签、信息重复

- tab 顶部两行都是无标签下拉：第一行触发器列表显示 `trigger_1 (location)`（类型为英文原值），第二行类型下拉显示"位置"。同一信息两种呈现、中英混排。

## 影响

- **区域触发（corner1/corner2）在游戏内编辑器里实际不可用**——格式与运行时都支持，但 UI 表达不出来。
- 直接印证"游戏内编辑器不能发挥模组全部能力"（见 02）。

## 修改方向（待定）

1. 子模式下拉加标签（"检测方式"/"区域模式"）+ 选项名写全（"点+半径（球体）"/"区域（两点方体）"）；
2. 修 i18n key（`point_radius`）或改 lang 键；
3. 让改变字段结构的控件在 `onDirty` 之外触发一次重建（对齐 `markDirty + requestRebuild` 既有模式）；
4. 类型下拉加「类型」标签 / 与列表项合并呈现。

## 对照：WebUI 编辑器

- `editor/src/components/trigger/LocationTriggerEditor.vue`：有「区域模式」标签、选项"点 + 半径 / 两点方体区域"；切换走 `updateMany` + Vue 响应式，不存在"切不过去"。
- `editor/src/components/TriggerPanel.vue`：有「触发类型」标签、「基本设置 / 触发条件」分组、列表项带可见删除按钮。
- 迁移时**别退化**；本文件可当检查单。

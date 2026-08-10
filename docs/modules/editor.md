# editor（游戏内编辑器）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/editor/`

功能树：

- **编辑器框架与布局**
  - ✅ `EditorScreen` 为游戏内全屏编辑器，四区布局：菜单栏/左侧属性面板/预览区/时间轴，以 960×540 参考分辨率等比缩放（`Scale`）
  - ✅ `Scale` 集中管理 UI 缩放系数（参考分辨率 960×540），各区域按 `sx/sy` 自适应窗口（`Scale`）
  - ✅ `MenuBarArea` 提供标题、新建/保存按钮、状态文本与 3 秒动作提示（脚本列表按钮已移除，由面板 tab 栏覆盖）（`MenuBarArea`）
  - ✅ `EditorDocument` 管理脚本 JSON 文档：新建默认模板（meta + 5 种轨道）、加载/序列化、文件名清洗、脏标记（`EditorDocument`）
  - ✅ `EditorBridge` 接口解耦编辑器与相机链路：setTime/pushScript/play/pause/stop（`EditorBridge`）
  - ✅ 编辑器打开时不暂停游戏（`isPauseScreen()` 返回 false），配合 `PreviewCapture` 实时捕获游戏画面供预览区显示（`EditorScreen`）
- **UI 组件树与事件分发**
  - ✅ `UIComponent` 为组件树基类：子节点渲染（zIndex 降序）、焦点系统、鼠标/键盘事件模板方法逐层分发（点击/拖拽/释放/滚动/按键/字符）（`UIComponent`）
  - ✅ `UIContext` 传递渲染上下文：GuiGraphics/字体/鼠标/修饰键，提供视口裁剪（push/pop/shiftViewport）与遗留滚动 API（`UIContext`）
  - ✅ 通用控件：`UIButton`（按钮）、`UIToggle`（开关）、`UILabel`（文本）、`UITextInput`（失焦提交文本输入）、`UIFloatInput`（数值输入，滚轮步进/范围钳制）、`UIDropdown`（下拉，支持右键/高亮/滚动）、`UIAutoCompleteInput`（自动补全输入，实时过滤/键盘选择/弹出列表）（`UIButton`、`UIToggle`、`UILabel`、`UITextInput`、`UIFloatInput`、`UIDropdown`、`UIAutoCompleteInput`）
  - ✅ `IFocusable` 统一文本输入控件的焦点接口，`ContextMenu` 提供通用右键菜单（条目/分隔线/屏幕边缘钳制）（`IFocusable`、`ContextMenu`）
  - ✅ 组件支持悬浮提示（tooltip）与 overlay 渲染层（下拉展开列表/右键菜单浮于顶层）（`UIComponent`、`UIDropdown`、`UIAutoCompleteInput`）
- **时间轴**
  - ✅ `TimelineArea` 渲染多轨道：轨道标签列 + 类型颜色标记 + 轨道底色 + 分隔线 + 空轨道提示（`TimelineArea`）
  - ✅ clip 按轨道类型着色（蓝/绿/黄/红/紫），3D 凸起描边，选中态金色左侧条，宽度足够时显示名称标签；morph 过渡以灰白半透明装饰块显示（中心对齐片段末尾、无文字、不参与命中），clip 视觉起点 = 数据 start + 前导转场 t/2（视觉顺排不重叠，交界处左右可分别选中）（`TimelineArea`）
  - ✅ 标尺自适应刻度（0.5s/1s/5s/10s/10 的幂）与主/次刻度线，点击标尺跳转播放头（`TimelineArea`）
  - ✅ 播放头：纵贯全轨道的红色竖线 + 顶部三角形指示器（`TimelineArea`）
  - ✅ 缩放系统：Ctrl+滚轮以鼠标位置为中心缩放（每步 ±25%，10~5000 px/s），Shift+滚轮水平滚动，滚轮垂直滚动轨道，Ctrl+0 重置（`TimelineArea`）
  - ✅ 拖拽交互：clip 移动/左右边缘裁剪/关键帧拖动，半透明 ghost 拖拽预览，8px 阈值吸附播放头与其他 clip 边缘，金色闪烁吸附指示器（`TimelineArea`）
  - ✅ 框选：矩形框选（蓝色半透明）选中时间与轨道范围内所有 clip（`TimelineArea`）
  - ✅ 工具栏按钮：添加/删除 clip、添加/删除关键帧、添加/删除轨道、吸附排列（`TimelineArea`）
  - ✅ 右键菜单三处入口：clip（复制/剪切/删除/复制偏移/分割/添加关键帧）、轨道标签（添加 clip/删除轨道/新增轨道）、时间轴空白（按轨道添加 clip/添加关键帧/全选/粘贴/新增轨道/吸附排列）、标尺（跳转播放头/缩放至全部可见）（`TimelineArea`、`EditorScreen`）
- **左侧面板**
  - ✅ `LeftPanelArea` 提供 6 个标签页：脚本列表/脚本属性/Clip 属性/关键帧属性/轨道列表/触发器（`LeftPanelArea`）
  - ✅ 脚本属性页：触发器面板 + 脚本信息（id/name/author/version/description/dimension）+ 20 个运行时行为开关（部分三态：未设置/真/假）+ 总时长显示（`LeftPanelArea`）
  - ✅ 属性反射编辑：clip/keyframe 的 JSON 字段自动生成对应控件（布尔→开关、数字→数值输入、字符串→文本输入、对象→递归展开、数组→逐项展开），枚举字段（transition/interpolation/loop_mode/layer_type/position_mode/source/attenuation/cam_tracking_*）循环切换并联动转换关键帧坐标模式（`LeftPanelArea`）
  - ✅ 面板滚动：内容超高时出现滚动条（点击/拖动/滚轮）；滚动是树语义（`getScrollOffset` 沿父链），命中统一绝对屏幕坐标 + 容器裁剪，tab 栏固定不随内容滚动，切模式滚动归零（`LeftPanelArea`、`UIComponent`）
  - ✅ 编辑触发防抖重建（150ms 内跳过重复 build）（`LeftPanelArea`）
- **预览区**
  - ✅ `PreviewArea` 16:9 保持宽高比居中显示预览画面 + 播放/暂停 toggle 按钮（播放中⏸/非播放▶）+ 终止按钮（重置播放头到第一帧，保持预览激活）+ 当前时间标签（`PreviewArea`）
  - ✅ `PreviewCapture` 通过 FBO 拷贝主渲染目标为纹理（分辨率变化时重建），供预览区带 UV 映射绘制（`PreviewCapture`）
- **编辑操作**
  - ✅ `EditorOperations` 提供全部结构操作：添加/删除 clip、添加/删除关键帧、移动/左右裁剪 clip、移动关键帧、跨轨道移动 clip、添加/删除轨道、clip 分割（Razor）、吸附排列、关键帧排序去重、边界关键帧保证（`EditorOperations`）
  - ✅ 新建 clip 按轨道类型填充 schema 默认字段（CAMERA 含追踪/呼吸字段、AUDIO 含音频字段、EVENT/OVERLAY 含各自字段）（`EditorOperations`）
  - ✅ 关键帧排序去重：相邻重复时间自动合并（`EditorOperations`）
  - ✅ 保存前校验 `validateScript()`：version=3、total_duration≠0、clip duration≠0、CAMERA clip 必须有关键帧、关键帧时间单调、同轨道 clip 重叠检测（`EditorOperations`）
- **撤销重做与剪贴板**
  - ✅ `EditorUndoManager` 维护 50 步快照栈（undo/redo 双向），编辑操作前压入文档 JSON 快照（`EditorUndoManager`）
  - ✅ 剪贴板：Ctrl+C 复制携带 `_trackType` 元数据，Ctrl+V 按轨道类型匹配自动粘贴（无匹配轨道时自动创建），Ctrl+X 剪切，Ctrl+D 复制偏移 0.5s（`EditorScreen`）
  - ✅ 快捷键全集：Enter 播放选中 clip、Ctrl+A 全选、Ctrl+C 复制、Ctrl+V 粘贴、Ctrl+X 剪切、Ctrl+Z 撤销、Ctrl+Y/Ctrl+Shift+Z 重做、←/→ 移动播放头（Shift 加速 5s）、Ctrl+←/→ 跳转 prev/next clip、Ctrl+Shift+←/→ 时间线起点/终点、Home/End、PageUp/PageDown 上下轨道、[ / ] 跳转 clip 起点/终点、F 缩放至全部可见、Ctrl+0 重置缩放、Delete 删除、Ctrl+D 复制偏移 0.5s、Ctrl+S 保存；选中 clip 时 ←/→ 微调位置（0.1s，Shift 1s），选中关键帧时 ←/→ 微调时间、↑/↓ 微调 yaw（0.5s，Shift 5s）（`EditorScreen`）
- **选择/播放/输出**
  - ✅ `EditorSelection` 管理选中态：单选/多选切换/批量选择/关键帧选择，变更时回调驱动面板模式切换（`EditorSelection`）
  - ✅ `EditorPlayback` 独立播放时钟（毫秒推进，播到总时长自动停止）（`EditorPlayback`）
  - ✅ `EditorOutput` 在客户端 tick 统一派发编辑意图到桥接层：时间节流 50ms、脚本推送节流 200ms，避免 UI 事件内直接发送（`EditorOutput`）
  - ✅ 打开脚本时复制到 `temp/` 再加载，保存写回原路径（`EditorScreen`）
  - ✅ 新建脚本引导 `bootstrapNewScript()`：自动生成默认 CAMERA clip（含完整关键帧属性）与 LETTERBOX 全时长 clip（2.35:1），新建文档轨道列表含 CAMERA/LETTERBOX/AUDIO/EVENT/MOD_EVENT 五种（`EditorScreen`、`EditorDocument`）
- **触发器条件编辑**
  - ✅ `TriggerPanel` 集成触发器列表：选择/新增/删除触发器，编辑 id/repeatable/on_enter/exit_buffer/delay 与条件（`TriggerPanel`）
  - ✅ `TriggerEditor` 工厂按类型分发条件编辑器：`NoConditionEditor`（login/command）、`SingleIdEditor`（advancement/biome/dimension/entity_interact/block_interact/item_* /gamestage，带注册表自动补全候选）、`LocationEditor`（point+radius / box 双模式）、`StructureEditor`、`EntityKillEditor`（多实体 + and/or + 场景条件）、`InventoryEditor`（物品列表 + mode + change）、`ItemOnInteractEditor`（item+target+target_type）、`XpEditor`（level/total）、`ObservationEditor`（target+target_type+reach）（`TriggerEditor`、`NoConditionEditor`、`SingleIdEditor`、`LocationEditor`、`StructureEditor`、`EntityKillEditor`、`InventoryEditor`、`ItemOnInteractEditor`、`XpEditor`、`ObservationEditor`）
- **调试日志**
  - ✅ `EditorLogger` 分类日志（AREA/MOUSE/ACTION/STATE）写入 `logs/editor/editor-*.log` 并回显控制台（`EditorLogger`）
  - ⚠️ `RawInputLogger` 提供 GLFW 原始输入记录接口（鼠标/按键/滚轮 + tick 心跳 + 坐标轮询，写入 `logs/input/input-*.log`），但输入事件回调全工程无注册点，实际采集未接线（`RawInputLogger`）

## 已知问题

- `RawInputLogger` 输入采集未接线：类注释声称通过 `ClientRawInputEvent` 注册（鼠标/按键/滚轮/心跳），但 `ClientEventHandler` 未注册任何回调，`onMouseButton/onKeyPress/onMouseScroll/onClientTick` 全工程无调用方；编辑器开启时仅写入 enable/disable 状态行，采集不到输入事件（来源：`RawInputLogger`、`ClientEventHandler`）
- `EditorOperations.fillKeyframeProperties()`/`interpolateKeyframe()` 无调用方：新建关键帧实际只复制相邻帧属性（`copyKeyframeProperties`），不会按时间比例插值（来源：`EditorOperations`）
- 保存校验不阻断：`EditorScreen.saveScript()` 在 `validateScript()` 返回错误时仅记录日志，仍继续写盘，与 CHANGELOG 0.3.3 声称的"有错误时阻断保存"不符（来源：`EditorScreen`）
- 编辑器仍遗留 `[KILO-DEBUG]` 调试输出（脚本列表刷新/初始化时打印到控制台）（来源：`EditorScreen`、`LeftPanelArea`）

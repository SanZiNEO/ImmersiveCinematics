## [0.3.4] - 2026-08-06

音乐配乐 + 覆盖层 + 事件重构 + 镜头追踪与呼吸扰动 + 翻滚角修复。

### 2026-08-09 追加 — 触发器完成、播放队列、编辑器事件树重构、预览与网络加固

**触发器（23 种，custom 删除）**
- 接线 `item_on_interact`（物品+目标+target_type，支持空手 `""`）与 `advancement`（PLAYER_ADVANCEMENT 事件携带 id 匹配，不再误判历史进度）
- 新增 `ItemUseMixin` 覆盖使用状态机：`item_consume`（用完，completeUsingItem）/ `item_release`（弓/弩/三叉戟/望远镜松手，UseAnim 判定）/ `item_use_interrupt`（吃一半松手等）
- 新增 `item_instant_use`（投掷物实体判定：雪球/鸡蛋/珍珠/药水/经验瓶）、`xp`（等级/累计经验轮询）、`dimension`（维度驻留轮询）、`item_pickup`/`item_drop`（拾取/丢弃事件）
- 新增 `observation`（服务端射线注视，block/entity/缺省近者优先，reach 可配）
- `entity_kill` 场景条件：dimension/biome/position/corner1+corner2（按击杀时刻记录，非玩家当前位置）
- 删除 `custom` 触发器（注册/求值/编辑器/文档/示例脚本全清）
- B5 触发器状态同步：触发/完成/JOIN 补发 `S2CTriggerStateSyncPacket`

**播放队列（C1，用户确认规则）**
- `ScriptQueue` 容量 8：不可打断脚本播放时新请求一律排队（priority 降序 + FIFO），结束自动接播；可打断脚本被请求立即替换（无渐出）
- 优先级不能大于打断：priority 仅用于队列内排序；移除 queueable 参数与高优先级强制抢占
- 客户端 `/icinematics queue` 查询命令不实施（与服务端命令根冲突）

**编辑器**
- 事件树命中统一绝对屏幕坐标（修复面板/预览区点击错位：absX/absY 双加父偏移）；滚动容器语义化（getScrollOffset 沿父链），移除 pushScroll 改 mouseY 的坐标补偿
- 鼠标事件模板加容器裁剪（滚动区外不再命中不可见内容）；tab 栏固定不随内容滚动；切模式滚动归零（修复面板"全白"）
- 顶部状态栏移除脚本列表按钮（面板 tab 已覆盖）；播放/暂停合并 toggle 按钮；终止按钮改为重置播放头到第一帧（保持预览激活，不再退出到玩家视角；玩家视角由脚本时间空隙自然产生）
- 预览器修复：编辑器加载不同轨道布局脚本后轨道索引错位（OVERLAY 层不创建/音频误读）→ replaceScript 按布局重建 TrackPlayer；捕获前 flush GUI 缓冲（覆盖层落盘）
- E1 布局常量提取、E3 letterbox smoothstep、E4 删除无用 MathUtil 方法

**网络加固**
- 新增 `NetworkGuard`：所有 C2S 发包统一防护，玩家随时退出/断线不再因 `sendToServer` 抛异常闪退（覆盖脚本结束通知/暂停握手/ACK 重发/回执）

**其它**
- `ScriptValidator` position 检查限定 CAMERA 轨道（消除非 CAMERA 轨道误报）；新增 9 个触发器冒烟测试脚本
- **统一关键帧级调控（用户决定）**：删除 letterbox clip 级 `aspect_ratio` 简写兼容与 EVENT clip 级 `command` 向后兼容——所有轨道一律以 keyframes 调控，旧格式脚本需改写（校验会报缺 keyframes）；补建 inventory change 冒烟脚本（共 10 个）



### Added
- **AUDIO 轨道**：OGG/WAV 音频播放（LWJGL OpenAL 直驱），关键帧控制音量与空间位置，淡入淡出、循环、衰减
- **OVERLAY 轨道**：fade 全屏遮罩 / image 图片 / subtitle 字幕 / pip 画中画四类覆盖层，多层叠加按 zIndex 渲染
- **EVENT 轨道重构**：从 clip 段改为 keyframe 驱动多点触发，`&&` 命令链、权限 4 执行
- **镜头追踪**：CAMERA clip 级 `cam_tracking_look_at`（注视坐标/实体）+ `cam_tracking_follow`（跟随玩家）
- **镜头呼吸扰动**：`cam_breath_enabled` / `cam_breath_intensity` / `cam_breath_seed` 运行时叠加随机微晃
- **翻滚角（roll）修复**：改为绕相机视线轴旋转，任何朝向下 roll>0 均为屏幕顺时针（画面向右倒）
- **删除景深（dof）字段**：原版 MC 无景深能力，全链路移除（代码/schema/文档/测试脚本）
- **Fabric Loader 要求降至 0.14.0**：兼容主流整合包（原 0.19.3 为开发版本误写）
- **新增 docs/AI_SCRIPTING_GUIDE.md**：面向 AI 的脚本编写指南（空间方向体系/速度档位/运镜配方/常见错误）
- **参考脚本**：`cinematics/example_orbit.json` 重写为《第二圆舞曲》配乐脚本，18 段运镜覆盖全部可控字段（静态/直摇/慢推/摇入/环绕/推变焦/微摆/甩镜头/升降/航拍/俯拍/降落/希区柯克变焦/快环绕/荷兰角/拉远/离场/定格）
- 音频文件非 ASCII 命名时输出警告（Windows 下 stb_vorbis fopen 无法解码中文路径）

### Changed
- 音频资源统一英文命名规范（写入指南与格式文档）

## [0.3.3] - 2026-07-26

Architectury 多平台迁移 + 编辑器交互大优化 + 播放器数据对齐。

### Added — 编辑器交互
- **缩放系统**：Ctrl+滚轮以鼠标位置为中心缩放（每步25%），滚轮=水平滚动，Ctrl+0 重置为 1:1
- **播放头与标尺**：纵贯全轨道的红色竖线 + 三角形指示器，标尺主/次刻度线，点击空白处跳转播放头
- **多轨道渲染**：5 种轨道（CAMERA/LETTERBOX/AUDIO/EVENT/MOD_EVENT）全部独立显示，左侧标签列 + 颜色标记 + 分隔线 + 空轨道提示
- **视觉设计**：clip 按轨道类型着色（蓝/绿/黄/红/紫），3D 凸起边框，选中态金色左侧条，clip 名称标签
- **拖拽吸附**：半透明 ghost 拖拽预览，8px 阈值吸附到播放头/clip 边缘，金色闪烁吸附指示器
- **框选与多选**：矩形框选（蓝色半透明）选中范围内所有 clip，Ctrl+click 多选切换，`sel.selectClips()` 全量选择
- **右键菜单**：新建通用 ContextMenu 组件，支持 clip（复制/删除/添加关键帧）、空白（添加clip/新增轨道/吸附排列）、标尺（跳转）三种右键菜单
- **标签栏**：LeftPanel 顶部 5 标签（Scripts/Properties/Clip/Keyframe/Tracks），TRACK_LIST 模式下可点击轨道行选中轨道
- **轨道选中**：`selectedTrackIndex` 持久化轨道选中态，时间轴轨道高亮 + 标签列点击选轨
- **关键帧空支持**：EVENT/AUDIO/MOD_EVENT 关键帧默认为空时间标记（仅 `{"time": x}`），不强制填充数据；`copyKeyframeProperties` 空安全
- **剪贴板**：Ctrl+C 复制携带 `_trackType` 元数据，Ctrl+V 按轨道类型匹配自动粘贴，无匹配时自动 `addTrack`
- **跨轨道操作**：Ctrl+↑/↓ 移动 clip 到上/下轨道，`moveClipToTrack`/`findTrackIndex`
- **clip 分割（Razor）**：右键菜单"分割（在播放头位置）"，`EditorOperations.splitClip()` 拆分 keyframes

### Added — 快捷键
- `Space` 播放/暂停（300ms 防重复）、`Enter` 播放选中 clip
- `Ctrl+A` 全选、`Ctrl+C` 复制、`Ctrl+V` 粘贴、`Ctrl+X` 剪切
- `Ctrl+Z` 撤销、`Ctrl+Y`/`Ctrl+Shift+Z` 重做（50 步快照栈）
- `←/→` 移动播放头 0.5s、`Shift+←/→` 5s
- `Ctrl+←/→` 跳转 prev/next clip、`Ctrl+Shift+←/→` 时间线起点/终点
- `Delete` 删除选中、`Ctrl+D` 复制偏移 0.5s
- `F` 缩放至全部可见、`Ctrl+0` 重置缩放
- `Home`/`End` 时间线起点/终点、`PageUp`/`PageDown` 上/下轨道
- `[`/`]` 跳转 clip 起点/终点

### Added — 播放器数据对齐
- 新增 `EditorOperations.validateScript()` 保存前全量校验：version/duration/关键帧单调性/必填字段/同轨道重叠检测
- 新增 `EditorOperations.sortTrackClips()` 保证 clips 数组按 start_time 升序，addClip/splitClip 后自动调用
- `sortKeyframes()` 添加去重，相邻重复时间的关键帧自动合并
- `EditorScreen.saveScript()` 调用 validateScript，有错误时阻断保存
- EVENT clip 补齐必填字段 `command`，AUDIO 补齐 `sound`，`addClip()` 零时长保护
- `transition`/`transition_duration` 从通用字段改为 CAMERA 专属，不再写入 AUDIO/EVENT/MOD_EVENT

### Changed
- **Architectury 多平台迁移**：Forge + Fabric 统一构建（API 声明式配置、Mixin 模块化、事件总线抽象、网络层 AbstractPacket）
- **运行时数据模型统一**：5 个 Clip 类 + 2 个 Keyframe 类 → schema 驱动通用 `Clip`/`Keyframe` + `Map<String,Object> data`
- 新增 `schema.json` 定义所有轨道类型字段结构和默认值，`SchemaLoader` 运行时加载
- `ScriptParser` 从独立解析方法重构为 schema 驱动统一解析
- `TimelineTrack` 统一为 `getClips()`，删除 5 个类型安全访问器
- `+C` 工具栏按钮改为按当前选中 clip 所在轨道添加，而非硬编码 CAMERA
- `fillKeyframeDefaults()` 只对 CAMERA/LETTERBOX 填充默认值，AUDIO/EVENT/MOD_EVENT 保持空关键帧
- `UITextInput` 从实时提交改为失焦提交（Enter 确认）
- `MenuBarArea`/`PreviewArea` 的 `mouseClicked` 提取共享静态方法
- LeftPanel 编辑触发 `scheduleBuild()` 防抖（150ms 内跳过重复重建）

### Fixed
- **致命**：EVENT clip 缺失 schema 必填字段 `command`，保存后播放器无法解析
- **致命**：拖拽移动/裁剪/关键帧微调/属性编辑无撤销，操作后 Ctrl+Z 无效
- `boxEndTime` 未初始化 → 点击空白处误触发从 t=0 开始的框选
- 框选只选中第一个命中 clip，改为选中范围内所有 clip
- Space 键码误绑为 57（数字 9），改为 32（Space）
- Space 按住时重复触发播放/暂停，300ms 冷却防护
- `fmt()` 无小时位 → `fmt(3661)` = `"61:01"`，改为 `"1:01:01"`
- 标尺刻度极限缩放下异常（≤5s 时 interval=10s 零刻度），改为 0.5s/1s/5s/10s 自适应
- 时间轴 render() 缺少 `drawTracks()`/`drawPlayhead()` 调用，导致时间轴空白
- 点击末帧关键帧预览错位（边界时间在半开区间外），微减 0.001s 保持在活跃区间内
- `copyKeyframeProperties()` 空指针风险，改为空安全访问
- `sortKeyframes()` 不防重复时间，添加去重
- 硬编码 `transition`/`transition_duration` 写入非 CAMERA clip
- `ensureBoundaryKeyframes()` 仅非空源才复制属性

### Removed
- `CameraClip.java`、`CameraKeyframe.java`、`LetterboxClip.java`、`LetterboxKeyframe.java`
- `AudioClip.java`、`EventClip.java`、`ModEventClip.java`
- `onClickEmpty` 回调（已合并到点击空白跳转播放头）
- `architectury` 分支（已合并到 main）
# Changelog

## [0.3.2] - 2026-06-16

编辑器和运行时的深度优化版本，重构脚本系统、UI 架构、布局系统，新增物品交互触发器，修复多项长期 bug。

### Added
- 新增 `item_on_interact` 触发器，同时检查手持物品和交互目标（方块/实体），例如 `carrot on iron_block` 触发 boss 出场
- 新增 `LetterboxKeyframe` 类，letterbox 轨道支持完整关键帧（`start_time`/`duration`/`target_aspect_ratio`/`easing`）

### Fixed
- 相机 Mixin 注入条件从 `isActive()` 改为 `isActive() && hasActiveCameraClip()`，CAMERA gap 和纯 letterbox 脚本不再锁死视角
- `/icinematics play` 改为 `EntityArgument.players()` + `S2CPlayScriptPacket` 分发，修复纯客户端类导致的服务端崩溃，同时支持 `@a`/`@p` 玩家选择器
- 编辑器布局从百分比改为参考分辨率 960×540 等比缩放，不同 MC GUI Scale 下保持一致
- 编辑器新建关键帧后画面空白（缺失 position/yaw/pitch 等默认属性）
- B 键关闭编辑器逻辑修复

### Refactored
- `EditorScreen` 删除对 `EditorBridgeImpl` 和 `CinematicKeyBindings` 的直接引用，消除跨包依赖泄漏
- 新增 `IFocusable` 接口，`UITextInput`/`UIFloatInput`/`UIAutoCompleteInput` 统一实现，4 处 instanceof 分发点合并为 1 行
- `LetterboxClip` 结构对齐 `CameraClip`：删除 `fade_in`/`fade_out`/`enabled`，`aspect_ratio` 移入关键帧属性，`LetterboxTrackPlayer` 重写为关键帧插值
- Transition morph 从独立行为改为前段 clip 的退场阶段（`exit_behavior`），修改 `CameraTrackPlayer`/`EditorOperations`/`TimelineArea`
- 编辑器 UI 树重构：统一父元素相对偏移坐标（`absX()`/`absY()`），事件分发改为 `root.mouseClicked(ctx)` 替代 4 个手动调用，新增 Overlay 层解决跨区组件事件截断
- 编辑器 LETTERBOX 特殊分支删除，合并到通用 clip 编辑流程

### Cleanup
- 清理死代码和重复触发器注册

## [0.3.1] - 2026-05-31

0.3.0 发布后的修复版本，集中修复编辑器关键帧和脚本管理问题，新增 `on_enter` 和 `exit_buffer` 触发器字段。

### Fixed
1. 编辑器新增关键帧导致关键帧数组乱序，ScriptParser 单调递增校验失败，脚本无法载入
2. 新建关键帧缺少 position/yaw/pitch/roll/fov/zoom/dof 属性值，预览时相机跳回原点
3. 所有关键帧修改路径添加排序保护
4. 编辑器新建脚本后保存时仍写回旧文件，覆盖已有脚本
5. 编辑器脚本目录与服务端加载目录不一致，保存后世界内无法生效

### Added
- `/icinematics reload` 命令（op 2 级）：同步全局脚本到世界存档并重载，使编辑内容立即生效
- `/icinematics play` 命令增加 Tab 自动补全
- 触发器新增 `on_enter` 字段。在 `repeatable: true` 基础上设置 `on_enter: true`，位置/群系/结构等触发器只在进入时触发，已在区域内不重复
- 触发器新增 `exit_buffer` 字段。配合 `on_enter: true` 使用，指定玩家离开原区域多少格后才标记为"已离开"，防止区域边界抖动导致反复触发

## [0.3.0] - 2026-05-22

从 0.2.0 完全重构。主要变化：

### Added
- 编辑器完整 UI：时间轴、左侧属性面板、预览区、菜单栏
- 触发器系统（24 种类型 + 条件编辑器 + C2S 网络同步）
- 运行时行为控制系统（CinematicController）
- 管理界面（配置界面、脚本管理、HUD 拦截）
- 多轨道架构（CAMERA / LETTERBOX / AUDIO / EVENT / MOD_EVENT）

## [0.2.0] - 2026-04-16

已发布的旧版本。基于 entity 实现相机，主要包结构：

- **entity-based 相机**：`camera/` 使用 Minecraft Entity 实现，利用原生 tick/同步/插值
- **导演编排**：`director/` 负责镜头序列编排
- **脚本播放**：`script/` 解析文本脚本驱动相机路径
- **触发器**：`trigger/` 独立条件引擎，基于位置/物品/交互触发运镜
- **网络层**：`network/` 服务端控制触发和权限
- **其他**：handler / item / mixin / util

因设计存在根本性问题，后续完全重构。

## [0.0.1] - 2026-01-24

项目初创（`f48792a`），实现基础的摄像机控制。

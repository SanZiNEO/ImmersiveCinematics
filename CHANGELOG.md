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

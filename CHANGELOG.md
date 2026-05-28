# Changelog

## [0.3.1] - 2026-05-26

### Fixed
1. 编辑器新增关键帧导致关键帧数组乱序，ScriptParser 单调递增校验失败，脚本无法载入
2. 新建关键帧缺少 position/yaw/pitch/roll/fov/zoom/dof 属性值，预览时相机跳回原点
3. 所有关键帧修改路径添加排序保护
4. 编辑器新建脚本后保存时仍写回旧文件，覆盖已有脚本
5. 编辑器脚本目录与服务端加载目录不一致，保存后世界内无法生效

### Added
- `/icinematics reload` 命令（op 2 级）：同步全局脚本到世界存档并重载，使编辑内容立即生效
- `/icinematics play` 命令增加 Tab 自动补全

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

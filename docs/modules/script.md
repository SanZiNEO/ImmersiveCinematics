# script（脚本系统）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/script/`

功能树：

- **脚本解析与校验**
  - ✅ 提供 `ScriptParser.parse()` 将 JSON 字符串解析为 `CinematicScript`，使用 Gson 树 API 手动解析并带字段路径的 `ScriptParseException` 异常（`ScriptParser`）
  - ✅ 校验 meta 必填字段（id/name/author/version），id 必须匹配 `^[a-zA-Z0-9_]{1,32}$`，name≤50 字符、author≤30 字符，version 仅支持 3（`ScriptParser`）
  - ✅ 校验 timeline：`total_duration` 不允许为 0（正数=有限时长，负数=无限时长）、关键帧时间必须单调递增、CAMERA clip 至少 1 个关键帧、duration 不允许为 0（`ScriptParser`）
  - ✅ 校验贝塞尔曲线 `control_points` 必须恰好 2 个点，`curve` 非法时拒绝解析（`ScriptParser`、`BezierCurve`）
  - ✅ 校验必填字段（来自 schema 元数据），缺失时报错（`ScriptParser`、`SchemaLoader`）
  - ✅ 提供向后兼容：LETTERBOX 缺 keyframes 时从 clip 级 `aspect_ratio` 自动生成两个关键帧；EVENT 旧式 clip 级 `command` 自动迁移到 keyframe（`ScriptParser`）
  - ✅ 支持未知字段自动解析（向前兼容），触发器定义解析支持 `on_enter`/`exit_buffer`（`ScriptParser`）
  - ✅ 解析轨道级合法性：超过 1 条 CAMERA 轨道仅使用第 1 条、LETTERBOX/EVENT 建议最多 1 条、morph 相邻 clip position_mode 不一致时告警（`ScriptParser`）
- **脚本加载与触发器注册**
  - ✅ 提供单例 `ScriptManager.INSTANCE`，从世界存档目录 `immersive_cinematics/scripts` 加载全部 .json 脚本并缓存原始 JSON（`ScriptManager`）
  - ✅ 首次启动时将游戏根目录全局脚本复制到世界存档（已存在的不覆盖）（`ScriptManager`）
  - ✅ 提供 `registerAllTriggers()` 将脚本 meta 中的触发器定义批量注册进 `TriggerEngine`，动作统一为 `StartPlaybackAction`（`ScriptManager`）
  - ✅ 提供 `reload()` 清空引擎、重新加载并重建索引（`ScriptManager`）
- **Schema 驱动的字段定义**
  - ✅ `SchemaLoader` 运行时加载 `schema.json`，为每种轨道定义 clip/keyframe 字段（类型、默认值、是否必填）（`SchemaLoader`）
  - ✅ 提供字段查询 API：`getDefaultValue()`/`isRequired()`/`hasField()`/`getClipFields()`/`getKeyframeFields()`（`SchemaLoader`）
- **数据模型**
  - ✅ `CinematicScript` 为顶层容器：meta + timeline + 原始 JSON（供网络同步）（`CinematicScript`）
  - ✅ `ScriptMeta` 持有 id/name/author/version/description/dimension/triggers 及 20 个运行时行为标志（`ScriptMeta`）
  - ✅ 运行时行为标志：屏蔽键盘/鼠标、屏蔽生物 AI（已弃用）、隐藏 HUD/手臂/聊天/记分板/动作栏/标题/字幕/快捷栏/准星/Boss 条/跳过 HUD、抑制视角摆动、渲染玩家模型、游戏暂停时暂停、可打断、可跳过、结尾保持（`ScriptMeta`）
  - ✅ `Timeline` 管理总时长与并行轨道，提供按类型查询轨道的便捷方法（`Timeline`）
  - ✅ `TimelineTrack` 为同类型通用片段数组，`Clip` 为通用片段容器（start_time/duration + data map + keyframes）（`TimelineTrack`、`Clip`）
  - ✅ `Keyframe` 为通用关键帧容器（time + data map），`PositionData` 区分相对（dx/dy/dz）与绝对（x/y/z）两种坐标模式（`Keyframe`、`PositionData`）
  - ✅ `TriggerDefinition` 描述脚本内嵌触发器：类型/条件/repeatable/delay/on_enter/exit_buffer（`TriggerDefinition`）
- **轨道种类**
  - ✅ 共 6 种轨道类型：CAMERA、LETTERBOX、AUDIO、EVENT、MOD_EVENT、OVERLAY（`TrackType`）
  - ✅ 轨道数量限制：CAMERA 最多 1 条，LETTERBOX/EVENT 建议最多 1 条，AUDIO/MOD_EVENT/OVERLAY 不限（`TrackType`）
- **关键帧插值与路径**
  - ✅ `KeyframeInterpolator` 提供无状态静态插值：段定位、匀速时间进度、循环（`loop`/`loop_count` 取模，`loop_mode` 支持 `repeat` 重复与 `pingpong` 往复折返）、范围外钳制到首/末关键帧（`KeyframeInterpolator`）
  - ✅ 位置/偏航/滚转插值使用角度环绕插值，俯仰/FOV/缩放使用线性插值（`KeyframeInterpolator`）
  - ✅ 支持 clip 级 `loop` 循环与 `loop_count` 次数限制（`KeyframeInterpolator`、`Clip`）
  - ✅ 片段过渡：`TransitionType.CUT` 硬切换（staged 原子提交）、`TransitionType.MORPH` 在 transition_duration 内从上一片段末帧飞向下一片段首帧（`TransitionType`、`CameraTrackPlayer`）
  - ✅ 插值类型枚举 `InterpolationType.LINEAR` 用于 JSON 校验白名单（`InterpolationType`）
  - ✅ 三次贝塞尔路径：`BezierCurve` 携带 2 个控制点，`BezierPathStrategy` 通过 `ArcLengthLUT` 弧长参数化实现匀速曲线运动（`BezierCurve`、`BezierPathStrategy`、`ArcLengthLUT`）
  - ✅ `ArcLengthLUT` 用德卡斯特里奥自适应细分建表（平坦度容差 0.001、最大深度 8），查询时二分查找（`ArcLengthLUT`）
  - ✅ `PathStrategies` 注册表按 curve.type 名称提供策略工厂，默认 `linear`，未知类型回退线性（`PathStrategies`、`PathStrategy`）
- **播放器调度**
  - ✅ `ScriptPlayer` 驱动脚本运行时：记录起始虚拟时间、相对模式基准位置（玩家激活时位置）、持有当前脚本运行时行为（`ScriptPlayer`）
  - ✅ 启动时按轨道类型批量创建 `TrackPlayer` 实例（EVENT 轨道不在客户端处理），并预执行首帧避免闪烁；编辑器加载不同轨道布局的脚本时 `replaceScript` 按新布局重建 TrackPlayer（轨道数量/类型顺序变化时旧索引失效，重建防止 OVERLAY/AUDIO 等读错轨道）（`ScriptPlayer`、`TrackPlayer`）
  - ✅ `TrackPlayer` 工厂按类型分发：CAMERA→`CameraTrackPlayer`、LETTERBOX→`LetterboxTrackPlayer`、AUDIO→`AudioTrackPlayer`、MOD_EVENT→`ModEventTrackPlayer`、OVERLAY→`OverlayTrackPlayer`；创建时传入轨道索引，数据源按索引定位（`TrackPlayer`）
  - ✅ 帧回调驱动：每渲染帧调度所有 TrackPlayer；`holdAtEnd` 时钳制在最后一帧（0.1ms 偏移）等待退出（`ScriptPlayer`）
  - ✅ 启动时若 `block_mob_ai` 开启，清空 128 格范围内以玩家为目标的生物（`ScriptPlayer`）
  - ✅ 支持结束判定（时间耗尽/无限循环）、剩余时间查询、`hasActiveCameraTrack()` 供 Mixin 缓存（`ScriptPlayer`）
  - ✅ 支持时间对齐 `alignTime()`（编辑器预览拖动播放头）、音频暂停/恢复/重定位（`ScriptPlayer`）
- **CAMERA 轨道播放器**
  - ✅ 每渲染帧定位活跃 clip（含循环/无限片段）并写入精确相机状态，无 partialTick 插值（`CameraTrackPlayer`）
  - ✅ morph 过渡窗口内混合上一片段末帧与下一片段首帧（位置线性混合、角度最短路径混合）（`CameraTrackPlayer`）
  - ✅ 关键帧级 `follow`（位置跟随实体，position 即相对实体偏移）与 `look_at`（注视实体/坐标/结构）：两端关键帧各自求值为世界坐标再插值 → follow↔普通、换目标、look_at 开关全部平滑过渡；look_at 目标点插值模型（none 端=该关键帧 yaw/pitch 方向远点）（`CameraTrackPlayer`）
  - ✅ 实体选择器子集：`@p`/`@s`/`@e`/`@e[type=…,name=…]`/`uuid:…`，就近优先 + 1 秒缓存（`CameraTrackPlayer`）
  - ✅ 结构目标：服务端 `/icinematics play` 推送前把 `look_at_target_structure` / `position.relative_origin`（结构 id）替换为结构 **bounding box 中心**坐标（`StructureLocator`：findNearestMapStructure 锚点 → STRUCTURE_STARTS → getBoundingBox().getCenter()，就近搜索 100 区块）；编辑器预览（单人）客户端直连集成服务端兜底（`StructureLocator`、`CinematicCommand`、`CameraTrackPlayer`）
  - ✅ 支持 `cam_breath_*` 呼吸扰动（clip 级）：按时间+种子生成确定性随机微晃叠加到 yaw/pitch/roll（`CameraTrackPlayer`）
  - ✅ 相对/绝对坐标模式为关键帧级（position 对象自描述：有 dx=相对、有 x=绝对），统一世界坐标空间插值；相对基准可扩展：`relative_origin` = 玩家激活位置（默认）/ `"coordinate"` 固定坐标 / 结构 id 结构中心（`CameraTrackPlayer`、`PositionData`）
- **AUDIO 轨道播放器**
  - ✅ 通过 LWJGL OpenAL 多音源播放：每 clip 一个 `CinematicAudioInstance`，clip 切换时淡出并清理旧实例（`AudioTrackPlayer`）
  - ✅ 支持 OGG（stb_vorbis 文件/资源包解码）与 WAV（javax.sound，8/16 位）两种格式（`CinematicAudioInstance`）
  - ✅ 关键帧插值驱动音量与空间位置（volume/x/y/z），支持淡入淡出、循环、音高（`AudioTrackPlayer`、`CinematicAudioInstance`）
  - ✅ 支持空间衰减模式 none/linear/inverse（默认距离 16 格），最终音量乘以 MC 音乐音量滑块（`CinematicAudioInstance`、`AudioTrackPlayer`）
  - ✅ 播放期间每帧压制 MC 背景音乐（`SoundSource.MUSIC`），防止原版音乐串场（`AudioTrackPlayer`）
  - ✅ 支持暂停/恢复/按时间重定位（`syncToTime` 偏差超 0.5s 时重播）（`CinematicAudioInstance`）
- **LETTERBOX 轨道播放器**
  - ✅ 关键帧插值驱动画幅比黑边（`aspect_ratio`），无活跃 clip 时归零，停止时重置（`LetterboxTrackPlayer`）
- **OVERLAY 轨道播放器**
  - ✅ 按 clip 的 `layer_type` 创建对应覆盖层（fade/image/subtitle/pip）并注册到 `OverlayManager`，支持 z_index 分层（`OverlayTrackPlayer`）
  - ✅ 首帧套用初始值，随后按关键帧插值驱动：`x/y`（屏幕百分比 0~1，中心锚点）、`scale_x/scale_y`（原图百分比乘数，image / 固定字号后缩放，subtitle）、`font_scale`（字号倍数，subtitle）、`opacity`（透明度，淡入淡出完全由关键帧表达）；`interpolation: "smooth"` 走 Centripetal Catmull-Rom 样条（范围外钳制到边界关键帧）（`OverlayTrackPlayer`）
  - ✅ 支持多条同类型 OVERLAY 轨道同时渲染：TrackPlayer 数据源按轨道索引定位（`clipsForTrack(trackIndex)`），轨道 JSON 以 `id` 区分管理（`ScriptPlayer`、`TrackPlayer`）
  - ✅ clip 切换或停止时移除并清理覆盖层（`OverlayTrackPlayer`）
- **EVENT 轨道（服务端执行）**
  - ✅ EVENT 轨道不在客户端处理，由服务端 `ScriptEventManager` 按关键帧时间多点触发，支持 `&&` 命令链、权限等级 4 执行（`ScriptEventManager`）
- **MOD_EVENT 轨道播放器**
  - ⏳ 仅为占位空壳：`isActiveAt()` 恒返回 false、`onRenderFrame()`/`onStop()` 空实现，Phase 1 不实现（`ModEventTrackPlayer`）

## 已知问题

- `PathStrategies` 注册表实际只注册了 `linear`，javadoc 声称的 `bezier` 注册缺失；动态查找 `curve.type=bezier` 会告警并回退线性，实际播放中由 `CameraTrackPlayer` 显式持有 `BezierPathStrategy` 实例才生效（来源：`PathStrategies`）
- 多人服务器下编辑器预览（本地播放）无法解析 `look_at_target_structure`（客户端无服务端访问）；服务端 `/icinematics play` 推送前会替换为坐标，不受影响（来源：`CameraTrackPlayer`）
- AUDIO clip 的 `fade_in + fade_out` 超过 clip 时长时整段跳过不播放（来源：`AudioTrackPlayer`）
- 音频文件名含非 ASCII 字符（中文等）时在 Windows 下可能无法解码（stb_vorbis 内部 C fopen 使用 ANSI 代码页），仅输出警告（来源：`CinematicAudioInstance`、CHANGELOG 0.3.4）

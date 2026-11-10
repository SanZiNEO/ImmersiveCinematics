> **过期文档（OUTDATED）——不得作为当前设计或实施依据**
>
> 本文未随最新设计更新，仅保留作历史参考。AI 与自动化工具不得据此推断当前格式、功能状态、接口或实施要求，也不得直接照搬其中示例用于新实现。
>
> 当前规划请先查阅 [0.3.6 计划状态索引](../../plans/0.3.6/README.md) 与 [I 轮实施基准](../../plans/0.3.6/I-script-core/README.md)，再按状态查阅对应未完成轮次。
>
> 计划目标不代表已经实现；核实当前代码行为时须结合源码，不得将旧文或未实现计划当作现状。

# editor（编辑器：WebUI 服务端 + 独立前端）

对应路径：
- WebUI 服务端：`common/src/main/java/com/immersivecinematics/immersive_cinematics/webui/`
- WebUI 前端：`editor/`（Electron + Vue 3 独立程序，不打包进 mod）

> **游戏内 Java 编辑器已退役（0.3.6）**：`common/.../editor/` 整包（62 文件 / 10,176 行）、`client/EditorBridgeImpl`、F6 键绑定与 `EDITOR_ENABLED` / `Config.editorEnabled` 开关全部删除，仅编辑器使用的 lang 键一并清理；删除清单见[实现结果](../../plans/0.3.6/Z-editor/implementation.md) §三。
> 0.3.5 的预设系统（`editor/preset/`，含环绕轨道预设）随包删除——模板体系将来从零新建，见 [templates](../../plans/0.3.6/Z-editor/templates.md)。
> 脚本格式与字段 schema 不受影响：权威仍在 `script/`（见 [script.md](./script.md)）。

> **本次同步（源码 `4b49c68`）**：只按当前源码更新捕获/预览条目（`CaptureHub`、`PreviewStreamer`、固定 1080p60 规格、18 字节帧头）及相关消息与源码角色；**未做游戏内验收**，其余历史条目和前端实现未重新核实。捕获与媒体细节见 [media.md](./media.md)。

功能树：

- **WebUI 编辑器（唯一编辑器）**
  - ✅ `WebEditorServer` 本地 WebSocket 服务端（只绑定 127.0.0.1、固定端口 8765、自实现无第三方库）：模组作服务端、编辑器作客户端，支持双向文本 JSON 与二进制帧流；`/ws` 升级前校验 Origin 白名单与每次启动随机 token（默认 token 文件 `<user.home>/.immersivecinematics/webui-token`，可用 `IC_WEBUI_TOKEN_FILE` 覆盖）（`WebEditorServer`）
  - ✅ `WebSocketSession` 每连接一个会话（服务端可同时登记多个）：读取客户端帧、发送文本/二进制帧、断开时自动移除；文本消息交回 Minecraft 主线程执行，二进制帧流按会话有界队列发送（`WebSocketSession`、`OutboundQueue`）
  - ✅ `WebEditorApi` 消息路由，协议 `{ "type", "data", "id" }`；命令：`hello`、`script.list/load/save/delete/new/validate/graph`、`registry.query/get`、`schema.get`、`resource.list`、`editor.seek/play/pause/stop/setCamera/pushScript/previewConfig/enter_flight_mode/exit_flight_mode/cancel_flight_mode`（`WebEditorApi`）
  - ✅ 服务端消息：`hello_ack`；`script.list.result` / `script.loaded` / `script.saved` / `script.deleted` / `script.new.result` / `script.validate.result` / `script.graph.result`；`registry.query.result` / `registry.get.result`；`schema.data`；`resource.list.result`；`editor.previewConfig.result`；以及 `playback.state`（预览屏 50ms / 约 20Hz）、`preview.stats`（1s）、`flight.state`（飞控中 100ms）、`flight.exit`、`error`（`WebEditorApi`、`WebPreviewScreen`、`PreviewStreamer`）
  - ✅ `ScriptFileService` 脚本文件读写/新建/删除：根目录 `immersive_cinematics/scripts/`，递归列 `.json`，写盘前 `normalize()` + `startsWith(base)` 越界校验；`newScriptJson()` 走 `script/ScriptTemplate` 的默认空脚本骨架（`ScriptFileService`、`ScriptTemplate`）
  - ✅ `WebRegistryService` 注册表/自动补全数据源：即时查询 MC 注册表并过滤，支持 item / block / entity / sound / target / biome / dimension / structure / advancement，Forge/Fabric 通用（`WebRegistryService`）
  - ✅ `ResourceFileService` 资源列举（只读）：`resource.list` 支持 `kind = lang / image / audio / all` + 可选 `dir`（资源根下的子目录）。`lang` 返回 `languages` + `entries`（key 并集 + 各语言译文，解析口径与运行时同一套 `LangResources.readDictionary`）；其余 kind 返回相对 `resource/` 的文件路径。所有相对路径经 `normalize()` + `startsWith(root)` 校验，拒绝绝对路径 / 盘符 / `..` 逃逸（与 `ScriptFileService` 同款防护）（`ResourceFileService`）
  - ✅ 前端资源字段控件（0.3.6 文本资源 i18n）：字幕 `text` / 脚本 `description` → `@lang:<key>` 引用选择（候选 = key 并集，选项显示当前编辑器语言译文），图片 `path` → `resource/` 文件选择；当前值缺 key / 缺文件时字段下方黄色提示（只警告不阻塞）（`editor/src/components/fields/ResourceStringField.vue`、`resourceHint.ts`、`FieldRenderer.vue`、`store.ts` 的 `resourceList`）
  - ✅ `WebPreviewScreen`（F9）预览屏：`init()` 启动本地服务并把 `PreviewStreamer` 注册为 `CaptureHub` 消费者；渲染帧驱动暂停判定、1s 预览统计、50ms `playback.state` 推送与飞控 HUD；关闭时依次 `PreviewStreamer.close()` + `WebEditorServer.stop()`。时间轴等编辑器按键由前端处理，游戏端只保留 F7/F9/ESC 与飞控按键（`WebPreviewScreen`）
  - ✅ 共享捕获 `CaptureHub`：主 RenderTarget 以 GL_LINEAR 缩放到固定规格 FBO（alpha 强制 1），三缓冲 PBO 异步回读、稳态交付两帧前画面；读回缓冲池 6 个，池空则丢帧计数，不阻塞渲染线程（`CaptureHub`、`CapturedFrame`，挂点 `GameRendererMixin`）
  - ✅ 预览流 `PreviewStreamer` + `PreviewEncoder` + `H264AuSplitter` + `PreviewProtocol`：捕获规格与发送目标固定 1920×1080@60（不承诺实测稳定 60fps）；每会话优先 ffmpeg libx264（baseline / zerolatency / 2 秒关键帧）编码为 Annex B AU，ffmpeg 缺失或编码失败时本会话回退原始 RGBA；二进制帧为 18 字节大端头（type / flags / frameId / ptsMicros / width / height）+ 负载，出站队列容量 16、满时先丢最旧帧（`PreviewStreamer`、`PreviewEncoder`、`H264AuSplitter`、`PreviewProtocol`、`OutboundQueue`）
  - ✅ 预览统计与多会话：`preview.stats` 每 1s 推送 `mode/width/height/fps/encoder/sent/dropped/inputDropped/encoderDropped`；每会话独立编码器、回退标志、PTS 基准与发送队列，单会话失败/关闭不影响其他会话；`WebPreviewScreen` 关闭时停止流与服务（`PreviewStreamer`、`WebPreviewScreen`）
  - ✅ 飞控：`FlightModeManager`（`control/`）持有会话，`FlightController` 接管相机；WebUI 只做进入/退出/取消与状态显示，退出时返回最终相机参数供前端写回片段（`FlightModeManager`、`WebPreviewScreen`）
  - ✅ 前端 `editor/`：Electron + Vue 3 桌面程序，多轨道时间轴、属性/关键帧/触发器面板、schema 动态表单、预览、飞控叠层、撤销重做、A-B 循环、marker、快捷键、中英 i18n（`editor/src`）
  - ✅ 速度曲线（缓动）= 编辑器烘焙（0.3.6 方案 E，运行时纯线性不求值曲线）：关键帧面板选预设曲线（linear / ease_in / ease_out / ease_in_out，作用于「本帧 → 下一帧」区段），保存 / 预览推送前在深拷贝上按曲线等距采样补出显式关键帧（含首尾 `clamp(round(duration×4)+2, 8, 20)` 帧、时长守恒），曲线标记 `easing` 是编辑器私有字段、不落盘；LETTERBOX（运行时自带 smoothstep）/ EVENT / MOD_EVENT（离散事件）/ 贝塞尔路径片段不参与（`editor/src/operations.ts` 的 `easingProgress` / `bakeSampleCount` / `easingSupport` / `bakeClipEasing` / `bakeDocEasing`、`KeyframePanel.vue`、`store.ts` 的 `saveScript` / `pushScript`）

- **共享层（不随编辑器存亡）**
  - ✅ `script/schema/`：`TrackSchemas` / `MetaSchemas` / `TriggerSchemas` / `FieldDef` / `SchemaRegistry` / `SchemaExporter` 是脚本字段元数据的 Java 侧唯一权威，经 `schema.get` 提供给前端渲染表单（`SchemaExporter`）
  - ✅ `script/ScriptTemplate`：新建脚本的默认骨架（meta 默认值取自 `SchemaLoader.getMetaFields()`、轨道跟随 `TrackType` 枚举、每轨唯一 id + 空 clips），与手写脚本同构、可被 `ScriptParser` / `ScriptValidator` 正常解析（`ScriptTemplate`）

## 已知问题

- 运行时媒体能力与分类资源列表尚未完全对齐：`ResourceFileService` 的 `kind=image` 仍只列 PNG/GIF，`kind=audio` 仍只列 OGG/WAV，因此视频与扩展音频不会出现在这两类响应中。`kind=all` 可列全部文件，但本轮未核实前端是否提供该入口；不能把后端可解码等同于前端选择器已支持全部格式。

> 历史项（已修复）：`WebPreviewScreen.enterFlightMode` 曾忽略传入的 `x/y/z`。现前端显式提供的 `x/y/z/yaw/pitch/roll/fov/zoom` 覆盖对应默认值，未提供的字段才回落到当前相机状态；`absolute` 只作飞控会话的坐标模式标记转发（`WebPreviewScreen.enterFlightMode`、`WebEditorApi.handleEnterFlightMode`）。

# editor（编辑器：WebUI 服务端 + 独立前端）

对应路径：
- WebUI 服务端：`common/src/main/java/com/immersivecinematics/immersive_cinematics/webui/`
- WebUI 前端：`editor/`（Electron + Vue 3 独立程序，不打包进 mod）

> **游戏内 Java 编辑器已退役（0.3.6）**：`common/.../editor/` 整包（62 文件 / 10,176 行）、`client/EditorBridgeImpl`、F6 键绑定与 `EDITOR_ENABLED` / `Config.editorEnabled` 开关全部删除，仅编辑器使用的 lang 键一并清理；删除清单见[编辑器 WebUI 迁移](../../plans/0.3.6/editor-webui-migration.md) §4。
> 0.3.5 的预设系统（`editor/preset/`，含环绕轨道预设）随包删除——模板体系将来从零新建，见 [templates](../../plans/0.3.6/templates.md)。
> 脚本格式与字段 schema 不受影响：权威仍在 `script/`（见 [script.md](./script.md)）。

功能树：

- **WebUI 编辑器（唯一编辑器）**
  - ✅ `WebEditorServer` 本地 WebSocket 服务端（只绑定 127.0.0.1、固定端口 8765、自实现无第三方库）：模组作服务端、编辑器作客户端，支持双向文本 JSON 与二进制帧流（`WebEditorServer`）
  - ✅ `WebSocketSession` 单客户端会话：读取客户端帧、发送文本/二进制帧、断开时自动移除；`CameraManager` 与播放器操作统一切回 Minecraft 主线程执行（`WebSocketSession`）
  - ✅ `WebEditorApi` 消息路由，协议 `{ "type", "data", "id" }`；命令：`hello`、`script.list/load/save/delete/new/validate`、`registry.query/get`、`schema.get`、`resource.list`、`editor.seek/play/pause/stop/setCamera/pushScript/enter_flight_mode/exit_flight_mode/cancel_flight_mode`（`WebEditorApi`）
  - ✅ 服务端主动推送：`hello_ack`、`playback.state`（预览屏 50ms / 约 20Hz）、`flight.state`（飞控中 100ms）、`flight.exit`、`error`（`WebEditorApi`、`WebPreviewScreen`）
  - ✅ `ScriptFileService` 脚本文件读写/新建/删除：根目录 `immersive_cinematics/scripts/`，递归列 `.json`，写盘前 `normalize()` + `startsWith(base)` 越界校验；`newScriptJson()` 走 `script/ScriptTemplate` 的默认空脚本骨架（`ScriptFileService`、`ScriptTemplate`）
  - ✅ `WebRegistryService` 注册表/自动补全数据源：即时查询 MC 注册表并过滤，支持 item / block / entity / sound / target / biome / dimension / structure / advancement，Forge/Fabric 通用（`WebRegistryService`）
  - ✅ `ResourceFileService` 资源列举（只读）：`resource.list` 支持 `kind = lang / image / audio / all` + 可选 `dir`（资源根下的子目录）。`lang` 返回 `languages` + `entries`（key 并集 + 各语言译文，解析口径与运行时同一套 `LangResources.readDictionary`）；其余 kind 返回相对 `resource/` 的文件路径。所有相对路径经 `normalize()` + `startsWith(root)` 校验，拒绝绝对路径 / 盘符 / `..` 逃逸（与 `ScriptFileService` 同款防护）（`ResourceFileService`）
  - ✅ 前端资源字段控件（0.3.6 文本资源 i18n）：字幕 `text` / 脚本 `description` → `@lang:<key>` 引用选择（候选 = key 并集，选项显示当前编辑器语言译文），图片 `path` → `resource/` 文件选择；当前值缺 key / 缺文件时字段下方黄色提示（只警告不阻塞）（`editor/src/components/fields/ResourceStringField.vue`、`resourceHint.ts`、`FieldRenderer.vue`、`store.ts` 的 `resourceList`）
  - ✅ `WebPreviewScreen`（F9）预览屏：启动本地服务、播放控制（play/pause/seek/stop/pushScript）、飞控模式入口与飞控 HUD；编辑器按键绑定全部由前端处理，游戏端不转发（`WebPreviewScreen`）
  - ✅ `WebFrameCapture` + `WebFrameStreamer` 帧推流：主 RenderTarget 缩放到固定 16:9 小 FBO 后 `glReadPixels` 读回（720p raw RGBA），读帧在渲染线程、翻转/发送在独立 worker 线程，约 60fps 节流、落后时只保留最新帧；帧格式 `[type][frameId][w][h][RGBA]`（`WebFrameCapture`、`WebFrameStreamer`）
  - ✅ 飞控：`FlightModeManager`（`control/`）持有会话，`FlightController` 接管相机；WebUI 只做进入/退出/取消与状态显示，退出时返回最终相机参数供前端写回片段（`FlightModeManager`、`WebPreviewScreen`）
  - ✅ 前端 `editor/`：Electron + Vue 3 桌面程序，多轨道时间轴、属性/关键帧/触发器面板、schema 动态表单、预览、飞控叠层、撤销重做、A-B 循环、marker、快捷键、中英 i18n（`editor/src`）
  - ✅ 速度曲线（缓动）= 编辑器烘焙（0.3.6 方案 E，运行时纯线性不求值曲线）：关键帧面板选预设曲线（linear / ease_in / ease_out / ease_in_out，作用于「本帧 → 下一帧」区段），保存 / 预览推送前在深拷贝上按曲线等距采样补出显式关键帧（含首尾 `clamp(round(duration×4)+2, 8, 20)` 帧、时长守恒），曲线标记 `easing` 是编辑器私有字段、不落盘；LETTERBOX（运行时自带 smoothstep）/ EVENT / MOD_EVENT（离散事件）/ 贝塞尔路径片段不参与（`editor/src/operations.ts` 的 `easingProgress` / `bakeSampleCount` / `easingSupport` / `bakeClipEasing` / `bakeDocEasing`、`KeyframePanel.vue`、`store.ts` 的 `saveScript` / `pushScript`）

- **共享层（不随编辑器存亡）**
  - ✅ `script/schema/`：`TrackSchemas` / `MetaSchemas` / `TriggerSchemas` / `FieldDef` / `SchemaRegistry` / `SchemaExporter` 是脚本字段元数据的 Java 侧唯一权威，经 `schema.get` 提供给前端渲染表单（`SchemaExporter`）
  - ✅ `script/ScriptTemplate`：新建脚本的默认骨架（meta 默认值取自 `SchemaLoader.getMetaFields()`、轨道跟随 `TrackType` 枚举、每轨唯一 id + 空 clips），与手写脚本同构、可被 `ScriptParser` / `ScriptValidator` 正常解析（`ScriptTemplate`）

## 已知问题

- 无。

> 历史项（已修复）：`WebPreviewScreen.enterFlightMode` 曾忽略传入的 `x/y/z`。现前端显式提供的 `x/y/z/yaw/pitch/roll/fov/zoom` 覆盖对应默认值，未提供的字段才回落到当前相机状态；`absolute` 只作飞控会话的坐标模式标记转发（`WebPreviewScreen.enterFlightMode`、`WebEditorApi.handleEnterFlightMode`）。

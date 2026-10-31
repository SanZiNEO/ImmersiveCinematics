# 实现结果

> 状态：独立编辑器、服务端、协议、握手校验、预览推流、飞控绑定、架构图步骤 1–4 已落地；游戏内编辑器已删除；选中片段全屏 lane、可选编码与远程面板未做
> 范围：实现状态、与设计的偏离、游戏内编辑器退役、参考编辑器评估、验证与事实核查
> 关联：[`server-protocol.md`](server-protocol.md)、[`security.md`](security.md)、[`flight-mode.md`](flight-mode.md)、[`script-graph.md`](script-graph.md)

## 一、状态

独立编辑器、服务端、协议、握手校验、预览推流、飞控绑定、架构图步骤 1–4 已落地，游戏内编辑器已删除。

剩余：选中片段全屏 lane 渲染；H.264 / WebRTC 与飞控远程面板；调色、组合器、转场、模板面板与贝塞尔控制点可视化（见 [`README.md`](README.md) §六）。

## 二、与设计的偏离

| 设计目标 | 实现 | 说明 |
|---|---|---|
| 飞控按选中片段开单独镜头，退出写回该片段 | 进入时按片段在播放头处的姿态绑定，退出写回选中关键帧；全屏 lane 渲染未实现（2026-10-07） | 全屏渲染依赖多相机 lane；写回先按关键帧 |
| 预览与游戏播放通道隔离（原列为并行播放步骤 5） | 预览为独立实例，已隔离（2026-10-07） | — |
| 协议只列脚本、注册表、schema 与编辑器命令 | 另加资源列举与保存后通知（2026-10-07） | 脚本 i18n 与服务器同步需要 |
| WebSocket 服务用 NanoHTTPD / Java-WebSocket | 自实现（2026-10-07） | 需求只有文本帧、二进制帧与单机连接 |
| 预览为 JPEG / H.264 / WebRTC | raw RGBA 720p + 约 60fps 节流 | 先保证可用；编码未做 |

## 三、游戏内编辑器退役

**决策**：不留游戏内编辑器，后续只维护外部编辑器。2026-10-07 退役执行完毕，`editor/` 包整体消失，外部引用清零（`sh gradlew compileJava` 通过）。

### 3.1 删除清单

| 删除项 | 说明 |
|---|---|
| `common/.../editor/` 整包（62 文件 / 10,176 行） | 含 `preset/`（0.3.5 预设系统）、`debug/`（EditorLogger、RawInputLogger）、`panel/`、`widget/`、`area/`、`trigger/`、`fields/` 全部子包 |
| `common/.../editor/EditorBridge.java`、`common/.../client/EditorBridgeImpl.java`（34 行） | 桥接接口与实现一并删除，无替代 |
| `CinematicKeyBindings`：F6 `EDITOR_KEY` + 编辑器专用键 17 个 | 播放/播放头/标记/循环/裁剪/微调/删除等；**F7 飞控、F9 WebUI 保留** |
| `ImmersiveCinematics.EDITOR_ENABLED`、`Config.editorEnabled` | 含 Forge/Fabric 配置键与 `ConfigScreen` 开关项，整条删除（不留空壳） |
| lang 键 324 条 | `editor.*`（保留 `editor.flight.key.*` 11 条）、`key.immersive_cinematics.editor*`（保留飞控/WebUI 10 条）、`config.immersive_cinematics.editorEnabled*` 2 条 |
| `client/EditorBridgeImpl` 相关的 F6 tick 分支、`ClientEventHandler` 编辑器键注册 | 见上 |

### 3.2 替代与外部引用面

`script/ScriptTemplate`（默认空脚本骨架，meta 默认值取自 `SchemaLoader.getMetaFields()`、轨道跟随 `TrackType` 枚举）取代 `EditorDocument.reset()`，供 WebUI `script.new` 使用；`script/schema/` 未动。

删包时 `editor/` 包之外只有 3 处引用它（2026-10-07 核查，已处理）：

- `client/EditorBridgeImpl`（实现 `editor.EditorBridge`）——随包删除；
- `control/CinematicKeyBindings`（F6 入口引用 `editor.EditorScreen`）——F6 键与桥接整体移除；
- `webui/ScriptFileService.newScriptJson()`（`new EditorDocument().toJson()`）——改走 `script/ScriptTemplate.newScriptJson()`（等价空脚本骨架，默认值仍来自 schema 与 `TrackType`）。

另清理 `ImmersiveCinematics.EDITOR_ENABLED` / `Config.editorEnabled`（Forge/Fabric/ConfigScreen 一并删除）与仅编辑器使用的 lang 键，全仓已无 `editor` 包类名引用。

### 3.3 占比

`git ls-files` + `wc -l` 统计；2026-10-07 在 `cfd3b7d` 重算 common 全部 Java 234 文件 / 30,634 行。删除前 HEAD 实测 243 文件 / 31,898 行，删除后 **181 文件 / 21,677 行**。

| 部分 | 行数 | 占比 |
|---|---|---|
| 游戏内编辑器（`editor/` 包 62 文件 = 10,176；另加 `client/EditorBridgeImpl` 34 行共 10,210）**已删除** | **10,176** | **33%** |
| 外部编辑器（`editor/src` 全量文件；其中 TS+Vue 仅 5,643，另含 26 个 SVG + 1 个 PNG） | 12,799 | — |
| WebUI 服务端（`webui/`，10 文件） | 2,144 | 7.0% |
| 共享 schema（`script/schema/`，7 文件） | 654 | 2% |

2026-10-09 回写：`webui/` 一行按 10 文件口径重算（新增 `ScriptGraphService` / `ResourceFileService`）；其余各行数字仍为 2026-10-07 口径。

### 3.4 理由

- **占 mod Java 的三分之一**，是单体最大的包（比相机 1,036、脚本 7,929（含 `script/schema/` 654）、触发器 4,263 都大）。
- **跨版本**：它编译在 mod 内，每个 MC 版本都要重新适配；外部编辑器是 web 应用，**一份编辑器对接所有版本**。
- **历史遗留**：自带 `Files` 直读写 IO、`PreviewCapture`、`EditorOutput` 各一套；代码里留有 `[KILO-DEBUG]` 打印与专用 `EditorLogger`。
- **独有能力已被覆盖**：世界内取景 = WebUI 的飞行直控（`enter_flight_mode`）+ 帧推流预览。
- **UI 重复劳动**：字段控件 / 面板 / 撤销 / 选择，外部编辑器里有另一份。

保留的唯一好处：**不切窗口**（游戏内直接改）。判定：优势很薄。

### 3.5 脚本数据模型保留

删除 `editor/` 包时，脚本数据模型不随之删除：

- 脚本格式的权威在 mod 的解析与播放模块（`script/`）：只有它能读脚本。`script/schema/`（`TrackSchemas` / `FieldDef` / `SchemaExporter` / `SchemaRegistry`）是其中的字段元数据，独立于任何编辑器存在。
- 编辑器只是这份模型的消费者：外部编辑器通过 `schema.get` 取。
- 删包后的归属：schema 留在 `script/schema/`，升级方向见[脚本模型](../G-script-model/script-model.md)（参数声明、keyframable、关键帧到分量）。

### 3.6 迁移路径

1. **冻结**：不再给游戏内编辑器加功能。✅
2. **对齐能力**：确认外部编辑器覆盖其全部能力（缺口清单见 `feedback-0.3.5/02`）。✅
3. **切换默认**：F9 为主；F6 标记为旧或移除。✅（F6 已移除）
4. **删除 `editor/` 包**（62 文件 / 10,176 行）——保留 `script/schema/` 与脚本格式。✅ 2026-10-07 落地。

> 原始迁移方案（研究参考、阶段划分、协议）与 0.3.5 的独立 Editor 计划见 git 历史与 `plans/complete/0.3.5/webui-editor-standalone-plan.md`、`webui-logic-completion.md`。

## 四、参考编辑器评估

### 4.1 许可

`example/editor/` 下有两份开源剪辑软件：

| 项目 | 许可 | 结论 |
|---|---|---|
| Olive（非线性剪辑，C++/Qt） | **GPL-3.0** | 代码不可取 |
| lossless-cut（切割工具，Electron） | **GPL-2.0-only** | 代码不可取 |

本项目是 **MIT**：搬 GPL 代码会让整个 mod 被迫改许可，因此“直接用它的壳子”这条路不通。

### 4.2 MIT 剪辑软件检索（2026-10-06；网络数据，未复检）

GitHub 上存在 MIT 许可的剪辑器：

| 项目 | ★ | 许可 | 形态 | 说明 |
|---|---|---|---|---|
| `mifi/editly` | 5,516 | MIT | CLI / API（TS） | 声明式命令行剪辑，非 GUI |
| `Augani/openreel-video` | 5,280 | MIT | 浏览器（TS） | 开源 CapCut 替代 |
| `AIEraDev/Clypra` | 3,305 | MIT | 桌面（Rust + Tauri + React） | GPU 渲染、帧精确时间轴 |
| `walterlow/freecut` | 2,231 | MIT | 浏览器（TS） | 多轨 + 关键帧动画 + 导出 |
| `tharunbirla/LibreCuts` | 917 | MIT | Android（Kotlin） | 手机端 |
| `MartinDelophy/ai-video-editor` | 890 | MIT | 浏览器 / PWA（JS） | local-first |
| `mohyware/clip-js` | 769 | MIT（已核对 LICENSE 原文） | 浏览器（TS） | Next.js + Remotion + ffmpeg.wasm |

**结论**：这些都是**视频剪辑器**（素材 = 媒体文件，核心 = 切片段 / 排顺序），与本文定位的**脚本生成器**不同构——**整体结构不采用**。

参考的是它们的**外形**：这类工具“该有的功能长什么样”（时间轴怎么摆、关键帧怎么编辑、属性面板怎么组织），再**翻译成我们的**（脚本生成器该有的样子）。**不是抄功能，也不是复制实现**。

### 4.3 脚手架

编辑器要的是**一副好用的脚手架 + 改功能**，不是一套完整剪辑器：

- **骨架**（窗口 / 状态 / 撤销 / i18n / 打包）要省事——已有：Vue 3 + Vite + Electron，MIT，已打包 0.1.0。
- **业务层要薄**：字段定义在 mod 侧的 `script/`（解析与播放模块，唯一权威），编辑器通过 `schema.get` 把它渲染成表单——**加一个脚本字段 = 改 schema + 加一个控件**。
- 若要内部整理骨架约定，唯一有意义的参照是 **`electron-vite`**（MIT，main / preload / renderer 三层）——这是整理，不是换项目。

### 4.4 从 Olive 采纳的设计点

- 变换按序列分辨率归一化到 [-1,1]（`transformdistortnode.cpp:291-297`：`scale(2.0/sequence_res.x(), 2.0/sequence_res.y(), 1.0)` 再 `texture_res*0.5`）。
- Auto-Scale 下拉 `{None, Fit, Fill, Stretch}`（`:80`）。
- 关键帧贝塞尔手柄与三次/二次插值（`keyframe.h:104-111`、`node.cpp:487-513`）。
- 向量按分量分轨（`NodeKeyframeTrackReference(NodeInput(this, kPositionInput), 0/1)`）。
- 裁切四边 0~1 归一化（`cropdistortnode.cpp:154-157`，四个 side 输入 `min=0/max=1/Percentage`，`:109-112` 按分辨率换算）。

见[可变画面](../D-transition/variable-frame.md)、[脚本模型](../G-script-model/script-model.md)。

## 五、已知缺陷（2026-10-06 代码复查）

- **飞控入口忽略传入坐标**：`WebPreviewScreen.enterFlightMode` 忽略传入的 `x/y/z`。——**已修（2026-10-07，D5）**：接收端逐字段解析并按字段回落当前相机状态；前端自该批次起发送选中片段在播放头处的参数（见 [`flight-mode.md`](flight-mode.md) §二）。

## 六、验证记录

- 2026-10-07：`sh gradlew compileJava` 通过；`cd editor && npm run build` 通过。
- 握手：冒烟脚本对真实 `WebEditorServer` 打 11 组握手，坏 Origin / 缺 token / 空 token / 错 token / 无 Origin 无 token 全部 403；`file://` + 正确 token、`http://localhost:5173` + 正确 token、无 Origin + 正确 token 全部 101；`stop()` 后 token 文件删除。真实 Electron 客户端端到端：token 在位时 `editor client connected`；token 移走后 10 次重连全部 `rejected ... invalid or missing token`。
- 游戏内实际连接未验证（本机无 MC 运行环境）。

## 七、事实核查（2026-10-07）

> 快照说明：本节是删包前（`cfd3b7d`）的核查结果；`editor/` 包随后已删除，本节保留当时的核查口径与修正依据。2026-10-09 回写：`webui/` 现为 10 文件（新增 `ScriptGraphService` / `ResourceFileService`），协议新增 `script.graph` / `resource.list`。

核查依据：本仓工作树（文档最后提交 `cfd3b7d` = 2026-10-06T21:57:01+08:00，其后无代码改动）、`common/src/main/java/com/immersivecinematics/immersive_cinematics/{webui,editor,script,control,client}/`、`editor/`（前端）、`example/editor/olive/app/`、`example/editor/lossless-cut/`。
行数口径：`git ls-files <pathspec> | xargs cat | wc -l`。

### ① 核实为真

| # | 断言 | 证据 |
|---|---|---|
| 1 | `editor/` 包 10,164 行 | 62 文件；顶层 12 个类 + `area`(6) / `debug`(2) / `fields`(2) / `panel`(10) / `preset`(4) / `trigger`(10) / `widget`(16) |
| 2 | `script/` 7,929 行 | 42 文件（含 `script/schema/` 的 7 文件 654 行） |
| 3 | `camera/` 1,036 行 / `webui/` 1,394 行 / `script/schema/` 654 行 | `webui/` 8 文件：`WebEditorServer` 213 / `WebSocketSession` 150 / `WebEditorApi` 279 / `ScriptFileService` 71 / `WebRegistryService` 120 / `WebFrameCapture` 148 / `WebFrameStreamer` 108 / `WebPreviewScreen` 305（2026-10-09 回写：现共 10 文件——新增 `ScriptGraphService` 348 / `ResourceFileService` 195） |
| 4 | common 全部 Java 30,634 行 | 234 文件 |
| 5 | 占比 33% / 4.5% / 2% | 10,164÷30,634 = 33.2%；1,394÷30,634 = 4.6%；654÷30,634 = 2.1% |
| 6 | `WebEditorServer` 仅绑 127.0.0.1、端口 8765、自实现无第三方库 | `WebEditorServer.java:63` `new ServerSocket(8765, 4, InetAddress.getByName("127.0.0.1"))`；仅用 JDK `ServerSocket` + `MessageDigest`/`WS_MAGIC` 自实现握手 |
| 7 | `WebFrameCapture` + `WebFrameStreamer`：主画面 → 小 FBO → glReadPixels；720p；worker 线程；约 60fps 节流；跟不上丢旧帧 | `WebFrameCapture.java:22-23` `TARGET_W/H = 1280/720`；`WebFrameStreamer.java` `SEND_INTERVAL_MS = 16`、单线程 `SENDER`、`AtomicReference<byte[]> PENDING` 只保留最新帧 |
| 8 | 二进制帧 `[1B type=0x01][2B frameId][2B width][2B height][RGBA]` | `WebFrameStreamer.sendRaw()`：`headerSize = 7`、`FRAME_TYPE_RAW_RGBA = 0x01`、`ByteBuffer` 默认大端；发送前按行翻转 |
| 9 | 消息清单 | `WebEditorApi.handle()` 的 switch 分支逐一对应，无遗漏、无多余（2026-10-09 回写：另含 `script.graph` / `resource.list`） |
| 10 | 文本帧信封 `{type,data,id}`；请求带回执 | `WebEditorApi.handle()` 解析 + `wrap(type,data,id)` 回包（`id` 为空时省略） |
| 11 | `WebRegistryService` 即时查询 | `query()` 直接读 `BuiltInRegistries.*` / `Registries.*`，无缓存 |
| 12 | `FlightModeManager` 与 `EditorScreen` 解耦、WebUI / 游戏内共用 | 位于 `control/FlightModeManager.java`；`WebPreviewScreen` 与 `editor/EditorScreen` 均只调用其 `INSTANCE` |
| 13 | `SchemaExporter` 是 `schema.get` 的 Java 侧权威 | `handleSchemaGet()` → `SchemaExporter.exportAll()`；`script/schema/` 含 `TrackSchemas` / `MetaSchemas` / `TriggerSchemas` / `TrackTypeSchema` / `FieldDef` / `SchemaRegistry` / `SchemaExporter` |
| 14 | 前端组件清单 | `editor/src/components/`：`Timeline.vue`、`Preview.vue`、`ScriptList.vue`、`TriggerPanel.vue` + `trigger/*.vue`（10 个类型专用编辑器）、`DynamicForm.vue` + `fields/*.vue`（12 个）、`TrackListPanel.vue` / `ClipPanel.vue` / `KeyframePanel.vue`；`i18n/{index,zh_cn,en_us}.ts` |
| 15 | 已覆盖能力 | 撤销重做 `store.ts:78-104`（上限 200）；A-B 循环 `loopStart/loopEnd` + `setLoopIn/Out/clearLoop`；marker `markers` + `addMarker/removeMarker`；快捷键 `App.vue`（Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y / M）；飞控 `store.ts enterFlightMode` + `Preview.vue` 飞控叠层 |
| 16 | 形态 | `editor/package.json` `"version": "0.1.0"`；`editor/release/ImmersiveCinematicsEditor Setup 0.1.0.exe` 存在；`store.ts:19` `SERVER_URL = 'ws://127.0.0.1:8765/ws'`；`CinematicKeyBindings.java:26` F6 = 旧 Java 编辑器、`:48` F9 = WebUI；`WebPreviewScreen.init()` 调 `WebEditorServer.INSTANCE.start()`；`demo.ts` + `loadDemo()` = 离线演示 |
| 17 | 差异 | 全仓无 NanoHTTPD / Java-WebSocket 依赖（grep 无命中）；预览无 JPEG / H.264 / WebRTC 代码路径 |
| 18 | 安全现状 | `WebEditorServer` 无 Origin / token / 子协议校验（现已实现，见 [`security.md`](security.md)） |
| 19 | 预览走 `CameraManager` 直控 | `handleSeek/play/pause/stop` 与 `handleSetCamera` 均调 `CameraManager.INSTANCE`（`setTime` / `resume` / `pause` / `stop` / `setCameraDirect`） |
| 20 | 许可 | `example/editor/olive/LICENSE` = GPL v3 全文（`CMakeLists.txt:5-7` 为 "version 3 or later"）；`example/editor/lossless-cut/LICENSE` = GPL v2 全文 + `package.json:46` `"license": "GPL-2.0-only"`；本仓 `LICENSE` = MIT |
| 21 | 从 Olive 采纳的设计点 | Auto-Scale `{None, Fit, Fill, Stretch}`（`transformdistortnode.cpp:80`）；按序列分辨率归一化（`:291-297`）；关键帧贝塞尔手柄与三次/二次插值（`keyframe.h:104-111`、`node.cpp:487-513`）；裁切四边 0~1（`cropdistortnode.cpp:154-157`，`:109-112` 按分辨率换算）；向量按分量分轨（`NodeKeyframeTrackReference(NodeInput(this, kPositionInput), 0/1)`） |
| 22 | 已知缺陷 | `WebPreviewScreen.java:138-152` `enterFlightMode(...)` 只使用 `absolute`，其余 8 个参数丢弃，改用 `CameraManager.INSTANCE.getPath().getPosition()` + `getProperties().get{...}()`；`WebEditorApi.handleEnterFlightMode` 确实解析并传入了这些字段（已修，见 §五） |

### ② 已修正的断言

| # | 旧说法 | 新事实 | 证据 / 裁决 |
|---|---|---|---|
| 1 | 理由表「触发器 2,889」 | **触发器 4,263** | 当前 HEAD 实测 `trigger/**` = 4,263。时间对比：`git log -1 --format=%cI -- .../trigger/` = **2026-09-29T14:52:45+08:00**，文档 = **2026-10-06T21:57:01+08:00**——代码早于文档，旧值统计口径漏算：2,889 = `trigger/client`(592) + `trigger/network`(1,029) + `trigger/server` 顶层(1,268)，漏掉 `trigger/server/` 下 4 个子目录（action 173 + evaluator 745 + prereq 129 + store 327 = 1,374）。 |
| 2 | 组件表标题「Java 侧（`common/.../webui/`）」 | 改为「`common/.../webui/` 及其依赖模块」；`FlightModeManager` 标注在 `control/`，`SchemaExporter` 标注在 `script/schema/`；补上遗漏的 `WebSocketSession` | 两文件实际路径：`control/FlightModeManager.java`、`script/schema/SchemaExporter.java`；`webui/` 包核查时为 8 个文件（2026-10-09 回写：现共 10 个），原表只列了 6 个 |
| 3 | 退役表「游戏内编辑器（`editor/` 包 + `client/EditorBridgeImpl`）10,164」 | 10,164 只是 `editor/` 包；`EditorBridgeImpl` 34 行，合计 10,198（占比仍 33%） | `git ls-files .../editor/*` = 10,164；`client/EditorBridgeImpl.java` = 34 |
| 4 | 退役表「外部编辑器（`editor/src`，Vue 3 + TS）12,799」 | 12,799 是 `editor/src` 全量文件（含 26 个 SVG 4,168 行 + 1 个 PNG）；TS+Vue 实为 5,643（.ts 439 + .vue 5,204） | `git ls-files 'editor/src/**'` 按扩展名分组统计 |

### ③ 补全的信息

- `webui/` 包文件清单与行数见 ①-3（2026-10-09 回写：现共 10 文件）。
- `WebRegistryService` 支持的 kind：`item` / `block` / `entity`（别名 `entity_type`）/ `sound` / `target` / `biome` / `dimension`（别名 `from_dimension`）/ `structure` / `advancement`；`stage`（`gamestage`）返回空列表（`WebRegistryService.java:39-51`）。
- 事件推送清单：`hello_ack`（携带 `version = "0.3.5"`，0.3.6 开发期仍是该值）；`playback.state`（`WebPreviewScreen.render` 每 50ms 一次）；`flight.state`（飞控中每 100ms 一次）；`flight.exit`（带最终相机参数与 RELATIVE 基准点）；`error`。
- `ScriptFileService` 细节：脚本根目录 `immersive_cinematics/scripts/`，`Files.walk(..., 5)` 递归列 `.json`，写盘前做 `normalize()` + `startsWith(base)` 越界校验。
- `editor/` 包的外部引用面：仅 3 处（见 §3.2）。
- 参考编辑器检索表头：标注「网络数据，未复检」。

### ④ 无法核实的断言（未验证）

- 「lossless-cut 用的 `electron-vite` 脚手架本身是 MIT」——本仓 `example/editor/lossless-cut/node_modules/` 不存在，无法就地核验 electron-vite 的 license 字段；仅确认其构建脚本使用 `electron-vite build`（`package.json:19`）。**未验证**。
- MIT 剪辑软件检索全表（★ 数、各项目许可与形态）：2026-10-06 网络快照，未重搜。**未验证**。
- 「不打包进 mod」——未核查构建脚本（`build.gradle` / electron-builder 配置）是否会把 `editor/` 产物并入模组 jar。**未验证**。
- 「编辑器预览与游戏内播放的通道隔离属并行播放步骤 5 的范围」：属跨文档引用，未在本文核查范围内比对。

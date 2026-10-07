# 编辑器 WebUI 迁移（0.3.6）

**状态**: 独立 Editor（Vue 3 + Electron）已实现并打包 0.1.0；**游戏内编辑器决定退役**（§4）；剩余工作见 §3。
**范围**: 本文只描述“编辑器 WebUI 迁移”这一件事，不混入其他 0.3.6 方案。

---

## 1. 现状

### 1.1 产品形态

- **独立 Windows 小工具**：Vue 3 + TypeScript + Vite + Electron，源码在 `editor/`，已打出安装包（0.1.0，测试中），**不打包进 mod**。
- **定位（已确认）**：编辑器**不是视频剪辑器、也不是构图编辑器**，它是**固定格式脚本的生成器**——最终产物就是那份脚本。推论：
  - 时间轴 / 预览 / 参数面板都是**为产出脚本服务的辅助**，不是通用剪辑能力；
  - **不需要**：媒体导入、成片导出、转码、特效库；
  - **需要**：字段编辑、校验（`script.validate`）、读写脚本，以及“能看到效果”的预览通道；
  - 对它的要求是“**一副好用的脚手架 + 改功能**”：骨架省事，业务只做脚本的字段与校验。
  - **权威在 mod 侧**：脚本的解析与播放是 mod 的 `script/` 模块，**只有它能读**——编辑器不直接读文件、不自己解析，一切经协议由那边确认（`schema.get` / `script.validate`）。
- **mod 是服务端，Editor 是客户端**：游戏内按 **F9** 打开 WebUI 预览屏（同时启动本地服务），Editor 连接 `ws://127.0.0.1:8765/ws`。
- **游戏内 Java 编辑器仍在**（F6）：两个编辑器并存，共用同一套脚本格式与字段 schema。
- Editor 带离线演示模式（未连接游戏时可浏览界面与示例数据）。

### 1.2 Java 侧（`common/.../webui/` 及其依赖模块）

`webui/` 包共 8 个文件：`WebEditorServer` / `WebSocketSession` / `WebEditorApi` / `ScriptFileService` / `WebRegistryService` / `WebFrameCapture` / `WebFrameStreamer` / `WebPreviewScreen`。

| 组件 | 职责 |
|---|---|
| `WebEditorServer` | 本地 WebSocket 服务：仅绑定 127.0.0.1、固定端口 8765；自实现（无第三方库） |
| `WebSocketSession` | 单连接的会话封装（文本 / 二进制发送） |
| `WebEditorApi` | 消息路由（见 §2）；播放状态 / 飞控状态主动推送 |
| `ScriptFileService` | 脚本文件读写、新建、删除（根目录 `immersive_cinematics/scripts/`，相对路径 + 越界校验） |
| `WebRegistryService` | 物品 / 方块 / 实体 / 声音 / 目标 / 群系 / 维度 / 结构 / 进度等自动补全数据（即时查询） |
| `WebFrameCapture` + `WebFrameStreamer` | 720p raw RGBA 帧流：主画面 → 小 FBO → glReadPixels；worker 线程发送、约 60fps 节流、跟不上丢旧帧 |
| `WebPreviewScreen` | F9 预览屏：播放控制、飞控入口与飞控 HUD |
| `FlightModeManager`（在 `control/`） | 飞控核心：与 `EditorScreen` 解耦，WebUI / 游戏内共用 |
| `SchemaExporter`（在 `script/schema/`） | 导出字段元数据（`schema.get`）；Java 侧是唯一 schema 权威 |

### 1.3 前端（`editor/`）

- 组件：时间轴、预览、脚本列表、触发器面板（各类型专用编辑器 + schema 动态表单）、轨道 / 片段 / 关键帧面板、中英 i18n。
- 已覆盖：脚本管理（新建 / 打开 / 保存 / 删除 / 校验）、meta / 轨道 / clip / 关键帧编辑、触发器编辑、播放控制、预览画面、飞控模式（进入 / 记录 / 取消）、撤销重做、A-B 循环、marker、快捷键。

### 1.4 与原方案的差异（实施时定的）

- WebSocket 服务为自实现，未用 NanoHTTPD / Java-WebSocket。
- 预览为 raw RGBA 720p + 约 60fps 节流；未做 JPEG / H.264 / WebRTC。
- Editor 是独立 Electron 应用，不由 mod 托管页面。
- 飞控留在游戏原生环境，WebUI 只做进入 / 退出与状态显示。

---

## 2. 通信协议（现状）

- 文本帧：`{ "type": "...", "data": {...}, "id": "..." }` 信封；请求带回执，事件主动推送。
- 已实现消息：`hello`；`script.list` / `script.load` / `script.save` / `script.delete` / `script.new` / `script.validate`；`registry.query` / `registry.get`；`schema.get`；`editor.seek` / `editor.play` / `editor.pause` / `editor.stop` / `editor.setCamera` / `editor.pushScript`；`editor.enter_flight_mode` / `editor.exit_flight_mode` / `editor.cancel_flight_mode`。
- 服务端主动推送（事件）：`hello_ack`；`playback.state`（预览屏每 50ms 推一次，约 20Hz）；`flight.state`（飞控中每 100ms，约 10Hz）；`flight.exit`；`error`。
- 二进制帧（预览）：`[1 byte type = 0x01][2 bytes frameId][2 bytes width][2 bytes height][RGBA payload]`（宽度 / 高度 / frameId 均为大端 2 字节；像素为 1280×720 RGBA，发送前已做上下翻转）。
- 安全：仅绑定 127.0.0.1；无 token / Origin 校验（见 §3）。

---

## 3. 剩余工作

- **旧游戏内编辑器退役**：**决策已定——不留**（理由与占比见 §4，路径见 §7）。
- **安全加固**：本地 token / Origin 校验未做。
- **预览与游戏内播放的通道隔离**：编辑器预览目前走 `CameraManager` 直控；隔离属[并行播放](./parallel-playback.md)步骤 5 的范围。
- **远期 / 可选**：H.264 / WebRTC；飞控远程面板；动效。

### 3.1 飞控模式：按选中片段开"单独镜头"（已确认·2026-10-07）

**方向**：飞控不再只是"接管全局唯一相机"，而是**绑定到选中的相机轨道片段**——n 条相机轨道中选中轨道 A 的某个片段进入飞控时，该片段的相机画面**自动全屏覆盖预览**（即一个 dest=全屏、opacity=1 的 lane，"单独开了一个镜头"），精确调控的正是这一个相机的状态；退出飞控把最终姿态写回该片段（现状：`FlightModeManager.enter()` = `CameraManager.pause()` + `setPreviewDirectControl(true)`，`FlightController` 接管全局相机，`exit()` 返回 `FlightState` 供编辑器写回片段）。

**明确不做**：把选中片段的相机画面当 PIP 小窗叠在原画上、或通过某种模式退回原画再操作——预览里处理的就是这个镜头本身。

**依赖**：① [多相机渲染](./multi-camera-rendering.md)的 lane 渲染（选中片段相机 → 离屏 FBO → 全屏上屏）；② [并行播放](./parallel-playback.md)步骤 5（预览为独立实例，不挤掉游戏内播放）——飞控即"预览实例的单 lane 全屏"形态。多 lane 合成本身在编辑器预览中的表现见[画面合成](./camera-composition.md) §5 待定项。

> 原始迁移方案（研究参考、阶段划分、协议草案）与 0.3.5 的独立 Editor 计划见 git 历史与 `plans/complete/0.3.5/webui-editor-standalone-plan.md`、`webui-logic-completion.md`。

---

## 4. 游戏内编辑器退役（已确认）

**决策**：**不留游戏内编辑器**，后续只维护外部编辑器。

**占比**（`git ls-files` + `wc -l` 统计，2026-10-07 在 HEAD 重算；common 全部 Java 234 文件 / 30,634 行）：

| 部分 | 行数 | 占比 |
|---|---|---|
| 游戏内编辑器（`editor/` 包 62 文件 = 10,164；另加 `client/EditorBridgeImpl` 34 行共 10,198） | **10,164** | **33%** |
| 外部编辑器（`editor/src` 全量文件；其中 TS+Vue 仅 5,643，另含 26 个 SVG + 1 个 PNG） | 12,799 | — |
| WebUI 服务端（`webui/`，8 文件） | 1,394 | 4.5% |
| 共享 schema（`script/schema/`，7 文件） | 654 | 2% |

理由：

- **占 mod Java 的三分之一**，是单体最大的包（比相机 1,036、脚本 7,929（含 `script/schema/` 654）、触发器 4,263 都大）。
- **跨版本**：它编译在 mod 内，每个 MC 版本都要重新适配；外部编辑器是 web 应用，**一份编辑器对接所有版本**——一份编辑器可产出多个版本的脚本。
- **历史遗留**：自带 `Files` 直读写 IO、`PreviewCapture`、`EditorOutput` 各一套；代码里留有 `[KILO-DEBUG]` 打印与专用 `EditorLogger`。
- **独有能力已被覆盖**：世界内取景 = WebUI 的飞行直控（`enter_flight_mode`）+ 帧推流预览，已经在做同一件事。
- **UI 重复劳动**：字段控件 / 面板 / 撤销 / 选择，外部编辑器里有另一份。

保留的唯一好处：**不切窗口**（游戏内直接改）。判定：优势很薄。

---

## 5. 参考编辑器评估（已确认）

`example/editor/` 下有两份开源剪辑软件：

| 项目 | 许可 | 结论 |
|---|---|---|
| Olive（非线性剪辑，C++/Qt） | **GPL-3.0** | 代码不可取 |
| lossless-cut（切割工具，Electron） | **GPL-2.0-only** | 代码不可取 |

本项目是 **MIT**：搬 GPL 代码会让整个 mod 被迫改许可，**因此"直接用它的壳子"这条路不通**。

**可以拿的是设计思路**（不受版权保护），已采纳：Olive 的变换归一化（按序列分辨率归一化到 [-1,1]）、Fit / Fill / Stretch 适配三档、关键帧自带插值 + 贝塞尔手柄、向量按分量分轨、裁切四边 0~1 归一化（见[可变画面](./variable-frame.md)、[脚本模型](./script-model.md)）。

lossless-cut 用的 `electron-vite` 脚手架本身是 MIT，但我们的编辑器已是同一套栈（Vue 3 + Vite + Electron），fork 它是降级。

### 5.1 GitHub 上的 MIT 剪辑软件（2026-10-06 检索；网络数据，未复检）

| 项目 | ★ | 许可 | 形态 | 说明 |
|---|---|---|---|---|
| `mifi/editly` | 5,516 | MIT | CLI / API（TS） | 声明式命令行剪辑，非 GUI |
| `Augani/openreel-video` | 5,280 | MIT | 浏览器（TS） | 开源 CapCut 替代 |
| `AIEraDev/Clypra` | 3,305 | MIT | 桌面（Rust + Tauri + React） | GPU 渲染、帧精确时间轴 |
| `walterlow/freecut` | 2,231 | MIT | 浏览器（TS） | 多轨 + 关键帧动画 + 导出 |
| `tharunbirla/LibreCuts` | 917 | MIT | Android（Kotlin） | 手机端 |
| `MartinDelophy/ai-video-editor` | 890 | MIT | 浏览器 / PWA（JS） | local-first |
| `mohyware/clip-js` | 769 | MIT（已核对 LICENSE 原文） | 浏览器（TS） | Next.js + Remotion + ffmpeg.wasm |

**结论（已确认）**：这些都是**视频剪辑器**（素材 = 媒体文件，核心 = 切片段 / 排顺序），与本文定位的**脚本生成器**不同构——**整体结构不采用**。

参考的是它们的**外形**：这类工具“该有的功能长什么样”（时间轴怎么摆、关键帧怎么编辑、属性面板怎么组织），再**翻译成我们的**（脚本生成器该有的样子）。**不是抄功能，也不是复制实现**。

### 5.2 脚手架（已确认）

编辑器要的是**一副好用的脚手架 + 改功能**，不是一套完整剪辑器：

- **骨架**（窗口 / 状态 / 撤销 / i18n / 打包）要省事——这部分**我们已有**：Vue 3 + Vite + Electron，MIT，已打包 0.1.0。
- **业务层要薄**：字段定义在 mod 侧的 `script/`（解析与播放模块，唯一权威），编辑器通过 `schema.get` 把它渲染成表单——**加一个脚本字段 = 改 schema + 加一个控件**。
- 若要内部整理骨架约定，唯一有意义的参照是 **`electron-vite`**（MIT，main / preload / renderer 三层）——这是整理，不是换项目。

---

## 6. 数据模型保留（已确认）

删除 `editor/` 包时，**脚本数据模型不随之删除**：

- **脚本格式的权威在 mod 的解析与播放模块（`script/`）**：只有它能读脚本。`script/schema/`（`TrackSchemas` / `FieldDef` / `SchemaExporter` / `SchemaRegistry`）是其中的字段元数据，独立于任何编辑器存在。
- 编辑器只是这份模型的消费者：外部编辑器通过 `schema.get` 取；游戏内编辑器当年直接调用。
- **删包时的外部引用（2026-10-07 核查）**：`editor/` 包之外只有 3 处引用它——`client/EditorBridgeImpl`（实现 `editor.EditorBridge`）、`control/CinematicKeyBindings`（F6 入口引用 `editor.EditorScreen`）、`webui/ScriptFileService.newScriptJson()`（`new EditorDocument().toJson()`）。前两处随包一起删；`ScriptFileService` 需改用 `script/` 侧的等价方式生成空脚本，否则 `script.new` 会编译失败。
- 删包后的归属：schema 留在 `script/schema/`，升级方向见[脚本模型](./script-model.md)（参数声明、keyframable、关键帧到分量）。

---

## 7. 迁移路径（方向）

1. **冻结**：不再给游戏内编辑器加功能。
2. **对齐能力**：确认外部编辑器覆盖其全部能力（缺口清单见 `feedback-0.3.5/02`）。
3. **切换默认**：F9 为主；F6 标记为旧或移除。
4. **删除 `editor/` 包**（10,164 行）——保留 `script/schema/` 与脚本格式。

---

## 已知缺陷（2026-10-06 代码复查）

> 只读代码审查发现，未在游戏内复现；不影响当前设计，记录备查。

- **飞控入口忽略传入坐标**：`WebPreviewScreen.enterFlightMode` 忽略传入的 `x/y/z`。

---

## 事实核查（2026-10-07）

**核查依据**：本仓工作树（文档最后提交 `cfd3b7d` = 2026-10-06T21:57:01+08:00，其后无代码改动）、`common/src/main/java/com/immersivecinematics/immersive_cinematics/{webui,editor,script,control,client}/`、`editor/`（前端）、`example/editor/olive/app/`、`example/editor/lossless-cut/`。
**行数口径**：`git ls-files <pathspec> | xargs cat | wc -l`（逐文件 `wc -l` 会因 xargs 分批产生多个 total，故改用 cat 汇总）。

### ① 核实为真的断言

| # | 断言 | 证据 |
|---|---|---|
| 1 | `editor/` 包 10,164 行 | 62 文件；顶层 12 个类 + `area`(6) / `debug`(2) / `fields`(2) / `panel`(10) / `preset`(4) / `trigger`(10) / `widget`(16) |
| 2 | `script/` 7,929 行 | 42 文件（含 `script/schema/` 的 7 文件 654 行） |
| 3 | `camera/` 1,036 行 / `webui/` 1,394 行 / `script/schema/` 654 行 | `webui/` 8 文件：`WebEditorServer` 213 / `WebSocketSession` 150 / `WebEditorApi` 279 / `ScriptFileService` 71 / `WebRegistryService` 120 / `WebFrameCapture` 148 / `WebFrameStreamer` 108 / `WebPreviewScreen` 305 |
| 4 | common 全部 Java 30,634 行 | 234 文件 |
| 5 | 占比 33% / 4.5% / 2% | 10,164÷30,634 = 33.2%；1,394÷30,634 = 4.6%；654÷30,634 = 2.1% |
| 6 | `WebEditorServer` 仅绑 127.0.0.1、端口 8765、自实现无第三方库 | `WebEditorServer.java:63` `new ServerSocket(8765, 4, InetAddress.getByName("127.0.0.1"))`；仅用 JDK `ServerSocket` + `MessageDigest`/`WS_MAGIC` 自实现握手 |
| 7 | `WebFrameCapture` + `WebFrameStreamer`：主画面 → 小 FBO → glReadPixels；720p；worker 线程；约 60fps 节流；跟不上丢旧帧 | `WebFrameCapture.java:22-23` `TARGET_W/H = 1280/720`；`WebFrameStreamer.java` `SEND_INTERVAL_MS = 16`、单线程 `SENDER`、`AtomicReference<byte[]> PENDING` 只保留最新帧 |
| 8 | 二进制帧 `[1B type=0x01][2B frameId][2B width][2B height][RGBA]` | `WebFrameStreamer.sendRaw()`：`headerSize = 7`、`FRAME_TYPE_RAW_RGBA = 0x01`、`ByteBuffer` 默认大端；发送前按行翻转（OpenGL 自底向上） |
| 9 | §2 消息清单（hello / script.* / registry.* / schema.get / editor.* / enter·exit·cancel_flight_mode） | `WebEditorApi.handle()` 的 switch 分支逐一对应，无遗漏、无多余 |
| 10 | 文本帧信封 `{type,data,id}`；请求带回执 | `WebEditorApi.handle()` 解析 + `wrap(type,data,id)` 回包（`id` 为空时省略） |
| 11 | `WebRegistryService` 即时查询 | `query()` 直接读 `BuiltInRegistries.*` / `Registries.*`，无缓存 |
| 12 | `FlightModeManager` 与 `EditorScreen` 解耦、WebUI / 游戏内共用 | 位于 `control/FlightModeManager.java`；`WebPreviewScreen` 与 `editor/EditorScreen` 均只调用其 `INSTANCE` |
| 13 | `SchemaExporter` 是 `schema.get` 的 Java 侧权威 | `handleSchemaGet()` → `SchemaExporter.exportAll()`；`script/schema/` 含 `TrackSchemas` / `MetaSchemas` / `TriggerSchemas` / `TrackTypeSchema` / `FieldDef` / `SchemaRegistry` / `SchemaExporter` |
| 14 | §1.3 前端组件清单 | `editor/src/components/`：`Timeline.vue`、`Preview.vue`、`ScriptList.vue`、`TriggerPanel.vue` + `trigger/*.vue`（10 个类型专用编辑器）、`DynamicForm.vue` + `fields/*.vue`（12 个）、`TrackListPanel.vue` / `ClipPanel.vue` / `KeyframePanel.vue`；`i18n/{index,zh_cn,en_us}.ts` |
| 15 | §1.3 已覆盖能力 | 撤销重做 `store.ts:78-104`（上限 200）；A-B 循环 `loopStart/loopEnd` + `setLoopIn/Out/clearLoop`；marker `markers` + `addMarker/removeMarker`；快捷键 `App.vue`（Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y / M）；飞控 `store.ts enterFlightMode` + `Preview.vue` 飞控叠层 |
| 16 | §1.1 形态 | `editor/package.json` `"version": "0.1.0"`；`editor/release/ImmersiveCinematicsEditor Setup 0.1.0.exe` 存在；`store.ts:19` `SERVER_URL = 'ws://127.0.0.1:8765/ws'`；`CinematicKeyBindings.java:26` F6 = 旧 Java 编辑器、`:48` F9 = WebUI；`WebPreviewScreen.init()` 调 `WebEditorServer.INSTANCE.start()`；`demo.ts` + `loadDemo()` = 离线演示 |
| 17 | §1.4 差异 | 全仓无 NanoHTTPD / Java-WebSocket 依赖（grep 无命中）；预览无 JPEG / H.264 / WebRTC 代码路径 |
| 18 | §3 安全现状 | `WebEditorServer` 无 Origin / token / 子协议校验 |
| 19 | §3 预览走 `CameraManager` 直控 | `handleSeek/play/pause/stop` 与 `handleSetCamera` 均调 `CameraManager.INSTANCE`（`setTime` / `resume` / `pause` / `stop` / `setCameraDirect`） |
| 20 | §5 许可 | `example/editor/olive/LICENSE` = GPL v3 全文（`CMakeLists.txt:5-7` 为 "version 3 or later"）；`example/editor/lossless-cut/LICENSE` = GPL v2 全文 + `package.json:46` `"license": "GPL-2.0-only"`；本仓 `LICENSE` = MIT |
| 21 | §5 从 Olive 采纳的设计点 | Auto-Scale 下拉 `{None, Fit, Fill, Stretch}`（`transformdistortnode.cpp:80`，故 "Fit / Fill / Stretch 三档" 属实）；按序列分辨率归一化（同文件 `:291-297` `scale(2.0/sequence_res.x(), 2.0/sequence_res.y(), 1.0)` 再 `texture_res*0.5`）；关键帧贝塞尔手柄与三次/二次插值（`keyframe.h:104-111`、`node.cpp:487-513`）；裁切四边 0~1（`cropdistortnode.cpp:154-157` 四个 side 输入 `min=0/max=1/Percentage`，`:109-112` 按分辨率换算）；向量按分量分轨（`NodeKeyframeTrackReference(NodeInput(this, kPositionInput), 0/1)` 分量索引） |
| 22 | 已知缺陷 | `WebPreviewScreen.java:138-152` `enterFlightMode(double x, double y, double z, float yaw, float pitch, float roll, float fov, float zoom, boolean absolute)` 只使用 `absolute`，其余 8 个参数全部丢弃，改用 `CameraManager.INSTANCE.getPath().getPosition()` + `getProperties().get{Yaw,Pitch,Roll,Fov,Zoom}()`；`WebEditorApi.handleEnterFlightMode` 确实解析并传入了这些字段 |

### ② 已修正的断言

| # | 旧说法 | 新事实 | 证据 / 裁决 |
|---|---|---|---|
| 1 | §4 理由：「触发器 2,889」 | **触发器 4,263** | 当前 HEAD 实测 `trigger/**` = 4,263。时间对比：`git log -1 --format=%cI -- .../trigger/` = **2026-09-29T14:52:45+08:00**，`... -- plans/0.3.6/editor-webui-migration.md` = **2026-10-06T21:57:01+08:00**——代码早于文档，故不是"代码变了"，而是旧值统计口径漏算：2,889 = `trigger/client`(592) + `trigger/network`(1,029) + `trigger/server` 顶层(1,268)，漏掉 `trigger/server/` 下 4 个子目录（action 173 + evaluator 745 + prereq 129 + store 327 = 1,374）。按当前 HEAD 值更新。 |
| 2 | §1.2 标题：「Java 侧（`common/.../webui/`）」 | 改为「`common/.../webui/` 及其依赖模块」；`FlightModeManager` 标注在 `control/`，`SchemaExporter` 标注在 `script/schema/`；补上遗漏的 `WebSocketSession` | 两文件实际路径：`control/FlightModeManager.java`、`script/schema/SchemaExporter.java`；`webui/` 包共 8 个文件，原表只列了 6 个 |
| 3 | §4 表：「游戏内编辑器（`editor/` 包 + `client/EditorBridgeImpl`）10,164」 | 10,164 只是 `editor/` 包；`EditorBridgeImpl` 34 行，合计 10,198（占比仍 33%） | `git ls-files .../editor/*` = 10,164；`client/EditorBridgeImpl.java` = 34 |
| 4 | §4 表：「外部编辑器（`editor/src`，Vue 3 + TS）12,799」 | 12,799 是 `editor/src` 全量文件（含 26 个 SVG 4,168 行 + 1 个 PNG）；TS+Vue 实为 5,643（.ts 439 + .vue 5,204） | `git ls-files 'editor/src/**'` 按扩展名分组统计 |

### ③ 补全的信息

- **`webui/` 包文件清单与行数**（原表缺 `WebSocketSession`，且未给路径/行数）：见 ①-3。
- **`WebRegistryService` 支持的 kind**：`item` / `block` / `entity`(别名 `entity_type`) / `sound` / `target` / `biome` / `dimension`(别名 `from_dimension`) / `structure` / `advancement`；`stage`(`gamestage`) 返回空列表（`WebRegistryService.java:39-51`）。
- **事件推送清单**：`hello_ack`（携带 `version = "0.3.5"`，0.3.6 开发期仍是该值）；`playback.state`（`WebPreviewScreen.render` 每 50ms 一次）；`flight.state`（飞控中每 100ms 一次）；`flight.exit`（带最终相机参数与 RELATIVE 基准点）；`error`。
- **`ScriptFileService` 细节**：脚本根目录 `immersive_cinematics/scripts/`，`Files.walk(..., 5)` 递归列 `.json`，写盘前做 `normalize()` + `startsWith(base)` 越界校验。
- **`editor/` 包的外部引用面**（已补进 §6）：仅 3 处。
- **§5.1 表头**：标注「网络数据，未复检」。

### ④ 无法核实的断言（未验证）

- §5：「lossless-cut 用的 `electron-vite` 脚手架本身是 MIT」——本仓 `example/editor/lossless-cut/node_modules/` 不存在，无法就地核验 electron-vite 的 license 字段；仅确认其构建脚本使用 `electron-vite build`（`package.json:19`）。**未验证**。
- §5.1 全表（★ 数、各项目许可与形态）：2026-10-06 网络快照，按要求未重搜。**未验证**。
- §1.1：「不打包进 mod」——本文未核查构建脚本（`build.gradle` / electron-builder 配置）是否会把 `editor/` 产物并入模组 jar。**未验证**。
- §2「编辑器预览与游戏内播放的通道隔离属[并行播放](./parallel-playback.md)步骤 5 的范围」：属跨文档引用，未在本文核查范围内比对。

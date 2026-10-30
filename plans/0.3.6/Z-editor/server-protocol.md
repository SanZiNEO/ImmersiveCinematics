# 本地服务端与协议

> 状态：已实现（`webui/` 10 文件；协议、帧流、预览隔离全部落地）
> 范围：模组侧本地 WebSocket 服务端、消息协议、帧流、前端形态与契约；握手校验见 [`security.md`](security.md)，飞控见 [`flight-mode.md`](flight-mode.md)
> 关联：`editor/src/`、`docs/modules/editor.md`

## 一、组件

`webui/` 包共 10 个文件；`FlightModeManager` 在 `control/`，`SchemaExporter` 在 `script/schema/`。

| 组件 | 职责 |
|---|---|
| `WebEditorServer` | 本地 WebSocket 服务：仅绑定 127.0.0.1、固定端口 8765；自实现（无第三方库） |
| `WebSocketSession` | 单连接的会话封装（`sendText` / `sendFrame`，只做 WebSocket 帧收发） |
| `WebEditorApi` | 消息路由；播放状态 / 飞控状态主动推送 |
| `ScriptFileService` | 脚本文件读写、新建、删除（根目录 `immersive_cinematics/scripts/`，`Files.walk(..., 5)` 递归列 `.json`，写盘前 `normalize()` + `startsWith(base)` 越界校验） |
| `WebRegistryService` | 物品 / 方块 / 实体 / 声音 / 目标 / 群系 / 维度 / 结构 / 进度等自动补全数据（即时查询，直接读 `BuiltInRegistries.*` / `Registries.*`，无缓存） |
| `WebFrameCapture` + `WebFrameStreamer` | 720p raw RGBA 帧流：主画面 → 小 FBO → glReadPixels；worker 线程发送、约 60fps 节流、跟不上丢旧帧 |
| `WebPreviewScreen` | F9 预览屏：播放控制、飞控入口与飞控 HUD |
| `ScriptGraphService` | 脚本架构图数据（`script.graph`）：扫描 scripts 目录 → 节点（脚本）/ 边（`requires` 依赖）/ 分区（文件夹）/ 提示；全量重扫，容错（坏脚本也上图，标记 `valid=false`） |
| `ResourceFileService` | 资源列举（`resource.list`）：`resource/` 下文本 key / 图片 / 音频清单，只读；相对路径经 `resolveSafe` 越界校验 |
| `FlightModeManager`（`control/`） | 飞控核心：不依赖具体界面，WebUI 预览屏与键盘中转共用 |
| `SchemaExporter`（`script/schema/`） | 导出字段元数据（`schema.get`）；Java 侧是唯一 schema 权威 |

`WebRegistryService` 支持的 kind：`item` / `block` / `entity`（别名 `entity_type`）/ `sound` / `target` / `biome` / `dimension`（别名 `from_dimension`）/ `structure` / `advancement`；`stage`（`gamestage`）返回空列表（`WebRegistryService.java:39-51`）。

## 二、前端

- 组件：时间轴、预览、脚本列表、触发器面板（各类型专用编辑器 + schema 动态表单）、轨道 / 片段 / 关键帧面板、中英 i18n。
- 目录：`editor/src/components/`：`Timeline.vue`、`Preview.vue`、`ScriptList.vue`、`TriggerPanel.vue` + `trigger/*.vue`（10 个类型专用编辑器）、`DynamicForm.vue` + `fields/*.vue`（12 个）、`TrackListPanel.vue` / `ClipPanel.vue` / `KeyframePanel.vue`；`i18n/{index,zh_cn,en_us}.ts`。
- 已覆盖：脚本管理（新建 / 打开 / 保存 / 删除 / 校验）、meta / 轨道 / clip / 关键帧编辑、触发器编辑、播放控制、预览画面、飞控模式（进入 / 记录 / 取消）、撤销重做（上限 200）、A-B 循环、marker、快捷键（Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y / M）、中英双语。

## 三、实施时定的差异

- WebSocket 服务为自实现，未用 NanoHTTPD / Java-WebSocket。
- 预览为 raw RGBA 720p + 约 60fps 节流；未做 JPEG / H.264 / WebRTC。
- Editor 是独立 Electron 应用，不由 mod 托管页面。
- 飞控留在游戏原生环境，WebUI 只做进入 / 退出与状态显示。

## 四、协议

### 4.1 信封

文本帧：`{ "type": "...", "data": {...}, "id": "..." }`；请求带回执，事件主动推送；`id` 为空时省略。

### 4.2 请求与回执

已实现消息：`hello`；`script.list` / `script.load` / `script.save` / `script.delete` / `script.new` / `script.validate`；`registry.query` / `registry.get`；`schema.get`；`script.graph` / `resource.list`；`editor.seek` / `editor.play` / `editor.pause` / `editor.stop` / `editor.setCamera` / `editor.pushScript`；`editor.enter_flight_mode` / `editor.exit_flight_mode` / `editor.cancel_flight_mode`。

回执族：`hello_ack`、各 `*.result`、`schema.data`。

`script.graph` / `resource.list` 为 0.3.6 新增，`WebEditorApi.handle()` :46-47 分派，结果消息 `script.graph.result` :221 / `resource.list.result` :234。

### 4.3 推送

- `hello_ack`（携带 `version`，0.3.6 开发期为 `0.3.5`）。
- `playback.state`（预览屏每 50ms 一次，约 20Hz）。
- `flight.state`（飞控中每 100ms 一次，约 10Hz）。
- `flight.exit`（最终相机参数与 RELATIVE 基准点）。
- `error`。

### 4.4 二进制帧

`[1 byte type = 0x01][2 bytes frameId][2 bytes width][2 bytes height][RGBA payload]`；宽度 / 高度 / frameId 均为大端 2 字节；像素为 1280×720 RGBA，发送前已做上下翻转（OpenGL 自底向上）。

### 4.5 凭据与路径

凭据文件在用户主目录下（`<user.home>/.immersivecinematics/webui-token`），可由环境变量 `IC_WEBUI_TOKEN_FILE` 覆盖；脚本以相对脚本根的路径标识。握手细节见 [`security.md`](security.md)。

## 五、契约

- mod 是服务端、编辑器是客户端；服务端随预览屏启停，关闭时断开全部会话。
- 脚本解析与播放归模组侧，是唯一权威；编辑器只消费 schema 与校验结果。
- 播放状态与飞控状态归游戏端：编辑器只发命令，时间、是否播放、最终姿态由游戏端回推。
- 字段元数据归 `script/schema/`，独立于任何编辑器存在。
- 飞控绑定选中片段：进入时提供该片段在播放头处的姿态，退出时接收最终姿态并写回。
- 握手凭据每次启动轮换，只出现在握手 URL 里。

## 六、边界

- 编辑器不解析脚本、不读磁盘；服务端不保存编辑器状态（撤销栈、面板布局、草稿都在前端）。
- 预览与游戏内播放走不同实例，互不挤占。
- 飞控期间相机由飞控接管，编辑器只显示与写回；模组不打包前端产物。
- 预览与游戏内播放的通道隔离已落地（预览为独立实例，2026-10-07）；原方案把它列为并行播放步骤 5，见 [`implementation.md`](implementation.md)。

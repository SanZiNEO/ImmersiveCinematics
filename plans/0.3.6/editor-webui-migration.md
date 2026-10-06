# 编辑器 WebUI 迁移（0.3.6）

**状态**: 独立 Editor（Vue 3 + Electron）已实现并打包 0.1.0；剩余工作见 §3。
**范围**: 本文只描述“编辑器 WebUI 迁移”这一件事，不混入其他 0.3.6 方案。

---

## 1. 现状

### 1.1 产品形态

- **独立 Windows 小工具**：Vue 3 + TypeScript + Vite + Electron，源码在 `editor/`，已打出安装包（0.1.0，测试中），**不打包进 mod**。
- **mod 是服务端，Editor 是客户端**：游戏内按 **F9** 打开 WebUI 预览屏（同时启动本地服务），Editor 连接 `ws://127.0.0.1:8765/ws`。
- **游戏内 Java 编辑器仍在**（F6）：两个编辑器并存，共用同一套脚本格式与字段 schema。
- Editor 带离线演示模式（未连接游戏时可浏览界面与示例数据）。

### 1.2 Java 侧（`common/.../webui/`）

| 组件 | 职责 |
|---|---|
| `WebEditorServer` | 本地 WebSocket 服务：仅绑定 127.0.0.1、固定端口 8765；自实现（无第三方库） |
| `WebEditorApi` | 消息路由（见 §2）；播放状态 / 飞控状态主动推送 |
| `ScriptFileService` | 脚本文件读写、新建、删除 |
| `WebRegistryService` | 物品 / 方块 / 实体 / 群系 / 维度 / 结构 / 进度等自动补全数据（即时查询） |
| `WebFrameCapture` + `WebFrameStreamer` | 720p raw RGBA 帧流：主画面 → 小 FBO → glReadPixels；worker 线程发送、约 60fps 节流、跟不上丢旧帧 |
| `WebPreviewScreen` | F9 预览屏：播放控制、飞控入口与飞控 HUD |
| `FlightModeManager` | 飞控核心：与 `EditorScreen` 解耦，WebUI / 游戏内共用 |
| `SchemaExporter` | 导出字段元数据（`schema.get`）；Java 侧是唯一 schema 权威 |

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
- 二进制帧（预览）：`[1 byte type = 0x01][2 bytes frameId][2 bytes width][2 bytes height][RGBA payload]`。
- 安全：仅绑定 127.0.0.1；无 token / Origin 校验（见 §3）。

---

## 3. 剩余工作

- **旧游戏内编辑器退役**：未执行（现保留 F6 入口）。
- **安全加固**：本地 token / Origin 校验未做。
- **预览与游戏内播放的通道隔离**：编辑器预览目前走 `CameraManager` 直控；隔离属[并行播放](./parallel-playback.md)步骤 5 的范围。
- **远期 / 可选**：H.264 / WebRTC；飞控远程面板；动效。

> 原始迁移方案（研究参考、阶段划分、协议草案）与 0.3.5 的独立 Editor 计划见 git 历史与 `plans/complete/0.3.5/webui-editor-standalone-plan.md`、`webui-logic-completion.md`。

---

## 已知缺陷（2026-10-06 代码复查）

> 只读代码审查发现，未在游戏内复现；不影响当前设计，记录备查。

- **飞控入口忽略传入坐标**：`WebPreviewScreen.enterFlightMode` 忽略传入的 `x/y/z`。

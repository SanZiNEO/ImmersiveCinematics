# 编辑器 WebUI 迁移（0.3.6）

**状态**: 独立 Editor（Vue 3 + Electron）已实现并打包 0.1.0；**游戏内编辑器决定退役**（§4）；剩余工作见 §3。
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

- **旧游戏内编辑器退役**：**决策已定——不留**（理由与占比见 §4，路径见 §7）。
- **安全加固**：本地 token / Origin 校验未做。
- **预览与游戏内播放的通道隔离**：编辑器预览目前走 `CameraManager` 直控；隔离属[并行播放](./parallel-playback.md)步骤 5 的范围。
- **远期 / 可选**：H.264 / WebRTC；飞控远程面板；动效。

> 原始迁移方案（研究参考、阶段划分、协议草案）与 0.3.5 的独立 Editor 计划见 git 历史与 `plans/complete/0.3.5/webui-editor-standalone-plan.md`、`webui-logic-completion.md`。

---

## 4. 游戏内编辑器退役（已确认）

**决策**：**不留游戏内编辑器**，后续只维护外部编辑器。

**占比**（`git ls-files` 统计；common 全部 Java 30,634 行）：

| 部分 | 行数 | 占比 |
|---|---|---|
| 游戏内编辑器（`editor/` 包 + `client/EditorBridgeImpl`） | **10,164** | **33%** |
| 外部编辑器（`editor/src`，Vue 3 + TS） | 12,799 | — |
| WebUI 服务端（`webui/`） | 1,394 | 4.5% |
| 共享 schema（`script/schema/`） | 654 | 2% |

理由：

- **占 mod Java 的三分之一**，是单体最大的包（比相机 1,036、脚本 7,929、触发器 2,889 都大）。
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

---

## 6. 数据模型保留（已确认）

删除 `editor/` 包时，**脚本数据模型不随之删除**：

- `script/schema/`（`TrackSchemas` / `FieldDef` / `SchemaExporter` / `SchemaRegistry`）是脚本格式的**单一权威**，独立于任何编辑器存在。
- 编辑器只是这份模型的消费者：外部编辑器通过 `schema.get` 取；游戏内编辑器当年直接调用。
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

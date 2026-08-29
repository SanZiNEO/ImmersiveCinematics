# 编辑器 WebUI 迁移计划（0.3.6）

**状态**: 📋 方案讨论，未开始实现
**目标版本**: 0.3.6
**最后更新**: 2026-08-29
**范围**: 本文只描述“编辑器 WebUI 迁移”这一件事。不混入 0.3.6 的多相机渲染、无假人相机区域、UI 缩放等其他方案。

---

## 1. 背景与动机

当前编辑器是游戏内全屏 Java 自定义 GUI。它虽然功能完整，但维护成本越来越高：

- **Java 自绘 UI 臃肿**：`EditorScreen`、`TimelineArea`、`LeftPanelArea`、`PreviewArea` 以及大量 `UI*` 控件，布局、偏移、命中、滚动都要手写。
- **多版本适配成本高**：每个 MC 版本都可能改动 GUI Scale、渲染接口、输入接口，游戏内编辑器需要反复适配。
- **0.3.6 的“UI 缩放/对冲缩放”**就是典型痛点：为了编辑器内比例，要维护一套额外坐标系和缩放换算，极其繁琐。
- **Web 技术更适合做交互和动效**：时间轴拖拽、面板折叠、属性表单、过渡动画，在 HTML/CSS/JS 里实现成本远低于 Java 自绘。
- **编辑器本质是“固定格式脚本生成器”**：它只负责产出解析器能读取的脚本，本身没有太多游戏世界耦合。
- **编辑器场景是非实时的**：除了飞控取景，大部分时间不需要和游戏世界进行实时交互，天然适合作为外部 Web 工具。

因此方向明确：

> **编辑器 UI 迁到 WebUI，Java 侧只保留播放、预览、文件、数据、飞控等后端能力。**

---

## 2. 目标 / 非目标

### 目标

1. 用 WebUI 实现编辑器界面：时间轴、属性面板、触发器编辑、脚本列表、撤销重做、自动补全、动效。
2. 模组 Java 侧保留：
   - 脚本文件读写；
   - 脚本解析/校验；
   - 播放引擎与相机控制；
   - 预览画面捕获与传输；
   - MC 注册表/自动补全数据；
   - 飞控核心。
3. 设计一套清晰的双通道协议：
   - **控制/状态协议**：Web UI → 游戏，以及游戏 → Web UI；
   - **画面传输协议**：游戏渲染帧 → 浏览器预览。
4. 画面传输目标：**最低 60fps**，通过格式协商和分辨率策略保证。
5. 飞控模式保持在游戏原生环境，WebUI 只做启动/退出/状态显示或远程面板。

### 2.1 编辑器产品形态：对标现代剪辑软件

WebUI 编辑器不再沿用当前 Java 编辑器的“固定四区”思路，而是直接对标现代剪辑软件（Premiere / DaVinci Resolve / Final Cut / CapCut 一类）：

- **不再强制划分“菜单栏 / 左侧属性面板 / 预览区 / 时间轴”这些固定区域**。
- 使用通用 Web 布局：中央大预览窗口、下方/侧边多轨道时间轴、属性/素材/模板侧栏或弹出层。
- 面板可以自由折叠、缩放、拖拽、停靠；复杂功能在 Web 里更容易扩展。
- **模板更适合做了**：常用镜头、轨道、预设片段、过场风格都可以做成“可拖入时间轴的素材/模板”，一键生成对应脚本 JSON。
- 现有 Java 编辑器的“xx 区域”概念在 WebUI 中不再需要通过自绘组件实现，统一交给 Web 组件库/布局系统。

### 非目标

1. 不把飞控核心搬到浏览器。
2. 不自己实现一个浏览器端 MC 世界渲染器。
3. 第一版不引入 WebRTC / FFmpeg 等重依赖。
4. 不追求“取代游戏内全部 UI”——只针对编辑器。
5. 本计划不包含 0.3.6 其他功能（多相机渲染等）的实现细节。

---

## 3. 需求来源

以下来自作者/维护者讨论，作为本计划的设计输入：

1. **Java 自绘 GUI 很臃肿**：偏移、布局、绘制都非常麻烦。
2. **多版本适配痛苦**：游戏内编辑器在每个 MC 版本都要重新适配，例如 0.3.6 的 UI 缩放问题。
3. **WebUI 做交互和动效简单**：时间轴、面板、动画用 Web 技术更自然。
4. **编辑器是固定格式脚本生成器**：只负责产出“解析器能读的脚本”，业务简单。
5. **编辑器场景非实时**：大部分编辑过程不需要游戏内交互。
6. **预览画面要尽量流畅**：用户认为 60fps 是合理目标，15fps 偏保守。
7. **需要自动补全**：Web 端无法直接访问 MC 注册表，必须通过数据协议获取物品、方块、实体、群系、维度、结构、进度等。
8. **飞控模式需要单独考虑**：它是少数真正依赖游戏世界实时交互的功能。
9. **选择 WebUI 迁移整体方向正确**：因为编辑器本质只是脚本生成器。
10. **WebUI 编辑器只需模仿剪辑软件**：不需要保留现有 Java 编辑器的“面板区域 / xxx 区域”结构；复杂功能在 Web 下更好做，模板也更容易实现。

---

## 4. 探索与参考项目

### 4.1 DebugBridge

- 仓库：<https://github.com/use-ai-for-mc/debugbridge>
- 定位：Fabric 客户端模组，运行 localhost WebSocket 服务器，暴露游戏状态；内置 Vue 3 Web UI。
- 技术栈：Java-WebSocket 1.6.0 + Gson + Groovy；前端 Vue 3 + Pinia + Tailwind；Web UI 通过另一个本地 HTTP 端口提供。
- 关键能力：
  - `screenshot`：抓取 framebuffer 并编码为 JPEG；
  - `record_video`：按帧/按时间间隔抓 N 帧，JPEG 编码放到 worker 线程；
  - 每个 MC 版本有独立的 `ScreenshotProvider`，因为 framebuffer 访问方式差异很大；
  - 只绑定 `127.0.0.1`，Web UI 静态资源和 WS 都走本地。
- 对本项目的启发：
  - **MC 模组 + 本地 WebSocket + Vue UI 是已被验证的架构**；
  - 帧捕获必须按版本隔离；
  - 渲染线程只负责“给像素”，编码/写盘放到后台线程；
  - 用 in-flight 上限 + 丢帧避免拖慢渲染线程。

### 4.2 Meteor WebGUI

- 仓库：<https://github.com/MCDxAI/meteor-client-webgui>
- 定位：Meteor Client 插件，用浏览器控制模组设置；内置 Vue 3 Web UI。
- 技术栈：NanoHTTPD 2.3.1（HTTP + WebSocket）+ Gson；前端 Vue 3 + Pinia + TypeScript + Vite。
- 关键能力：
  - **同一个端口**提供静态页面和 `/ws`；
  - JSON 消息统一为 `{ type, data, id }`；
  - 首次连接推 `initial.state` 全量快照，之后只推增量变更；
  - 注册表数据按需请求，不一次性全推；
  - 前端断线后 3 秒自动重连。
- 对本项目的启发：
  - 协议建议使用简单的 `{ type, data, id }` 信封；
  - 注册表/自动补全数据采用“按需拉取 + 前端缓存”；
  - 前后端状态采用“初始快照 + 增量更新”。

### 4.3 Selkies

- 仓库：<https://github.com/selkies-project/selkies>
- 定位：低延迟 Web 远程桌面/云游戏串流平台，支持 60fps 全高清。
- 传输：默认 WebSocket，WebRTC 可选；支持 JPEG 与 H.264 编码。
- 关键机制：
  - 文本帧跑控制协议，二进制帧跑视频/音频；
  - 二进制帧用**第一个字节表示类型**，例如 `0x03` JPEG、`0x04` H.264；
  - 视频帧带 `uint16` frame id，客户端 ACK，用于丢帧/抖动处理；
  - 编码优先 GPU（NVENC/VA-API），回退软件编码。
- 对本项目的启发：
  - 60fps 不是靠“简单 MJPEG 15fps”实现的，而是靠**多格式编码 + 异步管线 + 合理帧协议**；
  - 如果要做高帧率，协议必须支持多种 payload 类型，而不是只发 JPEG。

### 4.4 webrtc-java

- 仓库：<https://github.com/devopvoid/webrtc-java>
- 定位：Java 封装的 WebRTC 原生库，支持桌面窗口/屏幕捕获、P2P 视频、DataChannel。
- 优点：真低延迟、浏览器端原生支持。
- 缺点：JNI 原生库 + 跨平台打包复杂，对 MC 模组分发不友好。
- 对本项目的启发：
  - 作为远期可选路线；
  - 第一版不建议引入。

### 4.5 总结

> 三个代表性项目分别覆盖了：
> - MC 模组内嵌本地 Web UI + WebSocket：DebugBridge；
> - 简洁双向 JSON 协议 + 注册表按需拉取：Meteor WebGUI；
> - 60fps 多格式帧传输 + 二进制帧协议：Selkies。

---

## 5. 目标架构

```text
┌────────────────────────────────────┐
│          浏览器 Web Editor          │
│                                    │
│  剪辑软件式 UI / 时间轴 / 属性面板   │
│  模板素材库 / 触发器 / 动效          │
│  自动补全 / 撤销重做 / 脚本列表       │
│  预览 Canvas / 视频元素              │
└──────────────┬─────────────────────┘
               │
               │ WebSocket：JSON 控制/状态 + 二进制帧
               │ HTTP：静态资源 + 可选 REST 文件接口
               ▼
┌────────────────────────────────────┐
│        Minecraft 模组（Java）       │
│                                    │
│  WebEditorServer                   │
│  ├── HTTP 静态文件                 │
│  ├── WebSocket 控制/状态           │
│  ├── EditorApi                     │
│  │    ├── 脚本 list/load/save      │
│  │    ├── validate                 │
│  │    ├── registry / autocomplete  │
│  │    ├── schema / preset          │
│  │    └── playback control         │
│  └── FrameStreamer                 │
│       ├── framebuffer 捕获         │
│       ├── 缩放/回读/编码           │
│       └── 二进制帧发送             │
│                                    │
│  CameraManager / EditorBridge      │
│  FlightController（游戏内）         │
└────────────────────────────────────┘
```

---

## 6. 功能分层

### 6.1 WebUI 负责

- 脚本 JSON 编辑；
- 剪辑软件式 UI：中央预览、多轨道时间轴、可折叠/拖拽面板；
- 时间轴、轨道、clip、关键帧；
- 属性面板和触发器条件编辑器；
- 撤销/重做；
- 自动补全 UI；
- 脚本列表、保存/新建；
- 模板/素材库（可拖入时间轴并生成脚本 JSON）；
- 预览画面显示；
- 与 Java 侧的通信。

### 6.2 Java 负责

- Web 服务器 + WebSocket；
- 脚本文件读写；
- 脚本解析与校验；
- 播放控制（`setTime / pushScript / play / pause / stop`）；
- 相机预览渲染；
- 帧捕获与传输；
- MC 注册表/自动补全数据；
- 飞控核心。

---

## 7. 通信协议设计

### 7.1 总体原则

- 消息采用简单 JSON 信封：`{ "type": "...", "data": {...}, "id": "..." }`；
- 请求需要回执：`response` / `error`；
- 事件可以主动推送，不需要 `id`；
- 二进制帧和文本帧可以共存于同一个 WebSocket；
- 只监听 `127.0.0.1`，生产环境必须加本地 token 或 Origin 校验。

### 7.2 控制/状态协议

```text
C→S: { "type": "hello", "data": { "token": "..." } }
S→C: { "type": "hello_ack", "data": { "version": "0.3.6", "capabilities": [...] } }

C→S: { "type": "script.list" }
S→C: { "type": "script.list.result", "data": { "files": [...] } }

C→S: { "type": "script.load", "data": { "path": "chapter1/boss.json" } }
S→C: { "type": "script.loaded", "data": { "path": "...", "doc": { ... } } }

C→S: { "type": "script.save", "data": { "path": "...", "doc": { ... } } }
S→C: { "type": "script.saved", "data": { "path": "..." } }

C→S: { "type": "editor.seek", "data": { "time": 12.5 } }
C→S: { "type": "editor.play" }
C→S: { "type": "editor.pause" }
C→S: { "type": "editor.stop" }

S→C: { "type": "playback.state", "data": { "time": 12.5, "playing": false } }
```

### 7.3 画面传输协议

参考 Selkies，使用二进制帧，首字节表示类型：

```text
[1 byte type]
[2 byte frameId]
[4 byte length]
[payload]
```

类型定义：

```text
0x01 = raw RGBA
0x02 = JPEG
0x03 = H.264（远期）
```

订阅预览：

```json
C→S: {
  "type": "preview.subscribe",
  "data": {
    "width": 640,
    "height": 360,
    "fps": 60,
    "format": "raw_rgba",
    "quality": 0.7
  }
}
```

服务端回复实际可用档位：

```json
S→C: {
  "type": "preview.ready",
  "data": {
    "format": "raw_rgba",
    "width": 640,
    "height": 360,
    "fps": 60
  }
}
```

### 7.4 自动补全 / 注册表数据协议

关键：WebUI 无法直接读取 MC 注册表，必须由 Java 侧查询。

按需查询：

```json
C→S: {
  "type": "registry.query",
  "data": {
    "kind": "item",
    "query": "iron",
    "limit": 20
  }
}
S→C: {
  "type": "registry.result",
  "data": {
    "kind": "item",
    "matches": [
      { "id": "minecraft:iron_ingot", "label": "铁锭" },
      { "id": "minecraft:iron_nugget", "label": "铁粒" }
    ]
  }
}
```

小数据集一次性获取：

```json
C→S: { "type": "registry.get", "data": { "kind": "biome" } }
S→C: { "type": "registry.data", "data": { "kind": "biome", "values": [...] } }
```

### 7.5 数据同步清单

| 数据 | 用途 | 推荐方式 |
|---|---|---|
| 物品/方块/实体/群系/维度/结构/进度 | 自动补全 | `registry.query` / `registry.get` |
| 脚本文件列表 | 打开/保存 | `script.list` / `script.load` / `script.save` |
| 脚本 schema 元数据 | 表单生成、字段提示 | `schema.get` |
| 预设列表及参数 | 预设调用 | `preset.list` |
| 触发器类型/字段 | 条件编辑器 | `trigger.schema` |
| 校验错误 | 保存前反馈 | `script.validate` |
| 播放状态/时间 | 时间轴同步 | `playback.state` 推送 |
| 飞控状态 | Web 显示飞控 HUD | `flight.state` 推送 |
| 相机状态 | 预览/飞控信息 | `camera.state` |

---

## 8. 画面传输：如何做到 60fps

当前 Java 编辑器里的 `PreviewCapture` 只是 GPU 内 FBO 拷贝：

```text
主 RenderTarget → FBO → 纹理 → Java GUI 绘制
```

WebUI 需要变成：

```text
主 RenderTarget (GPU)
  → 缩小 FBO（如 640×360）
  → glReadPixels / MC Screenshot API 回读 CPU
  → 可选编码（raw / JPEG / H.264）
  → WebSocket 二进制帧
  → 浏览器 Canvas
```

### 8.1 第一版推荐：raw RGBA 低分辨率 60fps

- 分辨率：640×360 或 800×450；
- 格式：raw RGBA；
- 无编码开销；
- 60fps 下带宽约 40–70 MB/s，localhost 可承受；
- 浏览器端用 `ImageData` / `putImageData` 绘制到 Canvas。

### 8.2 备选：JPEG / H.264

- JPEG：带宽小，适合高质量低帧率档位；
- H.264：稳定大分辨率 60fps 的正路，但需要编码器/原生库，远期再评估。

### 8.3 性能约束

- 回读必须在渲染线程完成；
- 编码/写网络放到 worker 线程；
- 使用 in-flight 上限，跟不上就丢旧帧；
- 允许格式协商自动降级。

---

## 9. 飞控模式

### 9.1 结论

**飞控核心留在游戏原生环境。**

原因：

- 它是在真实 MC 世界里移动相机；
- 依赖真实地形、实体、碰撞、相机渲染；
- 依赖原版键鼠输入和灵敏度；
- 不是纯脚本生成功能。

### 9.2 推荐交互流程

```text
WebUI：编辑脚本/选关键帧
    ↓
WebUI 发送 flight.enter
    ↓
游戏进入飞控模式，用户切回游戏窗口用键鼠取景
    ↓
退出飞控，游戏把最终相机状态写回关键帧
    ↓
WebUI 收到脚本/关键帧更新
```

### 9.3 飞控协议（第一版）

```json
C→S: { "type": "flight.enter", "data": { "keyframeId": "..." } }
C→S: { "type": "flight.exit",  "data": { "record": true } }
C→S: { "type": "flight.cancel" }
S→C: { "type": "flight.state", "data": { "active": true, "pos": {...}, "yaw": 0, "pitch": 0 } }
```

### 9.4 后续可选增强

- WebUI 远程摇杆/滑杆驱动飞控；
- 通过 WebSocket 发送按键/鼠标增量；
- 这属于增强项，不进入第一版。

---

## 10. 自动补全与数据同步

### 10.1 自动补全覆盖范围

- 物品 ID；
- 方块 ID；
- 实体类型；
- 生物群系；
- 维度；
- 结构；
- 进度；
- 游戏阶段（如果安装了 GameStages）；
- 脚本文件路径；
- 预设名称；
- 触发器类型/枚举字段。

### 10.2 前端缓存策略

- 首次需要某种注册表时，向 Java 请求；
- 前端本地缓存；
- 之后输入前缀优先本地过滤；
- 进入新世界/重载世界时刷新缓存；
- 大注册表不推全量，使用 `registry.query` 服务端搜索。

---

## 11. 技术选型与依赖

### 11.1 Java 后端

| 功能 | 推荐 | 备注 |
|---|---|---|
| HTTP 静态文件 | NanoHTTPD 或 JDK `HttpServer` | 托管 Web UI |
| WebSocket | NanoHTTPD WebSocket 或 Java-WebSocket 1.6.0 | DebugBridge / Meteor 均已验证 |
| JSON | Gson | 简单稳定 |
| 帧捕获 | MC `Screenshot` / `NativeImage` / `RenderTarget` | 按版本封装 |
| JPEG 编码 | JDK `ImageIO` | 第一版够用 |
| 线程 | JDK `ExecutorService` / `CompletableFuture` | 无需额外库 |

### 11.2 前端

| 功能 | 推荐 | 备注 |
|---|---|---|
| 框架 | Vue 3 或 React | 看团队习惯 |
| 状态 | Pinia / Zustand | 参考 Meteor |
| 构建 | Vite | 产物打包进 jar |
| 时间轴 | 自行实现或用轻量库 | 第一版优先自研 |

### 11.3 打包

- Forge：使用 jarJar 或打包依赖；
- Fabric：使用 `include`，类似现在 `mixinextras-fabric`；
- Web 前端产物放进 mod jar 静态资源目录。

---

## 12. 实施阶段（建议顺序）

### Phase 0：协议与原型验证

- 选定 WebSocket 库；
- 搭一个最小 Java 本地服务器；
- 实现静态页面 + WebSocket echo；
- 验证浏览器连接、JSON 协议、断线重连。

### Phase 1：预览画面最小链路

- 实现低分辨率 raw RGBA 帧流；
- 目标 640×360 @ 60fps；
- 前端 Canvas 显示；
- 验证不影响游戏帧率。

### Phase 2：脚本文件与播放控制

- `script.list / load / save`；
- `editor.seek / play / pause / stop`；
- 接入现有 `CameraManager` / `EditorBridge`。

### Phase 3：WebUI 编辑器核心

- 剪辑软件式基础布局：中央预览 + 多轨道时间轴 + 可折叠侧栏/面板；
- 时间轴；
- clip/关键帧编辑；
- 属性面板；
- 撤销/重做；
- 脚本新建/保存；
- 模板/素材库原型（拖拽模板到时间轴生成脚本片段）。

### Phase 4：自动补全 / 注册表 API

- `registry.query` / `registry.get`；
- 前端缓存；
- 物品/方块/实体/群系/维度/结构/进度自动补全。

### Phase 5：飞控接入

- `flight.enter / exit / cancel / state`；
- WebUI 显示飞控状态；
- 游戏内飞控保持不变。

### Phase 6：旧编辑器退役

- 新 WebUI 稳定后逐步移除 Java `EditorScreen` / `TimelineArea` / `LeftPanelArea` / `PreviewArea` 等；
- 保留一个极小的游戏内“打开 Web 编辑器”入口。

### Phase 7：打包、测试、安全

- Forge jarJar / Fabric include；
- localhost 鉴权、路径穿越防护；
- 性能测试；
- 多版本验证。

---

## 13. 验证与测试

1. **帧率**：低分辨率 raw 60fps 能否稳定；
2. **延迟**：从拖动时间轴到画面更新；
3. **正常编辑**：新建、修改、保存、重开脚本；
4. **自动补全**：各注册表查询与缓存；
5. **飞控**：进入、退出、记录、取消；
6. **多版本**：Forge / Fabric、不同 MC 版本；
7. **安全**：仅 localhost、token / Origin 校验；
8. **兼容性**：Sodium / Embeddium / Iris 等渲染模组下预览是否正常。

---

## 14. 风险与开放问题

### 风险

1. 画面回读和编码可能影响游戏帧率；
2. Web UI 与 Java 端编辑逻辑可能产生格式漂移；
3. WebSocket 安全：必须限制 localhost 并加 token；
4. 前端工程引入 Node/Vite 构建链，增加维护成本；
5. H.264/WebRTC 如果要做，会引入原生库和许可证问题；
6. 完全移除旧编辑器可能影响现有用户习惯。

### 开放问题

1. WebUI 是否只跑外部浏览器？还是考虑嵌入窗口？
2. 最终预览分辨率：640×360 还是更高？
3. 60fps 是指 Web UI 交互，还是预览帧流，还是两者？
4. 是否保留旧游戏内编辑器作为过渡？
5. 前端技术栈：Vue 3 / React / 原生 JS？
6. 编辑核心放前端还是 Java？本计划倾向“前端全权负责编辑，Java 只做后端”。

---

## 15. 待确认决策

- [ ] 目标版本是否确定为 0.3.6；
- [ ] WebUI 运行形态：外部浏览器 / 游戏内窗口；
- [ ] 前端技术栈；
- [ ] 预览分辨率与帧率目标；
- [ ] 旧编辑器过渡期长度；
- [ ] 是否第一版实现 raw RGBA 60fps。

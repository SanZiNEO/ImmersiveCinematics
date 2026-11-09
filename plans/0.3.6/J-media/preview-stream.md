# 编辑器预览编码流（网络 sink）

> 状态：已实现（J-3，2026-11-09）
> 范围：捕获帧的编码、每会话有界队列、PTS、关键帧、慢客户端丢帧；只负责本地网络输出
> 关联：`capture.md`（共用捕获层）、`export.md`（同一捕获点的文件 sink）、`../Z-editor/README.md` §8.3.1

## 一、摘要

预览消费者从共用捕获层取帧，编码成 H.264（ffmpeg 不可用时退回原始 RGBA），按会话有界队列发送；每帧带 PTS，前端按时间抖动缓冲；慢客户端丢最旧帧并计数，不阻塞其他会话。**不自动降分辨率/帧率**（用户 2026-11-09 裁决）：慢只表现为丢帧，尺寸/帧率由编辑器显式配置。

## 二、为什么做

### 2.1 问题

- 现有预览只发原始 RGBA，720p@60fps 约 221 MB/s。
- 旧帧头只有 16 bit frameId，没有 PTS；前端只能按到达顺序硬播。
- `broadcastBinary` 串行同步写 session，慢客户端会阻塞整条流。

### 2.2 目标

- 预览走编码帧，带宽随分辨率/帧率可控；分辨率与帧率可配。
- 每帧带 PTS，前端按时间抖动缓冲。
- 每会话独立有界队列；慢客户端丢帧，不影响其他会话。
- 新会话从关键帧开始；暂停时停止编码，恢复时重新取关键帧。

### 2.3 非目标

- 不做捕获与尺寸控制（归 `capture.md`）。
- 不做远程媒体文件传输（本机通信；`transport.md` 标不做）。
- 不做直播/推流协议（HLS/DASH/RTMP）。
- 不把音频混进帧流。
- 不做慢客户端自动降级（只丢帧 + 计数）。

## 三、方案

### 3.1 输入

- 从共用捕获层取帧；捕获点固定 `hud`（HUD 后、编辑器 UI 前，见 `capture.md` §3.1）；采样策略为“跟随渲染帧、允许丢帧”。
- 尺寸三档：`original`（默认，跟随窗口 framebuffer，窗口 resize 重建捕获会话）/ `720p`（1280×720）/ `1080p`（1920×1080），由 `editor.previewConfig` 下发。
- 无 ffmpeg 时 `original` 档自动封顶 1280×720（避免原始 RGBA 带宽失控）。
- WebUI 是本地通信（本机浏览器），不做远程传输。

### 3.2 编码

- 首选 H.264（外部 ffmpeg 编码），每个 access unit 一条 WebSocket 二进制消息（Annex B，关键帧内联 SPS/PPS）。
- 编码参数：libx264 baseline、level 5.1、`veryfast`、`zerolatency`、CRF 23、GOP = 2s、无 B 帧、`aud=1`。
- **ffmpeg 不可用或编码器启动/运行失败时，该会话退回原始 RGBA**：功能可用、带宽高；不引入 JPEG/MJPEG。
- 编码器按会话隔离，不跨会话共享编码状态。
- 编码在工作线程（stdin 写线程 + stdout 读线程 + stderr 排空线程）；渲染线程只提交捕获帧。
- 回退是运行期能力选择，不改协议语义：前端按帧头 `type` 区分「编码流 / 原始帧」两种负载。

### 3.3 会话与背压

- 每个 WebSocket 会话一个有界发送队列（容量 16 条消息）；队列满时丢最旧帧并计数，文本消息不丢（满时先丢帧）。
- 每会话独立 `IC-WSSender` 发送线程，慢客户端只阻塞自己的线程；**不自动降分辨率/帧率**。
- 新会话/恢复播放时先发关键帧；关键帧间隔可配（默认 2s）。
- 发送队列、编码器输入队列、捕获缓冲池三处丢帧分别计数，`preview.stats` 每秒推送。

### 3.4 暂停与恢复

- 预览暂停 = 编辑器播放已激活且 `editorPaused`；飞控模式与普通游戏画面持续出流。
- 暂停时关闭全部会话编码器（不编码）；恢复时重建编码器，首帧即 IDR。
- `editor.seek` / `editor.pushScript` / `editor.setCamera` 在暂停态触发一次补帧刷新（预算 20 帧，实际产出即停），保证拖动播放头可见。
- 会话断开时释放编码器与队列；编码器关闭走常驻 `IC-PreviewEnc-closer` 单线程，不阻塞渲染线程。

## 四、技术细节

### 4.1 核心模型

| 对象 | 职责 |
|---|---|
| 预览编码器 | 捕获帧 → 编码分片；每会话一份 |
| 会话队列 | 有界发送队列、丢帧计数、文本优先 |
| 帧头 | 18 字节：type/flags/frameId/PTS/宽/高 |

### 4.2 契约

- **创建**：会话建立时创建队列与编码器（懒启动）；首帧必须是关键帧。
- **销毁**：会话断开时释放编码器与队列；不留下编码进程。
- **背压**：队列有界；满则丢最旧帧并计数，不无界增长。
- **降级**：不自动降档；作者通过 `editor.previewConfig` 显式切换档位/帧率。
- **线程**：编码在工作线程；渲染线程不等待网络写入。
- **暂停**：暂停不编码；恢复/seek 刷新强制新编码器首帧 IDR。
- **PTS**：相对该会话/编码器首帧的微秒数；跨编码器重建时会话级偏移保证单调。

### 4.3 数据形态

- 帧头（18B，大端）：
  - 0 `type` u8：1 = RGBA8，2 = H.264 Annex B access unit
  - 1 `flags` u8：bit0 = 关键帧
  - 2..5 `frameId` u32
  - 6..13 `ptsMicros` u64（相对首帧）
  - 14..15 `width` u16，16..17 `height` u16
  - 18.. payload
- RGBA payload = `width*height*4`（自顶向下）；H.264 payload = 一个完整 AU。
- 队列按消息条数计数（容量 16），不按字节。

### 4.4 边界

- 不改脚本字段；预览是编辑器/运行时通道。
- 不传原始 RGBA 给跨机客户端；WebUI 本机允许原始 RGBA 回退。
- 不与远程媒体分片混用：预览是流，文件分发不做（见 `transport.md`）。
- 不负责捕获尺寸与像素格式（归 `capture.md`）。
- ffmpeg 只从 `<gameDir>/ffmpeg/ffmpeg.exe` 解析，不读 PATH、不做配置项。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 继续发原始 RGBA | 不改协议 | 720p@60fps 约 221 MB/s | 编码 + PTS + 每会话背压 |
| 跨机实时传帧 | 服务端编码每帧下发 | 带宽/延迟/服务端 CPU 不可控 | 本机通信；远程分片不做 |
| 无界发送队列 | 尽量不丢帧 | 慢客户端拖死内存与其他会话 | 有界队列 + 丢帧 |
| 自动降分辨率/帧率 | 按丢帧率降档 | 观感突变、作者误判 | 用户裁决：只丢帧计数，档位由编辑器显式配置 |

**本方案的缺点**：

- 预览编码增加 ffmpeg 进程与前端解码依赖。
- 无 ffmpeg 时原始 RGBA 回退的带宽仍明显高于 H.264（已封顶 720p）。
- 丢帧会改变预览观感，作者可能误判画面。

## 六、实现结果

状态：已实现（2026-11-09，J-3）。

- 协议：`webui/PreviewProtocol`（18B 头，pack 复制负载、parse 全量校验）；`webui/H264AuSplitter`（AUD 优先、无 AUD 按 VCL 连续性切分，关键帧 = 含 NAL type 5）。
- 编码：`webui/PreviewEncoder`（每会话一个 ffmpeg 进程，输入队列容量 1，PTS 按输入帧 FIFO 配对，frameId 原样配对；失败只置 `isFailed()`，不抛异常；`close()` 幂等）。命令行固定 `-profile:v baseline -level 5.1 -crf 23 -g fps*2 -x264-params aud=1`。
- 分发：`webui/PreviewStreamer`（单例；会话 map；每会话编码器/RGBA 回退；按 `1000/fps` 节流；暂停/恢复；`requestFrame()` 暂停补帧；`preview.stats` 每秒推送 + 一行日志）。
- 会话发送：`webui/OutboundQueue`（容量 16，帧可丢、文本优先）+ `WebSocketSession` 单 `IC-WSSender` 线程；`broadcastBinary`/`sendBinary`/`WebFrameStreamer` 已删除。
- 捕获：`FrameCapture` 3 缓冲池 + `CapturedFrame` 引用计数（retain/release），池空丢帧计数；捕获尺寸由 `PreviewConfig.captureWidth/Height` 决定，无 ffmpeg + original 封顶 720p。
- 配置：`editor.previewConfig`（mode/fps/keyframeIntervalSeconds/encoder）→ `editor.previewConfig.result`（回执实际捕获尺寸）；`editor.seek/pushScript/setCamera` 触发 `requestFrame()`。
- 前端（editor 分支）：18B 头解析、WebCodecs H.264（`avc1.42E033`，Annex B）、RGBA 回退、PTS 抖动缓冲 100ms、分辨率/帧率下拉、`preview.stats` 状态栏。
- 验证：
  - 纯 Java 探针：协议/AU 切分/队列/三格式 PCM **85/85 PASS**；真实编码器 **15/15 PASS**（含 2560×1392@60，确认 level 5.1）。
  - 实机（Fabric）：H.264 2544×1353 约 45fps、720p 54.86fps、1080p 50.53fps，PTS 单调、关键帧正常；暂停 0 帧、暂停 seek 补 4 帧首帧关键帧、恢复首帧关键帧。
  - 限速对照：慢端（200ms/帧）单独跑时服务端 `dropped` 223→439/4s（约 54/s）；快端单独 54.86fps vs 双会话并发 54.30fps（差 0.56fps，<1%）。
  - 无 ffmpeg：见下方验收记录（回退 RGBA 1280×720）。
  - 20 次 F9 开关：ffmpeg 进程 0，`IC-WSSender/Reader/PreviewEnc` 线程 0，仅常驻 `IC-PreviewEnc-closer`。
  - Electron 实跑：WebCodecs 解码/绘制 161 帧、canvas 2544×1352、中心像素非黑、无解码错误。

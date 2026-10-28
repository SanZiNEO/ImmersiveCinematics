# 视频播放 / 传输（运行时媒体能力）

> 状态：未实现
> 范围：运行时视频解码与播放、预览编码流、媒体分片传输；不新增脚本字段；视频导出只引用参考结论，不在本文实现
> 关联：`track-overlay-letterbox.md`、`runtime-evaluation.md`、`F-playback/multi-camera-rendering.md`、`F-playback/f5-script-packet-limit.md`、`docs/modules/editor.md`；参考 `example/bbs-mod-1.20.1/`、`example/ReplayMod-stable/`

## 一、摘要

当前仓库没有视频解码/编码能力，也没有服务端到客户端的媒体传输通道。预览通道只发 720p 原始 RGBA（约 221 MB/s @60fps），音频轨只解本地 OGG/WAV 到内存 PCM，OVERLAY 只有 fade/image/subtitle 三类图层。

本文把视频作为**运行时底层能力**接入，不新增脚本字段：

- 播放：`layer_type=image` 的 `path` 支持视频扩展名；运行时按扩展名选 `VideoLayer`，复用现有的 `x/y`、`scale`、`source`、`opacity`、`z_index`。视频时间取片段局部时间，跟随脚本播放/暂停/seek。
- 传输：本地编辑器预览改为“编码帧 + PTS + 每会话有界队列”；远程客户端走“媒体文件分片拉取 + 本地缓存”，不实时传原始帧。
- 解码/编码：外部 ffmpeg 进程，rawvideo 管道；不做 JNI、不打包 ffmpeg、不内嵌解码器。
- 失败：缺 ffmpeg、坏文件、解码失败、网络断开都回退错误纹理/最后一帧并限频记日志，不崩客户端。

参考实现只解决“导出/编码”，不解决“播放/传输”：BBS 是 MIT，ReplayMod 是 GPLv3；两者都只有录制/导出，没有视频文件播放，也没有媒体传输。本文只借鉴它们的进程模型、队列/背压和失败分层，不复制 GPLv3 代码。

## 二、为什么做

### 2.1 问题

- **没有解码器**：全仓没有 ffmpeg/JavaCV/VLC/JCodec 依赖；`TextureManager` 只加载 PNG/`.mcmeta`，`AudioReader` 只接受 WAV/OGG。视频文件无法进入画面。
- **没有视频图层**：OVERLAY 只有 `fade`/`image`/`subtitle`；`ImageLayer` 只处理静态图和 GIF。GIF 还是整段拆帧进内存，不能承载长视频。
- **预览流不可用于远程/多客户端**：`WebFrameStreamer` 只发原始 RGBA，720p@60fps 约 221 MB/s；帧头只有 16 bit frameId，没有 PTS；`broadcastBinary` 串行同步写 session，慢客户端会阻塞整条流。
- **没有媒体传输**：`ResourcePath` 只解析本地文件；MC 自定义包自设 1 MiB 压缩上限；没有分片、请求/拉取、缓存、校验、超时回收。
- **音频与视频脱节**：视频容器里的音轨不能进入现有 AUDIO 轨；导出时也没有确定性混音。
- **参考实现帮不上播放**：BBS 的 `VideoRecorder` 是“GPU 帧 → ffmpeg stdin”，没有解码/播放；ReplayMod 的 `VideoRenderer` 也是导出，且 GPLv3 不能拷代码。

### 2.2 目标

- 本地视频文件能在 OVERLAY 中播放，画面跟随片段局部时间；暂停、seek、循环按脚本语义走。
- 预览流有编码、PTS、每会话背压和丢帧；慢客户端不拖死其他会话。
- 远程客户端能按需拉取媒体文件，分片校验、缓存、断点续传；不把整段视频塞进一个包。
- 缺 ffmpeg / 坏文件 / 解码失败 / 网络断开不崩客户端，有明确的错误态和资源回收。
- **不改脚本字段**：不新增 `layer_type`、不新增视频专用字段；`path` 的扩展名决定实现。

### 2.3 非目标

- 不做视频剪辑/转码/特效 UI；编辑器仍是脚本生成器。
- 不内嵌视频解码器、不把 ffmpeg 打包进模组 jar；只调用外部可执行文件。
- v1 不支持 HLS/DASH/RTMP 等直播流；先本地文件，URL 下载后本地播放。
- 不自动播放视频容器内的音轨；音频仍走 AUDIO 轨，必要时导入时抽成 WAV/OGG。
- 不实现视频导出；导出链路只复用本文的 ffmpeg 进程/队列结论，另立任务。
- 不把原始 RGBA 当远程传输格式；远程只传文件分片或编码流。

## 三、方案

### 3.1 作者看到的样子

- OVERLAY 仍然写 `layer_type: "image"`，`path` 指向 `resource/` 下的 `.mp4` / `.webm` / `.mkv`。现有 `x/y`、`anchor_*`、`scale_*`、`source`、`opacity`、`z_index` 全部照旧。
- 视频按片段局部时间播放：片段暂停/seek/循环时，视频跟着停/跳/回卷；不跟全局墙钟。
- 视频没有音轨播放；需要声音时另建 AUDIO 片段，或在导入时把视频音轨抽成 OGG/WAV。
- 编辑器文件选择器把视频扩展名纳入 `image` 资源；选中后显示分辨率/时长/帧率（ffprobe 或 ffmpeg 探测）。
- 缺 ffmpeg 时显示错误纹理和一行可定位的提示；不阻止脚本加载，不崩游戏。

### 3.2 播放链路

1. 解析 `path`，按扩展名判定为视频；本地文件走 `ResourcePath`，远程文件先落缓存再走同一路径。
2. 工作线程探测元数据：宽高、帧率、时长、像素格式；结果缓存，避免每次播放重新探测。
3. 启动 ffmpeg 解码进程：输出 `rawvideo`、`rgba`、固定 `width*height*4` 帧；音频 `-an`，音轨交给 AUDIO 轨。
4. 解码线程按帧读取，写入容量 2–4 的有界队列；队列满时阻塞解码线程，不阻塞渲染线程。
5. 渲染线程按片段局部时间取“目标帧”：`frameIndex = floor(localTime * fps)`；丢弃过期帧，只上传最新帧；没有新帧时保留上一帧。
6. 上传纹理：预分配两张 RGBA8 `DynamicTexture`，`NativeImage.copyFrom` + `upload`，双缓冲交换；只在渲染线程调用。
7. seek：停止当前进程，带超时 join，清空队列，按目标时间重启；重启期间显示最后一帧或错误纹理。
8. 结束/关闭：停进程、关管道、清队列、释放纹理；重复 close 幂等。

### 3.3 预览传输

- 本地编辑器预览不再发原始 RGBA。首选 H.264（ffmpeg 编码）分片；ffmpeg 不可用时退回 JPEG/MJPEG。
- 帧头带 PTS（毫秒或微秒）和 frameId；前端按 PTS 做抖动缓冲，不按到达顺序硬播。
- 每个 WebSocket 会话一个**有界发送队列**；队列满时丢最旧帧并计数；慢客户端降级为低分辨率/低帧率，不阻塞其他会话。
- 新会话加入时先发关键帧；关键帧间隔可配；预览暂停时停止编码，恢复时从当前播放头重新取关键帧。
- 音频不混进帧流；预览需要声音时走第二条音频通道，或前端本地播放脚本音频。

### 3.4 媒体传输

远程客户端播放视频时，传“文件分片”，不传实时帧：

1. 客户端按 `mediaId` 请求元数据（大小、SHA-256、分片大小、总分片数）。
2. 客户端按需拉取分片；服务端从允许的资源根读取，按 16–64 KiB 切片，带 `mediaId + seq + total + crc32`。
3. 客户端写临时文件，校验分片 CRC 和整文件 SHA-256；完成后原子改名为缓存文件。
4. 播放时从缓存文件走本地解码；缺片/校验失败只重拉该片，不重传整段。
5. 缓存按 `mediaId + hash` 分目录，设总大小上限和 LRU 淘汰；断线/退出释放句柄。
6. 服务端只允许白名单扩展名和资源根内的相对路径；拒绝 `..`、绝对路径、符号链接逃逸；限制单文件大小和并发请求数。

### 3.5 失败与降级

| 场景 | 行为 |
|---|---|
| ffmpeg 不存在/不可执行 | 启动探测失败，图层显示错误纹理；限频日志；不崩 |
| 文件不存在/扩展名不支持 | 加载期错误纹理；validator 只做扩展名/路径检查，不要求 ffmpeg 在场 |
| 解码中途失败/进程退出 | 停进程、清队列、显示最后一帧或错误纹理；记录退出码和 stderr 尾部 |
| seek 超时 | 强制结束进程，回退最后一帧；下一次 seek 重新启动 |
| 预览客户端慢 | 丢最旧帧、计数、降级；不阻塞其他会话 |
| 分片校验失败/超时 | 只重拉该片；超过重试次数标记媒体不可用，回退错误纹理 |
| 关闭/退出 | 停进程、关管道、释放纹理/缓存；不留下僵尸进程 |

## 四、技术细节

### 4.1 核心模型

| 对象 | 职责 |
|---|---|
| `VideoSource` | 本地路径或缓存文件、扩展名、元数据（宽高/帧率/时长/像素格式） |
| `VideoDecoder` | 一个 ffmpeg 进程、stdin 帧读取、有界队列、seek/close；每个活跃视频图层一份 |
| `VideoFrame` | RGBA8 字节、宽高、PTS/frameIndex；队列元素 |
| `FrameTexture` | 两张预分配 `DynamicTexture`，渲染线程上传/交换；close 释放 |
| `VideoLayer` | 实现现有 `OverlayLayer`；`setTime(localTime)` 选帧，`render` blit，`close` 回收 |
| `PreviewEncoder` | 原始帧 → ffmpeg 编码 → WebSocket 分片；每流一份 |
| `MediaAsset` / `MediaChunk` / `MediaCache` | 远程媒体元数据、分片、客户端缓存与校验 |

`layer_type=image` 仍由 `OverlayTrackPlayer` 创建图层；`path` 是视频扩展名时内部创建 `VideoLayer`，对上层暴露同一套 `OverlayLayer` 接口。

### 4.2 契约

- **创建**：`OverlayTrackPlayer` 在 clip 进入活跃窗口时创建 `VideoLayer`；`VideoDecoder` 懒启动，首帧请求时探测/起进程。
- **销毁**：图层移除、clip 结束、脚本停止、世界退出都调用 `close`；`close` 幂等，先停解码线程再停进程，最后释放纹理。
- **线程**：解码线程只读进程/写队列；渲染线程只取最新帧/上传纹理；网络线程只收发分片；三者不共享可变帧缓冲。
- **背压**：解码队列 2–4 帧；预览每会话队列有界；队列满丢旧帧并计数，绝不无界增长。
- **seek**：杀进程 + 清队列 + 重启；不做“从当前位置快进解码”。seek 期间渲染线程继续画最后一帧。
- **所有权**：一个 `VideoLayer` 一个解码进程；不跨图层共享进程；预览编码器按流/会话隔离。
- **音频**：解码器 `-an`；视频音轨不进入播放链路。需要声音时由 AUDIO 轨独立播放。

### 4.3 数据形态

- 解码帧：RGBA8，`width * height * 4` 字节，行序与 ffmpeg rawvideo 一致；上传前按需翻转（与现有 `WebFrameStreamer` 的翻转逻辑统一）。
- 帧时间：优先用 ffmpeg 输出 PTS；没有 PTS 时按 `frameIndex / fps` 回退。脚本侧只认片段局部时间。
- 纹理：RGBA8、`DynamicTexture`；双缓冲避免上传时读到半帧。
- 分片：`mediaId`（UTF-8，长度前缀）、`seq`（VarInt）、`total`（VarInt）、`crc32`（int）、`bytes`；单片刻度 16–64 KiB。
- 缓存键：`mediaId + sha256`；缓存目录按前两位分桶；元数据单独 JSON，原子写。
- 路径：只允许资源根内相对路径；扩展名白名单 `.mp4`/`.webm`/`.mkv`；大小上限按配置。

### 4.4 边界

- **不改脚本字段**：不新增 `video` 类型、不新增 `path` 之外的视频字段；`layer_type=image` + 扩展名是唯一入口。
- **不改主 framebuffer**：不学 ReplayMod 的 `resizeMainWindow`；视频只作为 OVERLAY 图层，走现有 GUI 合成，不劫持窗口尺寸。
- **不碰相机/lane**：视频图层不参与 lane 渲染、不改变相机状态、不影响 FBO 合成。
- **不把原始帧当远程格式**：远程只传文件分片或编码流；原始 RGBA 只允许本机预览回退。
- **不内嵌解码器**：ffmpeg 是外部进程；模组只负责发现、启动、喂参数、收尸。
- **不做导出**：导出复用 ffmpeg 进程/队列模式，但帧源、音频混音、输出校验另立文档。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 内嵌 ffmpeg / JNI / JavaCV | 把解码器打进模组 | 体积、natives、平台差异、崩溃面、许可证 | 外部进程零打包、隔离崩溃、跨平台简单 |
| 新增 `layer_type=video` | 显式视频类型 | 改 schema/validator/编辑器枚举；用户明确不想因 ffmpeg 改字段 | 复用 `image` + `path` 扩展名，零字段变更 |
| 渲染线程直接解码 | 每帧读进程/上传 | 渲染线程被 IO/解码阻塞，帧率抖动 | 独立解码线程 + 有界队列 |
| 预览继续发原始 RGBA | 不改协议 | 720p@60fps 约 221 MB/s，远程不可用 | 编码 + PTS + 每会话背压 |
| 远程实时传帧 | 服务端编码每帧下发 | 带宽/延迟/服务端 CPU 不可控，弱网必炸 | 传文件分片，客户端本地解码 |
| 复制 BBS/Replay 代码 | 直接搬 | BBS MIT 可但仍有 `Unsafe`/旧 API；Replay GPLv3 传染 | 只借鉴架构，重写实现 |
| 视频音轨跟画面一起解 | 单进程解音视频 | 音画同步、暂停、seek、音量/衰减都要重做 | 音频继续走 AUDIO 轨，视频 `-an` |

**本方案的缺点**：

- 依赖用户安装 ffmpeg；缺 ffmpeg 时只能错误纹理，不能“开箱即用”。
- `layer_type=image` 承载视频是命名债；编辑器/validator 需要按扩展名分流，读代码的人容易误判。
- seek 靠重启进程，延迟和首帧时间取决于文件/关键帧间隔；长 GOP 视频体验差。
- 视频音轨不自动播放；作者要额外建 AUDIO 片段，音画对齐靠脚本时间。
- v1 只支持本地文件；URL/直播流要等下载/缓存链路稳定后再开。
- 预览编码增加 ffmpeg 进程和前端解码依赖；MJPEG 回退的带宽仍明显高于 H.264。
- BBS/Replay 都没有播放/传输实现，本文的播放链路没有同构参考，需要实机调。

## 六、实现结果

状态：未实现

### 6.1 参考实现结论

| 参考 | 许可证 | 实际能力 | 可借鉴 | 不能提供 |
|---|---|---|---|---|
| BBS `example/bbs-mod-1.20.1` | MIT | 录制/导出；mp4 仅作音频导入；无视频播放、无媒体传输 | ffmpeg 进程、双 PBO、rawvideo stdin、离线混音、URL 缓存/错误纹理 | 视频解码/播放、媒体分片、音画同步 |
| ReplayMod `example/ReplayMod-stable` | GPLv3 | 导出/编码；无音频、无播放、无传输 | 四段管线、`ArrayBlockingQueue(2)` 背压、ffmpeg stdin、失败分层、日志 tee | 任何 Java 代码（GPLv3）；主 framebuffer 劫持与 lane FBO 不兼容 |

关键证据：

- BBS `VideoRecorder.java:252-291`：`glGetTexImage` → PBO → `channel.write`，写 ffmpeg stdin；`AudioRenderer.java:101-169` 离线混音 WAV；`FFMpegUtils.java:84-113` 外调进程。没有解码/播放类。
- ReplayMod `FFmpegWriter.java:43-79`：`ProcessBuilder` + rawvideo stdin；`Pipeline.java:45-91` 有界队列 + 工作线程；`VideoRenderer.java:207-273` 导出主循环；`RenderSettings.java:63-116` 预设。没有音频/播放/传输。
- 本仓 `WebFrameCapture.java:35-81` 固定 1280×720 原始 RGBA；`WebFrameStreamer.java:43-101` 无编码/无 PTS/16 bit frameId；`ImageLayer.java:69` 只 blit；`GifAnimation.java:38` 是唯一逐帧上传先例；`S2CPlayScriptPacket.java:34-36` 是现有大块传输上限。

### 6.2 未验证 / 待定

- ffmpeg 解码输出的 PTS 精度与 `-r`/`-fps_mode` 组合在不同容器下的行为。
- 视频纹理上传在 Iris/Oculus 光影开启时是否与 GUI 合成冲突。
- `DynamicTexture` 双缓冲在 1.20.1 的纹理绑定/上传线程约束。
- 远程分片在 Fabric/Forge 自定义包上的实际单包上限与吞吐。
- 预览 H.264 在 Electron 前端的解码/首帧延迟；MJPEG 回退的实际带宽。
- 视频 seek 在长 GOP/HEVC/AV1 文件上的首帧时间。
- BBS/Replay 参考实现的许可证文本已在各自 `LICENSE.md` 核对；若后续复制任何 MIT 片段，需在文件头保留版权与许可声明。

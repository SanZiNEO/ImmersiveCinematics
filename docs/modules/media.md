# media（媒体：外部工具、视频覆盖、音频解码、预览捕获）

对应路径：
- 媒体核心：`common/src/main/java/com/immersivecinematics/immersive_cinematics/media/`
  - `video/`：`Ffmpeg`、`VideoFiles`、`VideoProbe`、`VideoDecoder`、`VideoFrameTexture`、`VideoLayer`、`VideoAudioTrack`、`VideoAudioInstance`、`ProcessPcmStream`、`VideoMetadata`、`DecodedFrame`
  - `audio/`：`ExternalPcmDecode`
  - `capture/`：`CaptureHub`、`CapturedFrame`
  - `ErrorTexture`：全工程共享的坏素材纹理
- 编辑器预览侧：`common/src/main/java/com/immersivecinematics/immersive_cinematics/webui/`（`PreviewConfig`、`PreviewStreamer`、`PreviewEncoder`、`H264AuSplitter`、`PreviewProtocol`）
- 捕获挂点：`common/src/main/java/com/immersivecinematics/immersive_cinematics/mixin/GameRendererMixin.java`（原版 `Gui.render` 之后调用 `CaptureHub.onHudRendered`）

> **验证边界**：本文按当前源码快照（HEAD `4b49c68`）整理，只写源码可核实的行为；**未做游戏内验收**——没有实机播放、真实 ffmpeg/ffprobe 进程、音频输出、多客户端同时预览或前端解码的联调记录。脚本字段口径见 [SCRIPT_FORMAT](../SCRIPT_FORMAT.md) §4.3 / §4.6；编辑器服务端消息与预览屏见 [editor](./editor.md)。

## 一、外部工具定位（`Ffmpeg`）

- ✅ **固定目录**：只认 `<gameDir>/ffmpeg/`。Windows 文件名 `ffmpeg.exe` / `ffprobe.exe`，其他平台为 `ffmpeg` / `ffprobe`（无后缀）。`gameDir` 取客户端 `Minecraft.getInstance().gameDirectory`；**不读 `PATH`、没有配置项、模组不下载**（`Ffmpeg`）。
- ✅ **缓存**：按 `gameDir` 缓存解析结果（含“文件不存在”的负结果），目录不变只读字段；目录变化或上次没有客户端目录时重查；专用服务器/早期初始化拿不到 `Minecraft` 类时返回 `null`。工具应在启动客户端前放好：首次查找失败后才补入文件，不会因目录内容变化自动重查，需重启客户端（`Ffmpeg.resolveIfNeeded`、`Ffmpeg.gameDir`）。
- ✅ **能力分档**：`available()` = `ffmpeg` 与 `ffprobe` 都存在；`encoderAvailable()` = 只需 `ffmpeg`。视频探测/解码要求两者齐全；外部音频解码和预览 H.264 编码只要求 `ffmpeg`（`Ffmpeg`）。
- ✅ **只做存在性检查**：`Files.isRegularFile` 命中即返回绝对路径，不校验版本、架构或可执行位；进程启动/退出失败由各调用方按自己的降级路径处理（`Ffmpeg.resolve`）。

## 二、视频覆盖层（OVERLAY `layer_type=image`）

- ✅ **不新增轨道/字段**：内置轨道为 CAMERA / LETTERBOX / AUDIO / EVENT / MOD_EVENT / OVERLAY / ADJUST，没有 `VIDEO` 轨，也没有 `layer_type=video`。视频沿用 OVERLAY 的 `layer_type=image` + `path`，由 `VideoFiles.isVideo` 只按扩展名分流：`mp4` / `webm` / `mkv`（大小写不敏感）；`avi` / `mov` / `m4v` 等其他容器不按视频处理，`image` 的校验白名单也不接受（`TrackTypeId`、`VideoFiles`、`ObjectValidator`、`OverlayTrackPlayer.createObjectLayer`）。
- ✅ **校验口径**：`layer_type=image` 的 `path` 允许 `.png` / `.gif` / `.mp4` / `.webm` / `.mkv`；扩展名不在白名单时 `ScriptValidator` 报错。扩展名识别不检查文件存在性，也不保证可解码（`ObjectValidator`、`VideoFiles`）。
- ✅ **分流位置**：`OverlayTrackPlayer.createObjectLayer` 的 `image` 分支里，`VideoFiles.isVideo(path)` 为真则建 `VideoLayer`，否则建 `ImageLayer`；视频层不是 `ImageLayer` 子类，但 `applyObjectParams` 对它写同一组 `opacity` / `x` / `y` / `anchor_x/y` / `scale_x/y` / `source` 参数（`OverlayTrackPlayer`）。
- ✅ **几何与素材**：位置、锚点、缩放、`base_resolution`、`z_index` 使用同一套 `k` 与锚点公式（`ImageGeometry.compute`），但**几何输入不同**：`ImageLayer` 把 `source` 裁切后的 `srcW/srcH` 传给 `compute`，`VideoLayer` 传完整素材尺寸 `materialWidth/materialHeight`，裁切只改 blit 的采样区域与 UV。因此 `source` 非整幅时，不能把两者的上屏矩形当成同一结果；视频层的缩放基准始终是完整视频分辨率（`ImageLayer.render`、`VideoLayer.render`）。
- ✅ **懒启动**：首次 `render` 且 `opacity > 0.001` 才探测元数据、建帧纹理/解码器/音轨并按当前片段局部时间起播；不可见时不初始化。探测为同步阻塞调用，每个 ffprobe 请求的超时为 5 秒，视频与音频探测可分别发起请求；因此首次可见帧可能等待探测与进程启动（`VideoLayer.ensureStarted`、`VideoProbe`）。
- ✅ **ffprobe 探测**：`-select_streams v:0` 取首条视频流的 `width` / `height` / `avg_frame_rate` / `r_frame_rate` / `duration`，再用 `format=duration` 兜底时长；分辨率解析不出即失败；帧率依次回落 `avg_frame_rate` → `r_frame_rate` → 25；另有 `a:0` 探测是否含音频。结果按 `(绝对路径, 文件大小, 修改时间)` 缓存，文件变化自动重探；失败不写缓存并限频告警（`VideoProbe`、`VideoMetadata`）。
- ✅ **解码**：外部 `ffmpeg` 以 `-f rawvideo -pix_fmt rgba` 从 `pipe:1` 出帧；滤镜为 `fps=<探测帧率>`，仅当解码尺寸与源尺寸不同时追加 `scale=W:H:flags=bilinear`；stderr 单独排空、尾部用于诊断；帧队列容量 3，队列满时解码线程阻塞（背压，不丢帧、不无界吃内存）；管道按 64 KiB 分块读取（`VideoDecoder`）。
- ✅ **解码尺寸**：默认按视频源分辨率解码，最大 4096×2160，超过才等比缩到上限；最小 2×2；不按窗口尺寸裁剪、不放大。显示大小和裁切全部由图层参数决定（`VideoLayer.fit`）。
- ✅ **帧号与 seek**：全局帧号 = `round(起点秒 × fps) + 本轮第 n 帧`；`seek` 停旧进程、清队列、从目标秒重启，`start` 只拉起解码，若已有存活进程则直接返回（幂等）；`poll` / `pollAtOrBefore` 非阻塞取帧。暂停时片段局部时间不变，画面保持当前纹理（`VideoDecoder`）。
- ✅ **时间驱动**：`setTime` 每渲染帧送入片段局部时间；目标帧 = `floor(localTime × fps)`，并按 `duration × fps - 1` 钳到片尾；时间倒退超过 0.05 秒或向前跳超过 1.0 秒才重启解码，正常推进不重启。取不到目标帧时保持上一帧（`VideoLayer.updateFrame`）。
- ✅ **上传**：两张 `DynamicTexture` 轮换，`glTexSubImage2D` 直接覆盖 back 纹理，成功后切成 front；RGBA8、行序自顶向下，上传不翻转（`GuiGraphics.blit` 的 `v=0` 取纹理顶部，正好抵消 GL 原点在左下）；缓存的直接内存暂存区复用（`VideoFrameTexture`）。
- ✅ **失败降级**：缺少 `ffmpeg` 或 `ffprobe` → 视频层不可见、不画占位，只记限频日志；文件缺失/探测失败/初始化异常/解码结束仍无任何帧且时间还在片内 → 画共享 `ErrorTexture`（1920×1080 虚拟素材、16×9 黑/洋红棋盘格），让作者看出素材坏了；错误纹理进程内常驻，不随层关闭释放（`VideoLayer`、`ErrorTexture`）。
- ✅ **生命周期**：`reset()` 只把不透明度归零，不释放资源；`close()` 幂等，依次关闭解码器、帧纹理、视频音轨；共享错误纹理不归本层所有（`VideoLayer`）。

## 三、视频音轨（独立于 AUDIO 轨）

- ✅ **归属**：每个 `VideoLayer` 在懒启动时按 `probed.hasAudio()` 建一个 `VideoAudioTrack`；`setTime` 把同一片段局部时间交给 `syncToTime`，画面与音轨共用唯一时钟。`hasAudio=false` 时所有同步方法为空操作（`VideoLayer`、`VideoAudioTrack`）。
- ✅ **暂停/恢复**：局部时间连续 100ms 没有变化 → 停掉当前 `VideoAudioInstance`；时间重新推进后按当前时间重建。音轨不参与脚本 `volume` / `fade_in` / `fade_out` / `attenuation` / `position_mode` / `file_loop`，也不使用 `AUDIO` 轨的 `CinematicAudioInstance`（`VideoAudioTrack`、`VideoAudioInstance`）。
- ✅ **漂移/seek**：已播实例按“起播时刻 + 墙上时钟”推算期望位置；与画面时间偏差超过 0.25 秒，或画面时间倒退超过 0.05 秒 → 停旧实例并按当前时间重建。外部 `ffmpeg -ss` 是唯一 seek 手段，进程内不跳读（`VideoAudioTrack`）。
- ✅ **实例参数**：固定 `SoundSource.MASTER`、相对听者（`relative = true`）、`Attenuation.NONE`、音量 1.0、音高 1.0；不乘脚本音量/淡入淡出，也不做空间化。循环由上层时间回卷触发重建，实例本身不执行文件循环（`VideoAudioInstance`）。
- ✅ **PCM 流**：`ProcessPcmStream` 以 `-vn -f s16le -ac 2 -ar 48000 pipe:1` 输出 48 kHz / 双声道 / 16 位小端 PCM；构造由视频层在渲染线程发起，`read` 由原版 SoundEngine 的流读取线程调用，`close` 可能由 `SoundManager.stop` 在客户端线程触发（`ProcessPcmStream`）。
- ✅ **失败**：缺 `ffmpeg` 时流直接进入已关闭状态，`read` 返回空缓冲 → 原版 SoundEngine 结束该声音，**画面播放不受影响**；stderr 必须排空，非零退出码记限频日志。`close()` 幂等，先 `destroy()`，进程仍存活立即 `destroyForcibly()`，不等待退出（`ProcessPcmStream`）。

## 四、音频解码（AUDIO 轨）

- ✅ **分流**：`CinematicAudioInstance.decode` 按 `source` 与扩展名决定路径——`source=minecraft` 只从资源包读 OGG；文件源 `.ogg` 用 stb_vorbis、`.wav` 用 JavaSound，两者都在构造期同步解码；`.mp3` / `.aac` / `.flac` / `.m4a` / `.opus` / `.wma` / `.oga` / `.ogx` 走 `ExternalPcmDecode` 的 ffmpeg 整段异步解码；其他扩展名直接抛“不支持的音频格式”（`CinematicAudioInstance`、`ExternalPcmDecode.isSupportedExtension`）。
- ✅ **文件路径**：`source != minecraft` 时 `sound` 相对于 `<gameDir>/immersive_cinematics/resource/` 解析；文件不存在在构造期抛异常（`ResourcePath.resolve`、`CinematicAudioInstance.decode`）。
- ✅ **内置 OGG/WAV**：OGG 全量解码进内存（`stb_vorbis`），WAV 支持 16 位直通与 8 位转 16 位，其他位深抛“不支持的 WAV 位深”；两者保留源采样率/声道，不依赖 ffmpeg（`CinematicAudioInstance.decodeOggFromFile` / `decodeWav`）。
- ✅ **8 种外部白名单**：mp3 / aac / flac / m4a / opus / wma / oga / ogx（大小写不敏感）；白名单外的扩展名不会交给 ffmpeg，避免误判二进制资源（`ExternalPcmDecode.SUPPORTED_EXTENSIONS`）。
- ✅ **外部解码口径**：`decodeAsync` 立即返回 future，daemon 工作线程把整个文件解成 `s16le / 48 kHz / 双声道`，stdout 全量读入内存后组装 `Pcm`；时长 = 帧数 / 48000。代码未设素材时长阈值，但长素材需要存放整段 PCM，不能理解成内存或时长无限（`ExternalPcmDecode`）。
- ✅ **未就绪语义**：外部解码未完成时 `canPlaySound()` 为 false，`play()` / seek 只记 `pendingPlay` / `pendingSeek`；渲染线程下一帧的 `ensurePlaying(localTime, volume)` 在就绪后按当前时间起播。就绪前不会把没有 PCM 的实例交给 SoundEngine（`CinematicAudioInstance`）。
- ✅ **失败行为**：外部白名单格式在构造期就要求 `ffmpeg` 存在，缺失直接抛“缺少 ffmpeg…”；异步解码失败（进程起不来 / 非零退出 / 空输出 / 被中断）置 `failed=true`、`ready=false`，记录 `LOGGER.error` 与限频 `ErrorLog`，**不重试、不伪装静音**；`AudioTrackPlayer` 在活动 clip 上看到 `isFailed()` 后 `cleanup()` 并把该 clip 记入 `failedClips`，后续帧不再重建/重试（`CinematicAudioInstance`、`ExternalPcmDecode`、`AudioTrackPlayer`）。
- ✅ **构造期失败**：缺失文件、格式不支持、内置解码失败、缺 ffmpeg 等异常由 `AudioTrackPlayer.startClipInstance` 捕获，只记一次错误并把该 clip 加入 `failedClips`，后续帧不再重试（`AudioTrackPlayer`）。
- ⚠️ **文件名**：包含非 ASCII 字符时只告警（Windows 下可能无法解码），不阻止加载（`CinematicAudioInstance`）。

## 五、共享捕获（`CaptureHub`）

- ✅ **唯一入口**：`CaptureHub.INSTANCE` 负责主 RenderTarget → 固定规格 FBO → PBO 异步读回；`GameRendererMixin` 在原版 `Gui.render` 之后调用 `onHudRendered`，此时主 framebuffer 已包含世界、lane 合成、后期与 GUI（`CaptureHub`、`GameRendererMixin`）。
- ✅ **按需捕获**：每帧先问所有消费者 `needsFrame`，无人需要时零 GL 调用、零分配；捕获规格取第一个非 null 的 `captureSpec`，规格变化时重建 FBO/PBO/缓冲池并回调消费者 `onCaptureFormatChanged`（`CaptureHub.Consumer`）。
- ✅ **规格校验**：宽高必须是 2..4096 的偶数，fps 1..1000；非法规格跳过本帧并限频告警（`CaptureHub.onHudRendered`）。
- ✅ **FBO 缩放**：主 RenderTarget 用 `GL_LINEAR` blit 到固定规格 RGBA8 FBO，并只写 alpha 通道把全帧 alpha 刷成 1；结束后恢复 READ/DRAW framebuffer、color mask 与 clear color（`CaptureHub.blitToFbo` / `initFbo`）。
- ✅ **PBO 三缓冲**：3 个 `GL_STREAM_READ` PBO 轮转；第 N 帧写入 `pbo[write]`，同时映射并交付 `pbo[(write+1)%3]`（两帧前写入的那份），因此首两帧只写不读、稳态延迟两帧；`CapturedFrame.ptsNanos()` 是该帧写入 PBO 的时刻（`CaptureHub.captureFrame` / `deliver`）。
- ✅ **缓冲池与引用计数**：6 个 `width*height*4` 字节数组随 `CapturedFrame` 交出；消费者必须释放收到的引用，新增持有者时才 `retain()`，计数归零归还缓冲。池空或尺寸与当前规格不符时本帧丢弃并计入 `droppedFrames()`，不阻塞渲染线程（`CaptureHub`、`CapturedFrame`）。
- ✅ **像素契约**：读回后原地翻转行序，交付 RGBA8、自顶向下（第 0 行 = 屏幕顶部）；长度必须等于 `width*height*4`（`CapturedFrame`、`CaptureHub.flipRows`）。
- ✅ **异常隔离**：`onHudRendered` 与每个消费者回调都兜底，异常只记限频日志，不打断渲染帧；`close()` 幂等，释放 FBO/PBO/缓冲池并清空消费者（`CaptureHub`）。

## 六、编辑器预览流（固定 1080p60）

- ✅ **固定参数**：`PreviewConfig.fixed()` 只有 1920×1080@60、2 秒关键帧、允许 H.264；线上名固定 `1080p`。没有 original / 720p 档位与可配帧率；`editor.previewConfig` 忽略请求字段并回执这一组固定值。**60 是捕获规格与发送节流目标，不是实测能稳定 60fps 的承诺**——实际帧率受渲染帧率、编码速度与网络队列影响（`PreviewConfig`、`WebEditorApi.handlePreviewConfig`、`PreviewStreamer`）。
- ✅ **捕获声明**：`PreviewStreamer` 作为 `CaptureHub.Consumer` 声明 `CaptureSpec(1920, 1080, 60)`；只有 WebUI 服务在跑且有客户端时才需要帧。未暂停时每渲染帧采样，按 60fps 在每会话 `submitLocked` 节流；暂停时只放行刷新帧（`PreviewStreamer.needsFrame` / `captureSpec` / `submitLocked`）。
- ✅ **暂停与刷新**：`WebPreviewScreen` 仅在“编辑器播放已激活且暂停、且不在飞控”时调用 `setPaused(true)`；暂停会关闭全部会话编码器，恢复时清回退标志、下一帧重建编码器（首帧 IDR）。`editor.seek` / `pushScript` / `setCamera` / `previewConfig` 会调用 `requestFrame()`；暂停刷新预算 20 帧，直到每个会话真正产出一帧（H.264 以 AU 回调、RGBA 以入队为准）或预算耗尽（`WebPreviewScreen.render`、`PreviewStreamer`）。
- ✅ **每会话 H.264**：每会话独立 `PreviewEncoder`，命令行固定 `libx264`、`baseline`、`level 5.1`、`veryfast`、`zerolatency`、`crf 23`、GOP = `fps × 2` 帧（2 秒）、`aud=1`、`yuv420p`、输出 `h264` Annex B；输入为捕获帧的 RGBA `pipe:0`，输入队列容量 1，满时丢最旧帧；`frameId` 与输出 AU 按 FIFO 配对，PTS 相对本编码器首帧，会话级偏移保证跨编码器单调（`PreviewEncoder`、`PreviewStreamer.onAu`）。
- ✅ **原始 RGBA 回退**：`ffmpeg` 缺失、编码器启动失败或运行期失败时，仅本会话回退 `TYPE_RGBA`。固定配置始终允许编码，没有用户可配的关闭开关；回退帧置 `FLAG_KEYFRAME`，PTS 相对该会话首帧。恢复播放或捕获格式变化时重置回退标志，下一帧可重新尝试编码（`PreviewStreamer.ensureEncoder` / `disableEncoderLocked` / `sendRgba`）。
- ✅ **帧协议**：二进制帧 = 18 字节大端头 + 负载：`type`(1) / `flags`(1) / `frameId`(u32) / `ptsMicros`(u64) / `width`(u16) / `height`(u16)。`TYPE_RGBA=1` 负载为 `width*height*4` 的 RGBA8；`TYPE_H264=2` 负载为一个 Annex B access unit；`FLAG_KEYFRAME=0x01` 仅对 H.264 有意义。`parse` 严格校验头长、type 与负载尺寸，非法输入返回 `null`（`PreviewProtocol`）。
- ✅ **AU 切分**：`H264AuSplitter` 识别 3/4 字节起始码；有 AUD（NAL type 9）时以 AUD 为边界，无 AUD 时在已出现 VCL 后再见 VCL 或 SPS/PPS 开新 AU；`keyframe` = AU 内含 NAL type 5（IDR）；输出统一用四字节起始码重建（`H264AuSplitter`）。
- ✅ **出站队列**：每会话 `OutboundQueue` 容量 16，独立 `IC-WSSender` 线程串行写出；满时先丢最旧帧，只有队列里没有帧时才丢最旧文本；帧与文本的丢弃分别计数，发送侧不阻塞渲染线程（`OutboundQueue`、`WebSocketSession`）。
- ✅ **统计**：每 1 秒向每个会话推送 `preview.stats`，字段 `mode` / `width` / `height` / `fps` / `encoder` / `sent` / `dropped` / `inputDropped` / `encoderDropped`；`width/height` 优先取 CaptureHub 当前捕获尺寸、未捕获时回落配置值，`encoder` 为 `h264` / `rgba` / `off` / `pending`；同时落一行限频统计日志（`PreviewStreamer.tick`）。
- ✅ **多会话生命周期**：WebSocket 升级成功后 `onSessionOpened` 创建独立流状态（编码器、回退标志、PTS 基准、丢帧计数）；会话关闭时 `onSessionClosed` 释放该会话编码器与待发帧；编码器 `close()` 提交给单线程 daemon `IC-PreviewEnc-closer` 串行回收，避免在渲染线程等待进程退出；`WebPreviewScreen.onClose()` 依次关闭 `PreviewStreamer`（注销 CaptureHub 消费者）与 `WebEditorServer`（`PreviewStreamer`、`WebEditorServer`、`WebPreviewScreen`）。
- ✅ **消息面**：`editor.previewConfig.result` 返回固定配置；`playback.state` 由 `WebPreviewScreen` 每 50ms 推送，`preview.stats` 由 `PreviewStreamer` 每 1s 推送；完整消息列表见 [editor](./editor.md)。

## 七、失败行为与降级边界

| 场景 | 行为 | 依据 |
|---|---|---|
| 缺 `ffmpeg`（有或无 `ffprobe`） | 视频层不可见（不占位）；外部白名单音频构造期报错并跳过 clip；预览每会话回退原始 RGBA | `Ffmpeg`、`VideoLayer.ensureStarted`、`CinematicAudioInstance.startExternalDecode`、`PreviewStreamer.ensureEncoder` |
| 有 `ffmpeg`、没有 `ffprobe` | 视频层（含视频音轨）不可见；外部音频可用；预览 H.264 可用 | `VideoLayer.ensureStarted`、`Ffmpeg.available` |
| 视频文件缺失/损坏/探测失败 | 画共享错误纹理（1920×1080 黑/洋红棋盘格） | `VideoLayer.markError`、`ErrorTexture` |
| 视频解码结束仍无帧，且时间还在片内 | 关闭该层资源并画错误纹理；时间已过片尾则不判坏文件 | `VideoLayer.checkDecodeFailure` |
| 音频扩展名不在内置/白名单 | 构造期抛“不支持的音频格式”，clip 只报一次并跳过 | `CinematicAudioInstance.decode`、`AudioTrackPlayer.startClipInstance` |
| 外部音频异步解码失败 | `failed=true`，不重试，clip 跳过；不伪装成正常静音 | `CinematicAudioInstance.onExternalDecoded`、`AudioTrackPlayer` |
| 预览编码器启动/运行失败 | 仅该会话回退 `TYPE_RGBA`，其他会话不受影响 | `PreviewStreamer.ensureEncoder` / `disableEncoderLocked` |
| WebSocket 发送队列满 | 先丢最旧帧（计入 `dropped`），文本优先；不阻塞渲染线程 | `OutboundQueue` |

## 八、未验证边界

- 本文没有做游戏内验收，也没有实测真实 `ffmpeg` / `ffprobe` 版本、各平台可执行文件、音频输出或性能；`VideoDecoder` 注释里的历史吞吐数字不作为本文承诺。`1080p60` 只是固定规格与发送目标，不是本轮实测帧率。
- 前端 `editor/src` 的 H.264 / RGBA 解码与播放实现不在本次核对范围；本文只描述服务端协议与发送行为。
- 多客户端同时预览、断线重连与会话并发只在源码层面核对（每会话独立状态、队列与编码器），没有实机压测记录。
- **资源列表与运行时能力不同**：`ResourceFileService` 的 `kind=image` 当前只列 PNG/GIF，`kind=audio` 只列 OGG/WAV；视频与扩展音频虽可由运行时按脚本路径使用，却不会出现在这两类列表中。`kind=all` 不按扩展名过滤，但本轮没有核实前端是否提供该选择入口；不能把后端解码支持写成前端选择器已完整接入。

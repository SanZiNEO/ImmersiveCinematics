# 媒体（总纲）

> 状态：J-2 已实现（捕获 / 解码 / 视频图层 / 视频音轨）；J-3 已实现（预览编码流 + AUDIO 外部格式）；J-4 导出未实现
> 范围：运行时视频播放、解码链路、共享帧捕获底座、音频解码、编辑器预览编码流、画面导出、失败降级与验收（远程媒体分片不做）
> 关联：`../I-script-core/track-overlay-letterbox.md`（OVERLAY `image` 字段）、`../F-playback/multi-camera-rendering.md`、`../F-playback/cross-dimension-camera.md`、`../F-playback/f5-script-packet-limit.md`、`../Z-editor/README.md` §8.3

## 一、摘要

视频作为运行时素材能力接进模组：作者仍然写 `layer_type: "image"`，`path` 指向视频文件，播放时间跟随片段局部时间。它只依赖 OVERLAY `image` 字段形态与 F8 的视图层。

本页只做索引、范围边界与跨篇契约，不重复各篇正文。

## 二、为什么做

### 2.1 问题

- 全仓没有视频解码能力，`TextureManager` 只加载 PNG，`AudioReader` 只接受 WAV/OGG；视频文件无法进入画面。
- OVERLAY 只有 `fade`/`image`/`subtitle`，`ImageLayer` 只处理静态图与 GIF；GIF 整段拆帧进内存，不能承载长视频。
- 预览流只发原始 RGBA，720p@60fps 约 221 MB/s，没有 PTS、没有背压，慢客户端会拖死整条流。
- 不做远程媒体传输：`ResourcePath` 只解析本地文件；视频随资源目录/整合包分发。
- 视频容器里的音轨与现有 AUDIO 轨脱节；导出只做画面，不导出音频。
- 参考实现只解决导出/编码，不解决播放：BBS 是 MIT，ReplayMod 是 GPLv3；两者都只有录制/导出。

### 2.2 目标

- 本地视频文件能在 OVERLAY 中播放，画面跟随片段局部时间；暂停、seek、循环按脚本语义走。
- 预览流有编码、PTS、每会话背压与丢帧；慢客户端不拖死其他会话。
- 缺 ffmpeg / 坏文件 / 解码失败不崩客户端，有明确错误态与资源回收。
- **不改脚本字段**：不新增 `layer_type`、不新增视频专用字段；`path` 扩展名决定实现。

### 2.3 非目标

- 不做视频剪辑/转码/特效 UI；编辑器仍是脚本生成器。
- 不内嵌解码器、不把 ffmpeg 打包进模组 jar；只调用外部可执行文件。
- v1 不支持 HLS/DASH/RTMP 等直播流；先本地文件。
- 视频容器内的音轨跟随视频播放；AUDIO 轨仍可播放独立音频文件，两者互不替代。
- 视频导出是编辑器模式功能（J-4），不是普通游戏内功能；导出参数由编辑器给。
- 不把原始 RGBA 当跨机传输格式；WebUI 本机允许原始 RGBA 回退。**远程媒体分片不做**（2026-11-08 裁决）。
- **不导出音频**：导出文件为静音视频；声音交给外部工具 / 后续 addon（2026-11-08 裁决）。

## 三、方案

### 3.1 四条跨篇契约

1. **视频时间 = 片段局部时间。** 播放、暂停、seek、循环、morph 两侧都按 `executionLocalTime`；视频不自建时钟，不跟全局墙钟。
2. **预览走编码帧，原始帧只回退。** 本地预览是编码帧 + PTS + 有界队列；原始 RGBA 只允许本机预览在无 ffmpeg 时回退；不做远程媒体分发。
3. **失败一律降级，不崩客户端。** 缺 ffmpeg 时视频素材不显示（有什么显示什么）；坏文件、解码卡住回退最后一帧或错误纹理，限频记日志。
4. **编辑器分两类。** WebUI 是外部编辑器（本机通信，编码流 / 原始 RGBA）；游戏内编辑器是附属模组（同进程，捕获 FBO/纹理直传）。两者共用捕获点 `hud` 与同一套信号语义（`../Z-editor/README.md` §8.3）。

### 3.2 分篇地图

| 文档 | 负责什么 | 一个点 |
|---|---|---|
| [playback.md](playback.md) | 视频图层在 OVERLAY 中播放，时间跟随片段，seek/循环/暂停语义 | 作者能用视频当 `image` 素材 |
| [decode.md](decode.md) | ffmpeg 解码进程、rawvideo 管道、有界队列、PTS、双缓冲纹理上传 | 帧怎么从文件到纹理 |
| [capture.md](capture.md) | 共享帧捕获：捕获点、尺寸/格式、PTS、消费者契约 | 预览 / 导出 / 游戏内编辑器共用哪一层 |
| [audio.md](audio.md) | 视频音轨的处置与独立音频解码；与 AUDIO 轨的边界 | 声音从哪来 |
| [preview-stream.md](preview-stream.md) | 预览编码流：H.264（无 FFmpeg 退回原始 RGBA）、PTS、每会话背压、关键帧 | 预览怎么不拖死自己 |
| [transport.md](transport.md) | **不做**（2026-11-08 裁决：编辑通信与播放均本地） | 历史存档 |
| [export.md](export.md) | 实时录屏导出：按目标 fps 采样、编码、进度/取消（静音） | 怎么出成片 |
| [degrade.md](degrade.md) | 外部依赖与失败分层：缺 ffmpeg、坏文件、解码失败、断线、资源回收 | 坏了怎么办 |
| [acceptance.md](acceptance.md) | 跨篇验收矩阵与真实机器检查项 | 怎么算过 |
| [handover.md](handover.md) | **J 轮开头的遗留验证**（承接 A–I）：B18 ④打断 / ⑤链式、B19 迟滞四象限、C-pause 复验、F 轮失败处理 P1/P2 | 老欠账怎么收 |

### 3.3 交付顺序

1. `capture.md`：✅ J-2 已交付（CPU 读回）；J-3 补尺寸参数化 / 缓冲池；GPU 直连待收尾。
2. `decode.md` + `playback.md`：✅ J-2 已交付（1080p 播放性能待修）。
3. `degrade.md`：✅ J-2 已交付；J-3 补预览丢帧与固定路径口径。
4. `audio.md`：视频音轨 ✅ J-2；AUDIO 轨外部格式 ✅ J-3。
5. `preview-stream.md`：✅ J-3 已交付（编码帧 + PTS + 每会话队列 + 关键帧）。
6. `export.md`：J-4；同一捕获点接文件消费者，静音导出与收尾。
7. `acceptance.md`：每篇完成时补本行，不集中到最后。
8. `transport.md`：**不做**。

## 四、技术细节

### 4.1 边界

- 不消费规则、路径、分支、次数语义；只消费 OVERLAY `image` 字段与 F8 的视图层。
- 不修改脚本格式；`layer_type=image` + `path` 扩展名是唯一入口。
- 不劫持主 framebuffer，不参与 lane 渲染，不改变相机状态。

### 4.2 外部依赖

- ffmpeg / ffprobe 是**可选**外部可执行文件，固定路径 `<gameDir>/ffmpeg/ffmpeg.exe` + `<gameDir>/ffmpeg/ffprobe.exe`（Windows 带 `.exe`；不读 PATH、不做配置项）。**没有它模组照常使用**；缺失时只影响媒体增强能力（视频素材播放、编码预览流、AUDIO 外部格式、导出），核心脚本播放、渲染、触发器、相机均不受影响。安装 ffmpeg 是整合包 / 地图 / 服务器的责任，不是模组的义务。具体降级见 [degrade.md](degrade.md) §3.1 与本文 §7.6。
- 不做远程传输；媒体路径由 `ResourcePath` 解析，视频随资源目录/整合包分发。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 媒体与脚本语义耦合 | 作为脚本语义的一部分 | 验收被视频问题污染；依赖面不同 | 按素材能力独立验收 |
| 新增 `layer_type=video` | 显式视频类型 | 改 schema/validator/编辑器枚举；因 ffmpeg 改字段 | 复用 `image` + `path` 扩展名，零字段变更 |
| 内嵌 ffmpeg / JNI | 解码器进模组 | 体积、natives、平台差异、崩溃面、许可证 | 外部进程零打包、隔离崩溃、跨平台简单 |

**本方案的缺点**：

- 媒体增强能力依赖用户自行安装 ffmpeg；没有它时视频素材播放、编码预览流与导出不可用或受限，但模组主体功能不受影响。
- `layer_type=image` 承载视频是命名债；编辑器/validator 要按扩展名分流。
- 各篇的播放/导出都没有同构参考实现，需要实机调。

## 六、实现结果

状态：J-2 已实现（捕获 / 解码 / 视频图层 / 视频音轨）；J-3 已实现（预览编码流 + AUDIO 外部格式）；J-4 导出未实现。各篇细节见对应文档「实现结果」。

- 预览流：`webui/PreviewProtocol`（18B 头）、`PreviewEncoder`（每会话 H.264）、`PreviewStreamer`（分发/暂停/统计）、`OutboundQueue`（有界丢帧）；`WebFrameStreamer` 已删除。
- AUDIO：`media.audio.ExternalPcmDecode`（白名单整段异步解码）；`CinematicAudioInstance` / `AudioTrackPlayer` 接入 ready/failed 状态机。
- ffmpeg：`media.video.Ffmpeg` 固定 `<gameDir>/ffmpeg/`；`available()` = ffmpeg+ffprobe，`encoderAvailable()` = ffmpeg。

---

## 七、包体积与外置 FFmpeg（2026-11-06 实测）

### 7.1 当前包体积

| 产物 | 大小 |
|---|---:|
| `ImmersiveCinematics-fabric-1.20.1-0.3.6.jar` | 1.2 MB |
| `ImmersiveCinematics-forge-1.20.1-0.3.6.jar` | 1.2 MB |
| `common` 中间产物 | 1.6 MB |
| sources / javadoc | 0.8 MB / 1.6 MB（不随发布） |

### 7.2 外置 FFmpeg 的包体积影响

| 项 | 大小 |
|---|---:|
| 新增代码（查找、进程管理、帧管道、错误诊断） | +10~40 KB（压缩后） |
| FFmpeg 本体 | 0（不在包内） |
| 发布包预估 | 仍约 1.2 MB |

用户侧 FFmpeg 安装体积（不计入模组包）：Windows essentials 压缩包约 80–100 MB、解压后约 200–300 MB；只取 `ffmpeg.exe` 约 80–120 MB；LGPL 精简构建约 30–50 MB。以上为通用构建量级，接入时以选定构建实测为准。

### 7.3 三用途与共用通道

| 用途 | 方向 | 形态 |
|---|---|---|
| 播放视频 / 音频素材 | 解码 | 外部 ffmpeg 进程 → rawvideo / PCM → 纹理 / OpenAL（[decode.md](decode.md)、[audio.md](audio.md)） |
| 编辑器预览流（WebUI 本机；游戏内编辑器同进程直传，不走网络与编码） | 编码 | `hud` 捕获点 → 外部 ffmpeg 编码 → 本地 WebSocket（[preview-stream.md](preview-stream.md)） |
| 导出成片 | 编码 | 同一 `hud` 捕获点 → 外部 ffmpeg 编码 → 静音文件（[export.md](export.md)） |

捕获点固定 `hud`：世界 + HUD + 覆盖层 + lane 合成 + 调色，不含编辑器 UI；预览、导出、游戏内编辑器共用同一捕获点。实现见 [capture.md](capture.md) §3.1。

### 7.4 参考实现的查找与启动（BBS / Replay）

| 项 | BBS | ReplayMod |
|---|---|---|
| 路径来源 | 设置里填「Encoder path」，或设置里「Find ffmpeg.exe…」文件选择器 | 设置里填命令 → `.minecraft/ffmpeg/bin/ffmpeg.exe` → 遍历 `.minecraft` 搜 `ffmpeg.exe` → Mac `/usr/local/bin`、`/usr/bin`、Homebrew Cellar → 回退 `PATH` 的 `ffmpeg` |
| 启动 | `ProcessBuilder` | `ProcessBuilder`，工作目录 = 输出目录 |
| 错误 | 设置页提示 + wiki 链接 | 失败弹窗展示 FFmpeg 日志；`export.log` 落盘 |
| 位置 | `example/bbs-mod-1.20.1` `utils/VideoRecorder`、`FFMpegUtils` | `example/ReplayMod-stable` `render/FFmpegWriter`、`render/RenderSettings` |

### 7.5 许可证

- 外置 = 模组不打包 FFmpeg 二进制，分发的是「查找 + 启动」代码；FFmpeg 由用户自行安装，其 LGPL/GPL 义务不随模组分发转移。
- 若将来改为内置（本方案不采用），需重新评估 LGPL 动态链接与 x264 GPL 的影响。

### 7.6 FFmpeg 是可选增强（2026-11-06 确认）

**FFmpeg 不是必须依赖。** 没有它模组照常使用，只是部分媒体能力受限。

| 能力 | 有 FFmpeg | 无 FFmpeg |
|---|---|---|
| 核心模组（脚本播放、渲染、触发器、相机、覆盖层） | 正常 | **正常** |
| WebUI 编辑器预览流 | H.264 视频流（带宽约为原始 RGBA 的 1/200–1/350） | **退回原始 RGBA（`original` 档捕获封顶 1280×720、按配置帧率节流）**，功能可用、带宽高 |
| 游戏内编辑器画面 | 同进程 GPU 直传 | 同进程 GPU 直传（不依赖 ffmpeg） |
| 视频素材播放（OVERLAY `image` 指向 mp4/webm/mkv） | 正常解码播放 | **什么都不显示**（有什么显示什么；没有 ffmpeg 就没有视频解析，不占位、不报错、不阻塞其余轨道） |
| 导出成片 | 正常编码写文件（静音） | 不可用（无编码器） |

实现约束：

- FFmpeg 探测必须**懒执行**：启动、脚本加载、首帧渲染都不依赖它；只有真正用到媒体增强能力时才探测。
- 探测失败只记录一次限频日志，不弹错误、不阻塞界面。
- 编辑器/作者界面对「无 FFmpeg」给出可读提示（缺什么、装哪里、装完是否重载），但不把它当成崩溃或错误态。

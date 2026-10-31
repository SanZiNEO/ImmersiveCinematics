# 媒体（总纲）

> 状态：未实现
> 范围：运行时视频播放、解码链路、共享帧捕获底座、音频解码、编辑器预览编码流、远程媒体分片传输、画面导出（实时录屏）、失败降级与验收
> 关联：`../I-script-core/track-overlay-letterbox.md`（OVERLAY `image` 字段）、`../F-playback/multi-camera-rendering.md`、`../F-playback/cross-dimension-camera.md`、`../F-playback/f5-script-packet-limit.md`、`docs/modules/editor.md`

## 一、摘要

视频作为运行时素材能力接进模组：作者仍然写 `layer_type: "image"`，`path` 指向视频文件，播放时间跟随片段局部时间。它只依赖 OVERLAY `image` 字段形态与 F8 的视图层。

本页只做索引、范围边界与跨篇契约，不重复各篇正文。

## 二、为什么做

### 2.1 问题

- 全仓没有视频解码能力，`TextureManager` 只加载 PNG，`AudioReader` 只接受 WAV/OGG；视频文件无法进入画面。
- OVERLAY 只有 `fade`/`image`/`subtitle`，`ImageLayer` 只处理静态图与 GIF；GIF 整段拆帧进内存，不能承载长视频。
- 预览流只发原始 RGBA，720p@60fps 约 221 MB/s，没有 PTS、没有背压，慢客户端会拖死整条流。
- 没有媒体传输：`ResourcePath` 只解析本地文件，自定义包自设 1 MiB 上限，没有分片、缓存、校验。
- 视频容器里的音轨与现有 AUDIO 轨脱节，导出也没有确定性混音。
- 参考实现只解决导出/编码，不解决播放/传输：BBS 是 MIT，ReplayMod 是 GPLv3；两者都只有录制/导出。

### 2.2 目标

- 本地视频文件能在 OVERLAY 中播放，画面跟随片段局部时间；暂停、seek、循环按脚本语义走。
- 预览流有编码、PTS、每会话背压与丢帧；慢客户端不拖死其他会话。
- 远程客户端能按需拉取媒体文件，分片校验、缓存、断点续传；不把整段视频塞进一个包。
- 缺 ffmpeg / 坏文件 / 解码失败 / 网络断开不崩客户端，有明确错误态与资源回收。
- **不改脚本字段**：不新增 `layer_type`、不新增视频专用字段；`path` 扩展名决定实现。

### 2.3 非目标

- 不做视频剪辑/转码/特效 UI；编辑器仍是脚本生成器。
- 不内嵌解码器、不把 ffmpeg 打包进模组 jar；只调用外部可执行文件。
- v1 不支持 HLS/DASH/RTMP 等直播流；先本地文件，URL 下载后本地播放。
- 不自动播放视频容器内的音轨；音频仍走 AUDIO 轨，必要时导入时抽成 WAV/OGG。
- 不实现视频导出；导出链路另立任务。
- 不把原始 RGBA 当远程传输格式；远程只传文件分片或编码流。

## 三、方案

### 3.1 三条跨篇契约

1. **视频时间 = 片段局部时间。** 播放、暂停、seek、循环、morph 两侧都按 `executionLocalTime`；视频不自建时钟，不跟全局墙钟。
2. **传输走分片，不走原始帧。** 本地预览是编码帧 + PTS + 有界队列；远程是分片拉取 + 本地缓存；原始 RGBA 只允许本机预览回退。
3. **失败一律降级，不崩客户端。** 缺 ffmpeg、坏文件、解码卡住、网络断开都回退错误纹理或最后一帧，限频记日志。

### 3.2 分篇地图

| 文档 | 负责什么 | 一个点 |
|---|---|---|
| [playback.md](playback.md) | 视频图层在 OVERLAY 中播放，时间跟随片段，seek/循环/暂停语义 | 作者能用视频当 `image` 素材 |
| [decode.md](decode.md) | ffmpeg 解码进程、rawvideo 管道、有界队列、PTS、双缓冲纹理上传 | 帧怎么从文件到纹理 |
| [capture.md](capture.md) | 共享帧捕获：捕获源、尺寸/格式、PTS、有界队列、消费者契约 | 预览和导出共用哪一层 |
| [audio.md](audio.md) | 视频音轨的处置与独立音频解码；与 AUDIO 轨的边界 | 声音从哪来 |
| [preview-stream.md](preview-stream.md) | 预览编码流：H.264/MJPEG、PTS、每会话背压、关键帧 | 预览怎么不拖死自己 |
| [transport.md](transport.md) | 远程媒体分片拉取、校验、缓存、白名单与限流 | 文件怎么到客户端 |
| [export.md](export.md) | 实时录屏导出：按目标 fps 采样、编码、音频混音、进度/取消 | 怎么出成片 |
| [degrade.md](degrade.md) | 外部依赖与失败分层：缺 ffmpeg、坏文件、解码失败、断线、资源回收 | 坏了怎么办 |
| [acceptance.md](acceptance.md) | 跨篇验收矩阵与真实机器检查项 | 怎么算过 |

### 3.3 交付顺序

1. `capture.md`：先把现有预览的捕获/缩放/发送拆成捕获层 + 消费者，行为不变。
2. `decode.md` + `playback.md`：让本地视频能动，且跟随局部时间。
3. `preview-stream.md`：预览改为编码消费者，复用捕获层。
4. `export.md`：同一捕获层接文件消费者，加音频与收尾。
5. `degrade.md`：错误态、进程回收、资源释放，与捕获/解码/编码同批。
6. `transport.md`：远程分片，依赖文件语义稳定后接。
7. `audio.md`：音轨处置，可与传输并行。
8. `acceptance.md`：每篇完成时补本行，不集中到最后。

## 四、技术细节

### 4.1 边界

- 不消费规则、路径、分支、次数语义；只消费 OVERLAY `image` 字段与 F8 的视图层。
- 不修改脚本格式；`layer_type=image` + `path` 扩展名是唯一入口。
- 不劫持主 framebuffer，不参与 lane 渲染，不改变相机状态。

### 4.2 外部依赖

- ffmpeg / ffprobe 是外部可执行文件，可配置路径；缺失时该能力降级为错误纹理，不影响脚本加载。
- 远程传输白名单只允许 `.mp4` / `.webm` / `.mkv`，只读资源根内相对路径。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 媒体与脚本语义耦合 | 作为脚本语义的一部分 | 验收被视频问题污染；依赖面不同 | 按素材能力独立验收 |
| 新增 `layer_type=video` | 显式视频类型 | 改 schema/validator/编辑器枚举；因 ffmpeg 改字段 | 复用 `image` + `path` 扩展名，零字段变更 |
| 内嵌 ffmpeg / JNI | 解码器进模组 | 体积、natives、平台差异、崩溃面、许可证 | 外部进程零打包、隔离崩溃、跨平台简单 |

**本方案的缺点**：

- 依赖用户安装 ffmpeg；缺失时不能开箱即用。
- `layer_type=image` 承载视频是命名债；编辑器/validator 要按扩展名分流。
- 各篇的播放/传输都没有同构参考实现，需要实机调。

## 六、实现结果

状态：未实现。各篇完成时在对应文档的「实现结果」里记录偏离，本页只保留总纲结论。

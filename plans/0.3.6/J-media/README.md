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
- 视频容器内的音轨跟随视频播放；AUDIO 轨仍可播放独立音频文件，两者互不替代。
- 不实现视频导出；导出链路另立任务。
- 不把原始 RGBA 当远程传输格式；远程只传文件分片或编码流。

## 三、方案

### 3.1 三条跨篇契约

1. **视频时间 = 片段局部时间。** 播放、暂停、seek、循环、morph 两侧都按 `executionLocalTime`；视频不自建时钟，不跟全局墙钟。
2. **传输走分片，不走原始帧。** 本地预览是编码帧 + PTS + 有界队列；远程是分片拉取 + 本地缓存；原始 RGBA 只允许本机预览回退。
3. **失败一律降级，不崩客户端。** 缺 ffmpeg 时视频素材不显示（有什么显示什么）；坏文件、解码卡住、网络断开回退最后一帧或错误纹理，限频记日志。

### 3.2 分篇地图

| 文档 | 负责什么 | 一个点 |
|---|---|---|
| [playback.md](playback.md) | 视频图层在 OVERLAY 中播放，时间跟随片段，seek/循环/暂停语义 | 作者能用视频当 `image` 素材 |
| [decode.md](decode.md) | ffmpeg 解码进程、rawvideo 管道、有界队列、PTS、双缓冲纹理上传 | 帧怎么从文件到纹理 |
| [capture.md](capture.md) | 共享帧捕获：捕获源、尺寸/格式、PTS、有界队列、消费者契约 | 预览和导出共用哪一层 |
| [audio.md](audio.md) | 视频音轨的处置与独立音频解码；与 AUDIO 轨的边界 | 声音从哪来 |
| [preview-stream.md](preview-stream.md) | 预览编码流：H.264（无 FFmpeg 退回原始 RGBA）、PTS、每会话背压、关键帧 | 预览怎么不拖死自己 |
| [transport.md](transport.md) | 远程媒体分片拉取、校验、缓存、白名单与限流 | 文件怎么到客户端 |
| [export.md](export.md) | 实时录屏导出：按目标 fps 采样、编码、音频混音、进度/取消 | 怎么出成片 |
| [degrade.md](degrade.md) | 外部依赖与失败分层：缺 ffmpeg、坏文件、解码失败、断线、资源回收 | 坏了怎么办 |
| [acceptance.md](acceptance.md) | 跨篇验收矩阵与真实机器检查项 | 怎么算过 |
| [handover.md](handover.md) | **J 轮开头的遗留验证**（承接 A–I）：B18 ④打断 / ⑤链式、B19 迟滞四象限、C-pause 复验、F 轮失败处理 P1/P2 | 老欠账怎么收 |

### 3.3 交付顺序

0. `handover.md`：先把 A–I 轮遗留的验证项做完（B18 ④⑤ / B19 四象限 / C-pause / F P1-P2），再进媒体实现。
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

- ffmpeg / ffprobe 是**可选**外部可执行文件，可配置路径。**没有它模组照常使用**；缺失时只影响媒体增强能力（视频素材播放、编码预览流、导出），核心脚本播放、渲染、触发器、相机均不受影响。具体降级见 [degrade.md](degrade.md) §3.1 与本文 §7.6。
- 远程传输白名单只允许 `.mp4` / `.webm` / `.mkv`，只读资源根内相对路径。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 媒体与脚本语义耦合 | 作为脚本语义的一部分 | 验收被视频问题污染；依赖面不同 | 按素材能力独立验收 |
| 新增 `layer_type=video` | 显式视频类型 | 改 schema/validator/编辑器枚举；因 ffmpeg 改字段 | 复用 `image` + `path` 扩展名，零字段变更 |
| 内嵌 ffmpeg / JNI | 解码器进模组 | 体积、natives、平台差异、崩溃面、许可证 | 外部进程零打包、隔离崩溃、跨平台简单 |

**本方案的缺点**：

- 媒体增强能力依赖用户自行安装 ffmpeg；没有它时视频素材播放、编码预览流与导出不可用或受限，但模组主体功能不受影响。
- `layer_type=image` 承载视频是命名债；编辑器/validator 要按扩展名分流。
- 各篇的播放/传输都没有同构参考实现，需要实机调。

## 六、实现结果

状态：未实现。各篇完成时在对应文档的「实现结果」里记录偏离，本页只保留总纲结论。

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
| 编辑器预览流（仅外部 WebUI 阶段；附属模组同进程直接传输画面，不走网络与编码） | 编码 | 共用捕获层 → 外部 ffmpeg 编码 → 网络分片（[preview-stream.md](preview-stream.md)） |
| 导出成片 | 编码 | 同一捕获层 → 外部 ffmpeg 编码 → 文件（[export.md](export.md)） |

捕获源是**游戏最终画面**（合成 FBO；需要所见即所得时用窗口源），同一帧可同时供预览与导出消费；契约见 [capture.md](capture.md)。

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
| WebUI 编辑器预览流 | H.264 视频流（带宽约为原始 RGBA 的 1/200–1/350） | **退回现有方式：原始 RGBA（720p / 约 60fps 节流）**，功能可用、带宽高 |
| 视频素材播放（OVERLAY `image` 指向 mp4/webm/mkv） | 正常解码播放 | **什么都不显示**（有什么显示什么；没有 ffmpeg 就没有视频解析，不占位、不报错、不阻塞其余轨道） |
| 导出成片 | 正常编码写文件 | 不可用（无编码器） |
| 远程媒体分片传输 | 正常 | 正常（与 FFmpeg 无关，只传文件） |

实现约束：

- FFmpeg 探测必须**懒执行**：启动、脚本加载、首帧渲染都不依赖它；只有真正用到媒体增强能力时才探测。
- 探测失败只记录一次限频日志，不弹错误、不阻塞界面。
- 编辑器/作者界面对「无 FFmpeg」给出可读提示（缺什么、装哪里、装完是否重载），但不把它当成崩溃或错误态。

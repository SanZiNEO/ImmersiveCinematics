# 音频轨道系统

**版本**: 0.4.0  
**类型**: 新功能  
**依赖**: 0.3.2 编辑器重构完成

---

## 系统集成

**原则**: 只负责传入音频，播放和空间效果全部由原版 SoundEngine 处理。

`AudioTrackPlayer.onRenderFrame()`:
1. 找到当前时间活跃的 AudioClip
2. 若无活跃 clip 且实例在播 → `instance.stop()`
3. 若有活跃 clip 且实例未创建 → 创建 `CinematicAudioInstance(audioPath)` → `soundManager.play(instance)`
4. 若有活跃 clip 且实例已创建 → `tick()` 内根据关键帧更新 volume/pitch/position

---

## 自定义 AudioInstance

继承 `AbstractTickableSoundInstance`，override `getStream()`:

```java
class CinematicAudioInstance extends AbstractTickableSoundInstance {
    private final Path audioFile;

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary lib, Sound sound, boolean looping) {
        return CompletableFuture.supplyAsync(() -> {
            FileInputStream fis = new FileInputStream(audioFile.toFile());
            boolean isOgg = audioFile.toString().endsWith(".ogg");
            if (isOgg) {
                return looping
                    ? new LoopingAudioStream(OggAudioStream::new, fis)
                    : new OggAudioStream(fis);
            }
            // WAV: 跳过 44 字节头 → 裸 PCM → 构造 AudioStream
            byte[] header = new byte[44];
            fis.read(header);
            // 解析样本率/通道数/AudioFormat → AudioStream
            ...
        });
    }
}
```

`resolve()` 返回自造的 `WeighedSoundEvents`，含一个空壳 `Sound`（type=FILE, stream=true），避免依赖 `sounds.json` 注册。

SoundEngine 的 `tickNonPaused()` 每 tick 自动从 instance 读取 `getVolume()/getPitch()/getX()/getY()/getZ()` 并推送到 OpenAL Channel——volume/pitch/position 全自动同步，零 OpenAL 操作。

---

## 音频文件目录

```
run/immersive_cinematics/video/
├── music/
│   └── battle_theme.ogg
├── ambient/
│   └── wind.wav
└── voice/
    └── nar_01.ogg
```

脚本中 `sound` 字段写相对路径: `"music/battle_theme.ogg"`。路径以 `minecraft:` 开头走原版 `SoundEvent` 注册表，否则走文件系统读取。

---

## 支持格式

| 格式 | 解码方式 | 说明 |
|------|----------|------|
| .ogg | 原版 `OggAudioStream` | 推荐，Vorbis 压缩 |
| .wav | 裸 PCM（跳过 44 字节头） | 无损备选 |

---

## 脚本格式

```json
{
  "start_time": 0.0,
  "duration": 10.0,
  "sound": "music/battle_theme.ogg",
  "source": "music",
  "attenuation": "none",
  "position_mode": "relative",
  "keyframes": [
    {"time": 0.0, "volume": 0.0, "pitch": 1.0, "dx": 0, "dy": 0, "dz": 0},
    {"time": 1.0, "volume": 1.0, "pitch": 1.0, "dx": 0, "dy": 0, "dz": 0},
    {"time": 9.0, "volume": 1.0, "pitch": 1.0, "dx": 0, "dy": 0, "dz": 0},
    {"time": 10.0, "volume": 0.0, "pitch": 1.0, "dx": 0, "dy": 0, "dz": 0}
  ]
}
```

### Clip 级字段

| 字段 | 说明 |
|------|------|
| `start_time` | 开始时间 (秒) |
| `duration` | 持续时长 (秒) |
| `sound` | 音频路径。`minecraft:` 开头走 SoundEvent，否则读文件系统 |
| `source` | SoundSource 枚举: MASTER/MUSIC/RECORDS/AMBIENT/PLAYERS/VOICE 等 |
| `attenuation` | `"none"` = 2D (无视距离) / `"linear"` = 3D (空间衰减) |
| `position_mode` | `"relative"` = 相对玩家位置 / `"absolute"` = 世界坐标 |

### 关键帧字段

| 字段 | 说明 |
|------|------|
| `time` | 关键帧时间点 (clip 内) |
| `volume` | 0.0-1.0，自动受全局音量滑条缩放 |
| `pitch` | 0.5-2.0 (OpenAL 限制) |
| `dx/dy/dz` | 相对位置 (position_mode: relative) |
| `x/y/z` | 绝对位置 (position_mode: absolute) |

---

## AudioTrackPlayer

```java
class AudioTrackPlayer implements TrackPlayer {
    private final List<AudioClip> clips;
    private CinematicAudioInstance currentInstance;
    private int currentClipIdx = -1;

    @Override
    public void onRenderFrame(float globalTime) {
        AudioClip clip = findActiveClip(globalTime);
        if (clip == null) {
            if (currentInstance != null) currentInstance.stop();
            currentInstance = null;
            return;
        }

        float localTime = globalTime - clip.startTime;

        if (currentInstance == null || currentClipIdx != clipIdx) {
            if (currentInstance != null) currentInstance.stop();
            currentInstance = new CinematicAudioInstance(clip.sound, clip.source, clip.attenuation, ...);
            Minecraft.getInstance().getSoundManager().play(currentInstance);
            currentClipIdx = clipIdx;
        }

        // 关键帧插值 → 直接赋值给 instance 字段
        AudioKeyframe kf = interpolate(clip.keyframes, localTime);
        currentInstance.volume = kf.volume;
        currentInstance.pitch = kf.pitch;
        currentInstance.x = kf.x; currentInstance.y = kf.y; currentInstance.z = kf.z;
    }

    @Override
    public void onStop() {
        if (currentInstance != null) currentInstance.stop();
    }
}
```

SoundEngine 的 `tickNonPaused()` 每 tick 自动读取 instance 的 volume/pitch/position 并推送到 OpenAL——不需要在 AudioTrackPlayer 里手动调 Channel API。

---

## 编辑器联动

- 编辑器预览时音频随播放头同步（OGG 用预读缓冲区实现 seek，WAV 用 `InputStream.skip()`）
- 音频 clip 属性面板（sound 路径自动补全、source/attenuation 下拉）
- 拖拽 audio clip/关键帧时音频实时跟随

---

## 改动文件

| 文件 | 改动 |
|------|------|
| `script/AudioClip.java` | 改为关键帧驱动，删除 fadeIn/fadeOut |
| `script/AudioKeyframe.java` | 新增 record(time, volume, pitch, dx, dy, dz) |
| `script/ScriptParser.java` | 解析 audio keyframes |
| `script/AudioTrackPlayer.java` | 重写：关键帧插值 + SoundInstance 管理 |
| `script/CinematicAudioInstance.java` | 新增，Override getStream() 读文件 |
| `editor/EditorOperations.java` | addClip 按 AUDIO 填充默认 keyframe |
| `editor/area/LeftPanelArea.java` | AUDIO 属性面板 |
| `editor/area/TimelineArea.java` | AUDIO 轨道渲染 |

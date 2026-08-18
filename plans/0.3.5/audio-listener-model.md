# 音频听者模型（Audio Listener Model）

**版本**: 0.3.5
**类型**: 音频体系设计（2026-08-13 定稿）
**状态**: 📋 设计定稿，待实施
**关联**: 取代 `audio-playback-model.md`（草案）、`audio-relative-position-modes.md`（结论并入）、`plans/0.4.0/audio-system.md`（旧草案）；与 `chunk-preload.md`（画面/区块加载）构成 0.3.5 的"画面 + 声音"两条线，**互不依赖**

---

## 一句话定义

声音分两类：**空间音效**（有世界位置，衰减中心 = 听者）与**非空间声音**（广播类，无空间性，锁定玩家）。听者由作者二选一（玩家 / 相机，默认玩家）——空间音效全部按听者衰减，传错位置的问题不存在；非空间声音保持原版行为，与听者无关。

---

## 一、声音分类（第一原则）

| 类型 | 例子 | 空间性 | 听者 |
|------|------|--------|------|
| **空间音效** | 本模组 AUDIO 轨道（relative/absolute）、环境音（风/虫鸣/洞穴/水下/瀑布）、到达客户端的原版空间声音（生物叫/方块音） | 世界位置 + 距离衰减 | **作者选择的听者**（player 或 camera） |
| **非空间声音** | 音乐、通知、UI 音 | 无位置、无衰减 | **锁定玩家**（原版现状，与听者无关） |

**推论**：
- 空间音效的衰减中心 = 听者——听者是谁，声音就按谁的距离衰减，不存在"设计听者是相机却传到玩家那边"
- 非空间声音没有"位置"概念，无论听者是谁都照常播放——**广播路径（服务端→客户端）零改动**：原版按玩家广播、客户端播放，怎么都对

---

## 二、听者模型（核心）

### 脚本字段

```json
{ "meta": { "listener": "camera" } }   // "camera" | "player"；缺省 = "player"
```

### 语义

- 听者 = 空间音效的衰减中心（OpenAL listener 位置 + 朝向）
- `"player"`（默认）：与现状一致——所有空间音效按玩家位置衰减
- `"camera"`：过场电影视角——所有空间音效按相机位置/朝向衰减；**听不到玩家附近的声音是正确语义**（听者=相机），反之亦然

### 实现（机制层面，简要）

- 原版 `SoundEngine.updateSource(渲染相机)` 每帧设置 listener——听者=相机时**原版机制天然成立**（渲染相机即虚拟相机）
- 听者=玩家时，以"玩家视角代理"（位置=玩家、朝向=玩家视线）走原版 `SoundManager.updateSource` 公开 API——listener 的设置始终走原版代码路径，位置+朝向完整
- 本模组音频轨道回归原版 SoundEngine（§四）后，不再存在任何直接 OpenAL listener 写入——**全局只有一个听者，由作者的选择决定**

---

## 三、递进关系（设计顺序）

1. **空间音效按听者判断** → 衰减中心 = 听者，传错位置问题不存在
2. **广播的非空间声音锁定玩家** → 原版行为，零改动（§五）
3. **位置语义**：relative = 相对听者，absolute = 世界坐标（§六）

---

## 四、本模组 AUDIO 轨道：回归原版 SoundEngine

自定义 `CinematicAudioInstance extends AbstractTickableSoundInstance`：

```
├─ getStream()：OGG → 原版 OggAudioStream；WAV → 裸 PCM 流
├─ resolve()：自造 WeighedSoundEvents（空壳 Sound，type=FILE, stream=true），绕开注册表
└─ 每帧赋值 x/y/z/volume/pitch → SoundEngine.tickNonPaused() 自动推送 OpenAL channel
```

**意义**：空间音效的衰减（按听者）、音量（分类滑条 × MASTER）、流式加载、设备切换、暂停语义全部由原版统一处理——音源位置与世界坐标的关系、衰减中心与听者的关系，原版保证一致，不存在"传错位置"。

---

## 五、环境音（空间音效的一种，客户端本地）

环境音本质 = 以**听者位置**采样生成的本地空间音效。原版以玩家采样（正常游玩时听者≈玩家，正确）；听者=相机时采样点重定向到相机：

| 处理器 | 内容 | 听者=玩家 | 听者=相机 |
|--------|------|-----------|-----------|
| `BiomeAmbientSoundsHandler` | 群系 loop（风/虫鸣）+ mood（洞穴低鸣） | 原版（玩家采样） | 采样/计算点 → 相机 |
| `UnderwaterAmbientSoundHandler` | 水下环境音 | 原版 | 相机位置水下判定 |
| `BubbleColumnAmbientSoundHandler` | 气泡柱 | 原版 | 相机周围 |
| `animateTick`（环境粒子+方块音效） | 瀑布/火/滴水等 | 原版（玩家中心） | 采样中心 → 相机 |

**特性**：纯客户端本地生成，与距离无关——相机离玩家几千个区块（几万格）时依然成立，**只要相机区域区块已加载（画面线保证），声音就在**。"相机附近有声音"的答案 = 环境音。

---

## 六、位置语义

| 模式 | 语义 | 说明 |
|------|------|------|
| `relative` | **听者位置 + 偏移**（每帧求值，听者是谁就相对谁），默认启用空间衰减 | 随身声（听者=玩家）/ 播报旁白（听者=相机）；偏移 0 时自然无衰减 |
| `absolute` | 世界坐标 | 场景声 |

- `relative` 音源最终落世界坐标（听者位置+偏移）→ 衰减规则与 absolute 相同（`attenuation`：linear/inverse/none，默认 linear，距离 16 格）
- 废弃 `relative_fixed`（创建时锚定）讨论——锚定需求用 absolute 表达
- 不引入实体/结构/方块基准（音频位置不搞复杂）
- 兼容性：旧脚本 `relative` 音频（偏移>0）从"无衰减"变"有衰减"——文档标注

---

## 七、音频分类（`category`）

作者把音频归入原版 SoundSource 分类，音量滑条与空间性按分类贴合：

| `category` | 音量滑条 | 空间性 |
|---|---|---|
| `music`（默认） | MUSIC | **非空间**（强制无衰减，音源位置无效，画面在哪发在哪） |
| `ambient` | AMBIENT | 空间 |
| `voice` | VOICE | 空间 |
| `blocks` | BLOCKS | 空间 |
| `players` | PLAYERS | 空间 |
| `master` | MASTER | 空间 |
| `neutral` / `records` / `weather` | 各自滑条 | 空间 |

- 音量链路统一为原版：`volume × 分类滑条 × MASTER`
- 默认 `music` → 存量脚本音量行为与现状一致
- 注：现有 `source` 字段 = 文件来源类型（`"file"`/`"minecraft"`），保留不变；`category` 为新增字段

---

## 八、广播类声音（非空间）：原版零改动

- 服务端按玩家位置广播（原版 `PlayerList.broadcast`），客户端播放——与听者无关，不改
- 音乐类（`category: music`）与通知/UI 音走此路径
- 已知限制：**广播的空间声音**（生物叫/方块音/爆炸，服务端按玩家裁剪）在相机区域**缺失**（包到不了客户端）——过场场景事件源稀缺（生物加载不做、无人区方块不交互），缺失可接受；多人其他玩家在镜头前活动的声音会缺失，记录在案
- 未来若需解决：`PlayerList.broadcast` 距离基准替换（一个 Mixin）+ 相机位置 C2S 上报（与预加载共用通道）——届时再议

---

## 九、边界与待定

1. **多人**：各玩家独立听者状态——播放脚本者=camera，其他人=player（倾向是）
2. **编辑器预览**：预览时听者跟随预览相机（画面与声音一致）还是保持 player？（倾向：跟随预览相机）
3. **音乐压制**：配乐回归 SoundEngine 后，原"每帧 stop(MUSIC) 压制"逻辑调整为"仅当我们的 MUSIC 类实例活跃时压制"
4. **animateTick**：替换 vs 双份（倾向替换）
5. **relative 衰减默认值**：linear 16 格（与 absolute 一致）——数值待确认
6. **`category` 编辑器**：属性面板下拉，schema 默认值 `music`
7. **生物加载**：不做（可能下个版本，可能永远不做）

---

## 十、实施顺序

1. **听者管理模块**：listener 集中决策（玩家代理 Camera）+ 删除现有裸 AL 覆盖
2. **AUDIO 轨道回归原版**：CinematicAudioInstance 重写 + `category` 字段（schema/编辑器）
3. **位置语义**：relative 相对听者 + 默认衰减
4. **环境音重定向**（camera 模式）：3 个 handler + animateTick
5. **编辑器音频联动**（随重构一并重写）：repositionAudio/syncToTime/seek/暂停态基于新 SoundInstance 实现；预览随播放头同步、拖拽音频 clip/关键帧实时跟随（原 0.4.0 条目并入本版本）
6. **文档更新**：SCRIPT_FORMAT / AI_SCRIPTING_GUIDE / CHANGELOG（relative 衰减行为变化标注）
7. **冒烟测试**：player 模式回归、camera 模式（环境音跟随镜头、远处轨道声音按相机衰减）、MUSIC 类非空间、relative 衰减、多人独立听者

> 服务端零改动；无相机位置上报；与区块预加载（画面）完全解耦。

## 执行前再看 / 具体方案

- **MC 源码**（已抽取到 `build/mc-sources/`）：
  - `client/sounds/SoundEngine.java`：`updateSource(Camera)`（listener = camera pos/look/up）、`tickNonPaused()`（每 tick 读 `TickableSoundInstance.getX/Y/Z/volume/pitch` 推 OpenAL channel）。
  - `client/resources/sounds/AbstractSoundInstance.java`（`x/y/z/volume/pitch/attenuation/relative/looping` 字段）、`AbstractTickableSoundInstance.java`、`TickableSoundInstance.java`、`Sound.java`（`Type.FILE` + `stream=true` 可绕开注册表）、`client/sounds/WeighedSoundEvents.java`。
  - `client/resources/sounds/BiomeAmbientSoundsHandler.java` / `UnderwaterAmbientSoundHandler.java` / `BubbleColumnAmbientSoundHandler.java`（都直接用 `this.player` 采样，camera 模式要重定向采样点）。
- **项目现状**：
  - `script/AudioTrackPlayer.java` 每帧 `alListener3f` 裸写 listener + `stop(MUSIC)` 压制；`script/CinematicAudioInstance.java` 是裸 OpenAL 源。
  - `mixin/CameraMixin.java` HEAD+cancel 已让原版 `SoundEngine.updateSource` 使用电影相机——**听者=相机天然成立**；听者=玩家时需要“玩家代理 Camera”或每帧用玩家位置/朝向的 Camera 调 `SoundManager.updateSource`。
- **外部参考**：
  - `hackermdch/MediaPlayer` → `AudioInstance.java`（自定义 SoundInstance 的 `getStream` 返回 CompletableFuture<AudioStream>）。
  - `Rogic460/Minecraft-laowu-meme` → `ImportedSoundInstance.java`（继承 AbstractTickableSoundInstance + 自造 WeighedSoundEvents）。
  - `Dseelis/TrueMusic` / `metrovoc/Phonon` 的 Speaker/Headphones SoundInstance 同类。
- **执行时再看**：`AudioTrackPlayer`、`CinematicAudioInstance`、`CameraMixin`、`SoundEngine.tickNonPaused/updateSource`、`AbstractSoundInstance`、`Sound`、`WeighedSoundEvents`、三个环境音 handler、`ClientLevel.animateTick`。

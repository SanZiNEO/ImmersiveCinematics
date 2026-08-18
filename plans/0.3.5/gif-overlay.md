# GIF 动图 Overlay：拆帧 + 帧索引轮播

## 一句话定义

`overlay` 图片层支持 GIF 动图：解码时把 GIF 按帧拆成一张张帧图，渲染时按帧索引轮播——火焰/雨/粒子/logo 动画/电视雪花等电影感动态元素，静态 PNG 做不到。

## 现状

- `TextureLoader` → `NativeImage.read` → **stb_image**：实际支持 PNG/JPEG/BMP/TGA/GIF（**GIF 只解第一帧，静默当静态图**）
- 文档写"只支持 PNG"是保守表述

## 方案（拆帧 + 轮播）

### 解码（LWJGL 原生，无新依赖）

```java
STBIGifResult result = STBImage.stbi_load_gif(buffer);   // 1.20.1 自带 LWJGL 3.3.1
// result: frames[]（每帧 ByteBuffer）+ delays[]（每帧延迟 ms）+ width/height
```

### 数据模型

- 拆帧 → `List<NativeImage>`（或直接持有 ByteBuffer，按需转）
- 帧延迟表 `int[] delays`（ms）
- 单张 `DynamicTexture` 复用：轮播到第 N 帧时 upload 该帧（**显存只占一帧**）——比 N 张纹理省，且帧数不受 GPU 纹理数限制

### 播放模型（ImageLayer 扩展动态纹理）

```
渲染前：frameIndex = 由 globalTime 按 delays 累计计算（帧索引 + 帧内插值进度）
每帧：当前帧 NativeImage → DynamicTexture.upload → blit
```

- 确定性：globalTime → 帧索引，暂停/重放一致 ✓
- 循环：超出总帧长取模（GIF 语义）
- 与现有属性兼容：x/y 位置、scale_x/y、opacity、中心锚点全部沿用（只是纹理源动态）

### 资源管理

- 缓存：TextureLoader 按 fileName 缓存帧序列（同静态纹理模式）
- 内存上限：帧数（如 ≤ 256 帧）+ 单帧尺寸（如 ≤ 1024²）超限降级为第一帧静态
- 释放：脚本停止/层清理时释放帧数据

## 复杂度评估（用户判断：较好做）

拆帧（LWJGL API 现成）+ 帧索引轮播（时间→索引计算）——核心 ~80 行；ImageLayer 加"动态纹理源"分支（渲染前选帧）~20 行；TextureLoader 加 gif 分支 ~40 行。比文字纹理化简单（无字体/缓存失效问题）。

## 边界与待定

0. **视频（mp4/webm）明确不做**：需要 FFmpeg 级解码器（库大、跨平台复杂），原版 MC 无视频播放管线，音画同步成本高；GIF 是动图支持的合理上限（stb 自带解码、单帧内存、无音画问题）。bbs-mod 的 video 层长期占位未落地也是旁证
1. 播放速度：用 GIF 自带 delay 还是脚本可覆盖（`gif_speed` 倍率）？
2. 帧索引计算频率：每渲染帧算一次（全局时间），无需 tick 驱动
3. 是否要"播放/暂停/单帧"控制（脚本里控制动图进度）——初版按 GIF 自然播放，后续按需
4. 与 ImageLayer 诊断/缓存清理（TextureLoader.clearCache）同步

## 参考

- LWJGL `org.lwjgl.stb.STBImage.stbi_load_gif` / `STBIGifResult`
- bbs-mod 的 AnimatedTexture（KeyframeChannel 轮播帧）是同类模型

## 执行前再看 / 具体方案

- **LWJGL API**：`STBImage.stbi_load_gif_from_memory(ByteBuffer, PointerBuffer delays, IntBuffer x, IntBuffer y, IntBuffer z, IntBuffer channels, int desired)`；返回按帧顺序交错排列的像素数据，`delays` 为每帧延迟数组，`z` 为帧数。
- **外部参考**：
  - `CreeperHost/PolyLib` → `common/src/main/java/net/creeperhost/polylib/client/gif/AnimatedGif.java`（拆帧切片 + 纹理轮播）。
  - `The-Plum-Team/Quick-Skin-Mod` → `StbGifLoader.java`（先解析 GIF 容器防止解压炸弹，可参考内存上限思路）。
  - `SamsTheNerd/inline` → `ImgFormatParser.java`、`DarkKronicle/Facelift` → `ImageUtil.java`、`aratakileo/elegantia` → `GifImage.java`。
- **项目文件**：
  - `util/TextureLoader.java`（`NativeImage.read` 目前只解第一帧；新增 gif 分支与缓存）
  - `overlay/ImageLayer.java`（加“动态纹理”模式，渲染前按帧索引上传）
  - `script/OverlayTrackPlayer.java`（把 `globalTime` 传给 ImageLayer 计算帧索引）
  - `client/renderer/texture/DynamicTexture.java`（单帧纹理复用上传）
- **执行时再看**：`TextureLoader`、`ImageLayer`、`OverlayTrackPlayer`、`DynamicTexture`、PolyLib AnimatedGif / Quick-Skin StbGifLoader。

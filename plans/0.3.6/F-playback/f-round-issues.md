# F 轮实测问题报告（2026-10-17）

> 环境：Forge 47.4.23 / Oculus 1.8.0 / Embeddium 0.3.31 / ComplementaryUnbound r5.9.3，
> 2560×1369（测试中曾调整窗口比例），光影启用，`shader_pack_in_use=true`。
> 会话：`diagnostics/sessions/20261017-172637-f-round/`。

## 1. 测试进度与缺失项

| 项 | 记录 |
|---|---|
| 功能脚本 1–3 | 通过；`cam_views` 左右分屏是 `dest` 半宽 + `source` 全幅，属**横向压扁**，不是裁切（fixture 预期） |
| `perf_l0` | 600/600 完成 |
| `perf_l1` | 600/600 完成 |
| `perf_l16` FULL | **259/600**，`FORCE_QUIT` 中断 |
| `perf_l16` WORLD_ONLY | 600/600 完成 |
| `perf_l4` FULL | 600/600 完成 |
| `perf_l4` WORLD_ONLY | 未跑 |
| `perf_l25` | **未启动**：无 `script_start` / `perf_start` / 性能文件；用户反馈命令层报错 |

`l25` 的脚本本身加载正常（启动日志有 `Loaded script: ic_f_perf_l25`，26 条轨、900s）。
需要用户补充聊天栏报错原文，才能确定是“脚本文件不存在”还是参数/命令解析问题。

## 2. 问题 A：lane 合成疑似非 100% 不透明（绿色毛边 / 隐约透底）

### 现象

- 高对比边缘（白色柱子）出现绿色毛边；
- 静态不明显，运动时能看到“下面还有东西”；
- 用户历史排查结论：原版天空/雾的 alpha=0 在 lane 合成前被过早采样，出现过类似抠像的透底；当时用 RGB 分离测试验证过。

### 当前代码事实

- 合成 shader `ic_lane_blit.fsh`：`fragColor = vec4(rgb * ColorModulator.rgb, ColorModulator.a * coverage)`，
  **不读 lane 纹理 alpha**；`opacity=1`、`full` 时输出 alpha=1。
- `LaneCompositor` 混合：`SRC_ALPHA / ONE_MINUS_SRC_ALPHA`，`colorMask(true,true,true,false)`（不写主画面 alpha）。
- 只有 `ic_lane_blit` **加载失败**时才退回原版 `position_tex`（会重新引入 lane alpha 透底），并且只记录一次错误。
- 会话中 raw / window 图的 alpha 实测均为 **255**（`cam_views`、`lane_master_color`、`source_dest`、`mask_wipe`）。
- `LevelRendererMixin.laneClearAlpha` 仍把 lane 清屏 alpha 抬到 1；但光影 final pass 可能再次写 alpha。

### 待验证假设

1. **Oculus 下 `ic_lane_blit` 未生效 / 回退到 `position_tex`**：需要确认自定义 core shader 在 Oculus 管线里是否被替换或编译失败；日志未见错误，但需读码确认加载路径。
2. **合成之后又被光影后处理**：若 `finalizeGameRendering` / `postEffect` / GUI 阶段再次采样主画面 alpha，会把边缘/半透明放大成毛边。
3. **绿色毛边本身是 shader pack 的色差/TAA**（不是 alpha）：Complementary 的 final pass 可能有 chromatic aberration / sharpening；需要关闭对应效果做 A/B。
4. **主画面 alpha=0 区域参与混合**：当前混合因子不读 dst alpha，但 GUI/后续 pass 可能读。

### 历史根因（已核实）

- **第一级**：`FogRenderer.setupColor` 把清屏 alpha 设为 0；旧合成走原版 `position_tex`
  （`if (color.a == 0.0) discard;` + srcalpha 混合），雾/天空区整片透出主画面。
  实测 lane 0 的 `a=0` 占 51.4%，lane 1–3 占 60.1%。
- **第二级**：清屏 alpha 抬到 1 后，星星（a=127）、方块图集 mipmap 边缘（a=146~254）、
  cutout 植被仍保留 a<255；`position_tex` 的混合权重被乘成 `a × opacity`，残留 0.15%~1.77% 透底。
- **修复**：`edd864d` 清屏 alpha=1；`a8b21d5` 新增 `ic_lane_blit`，合成只读 RGB、
  写出 `alpha = opacity × coverage`，lane 纹理 alpha 完全不参与。
- **关键**：`LaneCompositor` **没有光影分支**；唯一 bypass 是 `ic_lane_blit` 加载失败回退
  `position_tex`。历史验证只在无光影下做过（Iris/Oculus 未实机）。

### 用户补充的关键现象（2026-10-17）

- 绿色毛边**只在 lane 相机朝向与主相机朝向相反时出现**；同朝向没有。
- 发生在空地/物体边缘；静态不明显，运动时明显。
- 用户单独执行 `test_queue_bulk_09`（**单条**全屏 CAMERA lane，玩家上方 8 格）时，也能看到
  左右纹理略有差异的竖缝。

### 由此推导

- 单条全屏 lane 内部出现竖缝 → 缝在**该 lane 的 RGB 内部**，不是“多 lane 之间 alpha 混合”。
- 方向相反才出现 → 问题跟着**主相机的屏幕空间/历史状态**走（TAA history、previous 矩阵、
  shadow map 覆盖、SSR/SSAO），而不是 lane 相机自身的几何。
- `ic_lane_blit` 只读 RGB，所以合成层无法修复 lane RGB 里已有的毛边/缝；修复点应在 lane pass
  或光影状态隔离。

### 新发现：Iris 深度纹理缓存键是版本号，不是纹理 ID（强候选）

- `RenderTargets.resizeIfNeeded(newDepthBufferVersion, newDepthTextureId, ...)` 只有在
  **版本号变化**时才更新 `currentDepthTexture` 并重挂深度（`targets/RenderTargets.java:147-170`）。
- `MixinRenderTarget` 给每个 `RenderTarget` 加 `iris$depthBufferVersion`，只在 `onDestroyBuffers`
  （resize）时自增（`mixin/MixinRenderTarget.java:21-35`）。
- lane FBO 是 `TextureTarget`，版本初始 0；主画面未 resize 时也是 0。
  lane pass 调用 `beginLevelRendering` 时 `resizeIfNeeded(0, laneDepthId, ...)` 判定“没变化”，
  `depthtex0/1/2`、`gdepthtex`、`CenterDepthSampler` 仍是**主相机深度**。
- 这能解释“同朝向正常、反朝向出扇形/毛边”：屏幕空间效果用主相机深度去算 lane 画面。
- 修复候选：lane 绑定 FBO 前把 `iris$depthBufferVersion` 改成与缓存不同的值（反射或软访问器），
  强制重挂 lane 深度；回主画面时再挂回。需要实测确认。

### 建议的最小判定（RGB 分离）

- 场景：主画面正对一块**纯红**方块墙，lane 相机正对一块**纯蓝**方块墙，PIP 覆盖主画面；
- 采样 PIP 中心：若为纯蓝 → 不透明；若出现红/紫 → 有背景混合；
- 再分别用纯 R/G/B 三色重复，排除 shader 色差；
- 同时记录 raw lane 的 alpha 与窗口终帧 RGB，区分“lane 纹理 alpha”与“合成后混合”。

## 3. 问题 B：光影下玩家锚定的扇形/锥形明暗区域

### 现象

- 光影开启时出现，关闭光影没有；
- 区域从玩家位置朝玩家视角方向展开，上下摆头会改变；
- 用户判断与阴影/反射等后处理有关，不是 lane 相机本身。

### 根因（源码确定）：Iris 深度纹理缓存按版本号，lane pass 仍用主相机深度

- `RenderTargets.resizeIfNeeded` 只在 `iris$depthBufferVersion` **变化**时更新
  `currentDepthTexture` 并重挂深度（`targets/RenderTargets.java:147-170`）。
- `MixinRenderTarget` 的版本只在 `destroyBuffers()` 自增（`mixin/MixinRenderTarget.java:20-40`）；
  lane FBO（`TextureTarget`）构造时版本 **0**，未 resize 的窗口也是 **0**。
- lane pass 内 `IrisRenderingPipeline.beginLevelRendering` 传入 lane FBO 的版本 0
  （`IrisRenderingPipeline.java:906-923`），与缓存 0 相等 → **不重挂**；
  `depthtex0/1/2`、`gdepthtex`、`centerDepthSmooth` 仍是**主相机深度**。
- gbuffer 深度在 lane pass 内从不清（`ClearPassCreator` 只清颜色）→ 深度测试/重建都拿主相机深度。
- **同朝向**：lane 深度 ≈ 主 pass 深度 → 基本正常；
  **反朝向**：逐像素比较的是另一条射线的深度 → 大面积深度测试失败 + 深度重建错误
  → 扇形明暗；final 的边缘处理（FXAA / sharpen / TAA / 色差）用错误深度 → 绿色毛边。
- **阴影 pass 本身不是根因**：`ShadowRenderer` 每 lane 重画，中心/视锥跟随 lane 相机
  （`ShadowRenderer.java:354-405`、`MixinLevelRenderer` 的 `renderShadows`），不是主 pass 缓存。

### 受影响的 pass（ComplementaryUnbound）

| pass | 受影响 | 原因 |
|---|---|---|
| gbuffers / terrain | 是 | `depthtex0/1` |
| prepare / deferred（SSAO / SSR / 体积光 / 光照） | 是 | composite 采样器 + 深度重建 |
| composite（雾 / light shafts / 反射） | 是 | `depthtex1/2` |
| final（TAA / FXAA / sharpen / 色差） | 是 | 深度边缘 + 重投影 |
| centerDepthSmooth | 是 | `CenterDepthSampler` |
| shadow map / shadowcomp | 否 | 独立 `ShadowRenderTargets`，每 pass 重画 |

### 决定性 A/B 测试（用户可做）

- 重启客户端，在**第一次播放任何 lane 之前**把窗口 resize 一次（拖动窗口边缘即可），
  再跑 `bulk_09`。
- 原理：窗口 target 版本变成 N，lane FBO 仍是 0 → `resizeIfNeeded` 会重挂 lane 深度；
  lane FBO 已被 `clear(16640)` 清过 → 深度正确。
- 如果扇形/毛边消失 → 实锤本条。

### 修复尝试结果（2026-10-17）

- 已实现并在实机生效：`LaneRenderer` 在每条 lane pass 前把 lane FBO 的
  `iris$depthBufferVersion` +1；日志出现且仅出现一次
  `[lane] Iris 深度重挂已接管…`（18:40:57）。
- **但用户实测：竖缝 / 扇形 / 毛边仍然存在** → 深度版本不是唯一原因，或
  `resizeIfNeeded` 重挂后仍被其他状态覆盖。该假设需要降级为“已排除/不充分”。

### 用户观察（2026-10-17）

- 本次实机确认：**光影里处理阴影的那条链路会影响 lane 画面**。
- 但先不追来源，优先做原始 RGB 分析，确认差异到底落在哪一类像素上；确认前不改代码。

### 下一步：原始 RGB 分析（用户建议）

- 回到历史探针的做法：同一帧取 **lane raw** 与 **窗口终帧**，按 lane alpha 分组
  （`>=254.9` vs `<255`），比较 RGB 平均绝对偏差；再做 R/G/B 分离，判断是否有背景混合。
- 需要能直接读游戏内 framebuffer / 纹理像素，而不是只看截图。

### 修复候选

- lane 绑定 FBO 前，把 lane FBO 的 `iris$depthBufferVersion` 改成与缓存不同的值
  （反射缓存 `Field`，无 Iris 时跳过；失败降级），强制 `resizeIfNeeded` 重挂 lane 深度；
  回主画面时主版本又不同 → 自动重挂主深度。
- 需要量一下重挂成本（`ownedFramebuffers` 数量）与主 pass 恢复是否稳定。
- 合成层 alpha 契约不需要改。

## 4. 失败处理原则（用户口径，2026-10-17）

- 不是禁止 try/catch，而是**不要层层乱包、不要静默吞错**。
- 只在**边界**捕获：tick / render 主循环、服务端线程、网络收发、命令入口、资源重载；
  内部调用不到处 catch。
- 捕获时必须记录足够上下文：包名 / 方向 / 玩家 / 脚本 / 实例 / 阶段 / 关键状态 + 异常栈；
  限频但不要只记 `getMessage()`，首次或每 N 秒带栈。
- 可恢复的降级继续；真正致命的（OOM、GL 上下文丢失、线程断言、数据损坏）不吞，
  照常上抛或走明确终止路径。
- 目标：失败有真实记录、可定位；不立刻崩溃；不把错误包成无法调查的静默。

## 5. 下一步

- 深度修复已落盘（`8836cd7`），待用户实机验证；
- 失败→崩溃全仓审计：`failure-handling-audit.md`（按上述原则给“捕获 / 上抛 / 先补日志”建议）；
- 网络发包防护与断线清理按审计结论一并修，归入 F 轮收口。

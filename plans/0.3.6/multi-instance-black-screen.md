# 多脚本同屏偶发黑屏 · 调查报告（只调查，未修复）

> 2026-10-09。触发背景：用户观看 13 脚本同屏压力演示时偶发**世界画面全黑、HUD/GUI 层完好**；非频发，系统资源压力（前台其他游戏、运存高、区块涌入）下更易出现。
> 本次只做调查与取证，**未改任何代码**。用户已给修复阶段验收标准（见 §五）。

## 一、现象实证（非猜测）

黑屏事件被诊断工具完整捕获（`E:/tmp/icblack/out/`）：

- 截图 `black_20261009-215512-796.png`：**世界层纯黑，GUI 层完全正常**——字幕（12 号 A/B「保持」段）、准星、底部提示、「长按 C 跳过动画」全部清晰渲染在黑底之上。与用户描述逐字吻合。
- 6 个世界探针亮度均值 **0.00/255**，从截图复算偏差 0.00（真黑，非抓屏误读）；持续 **3 帧 / 197ms** 后恢复。
- 事发时刻 = 13 脚本同时开播后 **20.9s**：12 层 master 调色同帧链式叠加（01~10 + 12a/12b 的 ADJUST 全部活跃），13 条相机轨全活、smartCull 关、1 秒内 229 个区块包涌入；用户前台另有游戏抢占资源。

黑在世界层、GUI 完好 = 问题在 GUI 挂点**之前**的世界/调色链（master 调色挂点 = 原版后处理后、GUI 之前），与「调色只动世界画面」的架构一致。

## 二、主嫌疑（按证据强度排序）

### 1. fail-open 的链式 blit（结构性根因，最强）
- `ColorAdjustPass.applyTo` **无条件 `return true`**（ColorAdjustPass.java:315），只有 try/finally 状态还原，**无 catch**；
- master 链末尾的整屏 blit（:202）**无条件信任中转缓冲已被本帧写满**；
- 全链 **0 处 `glGetError` / `checkFramebufferStatus` / `isComplete`**。
- 后果：任何**静默失败**的 pass（资源压力下驱动丢绘制、FBO 不完整、纹理上传失败）都会把「未定义/黑的中转缓冲」铺满整屏。偶发性、资源压力相关、HUD 完好——三个特征全部对上。

### 2. 参数 NaN/Inf 级联（BlackTiming 路线）
- `ScriptValidator` **不在加载/播放路径上**（`ScriptManager.loadFromDir`、`ClientScriptReceiver.handlePlayScript` 均不调 validate），NaN/越界值可达 shader；`checkRange` 也不拒 NaN。
- shader 末行 `clamp(c,0,1)` 把 NaN 变 **0 = 全黑**。
- 事发时刻 08 号脚本正在 `gamma_r=-1` 段（LGG gamma 的潜在除零/负底 pow 奇异点，[INFERENCE] 需对照 shader 公式实证）；短暂 197ms 黑与插值扫过奇异点的时长吻合。

### 3. LUT 采样器单元错位 / 纹理 0（BlackRender 路线）
- `LUT_3D_UNIT=11` 硬编码 vs `ShaderInstance.updateLocations` 的采样器序号压缩可能不一致（唯一「黑而非崩」的单元错位路径）；
- 3D 表不完整/纹理 0 被采样 → `texelFetch=(0,0,0,1)` 直接黑。多层重复绑定增加错位机会。

### 4. lane 合成层同症状路径（次要）
合成层无条件把 lane 纹理按 dest=FULL/opacity=1 上屏；lane 纹理黑 = 整屏黑，且 `LaneRenderer` 从不向调用方报告失败（LaneRenderer.java:314-316）。

## 三、已排除

- **帧内时序/线程竞态**（BlackTiming 负结论）：无跨线程 publish（WebUI/网络均回主线程）、无帧间残留可达路径、层数突变只发生在帧边界。
- **链式 blit 把未写缓冲上屏**（BlackRender）：未写缓冲清屏色是白（RenderTarget.createBuffers 清 (1,1,1,0)），最多白屏不会黑——但嫌疑 1 的 fail-open 让「写失败的黑缓冲」可以上屏。

## 四、诊断产物（可复用）

`E:/tmp/icblack/`（throwaway，未入库）：
- `detect_black.py`：屏幕探针轮询黑屏检测 + 事发时自动导出截图与 latest.log 尾部 300 行（gdi 后端 6~15ms/帧，自带截图复算交叉校验）。
- `stress_scripts/` + `make_stress_scripts.py`：13 支脚本 delay 全 0 的同时播压力副本（仓库原件不动）。
- `RUNBOOK.md`：完整实验步骤。事件产物 `out/black_20261009-215512-796.{png,log}`、`events.tsv`。

## 五、修复方向（待用户拍板后执行；本次不动手）

用户裁决（2026-10-09）：**渲染错误 fail-safe = 返回原始画面（直通），绝不能输出 0/黑**。据此的方向清单：

1. **fail-safe 直通改造**：链式施加每步校验（至少 blit 前验证本帧已写、加 glGetError/checkFramebufferStatus），失败 → 该层/整链直通原始画面，不把未定义缓冲上屏。
2. **参数防 NaN**：加载/播放路径接入校验或钳制（NaN/Inf → 恒等），堵住 validator 不在运行路径的缺口。
3. **LUT 绑定自检**：采样器单元错位检测 + 纹理完整性守卫。
4. **lane 合成失败直通**：lane 渲染失败时不上屏（保持基画面）。

## 六、遗留

- 嫌疑 2 的 LGG gamma 奇异点需对 `ic_color_adjust.fsh` 的 gamma 公式实证（是否除零/负底 pow）。
- 偶发问题的复现率：本轮 5 分钟压力窗口捕获 1 次（13 脚本同时播 + 资源压力）；单脚本播放未见。修复验证时用同一套检测器回归。

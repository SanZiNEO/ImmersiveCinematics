# AUDIO / ADJUST 轨道对象

> 状态：未实现
> 范围：AUDIO 对象与片段级 value-only 字段、ADJUST 59 通道对象、曲线/LUT/混合的 value-only 定义、lane 与 master 的边界
> 关联：`object-model.md`、`runtime-evaluation.md`；语义参考 `complete/0.3.6/A-color/screen-color-adjust.md`、`D-transition/color-mask-execution.md`、`docs/SCRIPT_FORMAT.md` §8/§11

## 一、摘要

AUDIO 的 `volume`/`x`/`y`/`z` 是对象；`sound`/`source`/`pitch`/`file_loop`/`fade_in`/`fade_out`/`attenuation`/`position_mode`/`category` 是片段级 value-only 字段。ADJUST 的 59 个通道全部是对象，曲线/LUT/`blend_mode` 是 value-only 字段。运行时一个 `SampledClip` 同时供音频实例参数和调色参数包使用；`ColorAdjustSampler` 不再扫描关键帧。AUDIO 的 `loop` 改名为 `file_loop`，与片段执行循环 `loop`/`loop_count` 分开。

## 二、为什么做

### 2.1 问题

- AUDIO 的 `loop` 同时表示“音频文件循环”和潜在的“片段执行循环”；字段语义冲突。
- AUDIO 的 `volume`/`x/y/z` 各自 `interpolateFloat`，与对象模型不统一。
- ADJUST 的 59 个通道靠 `ColorAdjustSampler.channel/strength` 逐个读关键帧；新增通道要改 sampler/schema/params/fsh 多处。
- `AdjustTrackPlayer` 对无限片段把 local time 冻结在 0，和 OVERLAY/LETTERBOX/AUDIO 的无限片段行为不一致。
- 相机 lane 调色与 master ADJUST 都走 `ColorAdjustSampler`，但采样时间/循环口径可能不同。

### 2.2 目标

- AUDIO 的每个可调量是字段；`file_loop` 与执行 `loop` 分离。
- ADJUST 的 59 个通道全部对象化；曲线/LUT/混合模式仍是 value-only 字段。
- 一个 `SampledClip` 供音频/调色消费；不再逐字段采样。
- 无限片段的 local time 统一：时间前进，不冻结。
- 保持 A 轮调色语义/着色器栈顺序；本轮只换数据来源。

### 2.3 非目标

- 不改变调色数学/着色器顺序；`screen-color-adjust.md` 是语义基准。
- 不把 `rgb_curve`/`lut`/`blend_mode` 做成 animated/event；它们是 value-only 字段。
- 不改变音频文件加载/OpenAL 播放；只换参数来源。
- 不实现新的音频衰减模型；`attenuation` 的现有行为见 §4.4。

## 三、方案

### 3.1 AUDIO 对象

| 对象路径 | 类型 | 插值 | 缺省 | 说明 |
|---|---|---|---|---|
| `volume` | float | linear | 1.0 | 本字段的 `value` 是基量；0~1 或更高？保持现有范围 |
| `x` / `y` / `z` | float | linear | 0 | 相对/绝对位置，由 value-only `position_mode` 决定 |

- `volume` 是 animated 字段；它的 `value` 是基量（缺省 1.0），没有 keyframes 时就是基量。
- `x/y/z` 缺省 0；`position_mode=relative` 时是相对监听器/玩家的偏移，`absolute` 时是世界坐标。
- `pitch` 是 value-only 字段，不可关键帧；`file_loop` 是音频文件循环，不是片段执行循环。
- `fade_in`/`fade_out` 是 value-only 包络；执行边界不自动重计时，见 `loop-execution.md` §3.5。
- `category=music` 强制相对/无衰减/玩家位置；`ambient` 使用衰减和位置。保持现行。
- `meta.listener`（`camera`/`player`）不是轨道字段；由 `AudioListenerController` 读脚本 meta。

### 3.2 AUDIO 片段级 value-only 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `sound` | string | 声音 id/路径，必填 |
| `source` | enum `file`/`minecraft` | 来源 |
| `pitch` | float | 播放音高；不可关键帧 |
| `file_loop` | bool | 音频文件循环（原 `loop` 改名） |
| `fade_in` / `fade_out` | float | 秒；value-only 包络 |
| `attenuation` | enum `none`/`linear`/`inverse` | 衰减；`inverse` 的现行行为见 §4.4 |
| `position_mode` | enum `relative`/`absolute` | x/y/z 解释 |
| `category` | enum `music`/`ambient` | 类别 |

- 规则声明属于脚本级独立集合，通过片段引用定位，不是 AUDIO 数值属性；本轨道字段表不承载规则容器，也不在 clip 上新增 `rules`。规则的时间门、条件、门前事实采集、实例级挂起、事实接力与延迟动作由规则集合负责；本轨道只提供片段局部时间轴与对象关键帧。
- 片段执行循环使用公共 `loop`/`loop_count`/`loop_mode`；`pingpong` 在 AUDIO 上 validator 拒绝（音频反向播放没有意义）。
- `file_loop` 与执行 `loop` 可以同时存在：执行次数决定片段被调度几次；音频实例仍按现有循环设计复用——跨执行边界仍然活跃的实例不重启，不再活跃的实例下次执行重新起；`file_loop` 只控制实例内部的文件循环。
- `fade_in`/`fade_out` 是 value-only 包络，不按执行次数自动重计时；是否重新淡入由音频实例是否重建决定。

### 3.3 ADJUST 对象（59 通道）

全部通道为 `float`、采用 `linear`；缺省除强度类为 1 外均为 0。下表按功能分组，既不是 JSON 字段的执行顺序，也不由参数包排列决定着色器运算；通道数学按 A 轮操作栈。

| 组 | 对象路径 | 缺省 | 说明 |
|---|---|---|---|
| 基础校色 | `exposure`, `contrast`, `highlights`, `shadows`, `whites`, `blacks` | 0 | exposure -5~5，其余 -1~1 |
| HSL | `hue`, `saturation`, `vibrance`, `lightness` | 0 | -1~1 |
| 白平衡 | `temperature`, `tint` | 0 | -1~1 |
| 通道系数 | `red`, `green`, `blue` | 0 | -1~1，乘性 |
| 通道混合 | `mix_rr`, `mix_rg`, `mix_rb`, `mix_gr`, `mix_gg`, `mix_gb`, `mix_br`, `mix_bg`, `mix_bb` | 0 | 单位阵 + 参数矩阵 |
| 曲线强度 | `curve_strength`, `r_curve_strength`, `g_curve_strength`, `b_curve_strength` | 1 | 0~1 |
| Lift/Gamma/Gain | `lift_r/g/b`, `gamma_r/g/b`, `gain_r/g/b` | 0 | -1~1 |
| LUT 强度 | `lut_strength` | 1 | 0~1；LUT 本身是 value-only `lut` 字段 |
| Hue 曲线强度 | `hv_h_strength`, `hv_s_strength`, `hv_l_strength`, `lv_s_strength`, `sv_s_strength`, `sv_l_strength` | 1 | 0~1 |
| 六色带 | `hue_red`, `sat_red`, `hue_yellow`, `sat_yellow`, `hue_green`, `sat_green`, `hue_cyan`, `sat_cyan`, `hue_blue`, `sat_blue`, `hue_magenta`, `sat_magenta` | 0 | -1~1 |
| 风格化 | `grayscale`, `invert` | 0 | 0~1 |
| 层级混合 | `blend_amount` | 1 | 0~1；仅 ADJUST master |

- 59 = 47 标量通道 + 10 曲线强度（4 曲线 + 6 hue 曲线）+ `lut_strength` + `blend_amount`。按现行 schema 逐项计数：6+4+2+3+9+4+9+1+6+12+2+1 = 59；文档以 schema 顺序为准。
- `blend_amount` 在全部 19 步之后施加；`blend_mode` 是 value-only 枚举字段。保持 A 轮语义。
- 相机片段自带 57 个调色通道（与 ADJUST 同名同缺省，但没有 `lut_strength`/`blend_amount`），作用于该相机 lane；ADJUST 作用于合成输出（master）。两者都从各自的 `SampledClip` 读，互不覆盖。

### 3.4 ADJUST 片段级 value-only 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `rgb_curve` / `r_curve` / `g_curve` / `b_curve` | color_curve | 曲线定义一次；强度对象控制混合 |
| `hv_h_curve` / `hv_s_curve` / `hv_l_curve` / `lv_s_curve` / `sv_s_curve` / `sv_l_curve` | color_curve | 六条 hue 曲线 |
| `lut` | string | `resource/` 下的 `.cube`；缺省 null |
| `lut_input_gamma` | float | >0，缺省 1 |
| `blend_mode` | enum | `normal`/`multiply`/`screen`/`soft_light`/`overlay` |

- 规则声明属于脚本级独立集合，通过片段引用定位，不是 ADJUST 通道属性；本轨道字段表与调色参数包都不承载规则容器。规则的时间门、条件、门前事实采集、实例级挂起、事实接力与延迟动作由规则集合负责。
- 路径曲线属于脚本级曲线集合，绑定片段内位置对象/对象组的两个相邻关键帧；`rgb_curve`/`r_curve`/`g_curve`/`b_curve` 与六条 hue 曲线是调色结构定义，不属于该集合，也不因曲线集合改变。
- 曲线/LUT 是结构/文件引用，不随时间变；只有强度是对象。
- `lut_input_gamma` 是 value-only 常量；不关键帧。
- `blend_mode` 是 value-only 枚举；`blend_amount` 是对象。

### 3.5 执行

- `AudioTrackPlayer` 从 `SampledClip` 读 `volume`/`x/y/z`，构造/更新 `CinematicAudioInstance`；不再 `interpolateFloat`。
- `AdjustTrackPlayer` 从 `SampledClip` 读 59 通道，填 `ColorAdjustParams`；不再 `ColorAdjustSampler.channel/strength` 扫描关键帧。
- `ColorAdjustSampler` 改为“从 `SampledClip` 取槽位”的薄读取器，或直接删除，由 `AdjustTrackPlayer`/`ScriptLaneDriver` 填参数包。
- 相机 lane 调色：`ScriptLaneDriver` 从相机 `SampledClip` 读 57 通道；与相机状态、mask 同一 `t`。
- ADJUST 无限片段：local time 不再冻结在 0；按 `loop-execution.md` 的无限片段规则前进（`duration < 0` = 无窗口结束，时间持续增长）。
- AUDIO 无限片段：现有实现已特判 `duration < 0`；统一到 `loop-execution.md` 的无限规则。

### 3.6 静态片段

- 没有 keyframes 的 AUDIO/ADJUST 片段合法：字段用 `value`/默认值。
- ADJUST 静态片段 = 恒定调色；AUDIO 静态片段 = 恒定音量/位置。

## 四、技术细节

### 4.1 核心模型

```text
AudioClipRuntime
  SampledClip sample
  CinematicAudioInstance instance

AdjustClipRuntime
  SampledClip sample
  ColorAdjustParams params
```

- `SampledClip` 的 audio 槽位：volume/x/y/z；adjust 槽位：59 个 float。
- 参数包、schema 与具名 shader uniform 的通道映射必须一致；字段排列不是实际运算顺序。片段声明要有哪些效果，画面叠放按轨道层级与播放实例执行顺序，通道内部数学按 A 轮固定操作栈；不把 JSON 顺序解释成作者指定的滤镜链。
- `CinematicAudioInstance` 仍持有 OpenAL 源/衰减/循环；参数来自采样。

### 4.2 契约

- **创建**：clip 加载时构建 runtime；采样器编译；参数包预分配。
- **销毁**：随 clip 销毁；音频实例停止/释放。
- **状态归谁**：音频实例/OpenAL 状态归 `AudioTrackPlayer`；调色参数包归 `AdjustTrackPlayer`；采样器纯函数。
- **边界**：AUDIO 不碰调色；ADJUST 不碰音频；两者都只读 `SampledClip`。

### 4.3 数据形态

- AUDIO `volume` 0~1（或现有上限）；`x/y/z` 世界/相对坐标；`position_mode` 决定。
- ADJUST 通道范围按 schema `min/max`；validator 校验，运行时不钳制。
- `blend_amount` 0~1；`lut_strength` 0~1；曲线强度 0~1。
- 曲线/LUT 结构不进入 `SampledClip`；只传引用/参数包。

### 4.4 已知边界：`attenuation=inverse`

现行 `CinematicAudioInstance.setAttenuation` 把除 `none` 外的所有值映射为 LINEAR；`inverse` 实际按线性衰减。本轮对象化不改变音频衰减数学：

- 选项 A（推荐在实现时）：补真正的反距离衰减；validator 保留 `inverse`。
- 选项 B：从枚举删除 `inverse`，避免“写了但没用”的字段；迁移把旧 `inverse` 改为 `linear` 并记录。
- 在决定前，validator 对 `inverse` 给警告“当前按 linear 处理”；文档不把 `inverse` 描述成已实现。

### 4.5 边界

- 不改变 `screen-color-adjust.md` 的 19 步/曲线/LUT 语义；只换数据来源。
- 不改变 `AudioListenerController` 的 `meta.listener` 规则。
- 不改变 `category=music` 的强制相对/无衰减。
- 不把 `pitch` 对象化；本轮保持 value-only 字段。
- 不把 `fade_in`/`fade_out` 对象化；它们是 value-only 包络。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| AUDIO 继续用 `loop` 表示文件循环 | 片段执行循环另起字段 | 两个 `loop` 语义冲突；作者/工具易混 | 改名 `file_loop` |
| ADJUST 保持 59 字段快照 | 一个 keyframe 携带所有通道 | 加通道要动所有关键帧；不能单独调一个通道 | 全部对象化 |
| 曲线/LUT 也对象化 | 曲线控制点/文件随时间变 | 结构复杂、shader 参数多、编辑器重 | 曲线定义固定，强度可动画 |
| `blend_mode` 对象化 | 混合模式随时间切换 | 离散切换会闪；参数包/着色器分支复杂 | 保持 value-only 字段 |
| 无限片段冻结 local time | 保持 `AdjustTrackPlayer` 现状 | 与 OVERLAY/AUDIO 不一致；动画停住 | 统一时间前进 |

**本方案的缺点**：

- ADJUST 59 个字段让原始 JSON 很长；作者入口是编辑器，手写脚本不是推荐路径。
- `volume`/`color.r/g/b` 的动态默认（字段 `value` 基量/基色）需要特判，schema 静态 `default` 表达不了。
- 删除 `ColorAdjustSampler` 的扫描逻辑会触及相机 lane 调色/master 调色两条路径；需要回归。
- `attenuation=inverse` 仍是未决边界；文档只能标注，不能假装已实现。
- AUDIO 执行循环 + `file_loop` 双层循环的听觉结果需要实测；作者可能误用。

## 六、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 未实现 | — | — |
